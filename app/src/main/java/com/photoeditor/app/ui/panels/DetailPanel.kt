package com.photoeditor.app.ui.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photoeditor.app.R
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.SectionTitle
import com.photoeditor.app.ui.theme.TextDim
import kotlin.math.roundToInt

/** Деталізація: чіткість, димка, різкість, шумозаглушення. */
@Composable
fun DetailPanel(vm: EditorViewModel) {
    val a = vm.state.adjust
    fun set(f: (Adjustments) -> Adjustments) = vm.previewAdjust(f(vm.state.adjust))
    val unsigned = { v: Float -> "${(v * 100).roundToInt()}" }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionTitle(stringResource(R.string.section_presence))
        AdjustSlider(stringResource(R.string.adj_clarity), a.clarity, { v -> set { it.copy(clarity = v) } }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_dehaze), a.dehaze, { v -> set { it.copy(dehaze = v) } }, vm::commit)

        SectionTitle(stringResource(R.string.section_sharpening))
        AdjustSlider(stringResource(R.string.adj_sharpen), a.sharpen, { v -> set { it.copy(sharpen = v) } }, vm::commit, range = 0f..1f, format = unsigned)

        SectionTitle(stringResource(R.string.section_noise))
        AdjustSlider(stringResource(R.string.adj_noise_luma), a.noise, { v -> set { it.copy(noise = v) } }, vm::commit, range = 0f..1f, format = unsigned)
        AdjustSlider(stringResource(R.string.adj_noise_color), a.colorNoise, { v -> set { it.copy(colorNoise = v) } }, vm::commit, range = 0f..1f, format = unsigned)

        Text(
            stringResource(R.string.detail_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
