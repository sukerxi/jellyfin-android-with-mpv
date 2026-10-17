package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import org.jellyfin.mobile.player.mpv.MpvCore
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A throwaway libmpv instance that renders exactly one frame of a scene.
 *
 * Why a second native instance instead of the player's one: the player's instance owns the sound,
 * the media session and the visible surface, and its option set *is* the baseline of the
 * comparison. Reusing it meant destroying and recreating it while playback was running (which left
 * the player without a video output) and then changing options on it (which could not be undone,
 * because applying a profile never resets another profile's options). An instance per candidate
 * gives every candidate the same, clean starting point - mpv reads its config file exactly once, at
 * startup - and leaves the player completely untouched.
 *
 * The instance is configured through its own `mpv.conf` with the same base options as the player
 * (see [MpvCore.baseOptions]), so what it renders is what playback would render.
 *
 * Everything here is "issue an IPC call, then wait for the event that proves it happened". The
 * blocking waits are safe because the caller runs on a background thread; they are also what makes
 * the sequence deterministic, and determinism is the whole point of a pixel comparison.
 *
 * Lives in the caller's [workspace] directory, which is deleted again on [close].
 */
internal class MpvBenchInstance(
    private val appContext: Context,
    private val workspace: File,
) : Closeable {

    private val configDir = File(workspace, CONFIG_DIR).apply { mkdirs() }
    private val shotDir = File(workspace, SHOTS_DIR).apply { mkdirs() }

    /** Event ids as mpv emits them, for [awaitEvent]. Entries are consumed, not inspected. */
    private val events = LinkedBlockingQueue<Int>()

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var mpv: MPVLib? = null

    private val observer = object : MPVLib.EventObserver {
        override fun event(eventId: Int) {
            // Never block here: this runs on mpv's own event thread.
            if (events.size >= MAX_QUEUED_EVENTS) events.poll()
            events.offer(eventId)
        }

        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun eventProperty(property: String, value: String) = Unit
    }

    /**
     * Creates the native instance and points it at [configText], rendering into an offscreen
     * surface of [viewportWidth] x [viewportHeight] pixels.
     */
    fun open(configText: String, viewportWidth: Int, viewportHeight: Int) {
        writeConfigFile(configText)
        val instance = MPVLib.create(appContext) ?: error("无法创建 mpv 实例")
        mpv = instance
        MpvCore.baseOptions(appContext, configDir).forEach { (name, value) ->
            instance.setOptionString(name, value)
        }
        instance.init()
        instance.addObserver(observer)

        // The benchmark must not play sound, must not draw subtitles or the OSD, and must keep the
        // frame on screen once it is there. These are set after init so no candidate config can
        // accidentally override them; none of them changes how the video itself is rendered.
        setProperty("ao", "null")
        setProperty("aid", "no")
        setProperty("sid", "no")
        setProperty("osd-level", "0")
        setProperty("keep-open", "yes")
        setProperty("screenshot-format", "png")
        setProperty("screenshot-sw", "no")
        setProperty("screenshot-directory", shotDir.path)

        attachOffscreenSurface(viewportWidth, viewportHeight)
    }

    /** Opens [sourceUrl] at [positionMs]. Seeking at load keeps it to a single decode pass. */
    fun load(sourceUrl: String, positionMs: Long) {
        val instance = mpv ?: error("mpv 未初始化")
        instance.command(
            arrayOf(
                "loadfile",
                sourceUrl,
                "replace",
                "0",
                "start=+%.3f".format(Locale.ROOT, positionMs / 1000.0),
            ),
        )
    }

    /**
     * Waits until the first frame of the scene is on screen, then freezes it.
     *
     * PLAYBACK_RESTART is mpv's "the first frame was displayed" signal; a screenshot taken before
     * it captures an empty output. Pausing afterwards keeps exactly that frame.
     */
    fun awaitFirstFrame(timeoutMillis: Long) {
        awaitEvent(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, FILE_LOAD_TIMEOUT_MILLIS, "打开视频源超时")
        awaitEvent(MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART, timeoutMillis, "等待首帧超时")
        setProperty("pause", true)
        // The frame is on screen before the event is emitted, but the video output flips it to the
        // consumer on its own thread; give it a moment so the screenshot cannot race it.
        Thread.sleep(FRAME_SETTLE_MILLIS)
    }

    /** Takes the frozen frame out of the video output. */
    fun captureFrame(timeoutMillis: Long): Bitmap {
        clearShots()
        var file = takeScreenshot(timeoutMillis)
        if (file == null) {
            // Last resort. Grabbing the frame through the video output is the only way to capture
            // what the render configuration produced, but not every GPU backend exposes it; a
            // software screenshot of the same frozen frame still answers "did this change the
            // picture?" - it just also runs it through the software scaler.
            setProperty("screenshot-sw", true)
            clearShots()
            file = takeScreenshot(timeoutMillis)
        }
        val shot = file ?: error("mpv 没有生成截图")
        val bitmap = BitmapFactory.decodeFile(shot.path) ?: error("无法解码截图")
        shot.delete()
        return bitmap
    }

    override fun close() {
        val instance = mpv
        mpv = null
        if (instance != null) {
            runCatching { instance.command(arrayOf("stop")) }
            // Only detach when a surface was really attached: the native detach releases the
            // global reference it stored, so detaching twice (or without an attach) is not safe.
            if (surface != null) runCatching { instance.detachSurface() }
            runCatching { instance.destroy() }
        }
        runCatching { surface?.release() }
        runCatching { surfaceTexture?.release() }
        surface = null
        surfaceTexture = null
        runCatching { workspace.deleteRecursively() }
    }

    private fun writeConfigFile(configText: String) {
        val configFile = File(configDir, CONFIG_FILE_NAME)
        val content = configText.trim()
        if (content.isEmpty()) {
            configFile.delete()
        } else {
            configFile.writeText(content + "\n")
        }
    }

    /**
     * Attaches an offscreen surface to this instance.
     *
     * A [SurfaceTexture] is used because the frame only has to exist as pixels; nothing displays it
     * while it is rendered, and the comparison views draw the captured bitmap instead. The buffer
     * is sized in pixels to match the real viewport, so the rendered frame has the same aspect the
     * user is looking at.
     */
    private fun attachOffscreenSurface(width: Int, height: Int) {
        val texture = SurfaceTexture(0)
        texture.setDefaultBufferSize(width, height)
        val offscreen = Surface(texture)
        surfaceTexture = texture
        surface = offscreen
        // Size first: attaching is what makes the video output (re)create itself, so the size has
        // to be known by then or the first frames are rendered for the previous viewport.
        setProperty("android-surface-size", "${width}x$height")
        mpv?.attachSurface(offscreen)
    }

    private fun setProperty(name: String, value: Any) {
        val instance = mpv ?: return
        try {
            when (value) {
                is String -> instance.setPropertyString(name, value)
                is Boolean -> instance.setPropertyBoolean(name, value)
                is Int -> instance.setPropertyInt(name, value)
                is Double -> instance.setPropertyDouble(name, value)
                else -> instance.setPropertyString(name, value.toString())
            }
        } catch (e: Exception) {
            // A rejected option must not abort a run: the frame is still comparable without it.
            Timber.w(e, "mpv bench: could not set %s", name)
        }
    }

    /** Waits for [expectedId], failing the run on a timeout or on playback ending prematurely. */
    private fun awaitEvent(expectedId: Int, timeoutMillis: Long, message: String) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) error(message)
            val eventId = events.poll(remaining, TimeUnit.MILLISECONDS) ?: error(message)
            if (eventId == expectedId) return
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                error("视频源无法解码（mpv 在出画前就结束了）")
            }
        }
    }

    /** Empties the screenshot directory so the next file that appears is the one being waited for. */
    private fun clearShots() {
        shotDir.listFiles()?.forEach { it.delete() }
    }

    /** Writes one screenshot and returns the file, or null when mpv did not produce one. */
    private fun takeScreenshot(timeoutMillis: Long): File? {
        if (screenshotToFileSupported) {
            // Deterministic: mpv writes exactly the file we named instead of us scanning a directory.
            val target = File(shotDir, FRAME_FILE)
            mpv?.command(arrayOf("screenshot-to-file", target.path, "video"))
            waitForScreenshot(PROBE_TIMEOUT_MILLIS)?.let { return it }
            screenshotToFileSupported = false
        }
        mpv?.command(arrayOf("screenshot"))
        return waitForScreenshot(timeoutMillis)
    }

    /**
     * Polls [shotDir] for a finished screenshot.
     *
     * The size is read twice because mpv writes the file in place: a file that is still growing
     * cannot be decoded, and returning it would fail the candidate for the wrong reason.
     */
    private fun waitForScreenshot(timeoutMillis: Long): File? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val candidate = shotDir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
            if (candidate != null) {
                val size = candidate.length()
                Thread.sleep(SIZE_SETTLE_MILLIS)
                if (candidate.length() == size && size > 0) return candidate
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return null
    }

    private companion object {
        const val CONFIG_DIR = "config"
        const val CONFIG_FILE_NAME = "mpv.conf"
        const val SHOTS_DIR = "shots"
        const val FRAME_FILE = "frame.png"

        const val MAX_QUEUED_EVENTS = 64

        /** Network sources need a generous opening budget; a transcode can take a while to start. */
        const val FILE_LOAD_TIMEOUT_MILLIS = 30_000L

        /** Screenshot of a frozen frame is local work, so this only needs to cover a slow encoder. */
        const val PROBE_TIMEOUT_MILLIS = 2_000L

        const val POLL_INTERVAL_MILLIS = 20L
        const val SIZE_SETTLE_MILLIS = 25L
        const val FRAME_SETTLE_MILLIS = 120L

        /**
         * Whether the native library knows `screenshot-to-file` (mpv 0.36+). Probed once per
         * process: when it is missing, every candidate would otherwise pay the probe timeout again.
         */
        @Volatile
        var screenshotToFileSupported = true
    }
}
