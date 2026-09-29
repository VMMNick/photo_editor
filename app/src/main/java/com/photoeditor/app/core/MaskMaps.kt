package com.photoeditor.app.core

import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import kotlin.math.max
import kotlin.math.roundToInt

/** Готує растрові карти для масок-пензлів та AI-масок об'єкта. */
object MaskMaps {

    /** Роздільність карти пензля (довша сторона). Маска м'яка, тож більше не потрібно. */
    const val BRUSH_RES = 1024

    /** Розмір карти пензля для джерела srcW×srcH. */
    fun brushMapSize(srcW: Int, srcH: Int): IntArray {
        val k = BRUSH_RES.toFloat() / max(srcW, srcH)
        return intArrayOf((srcW * k).roundToInt().coerceAtLeast(1), (srcH * k).roundToInt().coerceAtLeast(1))
    }

    /**
     * @param withoutLast готова карта для тих самих мазків, крім останнього (кеш під час малювання).
     *   Тоді домальовується лише останній мазок — один рух пальця коштує O(площа мазка), а не O(усі мазки).
     */
    fun forMask(m: Mask, srcW: Int, srcH: Int, withoutLast: AlphaMap? = null): AlphaMap? {
        val base: AlphaMap?
        val (w, h) = when (m.kind) {
            MaskKind.BRUSH -> { base = null; brushMapSize(srcW, srcH).let { it[0] to it[1] } }
            MaskKind.SUBJECT -> {
                val subject = m.subject ?: return null
                if (m.strokes.isEmpty()) return subject
                base = subject
                subject.width to subject.height
            }
            else -> return null
        }
        if (withoutLast != null && m.strokes.isNotEmpty() && withoutLast.width == w && withoutLast.height == h) {
            return MaskRaster.rasterize(listOf(m.strokes.last()), w, h, srcW, srcH, withoutLast)
        }
        return MaskRaster.rasterize(m.strokes, w, h, srcW, srcH, base)
    }
}
