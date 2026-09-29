package com.photoeditor.app.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.photoeditor.app.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WebP", "image/webp", "webp"),
}

/** Збереження результату в галерею (Pictures/PhotoEditor). */
object GallerySaver {

    private const val ALBUM = "PhotoEditor"

    /**
     * @param exifSource оригінал, з якого переносяться метадані камери (лише для JPEG).
     *   GPS-координати навмисно НЕ копіюються — щоб не «світити» місце зйомки в соцмережах.
     */
    fun save(context: Context, bitmap: Bitmap, format: ExportFormat, quality: Int, baseName: String, exifSource: Uri? = null): Uri {
        val exif = if (format == ExportFormat.JPEG && exifSource != null) readExif(context, exifSource) else null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val clean = baseName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
        val fileName = "${clean}_edit_$stamp.${format.ext}"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error(context.getString(R.string.err_gallery_create))
            try {
                resolver.openOutputStream(uri)?.use { compress(bitmap, format, quality, it) }
                    ?: error(context.getString(R.string.err_gallery_write))
                if (exif != null) runCatching {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd -> writeExif(ExifInterface(pfd.fileDescriptor), exif) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { compress(bitmap, format, quality, it) }
            if (exif != null) runCatching { writeExif(ExifInterface(file.absolutePath), exif) }
            // Реєструємо файл у MediaStore — отримуємо content://-адресу, якою можна ділитися
            // (file:// в Intent на Android 7+ заборонений і падає з FileUriExposedException)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            runCatching { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                ?: run {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(format.mime), null)
                    Uri.fromFile(file)
                }
        }
    }

    /** Теги, що описують зйомку. Орієнтацію не беремо: пікселі вже повернуті. */
    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
    )

    private fun readExif(context: Context, uri: Uri): Map<String, String>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val src = ExifInterface(input)
            EXIF_TAGS.mapNotNull { tag -> src.getAttribute(tag)?.let { tag to it } }.toMap()
        }
    }.getOrNull()

    private fun writeExif(dst: ExifInterface, tags: Map<String, String>) {
        for ((k, v) in tags) dst.setAttribute(k, v)
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoEditor")
        dst.setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
        dst.saveAttributes()
    }

    private fun compress(b: Bitmap, f: ExportFormat, q: Int, out: java.io.OutputStream) {
        val cf = when (f) {
            ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
            ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (q >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        check(b.compress(cf, q, out)) { "Bitmap.compress failed" }
    }
}
