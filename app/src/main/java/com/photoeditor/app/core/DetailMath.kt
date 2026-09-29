package com.photoeditor.app.core

import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.RetouchOp
import com.photoeditor.app.model.StrokePoint
import kotlin.math.max
import kotlin.math.min

/**
 * Математика детального тайла (фрагмент оригіналу в повній роздільності при збільшенні).
 * Без android.* — тестується на JVM.
 */
object DetailMath {

    // Значення EXIF TAG_ORIENTATION (однакові в androidx.exifinterface і в стандарті EXIF)
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    /**
     * uv у «повернутому» зображенні (як його бачить користувач) → uv у збережених пікселях файлу.
     * Саме в координатах файлу працює BitmapRegionDecoder.
     */
    fun orientedToRaw(u: Float, v: Float, orientation: Int): FloatArray = when (orientation) {
        FLIP_HORIZONTAL -> floatArrayOf(1 - u, v)
        ROTATE_180 -> floatArrayOf(1 - u, 1 - v)
        FLIP_VERTICAL -> floatArrayOf(u, 1 - v)
        TRANSPOSE -> floatArrayOf(v, u)
        ROTATE_90 -> floatArrayOf(v, 1 - u)
        TRANSVERSE -> floatArrayOf(1 - v, 1 - u)
        ROTATE_270 -> floatArrayOf(1 - v, u)
        else -> floatArrayOf(u, v)
    }

    /** Зворотне до [orientedToRaw]. */
    fun rawToOriented(u: Float, v: Float, orientation: Int): FloatArray = when (orientation) {
        FLIP_HORIZONTAL -> floatArrayOf(1 - u, v)
        ROTATE_180 -> floatArrayOf(1 - u, 1 - v)
        FLIP_VERTICAL -> floatArrayOf(u, 1 - v)
        TRANSPOSE -> floatArrayOf(v, u)
        ROTATE_90 -> floatArrayOf(1 - v, u)
        TRANSVERSE -> floatArrayOf(1 - v, 1 - u)
        ROTATE_270 -> floatArrayOf(v, 1 - u)
        else -> floatArrayOf(u, v)
    }

    /** Прямокутник (l, t, r, b) у повернутих uv → прямокутник у uv файлу. */
    fun orientedRectToRaw(r: FloatArray, orientation: Int): FloatArray {
        val a = orientedToRaw(r[0], r[1], orientation)
        val b = orientedToRaw(r[2], r[3], orientation)
        return floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1]))
    }

    fun rawRectToOriented(r: FloatArray, orientation: Int): FloatArray {
        val a = rawToOriented(r[0], r[1], orientation)
        val b = rawToOriented(r[2], r[3], orientation)
        return floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1]))
    }

    /** Чи потрібен тайл: на екрані один піксель прев'ю розтягнуто більш ніж на [threshold] пікселя. */
    fun needsDetail(screenPxPerPreviewPx: Float, fullWidth: Int, previewWidth: Int, threshold: Float = 1.15f): Boolean =
        screenPxPerPreviewPx > threshold && fullWidth > previewWidth * 1.1f

    /** Найбільший степінь двійки, за якого область [regionPx] ще не менша за [targetPx]. */
    /**
     * inSampleSize для повного декодування: найбільший степінь двійки, за якого довша сторона ще ≥ maxSide
     * і кількість пікселів не більша за 4 × maxPixels (решту доріже fitInto).
     * Безпечний для maxPixels = Long.MAX_VALUE і нульових розмірів (раніше тут було переповнення → ділення на 0).
     */
    fun decodeSampleSize(w: Int, h: Int, maxSide: Int, maxPixels: Long = Long.MAX_VALUE): Int {
        val pixelLimit = if (maxPixels > Long.MAX_VALUE / 4) Long.MAX_VALUE else maxPixels * 4
        var s = 1
        while (s < (1 shl 20)) {
            val next = s * 2
            val bySide = maxSide > 0 && maxOf(w, h) / next >= maxSide
            val byPixels = (w.toLong() / s) * (h.toLong() / s) > pixelLimit
            if (!bySide && !byPixels) break
            s = next
        }
        return s
    }

    fun sampleSize(regionPx: Int, targetPx: Int): Int {
        var s = 1
        while (regionPx / (s * 2) >= targetPx) s *= 2
        return s
    }

    /** Чи покриває вже завантажений тайл потрібну область з достатньою деталізацією. */
    fun covers(have: FloatArray, haveSample: Int, want: FloatArray, wantSample: Int): Boolean =
        have[0] <= want[0] + 1e-4f && have[1] <= want[1] + 1e-4f && have[2] >= want[2] - 1e-4f && have[3] >= want[3] - 1e-4f &&
            haveSample <= wantSample

    /**
     * Застосувати ретуш до тайла. Коректор і штамп перераховуються в координати тайла й виконуються
     * по-справжньому (результат різкий). AI-ластик на тайлі не запускаємо — його області позначаються
     * в [Result.coverage], і там шейдер показує вже оброблене прев'ю.
     *
     * @param rect область тайла в uv джерела (l, t, r, b)
     * @param srcW,srcH розміри джерела в будь-якому масштабі — важлива лише пропорція (для радіусів)
     */
    fun applyRetouch(tile: PixelImage, rect: FloatArray, srcW: Int, srcH: Int, ops: List<RetouchOp>): Result {
        val rw = rect[2] - rect[0]
        val rh = rect[3] - rect[1]
        val tileMax = max(tile.width, tile.height).toFloat()
        // квадратна одиниця джерела → квадратна одиниця тайла
        val radiusScale = max(srcW, srcH).toFloat() / srcW * tile.width / rw / tileMax
        fun pt(p: StrokePoint) = StrokePoint((p.u - rect[0]) / rw, (p.v - rect[1]) / rh)
        fun stroke(s: BrushStroke) = s.copy(points = s.points.map(::pt), radius = s.radius * radiusScale)

        var img = tile
        var coverage: AlphaMap? = null
        for (op in ops) {
            when (op) {
                is HealOp -> {
                    // Радіус у частках ширини/висоти джерела; пропускаємо плями далеко за межами тайла
                    val ru = op.radius * max(srcW, srcH) / srcW
                    val rv = op.radius * max(srcW, srcH) / srcH
                    if (op.u < rect[0] - 2 * ru || op.u > rect[2] + 2 * ru || op.v < rect[1] - 2 * rv || op.v > rect[3] + 2 * rv) continue
                    val t = pt(StrokePoint(op.u, op.v))
                    // Тайл належить нам (свіжо декодований) — пишемо на місці, без копій
                    img = Retouch.apply(img, HealOp(t.u, t.v, op.radius * radiusScale), inPlace = true)
                }
                is CloneOp -> {
                    val s = stroke(op.stroke)
                    img = Retouch.apply(img, CloneOp(s, op.du / rw, op.dv / rh))
                }
                is EraseOp -> {
                    val cw = max(1, tile.width / 4)
                    val ch = max(1, tile.height / 4)
                    val cov = coverage ?: AlphaMap(cw, ch)
                    var any = false
                    for (y in 0 until ch) {
                        val v = rect[1] + (y + 0.5f) / ch * rh
                        for (x in 0 until cw) {
                            val u = rect[0] + (x + 0.5f) / cw * rw
                            val a = op.mask.sampleUv(u, v)
                            if (a > 0.02f) {
                                // Трохи розширюємо: заповнення ластика виходить за межі маски на 2 px
                                val value = min(255, (a * 255 * 1.5f).toInt())
                                val i = y * cw + x
                                if (value > (cov.data[i].toInt() and 0xFF)) { cov.data[i] = value.toByte(); any = true }
                            }
                        }
                    }
                    if (any) coverage = cov
                }
            }
        }
        return Result(img, coverage?.let { dilate(it, 2) })
    }

    class Result(val image: PixelImage, val coverage: AlphaMap?)

    private fun dilate(m: AlphaMap, r: Int): AlphaMap {
        val out = AlphaMap(m.width, m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            var best = 0
            for (dy in -r..r) {
                val yy = y + dy
                if (yy !in 0 until m.height) continue
                for (dx in -r..r) {
                    val xx = x + dx
                    if (xx !in 0 until m.width) continue
                    best = max(best, m.at(xx, yy))
                }
            }
            out.data[y * m.width + x] = best.toByte()
        }
        return out
    }
}
