package com.photoeditor.app.core

import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.RetouchOp
import com.photoeditor.app.model.StrokePoint
import com.photoeditor.app.testutil.TestImages
import com.photoeditor.app.testutil.TestImages.b
import com.photoeditor.app.testutil.TestImages.g
import com.photoeditor.app.testutil.TestImages.r
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RetouchTest {

    @Test
    fun healRemovesSpotOnTexture() {
        val img = TestImages.bricks(320, 200)
        TestImages.fillRect(img, 155, 95, 165, 105, PixelImage.argb(250, 250, 30))
        val out = Retouch.heal(img, HealOp(160f / 320, 100f / 200, 10f / 320))
        for (y in 95..105) for (x in 155..165) {
            val p = out[x, y]
            assertTrue("жовтий піксель ($x,$y)", !(r(p) > 225 && g(p) > 225))
        }
    }

    @Test
    fun healMatchesToneOnGradient() {
        // На плавному градієнті латка має продовжувати градієнт, а не «переносити» чужу яскравість
        val img = TestImages.gradient(400, 100)
        TestImages.fillRect(img, 195, 45, 205, 55, PixelImage.argb(255, 0, 0))
        val out = Retouch.heal(img, HealOp(200f / 400, 50f / 100, 9f / 400))
        val expected = 200 * 255 / 399
        assertTrue("тон ${r(out[200, 50])} vs $expected", abs(g(out[200, 50]) - expected) < 12)
        assertTrue(abs(r(out[200, 50]) - g(out[200, 50])) < 12) // червоний прибрано
    }

    @Test
    fun healDoesNotTouchFarPixels() {
        val img = TestImages.bricks(320, 200)
        val out = Retouch.heal(img, HealOp(0.5f, 0.5f, 0.02f))
        assertEquals(img[10, 10], out[10, 10])
        assertEquals(img[300, 190], out[300, 190])
    }

    @Test
    fun healNearBorderDoesNotCrash() {
        val img = TestImages.bricks(100, 80)
        Retouch.heal(img, HealOp(0f, 0f, 0.1f))
        Retouch.heal(img, HealOp(1f, 1f, 0.3f))
    }

    @Test
    fun cloneCopiesFromOffset() {
        val img = TestImages.solid(200, 100, 20, 20, 20)
        TestImages.fillRect(img, 140, 40, 180, 60, PixelImage.argb(250, 10, 10)) // джерело справа
        val op = CloneOp(BrushStroke(listOf(StrokePoint(0.2f, 0.5f), StrokePoint(0.25f, 0.5f)), 0.03f, 0.9f), du = 0.6f, dv = 0f)
        val out = Retouch.clone(img, op)
        assertTrue("мазок має стати червоним", r(out[45, 50]) > 200)
        assertEquals(img[100, 10], out[100, 10])
        assertTrue("джерело лишилось", r(out[160, 50]) > 200)
    }

    @Test
    fun cloneWithEmptyStrokeIsNoop() {
        val img = TestImages.solid(10, 10, 1, 2, 3)
        assertSame(img, Retouch.clone(img, CloneOp(BrushStroke(emptyList(), 0.1f), 0.1f, 0.1f)))
    }

    @Test
    fun operationsDoNotMutateInput() {
        val img = TestImages.bricks(120, 80)
        val before = img.pixels.copyOf()
        Retouch.heal(img, HealOp(0.5f, 0.5f, 0.05f))
        Retouch.clone(img, CloneOp(BrushStroke(listOf(StrokePoint(0.3f, 0.3f)), 0.05f), 0.2f, 0.2f))
        Retouch.apply(img, EraseOp(TestImages.rectMask(120, 80, 50, 30, 70, 50)))
        assertArrayEquals(before, img.pixels)
    }

    @Test
    fun applyAllRunsInOrderAndReportsProgress() {
        val img = TestImages.bricks(160, 100)
        val ops: List<RetouchOp> = listOf(
            HealOp(0.3f, 0.3f, 0.03f),
            CloneOp(BrushStroke(listOf(StrokePoint(0.6f, 0.6f)), 0.04f), -0.2f, 0f),
            HealOp(0.7f, 0.2f, 0.03f),
        )
        val progress = ArrayList<Int>()
        val all = Retouch.applyAll(img, ops) { progress += it }
        assertEquals(listOf(1, 2, 3), progress)
        var manual = img
        for (op in ops) manual = Retouch.apply(manual, op)
        assertArrayEquals("детермінованість", manual.pixels, all.pixels)
    }

    @Test
    fun resolutionIndependentOps() {
        // Та сама операція на прев'ю і в 2× роздільності дає схожий результат (порівнюємо центр плями)
        val small = TestImages.gradient(200, 100)
        val big = TestImages.gradient(400, 200)
        TestImages.fillRect(small, 97, 47, 103, 53, PixelImage.argb(255, 0, 0))
        TestImages.fillRect(big, 194, 94, 206, 106, PixelImage.argb(255, 0, 0))
        val op = HealOp(0.5f, 0.5f, 6f / 200)
        val a = Retouch.heal(small, op)[100, 50]
        val bb = Retouch.heal(big, op)[200, 100]
        assertTrue(abs(g(a) - g(bb)) < 15 && r(a) < 180 && r(bb) < 180)
    }

    @Test
    fun inPlaceHealMatchesCopyAndReusesBuffer() {
        val a = TestImages.bricks(320, 200)
        TestImages.fillRect(a, 155, 95, 165, 105, PixelImage.argb(250, 250, 30))
        val b = a.copy()
        val op = HealOp(160f / 320, 100f / 200, 10f / 320)
        val copyResult = Retouch.apply(a, op)
        val inPlace = Retouch.apply(b, op, inPlace = true)
        assertSame(b, inPlace)
        assertArrayEquals(copyResult.pixels, inPlace.pixels)
    }

    @Test
    fun inPlaceEraseMatchesCopy() {
        val a = TestImages.bricks(200, 120)
        TestImages.fillRect(a, 80, 40, 120, 80, PixelImage.argb(10, 10, 10))
        val mask = TestImages.rectMask(200, 120, 80, 40, 120, 80)
        val b = a.copy()
        val copyResult = Retouch.apply(a, EraseOp(mask))
        val inPlace = Retouch.apply(b, EraseOp(mask), inPlace = true)
        assertSame(b, inPlace)
        assertArrayEquals(copyResult.pixels, inPlace.pixels)
    }

    @Test
    fun applyAllInPlaceMatchesCopy() {
        val ops: List<RetouchOp> = listOf(
            HealOp(0.3f, 0.3f, 0.03f),
            CloneOp(BrushStroke(listOf(StrokePoint(0.6f, 0.6f), StrokePoint(0.7f, 0.6f)), 0.04f), -0.3f, 0f),
            EraseOp(TestImages.rectMask(160, 100, 100, 20, 130, 50)),
        )
        val a = TestImages.bricks(160, 100)
        val expected = Retouch.applyAll(a, ops)
        val actual = Retouch.applyAll(a.copy(), ops, inPlace = true)
        assertArrayEquals(expected.pixels, actual.pixels)
    }
}
