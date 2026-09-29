package com.photoeditor.app.core

import com.photoeditor.app.model.BrushStroke
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Растеризація мазків пензля в AlphaMap (без android.graphics — працює й у тестах).
 *
 * Малює одразу в байти карти й торкається лише пікселів під відбитками пензля,
 * без повних проходів по всій карті — тому мазок пальцем не гальмує навіть на 1024 px.
 */
object MaskRaster {

    /**
     * Нова карта: копія [base] (або порожня) + мазки.
     * @param w,h роздільність результату
     * @param srcW,srcH розміри джерела — щоб перевести квадратні одиниці радіуса в пікселі
     */
    fun rasterize(strokes: List<BrushStroke>, w: Int, h: Int, srcW: Int, srcH: Int, base: AlphaMap? = null): AlphaMap {
        val out = when {
            base == null -> AlphaMap(w, h)
            base.width == w && base.height == h -> AlphaMap(w, h, base.data.copyOf())
            else -> base.resized(w, h) // resized() вже повертає нову карту
        }
        paintInto(out, strokes, srcW, srcH)
        return out
    }

    /** Домалювати мазки прямо в [map] (без копіювання). */
    fun paintInto(map: AlphaMap, strokes: List<BrushStroke>, srcW: Int, srcH: Int) {
        val pxPerSquare = max(map.width * max(srcW, srcH) / srcW.toFloat(), map.height * max(srcW, srcH) / srcH.toFloat())
        for (s in strokes) stroke(map, s, s.radius * pxPerSquare)
    }

    private fun stroke(map: AlphaMap, s: BrushStroke, radiusPx: Float) {
        if (s.points.isEmpty() || radiusPx <= 0.3f) return
        val w = map.width
        val h = map.height
        val spacing = max(1f, radiusPx * 0.25f)
        var prevX = s.points[0].u * w
        var prevY = s.points[0].v * h
        dab(map, prevX, prevY, radiusPx, s.hardness, s.erase)
        for (i in 1 until s.points.size) {
            val x = s.points[i].u * w
            val y = s.points[i].v * h
            val dist = hypot(x - prevX, y - prevY)
            val n = floor(dist / spacing).toInt()
            for (k in 1..n) {
                val t = k * spacing / dist
                dab(map, prevX + (x - prevX) * t, prevY + (y - prevY) * t, radiusPx, s.hardness, s.erase)
            }
            if (n > 0) {
                val t = n * spacing / dist
                prevX += (x - prevX) * t
                prevY += (y - prevY) * t
            }
        }
    }

    /** Один відбиток пензля: max-накладання (без «накопичення» при повторних проходах). */
    private fun dab(map: AlphaMap, cx: Float, cy: Float, r: Float, hardness: Float, erase: Boolean) {
        val w = map.width
        val data = map.data
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(w - 1, ceil(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(map.height - 1, ceil(cy + r).toInt())
        val inner = hardness.coerceIn(0f, 0.99f)
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val d = sqrt(dx * dx + dy * dy) / r
                if (d >= 1f) continue
                val a = if (d <= inner) 1f else smooth(1f - (d - inner) / (1f - inner))
                val v = (a * 255f).roundToInt()
                val i = y * w + x
                val cur = data[i].toInt() and 0xFF
                val next = if (erase) min(cur, 255 - v) else max(cur, v)
                if (next != cur) data[i] = next.toByte()
            }
        }
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
}
