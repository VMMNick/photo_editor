package com.photoeditor.app.image

/**
 * Пошук вбудованих JPEG у RAW-файлах (CR2, CR3, NEF, ARW, ORF, RW2, RAF…).
 * Камери кладуть туди мініатюру і, як правило, повнорозмірне прев'ю — беремо найбільше за пікселями.
 * Без android.* — тестується на JVM.
 */
object RawPreviewExtractor {

    /** @return (offset, length) найбільшого JPEG або null. */
    fun largestJpeg(data: ByteArray): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestPixels = 0L
        var i = 0
        val n = data.size
        while (i < n - 3) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                val r = parse(data, i)
                if (r != null) {
                    val (end, pixels) = r
                    if (pixels > bestPixels) {
                        bestPixels = pixels
                        best = i to (end - i)
                    }
                    i = end
                    continue
                }
            }
            i++
        }
        return best
    }

    /** Розбирає JPEG від SOI. Повертає (кінець після EOI, ширина×висота) або null. */
    private fun parse(d: ByteArray, start: Int): Pair<Int, Long>? {
        val n = d.size
        var p = start + 2
        var pixels = 0L
        while (p + 2 <= n) {
            if (d[p] != 0xFF.toByte()) return null
            var marker = d[p + 1].toInt() and 0xFF
            // Заповнювальні 0xFF
            while (marker == 0xFF && p + 2 < n) { p++; marker = d[p + 1].toInt() and 0xFF }
            p += 2
            when (marker) {
                0xD8, 0x01, in 0xD0..0xD7 -> continue
                0xD9 -> return if (pixels > 0) p to pixels else null
            }
            if (p + 2 > n) return null
            val len = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            if (len < 2) return null
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && p + 7 <= n) {
                val h = ((d[p + 3].toInt() and 0xFF) shl 8) or (d[p + 4].toInt() and 0xFF)
                val w = ((d[p + 5].toInt() and 0xFF) shl 8) or (d[p + 6].toInt() and 0xFF)
                // Беремо лише baseline/progressive (C0..C2) — їх уміє BitmapFactory
                pixels = if (marker <= 0xC2) w.toLong() * h else -1L
            }
            p += len
            if (marker == 0xDA) {
                // Ентропійні дані до наступного маркера (крім FF00 та RSTn)
                while (p + 1 < n) {
                    if (d[p] == 0xFF.toByte()) {
                        val m = d[p + 1].toInt() and 0xFF
                        if (m == 0x00 || m in 0xD0..0xD7 || m == 0xFF) { p += if (m == 0xFF) 1 else 2; continue }
                        break
                    }
                    p++
                }
            }
        }
        return null
    }
}
