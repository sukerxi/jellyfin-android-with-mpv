package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import android.graphics.Bitmap
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
 * ## Where the pixels come from
 *
 * Not from mpv's `screenshot`: that encodes through libavcodec, and the libmpv build shipped with
 * this app disables FFmpeg's encoders and muxers, so it can never write a file. Not from an
 * `ImageReader` either: it demands the GPU produce the exact format it was created with, and
 * devices do not always agree. Frames are read from the render target by [GlFrameReader], which
 * takes whatever the video output produces and reads it back with `glReadPixels` - see that class
 * for the details.
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

    /** Event ids as mpv emits them, for [awaitEvent]. Entries are consumed, not inspected. */
    private val events = LinkedBlockingQueue<Int>()

    private var frameReader: GlFrameReader? = null

    /** The window currently handed to the video output, if any. */
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
     * target of [viewportWidth] x [viewportHeight] pixels.
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

        attachRenderTarget(viewportWidth, viewportHeight)
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
     * PLAYBACK_RESTART is mpv's "the first frame was displayed" signal; reading the render target
     * before it returns nothing. Pausing afterwards keeps exactly that frame.
     */
    fun awaitFirstFrame(timeoutMillis: Long) {
        awaitEvent(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, FILE_LOAD_TIMEOUT_MILLIS, "打开视频源超时")
        awaitEvent(MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART, timeoutMillis, "等待首帧超时")
        setProperty("pause", true)
        // The frame is on screen before the event is emitted, but the video output presents it on
        // its own thread; give it a moment so the read-back cannot race it.
        Thread.sleep(FRAME_SETTLE_MILLIS)
    }

    /**
     * Reads the frozen frame out of the render target.
     *
     * A candidate gets the whole [timeoutMillis] because a hardware decoder may still be finishing
     * the seek, and an offscreen target can miss the first few swaps after the output is created.
     * When nothing arrives at all, the error explains why in mpv's terms (see [noFrameReason]).
     */
    fun captureFrame(timeoutMillis: Long): Bitmap {
        val reader = frameReader ?: error("渲染目标未初始化")
        return reader.captureFrame(timeoutMillis) ?: error(noFrameReason())
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
        surface = null
        // Releases the surface, the texture, the GL objects and the EGL context.
        runCatching { frameReader?.close() }
        frameReader = null
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
     * Points mpv's video output at an offscreen render target of [width] x [height] pixels.
     *
     * The target is sized in pixels to match the real viewport, so the rendered frame has the same
     * shape the user is looking at - a dp-sized target was what stretched portrait video earlier.
     */
    private fun attachRenderTarget(width: Int, height: Int) {
        val reader = GlFrameReader(width, height)
        frameReader = reader
        surface = reader.surface
        // Size first: attaching is what makes the video output (re)create itself, so the size has
        // to be known by then or the first frames are rendered for the previous viewport.
        setProperty("android-surface-size", "${width}x$height")
        mpv?.attachSurface(reader.surface)
    }

    /**
     * Explains an empty render target in terms the user can act on.
     *
     * `vo-configured=no` means the configuration killed the video output itself (an unsupported
     * `vo`, a GPU option the device rejects) - that is a real result of the comparison, not a
     * bug in the capture path, so it must not read like one.
     */
    private fun noFrameReason(): String {
        val configured = runCatching { mpv?.getPropertyBoolean("vo-configured") }.getOrNull()
        val vo = runCatching { mpv?.getPropertyString("vo") }.getOrNull()?.takeIf { it.isNotBlank() }
        Timber.w("mpv bench: no frame, vo=%s vo-configured=%s", vo, configured)
        return when {
            configured == false -> "该配置无法初始化视频输出（vo=${vo ?: "未知"}）"
            vo != null -> "mpv 没有输出画面（vo=$vo）"
            else -> "mpv 没有输出画面"
        }
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

    private companion object {
        const val CONFIG_DIR = "config"
        const val CONFIG_FILE_NAME = "mpv.conf"

        const val MAX_QUEUED_EVENTS = 64

        /** Network sources need a generous opening budget; a transcode can take a while to start. */
        const val FILE_LOAD_TIMEOUT_MILLIS = 30_000L

        /** Pause lands after the frame was presented; the output needs a moment to hand it over. */
        const val FRAME_SETTLE_MILLIS = 120L
    }
}
