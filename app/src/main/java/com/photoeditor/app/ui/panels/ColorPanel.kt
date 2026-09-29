package com.photoeditor.app.ui.panels

import androidx.compose.ui.res.stringResource
import com.photoeditor.app.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.photoeditor.app.gl.RenderParams
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.ColorGrading
import com.photoeditor.app.model.GradeWheel
import com.photoeditor.app.model.HslBand
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.labelRes
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.ChipRow
import com.photoeditor.app.ui.components.ColorDot
import com.photoeditor.app.ui.components.SectionTitle
import com.photoeditor.app.ui.components.hueBrush
import com.photoeditor.app.ui.components.temperatureBrush
import com.photoeditor.app.ui.components.tintBrush
import kotlin.math.roundToInt

private enum class ColorTab(val labelRes: Int) { BASIC(R.string.color_tab_basic), HSL(R.string.color_tab_hsl), GRADING(R.string.color_tab_grading) }
private enum class Zone(val labelRes: Int) { SHADOWS(R.string.zone_shadows), MIDTONES(R.string.zone_midtones), HIGHLIGHTS(R.string.zone_highlights) }

@Composable
fun ColorPanel(vm: EditorViewModel) {
    var tab by rememberSaveable { mutableStateOf(ColorTab.BASIC) }
    val a = vm.state.adjust
    fun set(f: (Adjustments) -> Adjustments) = vm.previewAdjust(f(vm.state.adjust))

    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp)) {
        ChipRow(ColorTab.entries, tab, { stringResource(it.labelRes) }, { tab = it })
        when (tab) {
            ColorTab.BASIC -> {
                SectionTitle(stringResource(R.string.section_white_balance))
                AdjustSlider(stringResource(R.string.adj_temperature), a.temperature, { v -> set { it.copy(temperature = v) } }, vm::commit, trackBrush = temperatureBrush)
                AdjustSlider(stringResource(R.string.adj_tint), a.tint, { v -> set { it.copy(tint = v) } }, vm::commit, trackBrush = tintBrush)
                SectionTitle(stringResource(R.string.section_saturation))
                AdjustSlider(stringResource(R.string.adj_vibrance), a.vibrance, { v -> set { it.copy(vibrance = v) } }, vm::commit)
                AdjustSlider(stringResource(R.string.adj_saturation), a.saturation, { v -> set { it.copy(saturation = v) } }, vm::commit)
            }
            ColorTab.HSL -> HslSection(vm)
            ColorTab.GRADING -> GradingSection(vm)
        }
    }
}

@Composable
private fun HslSection(vm: EditorViewModel) {
    var band by rememberSaveable { mutableStateOf(HslBand.RED) }
    val h = vm.state.adjust.hsl[band.ordinal]
    fun setBand(f: (com.photoeditor.app.model.HslAdjust) -> com.photoeditor.app.model.HslAdjust) {
        val cur = vm.state.adjust
        vm.previewAdjust(cur.withHsl(band, f(cur.hsl[band.ordinal])))
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HslBand.entries.forEach { b -> ColorDot(Color(b.argb), b == band) { band = b } }
    }
    SectionTitle(stringResource(band.labelRes()))
    val c = Color(band.argb)
    AdjustSlider(stringResource(R.string.adj_hue), h.hue, { v -> setBand { it.copy(hue = v) } }, vm::commit,
        trackBrush = Brush.horizontalGradient(listOf(shiftHue(band.centerDeg - 30f), c, shiftHue(band.centerDeg + 30f))))
    AdjustSlider(stringResource(R.string.adj_saturation), h.sat, { v -> setBand { it.copy(sat = v) } }, vm::commit,
        trackBrush = Brush.horizontalGradient(listOf(Color.Gray, c)))
    AdjustSlider(stringResource(R.string.adj_luminance), h.lum, { v -> setBand { it.copy(lum = v) } }, vm::commit,
        trackBrush = Brush.horizontalGradient(listOf(Color.Black, c, Color.White)))
}

private fun shiftHue(deg: Float): Color {
    val rgb = RenderParams.hsvToRgb(((deg % 360f) + 360f) % 360f / 360f, 0.85f, 0.95f)
    return Color(rgb[0], rgb[1], rgb[2])
}

@Composable
private fun GradingSection(vm: EditorViewModel) {
    var zone by rememberSaveable { mutableStateOf(Zone.SHADOWS) }
    val g = vm.state.adjust.grading
    val w = when (zone) { Zone.SHADOWS -> g.shadows; Zone.MIDTONES -> g.midtones; Zone.HIGHLIGHTS -> g.highlights }

    fun setGrading(f: (ColorGrading) -> ColorGrading) {
        val cur = vm.state.adjust
        vm.previewAdjust(cur.copy(grading = f(cur.grading)))
    }
    fun setWheel(f: (GradeWheel) -> GradeWheel) = setGrading { gr ->
        when (zone) {
            Zone.SHADOWS -> gr.copy(shadows = f(gr.shadows))
            Zone.MIDTONES -> gr.copy(midtones = f(gr.midtones))
            Zone.HIGHLIGHTS -> gr.copy(highlights = f(gr.highlights))
        }
    }

    ChipRow(Zone.entries, zone, { stringResource(it.labelRes) }, { zone = it }, Modifier.padding(top = 8.dp))
    AdjustSlider(stringResource(R.string.adj_hue), w.hue, { v -> setWheel { it.copy(hue = v) } }, vm::commit,
        range = 0f..360f, default = 0f, format = { "${it.roundToInt()}°" }, trackBrush = hueBrush)
    AdjustSlider(stringResource(R.string.adj_saturation), w.sat, { v -> setWheel { it.copy(sat = v) } }, vm::commit,
        range = 0f..1f, format = { "${(it * 100).roundToInt()}" },
        trackBrush = Brush.horizontalGradient(listOf(Color.Gray, shiftHue(w.hue))))
    AdjustSlider(stringResource(R.string.adj_luminance), w.lum, { v -> setWheel { it.copy(lum = v) } }, vm::commit)
    SectionTitle(stringResource(R.string.section_general))
    AdjustSlider(stringResource(R.string.adj_blending), g.blending, { v -> setGrading { it.copy(blending = v) } }, vm::commit,
        range = 0f..1f, default = 0.5f, format = { "${(it * 100).roundToInt()}" })
    AdjustSlider(stringResource(R.string.adj_balance), g.balance, { v -> setGrading { it.copy(balance = v) } }, vm::commit)
}
