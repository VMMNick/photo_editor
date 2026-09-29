package com.photoeditor.app.image

import android.content.Context
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoeditor.app.TestFiles
import com.photoeditor.app.TestFiles.near
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.ui.EditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Детальні тайли: вирізання з оригіналу, EXIF-орієнтація, ретуш у тайлі, інтеграція з ViewModel. */
@RunWith(AndroidJUnit4::class)
class DetailTilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(name: String, w: Int, h: Int, orientation: Int? = null): File {
        val uri = TestFiles.writeJpeg(context, name, TestFiles.quadrants(w, h), orientation)
        return File(uri.path!!)
    }

    @Test
    fun decodesRegionAtFullResolution() {
        val f = file("detail_plain.jpg", 3000, 2000)
        val src = DetailSource.open(f, ExifInterface.ORIENTATION_NORMAL)!!
        src.use {
            assertEquals(3000, it.orientedWidth)
            // Центральна ділянка 10% × 10% = 300×200 пікселів оригіналу
            val tile = it.decode(floatArrayOf(0.45f, 0.45f, 0.55f, 0.55f), 2048, emptyList(), 600, 400)!!
            assertEquals(1, tile.sampleSize)
            assertTrue("ширина ${tile.bitmap.width}", kotlin.math.abs(tile.bitmap.width - 300) <= 2)
            // Лівий верхній кут тайла — червоний квадрант, правий нижній — жовтий
            assertTrue(near(tile.bitmap.getPixel(5, 5), Color.RED, 25))
            assertTrue(near(tile.bitmap.getPixel(tile.bitmap.width - 5, tile.bitmap.height - 5), Color.YELLOW, 25))
        }
    }

    @Test
    fun largeRegionIsDownsampledToScreen() {
        val src = DetailSource.open(file("detail_big.jpg", 4000, 3000), ExifInterface.ORIENTATION_NORMAL)!!
        src.use {
            // 4000 px на 1000 px екрана: ділимо на 4 (рівно 1000 px), але не більше
            val tile = it.decode(floatArrayOf(0f, 0f, 1f, 1f), 1000, emptyList(), 400, 300)!!
            assertEquals(4, tile.sampleSize)
            assertTrue(tile.bitmap.width in 1000..2000)
        }
    }

    @Test
    fun respectsExifRotation() {
        // Файл 400×200, але EXIF каже «повернути на 90°» — користувач бачить 200×400
        val src = DetailSource.open(file("detail_rot.jpg", 400, 200, ExifInterface.ORIENTATION_ROTATE_90), ExifInterface.ORIENTATION_ROTATE_90)!!
        src.use {
            assertEquals(200, it.orientedWidth)
            assertEquals(400, it.orientedHeight)
            // Верхня ліва чверть того, що бачить користувач, — це лівий НИЖНІЙ квадрант файлу (синій)
            val tile = it.decode(floatArrayOf(0f, 0f, 0.5f, 0.5f), 2048, emptyList(), 200, 400)!!
            assertEquals(100, tile.bitmap.width)
            assertEquals(200, tile.bitmap.height)
            assertTrue(near(tile.bitmap.getPixel(50, 100), Color.BLUE, 25))
        }
    }

    @Test
    fun retouchIsAppliedInsideTile() {
        val src = DetailSource.open(file("detail_heal.jpg", 2000, 1000), ExifInterface.ORIENTATION_NORMAL)!!
        src.use {
            val want = floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f)
            val plain = it.decode(want, 2048, emptyList(), 400, 200)!!
            // Коректор на межі квадрантів у центрі — пікселі там мають змінитися
            val healed = it.decode(want, 2048, listOf(HealOp(0.5f, 0.5f, 0.02f)), 400, 200)!!
            val cx = plain.bitmap.width / 2
            val cy = plain.bitmap.height / 2
            var changed = 0
            for (dy in -10..10 step 5) for (dx in -10..10 step 5) {
                if (plain.bitmap.getPixel(cx + dx, cy + dy) != healed.bitmap.getPixel(cx + dx, cy + dy)) changed++
            }
            assertTrue("ретуш у тайлі: змінено $changed", changed > 0)
            assertEquals(plain.bitmap.getPixel(2, 2), healed.bitmap.getPixel(2, 2))
        }
    }

    @Test
    fun unsupportedFileGivesNull() {
        val f = File(context.cacheDir, "not_image.dng").also { it.writeBytes(ByteArray(2000) { 9 }) }
        assertEquals(null, DetailSource.open(f, 1))
    }

    @Test
    fun viewModelProvidesTileForZoomedArea() {
        val instr = InstrumentationRegistry.getInstrumentation()
        var vm: EditorViewModel? = null
        instr.runOnMainSync { vm = EditorViewModel(context.applicationContext as android.app.Application) }
        val uri = TestFiles.writeJpeg(context, "vm_detail.jpg", TestFiles.quadrants(4000, 3000))
        instr.runOnMainSync { vm!!.open(uri) }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            var ready = false
            instr.runOnMainSync { ready = vm!!.info != null && !vm!!.loading }
            if (ready) break
            Thread.sleep(50)
        }
        instr.runOnMainSync {
            assertTrue("оригінал 4000 px більший за прев'ю", vm!!.detailAvailable)
            vm!!.requestDetail(floatArrayOf(0.4f, 0.4f, 0.6f, 0.6f), 1080)
        }
        var tile: DetailTile? = null
        val end2 = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end2 && tile == null) {
            instr.runOnMainSync { tile = vm!!.detailTile }
            Thread.sleep(50)
        }
        assertNotNull(tile)
        assertTrue("тайл різкіший за прев'ю: ${tile!!.bitmap.width} px на 20% ширини", tile!!.bitmap.width > 0.2f * vm!!.srcW * 1.5f)
        // Після ретушу тайл скидається (його треба перерахувати з новим списком операцій)
        instr.runOnMainSync {
            vm!!.heal(0.5f, 0.5f, 0.02f)
            assertEquals(null, vm!!.detailTile)
            vm!!.close()
        }
    }
}
