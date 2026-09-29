package com.photoeditor.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.photoeditor.app.R
import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoeditor.app.TestFiles
import com.photoeditor.app.ui.theme.PhotoEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Димові UI-тести Compose: екрани відкриваються, вкладки перемикаються, кнопки впливають на стан. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Тексти беремо з ресурсів — тест не залежить від мови емулятора. */
    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun editorWithImage(): EditorViewModel {
        lateinit var vm: EditorViewModel
        compose.runOnUiThread { vm = EditorViewModel(app) }
        val uri = TestFiles.writeJpeg(app, "ui_test.jpg", TestFiles.quadrants(600, 400))
        compose.runOnUiThread { vm.open(uri) }
        compose.waitUntil(15_000) { vm.info != null && !vm.loading }
        compose.setContent { PhotoEditorTheme { EditorScreen(vm) } }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun homeScreenShowsOpenButtonsAndFeatures() {
        lateinit var vm: EditorViewModel
        compose.runOnUiThread { vm = EditorViewModel(app) }
        compose.setContent { PhotoEditorTheme { HomeScreen(vm) {} } }
        compose.onNodeWithText(str(R.string.home_open_gallery)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.home_open_file)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.feat_retouch_title)).assertExists()
        compose.onNodeWithText(str(R.string.feat_masks_title)).assertExists()
    }

    @Test
    fun toolTabsSwitchPanels() {
        val vm = editorWithImage()
        compose.onNodeWithText(str(R.string.adj_horizon)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.tool_crop)).performClick()
        compose.onNodeWithText(str(R.string.adj_horizon)).assertExists()
        assertEquals(Tool.CROP, vm.tool)

        compose.onNodeWithText(str(R.string.tool_color)).performClick()
        compose.onNodeWithText(str(R.string.adj_temperature)).assertExists()

        compose.onNodeWithText(str(R.string.tool_detail)).performClick()
        compose.onNodeWithText(str(R.string.adj_clarity)).assertExists()
        compose.onNodeWithText(str(R.string.adj_noise_color)).assertExists()

        compose.onNodeWithText(str(R.string.tool_retouch)).performClick()
        compose.onNodeWithText(str(R.string.mode_heal)).assertExists()
        compose.onNodeWithText(str(R.string.mode_erase)).assertExists()

        compose.onNodeWithText(str(R.string.tool_filters)).performClick()
        compose.onNodeWithText(str(R.string.filter_none)).assertExists()

        compose.onNodeWithText(str(R.string.tool_masks)).performClick()
        compose.onNodeWithText(str(R.string.section_add_mask), substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun curveEditorTapAddsPointAndDragBendsCurve() {
        val vm = editorWithImage()
        compose.onNodeWithTag("histogram").assertExists()
        val editor = compose.onNodeWithTag("curve_editor").performScrollTo()
        // Торкання посередині діагоналі — нова точка, форма ще пряма
        editor.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(3, vm.state.adjust.curves.rgb.points.size)
            assertTrue(vm.state.adjust.curves.isIdentity)
        }
        // Тягнемо цю точку вгору — світлішає середина, у кривій записано нове значення
        editor.performTouchInput { swipe(center, center.copy(y = center.y - height * 0.2f), 300) }
        compose.runOnIdle {
            val mid = vm.state.adjust.curves.rgb.points[1]
            assertTrue("точку піднято: $mid", mid.y > mid.x + 0.1f)
            assertTrue(vm.canUndo)
        }
        // Канал: вибір синього не змінює RGB-криву, скидання повертає пряму
        compose.onNodeWithTag("curve_ch_BLUE").performClick()
        compose.runOnIdle { assertEquals(com.photoeditor.app.model.CurveChannel.BLUE, vm.curveChannel) }
        compose.runOnUiThread { vm.resetCurve(com.photoeditor.app.model.CurveChannel.RGB) }
        compose.runOnIdle { assertTrue(vm.state.adjust.curves.isIdentity) }
    }

    @Test
    fun scrollingOverCurveDoesNotChangeIt() {
        val vm = editorWithImage()
        val editor = compose.onNodeWithTag("curve_editor").performScrollTo()
        // Вертикальний свайп по лінії кривої (не по точці) — це прокрутка панелі, а не редагування
        editor.performTouchInput { swipeUp(startY = center.y + height * 0.2f, endY = center.y - height * 0.3f) }
        compose.runOnIdle { assertTrue(vm.state.adjust.curves.isIdentity) }
    }

    @Test
    fun histogramAppearsAfterOpening() {
        val vm = editorWithImage()
        compose.waitUntil(30_000) { vm.histogram != null }
        val h = vm.histogram!!
        // Квадранти червоний/зелений/синій/жовтий: у червоному каналі половина пікселів ≈ 255 (JPEG)
        val bright = (245..255).sumOf { h.red[it] }
        assertTrue("червоний ≈255: $bright з ${h.total}", bright > h.total * 0.4)
    }

    @Test
    fun compareButtonTapTogglesSplitAndHoldShowsOriginal() {
        val vm = editorWithImage()
        val btn = compose.onNodeWithTag("compare_button")
        btn.performClick()
        compose.runOnIdle { assertTrue(vm.splitCompare); assertTrue(!vm.compare) }
        compose.onNodeWithText(str(R.string.compare_before)).assertExists()
        compose.onNodeWithText(str(R.string.compare_after)).assertExists()
        compose.runOnIdle { assertTrue(vm.renderParams()!!.splitX in 0f..1f) }
        btn.performClick()
        compose.runOnIdle { assertTrue(!vm.splitCompare); assertEquals(-1f, vm.renderParams()!!.splitX, 0f) }
        // Утримання: оригінал лише поки палець на кнопці
        btn.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(vm.compare) }
        btn.performTouchInput { up() }
        compose.runOnIdle { assertTrue(!vm.compare); assertTrue("утримання не вмикає шторку", !vm.splitCompare) }
    }

    @Test
    fun moreMenuCopiesAndPastesSettings() {
        app.getSharedPreferences("look_clipboard", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val vm = editorWithImage()
        compose.onNodeWithTag("more_menu").performClick()
        compose.onNodeWithText(str(R.string.menu_paste_look)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.menu_batch)).assertIsNotEnabled()
        compose.runOnUiThread { vm.applyAdjust(vm.state.adjust.copy(contrast = 0.5f)) }
        compose.onNodeWithText(str(R.string.menu_copy_look)).performClick()
        compose.runOnIdle { assertTrue(vm.copiedLook != null) }
        compose.onNodeWithTag("more_menu").performClick()
        compose.onNodeWithText(str(R.string.menu_paste_look)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.menu_batch)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.cd_reset_all)).performClick()
        compose.onNodeWithText(str(R.string.dlg_reset_title)).assertExists()
    }

    @Test
    fun tipsCardShowsOnceAndCanBeDismissed() {
        app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val vm = editorWithImage()
        compose.onNodeWithTag("tips_card").assertExists()
        compose.onNodeWithText(str(R.string.tips_ok)).performClick()
        compose.onNodeWithTag("tips_card").assertDoesNotExist()
        compose.runOnIdle { assertTrue(!vm.showTips) }
        compose.runOnUiThread { assertTrue("не показується вдруге", !EditorViewModel(app).showTips) }
    }

    @Test
    fun undoButtonFollowsHistory() {
        val vm = editorWithImage()
        compose.onNodeWithContentDescription(str(R.string.cd_undo)).assertIsNotEnabled()
        compose.runOnUiThread { vm.applyAdjust(vm.state.adjust.copy(exposure = 1f)) }
        compose.onNodeWithContentDescription(str(R.string.cd_undo)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0f, vm.state.adjust.exposure, 0f) }
        compose.onNodeWithContentDescription(str(R.string.cd_redo)).assertIsEnabled()
    }

    @Test
    fun cropPanelButtonsChangeGeometry() {
        val vm = editorWithImage()
        compose.onNodeWithText(str(R.string.tool_crop)).performClick()
        compose.onNodeWithContentDescription(str(R.string.cd_rotate_right)).performClick()
        compose.runOnIdle { assertEquals(1, vm.state.geometry.quarterTurns) }
        compose.onNodeWithText("1:1").performClick()
        compose.runOnIdle {
            val gm = vm.geometryMath(forCropTool = true)
            val c = vm.state.geometry.crop
            assertEquals(1.0, c.w * gm.frameW / (c.h * gm.frameH), 1e-3)
        }
        compose.onNodeWithContentDescription(str(R.string.cd_reset_geometry)).performClick()
        compose.runOnIdle { assertTrue(vm.state.geometry.isIdentity) }
    }

    @Test
    fun addingMaskFromPanel() {
        val vm = editorWithImage()
        compose.onNodeWithText(str(R.string.tool_masks)).performClick()
        compose.onNodeWithText(str(R.string.mask_linear)).performClick()
        compose.runOnIdle { assertEquals(1, vm.state.masks.size) }
        compose.onNodeWithText(str(R.string.section_mask_adjust), substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithContentDescription(str(R.string.cd_delete)).performClick()
        compose.runOnIdle { assertEquals(0, vm.state.masks.size) }
    }

    @Test
    fun applyingFilterFromPanel() {
        val vm = editorWithImage()
        compose.onNodeWithText(str(R.string.tool_filters)).performClick()
        // Стрічка пресетів лінива — фільтруємо категорію, щоб картка точно була на екрані
        compose.onNodeWithText(str(R.string.cat_cinema)).performClick()
        compose.onNodeWithText(str(R.string.preset_noir)).performClick()
        compose.runOnIdle { assertEquals("noir", vm.state.presetId) }
        compose.onNodeWithText(str(R.string.filter_amount)).assertExists()
        compose.onNodeWithText(str(R.string.filter_none)).performClick()
        compose.runOnIdle { assertEquals(null, vm.state.presetId) }
    }

    @Test
    fun exportDialogOpensAndCloses() {
        editorWithImage()
        compose.onNodeWithContentDescription(str(R.string.cd_export)).performClick()
        compose.onNodeWithText("Pictures/PhotoEditor", substring = true).assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        compose.onNodeWithText("Pictures/PhotoEditor", substring = true).assertDoesNotExist()
    }
}
