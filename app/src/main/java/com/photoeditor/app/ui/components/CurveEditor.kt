package com.photoeditor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photoeditor.app.R
import com.photoeditor.app.core.CurveMath
import com.photoeditor.app.core.Histogram
import com.photoeditor.app.model.CurveChannel
import com.photoeditor.app.model.ToneCurve
import com.photoeditor.app.ui.theme.Accent
import com.photoeditor.app.ui.theme.TextDim
import java.util.Locale
import kotlin.math.abs

private val RedC = Color(0xFFFF5A5F)
private val GreenC = Color(0xFF3DDC84)
private val BlueC = Color(0xFF4DA3FF)

fun CurveChannel.color(): Color = when (this) {
    CurveChannel.RGB -> Color(0xFFECEDEF)
    CurveChannel.RED -> RedC
    CurveChannel.GREEN -> GreenC
    CurveChannel.BLUE -> BlueC
}

fun CurveChannel.labelRes(): Int = when (this) {
    CurveChannel.RGB -> R.string.curve_rgb
    CurveChannel.RED -> R.string.curve_red
    CurveChannel.GREEN -> R.string.curve_green
    CurveChannel.BLUE -> R.string.curve_blue
}

// ====================================================================== Гістограма

/**
 * Гістограма RGB з яскравістю. Трикутники в кутах — індикатори провалів (зліва) і пересвіту (справа);
 * торкання вмикає підсвічування цих ділянок на фото.
 */
@Composable
fun HistogramView(
    hist: Histogram?,
    clipWarning: Boolean,
    onToggleClip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val desc = stringResource(R.string.cd_histogram)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B0C0E))
                .clickable(onClick = onToggleClip)
                .semantics { contentDescription = desc }
                .testTag("histogram"),
        ) {
            Canvas(Modifier.matchParentSize().padding(horizontal = 4.dp, vertical = 4.dp)) {
                val h = hist ?: return@Canvas
                val peak = h.peak.toFloat()
                drawBins(h.luma, peak, Color(0x66ECEDEF), fill = true)
                drawBins(h.red, peak, RedC.copy(alpha = 0.85f))
                drawBins(h.green, peak, GreenC.copy(alpha = 0.85f))
                drawBins(h.blue, peak, BlueC.copy(alpha = 0.85f))
            }
            val loOn = (hist?.shadowClip ?: 0f) > CLIP_THRESHOLD
            val hiOn = (hist?.highlightClip ?: 0f) > CLIP_THRESHOLD
            ClipMark(loOn, clipWarning, BlueC, Modifier.align(Alignment.TopStart))
            ClipMark(hiOn, clipWarning, RedC, Modifier.align(Alignment.TopEnd))
        }
        if (clipWarning && hist != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.clip_shadows, pct(hist.shadowClip)), style = MaterialTheme.typography.labelMedium, color = BlueC)
                Text(stringResource(R.string.clip_highlights, pct(hist.highlightClip)), style = MaterialTheme.typography.labelMedium, color = RedC)
            }
        }
    }
}

private const val CLIP_THRESHOLD = 0.001f

private fun pct(v: Float) = String.format(Locale.US, "%.1f%%", v * 100f)

@Composable
private fun ClipMark(active: Boolean, warningOn: Boolean, color: Color, modifier: Modifier) {
    Box(
        modifier
            .padding(5.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(
                when {
                    active -> color
                    warningOn -> color.copy(alpha = 0.35f)
                    else -> Color(0xFF3A3F47)
                }
            )
    )
}

private fun DrawScope.drawBins(bins: IntArray, peak: Float, color: Color, fill: Boolean = false) {
    val path = Path()
    val w = size.width
    val h = size.height
    path.moveTo(0f, h)
    for (i in 0 until 256) {
        val x = i / 255f * w
        val y = h - (bins[i] / peak).coerceAtMost(1f) * h
        path.lineTo(x, y)
    }
    path.lineTo(w, h)
    if (fill) {
        path.close()
        drawPath(path, color)
    } else {
        drawPath(path, color, style = Stroke(width = 1.2.dp.toPx()))
    }
}

// ====================================================================== Редактор кривої

/**
 * Квадратний редактор кривої.
 * Торкання кривої додає точку, перетягування рухає її, витягування точки за межі сітки — видаляє.
 */
@Composable
fun CurveEditor(
    curve: ToneCurve,
    channel: CurveChannel,
    histogram: Histogram?,
    onChange: (ToneCurve) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestCurve by rememberUpdatedState(curve)
    val latestChange by rememberUpdatedState(onChange)
    val latestDone by rememberUpdatedState(onDone)
    var active by remember { mutableIntStateOf(-1) }
    val lineColor = channel.color()
    val desc = stringResource(R.string.cd_curve_editor)

    Box(
        modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B0C0E))
            .semantics { contentDescription = desc }
            .testTag("curve_editor")
            .pointerInput(channel) {
                val pad = PAD.toPx()
                fun norm(o: Offset): Offset {
                    val side = size.width - 2 * pad
                    return Offset((o.x - pad) / side, 1f - (o.y - pad) / side)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val side = size.width - 2 * pad
                    val p = norm(down.position)
                    val hitR = 22.dp.toPx() / side
                    var c = latestCurve
                    val idx = CurveMath.hitPoint(c, p.x, p.y, hitR)
                    if (idx < 0) {
                        // Не на точці: жест віддаємо прокручуванню панелі. Лише коротке торкання біля кривої
                        // додає точку — так прокрутка «крізь» редактор не змінює криву.
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if (abs(CurveMath.evaluate(c, p.x) - p.y) > hitR * 1.6f) return@awaitEachGesture
                        val added = CurveMath.addPoint(c, p.x) ?: return@awaitEachGesture
                        up.consume()
                        latestChange(added.first)
                        latestDone()
                        return@awaitEachGesture
                    }
                    active = idx
                    down.consume()
                    var removed = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (ch.positionChange() != Offset.Zero) {
                            val q = norm(ch.position)
                            val interior = idx > 0 && idx < c.points.lastIndex
                            val outside = q.x < -0.12f || q.x > 1.12f || q.y < -0.12f || q.y > 1.12f
                            if (interior && outside) {
                                if (!removed) { removed = true; latestChange(CurveMath.removePoint(c, idx)) }
                            } else {
                                if (removed) removed = false
                                c = CurveMath.movePoint(c, idx, q.x, q.y)
                                latestChange(c)
                            }
                            ch.consume()
                        }
                    }
                    active = -1
                    latestDone()
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val pad = PAD.toPx()
            val side = size.width - 2 * pad
            fun pt(x: Float, y: Float) = Offset(pad + x * side, pad + (1f - y) * side)

            // Гістограма каналу на тлі
            histogram?.let { h ->
                val bins = when (channel) {
                    CurveChannel.RGB -> h.luma
                    CurveChannel.RED -> h.red
                    CurveChannel.GREEN -> h.green
                    CurveChannel.BLUE -> h.blue
                }
                val peak = h.peak.toFloat()
                val path = Path().apply {
                    moveTo(pad, pad + side)
                    for (i in 0 until 256) lineTo(pad + i / 255f * side, pad + side - (bins[i] / peak).coerceAtMost(1f) * side * 0.9f)
                    lineTo(pad + side, pad + side)
                    close()
                }
                drawPath(path, lineColor.copy(alpha = 0.10f))
            }
            // Сітка 4×4 і діагональ
            val grid = Color(0xFF2A2E35)
            for (i in 0..4) {
                val t = pad + side * i / 4f
                drawLine(grid, Offset(pad, t), Offset(pad + side, t), 1.dp.toPx())
                drawLine(grid, Offset(t, pad), Offset(t, pad + side), 1.dp.toPx())
            }
            drawLine(
                Color(0xFF3A3F47), pt(0f, 0f), pt(1f, 1f), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            // Крива
            val lut = CurveMath.lut(curve, 97)
            val path = Path().apply {
                lut.forEachIndexed { i, y ->
                    val o = pt(i / 96f, y)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            // Точки
            curve.points.forEachIndexed { i, p ->
                val o = pt(p.x, p.y)
                drawCircle(if (i == active) Accent else lineColor, radius = (if (i == active) 7f else 5.5f).dp.toPx(), center = o)
                drawCircle(Color(0xFF0B0C0E), radius = 2.5.dp.toPx(), center = o)
            }
        }
    }
}

private val PAD = 12.dp

/** Вибір каналу кривої + скидання. */
@Composable
fun CurveChannelRow(
    selected: CurveChannel,
    changed: (CurveChannel) -> Boolean,
    onSelect: (CurveChannel) -> Unit,
    onReset: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CurveChannel.entries.forEach { ch ->
                val sel = ch == selected
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (sel) Color(0xFF2A2E35) else Color.Transparent)
                        .clickable { onSelect(ch) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("curve_ch_${ch.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(ch.color()))
                    Text(
                        if (ch == CurveChannel.RGB) stringResource(ch.labelRes()) else stringResource(ch.labelRes()).take(1),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sel) MaterialTheme.colorScheme.onSurface else TextDim,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    if (changed(ch)) Box(Modifier.padding(start = 4.dp).size(4.dp).clip(CircleShape).background(Accent))
                }
            }
        }
        IconButton(onClick = onReset) {
            Icon(Icons.Filled.RestartAlt, contentDescription = stringResource(R.string.cd_reset_curve), tint = TextDim)
        }
    }
}
