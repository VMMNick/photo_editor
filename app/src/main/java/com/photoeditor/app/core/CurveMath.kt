package com.photoeditor.app.core

import com.photoeditor.app.model.CurvePoint
import com.photoeditor.app.model.Curves
import com.photoeditor.app.model.ToneCurve
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Обчислення та редагування тонових кривих. Чистий Kotlin — покрито unit-тестами.
 *
 * Інтерполяція — монотонний кубічний сплайн Фрітча–Карлсона: крива проходить через усі точки
 * і між сусідніми точками не виходить за їхні значення (немає «хвиль», як у звичайного сплайна).
 */
object CurveMath {
    const val LUT_SIZE = 256
    /** Мінімальна відстань між точками по x. */
    const val MIN_GAP = 0.02f

    fun evaluate(curve: ToneCurve, x: Float): Float = evaluate(curve.points, tangents(curve.points), x)

    /** Таблиця значень кривої на рівномірній сітці 0..1. */
    fun lut(curve: ToneCurve, size: Int = LUT_SIZE): FloatArray {
        val pts = curve.points
        if (curve.isIdentity) return FloatArray(size) { it / (size - 1f) }
        val m = tangents(pts)
        return FloatArray(size) { evaluate(pts, m, it / (size - 1f)) }
    }

    /**
     * Текстура 256×1 RGBA для шейдера: канал = окрема крива(загальна крива(x)).
     * Так спершу діє загальна крива, потім — канальні, як у Lightroom.
     */
    fun combinedLut(c: Curves, size: Int = LUT_SIZE): ByteArray {
        val all = lut(c.rgb, size)
        val chans = arrayOf(c.red, c.green, c.blue)
        val out = ByteArray(size * 4)
        for (i in 0 until size) {
            val v = all[i]
            for (ch in 0..2) {
                val y = if (chans[ch].isIdentity) v else evaluate(chans[ch], v)
                out[i * 4 + ch] = (y.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            }
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    // ------------------------------------------------------------------ Редагування

    /** Індекс точки в радіусі r (у нормалізованих координатах) від (x, y), або -1. */
    fun hitPoint(curve: ToneCurve, x: Float, y: Float, r: Float): Int {
        var best = -1
        var bestD = r
        curve.points.forEachIndexed { i, p ->
            val d = hypot(p.x - x, p.y - y)
            if (d <= bestD) { best = i; bestD = d }
        }
        return best
    }

    /**
     * Нова точка на кривій у позиції x (y береться з кривої, щоб форма не змінилась).
     * @return крива та індекс нової точки; null, якщо поруч уже є точка
     */
    fun addPoint(curve: ToneCurve, x: Float, y: Float = evaluate(curve, x)): Pair<ToneCurve, Int>? {
        val xc = x.coerceIn(0f, 1f)
        if (curve.points.any { abs(it.x - xc) < MIN_GAP }) return null
        val pts = (curve.points + CurvePoint(xc, y.coerceIn(0f, 1f))).sortedBy { it.x }
        return ToneCurve(pts) to pts.indexOfFirst { it.x == xc }
    }

    /** Переміщення точки: x обмежено сусідами (порядок не змінюється), y — 0..1. */
    fun movePoint(curve: ToneCurve, index: Int, x: Float, y: Float): ToneCurve {
        val pts = curve.points.toMutableList()
        if (index !in pts.indices) return curve
        val lo = if (index == 0) 0f else pts[index - 1].x + MIN_GAP
        val hi = if (index == pts.lastIndex) 1f else pts[index + 1].x - MIN_GAP
        val nx = if (lo <= hi) x.coerceIn(lo, hi) else pts[index].x
        pts[index] = CurvePoint(nx, y.coerceIn(0f, 1f))
        return ToneCurve(pts)
    }

    /** Видаляє проміжну точку (кінцеві лишаються завжди). */
    fun removePoint(curve: ToneCurve, index: Int): ToneCurve {
        if (index <= 0 || index >= curve.points.lastIndex) return curve
        return ToneCurve(curve.points.filterIndexed { i, _ -> i != index })
    }

    // ------------------------------------------------------------------ Сплайн

    private fun evaluate(pts: List<CurvePoint>, m: FloatArray, x: Float): Float {
        if (pts.size < 2) return x
        if (x <= pts.first().x) return pts.first().y
        if (x >= pts.last().x) return pts.last().y
        var k = 0
        while (k < pts.size - 2 && x > pts[k + 1].x) k++
        val p0 = pts[k]
        val p1 = pts[k + 1]
        val h = p1.x - p0.x
        if (h <= 1e-6f) return p1.y
        val t = (x - p0.x) / h
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * p0.y + (t3 - 2 * t2 + t) * h * m[k] +
            (-2 * t3 + 3 * t2) * p1.y + (t3 - t2) * h * m[k + 1]
        return y.coerceIn(0f, 1f)
    }

    /** Дотичні Фрітча–Карлсона. */
    private fun tangents(pts: List<CurvePoint>): FloatArray {
        val n = pts.size
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { i ->
            val h = pts[i + 1].x - pts[i].x
            if (h <= 1e-6f) 0f else (pts[i + 1].y - pts[i].y) / h
        }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / sqrt(s)
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
        return m
    }
}
