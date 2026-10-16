package org.jellyfin.mobile.player.mpv.bench

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.Surface
import org.jellyfin.mobile.player.mpv.MpvCore
import org.jellyfin.mobile.player.mpv.MpvEvent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders benchmark frames on a dedicated mpv instance.
 *
 * A second instance is deliberate: the user's player keeps running - it owns the sound, the media
 * session and the visible surface - while the benchmark decodes the same source into an offscreen
 * surface. Sharing the player's instance would mean fighting it for the video output.
 *
 * Everything here is written as "issue IPC, then wait for the event that proves it happened". The
 * blocking waits are safe because [render] is called from a background thread; they are what makes
 * the sequence deterministic, and determinism is the whole point of a pixel comparison.
 */
class MpvSceneRendererImpl(private val application: Application) : MpvSceneRenderer {

    /**
     * The offscreen target. Reused across candidates: the surface is part of the scene, not of the
     * configuration under test, so recreating it would add a variable the comparison does not want.
     */
    private var surface: Surface? = null

    /**
     * Options the benchmark pins for its own bookkeeping. They are set through the instance's
     * config, not through the candidates, so no candidate can accidentally change them.
     */
    private val sceneOptions = arrayOf(
        // Keep the frame on screen when the file ends, so a screenshot is always possible.
        "keep-open" to "yes",
        "pause" to "yes",
        // Screenshots have to be PNG and taken from the video output: the point is to capture what
        // the render configuration produced, not a scaled or re-encoded copy of it.
        "screenshot-format" to "png",
        "screenshot-sw" to "no",
        "screenshot-template" to SHOT_TEMPLATE,
    )

    override fun render(spec: SceneSpec, candidates: List<ConfigCandidate>): SceneCapture {
        val frames = candidates.map { candidate ->
            runCatching { renderOne(spec, candidate) }.fold(
                onSuccess = { frame -> SceneFrame(candidate, frame) },
                onFailure = { error ->
                    SceneFrame(candidate, image = null, failure = error.message ?: "渲染失败")
                },
            )
        }
        return SceneCapture(spec, frames)
    }

    private fun renderOne(spec: SceneSpec, candidate: ConfigCandidate): RawFrame? {
        MpvCore.initialize(application)
        ensureSurface(spec)

        // A fresh file per candidate: the frame that is captured must be produced by the
        // candidate's configuration, not inherited from the previous candidate's decoder state.
        MpvCore.command(arrayOf("stop"))
        applySceneOptions()
        awaitEvent<MpvEvent.FileLoaded>("打开素材") {
            MpvCore.command(
                arrayOf(
                    "loadfile",
                    spec.sourceUrl,
                    "replace",
                    "0",
                    "start=+%.3f".format(spec.positionMs / 1000.0),
                ),
            )
        }
        // The configuration is applied after the file is loaded and before any frame is decoded, so
        // the single frame the screenshot captures is rendered with it from the start.
        candidate.profileName?.let { MpvCore.command(arrayOf("apply-profile", it)) }
        awaitEvent<MpvEvent.PlaybackRestart>("渲染首帧") { MpvCore.setProperty("pause", true) }
        return captureFrame()
    }

    private fun ensureSurface(spec: SceneSpec) {
        val existing = surface
        if (existing != null && !existing.isValid) {
            existing.release()
            surface = null
        }
        if (surface == null) {
            val texture = SurfaceTexture(0)
            texture.setDefaultBufferSize(spec.viewportWidth, spec.viewportHeight)
            surface = Surface(texture)
        }
        MpvCore.attachSurface(surface!!)
        MpvCore.setProperty("android-surface-size", "${spec.viewportWidth}x${spec.viewportHeight}")
    }

    private fun applySceneOptions() {
        sceneOptions.forEach { (name, value) -> MpvCore.setProperty(name, value) }
        MpvCore.setProperty("screenshot-directory", screenshotDir().path)
    }

    /**
     * Takes one screenshot and decodes it.
     *
     * The filename is produced by mpv's own template, so instead of guessing it the directory is
     * emptied first and the single file that appears is read back.
     */
    private fun captureFrame(): RawFrame? {
        val directory = screenshotDir()
        directory.listFiles()?.forEach { it.delete() }
        awaitEvent<MpvEvent>("截图") { MpvCore.command(arrayOf("screenshot")) }
        val file = waitForScreenshotFile(directory) ?: error("截图未生成")
        return decodeScreenshot(file)
    }

    private fun waitForScreenshotFile(directory: File): File? {
        val deadline = System.currentTimeMillis() + SCREENSHOT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val candidate = directory.listFiles()?.firstOrNull { it.length() > 0 }
            if (candidate != null) return candidate
            Thread.sleep(SCREENSHOT_POLL_INTERVAL_MS)
        }
        return null
    }

    private fun decodeScreenshot(file: File): RawFrame {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: error("无法解码截图")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val frame = RawFrame(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        file.delete()
        return frame
    }

    private fun screenshotDir(): File = File(application.cacheDir, SCREENSHOT_DIR).apply { mkdirs() }

    /**
     * Waits for the event type [T], performing [action] once the subscription is in place.
     *
     * Subscribing *before* the action is what makes this race-free: mpv can emit FILE_LOADED
     * before a naive implementation has started waiting for it.
     */
    private inline fun <reified T : MpvEvent> awaitEvent(label: String, action: () -> Unit) {
        val latch = CountDownLatch(1)
        val listener: (MpvEvent) -> Unit = { event ->
            if (event is T) latch.countDown()
        }
        MpvCore.subscribe(listener)
        try {
            action()
            if (!latch.await(EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) error("$label 超时")
        } finally {
            MpvCore.unsubscribe(listener)
        }
    }

    /**
     * Releases the offscreen surface. The mpv instance itself is left to the app singleton: it is
     * also what the benchmark's profile changes were applied to, and recreating it is the caller's
     * decision (see [MpvConfigSwapper.reset]).
     */
    fun releaseSurface() {
        surface?.release()
        surface = null
        MpvCore.detachSurface()
    }

    private companion object {
        const val SCREENSHOT_DIR = "mpv-ab-frames"

        /** Template without a directory component: `screenshot-directory` already points at it. */
        const val SHOT_TEMPLATE = "ab-shot-%n"

        const val EVENT_TIMEOUT_MS = 5_000L
        const val SCREENSHOT_TIMEOUT_MS = 3_000L
        const val SCREENSHOT_POLL_INTERVAL_MS = 25L
    }
}
