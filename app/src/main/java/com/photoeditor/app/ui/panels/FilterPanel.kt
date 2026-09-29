package com.photoeditor.app.ui.panels

import androidx.compose.ui.platform.testTag
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Delete
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.photoeditor.app.ui.presetName
import com.photoeditor.app.ui.categoryLabel
import com.photoeditor.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photoeditor.app.model.Preset
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.ChipRow
import com.photoeditor.app.ui.components.SectionTitle
import com.photoeditor.app.ui.theme.Accent
import com.photoeditor.app.ui.theme.PanelHigh
import com.photoeditor.app.ui.theme.TextDim
import kotlin.math.roundToInt

/** Імпортовані 3D LUT: вибір, інтенсивність, імпорт і видалення. */
@Composable
private fun LutSection(vm: EditorViewModel) {
    val importLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLut) }
    val current = vm.state.adjust.lut
    SectionTitle(stringResource(R.string.section_lut))
    val ids: List<String?> = listOf(null) + vm.luts.map { it.id }
    ChipRow(
        ids, current,
        { id -> if (id == null) stringResource(R.string.lut_none) else vm.luts.firstOrNull { it.id == id }?.title ?: "LUT" },
        { id -> vm.applyAdjust(vm.state.adjust.copy(lut = id, lutAmount = if (id == null) 1f else vm.state.adjust.lutAmount)) },
    )
    if (current != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdjustSlider(
                stringResource(R.string.lut_amount), vm.state.adjust.lutAmount,
                { v -> vm.previewAdjust(vm.state.adjust.copy(lutAmount = v)) }, vm::commit,
                range = 0f..1f, default = 1f, format = { "${(it * 100).roundToInt()}%" }, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.deleteLut(current) }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete_lut), tint = TextDim)
            }
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = { importLut.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("lut_import")) {
            Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp))
            Text("  " + stringResource(R.string.lut_import))
        }
    }
    Text(
        stringResource(R.string.lut_hint),
        style = MaterialTheme.typography.bodySmall, color = TextDim,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun FilterPanel(vm: EditorViewModel) {
    val categories = remember(vm.userPresets) { listOf(ALL) + vm.allPresets.map { it.category }.distinct() }
    var category by rememberSaveable { mutableStateOf(ALL) }
    var saveDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Preset?>(null) }
    val list = vm.allPresets.filter { category == ALL || it.category == category }
    val a = vm.state.adjust

    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp)) {
        ChipRow(categories, category, { if (it == ALL) stringResource(R.string.filter_all) else categoryLabel(it) }, { category = it })
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PresetCard(name = stringResource(R.string.filter_none), selected = vm.state.presetId == null, thumb = null, onClick = { vm.selectPreset(null) })
            }
            items(list, key = { it.id }) { p ->
                PresetCard(
                    name = presetName(p),
                    selected = vm.state.presetId == p.id,
                    thumb = vm.thumbnails[p.id],
                    onClick = { vm.selectPreset(p) },
                    onLongClick = if (!p.builtIn) ({ deleteTarget = p }) else null,
                )
            }
        }

        if (vm.state.presetId != null) {
            AdjustSlider(
                stringResource(R.string.filter_amount), vm.state.presetAmount, vm::previewPresetAmount, vm::commit,
                range = 0f..1f, default = 1f, format = { "${(it * 100).roundToInt()}%" },
            )
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(onClick = { saveDialog = true }) {
                Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                Text("  " + stringResource(R.string.filter_save_preset))
            }
        }

        LutSection(vm)

        SectionTitle(stringResource(R.string.section_effects))
        fun set(f: (com.photoeditor.app.model.Adjustments) -> com.photoeditor.app.model.Adjustments) = vm.previewAdjust(f(vm.state.adjust))
        AdjustSlider(stringResource(R.string.adj_vignette), a.vignette, { v -> set { it.copy(vignette = v) } }, vm::commit)
        AdjustSlider(stringResource(R.string.adj_grain), a.grain, { v -> set { it.copy(grain = v) } }, vm::commit, range = 0f..1f, format = { "${(it * 100).roundToInt()}" })
        AdjustSlider(stringResource(R.string.adj_fade), a.fade, { v -> set { it.copy(fade = v) } }, vm::commit, range = 0f..1f, format = { "${(it * 100).roundToInt()}" })
    }

    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text(stringResource(R.string.dlg_new_preset)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.preset_name_label)) })
            },
            confirmButton = { TextButton(onClick = { vm.saveUserPreset(name); saveDialog = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.dlg_delete_preset, p.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteUserPreset(p); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Службовий ключ «усі категорії». */
private const val ALL = "all"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(
    name: String,
    selected: Boolean,
    thumb: android.graphics.Bitmap?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.width(76.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .size(76.dp)
                .clip(shape)
                .background(PanelHigh)
                .then(if (selected) Modifier.border(2.dp, Accent, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(thumb.asImageBitmap(), contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(76.dp))
            } else {
                Icon(Icons.Filled.Block, null, tint = TextDim)
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
