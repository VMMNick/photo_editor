package com.photoeditor.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.DetailMath
import com.photoeditor.app.model.RetouchOp
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Фрагмент оригіналу в повній роздільності для збільшеного прев'ю.
 * @param rect область у uv джерела (l, t, r, b) — у тих самих координатах, що й прев'ю
 * @param coverage де показувати прев'ю замість тайла (області AI-ластика) або null
 */
class DetailTile(
    val bitmap: Bitmap,
    val rect: FloatArray,
    val sampleSize: Int,
    val coverage: AlphaMap?,
    /** Список ретушу, з яким зроблено тайл (порівнюється за ідентичністю). */
    val ops: List<RetouchOp> = emptyList(),
)

/**
 * Декодер фрагментів. Працює з JPEG/PNG/WebP/HEIF (BitmapRegionDecoder);
 * для RAW використовується збережене вбудоване прев'ю. DNG system decoder фрагментів не вміє — тоді null.
 */
class DetailSource private constructor(
    private val decoder: BitmapRegionDecoder,
    /** EXIF-орієнтація, яку ImageLoader застосував до прев'ю. */
    private val orientation: Int,
) : AutoCloseable {

    val rawWidth get() = decoder.width
    val rawHeight get() = decoder.height

    /** Ширина/висота в «повернутих» координатах (як бачить користувач). */
    private val swapped = orientation in listOf(DetailMath.ROTATE_90, DetailMath.ROTATE_270, DetailMath.TRANSPOSE, DetailMath.TRANSVERSE)
    val orientedWidth get() = if (swapped) rawHeight else rawWidth
    val orientedHeight get() = if (swapped) rawWidth else rawHeight

    /**
     * @param want бажана область (uv джерела)
     * @param targetMaxSide скільки пікселів вона займе на екрані (довша сторона)
     */
    @Synchronized
    fun decode(want: FloatArray, targetMaxSide: Int, ops: List<RetouchOp>, srcW: Int, srcH: Int): DetailTile? {
        if (decoder.isRecycled) return null
        val raw = DetailMath.orientedRectToRaw(want, orientation)
        val rect = Rect(
            floor(raw[0] * rawWidth).toInt().coerceIn(0, rawWidth - 1),
            floor(raw[1] * rawHeight).toInt().coerceIn(0, rawHeight - 1),
            ceil(raw[2] * rawWidth).toInt().coerceIn(1, rawWidth),
            ceil(raw[3] * rawHeight).toInt().coerceIn(1, rawHeight),
        )
        if (rect.width() < 2 || rect.height() < 2) return null
        val sample = DetailMath.sampleSize(max(rect.width(), rect.height()), targetMaxSide)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = orient(decoder.decodeRegion(rect, opts) ?: return null)
        // Фактично покрита область (після округлення до пікселів) — назад у повернуті uv
        val covered = DetailMath.rawRectToOriented(
            floatArrayOf(
                rect.left / rawWidth.toFloat(), rect.top / rawHeight.toFloat(),
                rect.right / rawWidth.toFloat(), rect.bottom / rawHeight.toFloat(),
            ),
            orientation,
        )
        if (ops.isEmpty()) return DetailTile(bmp, covered, sample, null, ops)

        // Ретуш: коректор і штамп — по-справжньому на пікселях тайла, AI-ластик — через покриття
        val res = DetailMath.applyRetouch(bmp.toPixelImage(), covered, srcW, srcH, ops)
        val out = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
        res.image.writeInto(out)
        return DetailTile(out, covered, sample, res.coverage, ops)
    }

    private fun orient(b: Bitmap): Bitmap {
        val m = Matrix()
        when (orientation) {
            DetailMath.ROTATE_90 -> m.postRotate(90f)
            DetailMath.ROTATE_180 -> m.postRotate(180f)
            DetailMath.ROTATE_270 -> m.postRotate(270f)
            DetailMath.FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            DetailMath.FLIP_VERTICAL -> m.postScale(1f, -1f)
            DetailMath.TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            DetailMath.TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    override fun close() = decoder.recycle()

    companion object {
        /** null — формат не підтримує декодування фрагментів (напр. DNG) або файл недоступний. */
        fun open(file: File, orientation: Int): DetailSource? = runCatching {
            val decoder = FileInputStream(file).use { input ->
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(input)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(input, false)
            } ?: return null
            DetailSource(decoder, orientation)
        }.getOrNull()

        /** Розмір тайла: не більше, ніж треба для екрана, і не більше за ліміт текстури прев'ю. */
        fun targetSide(visibleScreenPx: Int): Int = min(max(visibleScreenPx, 256), 2048)
    }
}
