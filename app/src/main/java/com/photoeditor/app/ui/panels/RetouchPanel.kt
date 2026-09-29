package com.photoeditor.app.ui.panels

import com.photoeditor.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.RetouchMode
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.ChipRow
import com.photoeditor.app.ui.theme.TextDim
import kotlin.math.roundToInt

@Composable
fun RetouchPanel(vm: EditorViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp)) {
        ChipRow(RetouchMode.entries, vm.retouchMode, { stringResource(it.labelRes) }, { vm.retouchMode = it })

        val hint = when (vm.retouchMode) {
            RetouchMode.HEAL -> stringResource(R.string.hint_heal)
            RetouchMode.CLONE -> stringResource(if (vm.pickingCloneSource) R.string.hint_clone_pick else R.string.hint_clone_paint)
            RetouchMode.ERASE -> stringResource(R.string.hint_erase)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        AdjustSlider(
            stringResource(R.string.brush_size), vm.retouchBrushSizeDp, { vm.retouchBrushSizeDp = it }, {},
            range = 6f..120f, default = 28f, format = { "${it.roundToInt()}" },
        )

        when (vm.retouchMode) {
            RetouchMode.CLONE -> Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { vm.pickingCloneSource = true }) {
                    Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (vm.cloneSource == null) R.string.clone_set_source else R.string.clone_change_source))
                }
            }
            RetouchMode.ERASE -> {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !vm.eraseBrushRemove, onClick = { vm.eraseBrushRemove = false }, label = { Text(stringResource(R.string.erase_add)) })
                    FilterChip(selected = vm.eraseBrushRemove, onClick = { vm.eraseBrushRemove = true }, label = { Text(stringResource(R.string.erase_remove)) })
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = vm::commitErase, enabled = vm.eraseSelection != null && vm.busy == null) {
                        Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.erase_apply))
                    }
                    OutlinedButton(onClick = vm::clearEraseSelection, enabled = vm.eraseSelection != null) {
                        Icon(Icons.Filled.Clear, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.erase_clear))
                    }
                }
            }
            RetouchMode.HEAL -> Unit
        }

        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedButton(onClick = vm::undoLastRetouch, enabled = vm.state.retouch.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.retouch_undo_last, vm.state.retouch.size))
            }
        }
    }
}
