package com.photoeditor.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HistogramTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun countsChannelsAndLuma() {
        val px = intArrayOf(argb(255, 0, 0), argb(0, 0, 0), argb(128, 128, 128), argb(255, 255, 255))
        val h = Histogram.compute(px)
        assertEquals(4, h.total)
        assertEquals(2, h.red[255])
        assertEquals(1, h.green[128])
        assertEquals(1, h.luma[255])
        assertEquals(1, h.luma[0])
        assertEquals(1, h.luma[128])
        assertEquals(4, h.red.sum())
        assertEquals(4, h.luma.sum())
    }

    @Test
    fun clippingFractions() {
        val px = IntArray(1000) { argb(100, 100, 100) }
        repeat(10) { px[it] = argb(255, 200, 100) }  // пересвіт одного каналу вже рахується
        repeat(5) { px[100 + it] = argb(0, 0, 0) }
        px[200] = argb(0, 0, 1)                      // не провал: синій ще живий
        val h = Histogram.compute(px)
        assertEquals(0.010f, h.highlightClip, 1e-6f)
        assertEquals(0.005f, h.shadowClip, 1e-6f)
    }

    @Test
    fun peakIgnoresExtremes() {
        val px = IntArray(100) { argb(255, 255, 255) } + IntArray(3) { argb(50, 50, 50) }
        assertEquals(3, Histogram.compute(px).peak)
    }

    @Test
    fun emptyIsSafe() {
        val h = Histogram.compute(IntArray(0))
        assertEquals(0f, h.highlightClip, 0f)
        assertEquals(1, h.peak)
    }
}
