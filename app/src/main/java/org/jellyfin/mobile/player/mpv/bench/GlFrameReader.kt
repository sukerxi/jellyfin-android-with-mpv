package org.jellyfin.mobile.player.mpv.bench

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import timber.log.Timber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A render target the video output can draw into, plus the pixels of what it drew.
 *
 * The video output only understands an [android.view.Surface], so getting pixels back out needs a
 * consumer, and the two obvious consumers do not work here:
 *
 * - mpv's own `screenshot`/`screenshot-to-file` encodes the frame with libavcodec, and the libmpv
 *   build shipped with this app disables FFmpeg's encoders and muxers - it can never write a file.
 * - [android.media.ImageReader] requires the producer to hand over exactly the pixel format the
 *   reader was created with. Several devices have the GPU produce something else (for example
 *   `The producer output buffer format 0x2b doesn't match the ImageReader's configured buffer
 *   format 0x1`), and there is no format the caller can ask for that is guaranteed to match.
 *
 * A [SurfaceTexture] accepts whatever the producer hands it and exposes the frame as an external
 * GL texture. Reading it back is then ordinary GLES2 work under our own control: draw the texture
 * into an RGBA8 framebuffer and `glReadPixels` it. Every GLES2 driver supports that, no encoder is
 * involved, and the bytes are exactly what the video output produced.
 *
 * All methods must be called from the thread that created the instance: the EGL context and the
 * texture belong to it.
 */
internal class GlFrameReader(private val width: Int, private val height: Int) : Closeable {

    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    private val textureName: Int
    private val surfaceTexture: SurfaceTexture

    /** The window handed to the video output. */
    val surface: Surface

    private val program: Int
    private val framebuffer: Int
    private val framebufferTexture: Int
    private val textureLocation: Int
    private val transformLocation: Int

    private val transform = FloatArray(16)
    /** Set from the producer's thread when a frame is queued; see [latchNewestFrame]. */
    private val frameAvailable = AtomicBoolean(false)
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
        .order(ByteOrder.nativeOrder())
    private val argb: IntArray = IntArray(width * height)

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "无法获取 EGL 显示" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "无法初始化 EGL" }

        val config = chooseConfig(display)
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        checkEgl("eglCreateContext")
        check(context != EGL14.EGL_NO_CONTEXT) { "无法创建 EGL 上下文" }

        // A 1x1 pbuffer only exists so the context has something to be current with; all drawing
        // goes into the framebuffer object below.
        eglSurface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        checkEgl("eglCreatePbufferSurface")
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "无法创建 EGL 表面" }

        makeCurrent()

        textureName = createExternalTexture()
        // The constructor attaches the texture to the current context, which is why the context
        // has to be current before this line.
        surfaceTexture = SurfaceTexture(textureName).apply {
            setDefaultBufferSize(width, height)
            // The producer does not stamp presentation times, so "is there a new frame" cannot be
            // told from `timestamp`; the callback is what reports it.
            setOnFrameAvailableListener { frameAvailable.set(true) }
        }
        surface = Surface(surfaceTexture)

        program = createProgram()
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture")
        transformLocation = GLES20.glGetUniformLocation(program, "uTransform")
        framebufferTexture = createFramebufferTexture(width, height)
        framebuffer = createFramebuffer(framebufferTexture)
        GLES20.glViewport(0, 0, width, height)
    }

    /**
     * Waits for a rendered frame and returns its pixels, or null when none arrived in time.
     *
     * The first frame that can be latched is not necessarily the one to compare: the video output
     * may still be presenting the frames it decoded around the seek. So frames keep being latched
     * until nothing new arrives, and the last one - after the output was paused, the frame mpv
     * considers current - is read back.
     */
    fun captureFrame(timeoutMillis: Long): Bitmap? {
        makeCurrent()
        val deadline = System.currentTimeMillis() + timeoutMillis
        var firstLatch = 0L
        var lastLatch = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= deadline) return null
            if (latchNewestFrame()) {
                if (firstLatch == 0L) firstLatch = now
                lastLatch = now
                continue
            }
            if (firstLatch != 0L) {
                val drained = now - lastLatch >= DRAIN_SETTLE_MILLIS ||
                    now - firstLatch >= MAX_DRAIN_MILLIS
                if (drained) return readPixels()
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    override fun close() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching {
            makeCurrent()
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
            GLES20.glDeleteTextures(1, intArrayOf(framebufferTexture), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        }
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(display, eglSurface) }
        runCatching { EGL14.eglDestroyContext(display, context) }
        runCatching { EGL14.eglTerminate(display) }
    }

    /**
     * Hands the newest frame of the producer to [surfaceTexture], if there is one.
     *
     * `updateTexImage` cannot tell "no frame yet" from "same frame again", so the callback installed
     * on the texture is what reports a queued frame. It runs on whichever thread the producer
     * delivers on, hence the atomic.
     */
    private fun latchNewestFrame(): Boolean {
        if (!frameAvailable.get()) return false
        // Cleared before the latch: a frame arriving during it will set the flag again.
        frameAvailable.set(false)
        return runCatching {
            surfaceTexture.updateTexImage()
            true
        }.getOrElse { error ->
            // A released or otherwise broken texture must not look like "no frame yet".
            Timber.w(error, "mpv bench: could not latch a frame")
            false
        }
    }

    private fun readPixels(): Bitmap {
        surfaceTexture.getTransformMatrix(transform)
        draw()

        pixels.clear()
        GLES20.glReadPixels(
            0,
            0,
            width,
            height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        checkGl("glReadPixels")
        pixels.rewind()

        // GL's origin is bottom-left, a bitmap's is top-left: rows are mirrored while converting.
        // Alpha is forced opaque - the video output is free to leave it at zero and a fully
        // transparent frame would show up as nothing in the comparison views.
        var source = 0
        for (y in 0 until height) {
            var destination = (height - 1 - y) * width
            repeat(width) {
                val r = pixels.get(source).toInt() and 0xFF
                val g = pixels.get(source + 1).toInt() and 0xFF
                val b = pixels.get(source + 2).toInt() and 0xFF
                argb[destination++] = OPAQUE_ALPHA or (r shl 16) or (g shl 8) or b
                source += BYTES_PER_PIXEL
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun draw() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glUniformMatrix4fv(transformLocation, 1, false, transform, 0)

        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION)
        FLOAT_BUFFER.position(0)
        GLES20.glVertexAttribPointer(ATTRIB_POSITION, 2, GLES20.GL_FLOAT, false, 0, FLOAT_BUFFER)
        GLES20.glEnableVertexAttribArray(ATTRIB_TEX_COORD)
        TEX_COORD_BUFFER.position(0)
        GLES20.glVertexAttribPointer(ATTRIB_TEX_COORD, 2, GLES20.GL_FLOAT, false, 0, TEX_COORD_BUFFER)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(ATTRIB_POSITION)
        GLES20.glDisableVertexAttribArray(ATTRIB_TEX_COORD)
        // glReadPixels would sync anyway; doing it explicitly keeps the GPU error on this call.
        GLES20.glFinish()
        checkGl("draw")
    }

    private fun createExternalTexture(): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        val texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGl("createExternalTexture")
        return texture
    }

    private fun createFramebufferTexture(width: Int, height: Int): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        val texture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("createFramebufferTexture")
        return texture
    }

    private fun createFramebuffer(texture: Int): Int {
        val names = IntArray(1)
        GLES20.glGenFramebuffers(1, names, 0)
        val framebuffer = names[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "渲染目标不可用（framebuffer 状态 0x${status.toString(16)}）"
        }
        return framebuffer
    }

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glBindAttribLocation(program, ATTRIB_POSITION, "aPosition")
        GLES20.glBindAttribLocation(program, ATTRIB_TEX_COORD, "aTexCoord")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            "着色器链接失败：$log"
        }
        // The shaders are baked into the program; the objects are no longer needed.
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        checkGl("createProgram")
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            "着色器编译失败：$log"
        }
        return shader
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            checkEgl("eglMakeCurrent")
            error("无法激活 EGL 上下文")
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "无法选择 EGL 配置"
        }
        checkEgl("eglChooseConfig")
        check(count[0] > 0) { "设备不支持所需的 EGL 配置" }
        return configs[0] ?: error("无法选择 EGL 配置")
    }

    private fun checkEgl(operation: String) {
        val eglError = EGL14.eglGetError()
        if (eglError != EGL14.EGL_SUCCESS) {
            Timber.w("mpv bench: %s failed with EGL error 0x%x", operation, eglError)
        }
    }

    private fun checkGl(operation: String) {
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            error("$operation 失败（GL 错误 0x${glError.toString(16)}）")
        }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val POLL_INTERVAL_MILLIS = 20L

        /** Quiet time that marks the last frame the paused output will present. */
        const val DRAIN_SETTLE_MILLIS = 100L

        /** Upper bound on draining, in case the output keeps presenting frames while paused. */
        const val MAX_DRAIN_MILLIS = 600L

        const val ATTRIB_POSITION = 0
        const val ATTRIB_TEX_COORD = 1

        const val OPAQUE_ALPHA = 0xFF shl 24

        /** Full-viewport triangle strip; the texture's own matrix handles crop and rotation. */
        val FLOAT_BUFFER = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        val TEX_COORD_BUFFER = floatBufferOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTransform;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
