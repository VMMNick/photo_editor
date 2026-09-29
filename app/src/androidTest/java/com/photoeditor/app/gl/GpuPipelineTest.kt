package com.photoeditor.app.gl

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoeditor.app.TestFiles
import com.photoeditor.app.TestFiles.near
import com.photoeditor.app.TestFiles.rgb
import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.CurvePoint
import com.photoeditor.app.model.Curves
import com.photoeditor.app.model.ToneCurve
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.LocalAdjust
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.Presets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Перевірка шейдера на справжньому GPU: офскрін-EGL, рендер у Bitmap, порівняння пікселів.
 * Заодно перевіряє, що GLSL компілюється драйвером пристрою.
 */
@RunWith(AndroidJUnit4::class)
class GpuPipelineTest {

    private lateinit var gl: OffscreenRenderer

    @Before
    fun setUp() {
        gl = OffscreenRenderer()
    }

    @After
    fun tearDown() {
        gl.close()
    }

    private fun render(src: Bitmap, s: EditState, maps: List<AlphaMap?> = emptyList(), bypass: Boolean = false): Bitmap {
        gl.setSource(src)
        val p = RenderParams.from(s, src.width, src.height, s.effectiveAdjust { Presets.find(it)?.delta }, maps, bypass = bypass)
        val size = OffscreenRenderer.outputSize(p, gl.maxTextureSize)
        return gl.render(p, size[0], size[1])
    }

    private fun lum(p: Int) = 0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)

    @Test
    fun neutralSettingsReproduceSource() {
        val src = TestFiles.quadrants(200, 100)
        val out = render(src, EditState())
        assertEquals(200, out.width)
        assertEquals(100, out.height)
        for ((x, y) in listOf(20 to 20, 180 to 20, 20 to 80, 180 to 80)) {
            assertTrue("($x,$y) ${rgb(out.getPixel(x, y)).toList()}", near(out.getPixel(x, y), src.getPixel(x, y), 3))
        }
    }

    @Test
    fun bypassShowsOriginal() {
        val src = TestFiles.solid(64, 64, Color.rgb(100, 120, 140))
        val out = render(src, EditState(adjust = Adjustments(exposure = 2f, saturation = -1f)), bypass = true)
        assertTrue(near(out.getPixel(32, 32), src.getPixel(32, 32), 2))
    }

    @Test
    fun splitShowsOriginalOnTheLeftOnly() {
        val src = TestFiles.solid(100, 50, Color.rgb(100, 100, 100))
        gl.setSource(src)
        val s = EditState(adjust = Adjustments(exposure = 2f))
        val p = RenderParams.from(s, 100, 50, s.adjust, emptyList(), splitX = 0.3f)
        val out = gl.render(p, 100, 50)
        assertTrue("ліворуч оригінал: ${rgb(out.getPixel(10, 25)).toList()}", near(out.getPixel(10, 25), src.getPixel(10, 25), 2))
        assertTrue("праворуч оброблене: ${rgb(out.getPixel(80, 25)).toList()}", lum(out.getPixel(80, 25)) > 160)
    }

    /** Сіре тло 128 із «сіллю й перцем» ±24 і різким краєм посередині. */
    private fun noisyEdge(w: Int = 128, h: Int = 64): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(3)
        for (y in 0 until h) for (x in 0 until w) {
            val base = if (x < w / 2) 70 else 180
            val n = (rnd.nextGaussian() * 12).toInt()
            val v = (base + n).coerceIn(0, 255)
            b.setPixel(x, y, Color.rgb(v, v, v))
        }
        return b
    }

    private fun stdDev(b: Bitmap, x0: Int, x1: Int): Double {
        val v = ArrayList<Double>()
        for (y in 8 until b.height - 8) for (x in x0 until x1) v += lum(b.getPixel(x, y))
        val m = v.average()
        return kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    @Test
    fun noiseReductionSmoothsFlatAreasButKeepsEdge() {
        val src = noisyEdge()
        val before = render(src, EditState())
        val after = render(src, EditState(adjust = Adjustments(noise = 1f)))
        assertTrue("шум зменшився: ${stdDev(after, 8, 56)} vs ${stdDev(before, 8, 56)}", stdDev(after, 8, 56) < stdDev(before, 8, 56) * 0.7)
        // Край лишається різким: різниця яскравості по обидва боки ~ та сама
        val jump = lum(after.getPixel(70, 32)) - lum(after.getPixel(57, 32))
        assertTrue("край: $jump", jump > 80)
    }

    @Test
    fun sharpeningIncreasesEdgeContrast() {
        val src = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) for (x in 0 until 64) {
            val v = (80 + 100 * ((x - 30).coerceIn(0, 4) / 4f)).toInt() // перехід 80 → 180 на x = 30..34
            src.setPixel(x, y, Color.rgb(v, v, v))
        }
        val plain = render(src, EditState())
        val sharp = render(src, EditState(adjust = Adjustments(sharpen = 1f)))
        // На краях переходу з'являється «підсилення»: світла сторона світлішає, темна темнішає
        assertTrue("світлий край: ${lum(sharp.getPixel(34, 16))} vs ${lum(plain.getPixel(34, 16))}", lum(sharp.getPixel(34, 16)) > lum(plain.getPixel(34, 16)) + 2)
        assertTrue("темний край: ${lum(sharp.getPixel(30, 16))} vs ${lum(plain.getPixel(30, 16))}", lum(sharp.getPixel(30, 16)) < lum(plain.getPixel(30, 16)) - 2)
        // Рівна ділянка не змінюється
        assertTrue(abs(lum(sharp.getPixel(5, 16)) - lum(plain.getPixel(5, 16))) < 2)
    }

    @Test
    fun clarityRaisesLocalContrast() {
        // Сірий квадрат 150 на тлі 110: чіткість (радіус ~1% кадру ≈ 5 px) збільшує різницю поблизу краю
        val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (y in 0 until 512) for (x in 0 until 512) {
            val v = if (x in 192..319 && y in 192..319) 150 else 110
            src.setPixel(x, y, Color.rgb(v, v, v))
        }
        val plain = render(src, EditState())
        val clear = render(src, EditState(adjust = Adjustments(clarity = 1f)))
        val d0 = lum(plain.getPixel(317, 256)) - lum(plain.getPixel(322, 256))
        val d1 = lum(clear.getPixel(317, 256)) - lum(clear.getPixel(322, 256))
        assertTrue("локальний контраст: $d1 vs $d0", d1 > d0 + 5)
    }

    @Test
    fun dehazeRestoresContrastOfHazyTexture() {
        // Шахівниця 60/120, накрита «туманом» 220 на 50% → 140/170: прибирання димки має розтягнути різницю
        val src = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) for (x in 0 until 128) {
            val scene = if (((x / 8) + (y / 8)) % 2 == 0) 60 else 120
            val v = (scene * 0.5 + 220 * 0.5).toInt()
            src.setPixel(x, y, Color.rgb(v, v, v))
        }
        val plain = render(src, EditState())
        val clear = render(src, EditState(adjust = Adjustments(dehaze = 1f)))
        val c0 = lum(plain.getPixel(68, 60)) - lum(plain.getPixel(60, 60))
        val c1 = lum(clear.getPixel(68, 60)) - lum(clear.getPixel(60, 60))
        assertTrue("контраст текстури: $c1 vs $c0", abs(c1) > abs(c0) * 1.2)
        val hazy = render(src, EditState(adjust = Adjustments(dehaze = -1f)))
        val c2 = lum(hazy.getPixel(68, 60)) - lum(hazy.getPixel(60, 60))
        assertTrue("додана димка зменшує контраст: $c2 vs $c0", abs(c2) < abs(c0))
    }

    @Test
    fun colorNoiseRemovesChromaSpeckles() {
        val src = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(5)
        for (y in 0 until 128) for (x in 0 until 128) {
            val d = rnd.nextInt(41) - 20
            src.setPixel(x, y, Color.rgb(128 + d, 128 - d, 128))
        }
        fun chroma(b: Bitmap): Double {
            var s = 0.0
            for (y in 16 until 112) for (x in 16 until 112) { val p = b.getPixel(x, y); s += abs(Color.red(p) - Color.green(p)) }
            return s / (96 * 96)
        }
        val plain = render(src, EditState())
        val clean = render(src, EditState(adjust = Adjustments(colorNoise = 1f)))
        assertTrue("кольоровий шум: ${chroma(clean)} vs ${chroma(plain)}", chroma(clean) < chroma(plain) * 0.4)
    }

    @Test
    fun lensVignettingBrightensCornersOnly() {
        val src = TestFiles.solid(200, 100, Color.rgb(100, 100, 100))
        val out = render(src, EditState(adjust = Adjustments(lensVignette = 1f)))
        assertTrue("центр без змін", abs(lum(out.getPixel(100, 50)) - 100) < 3)
        assertTrue("кут світліший: ${lum(out.getPixel(2, 2))}", lum(out.getPixel(2, 2)) > 130)
    }

    @Test
    fun chromaticCorrectionShiftsRedAndBlueOppositeWays() {
        // Біла вертикальна смуга праворуч від центру: після корекції червоний і синій канали зсуваються в різні боки
        val src = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 400) {
            val v = if (x in 300..303) 255 else 0
            src.setPixel(x, y, Color.rgb(v, v, v))
        }
        val out = render(src, EditState(adjust = Adjustments(chromatic = 1f)))
        var rSum = 0.0; var rW = 0.0; var bSum = 0.0; var bW = 0.0
        for (x in 280..325) { val p = out.getPixel(x, 50); rSum += x * Color.red(p); rW += Color.red(p); bSum += x * Color.blue(p); bW += Color.blue(p) }
        val rx = rSum / rW; val bx = bSum / bW
        assertTrue("центри каналів розійшлися: red=$rx blue=$bx", abs(rx - bx) > 0.8)
    }

    @Test
    fun distortionMovesPixelsButNotCenter() {
        val src = TestFiles.quadrants(400, 300)
        val plain = render(src, EditState())
        val fixed = render(src, EditState(geometry = Geometry(distortion = 1f)))
        assertEquals(plain.width, fixed.width)
        // Центральні квадранти на своїх місцях, кути не порожні (без фону)
        assertTrue(near(fixed.getPixel(100, 75), plain.getPixel(100, 75), 6))
        assertTrue(near(fixed.getPixel(300, 225), plain.getPixel(300, 225), 6))
        for ((x, y) in listOf(1 to 1, 398 to 1, 1 to 298, 398 to 298, 200 to 1, 1 to 150)) {
            val c = rgb(fixed.getPixel(x, y))
            assertTrue("край ($x,$y) не порожній: ${c.toList()}", c.max() > 100) // тло рендеру — чорне
        }
    }

    @Test
    fun luminanceRangeLimitsMaskToBrightAreas() {
        // Ліва половина темна (60), права світла (200); маска на все фото з діапазоном «лише світле»
        val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        for (y in 0 until 50) for (x in 0 until 100) { val v = if (x < 50) 60 else 200; src.setPixel(x, y, Color.rgb(v, v, v)) }
        val mask = Mask(1, MaskKind.RADIAL, cx = 0.5f, cy = 0.5f, rx = 2f, ry = 2f, feather = 0f,
            adjust = LocalAdjust(exposure = -1.5f),
            range = com.photoeditor.app.model.MaskRange(com.photoeditor.app.model.RangeMode.LUMINANCE, lo = 0.5f, hi = 1f, smooth = 0f))
        val out = render(src, EditState(masks = listOf(mask)), listOf(null))
        assertTrue("темне не змінилось: ${lum(out.getPixel(20, 25))}", abs(lum(out.getPixel(20, 25)) - 60) < 4)
        assertTrue("світле затемнено: ${lum(out.getPixel(80, 25))}", lum(out.getPixel(80, 25)) < 150)
    }

    @Test
    fun colorRangeLimitsMaskToHue() {
        // Червона і синя половини; маска діє лише на синій відтінок
        val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        for (y in 0 until 50) for (x in 0 until 100) src.setPixel(x, y, if (x < 50) Color.rgb(200, 40, 40) else Color.rgb(40, 80, 200))
        val mask = Mask(1, MaskKind.RADIAL, cx = 0.5f, cy = 0.5f, rx = 2f, ry = 2f, feather = 0f,
            adjust = LocalAdjust(saturation = -1f),
            range = com.photoeditor.app.model.MaskRange(com.photoeditor.app.model.RangeMode.COLOR, hue = 225f, hueWidth = 25f, smooth = 0.1f))
        val out = render(src, EditState(masks = listOf(mask)), listOf(null))
        val red = out.getPixel(20, 25); val blue = out.getPixel(80, 25)
        assertTrue("червоний лишився насиченим: ${rgb(red).toList()}", Color.red(red) - Color.green(red) > 120)
        assertTrue("синій знебарвлено: ${rgb(blue).toList()}", abs(Color.blue(blue) - Color.red(blue)) < 40)
    }

    @Test
    fun lutIsAppliedWithAmount() {
        val src = TestFiles.solid(32, 32, Color.rgb(60, 120, 200))
        gl.setSource(src)
        // LUT-інверсія: вихід = 1 - вхід
        val n = 9
        val d = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) { d[i++] = 1 - r / 8f; d[i++] = 1 - g / 8f; d[i++] = 1 - b / 8f }
        val lut = com.photoeditor.app.core.CubeLut("inv", n, d)
        fun renderWith(amount: Float): Int {
            val s = EditState(adjust = Adjustments(lut = "inv", lutAmount = amount))
            return gl.render(RenderParams.from(s, 32, 32, s.adjust, emptyList(), lut = lut), 32, 32).getPixel(16, 16)
        }
        assertTrue("повна інверсія: ${rgb(renderWith(1f)).toList()}", near(renderWith(1f), Color.rgb(195, 135, 55), 4))
        assertTrue("половина: ${rgb(renderWith(0.5f)).toList()}", near(renderWith(0.5f), Color.rgb(128, 128, 128), 4))
        // Нейтральний LUT нічого не змінює
        val id = com.photoeditor.app.core.CubeLut.identity(17)
        val s = EditState(adjust = Adjustments(lut = "id"))
        val same = gl.render(RenderParams.from(s, 32, 32, s.adjust, emptyList(), lut = id), 32, 32).getPixel(16, 16)
        assertTrue("identity: ${rgb(same).toList()}", near(same, src.getPixel(16, 16), 3))
    }

    @Test
    fun rgbCurveInvertsTones() {
        val src = TestFiles.solid(32, 32, Color.rgb(60, 60, 60))
        val invert = ToneCurve(listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f)))
        val out = render(src, EditState(adjust = Adjustments(curves = Curves(rgb = invert)))).getPixel(16, 16)
        assertTrue("інверсія: ${rgb(out).toList()}", near(out, Color.rgb(195, 195, 195), 4))
    }

    @Test
    fun channelCurveTintsOnlyThatChannel() {
        val src = TestFiles.solid(32, 32, Color.rgb(128, 128, 128))
        val lift = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.75f), CurvePoint(1f, 1f)))
        val out = render(src, EditState(adjust = Adjustments(curves = Curves(blue = lift)))).getPixel(16, 16)
        assertTrue("синій піднятий: ${rgb(out).toList()}", Color.blue(out) > 180)
        assertTrue("червоний без змін: ${rgb(out).toList()}", abs(Color.red(out) - 128) <= 3)
        assertTrue("зелений без змін: ${rgb(out).toList()}", abs(Color.green(out) - 128) <= 3)
    }

    @Test
    fun clipWarningMarksBlownAndBlackPixels() {
        val src = TestFiles.quadrants(64, 64)
        gl.setSource(src)
        val s = EditState(adjust = Adjustments(exposure = 4f))
        val p = RenderParams.from(s, 64, 64, s.adjust, emptyList(), clipWarning = true)
        val out = gl.render(p, 64, 64)
        // Після +4 EV світлі квадранти вибиті → червоне попередження
        val px = out.getPixel(48, 48)
        assertTrue("пересвіт підсвічено: ${rgb(px).toList()}", Color.red(px) > 230 && Color.green(px) < 60)
    }

    @Test
    fun exposureBrightensAndDarkens() {
        val src = TestFiles.solid(32, 32, Color.rgb(100, 100, 100))
        val base = lum(render(src, EditState()).getPixel(16, 16))
        val up = lum(render(src, EditState(adjust = Adjustments(exposure = 1f))).getPixel(16, 16))
        val down = lum(render(src, EditState(adjust = Adjustments(exposure = -1f))).getPixel(16, 16))
        assertTrue("+1 EV: $up vs $base", up > base * 1.25)
        assertTrue("-1 EV: $down vs $base", down < base * 0.8)
    }

    @Test
    fun desaturationProducesGray() {
        val src = TestFiles.solid(32, 32, Color.rgb(220, 60, 40))
        val c = rgb(render(src, EditState(adjust = Adjustments(saturation = -1f))).getPixel(16, 16))
        assertTrue(c.toList().toString(), abs(c[0] - c[1]) <= 2 && abs(c[1] - c[2]) <= 2)
    }

    @Test
    fun warmTemperatureRaisesRedLowersBlue() {
        val src = TestFiles.solid(32, 32, Color.rgb(128, 128, 128))
        val c = rgb(render(src, EditState(adjust = Adjustments(temperature = 0.8f))).getPixel(16, 16))
        assertTrue(c.toList().toString(), c[0] > c[2] + 15)
    }

    @Test
    fun contrastSpreadsTones() {
        val dark = TestFiles.solid(16, 16, Color.rgb(60, 60, 60))
        val light = TestFiles.solid(16, 16, Color.rgb(190, 190, 190))
        val s = EditState(adjust = Adjustments(contrast = 0.8f))
        assertTrue(lum(render(dark, s).getPixel(8, 8)) < 55)
        assertTrue(lum(render(light, s).getPixel(8, 8)) > 195)
    }

    @Test
    fun quarterTurnRotatesClockwise() {
        val src = TestFiles.quadrants(200, 100)
        val out = render(src, EditState(geometry = Geometry(quarterTurns = 1)))
        assertEquals(100, out.width)
        assertEquals(200, out.height)
        // Після повороту за годинниковою в лівому верхньому куті — колишній лівий нижній (синій)
        assertTrue(near(out.getPixel(10, 10), Color.BLUE))
        assertTrue(near(out.getPixel(90, 10), Color.RED))
        assertTrue(near(out.getPixel(90, 190), Color.GREEN))
    }

    @Test
    fun flipMirrorsHorizontally() {
        val out = render(TestFiles.quadrants(200, 100), EditState(geometry = Geometry(flipH = true)))
        assertTrue(near(out.getPixel(10, 10), Color.GREEN))
        assertTrue(near(out.getPixel(190, 10), Color.RED))
    }

    @Test
    fun cropOutputsOnlySelectedQuadrant() {
        val out = render(
            TestFiles.quadrants(200, 100),
            EditState(geometry = Geometry(crop = com.photoeditor.app.model.NRect(0.5f, 0.5f, 1f, 1f))),
        )
        assertEquals(100, out.width)
        assertEquals(50, out.height)
        assertTrue(near(out.getPixel(5, 5), Color.YELLOW))
        assertTrue(near(out.getPixel(95, 45), Color.YELLOW))
    }

    @Test
    fun tallOutputIsRenderedInCorrectOrderAcrossStrips() {
        // Висота > 512 → кілька смуг; перевіряємо, що смуги не переставлені і не перевернуті
        val src = Bitmap.createBitmap(40, 1300, Bitmap.Config.ARGB_8888)
        for (y in 0 until 1300) for (x in 0 until 40) src.setPixel(x, y, Color.rgb(y * 255 / 1299, 0, 255 - y * 255 / 1299))
        val out = render(src, EditState())
        for (y in listOf(0, 300, 511, 512, 700, 1024, 1299)) {
            val r = Color.red(out.getPixel(20, y))
            assertTrue("рядок $y: r=$r", abs(r - y * 255 / 1299) <= 4)
        }
    }

    @Test
    fun linearMaskDarkensOnlyItsSide() {
        val src = TestFiles.solid(100, 100, Color.rgb(150, 150, 150))
        val mask = Mask(1, MaskKind.LINEAR, x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 0.4f, adjust = LocalAdjust(exposure = -2f))
        val out = render(src, EditState(masks = listOf(mask)))
        assertTrue(lum(out.getPixel(50, 2)) < 110)
        assertTrue(abs(lum(out.getPixel(50, 90)) - 150) < 3)
    }

    @Test
    fun radialMaskBrightensCenter() {
        val src = TestFiles.solid(100, 100, Color.rgb(100, 100, 100))
        val mask = Mask(1, MaskKind.RADIAL, cx = 0.5f, cy = 0.5f, rx = 0.2f, ry = 0.2f, feather = 0.2f, adjust = LocalAdjust(exposure = 1.5f))
        val out = render(src, EditState(masks = listOf(mask)))
        assertTrue(lum(out.getPixel(50, 50)) > 130)
        assertTrue(abs(lum(out.getPixel(5, 5)) - 100) < 3)
    }

    @Test
    fun rasterMaskUsesMapAndPendingMapHasNoEffect() {
        val src = TestFiles.solid(100, 100, Color.rgb(100, 100, 100))
        val map = AlphaMap(10, 10).also { m -> for (y in 0 until 10) for (x in 0 until 5) m.data[y * 10 + x] = -1 }
        val mask = Mask(1, MaskKind.BRUSH, adjust = LocalAdjust(exposure = 2f))
        val withMap = render(src, EditState(masks = listOf(mask)), listOf(map))
        assertTrue(lum(withMap.getPixel(10, 50)) > 140)
        assertTrue(abs(lum(withMap.getPixel(90, 50)) - 100) < 3)
        // Карта ще рахується (null) → маска порожня, а не на весь кадр
        val pending = render(src, EditState(masks = listOf(mask)), listOf(null))
        assertTrue(abs(lum(pending.getPixel(10, 50)) - 100) < 3)
    }

    @Test
    fun allBuiltInPresetsRender() {
        val src = TestFiles.quadrants(64, 64)
        for (p in Presets.builtIn) {
            val out = render(src, EditState(presetId = p.id))
            assertEquals(p.id, 64, out.width)
        }
        // Ч/Б-пресет справді знебарвлює
        val bw = rgb(render(src, EditState(presetId = "bw")).getPixel(10, 10))
        assertTrue(abs(bw[0] - bw[1]) <= 3)
    }

    @Test
    fun presetThumbnailsForEveryPreset() = runBlocking {
        val thumbs = GlWorker.presetThumbnails(TestFiles.quadrants(400, 300), Presets.builtIn, side = 120)
        assertEquals(Presets.builtIn.size, thumbs.size)
        thumbs.values.forEach { assertEquals(120, it.width); assertEquals(90, it.height) }
    }
}
