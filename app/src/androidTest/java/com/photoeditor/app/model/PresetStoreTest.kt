package com.photoeditor.app.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** org.json є лише в Android, тому серіалізація пресетів тестується на пристрої. */
@RunWith(AndroidJUnit4::class)
class PresetStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.filesDir, "user_presets.json")

    @Before fun clean() { file.delete() }
    @After fun cleanAfter() { file.delete() }

    private val full = Adjustments(
        exposure = 1.25f, brightness = -0.1f, contrast = 0.3f, highlights = -0.4f, shadows = 0.5f,
        whites = 0.1f, blacks = -0.2f, temperature = 0.35f, tint = -0.05f, saturation = -0.3f, vibrance = 0.2f,
        hsl = List(8) { HslAdjust(hue = it / 10f, sat = -it / 20f, lum = 0.05f) },
        grading = ColorGrading(GradeWheel(210f, 0.4f, -0.1f), GradeWheel(30f, 0.1f, 0f), GradeWheel(45f, 0.3f, 0.05f), blending = 0.7f, balance = -0.3f),
        vignette = -0.4f, grain = 0.2f, fade = 0.15f,
    )

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val back = PresetStore.adjustFromJson(PresetStore.adjustToJson(full))
        // Float → Double → Float без втрат для цих значень
        assertEquals(full, back)
    }

    @Test
    fun saveAndLoad() {
        val store = PresetStore(context)
        val p = Preset("user_1", "Мій теплий", "Мої", full, builtIn = false)
        store.save(listOf(p) + Presets.builtIn) // вбудовані не зберігаються
        val loaded = PresetStore(context).load()
        assertEquals(1, loaded.size)
        assertEquals("Мій теплий", loaded[0].name)
        assertFalse(loaded[0].builtIn)
        assertEquals(full, loaded[0].delta)
    }

    @Test
    fun missingOrCorruptFileGivesEmptyList() {
        assertTrue(PresetStore(context).load().isEmpty())
        file.writeText("{ це не json")
        assertTrue(PresetStore(context).load().isEmpty())
    }

    @Test
    fun partialJsonUsesDefaults() {
        file.writeText("""[{"id":"u","name":"Старий","adjust":{"contrast":0.5}}]""")
        val p = PresetStore(context).load().single()
        assertEquals(0.5f, p.delta.contrast, 1e-6f)
        assertEquals(0.5f, p.delta.grading.blending, 1e-6f)
        assertEquals(8, p.delta.hsl.size)
    }
}
