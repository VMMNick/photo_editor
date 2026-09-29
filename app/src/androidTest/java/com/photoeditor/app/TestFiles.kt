package com.photoeditor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Тестові зображення, які генеруються на льоту в кеш-папці застосунку. */
object TestFiles {

    /** Чотири кольорові квадранти: TL червоний, TR зелений, BL синій, BR жовтий. */
    fun quadrants(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, w / 2f, h / 2f, p)
        p.color = Color.GREEN; c.drawRect(w / 2f, 0f, w.toFloat(), h / 2f, p)
        p.color = Color.BLUE; c.drawRect(0f, h / 2f, w / 2f, h.toFloat(), p)
        p.color = Color.YELLOW; c.drawRect(w / 2f, h / 2f, w.toFloat(), h.toFloat(), p)
        return b
    }

    fun solid(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    fun jpegBytes(b: Bitmap, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    fun writeJpeg(context: Context, name: String, b: Bitmap, orientation: Int? = null): Uri {
        val f = File(context.cacheDir, name)
        f.writeBytes(jpegBytes(b))
        if (orientation != null) {
            ExifInterface(f.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return Uri.fromFile(f)
    }

    /** «RAW» пропрієтарного формату: сміття + мініатюра + повнорозмірне прев'ю, як у NEF/CR2. */
    fun writeFakeRaw(context: Context, name: String, preview: Bitmap): Uri {
        val thumb = Bitmap.createScaledBitmap(preview, 64, 48, true)
        // Заголовок навмисно не TIFF, щоб системний декодер гарантовано не впорався і спрацював наш fallback
        val bytes = "FAKERAW".toByteArray() + ByteArray(4000) { (it * 31).toByte() } +
            jpegBytes(thumb) + ByteArray(1000) + jpegBytes(preview) + ByteArray(2000) { 7 }
        val f = File(context.cacheDir, name)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    fun rgb(p: Int) = intArrayOf(Color.red(p), Color.green(p), Color.blue(p))

    fun near(p: Int, color: Int, tol: Int = 12): Boolean {
        val a = rgb(p)
        val b = rgb(color)
        return (0..2).all { kotlin.math.abs(a[it] - b[it]) <= tol }
    }
}
