package com.photoeditor.app.core

import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MasksTest {

    private fun line(radius: Float, erase: Boolean = false, hardness: Float = 0.5f) =
        BrushStroke(listOf(StrokePoint(0.1f, 0.5f), StrokePoint(0.9f, 0.5f)), radius, hardness, erase)

    @Test
    fun strokeFillsAlongPathOnly() {
        val m = MaskRaster.rasterize(listOf(line(0.03f)), 200, 100, 640, 420)
        for (x in 25..175 step 10) assertTrue("x=$x", m.at(x, 50) > 250)
        assertEquals(0, m.at(100, 5))
        assertEquals(0, m.at(2, 50)) // до початку мазка
    }

    @Test
    fun radiusIsInSquareUnitsIndependentOfResolution() {
        // Радіус 0.05 від довшої сторони: на карті 200 px ширини це 10 px
        val m = MaskRaster.rasterize(listOf(BrushStroke(listOf(StrokePoint(0.5f, 0.5f)), 0.05f, 0.99f)), 200, 132, 640, 420)
        assertTrue(m.at(100 + 8, 66) > 200)
        assertEquals(0, m.at(100 + 12, 66))
        val big = MaskRaster.rasterize(listOf(BrushStroke(listOf(StrokePoint(0.5f, 0.5f)), 0.05f, 0.99f)), 400, 264, 640, 420)
        assertTrue(big.at(200 + 16, 132) > 200)
        assertEquals(0, big.at(200 + 24, 132))
    }

    @Test
    fun softBrushFallsOff() {
        val m = MaskRaster.rasterize(listOf(BrushStroke(listOf(StrokePoint(0.5f, 0.5f)), 0.1f, 0f)), 200, 200, 640, 640)
        val c = m.at(100, 100)
        val mid = m.at(110, 100)
        val edge = m.at(118, 100)
        assertTrue(c > mid && mid > edge && edge > 0)
    }

    @Test
    fun overlappingStrokesDoNotAccumulateBeyondMax() {
        val once = MaskRaster.rasterize(listOf(line(0.03f, hardness = 0f)), 200, 100, 640, 420)
        val twice = MaskRaster.rasterize(listOf(line(0.03f, hardness = 0f), line(0.03f, hardness = 0f)), 200, 100, 640, 420)
        assertTrue(once.data.contentEquals(twice.data))
    }

    @Test
    fun eraseStrokeRemovesFromBase() {
        val m = MaskRaster.rasterize(listOf(line(0.03f)), 200, 100, 640, 420)
        val e = MaskRaster.rasterize(listOf(BrushStroke(listOf(StrokePoint(0.5f, 0.5f)), 0.05f, 0.9f, erase = true)), 200, 100, 640, 420, m)
        assertEquals(0, e.at(100, 50))
        assertTrue(e.at(30, 50) > 250)
        // База не змінилась
        assertTrue(m.at(100, 50) > 250)
    }

    @Test
    fun emptyStrokesGiveEmptyMap() {
        val m = MaskRaster.rasterize(listOf(BrushStroke(emptyList(), 0.1f)), 50, 50, 100, 100)
        assertTrue(m.isEmpty())
    }

    @Test
    fun alphaMapResizeAndSample() {
        val m = AlphaMap(4, 4)
        for (y in 0..3) for (x in 2..3) m.data[y * 4 + x] = 0xFF.toByte()
        assertEquals(0f, m.sampleUv(0.1f, 0.5f), 1e-6f)
        assertEquals(1f, m.sampleUv(0.9f, 0.5f), 1e-6f)
        assertEquals(0.5f, m.sampleUv(0.5f, 0.5f), 0.01f)
        val r = m.resized(8, 8)
        assertEquals(8, r.width)
        assertEquals(0, r.at(0, 0))
        assertEquals(255, r.at(7, 7))
        assertSame(m, m.resized(4, 4))
    }

    @Test
    fun alphaMapUnionAndFromFloats() {
        val a = AlphaMap.fromFloats(2, 1, floatArrayOf(1f, 0f))
        val b = AlphaMap.fromFloats(2, 1, floatArrayOf(0f, 0.5f))
        val u = a.union(b)
        assertEquals(255, u.at(0, 0))
        assertEquals(128, u.at(1, 0))
        assertFalse(u.isEmpty())
        assertEquals(0, AlphaMap.fromFloats(1, 1, floatArrayOf(-3f)).at(0, 0))
        assertEquals(255, AlphaMap.fromFloats(1, 1, floatArrayOf(9f)).at(0, 0))
    }

    @Test
    fun maskMapsOnlyForRasterKinds() {
        assertNull(MaskMaps.forMask(Mask(1, MaskKind.LINEAR), 640, 420))
        assertNull(MaskMaps.forMask(Mask(1, MaskKind.RADIAL), 640, 420))
        assertNull(MaskMaps.forMask(Mask(1, MaskKind.SUBJECT, subject = null), 640, 420))
        val brush = MaskMaps.forMask(Mask(1, MaskKind.BRUSH, strokes = listOf(line(0.02f))), 640, 420)!!
        assertEquals(MaskMaps.BRUSH_RES, brush.width)
        assertEquals(672, brush.height) // 1024 * 420 / 640
        val subject = AlphaMap(64, 42)
        assertSame(subject, MaskMaps.forMask(Mask(1, MaskKind.SUBJECT, subject = subject), 640, 420))
    }

    @Test
    fun subjectMaskCanBeRefinedWithBrush() {
        val subject = AlphaMap(64, 42)
        val refined = MaskMaps.forMask(Mask(1, MaskKind.SUBJECT, subject = subject, strokes = listOf(line(0.05f))), 640, 420)!!
        assertEquals(64, refined.width)
        assertTrue(refined.at(32, 21) > 200)
        assertTrue(subject.isEmpty()) // оригінальна карта сегментації не змінилась
    }

    @Test
    fun paintIntoModifiesInPlace() {
        val m = AlphaMap(100, 100)
        MaskRaster.paintInto(m, listOf(BrushStroke(listOf(StrokePoint(0.5f, 0.5f)), 0.1f, 0.9f)), 100, 100)
        assertTrue(m.at(50, 50) > 250)
        assertEquals(0, m.at(2, 2))
    }

    @Test
    fun incrementalRasterEqualsFull() {
        val s1 = line(0.03f)
        val s2 = BrushStroke(listOf(StrokePoint(0.5f, 0.1f), StrokePoint(0.5f, 0.9f)), 0.02f, 0.3f)
        val s3 = BrushStroke(listOf(StrokePoint(0.3f, 0.3f), StrokePoint(0.7f, 0.7f)), 0.04f, 0.6f, erase = true)
        val full = MaskMaps.forMask(Mask(1, MaskKind.BRUSH, strokes = listOf(s1, s2, s3)), 640, 420)!!
        val base = MaskMaps.forMask(Mask(1, MaskKind.BRUSH, strokes = listOf(s1, s2)), 640, 420)!!
        val inc = MaskMaps.forMask(Mask(1, MaskKind.BRUSH, strokes = listOf(s1, s2, s3)), 640, 420, withoutLast = base)!!
        assertTrue("інкрементальна карта = повна", full.data.contentEquals(inc.data))
        // Базова карта не зіпсована домальовуванням
        assertTrue(base.data.contentEquals(MaskMaps.forMask(Mask(1, MaskKind.BRUSH, strokes = listOf(s1, s2)), 640, 420)!!.data))
    }

    @Test
    fun incrementalIgnoresMismatchedBase() {
        val m = Mask(1, MaskKind.BRUSH, strokes = listOf(line(0.03f)))
        val wrong = AlphaMap(10, 10)
        val r = MaskMaps.forMask(m, 640, 420, withoutLast = wrong)!!
        assertEquals(MaskMaps.BRUSH_RES, r.width)
    }

    @Test(timeout = 2_000)
    fun longStrokeOnFullResMapIsFast() {
        // 300 точок на карті 1024 px — так малює палець упродовж кількох секунд
        val pts = List(300) { StrokePoint(0.1f + it * 0.0027f, 0.5f + 0.2f * kotlin.math.sin(it / 20f)) }
        repeat(20) { MaskRaster.rasterize(listOf(BrushStroke(pts, 0.03f)), 1024, 672, 640, 420) }
    }
}
