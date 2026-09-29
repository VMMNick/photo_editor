package com.photoeditor.app.core

import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.RetouchOp
import com.photoeditor.app.model.StrokePoint
import com.photoeditor.app.testutil.TestImages
import com.photoeditor.app.testutil.TestImages.g
import com.photoeditor.app.testutil.TestImages.r
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailMathTest {

    private val all = (1..8).toList()

    private fun assertPoint(expU: Float, expV: Float, p: FloatArray, msg: String) {
        assertEquals("$msg u", expU, p[0], 1e-6f)
        assertEquals("$msg v", expV, p[1], 1e-6f)
    }

    @Test
    fun rawCornerLandsWhereExifSays() {
        // Куди потрапляє лівий верхній кут файлу після застосування EXIF-орієнтації (за стандартом EXIF)
        assertPoint(0f, 0f, DetailMath.rawToOriented(0f, 0f, DetailMath.NORMAL), "1")
        assertPoint(1f, 0f, DetailMath.rawToOriented(0f, 0f, DetailMath.FLIP_HORIZONTAL), "2")
        assertPoint(1f, 1f, DetailMath.rawToOriented(0f, 0f, DetailMath.ROTATE_180), "3")
        assertPoint(0f, 1f, DetailMath.rawToOriented(0f, 0f, DetailMath.FLIP_VERTICAL), "4")
        assertPoint(0f, 0f, DetailMath.rawToOriented(0f, 0f, DetailMath.TRANSPOSE), "5")
        assertPoint(1f, 0f, DetailMath.rawToOriented(0f, 0f, DetailMath.ROTATE_90), "6")
        assertPoint(1f, 1f, DetailMath.rawToOriented(0f, 0f, DetailMath.TRANSVERSE), "7")
        assertPoint(0f, 1f, DetailMath.rawToOriented(0f, 0f, DetailMath.ROTATE_270), "8")
        // Правий верхній кут файлу для транспонування та повороту на 90°
        assertPoint(0f, 1f, DetailMath.rawToOriented(1f, 0f, DetailMath.TRANSPOSE), "5 TR")
        assertPoint(1f, 1f, DetailMath.rawToOriented(1f, 0f, DetailMath.ROTATE_90), "6 TR")
    }

    @Test
    fun orientationMappingsAreInverse() {
        for (o in all) for ((u, v) in listOf(0.1f to 0.7f, 0.9f to 0.2f, 0.5f to 0.5f)) {
            val raw = DetailMath.orientedToRaw(u, v, o)
            assertPoint(u, v, DetailMath.rawToOriented(raw[0], raw[1], o), "o=$o")
        }
    }

    @Test
    fun rectMappingKeepsAreaAndOrder() {
        val rect = floatArrayOf(0.1f, 0.2f, 0.4f, 0.9f)
        for (o in all) {
            val raw = DetailMath.orientedRectToRaw(rect, o)
            assertTrue(raw[0] < raw[2] && raw[1] < raw[3])
            assertEquals("площа o=$o", 0.3f * 0.7f, (raw[2] - raw[0]) * (raw[3] - raw[1]), 1e-5f)
            val back = DetailMath.rawRectToOriented(raw, o)
            for (i in 0..3) assertEquals("o=$o [$i]", rect[i], back[i], 1e-6f)
        }
    }

    @Test
    fun sampleSizeNeverUndersamples() {
        assertEquals(1, DetailMath.sampleSize(1000, 1000))
        assertEquals(1, DetailMath.sampleSize(1999, 1000))
        assertEquals(2, DetailMath.sampleSize(2000, 1000))
        assertEquals(4, DetailMath.sampleSize(6000, 1080))
        for (region in listOf(500, 3000, 12000)) for (target in listOf(256, 1080, 2048)) {
            val s = DetailMath.sampleSize(region, target)
            assertTrue(region / s >= minOf(region, target))
        }
    }

    @Test
    fun decodeSampleSizeHandlesUnlimitedPixels() {
        // Регресія: maxPixels = Long.MAX_VALUE переповнював множення → нескінченний цикл і ділення на 0
        assertEquals(1, DetailMath.decodeSampleSize(3000, 2000, 2048))
        assertEquals(2, DetailMath.decodeSampleSize(6000, 4000, 2048))
        assertEquals(1, DetailMath.decodeSampleSize(0, 0, 2048))
        assertEquals(1, DetailMath.decodeSampleSize(100, 100, 0))
        // Обмеження пікселів: 24 Мп × 4 дозволено до ділення; 200 Мп → 2
        assertEquals(1, DetailMath.decodeSampleSize(8000, 6000, Int.MAX_VALUE, 24_000_000))
        assertEquals(2, DetailMath.decodeSampleSize(16000, 12500, Int.MAX_VALUE, 24_000_000))
    }

    @Test
    fun coverageCheck() {
        val have = floatArrayOf(0.1f, 0.1f, 0.6f, 0.6f)
        assertTrue(DetailMath.covers(have, 2, floatArrayOf(0.2f, 0.2f, 0.5f, 0.5f), 2))
        assertTrue(DetailMath.covers(have, 1, floatArrayOf(0.2f, 0.2f, 0.5f, 0.5f), 2))
        assertFalse("гірша деталізація", DetailMath.covers(have, 4, floatArrayOf(0.2f, 0.2f, 0.5f, 0.5f), 2))
        assertFalse("не вся область", DetailMath.covers(have, 1, floatArrayOf(0.5f, 0.5f, 0.7f, 0.7f), 1))
    }

    @Test
    fun needsDetailOnlyWhenMagnifiedAndOriginalIsBigger() {
        assertTrue(DetailMath.needsDetail(2f, 6000, 2048))
        assertFalse(DetailMath.needsDetail(1f, 6000, 2048))
        assertFalse("оригінал не більший за прев'ю", DetailMath.needsDetail(3f, 2048, 2048))
    }

    @Test
    fun fullImageTileMatchesRegularRetouch() {
        // Тайл на все фото в тій самій роздільності має дати рівно той самий результат, що й звичайний ретуш
        val img = TestImages.bricks(320, 200)
        TestImages.fillRect(img, 150, 90, 160, 100, PixelImage.argb(250, 250, 30))
        val ops: List<RetouchOp> = listOf(
            HealOp(155f / 320, 95f / 200, 9f / 320),
            CloneOp(BrushStroke(listOf(StrokePoint(0.2f, 0.3f), StrokePoint(0.3f, 0.3f)), 0.02f), 0.4f, 0.1f),
        )
        val expected = Retouch.applyAll(img, ops)
        val res = DetailMath.applyRetouch(img.copy(), floatArrayOf(0f, 0f, 1f, 1f), 320, 200, ops)
        assertArrayEquals(expected.pixels, res.image.pixels)
        assertNull(res.coverage)
    }

    @Test
    fun zoomedTileRemovesSpotAtHigherResolution() {
        // «Оригінал» 4× більший за прев'ю; тайл — центральна чверть у повній роздільності
        val full = TestImages.bricks(1280, 800)
        TestImages.fillRect(full, 620, 380, 660, 420, PixelImage.argb(250, 250, 30))
        val rect = floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f)
        val tile = PixelImage(640, 400, IntArray(640 * 400) { i -> full[320 + i % 640, 200 + i / 640] })
        // Операція задана в uv (як з прев'ю 320×200); радіус ~ 12 px прев'ю ≈ 48 px оригіналу
        val op = HealOp(0.5f, 0.5f, 12f / 320)
        val res = DetailMath.applyRetouch(tile, rect, 320, 200, listOf(op))
        for (y in 182..218 step 6) for (x in 302..338 step 6) {
            val p = res.image[x, y]
            assertFalse("жовтий піксель у тайлі ($x,$y)", r(p) > 225 && g(p) > 225)
        }
        // Далекі пікселі тайла не змінені
        assertEquals(tile.pixels[5], res.image.pixels[5])
    }

    @Test
    fun healOutsideTileIsSkipped() {
        val tile = TestImages.bricks(200, 100)
        val before = tile.pixels.copyOf()
        val res = DetailMath.applyRetouch(tile, floatArrayOf(0f, 0f, 0.25f, 0.25f), 800, 400, listOf(HealOp(0.9f, 0.9f, 0.01f)))
        assertArrayEquals(before, res.image.pixels)
    }

    @Test
    fun eraseAreasGoToCoverage() {
        val tile = TestImages.bricks(400, 200)
        val mask = TestImages.rectMask(100, 50, 40, 20, 60, 30) // центр кадру в координатах усього фото
        val res = DetailMath.applyRetouch(tile, floatArrayOf(0f, 0f, 1f, 1f), 400, 200, listOf(EraseOp(mask)))
        val cov = res.coverage!!
        assertEquals(100, cov.width)
        assertTrue("центр ластика", cov.sampleUv(0.5f, 0.5f) > 0.9f)
        assertEquals("далеко від ластика", 0f, cov.sampleUv(0.05f, 0.05f), 0f)
    }
}
