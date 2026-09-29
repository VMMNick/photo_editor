package com.photoeditor.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0E0F11)
val Panel = Color(0xFF16181C)
val PanelHigh = Color(0xFF1F2227)
val Accent = Color(0xFFF2B13A)
val TextDim = Color(0xFF9AA0A8)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1B1300),
    secondary = Color(0xFF7FB7FF),
    background = Bg,
    onBackground = Color(0xFFECEDEF),
    surface = Panel,
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextDim,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    outline = Color(0xFF3A3F47),
)

@Composable
fun PhotoEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            labelMedium = TextStyle(fontSize = 12.sp, letterSpacing = 0.2.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
        ),
    ) {
        // Екрани не загорнуті в Surface, тож без цього Text без явного кольору малюється чорним по темному тлу
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
