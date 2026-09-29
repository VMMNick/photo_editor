package com.photoeditor.app.ui

import android.app.Application
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoeditor.app.TestFiles
import com.photoeditor.app.image.ExportFormat
import com.photoeditor.app.image.ExportSize
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.Presets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: EditorViewModel

    /** Виконати на головному потоці; падіння асерта повертаємо в тестовий потік, а не валимо процес. */
    private fun onMain(block: () -> Unit) {
        var error: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (t: Throwable) { error = t }
        }
        error?.let { throw it }
    }

    /** Чекаємо асинхронну роботу ViewModel (корутини на Main). */
    private fun waitUntil(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var ok = false
            onMain { ok = cond() }
            if (ok) return
            Thread.sleep(50)
        }
        throw AssertionError("Не дочекались: $what")
    }

    @Before
    fun setUp() {
        File(app.filesDir, "user_presets.json").delete()
        File(app.filesDir, "session").deleteRecursively()
        app.getSharedPreferences("look_clipboard", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        onMain { vm = EditorViewModel(app) }
    }

    @After
    fun tearDown() {
        File(app.filesDir, "user_presets.json").delete()
    }

    private fun openTestImage(w: Int = 400, h: Int = 300) {
        val uri = TestFiles.writeJpeg(app, "vm_test.jpg", TestFiles.quadrants(w, h))
        onMain { vm.open(uri) }
        waitUntil(what = "відкриття фото") { vm.info != null && !vm.loading }
    }

    // ------------------------------------------------------------------ Історія

    @Test
    fun sliderGestureIsOneHistoryStep() = onMain {
        assertFalse(vm.canUndo)
        for (v in listOf(0.1f, 0.2f, 0.3f)) vm.previewAdjust(vm.state.adjust.copy(contrast = v))
        vm.commit()
        assertTrue(vm.canUndo)
        vm.undo()
        assertEquals(0f, vm.state.adjust.contrast, 0f)
        assertFalse(vm.canUndo)
        assertTrue(vm.canRedo)
        vm.redo()
        assertEquals(0.3f, vm.state.adjust.contrast, 0f)
    }

    @Test
    fun commitWithoutChangeAddsNothing() = onMain {
        vm.previewAdjust(vm.state.adjust)
        vm.commit()
        assertFalse(vm.canUndo)
    }

    @Test
    fun newEditClearsRedo() = onMain {
        vm.applyAdjust(Adjustments(exposure = 1f))
        vm.undo()
        assertTrue(vm.canRedo)
        vm.applyAdjust(Adjustments(saturation = 0.5f))
        assertFalse(vm.canRedo)
    }

    @Test
    fun undoCommitsPendingGestureFirst() = onMain {
        vm.previewAdjust(Adjustments(shadows = 0.4f))
        vm.undo() // незавершений жест спершу фіксується, а потім скасовується
        assertEquals(0f, vm.state.adjust.shadows, 0f)
        vm.redo()
        assertEquals(0.4f, vm.state.adjust.shadows, 0f)
    }

    @Test
    fun historyIsBounded() = onMain {
        repeat(120) { vm.applyAdjust(Adjustments(exposure = (it + 1) / 100f)) }
        var steps = 0
        while (vm.canUndo) { vm.undo(); steps++ }
        assertEquals(80, steps)
    }

    @Test
    fun resetAllIsUndoable() = onMain {
        vm.applyAdjust(Adjustments(contrast = 0.5f))
        vm.resetAll()
        assertEquals(0f, vm.state.adjust.contrast, 0f)
        vm.undo()
        assertEquals(0.5f, vm.state.adjust.contrast, 0f)
    }

    // ------------------------------------------------------------------ Фільтри

    @Test
    fun presetSelectionAndAmount() = onMain {
        val p = Presets.find("noir")!!
        vm.selectPreset(p)
        assertEquals("noir", vm.state.presetId)
        assertEquals(-1f, vm.effectiveAdjust.saturation, 1e-6f)
        vm.previewPresetAmount(0.5f); vm.commit()
        assertEquals(-0.5f, vm.effectiveAdjust.saturation, 1e-6f)
        vm.selectPreset(null)
        assertNull(vm.state.presetId)
        assertEquals(0f, vm.effectiveAdjust.saturation, 0f)
    }

    @Test
    fun userPresetIsPersisted() {
        onMain {
            vm.applyAdjust(Adjustments(contrast = 0.25f, temperature = -0.1f))
            vm.saveUserPreset("Тест")
            assertEquals(1, vm.userPresets.size)
        }
        var other: EditorViewModel? = null
        onMain { other = EditorViewModel(app) }
        val loaded = other!!.userPresets.single()
        assertEquals("Тест", loaded.name)
        assertEquals(0.25f, loaded.delta.contrast, 1e-6f)
        assertEquals(-0.1f, loaded.delta.temperature, 1e-6f)
        onMain { vm.deleteUserPreset(vm.userPresets.single()) }
        onMain { other = EditorViewModel(app) }
        assertTrue(other!!.userPresets.isEmpty())
    }

    // ------------------------------------------------------------------ Геометрія

    @Test
    fun fourQuarterTurnsReturnToStart() = onMain {
        repeat(4) { vm.rotate90(true) }
        assertEquals(0, vm.state.geometry.quarterTurns)
        vm.rotate90(false)
        assertEquals(3, vm.state.geometry.quarterTurns)
    }

    @Test
    fun aspectPresetProducesMatchingCrop() {
        openTestImage(400, 300)
        onMain {
            vm.setAspect(AspectRatio.STORIES)
            val gm = vm.geometryMath(forCropTool = true)
            val c = vm.state.geometry.crop
            assertEquals(9.0 / 16.0, c.w * gm.frameW / (c.h * gm.frameH), 1e-3)
            // Після повороту пропорція зберігається
            vm.rotate90(true)
            val gm2 = vm.geometryMath(forCropTool = true)
            val c2 = vm.state.geometry.crop
            assertEquals(9.0 / 16.0, c2.w * gm2.frameW / (c2.h * gm2.frameH), 1e-3)
            vm.setAspect(AspectRatio.ORIGINAL)
            assertEquals(1f, vm.state.geometry.crop.w, 1e-4f)
            assertEquals(1f, vm.state.geometry.crop.h, 1e-4f)
        }
    }

    // ------------------------------------------------------------------ Маски

    @Test
    fun masksAreLimitedToFour() = onMain {
        repeat(4) { vm.addMask(MaskKind.RADIAL) }
        assertEquals(4, vm.state.masks.size)
        assertEquals(3, vm.selectedMask)
        vm.addMask(MaskKind.LINEAR)
        assertEquals(4, vm.state.masks.size)
        assertNotNull(vm.message)
    }

    @Test
    fun deleteAndInvertMasks() = onMain {
        vm.addMask(MaskKind.LINEAR)
        vm.addMask(MaskKind.RADIAL)
        vm.duplicateMaskInverted(1)
        assertEquals(3, vm.state.masks.size)
        assertTrue(vm.state.masks[2].invert)
        assertTrue(vm.state.masks[1].id != vm.state.masks[2].id)
        vm.deleteMask(2)
        assertEquals(2, vm.state.masks.size)
        assertEquals(1, vm.selectedMask)
        vm.deleteMask(0); vm.deleteMask(0)
        assertEquals(-1, vm.selectedMask)
    }

    @Test
    fun brushMaskGetsRasterMap() {
        openTestImage()
        onMain {
            vm.addMask(MaskKind.BRUSH)
            val m = vm.state.masks[0]
            vm.updateMask(
                0,
                m.copy(strokes = listOf(com.photoeditor.app.model.BrushStroke(listOf(com.photoeditor.app.model.StrokePoint(0.5f, 0.5f)), 0.1f))),
                commit = true,
            )
        }
        waitUntil(what = "растрова карта пензля") { vm.maskMaps.firstOrNull()?.let { !it.isEmpty() } == true }
        onMain {
            val p = vm.renderParams()!!
            assertEquals(1, p.maskCount)
            assertNotNull(p.maskMaps[0])
        }
    }

    // ------------------------------------------------------------------ Ретуш і рендер

    @Test
    fun renderParamsNeedImage() = onMain { assertNull(vm.renderParams()) }

    @Test
    fun openImageResetsStateAndBuildsThumbnails() {
        onMain { vm.applyAdjust(Adjustments(exposure = 1f)) }
        openTestImage()
        onMain {
            assertEquals(400, vm.srcW)
            assertEquals(Adjustments(), vm.state.adjust)
            assertFalse(vm.canUndo)
            assertEquals(Tool.LIGHT, vm.tool)
        }
        waitUntil(what = "мініатюри фільтрів") { vm.thumbnails.size == vm.allPresets.size }
    }

    @Test
    fun cropToolRendersFullFrame() {
        openTestImage(400, 300)
        onMain {
            vm.setAspect(AspectRatio.SQUARE)
            vm.tool = Tool.LIGHT
            assertEquals(1f, vm.renderParams()!!.outAspect, 1e-3f)
            vm.tool = Tool.CROP
            assertEquals(400f / 300f, vm.renderParams()!!.outAspect, 1e-3f)
        }
    }

    @Test
    fun healChangesPreviewAndUndoRestoresIt() {
        openTestImage()
        var original: android.graphics.Bitmap? = null
        onMain {
            original = vm.displayBitmap
            vm.heal(0.5f, 0.5f, 0.03f) // межа квадрантів — точно щось зміниться
        }
        waitUntil(what = "застосування коректора") { vm.displayBitmap !== original && vm.busy == null }
        onMain { vm.undo() }
        waitUntil(what = "повернення після undo") {
            vm.displayBitmap?.getPixel(200, 150) == original!!.getPixel(200, 150)
        }
    }

    @Test
    fun healOutsideImageIgnored() = onMain {
        vm.heal(1.5f, 0.5f, 0.03f)
        assertTrue(vm.state.retouch.isEmpty())
    }

    @Test
    fun cloneNeedsSource() = onMain {
        val sp = com.photoeditor.app.model.StrokePoint(0.2f, 0.2f)
        vm.cloneStroke(listOf(sp), 0.02f)
        assertTrue(vm.state.retouch.isEmpty())
        assertNotNull(vm.message)
        vm.setCloneSource(0.6f, 0.6f)
        vm.cloneStroke(listOf(sp), 0.02f)
        val op = vm.state.retouch.single() as com.photoeditor.app.model.CloneOp
        assertEquals(0.4f, op.du, 1e-6f)
        assertEquals(0.4f, op.dv, 1e-6f)
    }

    @Test
    fun eraseBrushSelectionThenCommit() {
        openTestImage()
        onMain {
            val pts = listOf(com.photoeditor.app.model.StrokePoint(0.2f, 0.2f), com.photoeditor.app.model.StrokePoint(0.3f, 0.2f))
            vm.eraseBrush(pts, 0.03f)
            assertNotNull(vm.eraseSelection)
            vm.tool = Tool.RETOUCH
            vm.retouchMode = RetouchMode.ERASE
            // Виділення показується як тимчасова маска з підсвічуванням
            val p = vm.renderParams()!!
            assertEquals(1, p.maskCount)
            assertEquals(0, p.showMask)
            vm.commitErase()
            assertNull(vm.eraseSelection)
            assertEquals(1, vm.state.retouch.size)
        }
        waitUntil(what = "AI-ластик") { vm.busy == null && vm.displayBitmap != null }
    }

    // ------------------------------------------------------------------ Копіювання вигляду та пакетна обробка

    @Test
    fun copyAndPasteLookKeepsGeometryAndMasks() {
        openTestImage()
        onMain {
            assertEquals(null, vm.copiedLook)
            vm.applyAdjust(Adjustments(exposure = 0.8f, clarity = 0.4f))
            vm.selectPreset(com.photoeditor.app.model.Presets.find("noir"))
            vm.copyLook()
            assertNotNull(vm.copiedLook)
            // Інше редагування: інша геометрія й маска, які вставка не має чіпати
            vm.resetAll()
            vm.applyGeometry(Geometry(quarterTurns = 1))
            vm.addMask(MaskKind.RADIAL)
            vm.pasteLook()
            assertEquals(0.8f, vm.state.adjust.exposure, 0f)
            assertEquals(0.4f, vm.state.adjust.clarity, 0f)
            assertEquals("noir", vm.state.presetId)
            assertEquals(1, vm.state.geometry.quarterTurns)
            assertEquals(1, vm.state.masks.size)
            // Вставка — один крок історії
            vm.undo()
            assertEquals(0f, vm.state.adjust.exposure, 0f)
        }
        // Буфер переживає «перезапуск»
        onMain { assertEquals("noir", EditorViewModel(app).copiedLook?.presetId) }
    }

    @Test
    fun batchExportAppliesLookToEveryPhoto() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        openTestImage()
        onMain {
            vm.applyAdjust(Adjustments(saturation = -1f))
            vm.copyLook()
        }
        val uris = (1..3).map { TestFiles.writeJpeg(app, "batch_src_$it.jpg", TestFiles.quadrants(300 + it * 10, 200)) }
        val ok = app.getString(com.photoeditor.app.R.string.msg_batch_done, 3)
        onMain { vm.batchExport(uris, ExportSize.SOCIAL, ExportFormat.JPEG, 85) }
        waitUntil(60_000, "пакетний експорт") { vm.busy == null && vm.message == ok }
        val resolver = app.contentResolver
        var found = 0
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("batch_src_%"), null,
        )!!.use { c ->
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val bmp = resolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                val p = bmp.getPixel(20, 20)
                assertTrue("Ч/Б: ${Color.red(p)}/${Color.green(p)}", kotlin.math.abs(Color.red(p) - Color.green(p)) < 10)
                resolver.delete(uri, null, null)
                found++
            }
        }
        assertEquals(3, found)
    }

    @Test
    fun exportSavesToGallery() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) // на 8–9 потрібен дозвіл на запис
        openTestImage(800, 600)
        onMain {
            vm.applyAdjust(Adjustments(saturation = -1f))
            vm.export(ExportSize.MEDIUM, ExportFormat.JPEG, 90)
        }
        waitUntil(30_000, "експорт") { vm.busy == null && vm.message == app.getString(com.photoeditor.app.R.string.msg_exported) }
        onMain {
            assertEquals("content", vm.lastExportUri?.scheme)
            assertEquals("кнопка «Поділитися» в повідомленні", vm.lastExportUri, vm.messageShareUri)
        }
        // Знаходимо щойно збережений файл і перевіряємо, що він Ч/Б, потім прибираємо
        val resolver = app.contentResolver
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("vm_test_edit_%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )!!.use { c ->
            assertTrue("файл у галереї", c.moveToFirst())
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            val bmp = resolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            assertEquals(800, bmp.width)
            val p = bmp.getPixel(100, 100)
            assertTrue(kotlin.math.abs(Color.red(p) - Color.green(p)) < 10)
            // Метадані записані, орієнтація скинута (пікселі вже повернуті)
            val exif = resolver.openInputStream(uri)!!.use { androidx.exifinterface.media.ExifInterface(it) }
            assertEquals("PhotoEditor", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE))
            assertEquals(1, exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 0))
            resolver.delete(uri, null, null)
        }
    }

    // ------------------------------------------------------------------ Після рев'ю

    @Test
    fun maskMapsFollowMaskIdsAfterDeletion() {
        openTestImage()
        onMain {
            vm.addMask(MaskKind.BRUSH)
            vm.addMask(MaskKind.LINEAR)
            vm.addMask(MaskKind.BRUSH)
            val stroke = com.photoeditor.app.model.BrushStroke(listOf(com.photoeditor.app.model.StrokePoint(0.8f, 0.8f)), 0.05f)
            vm.updateMask(2, vm.state.masks[2].copy(strokes = listOf(stroke)), commit = true)
        }
        waitUntil(what = "карти масок") { vm.maskMaps[2]?.isEmpty() == false }
        onMain {
            vm.deleteMask(0) // тепер пензель зі штрихом — індекс 1
            val maps = vm.maskMaps
            assertEquals(2, maps.size)
            assertNull("лінійній масці карта не потрібна", maps[0])
            assertFalse("карта пензля переїхала разом з маскою", maps[1]!!.isEmpty())
        }
    }

    @Test
    fun maskOverlayOnlyWhileDraggingByDefault() {
        openTestImage()
        onMain {
            vm.tool = Tool.MASKS
            vm.addMask(MaskKind.RADIAL)
            assertFalse(vm.showMaskOverlay)
            assertEquals(-1, vm.renderParams()!!.showMask)
            vm.maskGestureActive = true
            assertEquals(0, vm.renderParams()!!.showMask)
            vm.maskGestureActive = false
            vm.showMaskOverlay = true
            assertEquals(0, vm.renderParams()!!.showMask)
        }
    }

    @Test
    fun eraseBrushPaintsIncrementallyAndCommitCopies() {
        openTestImage()
        onMain {
            val p1 = listOf(com.photoeditor.app.model.StrokePoint(0.2f, 0.2f), com.photoeditor.app.model.StrokePoint(0.25f, 0.2f))
            val p2 = listOf(com.photoeditor.app.model.StrokePoint(0.6f, 0.6f), com.photoeditor.app.model.StrokePoint(0.65f, 0.6f))
            vm.eraseBrush(p1, 0.03f)
            val first = vm.eraseSelection!!
            vm.eraseBrush(p2, 0.03f)
            val second = vm.eraseSelection!!
            assertTrue("новий об'єкт, щоб Compose/GL побачили зміну", first !== second)
            assertTrue(second.sampleUv(0.22f, 0.2f) > 0.5f && second.sampleUv(0.62f, 0.6f) > 0.5f)
            vm.commitErase()
            val op = vm.state.retouch.single() as com.photoeditor.app.model.EraseOp
            assertTrue("операція має власну копію даних", op.mask.data !== second.data)
        }
        waitUntil(what = "AI-ластик") { vm.busy == null }
    }

    @Test
    fun reopeningDiscardsStaleRetouch() {
        openTestImage(400, 300)
        onMain { vm.heal(0.5f, 0.5f, 0.05f) }
        // Одразу відкриваємо інше фото — результат ретушу першого не повинен його перезаписати
        val other = TestFiles.writeJpeg(app, "vm_other.jpg", TestFiles.solid(320, 200, Color.rgb(10, 200, 10)))
        onMain { vm.open(other) }
        waitUntil(what = "друге фото") { vm.srcW == 320 && !vm.loading }
        Thread.sleep(1500)
        onMain {
            assertEquals(320, vm.displayBitmap!!.width)
            assertTrue(vm.state.retouch.isEmpty())
        }
    }
}
