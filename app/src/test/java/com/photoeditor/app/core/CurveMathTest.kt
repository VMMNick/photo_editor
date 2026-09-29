package com.photoeditor.app.core

import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.CurveChannel
import com.photoeditor.app.model.CurvePoint
import com.photoeditor.app.model.Curves
import com.photoeditor.app.model.ToneCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveMathTest {

    private fun curve(vararg xy: Float) = ToneCurve(xy.toList().chunked(2).map { CurvePoint(it[0], it[1]) })
    private val sCurve = curve(0f, 0f, 0.25f, 0.18f, 0.75f, 0.85f, 1f, 1f)

    @Test
    fun identityIsStraightLine() {
        val lut = CurveMath.lut(ToneCurve())
        for (i in lut.indices) assertEquals(i / 255f, lut[i], 1e-6f)
        assertTrue(ToneCurve().isIdentity)
        assertTrue(Curves().isIdentity)
    }

    @Test
    fun passesThroughControlPoints() {
        for (p in sCurve.points) assertEquals(p.y, CurveMath.evaluate(sCurve, p.x), 1e-5f)
    }

    @Test
    fun monotoneBetweenPointsNoOvershoot() {
        // Різкий злам: звичайний кубічний сплайн тут дав би «хвилю» нижче 0 / вище 1
        val c = curve(0f, 0f, 0.1f, 0.9f, 0.5f, 0.95f, 1f, 1f)
        val lut = CurveMath.lut(c, 1001)
        for (i in 1 until lut.size) assertTrue("спадання в $i", lut[i] >= lut[i - 1] - 1e-6f)
        for (i in lut.indices) {
            val x = i / 1000f
            if (x in 0.1f..0.5f) assertTrue("вихід за межі сегмента в $x: ${lut[i]}", lut[i] in 0.9f - 1e-5f..0.95f + 1e-5f)
        }
    }

    @Test
    fun flatSegmentStaysFlat() {
        val c = curve(0f, 0.2f, 0.3f, 0.5f, 0.7f, 0.5f, 1f, 0.8f)
        for (x in listOf(0.35f, 0.5f, 0.65f)) assertEquals(0.5f, CurveMath.evaluate(c, x), 1e-5f)
    }

    @Test
    fun endpointsClampOutside() {
        val c = curve(0.1f, 0.2f, 0.9f, 0.7f)
        assertEquals(0.2f, CurveMath.evaluate(c, 0f), 1e-6f)
        assertEquals(0.7f, CurveMath.evaluate(c, 1f), 1e-6f)
    }

    @Test
    fun combinedLutAppliesRgbThenChannel() {
        val invert = curve(0f, 1f, 1f, 0f)
        val lut = CurveMath.combinedLut(Curves(rgb = invert, red = invert))
        // x = 0: RGB → 1, потім червоний канал ще раз інвертує → 0; зелений і синій лишаються 1
        assertEquals(0, lut[0].toInt() and 0xFF)
        assertEquals(255, lut[1].toInt() and 0xFF)
        assertEquals(255, lut[2].toInt() and 0xFF)
        assertEquals(255, lut[3].toInt() and 0xFF)
        assertEquals(256 * 4, lut.size)
    }

    @Test
    fun addMoveRemovePoints() {
        val (c1, i) = CurveMath.addPoint(ToneCurve(), 0.5f)!!
        assertEquals(1, i)
        assertEquals(0.5f, c1.points[1].y, 1e-6f)
        assertNull("занадто близько до існуючої", CurveMath.addPoint(c1, 0.51f))

        // x обмежується сусідами, y — діапазоном 0..1
        val c2 = CurveMath.movePoint(c1, 1, 1.4f, 1.3f)
        assertEquals(1f - CurveMath.MIN_GAP, c2.points[1].x, 1e-6f)
        assertEquals(1f, c2.points[1].y, 1e-6f)
        val c3 = CurveMath.movePoint(c1, 0, 0.9f, 0.1f)
        assertEquals(0.5f - CurveMath.MIN_GAP, c3.points[0].x, 1e-6f)

        assertEquals(2, CurveMath.removePoint(c1, 1).points.size)
        assertSame("кінцеві точки не видаляються", c1, CurveMath.removePoint(c1, 0))
    }

    @Test
    fun hitTestFindsNearest() {
        assertEquals(1, CurveMath.hitPoint(sCurve, 0.26f, 0.2f, 0.05f))
        assertEquals(-1, CurveMath.hitPoint(sCurve, 0.5f, 0.1f, 0.05f))
    }

    @Test
    fun scaledCurveBlendsTowardIdentity() {
        val half = sCurve.scaled(0.5f)
        assertEquals(0.25f + (0.18f - 0.25f) * 0.5f, half.points[1].y, 1e-6f)
        assertTrue(sCurve.scaled(0f).isIdentity)
    }

    @Test
    fun presetCurveOnlyWhenUserCurveIsStraight() {
        val preset = Adjustments(curves = Curves(rgb = sCurve))
        val none = Adjustments().plusScaled(preset, 0.5f)
        assertEquals(sCurve.scaled(0.5f), none.curves.rgb)
        val user = Adjustments(curves = Curves(blue = curve(0f, 0.1f, 1f, 1f)))
        assertEquals("ручна крива має пріоритет", user.curves, user.plusScaled(preset, 1f).curves)
    }

    @Test
    fun curvesAccessByChannel() {
        val c = Curves().with(CurveChannel.GREEN, sCurve)
        assertEquals(sCurve, c[CurveChannel.GREEN])
        assertTrue(c[CurveChannel.RED].isIdentity)
        assertNotNull(c.scaled(1f))
    }
}
