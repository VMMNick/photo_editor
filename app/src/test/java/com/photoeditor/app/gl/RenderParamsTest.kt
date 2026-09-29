package com.photoeditor.app.gl

import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.ColorGrading
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.GradeWheel
import com.photoeditor.app.model.LocalAdjust
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.NRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderParamsTest {

    private fun params(s: EditState, maps: List<AlphaMap?> = emptyList(), crop: NRect? = null, show: Int = -1) =
        RenderParams.from(s, 640, 420, s.adjust, maps, crop, show)

    @Test
    fun identityXformAndSizes() {
        val p = params(EditState())
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0..8) assertEquals(expected[i], p.xform[i], 1e-6f)
        assertEquals(640f, p.outW, 0f)
        assertEquals(420f, p.outH, 0f)
        assertEquals(1f, p.imgScale[0], 0f)
        assertEquals(420f / 640f, p.imgScale[1], 1e-6f)
        assertEquals(0, p.maskCount)
        assertEquals(-1, p.showMask)
    }

    @Test
    fun cropOverrideShowsFullFrame() {
        val s = EditState(geometry = Geometry(crop = NRect(0f, 0f, 0.5f, 0.5f)))
        assertEquals(320f, params(s).outW, 1e-3f)
        assertEquals(640f, params(s, crop = NRect.FULL).outW, 1e-3f)
    }

    @Test
    fun linearAndRadialMasksPackedInSquareUnits() {
        val lin = Mask(1, MaskKind.LINEAR, x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 1f, invert = true, adjust = LocalAdjust(exposure = -1f, tint = 0.3f))
        val rad = Mask(2, MaskKind.RADIAL, cx = 0.5f, cy = 0.5f, rx = 0.2f, ry = 0.1f, angle = 0f, feather = 0.7f)
        val p = params(EditState(masks = listOf(lin, rad)))
        assertEquals(2, p.maskCount)
        assertEquals(0, p.maskType[0]); assertEquals(1, p.maskType[1])
        val sy = 420f / 640f
        assertEquals(1f * sy, p.maskA[3], 1e-6f)            // y1 у квадратних одиницях
        assertEquals(1f, p.maskInv[0], 0f); assertEquals(0f, p.maskInv[1], 0f)
        assertEquals(-1f, p.maskAdjA[0], 0f)                 // експозиція
        assertEquals(0.3f, p.maskAdjB[2], 0f)                // відтінок
        assertEquals(0.5f * sy, p.maskA[5], 1e-6f)           // cy радіальної
        assertEquals(0.2f, p.maskA[6], 0f)                   // rx
        assertEquals(1f, p.maskB[4], 1e-6f)                  // cos(0)
        assertEquals(0.7f, p.maskB[6], 0f)                   // feather
        assertNull(p.maskMaps[0])
    }

    @Test
    fun rasterMasksUseProvidedMaps() {
        val map = AlphaMap(8, 8)
        val p = params(EditState(masks = listOf(Mask(1, MaskKind.LINEAR), Mask(2, MaskKind.BRUSH))), listOf(null, map))
        assertEquals(2, p.maskType[1])
        assertSame(map, p.maskMaps[1])
    }

    @Test
    fun atMostFourMasksReachShader() {
        val masks = List(6) { Mask(it.toLong(), MaskKind.RADIAL) }
        val p = params(EditState(masks = masks), show = 5)
        assertEquals(Shaders.MAX_MASKS, p.maskCount)
        assertEquals("індекс поза межами → без підсвічування", -1, p.showMask)
        assertEquals(2, params(EditState(masks = masks), show = 2).showMask)
    }

    @Test
    fun wheelColorHasZeroLuminance() {
        for (hue in listOf(0f, 45f, 120f, 210f, 300f)) {
            val c = RenderParams.wheelColor(GradeWheel(hue, 0.8f))
            val l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
            assertEquals("hue $hue", 0f, l, 1e-5f)
            assertTrue(c.any { kotlin.math.abs(it) > 0.01f })
        }
        val none = RenderParams.wheelColor(GradeWheel(120f, 0f))
        assertTrue(none.all { it == 0f })
    }

    @Test
    fun hsvToRgbPrimaries() {
        fun check(h: Float, s: Float, r: Float, g: Float, b: Float) {
            val c = RenderParams.hsvToRgb(h, s, 1f)
            assertEquals(r, c[0], 1e-5f); assertEquals(g, c[1], 1e-5f); assertEquals(b, c[2], 1e-5f)
        }
        check(0f, 1f, 1f, 0f, 0f)
        check(1f / 3f, 1f, 0f, 1f, 0f)
        check(2f / 3f, 1f, 0f, 0f, 1f)
        check(0.4f, 0f, 1f, 1f, 1f)
    }

    @Test
    fun gradingAndBypassPassedThrough() {
        val a = Adjustments(grading = ColorGrading(shadows = GradeWheel(200f, 0.5f, -0.3f), highlights = GradeWheel(40f, 0.2f, 0.1f)))
        val s = EditState(adjust = a)
        val p = RenderParams.from(s, 100, 100, a, emptyList(), bypass = true)
        assertTrue(p.bypass)
        assertEquals(-0.3f, p.gradeLum[0], 0f)
        assertEquals(0.1f, p.gradeLum[2], 0f)
        assertTrue(p.gradeM.all { it == 0f })
        assertTrue(p.gradeS[2] > 0f) // синюватий відтінок тіней
    }

    @Test
    fun rotationChangesOutputAspect() {
        val p = params(EditState(geometry = Geometry(quarterTurns = 1)))
        assertEquals(420f / 640f, p.outAspect, 1e-6f)
    }

    @Test
    fun splitAndCurvesDefaultsAreOff() {
        val p = RenderParams.from(EditState(), 100, 100, Adjustments(), emptyList())
        assertEquals(-1f, p.splitX, 0f)
        assertEquals(null, p.curveLut)
        val c = Adjustments(curves = com.photoeditor.app.model.Curves(rgb = com.photoeditor.app.model.ToneCurve(
            listOf(com.photoeditor.app.model.CurvePoint(0f, 0.2f), com.photoeditor.app.model.CurvePoint(1f, 1f)))))
        val q = RenderParams.from(EditState(adjust = c), 100, 100, c, emptyList(), splitX = 0.4f)
        assertEquals(0.4f, q.splitX, 0f)
        assertEquals(1024, q.curveLut!!.size)
    }

    @Test
    fun maskRangeIsPacked() {
        val m = Mask(1, MaskKind.LINEAR, range = com.photoeditor.app.model.MaskRange(com.photoeditor.app.model.RangeMode.LUMINANCE, 0.6f, 0.95f, smooth = 0f))
        val p = RenderParams.from(EditState(masks = listOf(m)), 100, 100, Adjustments(), listOf(null))
        assertEquals(1, p.maskRangeMode[0])
        assertEquals(0.6f, p.maskRange[0], 0f)
        assertEquals(0.95f, p.maskRange[1], 0f)
        assertEquals(0, p.maskRangeMode[1])
    }
}
