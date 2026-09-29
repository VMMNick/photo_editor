package com.photoeditor.app.ui.components

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoeditor.app.ui.theme.Accent
import com.photoeditor.app.ui.theme.TextDim
import java.util.Locale
import kotlin.math.roundToInt

/** Формат «+25» для значень -1..1. */
fun percent(v: Float): String {
    val i = (v * 100).roundToInt()
    return if (i > 0) "+$i" else "$i"
}

fun evFormat(v: Float): String = String.format(Locale.US, "%+.2f EV", v)

/**
 * Повзунок налаштування. Подвійний тап по назві — скинути до типового.
 * onChange викликається під час руху (без запису в історію), onDone — після відпускання.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    default: Float = 0f,
    format: (Float) -> String = ::percent,
    trackBrush: Brush? = null,
) {
    // Актуальні лямбди для жесту (pointerInput не перезапускається при рекомпозиції)
    val latestChange by rememberUpdatedState(onChange)
    val latestDone by rememberUpdatedState(onDone)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(default) {
                    detectTapGestures(onDoubleTap = { latestChange(default); latestDone() })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                format(value),
                style = MaterialTheme.typography.labelMedium,
                color = if (value != default) Accent else TextDim,
                fontWeight = if (value != default) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            if (trackBrush != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(trackBrush)
                )
            }
            val trackColor = MaterialTheme.colorScheme.outline
            val interaction = remember { MutableInteractionSource() }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = range,
                interactionSource = interaction,
                thumb = {
                    Box(Modifier.size(20.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
                },
                // Заливка від значення за замовчуванням (для -100..+100 — від центру), а не від лівого краю
                track = {
                    Canvas(Modifier.fillMaxWidth().height(6.dp).padding(horizontal = 10.dp)) {
                        val y = size.height / 2
                        val stroke = size.height
                        if (trackBrush == null) {
                            drawLine(trackColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                            val span = range.endInclusive - range.start
                            val f0 = ((default - range.start) / span).coerceIn(0f, 1f)
                            val f1 = ((value - range.start) / span).coerceIn(0f, 1f)
                            if (f0 != f1) {
                                drawLine(
                                    Accent, Offset(size.width * minOf(f0, f1), y), Offset(size.width * maxOf(f0, f1), y),
                                    stroke, StrokeCap.Round,
                                )
                            }
                        }
                    }
                },
                // TalkBack: назва повзунка і значення в тому ж форматі, що на екрані
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = label
                    stateDescription = format(value)
                },
            )
        }
    }
}

/** Горизонтальний ряд чіпів-перемикачів. */
@Composable
fun <T> ChipRow(
    items: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.18f),
                    selectedLabelColor = Accent,
                ),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TextDim,
        modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
            .clickable(onClick = onClick)
    )
}

val hueBrush = Brush.horizontalGradient(
    listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF),
        Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    )
)

val temperatureBrush = Brush.horizontalGradient(listOf(Color(0xFF3D7BFF), Color(0xFFDDDDDD), Color(0xFFFFB23D)))
val tintBrush = Brush.horizontalGradient(listOf(Color(0xFF3DDC5A), Color(0xFFDDDDDD), Color(0xFFE04DDC)))
