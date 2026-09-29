package com.photoeditor.app.ui

import kotlin.math.max
import kotlin.math.min

/*
 * Адаптивна розкладка редактора. Чиста логіка без Compose — тестується на JVM.
 *
 *  Телефон, портрет          Телефон/планшет, ландшафт       Планшет, портрет
 *  ┌──────────────┐          ┌───┬───────────────┬──────┐     ┌──────────────────┐
 *  │   top bar    │          │   │    top bar    │      │     │     top bar      │
 *  ├──────────────┤          │ R ├───────────────┤ Пан- │     ├──────────────────┤
 *  │    прев'ю    │          │ a │    прев'ю     │ ель  │     │      прев'ю      │
 *  ├──────────────┤          │ i │               │      │     ├──────────────────┤
 *  │    панель    │          │ l │               │      │     │  панель (вища)   │
 *  ├──────────────┤          └───┴───────────────┴──────┘     ├──────────────────┤
 *  │ інструменти  │                                           │   інструменти    │
 *  └──────────────┘                                           └──────────────────┘
 */

enum class PanelPlacement { BOTTOM, SIDE }
enum class ToolNav { BOTTOM_BAR, RAIL }
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

data class EditorLayout(
    val placement: PanelPlacement,
    val nav: ToolNav,
    /** Висота нижньої панелі або ширина бічної (dp). */
    val panelSizeDp: Float,
    /** Низький екран: компактний top bar без другого рядка. */
    val compactTopBar: Boolean,
    val widthClass: WidthClass,
)

object AdaptiveLayout {

    /** Material 3: < 600 — compact, 600..840 — medium, ≥ 840 — expanded. */
    fun widthClass(widthDp: Float): WidthClass = when {
        widthDp >= 840f -> WidthClass.EXPANDED
        widthDp >= 600f -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }

    /** Мінімальна частка екрана, яку завжди отримує саме фото. */
    private const val MIN_PREVIEW_SHARE = 0.4f

    fun forEditor(widthDp: Float, heightDp: Float): EditorLayout {
        val wc = widthClass(widthDp)
        val landscape = widthDp > heightDp
        val compactTopBar = heightDp < 480f

        // Широкий ландшафт (телефон боком, планшет, розгорнутий фолд): панель збоку, інструменти — рейкою
        // (поріг 640 dp: телефони боком мають 690–930 dp, вужчі вікна — це вже split-screen)
        if (landscape && widthDp >= 640f) {
            val panel = (widthDp * 0.34f).coerceIn(300f, 400f)
            // Рейка 80 dp + фото не менше 40% ширини
            val maxPanel = widthDp * (1f - MIN_PREVIEW_SHARE) - 80f
            return EditorLayout(PanelPlacement.SIDE, ToolNav.RAIL, min(panel, maxPanel), compactTopBar, wc)
        }

        // Портрет: панель знизу. На планшеті вона вища, щоб влізли всі повзунки без прокрутки
        val preferred = if (wc == WidthClass.COMPACT) heightDp * 0.36f else heightDp * 0.32f
        val range = if (wc == WidthClass.COMPACT) 200f..300f else 300f..420f
        // Верхня панель ~56 dp + рядок інструментів ~64 dp
        val chrome = (if (compactTopBar) 48f else 56f) + 64f
        val maxPanel = max(150f, (heightDp - chrome) * (1f - MIN_PREVIEW_SHARE))
        val panel = min(preferred.coerceIn(range.start, range.endInclusive), maxPanel)
        return EditorLayout(PanelPlacement.BOTTOM, ToolNav.BOTTOM_BAR, panel, compactTopBar, wc)
    }

    /** Кількість колонок карток можливостей на головному екрані. */
    fun homeColumns(widthDp: Float): Int = when (widthClass(widthDp)) {
        WidthClass.COMPACT -> 1
        WidthClass.MEDIUM -> 2
        WidthClass.EXPANDED -> 3
    }

    /** Максимальна ширина вмісту головного екрана, щоб рядки не розтягувались на весь планшет. */
    const val HOME_MAX_WIDTH_DP = 960f
}
