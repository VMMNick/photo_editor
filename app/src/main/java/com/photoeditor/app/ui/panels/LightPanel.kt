package com.photoeditor.app.ui.panels

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import com.photoeditor.app.ui.theme.TextDim
import com.photoeditor.app.ui.components.HistogramView
import com.photoeditor.app.ui.components.CurveEditor
import com.photoeditor.app.ui.components.CurveChannelRow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import com.photoeditor.app.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.SectionTitle
import com.photoeditor.app.ui.components.evFormat
import com.photoeditor.app.ui.theme.Accent

/** Світло і тон: експозиція, контраст, яскравість, світлі ділянки, тіні, білі, чорні. */
@Composable
fun LightPanel(vm: EditorViewModel) {
    val a = vm.state.adjust
    fun set(f: (Adjustments) -> Adjustments) = vm.previewAdjust(f(vm.state.adjust))
    // Висота видимої частини панелі: редактор кривої має вміщатися цілком, інакше його не відредагуєш
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val curveSide = minOf(maxWidth - 32.dp, maxHeight - 24.dp, 360.dp).coerceAtLeast(160.dp)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            HistogramView(
                vm.histogram, vm.clipWarning, onToggleClip = { vm.clipWarning = !vm.clipWarning },
                modifier = Modifier.padding(top = 10.dp),
            )
            vm.info?.let { info ->
                if (info.isRaw) {
                    Text(
                        stringResource(if (info.usedEmbeddedPreview) R.string.light_raw_embedded else R.string.light_raw_dng),
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
            SectionTitle(stringResource(R.string.section_light))
            AdjustSlider(stringResource(R.string.adj_exposure), a.exposure, { v -> set { it.copy(exposure = v) } }, vm::commit, range = -4f..4f, format = ::evFormat)
            AdjustSlider(stringResource(R.string.adj_contrast), a.contrast, { v -> set { it.copy(contrast = v) } }, vm::commit)
            AdjustSlider(stringResource(R.string.adj_brightness), a.brightness, { v -> set { it.copy(brightness = v) } }, vm::commit)
            SectionTitle(stringResource(R.string.section_tone))
            AdjustSlider(stringResource(R.string.adj_highlights), a.highlights, { v -> set { it.copy(highlights = v) } }, vm::commit)
            AdjustSlider(stringResource(R.string.adj_shadows), a.shadows, { v -> set { it.copy(shadows = v) } }, vm::commit)
            AdjustSlider(stringResource(R.string.adj_whites), a.whites, { v -> set { it.copy(whites = v) } }, vm::commit)
            AdjustSlider(stringResource(R.string.adj_blacks), a.blacks, { v -> set { it.copy(blacks = v) } }, vm::commit)

            SectionTitle(stringResource(R.string.section_curve))
            val ch = vm.curveChannel
            CurveChannelRow(
                selected = ch,
                changed = { !a.curves[it].isIdentity },
                onSelect = { vm.curveChannel = it },
                onReset = { vm.resetCurve(ch) },
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                CurveEditor(
                    curve = a.curves[ch],
                    channel = ch,
                    histogram = vm.histogram,
                    onChange = { vm.previewCurve(ch, it) },
                    onDone = vm::commit,
                    modifier = Modifier.size(curveSide),
                )
            }
            Text(
                stringResource(R.string.curve_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
