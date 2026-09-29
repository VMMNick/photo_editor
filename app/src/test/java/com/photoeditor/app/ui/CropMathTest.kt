package com.photoeditor.app.ui

import com.photoeditor.app.model.NRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {

    private val start = NRect(0.2f, 0.2f, 0.6f, 0.6f)
    private val eps = 1e-5f

    private fun inBounds(r: NRect) = r.l >= -eps && r.t >= -eps && r.r <= 1f + eps && r.b <= 1f + eps && r.w > 0 && r.h > 0

    @Test
    fun freeCornerDragMovesOnlyThatCorner() {
        val r = CropMath.drag(start, CropHandle.BR, 0.1f, 0.2f, null)
        assertEquals(0.2f, r.l, eps); assertEquals(0.2f, r.t, eps)
        assertEquals(0.7f, r.r, eps); assertEquals(0.8f, r.b, eps)
        val tl = CropMath.drag(start, CropHandle.TL, -0.1f, 0.05f, null)
        assertEquals(0.1f, tl.l, eps); assertEquals(0.25f, tl.t, eps)
        assertEquals(0.6f, tl.r, eps); assertEquals(0.6f, tl.b, eps)
    }

    @Test
    fun freeEdgeDragMovesOnlyThatEdge() {
        val r = CropMath.drag(start, CropHandle.L, 0.1f, 0.3f, null)
        assertEquals(NRect(0.3f, 0.2f, 0.6f, 0.6f), r)
        val b = CropMath.drag(start, CropHandle.B, 0.3f, -0.1f, null)
        assertEquals(NRect(0.2f, 0.2f, 0.6f, 0.5f), b)
    }

    @Test
    fun cannotInvertOrShrinkBelowMinimum() {
        val r = CropMath.drag(start, CropHandle.L, 0.9f, 0f, null)
        assertEquals(CropMath.MIN, r.w, 1e-4f)
        assertEquals(0.6f, r.r, eps)
        val t = CropMath.drag(start, CropHandle.T, 0f, 5f, null)
        assertEquals(CropMath.MIN, t.h, 1e-4f)
    }

    @Test
    fun cannotLeaveFrame() {
        for (h in CropHandle.entries) for ((dx, dy) in listOf(-2f to -2f, 2f to 2f, -2f to 2f, 2f to -2f)) {
            assertTrue("$h $dx $dy", inBounds(CropMath.drag(start, h, dx, dy, null)))
            assertTrue("lock $h $dx $dy", inBounds(CropMath.drag(start, h, dx, dy, 1.5f)))
        }
    }

    @Test
    fun moveKeepsSizeAndClamps() {
        val r = CropMath.drag(start, CropHandle.MOVE, 0.9f, -0.9f, null)
        assertEquals(start.w, r.w, eps); assertEquals(start.h, r.h, eps)
        assertEquals(1f, r.r, eps); assertEquals(0f, r.t, eps)
    }

    @Test
    fun aspectLockKeepsRatioForEveryHandle() {
        for (rn in listOf(0.5f, 1f, 1.5f, 2.3f)) for (h in CropHandle.entries.filter { it != CropHandle.MOVE })
            for ((dx, dy) in listOf(0.1f to 0.05f, -0.05f to 0.2f, 0.3f to -0.3f)) {
                val s = NRect(0.3f, 0.3f, 0.3f + 0.2f * rn, 0.5f)
                val r = CropMath.drag(s, h, dx, dy, rn)
                assertEquals("rn=$rn $h", rn, r.w / r.h, 1e-3f)
                assertTrue(inBounds(r))
            }
    }

    @Test
    fun aspectLockCornerAnchorsOppositeCorner() {
        val s = NRect(0.2f, 0.2f, 0.5f, 0.4f)
        val r = CropMath.drag(s, CropHandle.BR, 0.2f, 0.05f, 1.5f)
        assertEquals(0.2f, r.l, eps); assertEquals(0.2f, r.t, eps)
        val tl = CropMath.drag(s, CropHandle.TL, -0.1f, -0.1f, 1.5f)
        assertEquals(0.5f, tl.r, eps); assertEquals(0.4f, tl.b, eps)
    }

    @Test
    fun hitTestFindsHandles() {
        // Рамка 100..300 × 100..200 у пікселях, зона дотику 20
        fun hit(x: Float, y: Float) = CropMath.hit(x, y, 100f, 100f, 300f, 200f, 20f)
        assertEquals(CropHandle.TL, hit(105f, 95f))
        assertEquals(CropHandle.BR, hit(300f, 210f))
        assertEquals(CropHandle.R, hit(310f, 150f))
        assertEquals(CropHandle.T, hit(200f, 100f))
        assertEquals(CropHandle.MOVE, hit(200f, 150f))
        assertNull(hit(10f, 10f))
        assertNull(hit(400f, 150f))
    }
}
