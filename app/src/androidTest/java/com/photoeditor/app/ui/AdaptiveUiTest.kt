package com.photoeditor.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.photoeditor.app.R
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoeditor.app.TestFiles
import com.photoeditor.app.ui.theme.PhotoEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Розкладка під різні розміри вікна. Розмір задаємо примусово (requiredSize),
 * тож тест не залежить від екрана емулятора.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Тексти беремо з ресурсів — тест не залежить від мови емулятора. */
    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun vmWithImage(): EditorViewModel {
        lateinit var vm: EditorViewModel
        compose.runOnUiThread { vm = EditorViewModel(app) }
        val uri = TestFiles.writeJpeg(app, "adaptive.jpg", TestFiles.quadrants(600, 400))
        compose.runOnUiThread { vm.open(uri) }
        compose.waitUntil(15_000) { vm.info != null && !vm.loading }
        return vm
    }

    private fun sized(w: Int, h: Int, content: @Composable () -> Unit) {
        compose.setContent {
            PhotoEditorTheme { Box(Modifier.requiredSize(w.dp, h.dp)) { content() } }
        }
        compose.waitForIdle()
    }

    @Test
    fun phonePortraitHasBottomToolbar() {
        val vm = vmWithImage()
        sized(400, 800) { EditorScreen(vm) }
        compose.onNodeWithTag("layout_bottom").assertExists()
        compose.onNodeWithTag("toolbar_bottom").assertExists()
        compose.onNodeWithTag("toolbar_rail").assertDoesNotExist()
    }

    @Test
    fun landscapeHasRailAndSidePanel() {
        val vm = vmWithImage()
        sized(800, 380) { EditorScreen(vm) }
        compose.onNodeWithTag("layout_side").assertExists()
        compose.onNodeWithTag("toolbar_rail").assertExists()
        compose.onNodeWithTag("panel_side").assertExists()
        compose.onNodeWithTag("toolbar_bottom").assertDoesNotExist()
        // Інструменти в рейці працюють так само
        compose.onNodeWithText(str(R.string.tool_crop)).performClick()
        compose.onNodeWithText(str(R.string.adj_horizon)).assertExists()
        compose.runOnIdle { assertEquals(Tool.CROP, vm.tool) }
    }

    @Test
    fun tabletLandscapeUsesSideLayout() {
        val vm = vmWithImage()
        sized(1280, 800) { EditorScreen(vm) }
        compose.onNodeWithTag("layout_side").assertExists()
    }

    @Test
    fun tinyPhoneStillShowsAllTools() {
        val vm = vmWithImage()
        sized(320, 568) { EditorScreen(vm) }
        for (t in Tool.entries) compose.onNodeWithText(str(t.labelRes)).assertExists()
    }

    @Test
    fun stateSurvivesLayoutSwitch() {
        val vm = vmWithImage()
        compose.runOnUiThread { vm.tool = Tool.MASKS }
        var portrait by mutableStateOf(true)
        compose.setContent {
            PhotoEditorTheme {
                val w = if (portrait) 400 else 800
                val h = if (portrait) 800 else 380
                Box(Modifier.requiredSize(w.dp, h.dp)) { EditorScreen(vm) }
            }
        }
        compose.onNodeWithTag("layout_bottom").assertExists()
        compose.runOnIdle { portrait = false } // «поворот екрана»
        compose.onNodeWithTag("layout_side").assertExists()
        compose.runOnIdle { assertEquals(Tool.MASKS, vm.tool) }
    }

    @Test
    fun homeGridColumnsDependOnWidth() {
        lateinit var vm: EditorViewModel
        compose.runOnUiThread { vm = EditorViewModel(app) }
        var width by mutableStateOf(400)
        compose.setContent {
            PhotoEditorTheme { Box(Modifier.requiredSize(width.dp, 800.dp)) { HomeScreen(vm) {} } }
        }
        compose.onNodeWithTag("home_columns_1").assertExists()
        compose.runOnIdle { width = 700 }
        compose.onNodeWithTag("home_columns_2").assertExists()
        compose.runOnIdle { width = 1000 }
        compose.onNodeWithTag("home_columns_3").assertExists()
    }
}
