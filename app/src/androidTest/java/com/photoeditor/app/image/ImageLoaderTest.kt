package com.photoeditor.app.image

import android.content.Context
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoeditor.app.TestFiles
import com.photoeditor.app.TestFiles.near
import com.photoeditor.app.core.Retouch
import com.photoeditor.app.model.HealOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadsJpegWithoutRotation() {
        val uri = TestFiles.writeJpeg(context, "plain.jpg", TestFiles.quadrants(400, 200))
        val r = ImageLoader.load(context, uri, maxSide = 2048)
        assertEquals(400, r.bitmap.width)
        assertEquals(200, r.bitmap.height)
        assertEquals(400, r.fullWidth)
        assertFalse(r.isRaw)
        assertEquals("plain.jpg", r.displayName)
        assertTrue(near(r.bitmap.getPixel(20, 20), Color.RED, 20))
    }

    @Test
    fun appliesExifRotation() {
        val uri = TestFiles.writeJpeg(context, "rot90.jpg", TestFiles.quadrants(400, 200), ExifInterface.ORIENTATION_ROTATE_90)
        val r = ImageLoader.load(context, uri, maxSide = 2048)
        assertEquals(200, r.bitmap.width)
        assertEquals(400, r.bitmap.height)
        assertEquals(200, r.fullWidth)
        assertEquals(400, r.fullHeight)
        // Поворот на 90° за годинниковою: лівий верхній кут тепер синій (був лівий нижній)
        assertTrue(near(r.bitmap.getPixel(20, 20), Color.BLUE, 20))
    }

    @Test
    fun appliesExifMirror() {
        val uri = TestFiles.writeJpeg(context, "flip.jpg", TestFiles.quadrants(400, 200), ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        val r = ImageLoader.load(context, uri, maxSide = 2048)
        assertTrue(near(r.bitmap.getPixel(20, 20), Color.GREEN, 20))
    }

    @Test
    fun downscalesToMaxSideKeepingAspect() {
        val uri = TestFiles.writeJpeg(context, "big.jpg", TestFiles.quadrants(3000, 1000))
        val r = ImageLoader.load(context, uri, maxSide = 1200)
        assertEquals(1200, r.bitmap.width)
        assertEquals(400, r.bitmap.height)
        assertEquals(3000, r.fullWidth) // інформація про оригінал зберігається
    }

    @Test
    fun respectsPixelBudget() {
        val uri = TestFiles.writeJpeg(context, "budget.jpg", TestFiles.quadrants(2000, 2000))
        val r = ImageLoader.load(context, uri, maxSide = 4096, maxPixels = 1_000_000)
        assertTrue(r.bitmap.width.toLong() * r.bitmap.height <= 1_000_000L + 4000)
        assertEquals(r.bitmap.width, r.bitmap.height)
    }

    @Test
    fun proprietaryRawFallsBackToEmbeddedPreview() {
        val uri = TestFiles.writeFakeRaw(context, "IMG_0001.NEF", TestFiles.quadrants(800, 600))
        assertTrue(ImageLoader.isRaw(context, uri))
        val r = ImageLoader.load(context, uri, maxSide = 2048)
        assertTrue(r.isRaw)
        assertTrue(r.usedEmbeddedPreview)
        assertEquals("обрано повнорозмірне прев'ю, а не мініатюру", 800, r.bitmap.width)
        assertTrue(near(r.bitmap.getPixel(700, 500), Color.YELLOW, 25))
    }

    @Test
    fun rawExtensionsRecognised() {
        for (ext in listOf("dng", "CR2", "cr3", "nef", "ARW", "orf", "rw2", "raf")) {
            val f = File(context.cacheDir, "x.$ext").also { it.writeBytes(byteArrayOf(1)) }
            assertTrue(ext, ImageLoader.isRaw(context, android.net.Uri.fromFile(f)))
        }
        val jpg = File(context.cacheDir, "x.jpg").also { it.writeBytes(byteArrayOf(1)) }
        assertFalse(ImageLoader.isRaw(context, android.net.Uri.fromFile(jpg)))
    }

    @Test(expected = IllegalStateException::class)
    fun garbageFileThrows() {
        val f = File(context.cacheDir, "broken.jpg").also { it.writeBytes(ByteArray(500) { 3 }) }
        ImageLoader.load(context, android.net.Uri.fromFile(f), 1024)
    }

    @Test
    fun bitmapPixelImageRoundTrip() {
        val b = TestFiles.quadrants(64, 32)
        val px = b.toPixelImage()
        assertEquals(64, px.width)
        assertEquals(b.getPixel(50, 5), px[50, 5])
        val back = Retouch.heal(px, HealOp(0.5f, 0.5f, 0.01f)).toBitmap()
        assertEquals(64, back.width)
        assertEquals(b.getPixel(2, 2), back.getPixel(2, 2))
    }
}
