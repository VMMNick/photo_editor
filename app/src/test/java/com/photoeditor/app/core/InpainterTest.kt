package com.photoeditor.app.core

import com.photoeditor.app.testutil.TestImages
import com.photoeditor.app.testutil.TestImages.r
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InpainterTest {

    private fun darkObject(img: PixelImage, x0: Int, y0: Int, x1: Int, y1: Int): AlphaMap {
        TestImages.fillRect(img, x0, y0, x1, y1, PixelImage.argb(10, 10, 10))
        return TestImages.rectMask(img.width, img.height, x0, y0, x1, y1)
    }

    @Test
    fun fillsHoleWithSurroundingTexture() {
        val img = TestImages.bricks(320, 200)
        val mask = darkObject(img, 130, 80, 190, 130)
        val out = Inpainter.inpaint(img, mask)
        var dark = 0
        for (y in 80..130) for (x in 130..190) if (r(out[x, y]) < 60) dark++
        assertEquals("темних пікселів лишилось", 0, dark)
    }

    @Test
    fun keepsPixelsOutsideDilatedHole() {
        val img = TestImages.bricks(320, 200)
        val mask = darkObject(img, 130, 80, 190, 130)
        val out = Inpainter.inpaint(img, mask)
        for (y in 0 until 200) for (x in 0 until 320) {
            val inside = x in 127..193 && y in 77..133 // дірка + дилатація 2 px + запас
            if (!inside) assertEquals("($x,$y)", img[x, y], out[x, y])
        }
    }

    @Test
    fun fillOfSolidBackgroundIsSolid() {
        val img = TestImages.solid(200, 150, 90, 140, 210)
        val mask = darkObject(img, 80, 60, 120, 90)
        val out = Inpainter.inpaint(img, mask)
        for (y in 60..90) for (x in 80..120) {
            val p = out[x, y]
            assertTrue(kotlin.math.abs(r(p) - 90) <= 2 && kotlin.math.abs(TestImages.b(p) - 210) <= 2)
        }
    }

    @Test
    fun emptyMaskReturnsSameImage() {
        val img = TestImages.bricks(64, 64)
        assertSame(img, Inpainter.inpaint(img, AlphaMap(64, 64)))
        // Слабка маска (нижче порогу) — теж нічого не робимо
        val faint = AlphaMap(64, 64).also { it.data.fill(30) }
        assertSame(img, Inpainter.inpaint(img, faint))
    }

    @Test
    fun wholeImageMaskReturnsImage() {
        val img = TestImages.bricks(40, 40)
        val all = AlphaMap(40, 40).also { it.data.fill(-1) }
        assertSame(img, Inpainter.inpaint(img, all))
    }

    @Test
    fun maskOfDifferentResolutionIsScaled() {
        val img = TestImages.bricks(320, 200)
        TestImages.fillRect(img, 128, 80, 191, 119, PixelImage.argb(10, 10, 10))
        // Маска у вдвічі меншій роздільності (як з прев'ю)
        val mask = TestImages.rectMask(160, 100, 64, 40, 95, 59)
        val out = Inpainter.inpaint(img, mask)
        var dark = 0
        for (y in 82..117) for (x in 130..189) if (r(out[x, y]) < 60) dark++
        assertEquals(0, dark)
    }

    @Test
    fun deterministicForSameSeed() {
        val img = TestImages.bricks(200, 120)
        val mask = darkObject(img, 80, 40, 120, 80)
        assertArrayEquals(Inpainter.inpaint(img, mask, seed = 3).pixels, Inpainter.inpaint(img, mask, seed = 3).pixels)
    }

    @Test
    fun holeTouchingBorderIsFilled() {
        val img = TestImages.bricks(200, 120)
        val mask = darkObject(img, 0, 0, 40, 30)
        val out = Inpainter.inpaint(img, mask)
        var dark = 0
        for (y in 0..30) for (x in 0..40) if (r(out[x, y]) < 60) dark++
        assertEquals(0, dark)
    }

    @Test(timeout = 20_000)
    fun largeRegionUsesDownscaledPathInReasonableTime() {
        val w = 2000
        val h = 1500
        val img = PixelImage(w, h, IntArray(w * h) { i -> PixelImage.argb((i % w * 7) % 256, (i / w) % 256, 90) })
        val mask = darkObject(img, 600, 400, 1400, 1100)
        val out = Inpainter.inpaint(img, mask)
        assertTrue(r(out[1000, 750]) > 0 || TestImages.g(out[1000, 750]) > 20)
    }

    @Test
    fun dilateGrowsBySquareRadius() {
        val src = BooleanArray(25).also { it[12] = true } // центр 5×5
        val d = Inpainter.dilate(src, 5, 5, 1)
        assertEquals(9, d.count { it })
        assertTrue(d[6] && d[18] && !d[0])
        assertEquals(25, Inpainter.dilate(src, 5, 5, 2).count { it })
        assertArrayEquals(src, Inpainter.dilate(src, 5, 5, 0))
    }
}
