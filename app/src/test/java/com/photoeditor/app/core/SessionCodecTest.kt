package com.photoeditor.app.core

import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.ColorGrading
import com.photoeditor.app.model.CurvePoint
import com.photoeditor.app.model.Curves
import com.photoeditor.app.model.ToneCurve
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.GradeWheel
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.HslAdjust
import com.photoeditor.app.model.HslBand
import com.photoeditor.app.model.LocalAdjust
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.NRect
import com.photoeditor.app.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodecTest {

    private val meta = SessionMeta("IMG_0042 (копія).NEF", isRaw = true, fullWidth = 6000, fullHeight = 4000, usedEmbeddedPreview = true, sourceFile = "source.nef")

    private fun subject(): AlphaMap = AlphaMap(64, 48).also { m -> for (i in 0 until 64 * 20) m.data[i] = (i % 256).toByte() }

    private fun richState(): EditState {
        val subj = subject()
        val stroke = BrushStroke(listOf(StrokePoint(0.1f, 0.2f), StrokePoint(0.3f, 0.4f), StrokePoint(0.123456f, 0.987654f)), 0.031f, 0.7f, erase = true)
        return EditState(
            adjust = Adjustments(
                exposure = 1.37f, brightness = -0.1f, contrast = 0.33f, highlights = -0.8f, shadows = 0.6f,
                whites = 0.05f, blacks = -0.2f, temperature = 0.4f, tint = -0.15f, saturation = -0.3f, vibrance = 0.25f,
                vignette = -0.45f, grain = 0.2f, fade = 0.1f,
                sharpen = 0.4f, clarity = 0.25f, dehaze = -0.3f, noise = 0.5f, colorNoise = 0.6f,
                curves = Curves(
                    rgb = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.3f, 0.22f), CurvePoint(0.7f, 0.81f), CurvePoint(1f, 0.97f))),
                    blue = ToneCurve(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 0.9f))),
                ),
                grading = ColorGrading(GradeWheel(210f, 0.4f, -0.1f), GradeWheel(33.3f, 0.1f, 0f), GradeWheel(45f, 0.3f, 0.05f), 0.7f, -0.25f),
            ).withHsl(HslBand.BLUE, HslAdjust(-0.4f, 0.2f, 0.1f)),
            geometry = Geometry(quarterTurns = 3, flipH = true, flipV = false, straighten = -7.5f, perspectiveV = 0.3f, perspectiveH = -0.2f,
                crop = NRect(0.1f, 0.2f, 0.8f, 0.95f), aspect = AspectRatio.INSTA_PORTRAIT),
            masks = listOf(
                Mask(Long.MAX_VALUE - 7, MaskKind.LINEAR, x0 = 0.5f, y0 = 0f, x1 = 0.5f, y1 = 0.4f, adjust = LocalAdjust(exposure = -1f)),
                Mask(-123456789012345L, MaskKind.RADIAL, cx = 0.4f, cy = 0.6f, rx = 0.2f, ry = 0.1f, angle = 0.5f, feather = 0.3f, invert = true,
                    adjust = LocalAdjust(0.5f, 0.1f, -0.2f, 0.3f, 0.4f, -0.5f, 0.6f),
                    range = com.photoeditor.app.model.MaskRange(com.photoeditor.app.model.RangeMode.COLOR, 0.1f, 0.9f, 210f, 25f, 0.6f)),
                Mask(3, MaskKind.BRUSH, strokes = listOf(stroke, stroke.copy(erase = false))),
                Mask(4, MaskKind.SUBJECT, subject = subj, strokes = listOf(stroke)),
            ),
            retouch = listOf(
                HealOp(0.25f, 0.75f, 0.01f),
                CloneOp(stroke, -0.1f, 0.05f),
                EraseOp(subj), // та сама карта, що й у масці — має зберегтися один раз
                EraseOp(AlphaMap(10, 10)),
            ),
            presetId = "teal_orange",
            presetAmount = 0.65f,
        )
    }

    private fun roundTrip(state: EditState): Pair<SessionCodec.Decoded, SessionCodec.Encoded> {
        val enc = SessionCodec.encode(meta, state)
        // Імітуємо запис на диск: карти теж проходять через байти
        val maps = enc.maps.map { SessionCodec.decodeMap(SessionCodec.encodeMap(it)) }
        return SessionCodec.decode(enc.json, maps) to enc
    }

    @Test
    fun fullStateSurvivesRoundTrip() {
        val state = richState()
        val (dec, _) = roundTrip(state)
        assertEquals(meta, dec.meta)
        // AlphaMap порівнюється за ідентичністю, тому звіряємо все, крім карт, окремо
        assertEquals(state.adjust, dec.state.adjust)
        assertEquals(state.geometry, dec.state.geometry)
        assertEquals(state.presetId, dec.state.presetId)
        assertEquals(state.presetAmount, dec.state.presetAmount, 0f)
        assertEquals(state.masks.map { it.copy(subject = null) }, dec.state.masks.map { it.copy(subject = null) })
        assertTrue(state.masks[3].subject!!.data.contentEquals(dec.state.masks[3].subject!!.data))
        assertEquals(state.retouch[0], dec.state.retouch[0])
        assertEquals(state.retouch[1], dec.state.retouch[1])
        assertTrue((dec.state.retouch[2] as EraseOp).mask.data.contentEquals(subject().data))
    }

    @Test
    fun sharedMapsStoredOnce() {
        val (dec, enc) = roundTrip(richState())
        assertEquals("subject + порожня карта ластика", 2, enc.maps.size)
        assertSame("після відновлення маска і ластик знову ділять одну карту",
            dec.state.masks[3].subject, (dec.state.retouch[2] as EraseOp).mask)
    }

    @Test
    fun largeMaskIdsKeepPrecision() {
        val (dec, _) = roundTrip(richState())
        assertEquals(Long.MAX_VALUE - 7, dec.state.masks[0].id)
        assertEquals(-123456789012345L, dec.state.masks[1].id)
    }

    @Test
    fun sessionWithoutCurvesFromVersion11Loads() {
        // Сесії 1.1 не мали ключа "curves" — мають відкриватися з прямими кривими
        val enc = SessionCodec.encode(meta, EditState())
        val old = enc.json.replace(Regex(",\\s*\"curves\":\\{[^}]*\\}"), "")
        assertTrue("ключ прибрано", !old.contains("curves"))
        val dec = SessionCodec.decode(old, emptyList())
        assertTrue(dec.state.adjust.curves.isIdentity)
        // Так само сесії без полів деталізації (1.2)
        val noDetail = enc.json.replace(Regex(",\"sharpen\":[^,]*,\"clarity\":[^,]*,\"dehaze\":[^,]*,\"noise\":[^,]*,\"colorNoise\":[^,}]*"), "")
        assertTrue(!noDetail.contains("sharpen"))
        assertEquals(0f, SessionCodec.decode(noDetail, emptyList()).state.adjust.clarity, 0f)
    }

    @Test
    fun historyRoundTripsAndSharesMaps() {
        val cur = richState()
        val prev1 = cur.copy(adjust = cur.adjust.copy(exposure = 0f))
        val prev2 = prev1.copy(retouch = cur.retouch.take(2)) // без ластика
        val redo = cur.copy(adjust = cur.adjust.copy(contrast = -0.5f))
        val enc = SessionCodec.encode(meta, cur, undo = listOf(prev2, prev1), redo = listOf(redo))
        // Карти (AI-маска + ластик) спільні для всіх станів — у списку по одному разу
        assertEquals(2, enc.maps.size)
        val dec = SessionCodec.decode(enc.json, enc.maps)
        assertEquals(2, dec.undo.size)
        assertEquals(0f, dec.undo[1].adjust.exposure, 0f)
        assertEquals(2, dec.undo[0].retouch.size)
        assertEquals(-0.5f, dec.redo.single().adjust.contrast, 0f)
        // Ті самі об'єкти карт в усіх станах (пам'ять не дублюється після відновлення)
        assertSame(dec.state.masks[3].subject, dec.undo[1].masks[3].subject)
    }

    @Test
    fun brokenHistoryDoesNotLoseCurrentState() {
        val enc = SessionCodec.encode(meta, richState(), undo = listOf(EditState()))
        // Ламаємо стан в історії: без ключа "adjust" він не розбирається
        val broken = enc.json.replace("\"undo\":[{\"adjust\"", "\"undo\":[{\"adjustX\"")
        assertTrue(broken != enc.json)
        val dec = SessionCodec.decode(broken, enc.maps)
        assertEquals(richState().adjust, dec.state.adjust)
        assertTrue("пошкоджену історію відкинуто", dec.undo.isEmpty())
    }

    @Test
    fun mapFileNamesAreStoredWithState() {
        var n = 0
        val enc = SessionCodec.encode(meta, richState(), mapName = { "map_${n++}.bin" })
        assertEquals(listOf("map_0.bin", "map_1.bin"), SessionCodec.mapFiles(enc.json))
        assertNull("старий формат без імен", SessionCodec.mapFiles(SessionCodec.encode(meta, EditState()).json))
    }

    @Test
    fun emptyStateRoundTrips() {
        val (dec, enc) = roundTrip(EditState())
        assertEquals(EditState(), dec.state)
        assertTrue(enc.maps.isEmpty())
        assertNull(dec.state.presetId)
    }

    @Test
    fun maskBytesCompressWell() {
        val m = AlphaMap(1024, 672) // здебільшого нулі, як реальна маска пензля
        for (y in 300..340) for (x in 100..900) m.data[y * 1024 + x] = -1
        val bytes = SessionCodec.encodeMap(m)
        assertTrue("стиснення: ${bytes.size} байт", bytes.size < m.data.size / 50)
        assertTrue(m.data.contentEquals(SessionCodec.decodeMap(bytes).data))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureVersionRejected() {
        val json = SessionCodec.encode(meta, EditState()).json.replace("\"version\":1", "\"version\":99")
        SessionCodec.decode(json, emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingMapRejected() {
        val enc = SessionCodec.encode(meta, richState())
        SessionCodec.decode(enc.json, enc.maps.take(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedMaskRejected() {
        SessionCodec.decodeMap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
    }

    @Test
    fun miniJsonHandlesEscapesAndNumbers() {
        val v = mapOf(
            "s" to "лапки \" слеш \\ рядок\nтаб\t\u0001 емодзі 😀",
            "n" to listOf(0, -1.5, 1.0E-5, 123456789.0f, Long.MAX_VALUE),
            "b" to true, "z" to null, "e" to emptyList<Any>(), "o" to emptyMap<String, Any>(),
        )
        val parsed = MiniJson.parse(MiniJson.write(v)) as Map<*, *>
        assertEquals(v["s"], parsed["s"])
        val n = parsed["n"] as List<*>
        assertEquals(-1.5, n[1] as Double, 0.0)
        assertEquals(1.0E-5, n[2] as Double, 1e-20)
        assertEquals(123456789.0f, (n[3] as Double).toFloat(), 0f)
        assertEquals(true, parsed["b"])
        assertNull(parsed["z"])
        assertTrue((parsed["e"] as List<*>).isEmpty())
        assertTrue((parsed["o"] as Map<*, *>).isEmpty())
    }

    @Test
    fun floatsSurviveExactly() {
        for (f in listOf(0.1f, 1f / 3f, -7.123457f, 1.4E-45f, 3.4028235E38f, 0f)) {
            val back = ((MiniJson.parse(MiniJson.write(listOf(f))) as List<*>)[0] as Double).toFloat()
            assertEquals(f, back, 0f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun miniJsonRejectsGarbage() {
        MiniJson.parse("{\"a\": [1, 2,}")
    }
}
