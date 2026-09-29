package com.photoeditor.app.core

import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.NRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryMathTest {

    private val w = 640
    private val h = 420
    private val eps = 1e-4f

    private fun assertMaps(gm: GeometryMath, srcU: Float, srcV: Float, outU: Float, outV: Float) {
        val o = gm.sourceToOutUv(srcU, srcV)
        assertEquals("u", outU, o[0], eps)
        assertEquals("v", outV, o[1], eps)
    }

    @Test
    fun identityMapsPointsToThemselves() {
        val gm = GeometryMath(w, h, Geometry())
        for (p in listOf(0f to 0f, 0.3f to 0.7f, 1f to 1f)) assertMaps(gm, p.first, p.second, p.first, p.second)
        assertEquals(w.toDouble(), gm.outW, 1e-9)
        assertEquals(h.toDouble(), gm.outH, 1e-9)
        assertEquals(1.0, gm.autoScale, 1e-12)
    }

    @Test
    fun quarterTurnsRotateClockwise() {
        // Лівий верхній кут джерела після 90° → правий верхній, після 180° → правий нижній, після 270° → лівий нижній
        assertMaps(GeometryMath(w, h, Geometry(quarterTurns = 1)), 0f, 0f, 1f, 0f)
        assertMaps(GeometryMath(w, h, Geometry(quarterTurns = 2)), 0f, 0f, 1f, 1f)
        assertMaps(GeometryMath(w, h, Geometry(quarterTurns = 3)), 0f, 0f, 0f, 1f)
        // 4 оберти = тотожність, від'ємні теж нормалізуються
        assertMaps(GeometryMath(w, h, Geometry(quarterTurns = 4)), 0.2f, 0.1f, 0.2f, 0.1f)
        assertMaps(GeometryMath(w, h, Geometry(quarterTurns = -1)), 0f, 0f, 0f, 1f)
    }

    @Test
    fun oddTurnsSwapFrameSize() {
        val gm = GeometryMath(w, h, Geometry(quarterTurns = 1))
        assertEquals(h.toDouble(), gm.frameW, 1e-9)
        assertEquals(w.toDouble(), gm.frameH, 1e-9)
        assertEquals(h.toDouble() / w, gm.outAspect, 1e-9)
    }

    @Test
    fun flipsMirror() {
        assertMaps(GeometryMath(w, h, Geometry(flipH = true)), 0.2f, 0.3f, 0.8f, 0.3f)
        assertMaps(GeometryMath(w, h, Geometry(flipV = true)), 0.2f, 0.3f, 0.2f, 0.7f)
    }

    @Test
    fun cropSelectsSubRegion() {
        val gm = GeometryMath(w, h, Geometry(crop = NRect(0.25f, 0.5f, 0.75f, 1f)))
        assertMaps(gm, 0.25f, 0.5f, 0f, 0f)
        assertMaps(gm, 0.75f, 1f, 1f, 1f)
        assertEquals(w * 0.5, gm.outW, 1e-3)
        assertEquals(h * 0.5, gm.outH, 1e-3)
    }

    @Test
    fun cropOverrideIgnoresStateCrop() {
        val gm = GeometryMath(w, h, Geometry(crop = NRect(0.25f, 0.5f, 0.75f, 1f)), cropOverride = NRect.FULL)
        assertMaps(gm, 0.3f, 0.3f, 0.3f, 0.3f)
    }

    @Test
    fun complexChainRoundTrips() {
        val gm = GeometryMath(
            w, h,
            Geometry(quarterTurns = 3, flipH = true, straighten = 7f, perspectiveV = 0.4f, perspectiveH = -0.3f, crop = NRect(0.1f, 0.2f, 0.8f, 0.9f)),
        )
        for (i in 0..10) for (j in 0..10) {
            val s = gm.outToSourceUv(i / 10f, j / 10f)
            val o = gm.sourceToOutUv(s[0], s[1])
            assertEquals(i / 10f, o[0], eps)
            assertEquals(j / 10f, o[1], eps)
        }
    }

    @Test
    fun straightenAndPerspectiveNeverShowEmptyCorners() {
        for (angle in listOf(-45f, -12f, 3f, 30f, 45f)) for (pv in listOf(-1f, 0f, 0.8f)) for (ph in listOf(-0.6f, 0f, 1f)) {
            val gm = GeometryMath(w, h, Geometry(straighten = angle, perspectiveV = pv, perspectiveH = ph))
            for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0f, 0f to 0.5f)) {
                val s = gm.outToSourceUv(x, y)
                assertTrue("кут поза зображенням: a=$angle pv=$pv ph=$ph -> ${s.toList()}",
                    s[0] in -2e-3f..1.002f && s[1] in -2e-3f..1.002f)
            }
        }
    }

    @Test
    fun coverScaleIsMinimal() {
        val gm = GeometryMath(w, h, Geometry(straighten = 10f))
        assertTrue(gm.autoScale > 1.0)
        // Трохи менший масштаб уже відкриває кут
        val shrunk = Mat3.scale(gm.autoScale * 0.97, gm.autoScale * 0.97) * Mat3.rotate(Math.toRadians(10.0))
        val inv = shrunk.inverse()
        val outside = listOf(1.0 to 1.0, 1.0 to -1.0, -1.0 to 1.0, -1.0 to -1.0).any { (sx, sy) ->
            val q = inv.map(sx * w / 2.0, sy * h / 2.0)
            abs(q[0]) > w / 2.0 || abs(q[1]) > h / 2.0
        }
        assertTrue(outside)
    }

    @Test
    fun centeredCropHasRequestedAspect() {
        for (ratio in listOf(1.0, 4.0 / 5, 9.0 / 16, 16.0 / 9, 3.0 / 2)) {
            val c = GeometryMath.centeredCrop(640.0, 420.0, ratio)
            assertEquals(ratio, (c.w * 640.0) / (c.h * 420.0), 1e-4)
            assertTrue(c.l >= 0f && c.t >= 0f && c.r <= 1f && c.b <= 1f)
            assertEquals(0.5f, (c.l + c.r) / 2, 1e-6f)
            assertEquals(0.5f, (c.t + c.b) / 2, 1e-6f)
            assertTrue("хоч одна сторона на повну", c.w > 0.9999f || c.h > 0.9999f)
        }
    }

    @Test
    fun fitRectLetterboxes() {
        val wide = GeometryMath.fitRect(1000f, 1000f, 2f)
        assertEquals(1000f, wide[2], 1e-3f); assertEquals(500f, wide[3], 1e-3f); assertEquals(250f, wide[1], 1e-3f)
        val tall = GeometryMath.fitRect(1000f, 1000f, 0.5f)
        assertEquals(500f, tall[2], 1e-3f); assertEquals(250f, tall[0], 1e-3f)
        val zero = GeometryMath.fitRect(0f, 0f, 1f)
        assertEquals(0f, zero[2], 0f)
    }

    @Test
    fun mat3InverseAndColumnMajor() {
        val m = Mat3.translate(3.0, -2.0) * Mat3.rotate(0.7) * Mat3.scale(2.0, 0.5)
        val id = m * m.inverse()
        for (i in 0..2) for (j in 0..2) assertEquals(if (i == j) 1.0 else 0.0, id.m[i * 3 + j], 1e-9)
        val cm = Mat3.translate(5.0, 7.0).toColumnMajorFloats()
        // Трансляція в стовпчиковій розкладці — останній стовпчик: індекси 6, 7
        assertEquals(5f, cm[6], 0f)
        assertEquals(7f, cm[7], 0f)
    }

    // ------------------------------------------------------------------ Дисторсія об'єктива

    @Test
    fun distortionKeepsCenterAndInvertsExactly() {
        for (d in listOf(-1f, -0.4f, 0.5f, 1f)) {
            val gm = GeometryMath(4000, 3000, Geometry(distortion = d))
            val c = gm.distort(0.5, 0.5)
            assertEquals(0.5f, c[0], 1e-6f); assertEquals(0.5f, c[1], 1e-6f)
            for ((u, v) in listOf(0.1 to 0.2, 0.9 to 0.95, 0.5 to 0.02, 0.33 to 0.71)) {
                val p = gm.distort(u, v)
                val back = gm.undistort(p[0].toDouble(), p[1].toDouble())
                assertEquals("d=$d u", u, back[0], 1e-5)
                assertEquals("d=$d v", v, back[1], 1e-5)
            }
        }
    }

    @Test
    fun distortionNeverSamplesOutsideImage() {
        // Кожна точка межі результату бере колір усередині джерела — порожніх країв не буває
        for (d in listOf(-1f, -0.5f, 0.5f, 1f)) for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 2000 to 2000)) {
            val gm = GeometryMath(w, h, Geometry(distortion = d))
            for (i in 0..100) {
                val t = i / 100f
                for ((ou, ov) in listOf(t to 0f, t to 1f, 0f to t, 1f to t)) {
                    val s = gm.outToSourceUv(ou, ov)
                    assertTrue("d=$d ${w}x$h ($ou,$ov) -> ${s.toList()}", s[0] in -1e-4f..1.0001f && s[1] in -1e-4f..1.0001f)
                }
            }
        }
    }

    @Test
    fun barrelCorrectionSamplesFartherFromCenter() {
        // «Бочка» стискає краї; виправлений піксель бере колір далі від центру, ніж лежить сам
        val gm = GeometryMath(4000, 3000, Geometry(distortion = 1f))
        val s = gm.outToSourceUv(0.5f, 0.3f)
        assertTrue("v=${s[1]}", s[1] < 0.3f)
        // Кадр після screen→source→screen повертається на місце
        val o = gm.sourceToOutUv(s[0], s[1])
        assertEquals(0.3f, o[1], 1e-4f)
    }
}
