package com.photoeditor.app.ui

import com.photoeditor.app.model.NRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class CropHandle { TL, TR, BL, BR, L, R, T, B, MOVE }

/** Логіка перетягування рамки кадрування в нормалізованих координатах кадру. Без android.* */
object CropMath {
    const val MIN = 0.06f

    /**
     * @param rn пропорція w/h у НОРМАЛІЗОВАНИХ координатах (null — вільна)
     * @param dx,dy зсув пальця з початку жесту (нормалізований)
     */
    fun drag(start: NRect, h: CropHandle, dx: Float, dy: Float, rn: Float?): NRect {
        if (h == CropHandle.MOVE) {
            val nx = (start.l + dx).coerceIn(0f, 1f - start.w)
            val ny = (start.t + dy).coerceIn(0f, 1f - start.h)
            return NRect(nx, ny, nx + start.w, ny + start.h)
        }
        if (rn == null) {
            var l = start.l; var t = start.t; var r = start.r; var b = start.b
            when (h) {
                CropHandle.TL -> { l += dx; t += dy }
                CropHandle.TR -> { r += dx; t += dy }
                CropHandle.BL -> { l += dx; b += dy }
                CropHandle.BR -> { r += dx; b += dy }
                CropHandle.L -> l += dx
                CropHandle.R -> r += dx
                CropHandle.T -> t += dy
                CropHandle.B -> b += dy
                CropHandle.MOVE -> Unit
            }
            l = l.coerceIn(0f, start.r - MIN); r = r.coerceIn(start.l + MIN, 1f)
            if (h == CropHandle.L || h == CropHandle.TL || h == CropHandle.BL) r = start.r
            if (h == CropHandle.R || h == CropHandle.TR || h == CropHandle.BR) l = start.l
            t = t.coerceIn(0f, start.b - MIN); b = b.coerceIn(start.t + MIN, 1f)
            if (h == CropHandle.T || h == CropHandle.TL || h == CropHandle.TR) b = start.b
            if (h == CropHandle.B || h == CropHandle.BL || h == CropHandle.BR) t = start.t
            return NRect(l, t, r, b)
        }
        return when (h) {
            CropHandle.TL, CropHandle.TR, CropHandle.BL, CropHandle.BR -> corner(start, h, dx, dy, rn)
            else -> edge(start, h, dx, dy, rn)
        }
    }

    private fun corner(s: NRect, h: CropHandle, dx: Float, dy: Float, rn: Float): NRect {
        // Протилежний кут залишається на місці
        val ax = if (h == CropHandle.TL || h == CropHandle.BL) s.r else s.l
        val ay = if (h == CropHandle.TL || h == CropHandle.TR) s.b else s.t
        val cx = (if (h == CropHandle.TL || h == CropHandle.BL) s.l else s.r) + dx
        val cy = (if (h == CropHandle.TL || h == CropHandle.TR) s.t else s.b) + dy
        val sx = if (h == CropHandle.TL || h == CropHandle.BL) -1f else 1f
        val sy = if (h == CropHandle.TL || h == CropHandle.TR) -1f else 1f
        var w = max(MIN, (cx - ax) * sx)
        var hh = max(MIN, (cy - ay) * sy)
        if (w / hh > rn) hh = w / rn else w = hh * rn
        // Обмеження межами кадру
        val maxW = if (sx > 0) 1f - ax else ax
        val maxH = if (sy > 0) 1f - ay else ay
        val k = min(1f, min(maxW / w, maxH / hh))
        w *= k; hh *= k
        val l = if (sx > 0) ax else ax - w
        val t = if (sy > 0) ay else ay - hh
        return NRect(l, t, l + w, t + hh)
    }

    private fun edge(s: NRect, h: CropHandle, dx: Float, dy: Float, rn: Float): NRect {
        val cx = (s.l + s.r) / 2
        val cy = (s.t + s.b) / 2
        var w: Float
        var hh: Float
        when (h) {
            CropHandle.L, CropHandle.R -> {
                w = max(MIN, if (h == CropHandle.R) s.w + dx else s.w - dx)
                hh = w / rn
            }
            else -> {
                hh = max(MIN, if (h == CropHandle.B) s.h + dy else s.h - dy)
                w = hh * rn
            }
        }
        // Якір — протилежне ребро; інша вісь центрується
        val maxW = when (h) { CropHandle.R -> 1f - s.l; CropHandle.L -> s.r; else -> 2 * min(cx, 1f - cx) }
        val maxH = when (h) { CropHandle.B -> 1f - s.t; CropHandle.T -> s.b; else -> 2 * min(cy, 1f - cy) }
        val k = min(1f, min(maxW / w, maxH / hh))
        w *= k; hh *= k
        return when (h) {
            CropHandle.R -> NRect(s.l, cy - hh / 2, s.l + w, cy + hh / 2)
            CropHandle.L -> NRect(s.r - w, cy - hh / 2, s.r, cy + hh / 2)
            CropHandle.B -> NRect(cx - w / 2, s.t, cx + w / 2, s.t + hh)
            else -> NRect(cx - w / 2, s.b - hh, cx + w / 2, s.b)
        }
    }

    /** Який маркер під пальцем (координати в пікселях екрана). */
    fun hit(px: Float, py: Float, l: Float, t: Float, r: Float, b: Float, touch: Float): CropHandle? {
        fun near(a: Float, c: Float) = abs(a - c) <= touch
        val inX = px in (l - touch)..(r + touch)
        val inY = py in (t - touch)..(b + touch)
        if (!inX || !inY) return null
        return when {
            near(px, l) && near(py, t) -> CropHandle.TL
            near(px, r) && near(py, t) -> CropHandle.TR
            near(px, l) && near(py, b) -> CropHandle.BL
            near(px, r) && near(py, b) -> CropHandle.BR
            near(px, l) -> CropHandle.L
            near(px, r) -> CropHandle.R
            near(py, t) -> CropHandle.T
            near(py, b) -> CropHandle.B
            px in l..r && py in t..b -> CropHandle.MOVE
            else -> null
        }
    }
}
