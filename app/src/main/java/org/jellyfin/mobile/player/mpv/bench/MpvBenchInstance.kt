package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
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
 * ## Why frames are read from the render target, not from mpv
 *
 * mpv writes screenshots through libavcodec: `screenshot` / `screenshot-to-file` encode the frame
 * with the `png`/`mjpeg` encoder and mux it to a file. The libmpv build shipped with this app
 * disables FFmpeg's encoders and muxers (`--disable-{muxers,encoders,...}`), so *every* screenshot
 * command fails, for every format, with no file written and nothing but an mpv log line to show for
 * it - which is exactly the "mpv 没有生成截图" failure.
 *
 * The frame is therefore taken from the video output itself: mpv renders into an [ImageReader]
 * backed surface, and the finished buffer is read back as pixels ([captureFrame]). That skips
 * encoding entirely, and it captures what the GPU actually produced, which is the thing a render
 * configuration comparison is about.
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

    private var renderTarget: ImageReader? = null
    private var surface: Surface? = null
    private var mpv: MPVLib? = null

    /** Buffer geometry of the current render target, kept so it can be recreated. */
    private var targetWidth = 0
    private var targetHeight = 0

    /**
     * Pixel format handed to the video output.
     *
     * RGBA is the safe default, but whether the GL driver accepts it as a render target is up to the
     * device; [rotateRenderTarget] switches to RGBX (the format video outputs usually ask for) when
     * a frame does not show up.
     */
    private var pixelFormat = PixelFormat.RGBA_8888

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
     * before it returns an empty buffer. Pausing afterwards keeps exactly that frame - the buffer
     * stays queued in the [ImageReader] and is read by [captureFrame].
     */
    fun awaitFirstFrame(timeoutMillis: Long) {
        awaitEvent(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, FILE_LOAD_TIMEOUT_MILLIS, "打开视频源超时")
        awaitEvent(MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART, timeoutMillis, "等待首帧超时")
        setProperty("pause", true)
        // The frame is on screen before the event is emitted, but the video output flips it to the
        // consumer on its own thread; give it a moment so the read-back cannot race it.
        Thread.sleep(FRAME_SETTLE_MILLIS)
    }

    /**
     * Reads the frozen frame out of the render target.
     *
     * The buffer is taken as pixels directly: no encoder is involved, so this cannot fail the way
     * mpv's own screenshot command does. A candidate gets the whole [timeoutMillis] because a
     * hardware decoder may still be finishing the seek, and the render target is rebuilt once in the
     * other pixel format while waiting - an offscreen surface can miss its first swap after the
     * video output is (re)created, and a paused output then just sits there without a buffer to hand
     * over.
     */
    fun captureFrame(timeoutMillis: Long): Bitmap {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var rotated = false
        while (true) {
            val target = renderTarget ?: error("渲染目标未初始化")
            target.acquireLatestImage()?.let { image ->
                return try {
                    image.toBitmap()
                } finally {
                    image.close()
                }
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) error(noFrameReason())
            if (!rotated && remaining <= timeoutMillis - ROTATE_AFTER_MILLIS) {
                rotateRenderTarget()
                rotated = true
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
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
        surface = null
        runCatching { renderTarget?.close() }
        renderTarget = null
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
     * Points mpv's video output at an offscreen [ImageReader] of [width] x [height].
     *
     * [ImageReader] is used instead of a `SurfaceTexture` because the consumer side has to read the
     * pixels back: a texture can only be sampled by OpenGL, an [ImageReader] hands the finished
     * buffer over as bytes. The buffer is sized in pixels to match the real viewport, so the
     * rendered frame has the same aspect the user is looking at.
     */
    private fun attachRenderTarget(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        val target = createImageReader(width, height)
        val targetSurface = target.surface
        renderTarget = target
        surface = targetSurface
        // Size first: attaching is what makes the video output (re)create itself, so the size has
        // to be known by then or the first frames are rendered for the previous viewport.
        setProperty("android-surface-size", "${width}x$height")
        mpv?.attachSurface(targetSurface)
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ImageReader.newInstance(width, height, pixelFormat, MAX_BUFFERED_FRAMES)
        }
        // The GPU writes the frame, we read it: without the color-output/sampled usage flags some
        // devices hand out buffers the GL driver refuses to render into.
        val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_CPU_READ_OFTEN
        return ImageReader.newInstance(width, height, pixelFormat, MAX_BUFFERED_FRAMES, usage)
    }

    /**
     * Rebuilds the render target with the other pixel format.
     *
     * Nothing arrived on the current one, which means the video output never handed a buffer over -
     * usually because the format it wants and the one the reader was created with disagree. A fresh
     * target with a fresh output is the only way to find out, and it also covers an output that
     * simply missed its first swap.
     */
    private fun rotateRenderTarget() {
        val instance = mpv ?: return
        if (targetWidth <= 0 || targetHeight <= 0) return
        val next = if (pixelFormat == PixelFormat.RGBA_8888) PixelFormat.RGBX_8888 else PixelFormat.RGBA_8888
        Timber.w("mpv bench: no frame, recreating the render target as format %d", next)
        runCatching { instance.detachSurface() }
        runCatching { surface?.release() }
        surface = null
        runCatching { renderTarget?.close() }
        renderTarget = null
        pixelFormat = next
        attachRenderTarget(targetWidth, targetHeight)
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

    /**
     * Converts a rendered buffer to a bitmap.
     *
     * Rows can be padded (`rowStride`), so the copy is done per row instead of assuming a packed
     * `width * 4` layout. Alpha is forced opaque: the video output is free to leave the alpha
     * channel at zero, and a transparent frame would render as nothing in the comparison views.
     */
    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: error("渲染帧没有像素数据")
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val imageWidth = width
        val imageHeight = height
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        // Rows are padded to rowStride, but the *last* row is often handed over unpadded, so the
        // minimum is one full stride less plus a plain row.
        val minimumSize = rowStride * (imageHeight - 1) + imageWidth * pixelStride
        require(bytes.size >= minimumSize) {
            "渲染帧大小异常：${bytes.size} < $minimumSize"
        }

        val pixels = IntArray(imageWidth * imageHeight)
        var index = 0
        for (y in 0 until imageHeight) {
            var offset = y * rowStride
            repeat(imageWidth) {
                val r = bytes[offset].toInt() and 0xFF
                val g = bytes[offset + 1].toInt() and 0xFF
                val b = bytes[offset + 2].toInt() and 0xFF
                pixels[index++] = OPAQUE_ALPHA or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
        }
        return Bitmap.createBitmap(pixels, imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val CONFIG_DIR = "config"
        const val CONFIG_FILE_NAME = "mpv.conf"

        const val MAX_QUEUED_EVENTS = 64

        /**
         * Frames kept by the render target. One is the frame being read, the extra one lets the
         * video output swap without stalling on a buffer we have not consumed yet.
         */
        const val MAX_BUFFERED_FRAMES = 3

        /** Network sources need a generous opening budget; a transcode can take a while to start. */
        const val FILE_LOAD_TIMEOUT_MILLIS = 30_000L

        const val POLL_INTERVAL_MILLIS = 20L
        const val FRAME_SETTLE_MILLIS = 120L

        /** How long an empty render target is given before it is rebuilt in the other format. */
        const val ROTATE_AFTER_MILLIS = 1_500L

        const val OPAQUE_ALPHA = 0xFF shl 24
    }
}
