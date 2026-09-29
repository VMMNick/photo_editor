package com.photoeditor.app.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Рендерер прев'ю: GLSurfaceView у режимі RENDERMODE_WHEN_DIRTY.
 * Прямокутник фото задає UI (з урахуванням масштабування пальцями) —
 * так оверлеї Compose і GL-картинка завжди збігаються піксель у піксель.
 */
class PreviewRenderer : GLSurfaceView.Renderer {

    private val renderer = ImageRenderer()
    @Volatile private var pendingBitmap: Bitmap? = null
    @Volatile private var lastBitmap: Bitmap? = null
    @Volatile private var params: RenderParams? = null
    /** x, y (від верхнього лівого кута в'ю), w, h — у пікселях. */
    @Volatile private var rect: FloatArray? = null
    @Volatile private var detail: DetailLayer? = null
    private var viewW = 0
    private var viewH = 0

    /** Колір тла навколо фото і за межами зображення (RGB 0..1). */
    var background = floatArrayOf(0.055f, 0.059f, 0.067f)

    fun setBitmap(b: Bitmap) {
        if (b !== lastBitmap) pendingBitmap = b
    }

    fun setFrame(p: RenderParams, imageRect: FloatArray, detailLayer: DetailLayer? = null) {
        params = p
        rect = imageRect
        detail = detailLayer
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Контекст міг бути втрачений (згортання застосунку) — створюємо ресурси заново
        renderer.release()
        renderer.init()
        if (pendingBitmap == null) pendingBitmap = lastBitmap
        lastBitmap = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let {
            if (!it.isRecycled) {
                renderer.setSource(it)
                lastBitmap = it
            }
            pendingBitmap = null
        }
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(background[0], background[1], background[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val p = params ?: return
        val r = rect ?: return
        if (!renderer.hasSource) return
        val x = r[0].roundToInt()
        val w = r[2].roundToInt()
        val h = r[3].roundToInt()
        val yFromBottom = viewH - (r[1].roundToInt() + h)
        renderer.draw(p, x, yFromBottom, w, h, background, detail = detail)
    }
}
