package org.jellyfin.mobile.player.mpv.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Renders one scene frame with ExoPlayer, so it can be compared against the mpv frame pixel by pixel.
 *
 * The pixels come from the same [GlFrameReader] the mpv path uses: ExoPlayer decodes into a
 * `SurfaceTexture` we own and the frame is read back with `glReadPixels`. Nothing is screen-recorded
 * or re-encoded, so both frames are the decoder output at the same size - which is what makes the
 * difference between the two players meaningful.
 *
 * ## Geometry
 *
 * mpv letterboxes the video into the surface it is given. ExoPlayer does not: normally the layout
 * (`PlayerView`) sizes the surface to the video and pads around it. So the surface here is created
 * at the video's own aspect - exactly what a `PlayerView` does - and the frame is centred on a
 * black canvas of the viewport afterwards. Both engines then produce the same layout, and the black
 * bars cannot show up as differences of their own.
 *
 * The aspect comes from [SceneSpec.sourceWidth] / [SceneSpec.sourceHeight] because ExoPlayer does
 * not report a video size before it has a surface; whatever the player reports after rendering
 * takes precedence, and a correction only costs one rescale in the rare case the two disagree.
 *
 * ## Threading
 *
 * [render] blocks and must be called from a thread that is allowed to wait. ExoPlayer requires all
 * of its calls to happen on the thread it was created on, so the player is created, driven and
 * released on the main thread while the calling thread waits - and the read-back stays on the
 * calling thread, because an EGL context and its texture belong to the thread that created them.
 */
@UnstableApi
internal class ExoFrameRenderer(
    private val context: Context,
    private val mediaSourceFactory: MediaSource.Factory,
) {
    fun render(spec: SceneSpec, viewportWidth: Int, viewportHeight: Int): RenderedFrame {
        val mediaItem = spec.mediaItem ?: error(NO_MEDIA_ITEM_MESSAGE)
        val declared = spec.widthAndHeightOrNull()
        val targetSize = declared?.let { fittedSize(it, viewportWidth, viewportHeight) }
            ?: (viewportWidth to viewportHeight)
        val main = Handler(Looper.getMainLooper())
        val events = PlayerEvents()
        var player: ExoPlayer? = null
        var reader: GlFrameReader? = null

        try {
            // The render target exists before the player does: ExoPlayer does not report a video
            // size before a video output exists, and without one it may not even leave buffering,
            // so the surface is what the player is created with - at the video's own aspect, which
            // is what a PlayerView would give it.
            val frameReader = GlFrameReader(targetSize.first, targetSize.second)
            reader = frameReader
            val renderedBefore = events.renderedFrames

            val instance = callOnMain(main) {
                // Published before it is configured: a failure while configuring still has to be
                // released by `finally`, or every failed comparison would leak a decoder.
                ExoPlayer.Builder(context).build().also { player = it }.apply {
                    // mpv is still playing, and it owns the audio device. Disabling the track types
                    // keeps a second decoder (and a second audio pipeline) out of the comparison.
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    volume = 0f
                    addListener(events)
                    setVideoSurface(frameReader.surface)
                    setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                    // Paused: a paused seek renders the frame at the target position on its own, so
                    // the picture cannot drift away while it is being captured.
                    playWhenReady = false
                    prepare()
                    seekTo(spec.positionMs)
                }
            }
            if (!events.awaitReady(SOURCE_TIMEOUT_MILLIS)) error(SOURCE_TIMEOUT_MESSAGE)
            if (!events.awaitFrameAfter(renderedBefore, FRAME_TIMEOUT_MILLIS)) {
                // A few devices only render while the player is actually playing. Nudge it and pause
                // again immediately: a frame or two of drift still beats an empty comparison.
                Timber.w("exo bench: paused seek rendered no frame, playing briefly")
                callOnMain(main) { instance.playWhenReady = true }
                val rendered = events.awaitFrameAfter(renderedBefore, FRAME_TIMEOUT_MILLIS)
                callOnMain(main) { instance.playWhenReady = false }
                if (!rendered) error(NO_FRAME_MESSAGE)
            }

            val captured = frameReader.captureFrame(CAPTURE_TIMEOUT_MILLIS) ?: error(NO_FRAME_MESSAGE)
            // What the player actually decoded wins over the declared size: a wrong declaration
            // would otherwise letterbox a frame of the wrong aspect, and the bars would read as a
            // picture difference.
            val decoded = callOnMain(main) { instance.videoSize }
            val sourceSize = decoded.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width to it.height }
                ?: declared
            val frame = present(captured, sourceSize, viewportWidth, viewportHeight)
            Timber.d("mpv bench: exoplayer rendered %s, frame %dx%d", sourceSize, frame.width, frame.height)
            return RenderedFrame(
                frame = frame.toRawFrame(),
                effective = buildMap {
                    put("engine", "exoplayer")
                    sourceSize?.let { put("source", "${it.first}x${it.second}") }
                    put("output", "${viewportWidth}x$viewportHeight")
                },
            )
        } finally {
            val instance = player
            if (instance != null) {
                runCatching { runOnMain(main) { instance.release() } }
                    .onFailure { Timber.w(it, "exo bench: could not release the player") }
            }
            // Same thread that created it: the EGL context and the texture belong to this one.
            runCatching { reader?.close() }
        }
    }

    private fun SceneSpec.widthAndHeightOrNull(): Pair<Int, Int>? =
        if (sourceWidth > 0 && sourceHeight > 0) sourceWidth to sourceHeight else null

    /** The largest size with [source]'s aspect that fits inside the viewport. */
    private fun fittedSize(source: Pair<Int, Int>, viewportWidth: Int, viewportHeight: Int): Pair<Int, Int> {
        val sourceAspect = source.first.toFloat() / source.second
        return if (sourceAspect >= viewportWidth.toFloat() / viewportHeight) {
            viewportWidth to (viewportWidth / sourceAspect).roundToInt().coerceAtLeast(MIN_SIDE)
        } else {
            (viewportHeight * sourceAspect).roundToInt().coerceAtLeast(MIN_SIDE) to viewportHeight
        }
    }

    /**
     * Brings the captured frame into the viewport's geometry: scaled to the video's aspect (the
     * decoder stretches to whatever surface it is given) and centred on black.
     */
    private fun present(
        captured: Bitmap,
        sourceSize: Pair<Int, Int>?,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Bitmap {
        val target = sourceSize?.let { fittedSize(it, viewportWidth, viewportHeight) }
            ?: (captured.width to captured.height)
        val scaled = if (captured.width == target.first && captured.height == target.second) {
            captured
        } else {
            Bitmap.createScaledBitmap(captured, target.first, target.second, true)
                .also { captured.recycle() }
        }
        if (scaled.width == viewportWidth && scaled.height == viewportHeight) return scaled

        val padded = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            scaled,
            (viewportWidth - scaled.width) / 2f,
            (viewportHeight - scaled.height) / 2f,
            FILTER_PAINT,
        )
        scaled.recycle()
        return padded
    }

    /**
     * Player callbacks, polled by the waiting thread.
     *
     * Each field has exactly one writer (mpv's main thread) and is read by the waiting one, so a
     * volatile field is enough - no lock is held while the renderer calls into ExoPlayer.
     */
    private class PlayerEvents : Player.Listener {
        @Volatile
        private var ready = false

        @Volatile
        var renderedFrames = 0
            private set

        @Volatile
        private var failure: String? = null

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) ready = true
        }

        override fun onRenderedFirstFrame() {
            renderedFrames++
        }

        override fun onPlayerError(error: PlaybackException) {
            failure = "${error.errorCodeName}（${error.message}）"
        }

        fun awaitReady(timeoutMillis: Long): Boolean = await(timeoutMillis) { ready }

        fun awaitFrameAfter(frames: Int, timeoutMillis: Long): Boolean =
            await(timeoutMillis) { renderedFrames > frames }

        /** Waits for [condition]; false on timeout, throws as soon as the player reported an error. */
        private fun await(timeoutMillis: Long, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                failure?.let { error(ERROR_PREFIX + it) }
                if (condition()) return true
                if (System.currentTimeMillis() >= deadline) return false
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun <T> callOnMain(handler: Handler, block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        handler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        check(latch.await(MAIN_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { MAIN_TIMEOUT_MESSAGE }
        return result.get()?.getOrThrow() ?: error(MAIN_TIMEOUT_MESSAGE)
    }

    private fun runOnMain(handler: Handler, block: () -> Unit) {
        callOnMain(handler) { block() }
    }

    private companion object {
        // The UI prefixes these with the candidate's label ("ExoPlayer：…"), so they say what went
        // wrong without repeating which engine it was.
        const val ERROR_PREFIX = "无法播放这个源："
        const val NO_MEDIA_ITEM_MESSAGE = "拿不到这个片源的媒体项，无法渲染同一帧"
        const val SOURCE_TIMEOUT_MESSAGE = "打开视频源超时"
        const val NO_FRAME_MESSAGE = "没有输出画面"
        const val MAIN_TIMEOUT_MESSAGE = "等待主线程执行 ExoPlayer 调用超时"

        /** Opening the source: network sources and a transcode start can be slow. */
        const val SOURCE_TIMEOUT_MILLIS = 20_000L

        /** Rendering the frame at the sought position. */
        const val FRAME_TIMEOUT_MILLIS = 10_000L

        /** Reading the frame back out of the render target. */
        const val CAPTURE_TIMEOUT_MILLIS = 8_000L

        /** Handing one call to the main thread; the UI is idle behind the overlay. */
        const val MAIN_CALL_TIMEOUT_MILLIS = 10_000L

        const val POLL_INTERVAL_MILLIS = 20L

        const val MIN_SIDE = 2

        val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
