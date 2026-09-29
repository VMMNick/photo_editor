package com.photoeditor.app.ui.panels

import kotlin.math.roundToInt
import com.photoeditor.app.ui.theme.TextDim
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.photoeditor.app.ui.labelRes
import com.photoeditor.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.ChipRow
import com.photoeditor.app.ui.components.SectionTitle
import java.util.Locale

/** Кадрування, поворот, вирівнювання горизонту, перспектива. */
@Composable
fun CropPanel(vm: EditorViewModel) {
    val g = vm.state.geometry
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp)) {
        ChipRow(AspectRatio.entries, g.aspect, { stringResource(it.labelRes()) }, { vm.setAspect(it) })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FilledTonalIconButton(onClick = { vm.rotate90(false) }) {
                Icon(Icons.Filled.Rotate90DegreesCcw, contentDescription = stringResource(R.string.cd_rotate_left))
            }
            FilledTonalIconButton(onClick = { vm.rotate90(true) }) {
                Icon(Icons.Filled.Rotate90DegreesCw, contentDescription = stringResource(R.string.cd_rotate_right))
            }
            FilledTonalIconButton(onClick = { vm.applyGeometry(vm.state.geometry.copy(flipH = !vm.state.geometry.flipH)) }) {
                Icon(Icons.Filled.Flip, contentDescription = stringResource(R.string.cd_flip_h))
            }
            FilledTonalIconButton(onClick = { vm.applyGeometry(vm.state.geometry.copy(flipV = !vm.state.geometry.flipV)) }) {
                Icon(Icons.Filled.Flip, contentDescription = stringResource(R.string.cd_flip_v), modifier = Modifier.rotate(90f))
            }
            FilledTonalIconButton(onClick = { vm.resetGeometry() }) {
                Icon(Icons.Filled.RestartAlt, contentDescription = stringResource(R.string.cd_reset_geometry))
            }
        }
        SectionTitle(stringResource(R.string.section_straighten))
        AdjustSlider(
            stringResource(R.string.adj_horizon), g.straighten,
            { v -> vm.previewGeometry(vm.state.geometry.copy(straighten = v)) }, vm::commit,
            range = -45f..45f, format = { String.format(Locale.US, "%+.1f°", it) },
        )
        SectionTitle(stringResource(R.string.section_perspective))
        AdjustSlider(stringResource(R.string.adj_vertical), g.perspectiveV, { v -> vm.previewGeometry(vm.state.geometry.copy(perspectiveV = v)) }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_horizontal), g.perspectiveH, { v -> vm.previewGeometry(vm.state.geometry.copy(perspectiveH = v)) }, vm::commit)

        SectionTitle(stringResource(R.string.section_lens))
        AdjustSlider(stringResource(R.string.adj_distortion), g.distortion, { v -> vm.previewGeometry(vm.state.geometry.copy(distortion = v)) }, vm::commit)
        val a = vm.state.adjust
        AdjustSlider(stringResource(R.string.adj_chromatic), a.chromatic, { v -> vm.previewAdjust(vm.state.adjust.copy(chromatic = v)) }, vm::commit)
        AdjustSlider(
            stringResource(R.string.adj_lens_vignette), a.lensVignette, { v -> vm.previewAdjust(vm.state.adjust.copy(lensVignette = v)) }, vm::commit,
            range = 0f..1f, format = { "${(it * 100).roundToInt()}" },
        )
        Text(
            stringResource(R.string.lens_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
