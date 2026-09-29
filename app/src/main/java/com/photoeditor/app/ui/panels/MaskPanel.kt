package com.photoeditor.app.ui.panels

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import com.photoeditor.app.ui.components.ChipRow
import com.photoeditor.app.model.RangeMode
import com.photoeditor.app.model.MaskRange
import com.photoeditor.app.ui.maskName
import com.photoeditor.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.photoeditor.app.model.LocalAdjust
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.SectionTitle
import com.photoeditor.app.ui.components.evFormat
import com.photoeditor.app.ui.components.temperatureBrush
import com.photoeditor.app.ui.components.tintBrush
import com.photoeditor.app.ui.theme.Accent
import com.photoeditor.app.ui.theme.TextDim
import kotlin.math.roundToInt

/** Локальні корекції: градієнт (небо), радіальна (обличчя), пензель, AI-об'єкт і AI-фон. */
@Composable
fun MaskPanel(vm: EditorViewModel) {
    val masks = vm.state.masks
    val sel = masks.getOrNull(vm.selectedMask)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp)) {
        // Існуючі маски + додавання
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            masks.forEachIndexed { i, m ->
                FilterChip(
                    selected = i == vm.selectedMask,
                    onClick = { vm.selectedMask = i },
                    label = { Text(stringResource(R.string.mask_chip, i + 1, maskName(m))) },
                    leadingIcon = { Icon(iconFor(m.kind), null, Modifier.size(18.dp)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent.copy(alpha = 0.18f), selectedLabelColor = Accent),
                )
            }
        }
        SectionTitle(stringResource(R.string.section_add_mask))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddChip(stringResource(R.string.mask_linear), Icons.Filled.Gradient) { vm.addMask(MaskKind.LINEAR) }
            AddChip(stringResource(R.string.mask_radial), Icons.Filled.RadioButtonChecked) { vm.addMask(MaskKind.RADIAL) }
            AddChip(stringResource(R.string.mask_brush), Icons.Filled.Brush) { vm.addMask(MaskKind.BRUSH) }
            AddChip(stringResource(R.string.mask_subject), Icons.Filled.Person) { vm.addMask(MaskKind.SUBJECT) }
            AddChip(stringResource(R.string.mask_background), Icons.Filled.Landscape) { vm.addMask(MaskKind.SUBJECT, invert = true) }
        }

        if (sel == null) {
            Text(
                stringResource(R.string.masks_hint),
                style = MaterialTheme.typography.bodySmall, color = TextDim,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            SelectedMaskControls(vm, sel)
        }
    }
}

@Composable
private fun SelectedMaskControls(vm: EditorViewModel, sel: Mask) {
    Column {
        val idx = vm.selectedMask
        fun upd(m: Mask) = vm.updateMask(idx, m, commit = false)
        fun cur() = vm.state.masks.getOrNull(idx) ?: sel
        fun adj(f: (LocalAdjust) -> LocalAdjust) = upd(cur().copy(adjust = f(cur().adjust)))

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(maskName(sel), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
            IconButton(onClick = { vm.showMaskOverlay = !vm.showMaskOverlay }) {
                Icon(if (vm.showMaskOverlay) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, stringResource(R.string.cd_show_mask))
            }
            IconButton(onClick = { vm.updateMask(idx, cur().copy(invert = !cur().invert), commit = true) }) {
                Icon(Icons.Filled.InvertColors, stringResource(R.string.cd_invert), tint = if (sel.invert) Accent else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.duplicateMaskInverted(idx) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.cd_duplicate_inverted)) }
            IconButton(onClick = { vm.deleteMask(idx) }) { Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete)) }
        }

        when (sel.kind) {
            MaskKind.RADIAL -> AdjustSlider(stringResource(R.string.mask_feather), sel.feather, { v -> upd(cur().copy(feather = v)) }, vm::commit,
                range = 0f..1f, default = 0.5f, format = { "${(it * 100).roundToInt()}" })
            MaskKind.BRUSH, MaskKind.SUBJECT -> {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !vm.maskBrushErase, onClick = { vm.maskBrushErase = false }, label = { Text(stringResource(R.string.brush_paint)) })
                    FilterChip(selected = vm.maskBrushErase, onClick = { vm.maskBrushErase = true }, label = { Text(stringResource(R.string.brush_erase)) })
                }
                AdjustSlider(stringResource(R.string.brush_size), vm.maskBrushSizeDp, { vm.maskBrushSizeDp = it }, {},
                    range = 8f..160f, default = 40f, format = { "${it.roundToInt()}" })
                AdjustSlider(stringResource(R.string.brush_hardness), vm.maskBrushHardness, { vm.maskBrushHardness = it }, {},
                    range = 0f..1f, default = 0.3f, format = { "${(it * 100).roundToInt()}" })
            }
            MaskKind.LINEAR -> Text(
                stringResource(R.string.mask_linear_hint),
                style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        MaskRangeControls(vm, idx, sel, ::cur)

        SectionTitle(stringResource(R.string.section_mask_adjust))
        val a = sel.adjust
        AdjustSlider(stringResource(R.string.adj_exposure), a.exposure, { v -> adj { it.copy(exposure = v) } }, vm::commit, range = -2f..2f, format = ::evFormat)
        AdjustSlider(stringResource(R.string.adj_contrast), a.contrast, { v -> adj { it.copy(contrast = v) } }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_highlights), a.highlights, { v -> adj { it.copy(highlights = v) } }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_shadows), a.shadows, { v -> adj { it.copy(shadows = v) } }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_saturation), a.saturation, { v -> adj { it.copy(saturation = v) } }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_temperature), a.temperature, { v -> adj { it.copy(temperature = v) } }, vm::commit, trackBrush = temperatureBrush)
        AdjustSlider(stringResource(R.string.adj_tint), a.tint, { v -> adj { it.copy(tint = v) } }, vm::commit, trackBrush = tintBrush)
    }
}

/** Уточнення маски за яскравістю або відтінком джерела. */
@Composable
private fun MaskRangeControls(vm: EditorViewModel, idx: Int, sel: Mask, cur: () -> Mask) {
    val r = sel.range
    fun upd(f: (MaskRange) -> MaskRange, commit: Boolean = false) = vm.updateMask(idx, cur().copy(range = f(cur().range)), commit)
    SectionTitle(stringResource(R.string.section_mask_range))
    ChipRow(
        RangeMode.entries, r.mode,
        {
            stringResource(
                when (it) {
                    RangeMode.NONE -> R.string.range_none
                    RangeMode.LUMINANCE -> R.string.range_luma
                    RangeMode.COLOR -> R.string.range_color
                }
            )
        },
        { m -> upd({ it.copy(mode = m) }, commit = true) },
    )
    val pct = { v: Float -> "${(v * 100).roundToInt()}" }
    when (r.mode) {
        RangeMode.NONE -> Unit
        RangeMode.LUMINANCE -> {
            AdjustSlider(stringResource(R.string.range_from), r.lo, { v -> upd({ it.copy(lo = minOf(v, it.hi - 0.02f)) }) }, vm::commit,
                range = 0f..1f, default = 0f, format = pct, trackBrush = lumaBrush)
            AdjustSlider(stringResource(R.string.range_to), r.hi, { v -> upd({ it.copy(hi = maxOf(v, it.lo + 0.02f)) }) }, vm::commit,
                range = 0f..1f, default = 1f, format = pct, trackBrush = lumaBrush)
        }
        RangeMode.COLOR -> {
            AdjustSlider(stringResource(R.string.range_hue), r.hue, { v -> upd({ it.copy(hue = v) }) }, vm::commit,
                range = 0f..360f, default = 30f, format = { "${it.roundToInt()}°" }, trackBrush = hueBrush)
            AdjustSlider(stringResource(R.string.range_width), r.hueWidth, { v -> upd({ it.copy(hueWidth = v) }) }, vm::commit,
                range = 5f..90f, default = 30f, format = { "${it.roundToInt()}°" })
        }
    }
    if (r.mode != RangeMode.NONE) {
        AdjustSlider(stringResource(R.string.range_smooth), r.smooth, { v -> upd({ it.copy(smooth = v) }) }, vm::commit,
            range = 0f..1f, default = 0.3f, format = pct)
        Text(
            stringResource(if (r.mode == RangeMode.LUMINANCE) R.string.range_hint_luma else R.string.range_hint_color),
            style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private val lumaBrush = Brush.horizontalGradient(listOf(Color.Black, Color.White))
private val hueBrush = Brush.horizontalGradient(
    (0..6).map { Color.hsv((it * 60f) % 360f, 0.85f, 0.95f) }
)

@Composable
private fun AddChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
    )
}

private fun iconFor(k: MaskKind): ImageVector = when (k) {
    MaskKind.LINEAR -> Icons.Filled.Gradient
    MaskKind.RADIAL -> Icons.Filled.RadioButtonChecked
    MaskKind.BRUSH -> Icons.Filled.Brush
    MaskKind.SUBJECT -> Icons.Filled.Person
}
