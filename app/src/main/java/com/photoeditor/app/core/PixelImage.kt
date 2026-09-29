package com.photoeditor.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Чисто-Kotlin буфери пікселів без залежності від android.* —
 * алгоритми ретушу можна тестувати на звичайній JVM.
 */

/** ARGB_8888 зображення (формат як у Bitmap.getPixels). */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height)
    }

    fun copy() = PixelImage(width, height, pixels.copyOf())

    operator fun get(x: Int, y: Int) = pixels[y * width + x]

    /** Білінійна вибірка каналу c (0=R,1=G,2=B) з обмеженням по краях. */
    fun sample(x: Float, y: Float, out: FloatArray) {
        val fx = x.coerceIn(0f, width - 1f)
        val fy = y.coerceIn(0f, height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = fx - x0
        val ay = fy - y0
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        for (c in 0..2) {
            val sh = 16 - c * 8
            val v00 = (p00 shr sh) and 0xFF
            val v10 = (p10 shr sh) and 0xFF
            val v01 = (p01 shr sh) and 0xFF
            val v11 = (p11 shr sh) and 0xFF
            val top = v00 + (v10 - v00) * ax
            val bot = v01 + (v11 - v01) * ax
            out[c] = top + (bot - top) * ay
        }
    }

    companion object {
        fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        fun argb(r: Float, g: Float, b: Float): Int = argb(r.roundToInt(), g.roundToInt(), b.roundToInt())
    }
}

/**
 * Одноканальна карта 0..255 (маски, сегментація, області видалення).
 * Зберігається в uv джерела, тож її можна масштабувати під будь-яку роздільність.
 */
class AlphaMap(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init {
        require(data.size == width * height)
    }

    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    /** Значення 0..1 у нормалізованих координатах (білінійно). */
    fun sampleUv(u: Float, v: Float): Float {
        val fx = (u * width - 0.5f).coerceIn(0f, width - 1f)
        val fy = (v * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = fx - x0
        val ay = fy - y0
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * ax
        val bot = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * ax
        return (top + (bot - top) * ay) / 255f
    }

    fun resized(w: Int, h: Int): AlphaMap {
        if (w == width && h == height) return this
        val out = AlphaMap(w, h)
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            for (x in 0 until w) {
                val u = (x + 0.5f) / w
                out.data[y * w + x] = (sampleUv(u, v) * 255f + 0.5f).toInt().toByte()
            }
        }
        return out
    }

    fun isEmpty(threshold: Int = 8): Boolean {
        for (b in data) if ((b.toInt() and 0xFF) > threshold) return false
        return true
    }

    /** Об'єднання (max) з іншою картою тієї ж роздільності. */
    fun union(other: AlphaMap): AlphaMap {
        val o = other.resized(width, height)
        val out = AlphaMap(width, height)
        for (i in data.indices) {
            out.data[i] = max(data[i].toInt() and 0xFF, o.data[i].toInt() and 0xFF).toByte()
        }
        return out
    }

    companion object {
        fun fromFloats(w: Int, h: Int, values: FloatArray): AlphaMap {
            val m = AlphaMap(w, h)
            for (i in values.indices) m.data[i] = (values[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            return m
        }
    }
}
