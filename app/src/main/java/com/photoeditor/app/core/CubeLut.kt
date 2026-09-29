package com.photoeditor.app.core

import kotlin.math.roundToInt

/**
 * 3D LUT у форматі Adobe/Resolve .cube: size³ вузлів RGB, червоний змінюється найшвидше.
 * @param data size³ × 3 значень 0..1 (вже нормалізованих з DOMAIN_MIN/MAX)
 */
class CubeLut(val title: String, val size: Int, val data: FloatArray) {

    init {
        require(size in 2..MAX_SIZE) { "LUT size $size" }
        require(data.size == size * size * size * 3) { "LUT data ${data.size}" }
    }

    /** RGBA8 для GL_TEXTURE_3D (порядок r → g → b збігається з .cube). */
    fun toRgba8(): ByteArray {
        val n = size * size * size
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            for (c in 0..2) out[i * 4 + c] = (data[i * 3 + c].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    /** Трилінійна вибірка на CPU — для тестів і перевірки відповідності шейдеру. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val s = size - 1
        fun split(v: Float): Pair<Int, Float> {
            val x = v.coerceIn(0f, 1f) * s
            val i = x.toInt().coerceAtMost(s - 1)
            return i to (x - i)
        }
        val (ri, rf) = split(r); val (gi, gf) = split(g); val (bi, bf) = split(b)
        val out = FloatArray(3)
        for (c in 0..2) {
            fun at(x: Int, y: Int, z: Int) = data[((z * size + y) * size + x) * 3 + c]
            val c00 = at(ri, gi, bi) * (1 - rf) + at(ri + 1, gi, bi) * rf
            val c10 = at(ri, gi + 1, bi) * (1 - rf) + at(ri + 1, gi + 1, bi) * rf
            val c01 = at(ri, gi, bi + 1) * (1 - rf) + at(ri + 1, gi, bi + 1) * rf
            val c11 = at(ri, gi + 1, bi + 1) * (1 - rf) + at(ri + 1, gi + 1, bi + 1) * rf
            val c0 = c00 * (1 - gf) + c10 * gf
            val c1 = c01 * (1 - gf) + c11 * gf
            out[c] = c0 * (1 - bf) + c1 * bf
        }
        return out
    }

    companion object {
        /** Більші LUT (наприклад 129³) не дають видимої різниці, а текстура займала б ~8 МБ. */
        const val MAX_SIZE = 65

        /** @throws IllegalArgumentException якщо файл не є коректним 3D .cube */
        fun parse(text: String, fallbackTitle: String = "LUT"): CubeLut {
            var title = fallbackTitle
            var size = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var data: FloatArray? = null
            var n = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val first = line[0]
                if (first.isLetter()) {
                    val parts = line.split(Regex("\\s+"))
                    when (parts[0].uppercase()) {
                        "TITLE" -> title = line.substringAfter(' ').trim().trim('"').ifEmpty { fallbackTitle }
                        "LUT_3D_SIZE" -> {
                            size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Bad LUT_3D_SIZE")
                            require(size in 2..MAX_SIZE) { "Unsupported LUT size $size (2..$MAX_SIZE)" }
                            data = FloatArray(size * size * size * 3)
                        }
                        "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported")
                        "DOMAIN_MIN" -> for (c in 0..2) min[c] = parts[c + 1].toFloat()
                        "DOMAIN_MAX" -> for (c in 0..2) max[c] = parts[c + 1].toFloat()
                        else -> Unit // LUT_3D_INPUT_RANGE та інші — ігноруємо
                    }
                    continue
                }
                val d = data ?: throw IllegalArgumentException("Data before LUT_3D_SIZE")
                val nums = line.split(Regex("\\s+"))
                require(nums.size >= 3) { "Bad data line: $line" }
                require(n + 3 <= d.size) { "Too many data lines" }
                for (c in 0..2) {
                    val v = nums[c].toFloatOrNull() ?: throw IllegalArgumentException("Bad number: ${nums[c]}")
                    d[n + c] = (v - min[c]) / (max[c] - min[c])
                }
                n += 3
            }
            val d = data ?: throw IllegalArgumentException("No LUT_3D_SIZE")
            require(n == d.size) { "Expected ${d.size / 3} entries, got ${n / 3}" }
            return CubeLut(title, size, d)
        }

        /** Нейтральний LUT (вихід = вхід) — для тестів. */
        fun identity(size: Int = 17): CubeLut {
            val d = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                d[i++] = r / (size - 1f); d[i++] = g / (size - 1f); d[i++] = b / (size - 1f)
            }
            return CubeLut("Identity", size, d)
        }
    }
}
