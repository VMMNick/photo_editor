package com.photoeditor.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoeditor.app.TestFiles
import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.SessionMeta
import com.photoeditor.app.image.SessionStore
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Збереження сесії на диск і відновлення після «смерті процесу» (новий ViewModel). */
@RunWith(AndroidJUnit4::class)
class SessionRestoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sessionDir get() = File(app.filesDir, "session")

    private fun onMain(block: () -> Unit) {
        var error: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { try { block() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }

    private fun waitUntil(timeoutMs: Long = 20_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            onMain { ok = cond() }
            if (ok) return
            Thread.sleep(50)
        }
        throw AssertionError("Не дочекались: $what")
    }

    private val recentDir get() = File(app.filesDir, "recent")
    @Before fun clean() { sessionDir.deleteRecursively(); recentDir.deleteRecursively() }
    @After fun cleanAfter() { sessionDir.deleteRecursively(); recentDir.deleteRecursively() }

    // ------------------------------------------------------------------ SessionStore

    @Test
    fun storeRoundTripAndMapReuse() {
        val store = SessionStore(app)
        val src = TestFiles.writeJpeg(app, "store_src.jpg", TestFiles.quadrants(200, 100))
        val copy = store.importSource(app, src, "Моє фото.JPG")
        assertEquals("source.jpg", copy.name)
        assertTrue(copy.length() > 0)

        val map = AlphaMap(50, 50).also { it.data[10] = 7 }
        val meta = SessionMeta("Моє фото.JPG", false, 200, 100, false, copy.name)
        val s1 = EditState(adjust = Adjustments(contrast = 0.4f), retouch = listOf(EraseOp(map)))
        store.save(meta, s1)
        val mapFiles1 = sessionDir.listFiles { f -> f.name.startsWith("map_") }!!.map { it.name }.toSet()
        assertEquals(1, mapFiles1.size)

        // Та сама карта в новому стані — файл не переписується
        store.save(meta, s1.copy(adjust = Adjustments(contrast = 0.5f)))
        assertEquals(mapFiles1, sessionDir.listFiles { f -> f.name.startsWith("map_") }!!.map { it.name }.toSet())

        // Карта більше не використовується — файл прибирається
        store.save(meta, EditState(adjust = Adjustments(contrast = 0.6f)))
        assertTrue(sessionDir.listFiles { f -> f.name.startsWith("map_") }!!.isEmpty())

        val restored = SessionStore(app).load()!!
        assertEquals(meta, restored.meta)
        assertEquals(0.6f, restored.state.adjust.contrast, 0f)
        assertEquals(copy.absolutePath, restored.source.absolutePath)
    }

    @Test
    fun corruptedStateIsIgnored() {
        val store = SessionStore(app)
        store.importSource(app, TestFiles.writeJpeg(app, "c.jpg", TestFiles.quadrants(20, 20)), "c.jpg")
        File(sessionDir, "state.json").writeText("{ зламано")
        assertNull(store.load())
    }

    @Test
    fun missingSourceMeansNoSession() {
        val store = SessionStore(app)
        val copy = store.importSource(app, TestFiles.writeJpeg(app, "m.jpg", TestFiles.quadrants(20, 20)), "m.jpg")
        store.save(SessionMeta("m.jpg", false, 20, 20, false, copy.name), EditState())
        copy.delete()
        assertNull(store.load())
    }

    // ------------------------------------------------------------------ ViewModel

    @Test
    fun editSurvivesProcessDeath() {
        var vm: EditorViewModel? = null
        onMain { vm = EditorViewModel(app) }
        val uri = TestFiles.writeJpeg(app, "restore_me.jpg", TestFiles.quadrants(400, 300))
        onMain { vm!!.open(uri) }
        waitUntil(what = "відкриття") { vm!!.info != null && !vm!!.loading }
        onMain {
            vm!!.applyAdjust(Adjustments(exposure = 0.7f, saturation = -0.4f))
            vm!!.applyGeometry(Geometry(straighten = 5f))
            vm!!.addMask(MaskKind.RADIAL)
            vm!!.heal(0.5f, 0.5f, 0.03f)
            vm!!.selectPreset(com.photoeditor.app.model.Presets.find("noir"))
            vm!!.saveSessionNow()
        }
        waitUntil(what = "збереження") { File(sessionDir, "state.json").let { it.isFile && it.readText().contains("noir") } }
        val expected = vm!!.state

        // «Смерть процесу»: новий ViewModel без жодного стану в пам'яті
        var restored: EditorViewModel? = null
        onMain {
            restored = EditorViewModel(app)
            assertTrue(restored!!.restoreSessionIfAny())
        }
        waitUntil(what = "відновлення") { restored!!.info != null && !restored!!.loading && restored!!.busy == null }
        onMain {
            val r = restored!!
            assertEquals("restore_me.jpg", r.info!!.name)
            assertEquals(expected.adjust, r.state.adjust)
            assertEquals(expected.geometry, r.state.geometry)
            assertEquals(expected.presetId, r.state.presetId)
            assertEquals(1, r.state.masks.size)
            assertEquals(expected.retouch, r.state.retouch) // HealOp — data class, порівнюється за значенням
            assertTrue(r.state.retouch.single() is HealOp)
            assertNotNull(r.renderParams())
            // Історія теж відновилася: можна скасувати зміни, зроблені до «смерті» процесу
            assertTrue("історія після відновлення", r.canUndo)
            r.undo() // фільтр
            assertEquals(null, r.state.presetId)
            assertEquals(1, r.state.retouch.size)
            r.undo() // коректор
            assertTrue(r.state.retouch.isEmpty())
            r.undo(); r.undo(); r.undo() // маска, геометрія, світло
            assertEquals(EditState(), r.state)
            assertFalse(r.canUndo)
            assertTrue(r.canRedo)
            r.redo()
            assertEquals(0.7f, r.state.adjust.exposure, 0f)
        }
    }

    @Test
    fun closingPhotoDeletesSession() {
        var vm: EditorViewModel? = null
        onMain { vm = EditorViewModel(app) }
        onMain { vm!!.open(TestFiles.writeJpeg(app, "close_me.jpg", TestFiles.quadrants(100, 80))) }
        waitUntil(what = "відкриття") { vm!!.info != null && !vm!!.loading }
        waitUntil(what = "збереження") { File(sessionDir, "state.json").isFile }
        onMain { vm!!.close() }
        waitUntil(what = "очищення") { !sessionDir.exists() }
        onMain { assertFalse(EditorViewModel(app).restoreSessionIfAny()) }
    }

    @Test
    fun closingEditedPhotoMovesItToRecentAndItCanBeResumed() {
        var vm: EditorViewModel? = null
        onMain { vm = EditorViewModel(app) }
        onMain { vm!!.open(TestFiles.writeJpeg(app, "later.jpg", TestFiles.quadrants(300, 200))) }
        waitUntil(what = "відкриття") { vm!!.info != null && !vm!!.loading }
        onMain {
            vm!!.applyAdjust(Adjustments(exposure = 0.6f))
            vm!!.applyAdjust(Adjustments(exposure = 0.6f, contrast = 0.3f))
            vm!!.close()
        }
        waitUntil(what = "перенесення в нещодавні") { vm!!.recents.size == 1 && vm!!.recents[0].thumb != null }
        onMain {
            assertEquals("later.jpg", vm!!.recents[0].name)
            assertFalse("поточної сесії більше немає", sessionDir.exists())
        }
        // Інший ViewModel (новий запуск) теж бачить відкладене редагування
        var vm2: EditorViewModel? = null
        onMain { vm2 = EditorViewModel(app) }
        waitUntil(what = "список нещодавніх") { vm2!!.recents.size == 1 }
        onMain { vm2!!.openRecent(vm2!!.recents[0].id) }
        waitUntil(what = "відновлення") { vm2!!.info != null && !vm2!!.loading }
        onMain {
            assertEquals(0.3f, vm2!!.state.adjust.contrast, 0f)
            assertTrue("історія на місці", vm2!!.canUndo)
            vm2!!.undo()
            assertEquals(0f, vm2!!.state.adjust.contrast, 0f)
        }
        waitUntil(what = "запис прибрано з нещодавніх") { vm2!!.recents.isEmpty() }
    }

    @Test
    fun exportFileNamedAfterOriginalNotWorkingCopy() {
        var vm: EditorViewModel? = null
        onMain { vm = EditorViewModel(app) }
        onMain { vm!!.open(TestFiles.writeJpeg(app, "holiday.jpg", TestFiles.quadrants(100, 80))) }
        waitUntil(what = "відкриття") { vm!!.info != null && !vm!!.loading }
        onMain {
            assertEquals("holiday.jpg", vm!!.info!!.name)
            assertEquals("file", vm!!.sourceUri!!.scheme)
            assertTrue(vm!!.sourceUri!!.path!!.contains("/session/source"))
        }
    }

    @Test
    fun subjectMaskMapIsPersisted() {
        var vm: EditorViewModel? = null
        onMain { vm = EditorViewModel(app) }
        onMain { vm!!.open(TestFiles.writeJpeg(app, "subj.jpg", TestFiles.quadrants(200, 100))) }
        waitUntil(what = "відкриття") { vm!!.info != null && !vm!!.loading }
        val subject = AlphaMap(40, 20).also { m -> for (i in 0 until 400) m.data[i] = -1 }
        onMain {
            // Маску «об'єкта» додаємо напряму (без ML Kit, щоб тест не залежав від Play Services)
            vm!!.apply(vm!!.state.copy(masks = listOf(Mask(9, MaskKind.SUBJECT, subject = subject))))
            vm!!.saveSessionNow()
        }
        waitUntil(what = "збереження карти") { sessionDir.listFiles { f -> f.name.startsWith("map_") }?.isNotEmpty() == true }
        Thread.sleep(300)
        val r = SessionStore(app).load()!!
        assertTrue(r.state.masks.single().subject!!.data.contentEquals(subject.data))
    }
}
