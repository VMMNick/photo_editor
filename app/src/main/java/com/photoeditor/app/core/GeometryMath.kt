package com.photoeditor.app.core

import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.NRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 3×3 матриця, рядкова розкладка: m[row*3+col]. Діє на стовпчик (x, y, 1). */
class Mat3(val m: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) {

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += m[i * 3 + k] * o.m[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun inverse(): Mat3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > 1e-15) { "Singular matrix" }
        val inv = 1.0 / det
        return Mat3(
            doubleArrayOf(
                c00 * inv, (a[2] * a[7] - a[1] * a[8]) * inv, (a[1] * a[5] - a[2] * a[4]) * inv,
                c01 * inv, (a[0] * a[8] - a[2] * a[6]) * inv, (a[2] * a[3] - a[0] * a[5]) * inv,
                c02 * inv, (a[1] * a[6] - a[0] * a[7]) * inv, (a[0] * a[4] - a[1] * a[3]) * inv,
            )
        )
    }

    /** Проєктивне перетворення точки. */
    fun map(x: Double, y: Double): DoubleArray {
        val w = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf(
            (m[0] * x + m[1] * y + m[2]) / w,
            (m[3] * x + m[4] * y + m[5]) / w,
        )
    }

    /** Для glUniformMatrix3fv(transpose = false): OpenGL очікує стовпчикову розкладку. */
    fun toColumnMajorFloats(): FloatArray = FloatArray(9) { i ->
        val col = i / 3
        val row = i % 3
        m[row * 3 + col].toFloat()
    }

    companion object {
        fun translate(x: Double, y: Double) = Mat3(doubleArrayOf(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0))
        fun scale(x: Double, y: Double) = Mat3(doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0))
        fun rotate(rad: Double): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}

/**
 * Перетворення між вихідним кадром (після crop) та uv джерела.
 *
 * Прямий ланцюжок (джерело → екран):
 *   uv джерела → поворот 90°/віддзеркалення → центровані пікселі q
 *   → K (перспектива) → R (вирівнювання) → S (автомасштаб без порожніх кутів)
 *   → кадр → crop → вихідні uv (0..1).
 * Шейдеру потрібен зворотний напрям: для кожного вихідного пікселя — де брати колір у джерелі.
 */
class GeometryMath(val srcW: Int, val srcH: Int, val g: Geometry, cropOverride: NRect? = null) {

    val turns = ((g.quarterTurns % 4) + 4) % 4
    /** Розміри орієнтованого зображення (після повороту на 90°). */
    val frameW: Double = if (turns % 2 == 1) srcH.toDouble() else srcW.toDouble()
    val frameH: Double = if (turns % 2 == 1) srcW.toDouble() else srcH.toDouble()
    val crop: NRect = cropOverride ?: g.crop

    /** Розмір результату в пікселях джерела. */
    val outW: Double get() = frameW * crop.w
    val outH: Double get() = frameH * crop.h
    val outAspect: Double get() = outW / outH

    private val keystone: Mat3 = run {
        val px = g.perspectiveH * 0.5 / (frameW / 2.0) * 0.5
        val py = g.perspectiveV * 0.5 / (frameH / 2.0) * 0.5
        Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, px, py, 1.0))
    }
    private val rot: Mat3 = Mat3.rotate(Math.toRadians(g.straighten.toDouble()))
    private val rk: Mat3 = rot * keystone

    /** Мінімальний масштаб, за якого кадр повністю вкритий зображенням. */
    val autoScale: Double = computeCoverScale()

    /** q (центровані пікселі орієнтованого зображення) → d (центровані пікселі кадру). */
    val forwardQ: Mat3 = Mat3.scale(autoScale, autoScale) * rk
    val inverseQ: Mat3 = forwardQ.inverse()

    /** uv джерела → нормалізовані координати орієнтованого зображення. */
    private val srcToOriented: Mat3 = run {
        // Поворот на 90° за годинниковою: (u, v) → (1 - v, u)
        val quarter = Mat3(doubleArrayOf(0.0, -1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        var m = Mat3()
        repeat(turns) { m = quarter * m }
        if (g.flipH) m = Mat3(doubleArrayOf(-1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)) * m
        if (g.flipV) m = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)) * m
        m
    }

    /** Нормалізовані орієнтовані → центровані пікселі q. */
    private val orientedToQ = Mat3.translate(-frameW / 2, -frameH / 2).let { Mat3.scale(frameW, frameH).let { s -> it * s } }
    /** Центровані пікселі кадру → нормалізований кадр. */
    private val dToFrame = Mat3.translate(0.5, 0.5) * Mat3.scale(1.0 / frameW, 1.0 / frameH)
    /** Вихідні uv → нормалізований кадр (crop). */
    private val outToFrame = Mat3.translate(crop.l.toDouble(), crop.t.toDouble()) *
        Mat3.scale(crop.w.toDouble(), crop.h.toDouble())

    /** Джерело → вихід. */
    val sourceToOut: Mat3 = outToFrame.inverse() * dToFrame * forwardQ * orientedToQ * srcToOriented

    /** Вихід → джерело (саме її отримує шейдер). */
    val outToSource: Mat3 = sourceToOut.inverse()

    fun outToSourceUv(ou: Float, ov: Float): FloatArray {
        val p = outToSource.map(ou.toDouble(), ov.toDouble())
        return distort(p[0], p[1])
    }

    fun sourceToOutUv(u: Float, v: Float): FloatArray {
        val c = undistort(u.toDouble(), v.toDouble())
        val p = sourceToOut.map(c[0], c[1])
        return floatArrayOf(p[0].toFloat(), p[1].toFloat())
    }

    // ------------------------------------------------------------------ Дисторсія об'єктива
    /**
     * Радіальна модель r_s = r · (1 + k·r²) / N, r — відносно половини діагоналі джерела.
     * N підібрано так, щоб жодна точка межі кадру не брала колір поза зображенням (без порожніх країв):
     * для «подушки» (k > 0) найгірші — кути, для «бочки» (k < 0) — середини коротших сторін.
     * Застосовується після гомографії, у координатах джерела (оптичний центр — центр кадру).
     */
    val lensK: Double = -LENS_STRENGTH * g.distortion
    val lensN: Double = run {
        val minSide = minOf(srcW, srcH).toDouble()
        val rMin2 = minSide * minSide / (srcW.toDouble() * srcW + srcH.toDouble() * srcH)
        if (lensK >= 0) 1 + lensK else 1 + lensK * rMin2
    }

    /** «Виправлені» uv джерела → реальні uv у файлі (цей самий напрям рахує шейдер). */
    fun distort(u: Double, v: Double): FloatArray {
        if (lensK == 0.0) return floatArrayOf(u.toFloat(), v.toFloat())
        val f = lensFactor(radius2(u, v))
        return floatArrayOf((0.5 + (u - 0.5) * f).toFloat(), (0.5 + (v - 0.5) * f).toFloat())
    }

    /** Зворотне до [distort] (Ньютон по радіусу: функція монотонна при |k| < 1/3). */
    fun undistort(u: Double, v: Double): DoubleArray {
        if (lensK == 0.0) return doubleArrayOf(u, v)
        val rd = kotlin.math.sqrt(radius2(u, v))
        if (rd < 1e-9) return doubleArrayOf(u, v)
        var r = rd
        repeat(8) {
            val g0 = r * (1 + lensK * r * r) / lensN - rd
            val d = (1 + 3 * lensK * r * r) / lensN
            r -= g0 / d
        }
        val s = r / rd
        return doubleArrayOf(0.5 + (u - 0.5) * s, 0.5 + (v - 0.5) * s)
    }

    private fun radius2(u: Double, v: Double): Double {
        val x = (u - 0.5) * srcW
        val y = (v - 0.5) * srcH
        return (x * x + y * y) / ((srcW.toDouble() * srcW + srcH.toDouble() * srcH) / 4.0)
    }

    private fun lensFactor(r2: Double) = (1 + lensK * r2) / lensN

    private fun computeCoverScale(): Double {
        if (g.straighten == 0f && g.perspectiveH == 0f && g.perspectiveV == 0f) return 1.0
        val inv = rk.inverse()
        val hw = frameW / 2
        val hh = frameH / 2
        val corners = arrayOf(doubleArrayOf(-hw, -hh), doubleArrayOf(hw, -hh), doubleArrayOf(hw, hh), doubleArrayOf(-hw, hh))
        fun fits(s: Double): Boolean = corners.all { c ->
            val q = inv.map(c[0] / s, c[1] / s)
            abs(q[0]) <= hw * 1.0001 && abs(q[1]) <= hh * 1.0001
        }
        var lo = 1.0
        var hi = 6.0
        if (fits(lo)) return lo
        repeat(40) {
            val mid = (lo + hi) / 2
            if (fits(mid)) hi = mid else lo = mid
        }
        return hi
    }

    companion object {
        /** Прямокутник максимального розміру з пропорцією [aspect] (ширина/висота у пікселях), вписаний у кадр. */
        /** k = −0.25 · distortion: при |distortion| ≤ 1 модель монотонна. */
        const val LENS_STRENGTH = 0.25

        fun centeredCrop(frameW: Double, frameH: Double, aspect: Double): NRect {
            val frameAspect = frameW / frameH
            return if (aspect > frameAspect) {
                val h = frameAspect / aspect
                NRect(0f, ((1 - h) / 2).toFloat(), 1f, ((1 + h) / 2).toFloat())
            } else {
                val w = aspect / frameAspect
                NRect(((1 - w) / 2).toFloat(), 0f, ((1 + w) / 2).toFloat(), 1f)
            }
        }

        /** Вписати прямокутник пропорції [aspect] у в'юпорт w×h по центру. Повертає (x, y, w, h). */
        fun fitRect(viewW: Float, viewH: Float, aspect: Float): FloatArray {
            if (viewW <= 0f || viewH <= 0f || aspect <= 0f) return floatArrayOf(0f, 0f, viewW, viewH)
            return if (viewW / viewH > aspect) {
                val w = viewH * aspect
                floatArrayOf((viewW - w) / 2f, 0f, w, viewH)
            } else {
                val h = viewW / aspect
                floatArrayOf(0f, (viewH - h) / 2f, viewW, h)
            }
        }
    }
}
