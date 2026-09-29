package com.photoeditor.app.core

/**
 * Гістограма RGB + яскравості (256 кошиків) і частка «вибитих» пікселів.
 * @param highlightClip частка пікселів, де хоч один канал досяг 255 (пересвіт)
 * @param shadowClip частка пікселів, де всі канали 0 (провал у чорне)
 */
class Histogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luma: IntArray,
    val total: Int,
    val highlightClip: Float,
    val shadowClip: Float,
) {
    /** Найвищий кошик (без крайніх, щоб пересвіт не сплющив решту графіка). */
    val peak: Int
        get() {
            var p = 1
            for (i in 1 until 255) p = maxOf(p, red[i], green[i], blue[i], luma[i])
            return p
        }

    companion object {
        /** @param pixels ARGB, як у Bitmap.getPixels */
        fun compute(pixels: IntArray): Histogram {
            val r = IntArray(256)
            val g = IntArray(256)
            val b = IntArray(256)
            val l = IntArray(256)
            var hi = 0
            var lo = 0
            for (p in pixels) {
                val cr = (p shr 16) and 0xFF
                val cg = (p shr 8) and 0xFF
                val cb = p and 0xFF
                r[cr]++; g[cg]++; b[cb]++
                l[(cr * 54 + cg * 183 + cb * 19) shr 8]++
                if (cr == 255 || cg == 255 || cb == 255) hi++
                if (cr == 0 && cg == 0 && cb == 0) lo++
            }
            val n = pixels.size.coerceAtLeast(1)
            return Histogram(r, g, b, l, pixels.size, hi / n.toFloat(), lo / n.toFloat())
        }
    }
}
