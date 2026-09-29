package com.photoeditor.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsAndPresetsTest {

    @Test
    fun zeroAmountReturnsSameInstance() {
        val a = Adjustments(contrast = 0.3f)
        assertSame(a, a.plusScaled(Adjustments(contrast = 1f), 0f))
    }

    @Test
    fun plusScaledAddsAndClamps() {
        val r = Adjustments(contrast = 0.8f, exposure = 3.5f, grain = 0.9f)
            .plusScaled(Adjustments(contrast = 1f, exposure = 2f, grain = 1f, saturation = -0.5f), 0.5f)
        assertEquals(1f, r.contrast, 0f)          // 0.8 + 0.5 → обмеження 1
        assertEquals(4f, r.exposure, 0f)          // 3.5 + 1 → обмеження 4 EV
        assertEquals(1f, r.grain, 0f)             // 0..1
        assertEquals(-0.25f, r.saturation, 1e-6f)
    }

    @Test
    fun plusScaledAppliesPerBandHsl() {
        val d = Adjustments().withHsl(HslBand.BLUE, HslAdjust(hue = -0.4f, sat = 0.2f))
        val r = Adjustments().withHsl(HslBand.BLUE, HslAdjust(hue = 0.1f)).plusScaled(d, 1f)
        assertEquals(-0.3f, r.hsl[HslBand.BLUE.ordinal].hue, 1e-6f)
        assertEquals(0.2f, r.hsl[HslBand.BLUE.ordinal].sat, 1e-6f)
        assertTrue(r.hsl[HslBand.RED.ordinal].isNeutral)
        assertEquals(8, r.hsl.size)
    }

    @Test
    fun withHslDoesNotMutateOriginal() {
        val a = Adjustments()
        val b = a.withHsl(HslBand.GREEN, HslAdjust(sat = 1f))
        assertTrue(a.hsl[HslBand.GREEN.ordinal].isNeutral)
        assertEquals(1f, b.hsl[HslBand.GREEN.ordinal].sat, 0f)
    }

    @Test
    fun gradeWheelsBlendAsVectors() {
        val cancel = Adjustments.blendWheel(GradeWheel(0f, 0.5f), GradeWheel(180f, 0.5f), 1f)
        assertTrue(cancel.sat < 1e-4f)
        val same = Adjustments.blendWheel(GradeWheel(40f, 0.2f), GradeWheel(40f, 0.3f), 1f)
        assertEquals(40f, same.hue, 1e-3f)
        assertEquals(0.5f, same.sat, 1e-5f)
        val fromNeutral = Adjustments.blendWheel(GradeWheel(), GradeWheel(200f, 0.4f, 0.1f), 0.5f)
        assertEquals(200f, fromNeutral.hue, 1e-3f)
        assertEquals(0.2f, fromNeutral.sat, 1e-5f)
        assertEquals(0.05f, fromNeutral.lum, 1e-6f)
        val wrap = Adjustments.blendWheel(GradeWheel(350f, 0.3f), GradeWheel(10f, 0.3f), 1f)
        assertTrue("відтінок 0..360", wrap.hue in 0f..360f)
        assertTrue(wrap.hue < 1f || wrap.hue > 359f)
    }

    @Test
    fun effectiveAdjustUsesPresetAndAmount() {
        val preset = Adjustments(contrast = 0.4f)
        val s = EditState(adjust = Adjustments(contrast = 0.1f), presetId = "x", presetAmount = 0.5f)
        assertEquals(0.3f, s.effectiveAdjust { if (it == "x") preset else null }.contrast, 1e-6f)
        assertEquals(0.1f, s.effectiveAdjust { null }.contrast, 0f)
        assertEquals(0.1f, EditState(adjust = Adjustments(contrast = 0.1f)).effectiveAdjust { preset }.contrast, 0f)
    }

    @Test
    fun builtInPresetsAreValid() {
        val ids = Presets.builtIn.map { it.id }
        assertEquals("унікальні id", ids.size, ids.toSet().size)
        assertTrue(Presets.builtIn.size >= 15)
        for (p in Presets.builtIn) {
            val d = p.delta
            assertTrue(p.name.isNotBlank())
            assertTrue(p.builtIn)
            assertEquals(8, d.hsl.size)
            for (v in listOf(d.brightness, d.contrast, d.highlights, d.shadows, d.whites, d.blacks, d.temperature, d.tint, d.saturation, d.vibrance, d.vignette))
                assertTrue("${p.id}: $v", v in -1f..1f)
            assertTrue(d.grain in 0f..1f && d.fade in 0f..1f && d.exposure in -4f..4f)
            for (w in listOf(d.grading.shadows, d.grading.midtones, d.grading.highlights))
                assertTrue(w.hue in 0f..360f && w.sat in 0f..1f)
        }
    }

    @Test
    fun presetCategoriesCoverRequestedLooks() {
        val cats = Presets.builtIn.map { it.category }.toSet()
        assertTrue(cats.containsAll(listOf(PresetCategory.CINEMA, PresetCategory.VINTAGE, PresetCategory.BW)))
        // Лише відомі ключі — для кожного є перекладений підпис
        val known = setOf(PresetCategory.BASIC, PresetCategory.CINEMA, PresetCategory.VINTAGE, PresetCategory.BW, PresetCategory.MOOD)
        assertTrue(known.containsAll(cats))
        // Ч/Б-пресети справді знебарвлюють
        Presets.builtIn.filter { it.category == PresetCategory.BW }.forEach { assertEquals(-1f, it.delta.saturation, 0f) }
    }

    @Test
    fun findLooksInUserPresetsToo() {
        val user = Preset("user_1", "Мій", "Мої", Adjustments(exposure = 1f), builtIn = false)
        assertNotNull(Presets.find("noir"))
        assertSame(user, Presets.find("user_1", listOf(user)))
        assertNull(Presets.find("nope", listOf(user)))
    }

    @Test
    fun geometryIdentityFlag() {
        assertTrue(Geometry().isIdentity)
        assertTrue(!Geometry(straighten = 1f).isIdentity)
        assertTrue(!Geometry(crop = NRect(0f, 0f, 0.5f, 1f)).isIdentity)
        assertTrue(NRect.FULL.isFull)
    }

    @Test
    fun aspectRatiosMatchSocialFormats() {
        assertEquals(4f / 5f, AspectRatio.INSTA_PORTRAIT.ratio!!, 1e-6f)
        assertEquals(9f / 16f, AspectRatio.STORIES.ratio!!, 1e-6f)
        assertNull(AspectRatio.FREE.ratio)
        assertEquals(-1f, AspectRatio.ORIGINAL.ratio!!, 0f)
    }

    @Test
    fun detailFieldsScaleAndClamp() {
        val d = Adjustments(sharpen = 0.8f, clarity = 0.6f, dehaze = -0.9f, noise = 0.7f, colorNoise = 0.5f)
        val r = Adjustments(sharpen = 0.5f, clarity = 0.7f).plusScaled(d, 1f)
        assertEquals(1f, r.sharpen, 1e-6f)   // 0..1
        assertEquals(1f, r.clarity, 1e-6f)   // -1..1
        assertEquals(-0.9f, r.dehaze, 1e-6f)
        val half = Adjustments().plusScaled(d, 0.5f)
        assertEquals(0.35f, half.noise, 1e-6f)
        assertEquals(0.25f, half.colorNoise, 1e-6f)
    }
}
