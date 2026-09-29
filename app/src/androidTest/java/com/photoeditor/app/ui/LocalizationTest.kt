package com.photoeditor.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoeditor.app.R
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.model.HslBand
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun localized(tag: String): Context {
        val cfg = Configuration(app.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return app.createConfigurationContext(cfg)
    }

    @Test
    fun ukrainianAndEnglishResolve() {
        val uk = localized("uk")
        val en = localized("en")
        assertEquals("Фоторедактор", uk.getString(R.string.app_name))
        assertEquals("Photo Editor", en.getString(R.string.app_name))
        assertNotEquals(uk.getString(R.string.tool_crop), en.getString(R.string.tool_crop))
    }

    @Test
    fun unsupportedLocaleFallsBackToEnglish() {
        assertEquals("Photo Editor", localized("de").getString(R.string.app_name))
    }

    @Test
    fun everyBuiltInPresetHasTranslatedNameAndCategory() {
        val uk = localized("uk")
        for (p in Presets.builtIn) {
            val nameRes = presetNameRes(p)
            assertNotNull("немає назви для ${p.id}", nameRes)
            assertTrue(uk.getString(nameRes!!).isNotBlank())
            assertNotNull("немає підпису категорії ${p.category}", categoryLabelRes(p.category))
        }
    }

    @Test
    fun everyEnumHasLabel() {
        val uk = localized("uk")
        HslBand.entries.forEach { assertTrue(uk.getString(it.labelRes()).isNotBlank()) }
        AspectRatio.entries.forEach { assertTrue(uk.getString(it.labelRes()).isNotBlank()) }
        MaskKind.entries.forEach { assertTrue(uk.getString(it.labelRes()).isNotBlank()) }
        Tool.entries.forEach { assertTrue(uk.getString(it.labelRes).isNotBlank()) }
        RetouchMode.entries.forEach { assertTrue(uk.getString(it.labelRes).isNotBlank()) }
    }

    @Test
    fun formattedStringsAcceptArguments() {
        val uk = localized("uk")
        assertEquals("Скасувати останній (3)", uk.getString(R.string.retouch_undo_last, 3))
        assertEquals("Ретуш у повній роздільності: 1 / 4", uk.getString(R.string.progress_retouch, 1, 4))
        assertEquals("Undo last (3)", localized("en").getString(R.string.retouch_undo_last, 3))
    }
}
