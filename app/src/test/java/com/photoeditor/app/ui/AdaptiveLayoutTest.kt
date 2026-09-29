package com.photoeditor.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {

    // Типові розміри вікон у dp
    private val phonePortrait = 411f to 891f      // Pixel 7
    private val smallPhone = 320f to 568f         // найменші телефони
    private val phoneLandscape = 891f to 411f
    private val foldUnfolded = 673f to 841f       // розгорнутий фолд, портрет
    private val tabletPortrait = 800f to 1280f
    private val tabletLandscape = 1280f to 800f
    private val splitScreen = 411f to 420f        // пів екрана в режимі двох вікон

    private fun layout(s: Pair<Float, Float>) = AdaptiveLayout.forEditor(s.first, s.second)

    @Test
    fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, AdaptiveLayout.widthClass(599f))
        assertEquals(WidthClass.MEDIUM, AdaptiveLayout.widthClass(600f))
        assertEquals(WidthClass.MEDIUM, AdaptiveLayout.widthClass(839f))
        assertEquals(WidthClass.EXPANDED, AdaptiveLayout.widthClass(840f))
    }

    @Test
    fun phonePortraitUsesBottomPanelAndBar() {
        val l = layout(phonePortrait)
        assertEquals(PanelPlacement.BOTTOM, l.placement)
        assertEquals(ToolNav.BOTTOM_BAR, l.nav)
        assertTrue(l.panelSizeDp in 200f..300f)
        assertFalse(l.compactTopBar)
    }

    @Test
    fun landscapeMovesPanelToSideWithRail() {
        for (s in listOf(phoneLandscape, tabletLandscape)) {
            val l = layout(s)
            assertEquals(PanelPlacement.SIDE, l.placement)
            assertEquals(ToolNav.RAIL, l.nav)
            assertTrue("${l.panelSizeDp}", l.panelSizeDp in 260f..400f)
        }
        assertTrue("низький ландшафт — компактна верхня панель", layout(phoneLandscape).compactTopBar)
        assertFalse(layout(tabletLandscape).compactTopBar)
    }

    @Test
    fun tabletPortraitGetsTallerBottomPanel() {
        val tablet = layout(tabletPortrait)
        val phone = layout(phonePortrait)
        assertEquals(PanelPlacement.BOTTOM, tablet.placement)
        assertTrue(tablet.panelSizeDp > phone.panelSizeDp)
        assertEquals(PanelPlacement.BOTTOM, layout(foldUnfolded).placement)
        assertEquals(WidthClass.MEDIUM, layout(foldUnfolded).widthClass)
    }

    @Test
    fun photoAlwaysKeepsAtLeast40Percent() {
        val sizes = listOf(phonePortrait, smallPhone, phoneLandscape, foldUnfolded, tabletPortrait, tabletLandscape, splitScreen, 600f to 480f, 560f to 300f, 640f to 300f, 700f to 320f)
        for ((w, h) in sizes) {
            val l = AdaptiveLayout.forEditor(w, h)
            when (l.placement) {
                PanelPlacement.SIDE -> {
                    val preview = w - 80f - l.panelSizeDp
                    assertTrue("$w×$h: фото $preview", preview >= w * 0.4f - 1f)
                }
                PanelPlacement.BOTTOM -> {
                    val chrome = (if (l.compactTopBar) 48f else 56f) + 64f
                    val preview = h - chrome - l.panelSizeDp
                    assertTrue("$w×$h: фото $preview", preview >= (h - chrome) * 0.4f - 1f || l.panelSizeDp <= 150f)
                }
            }
        }
    }

    @Test
    fun narrowLandscapeStaysBottom() {
        // Вузьке, але «альбомне» вікно (split-screen на телефоні) — бічній панелі нема місця
        val l = AdaptiveLayout.forEditor(500f, 400f)
        assertEquals(PanelPlacement.BOTTOM, l.placement)
        assertTrue(l.compactTopBar)
        assertEquals(PanelPlacement.BOTTOM, AdaptiveLayout.forEditor(600f, 480f).placement)
        assertEquals(PanelPlacement.SIDE, AdaptiveLayout.forEditor(640f, 360f).placement)
    }

    @Test
    fun homeColumnsGrowWithWidth() {
        assertEquals(1, AdaptiveLayout.homeColumns(411f))
        assertEquals(2, AdaptiveLayout.homeColumns(700f))
        assertEquals(3, AdaptiveLayout.homeColumns(1280f))
    }
}
