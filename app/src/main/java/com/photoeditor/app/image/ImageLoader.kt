package com.photoeditor.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.photoeditor.app.R
import com.photoeditor.app.core.DetailMath
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Завантаження JPEG/PNG/WebP/HEIC та RAW.
 *
 * RAW: Android (Skia) вміє декодувати DNG через BitmapFactory. Для пропрієтарних форматів
 * (CR2/CR3/NEF/ARW/ORF/RW2/RAF…) декодер системи недоступний, тому беремо вбудоване
 * повнорозмірне JPEG-прев'ю, яке камери зберігають усередині RAW-файлу.
 */
object ImageLoader {

    data class Loaded(
        val bitmap: Bitmap,
        val isRaw: Boolean,
        /** Роздільність оригіналу (після EXIF-повороту). */
        val fullWidth: Int,
        val fullHeight: Int,
        val displayName: String,
        /** Для RAW: декодовано вбудоване JPEG-прев'ю замість сирих даних. */
        val usedEmbeddedPreview: Boolean,
        /** EXIF-орієнтація файлу, вже застосована до [bitmap] (потрібна для детальних тайлів). */
        val orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    )

    private val RAW_EXT = setOf("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "pef", "srw", "x3f", "3fr", "iiq", "rwl", "erf", "kdc", "mrw", "mos")

    fun isRaw(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        // image/x-adobe-dng, image/x-canon-cr2, image/x-nikon-nef, image/x-sony-arw… — але не image/x-icon чи BMP
        if (mime.contains("dng") || mime.contains("raw") || RAW_EXT.any { mime.endsWith("-$it") }) return true
        val ext = displayName(context, uri).substringAfterLast('.', "").lowercase()
        return ext in RAW_EXT
    }

    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
    }

    /**
     * @param maxSide максимальна довша сторона результату
     * @param maxPixels обмеження кількості пікселів (пам'ять)
     */
    /**
     * @param embeddedPreviewOut якщо для RAW використано вбудоване JPEG-прев'ю — зберегти його сюди
     *   (з нього потім вирізаються детальні тайли при збільшенні)
     */
    fun load(context: Context, uri: Uri, maxSide: Int, maxPixels: Long = Long.MAX_VALUE, embeddedPreviewOut: File? = null): Loaded {
        val raw = isRaw(context, uri)
        val name = displayName(context, uri)
        val orientation = readOrientation(context, uri)

        var usedPreview = false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var bitmap: Bitmap? = null
        var fullW = bounds.outWidth
        var fullH = bounds.outHeight
        if (fullW > 0 && fullH > 0) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(fullW, fullH, maxSide, maxPixels)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        if (bitmap == null && raw) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(context.getString(R.string.err_read_file))
            val jpeg = RawPreviewExtractor.largestJpeg(bytes) ?: error(context.getString(R.string.err_raw_no_image))
            val b2 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, jpeg.first, jpeg.second, b2)
            fullW = b2.outWidth
            fullH = b2.outHeight
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(fullW, fullH, maxSide, maxPixels)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, jpeg.first, jpeg.second, opts)
            usedPreview = true
            embeddedPreviewOut?.let { f -> runCatching { f.outputStream().use { it.write(bytes, jpeg.first, jpeg.second) } } }
        }
        bitmap ?: error(context.getString(R.string.err_unsupported_format))

        bitmap = ensureArgb(bitmap)
        bitmap = applyOrientation(bitmap, orientation)
        bitmap = fitInto(bitmap, maxSide, maxPixels)

        val swap = orientation in listOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
        )
        return Loaded(
            bitmap = bitmap,
            isRaw = raw,
            fullWidth = if (swap) fullH else fullW,
            fullHeight = if (swap) fullW else fullH,
            displayName = name,
            usedEmbeddedPreview = usedPreview,
            orientation = orientation,
        )
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int, maxPixels: Long): Int =
        DetailMath.decodeSampleSize(w, h, maxSide, maxPixels)

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun ensureArgb(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, true)
        b.recycle()
        return c
    }

    private fun applyOrientation(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    fun fitInto(b: Bitmap, maxSide: Int, maxPixels: Long = Long.MAX_VALUE): Bitmap {
        var k = minOf(1f, maxSide.toFloat() / max(b.width, b.height))
        val px = b.width.toLong() * b.height
        if (px * k * k > maxPixels) k = kotlin.math.sqrt(maxPixels.toDouble() / px).toFloat()
        if (k >= 0.999f) return b
        val r = Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
        if (r !== b) b.recycle()
        return r
    }
}
