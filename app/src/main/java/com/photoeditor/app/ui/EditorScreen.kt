package com.photoeditor.app.ui

import com.photoeditor.app.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.photoeditor.app.ui.theme.PanelHigh
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photoeditor.app.image.ExportFormat
import com.photoeditor.app.image.ExportSize
import com.photoeditor.app.ui.components.AdjustSlider
import com.photoeditor.app.ui.components.ChipRow
import com.photoeditor.app.ui.components.SectionTitle
import com.photoeditor.app.ui.panels.ColorPanel
import com.photoeditor.app.ui.panels.DetailPanel
import com.photoeditor.app.ui.panels.CropPanel
import com.photoeditor.app.ui.panels.FilterPanel
import com.photoeditor.app.ui.panels.LightPanel
import com.photoeditor.app.ui.panels.MaskPanel
import com.photoeditor.app.ui.panels.RetouchPanel
import com.photoeditor.app.ui.theme.Accent
import com.photoeditor.app.ui.theme.Bg
import com.photoeditor.app.ui.theme.Panel
import com.photoeditor.app.ui.theme.TextDim
import kotlin.math.roundToInt

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var exportDialog by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val shareLabel = stringResource(R.string.action_share)
    val shareTitle = stringResource(R.string.share_chooser)
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        val shareUri = vm.messageShareUri
        if (shareUri != null) {
            // Після експорту — одразу можна поділитися результатом
            val r = snackbar.showSnackbar(m, actionLabel = shareLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.message = null
            vm.messageShareUri = null
            if (r == SnackbarResult.ActionPerformed) shareImage(context, shareUri, shareTitle)
        } else {
            snackbar.showSnackbar(m)
            vm.message = null
        }
    }

    BackHandler {
        if (vm.canUndo) confirmExit = true else vm.close()
    }

    // Пакетна обробка: вибір кількох фото → діалог експорту → скопійований вигляд застосовується до кожного
    var batchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val pickBatch = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(BATCH_MAX)) { uris ->
        if (uris.isNotEmpty()) batchUris = uris
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Bg)
            // safeDrawing: статус-бар, навігація (в ландшафті вона буває збоку) і виріз камери
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val layout = AdaptiveLayout.forEditor(maxWidth.value, maxHeight.value)
        val topBar: @Composable () -> Unit = {
            TopBar(
                vm,
                compact = layout.compactTopBar,
                onBack = { if (vm.canUndo) confirmExit = true else vm.close() },
                onExport = { exportDialog = true },
                onReset = { confirmReset = true },
                onBatch = { pickBatch.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }

        when (layout.placement) {
            PanelPlacement.BOTTOM -> Column(Modifier.fillMaxSize().testTag("layout_bottom")) {
                topBar()
                PreviewWithTips(vm, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(layout.panelSizeDp.dp).background(Panel)) { ToolPanel(vm) }
                ToolBar(vm)
            }
            PanelPlacement.SIDE -> Row(Modifier.fillMaxSize().testTag("layout_side")) {
                ToolRail(vm)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    topBar()
                    PreviewWithTips(vm, Modifier.weight(1f).fillMaxWidth())
                }
                Box(Modifier.width(layout.panelSizeDp.dp).fillMaxHeight().background(Panel).testTag("panel_side")) {
                    ToolPanel(vm)
                }
            }
        }

        // Повідомлення — над панеллю (портрет) або внизу під фото (ландшафт)
        val snackbarBottom = when (layout.placement) {
            PanelPlacement.BOTTOM -> layout.panelSizeDp.dp + 72.dp
            PanelPlacement.SIDE -> 16.dp
        }
        val snackbarEnd = if (layout.placement == PanelPlacement.SIDE) layout.panelSizeDp.dp else 0.dp
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = snackbarBottom, end = snackbarEnd)
                .widthIn(max = 560.dp),
        )

        vm.busy?.let { text ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    // Перехоплюємо дотики, поки триває операція
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation() } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(16.dp))
                    Text(text, color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (exportDialog) {
        ExportDialog(
            onDismiss = { exportDialog = false },
            onExport = { size, format, quality ->
                exportDialog = false
                vm.export(size, format, quality)
            },
        )
    }
    if (batchUris.isNotEmpty()) {
        ExportDialog(
            title = stringResource(R.string.batch_title, batchUris.size),
            onDismiss = { batchUris = emptyList() },
            onExport = { size, format, quality ->
                val uris = batchUris
                batchUris = emptyList()
                vm.batchExport(uris, size, format, quality)
            },
        )
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.dlg_close_title)) },
            text = { Text(stringResource(R.string.dlg_close_text)) },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.close() }) { Text(stringResource(R.string.action_close)) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.dlg_reset_title)) },
            text = { Text(stringResource(R.string.dlg_reset_text)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetAll() }) { Text(stringResource(R.string.action_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun shareImage(context: Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = context.contentResolver.getType(uri) ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun TopBar(
    vm: EditorViewModel,
    compact: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onBatch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(if (compact) 48.dp else 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
        Column(Modifier.weight(1f)) {
            Text(vm.info?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            vm.info?.takeIf { !compact }?.let {
                Text(
                    stringResource(if (it.isRaw) R.string.image_size_raw else R.string.image_size, it.fullWidth, it.fullHeight),
                    style = MaterialTheme.typography.labelMedium, color = TextDim,
                )
            }
        }
        IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.cd_undo)) }
        IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.cd_redo)) }
        // Порівняння: коротке торкання вмикає шторку «до/після», утримання — показує оригінал, поки палець на кнопці
        val holdMs = LocalViewConfiguration.current.longPressTimeoutMillis
        Box(
            Modifier
                .size(48.dp)
                .testTag("compare_button")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val up = withTimeoutOrNull(holdMs) { waitForUpOrCancellation() }
                        if (up != null) {
                            vm.splitCompare = !vm.splitCompare
                        } else {
                            vm.compare = true
                            waitForUpOrCancellation()
                            vm.compare = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Compare, stringResource(R.string.cd_compare),
                tint = if (vm.compare || vm.splitCompare) Accent else MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onExport) { Icon(Icons.Filled.SaveAlt, stringResource(R.string.cd_export), tint = Accent) }
        MoreMenu(vm, onBatch, onReset)
    }
}

/** Меню «Більше»: копіювання / вставка вигляду, пакетна обробка, скидання всіх правок. */
@Composable
private fun MoreMenu(vm: EditorViewModel, onBatch: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("more_menu")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_copy_look)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { open = false; vm.copyLook() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_paste_look)) },
                leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                enabled = vm.copiedLook != null,
                onClick = { open = false; vm.pasteLook() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_batch)) },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                enabled = vm.copiedLook != null,
                onClick = { open = false; onBatch() },
            )
            Text(
                stringResource(R.string.menu_look_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cd_reset_all)) },
                leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                onClick = { open = false; onReset() },
            )
        }
    }
}

private const val BATCH_MAX = 50

@Composable
private fun ToolPanel(vm: EditorViewModel) {
    when (vm.tool) {
        Tool.CROP -> CropPanel(vm)
        Tool.LIGHT -> LightPanel(vm)
        Tool.COLOR -> ColorPanel(vm)
        Tool.DETAIL -> DetailPanel(vm)
        Tool.RETOUCH -> RetouchPanel(vm)
        Tool.MASKS -> MaskPanel(vm)
        Tool.FILTERS -> FilterPanel(vm)
    }
}

/** Один інструмент: іконка + підпис. Ширина гнучка, щоб 7 пунктів влазили навіть на 320 dp. */
@Composable
private fun ToolItem(vm: EditorViewModel, t: Tool, modifier: Modifier) {
    val selected = vm.tool == t
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { vm.commit(); vm.tool = t }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(iconFor(t), stringResource(t.labelRes), tint = if (selected) Accent else TextDim)
        Text(
            stringResource(t.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Accent else TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolBar(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 4.dp).testTag("toolbar_bottom"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tool.entries.forEach { t -> ToolItem(vm, t, Modifier.weight(1f)) }
    }
}

/** Вертикальна рейка інструментів для ландшафту й планшетів; прокручується на дуже низьких екранах. */
@Composable
private fun ToolRail(vm: EditorViewModel) {
    Column(
        Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(Panel)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag("toolbar_rail"),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Tool.entries.forEach { t -> ToolItem(vm, t, Modifier.fillMaxWidth()) }
    }
}

private fun iconFor(t: Tool): ImageVector = when (t) {
    Tool.CROP -> Icons.Filled.Crop
    Tool.LIGHT -> Icons.Filled.WbSunny
    Tool.COLOR -> Icons.Filled.Palette
    Tool.DETAIL -> Icons.Filled.CenterFocusStrong
    Tool.RETOUCH -> Icons.Filled.Healing
    Tool.MASKS -> Icons.Filled.Layers
    Tool.FILTERS -> Icons.Filled.AutoAwesome
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (ExportSize, ExportFormat, Int) -> Unit,
    title: String = stringResource(R.string.export_title),
) {
    var size by remember { mutableStateOf(ExportSize.FULL) }
    var format by remember { mutableStateOf(ExportFormat.JPEG) }
    var quality by remember { mutableFloatStateOf(92f) }
    var pendingPermission by remember { mutableStateOf(false) }

    // Android 8–9: для запису в спільну галерею потрібен дозвіл
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingPermission) onExport(size, format, quality.roundToInt())
        pendingPermission = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Прокрутка — щоб діалог вміщався в ландшафті на телефоні
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle(stringResource(R.string.export_size), Modifier.padding(start = 0.dp))
                ChipRow(ExportSize.entries, size, { exportSizeLabel(it) }, { size = it }, Modifier.padding(start = 0.dp))
                SectionTitle(stringResource(R.string.export_format))
                ChipRow(ExportFormat.entries, format, { it.label }, { format = it })
                if (format != ExportFormat.PNG) {
                    Spacer(Modifier.height(8.dp))
                    AdjustSlider(stringResource(R.string.export_quality), quality, { quality = it }, {}, range = 50f..100f, default = 92f, format = { "${it.roundToInt()}" })
                }
                Text(
                    stringResource(R.string.export_hint),
                    style = MaterialTheme.typography.bodySmall, color = TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    pendingPermission = true
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    onExport(size, format, quality.roundToInt())
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Фото і (при першому відкритті) картка з короткими порадами поверх нього. */
@Composable
private fun PreviewWithTips(vm: EditorViewModel, modifier: Modifier) {
    Box(modifier) {
        PreviewArea(vm, Modifier.fillMaxSize())
        if (vm.showTips) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .widthIn(max = 420.dp)
                    .background(PanelHigh.copy(alpha = 0.96f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("tips_card"),
            ) {
                Text(stringResource(R.string.tips_title), style = MaterialTheme.typography.titleSmall)
                listOf(R.string.tip_zoom, R.string.tip_compare, R.string.tip_reset, R.string.tip_more).forEach {
                    Text("• " + stringResource(it), style = MaterialTheme.typography.bodySmall, color = TextDim, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = vm::dismissTips, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.tips_ok)) }
            }
        }
    }
}
