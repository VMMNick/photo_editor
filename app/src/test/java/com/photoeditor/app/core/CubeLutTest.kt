package com.photoeditor.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {

    private fun cube(size: Int, title: String? = "Test", header: String = "", f: (Float, Float, Float) -> Triple<Float, Float, Float>): String {
        val sb = StringBuilder()
        title?.let { sb.append("TITLE \"$it\"\n") }
        sb.append("# коментар\n$header")
        sb.append("LUT_3D_SIZE $size\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val (x, y, z) = f(r / (size - 1f), g / (size - 1f), b / (size - 1f))
            sb.append("$x $y $z\n")
        }
        return sb.toString()
    }

    @Test
    fun parsesTitleSizeAndRedFastestOrder() {
        val lut = CubeLut.parse(cube(3) { r, g, b -> Triple(r, g, b) })
        assertEquals("Test", lut.title)
        assertEquals(3, lut.size)
        // Другий запис — r = 0.5, g = b = 0 (червоний змінюється найшвидше)
        assertEquals(0.5f, lut.data[3], 1e-6f)
        assertEquals(0f, lut.data[4], 1e-6f)
    }

    @Test
    fun identitySamplesBackTheInput() {
        val lut = CubeLut.parse(cube(17) { r, g, b -> Triple(r, g, b) })
        val s = lut.sample(0.31f, 0.77f, 0.05f)
        assertEquals(0.31f, s[0], 1e-5f); assertEquals(0.77f, s[1], 1e-5f); assertEquals(0.05f, s[2], 1e-5f)
    }

    @Test
    fun trilinearInterpolationBetweenNodes() {
        val lut = CubeLut.parse(cube(2) { r, g, b -> Triple(1 - r, g * g, b) })
        val s = lut.sample(0.25f, 0.5f, 1f)
        assertEquals(0.75f, s[0], 1e-6f)
        assertEquals(0.5f, s[1], 1e-6f) // 2 вузли → лінійна інтерполяція між 0 і 1
        assertEquals(1f, s[2], 1e-6f)
    }

    @Test
    fun domainIsNormalized() {
        val text = cube(2, header = "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n") { r, g, b -> Triple(r * 2, g * 2, b * 2) }
        val lut = CubeLut.parse(text)
        assertEquals(1f, lut.data.max(), 1e-6f)
    }

    @Test
    fun missingTitleUsesFileName() {
        assertEquals("film", CubeLut.parse(cube(2, title = null) { r, g, b -> Triple(r, g, b) }, "film").title)
    }

    @Test
    fun rejectsBrokenFiles() {
        fun fails(text: String) = assertTrue(runCatching { CubeLut.parse(text) }.isFailure)
        fails("")
        fails("LUT_1D_SIZE 256\n0 0 0\n")
        fails("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n")                        // замало рядків
        fails("0 0 0\nLUT_3D_SIZE 2\n")                                 // дані до розміру
        fails("LUT_3D_SIZE 200\n")                                      // завеликий
        fails(cube(2) { r, g, b -> Triple(r, g, b) }.replace("1.0 1.0 1.0", "1.0 x 1.0"))
    }

    @Test
    fun rgba8HasOpaqueAlphaAndClamps() {
        val lut = CubeLut.parse(cube(2) { r, _, _ -> Triple(r * 1.5f, -0.2f, 0.5f) })
        val bytes = lut.toRgba8()
        assertEquals(2 * 2 * 2 * 4, bytes.size)
        assertEquals(255, bytes[4].toInt() and 0xFF)  // 1.5 → 1.0
        assertEquals(0, bytes[5].toInt() and 0xFF)    // -0.2 → 0
        assertEquals(255, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun presetLutAppliesOnlyWithoutUserLut() {
        val preset = com.photoeditor.app.model.Adjustments(lut = "p", lutAmount = 1f)
        val none = com.photoeditor.app.model.Adjustments().plusScaled(preset, 0.4f)
        assertEquals("p", none.lut); assertEquals(0.4f, none.lutAmount, 1e-6f)
        val user = com.photoeditor.app.model.Adjustments(lut = "u", lutAmount = 0.7f).plusScaled(preset, 1f)
        assertEquals("u", user.lut); assertEquals(0.7f, user.lutAmount, 1e-6f)
    }
}
