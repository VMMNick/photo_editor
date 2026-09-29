package com.photoeditor.app.testutil

import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.PixelImage

/** Синтетичні зображення для тестів (без файлів і android.*). */
object TestImages {

    fun solid(w: Int, h: Int, r: Int, g: Int, b: Int) = PixelImage(w, h, IntArray(w * h) { PixelImage.argb(r, g, b) })

    /** «Цегляна стіна»: повторювана текстура, на якій добре видно якість заповнення. */
    fun bricks(w: Int, h: Int) = PixelImage(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val mortar = ((y / 12) % 2 * 12 + x) % 24 < 2 || y % 12 < 2
        if (mortar) PixelImage.argb(200, 195, 185) else PixelImage.argb(160 + (x * 31 + y * 17) % 20, 70, 50)
    })

    /** Горизонтальний градієнт яскравості. */
    fun gradient(w: Int, h: Int) = PixelImage(w, h, IntArray(w * h) { i ->
        val v = (i % w) * 255 / (w - 1)
        PixelImage.argb(v, v, v)
    })

    fun rectMask(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): AlphaMap {
        val m = AlphaMap(w, h)
        for (y in y0..y1) for (x in x0..x1) m.data[y * w + x] = 0xFF.toByte()
        return m
    }

    fun fillRect(img: PixelImage, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0..y1) for (x in x0..x1) img.pixels[y * img.width + x] = color
    }

    fun r(p: Int) = (p shr 16) and 0xFF
    fun g(p: Int) = (p shr 8) and 0xFF
    fun b(p: Int) = p and 0xFF
}
