package com.photoeditor.app.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Офскрін-рендер у повній роздільності для експорту.
 * Має власний EGL-контекст, тож працює у фоновому потоці паралельно з прев'ю.
 * Результат малюється смугами, щоб не тримати в пам'яті два повнорозмірні буфери зайвий раз.
 */
class OffscreenRenderer : AutoCloseable {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val renderer = ImageRenderer()

    val maxTextureSize: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "No GLES3 EGL config" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        renderer.init()
        maxTextureSize = ImageRenderer.maxTextureSize()
    }

    fun setSource(bitmap: Bitmap) = renderer.setSource(bitmap)

    /** Звільняє текстуру джерела (після експорту в повній роздільності — це сотні МБ відеопам'яті). */
    fun releaseSource() = renderer.releaseSource()

    /** Малює результат розміром outW×outH у новий Bitmap. */
    fun render(params: RenderParams, outW: Int, outH: Int, bg: FloatArray = floatArrayOf(0f, 0f, 0f)): Bitmap {
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val stripH = min(outH, 512)

        val fbo = IntArray(1)
        val tex = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, outW, stripH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }

        val buf = ByteBuffer.allocateDirect(outW * stripH * 4).order(ByteOrder.nativeOrder())
        val strip = Bitmap.createBitmap(outW, stripH, Bitmap.Config.ARGB_8888)
        try {
            var y0 = 0
            while (y0 < outH) {
                val h = min(stripH, outH - y0)
                val rect = floatArrayOf(0f, y0 / outH.toFloat(), 1f, h / outH.toFloat())
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                renderer.draw(params, 0, 0, outW, h, bg, flipY = true, outRect = rect)
                buf.position(0)
                GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
                GLES30.glReadPixels(0, 0, outW, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                if (h == stripH) {
                    strip.copyPixelsFromBuffer(buf)
                    canvas.drawBitmap(strip, 0f, y0.toFloat(), null)
                } else {
                    val last = Bitmap.createBitmap(outW, h, Bitmap.Config.ARGB_8888)
                    buf.limit(outW * h * 4)
                    last.copyPixelsFromBuffer(buf)
                    buf.limit(buf.capacity())
                    canvas.drawBitmap(last, 0f, y0.toFloat(), null)
                    last.recycle()
                }
                y0 += h
            }
        } finally {
            strip.recycle()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(1, tex, 0)
        }
        return result
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            renderer.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // eglTerminate не викликаємо: display спільний з GLSurfaceView прев'ю
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40

        /** Розмір виходу в пікселях з урахуванням обмежень GPU. */
        fun outputSize(params: RenderParams, maxSide: Int): IntArray {
            var w = params.outW
            var h = params.outH
            val k = minOf(1f, maxSide / maxOf(w, h))
            w *= k; h *= k
            return intArrayOf(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
        }
    }
}
