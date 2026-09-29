package com.photoeditor.app.ui

import com.photoeditor.app.ui.theme.PanelHigh
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import com.photoeditor.app.R
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoeditor.app.ui.theme.Accent
import com.photoeditor.app.ui.theme.Bg
import com.photoeditor.app.ui.theme.Panel
import com.photoeditor.app.ui.theme.TextDim

@Composable
fun HomeScreen(vm: EditorViewModel, onPicked: (Uri) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.message = null
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPicked)
    }
    // Системний вибір файлів — для RAW (DNG, CR2, NEF, ARW…), яких немає в галереї
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPicked)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val columns = AdaptiveLayout.homeColumns(maxWidth.value)
        val shortScreen = maxHeight < 480.dp
        // Ландшафт на телефоні: кнопки поруч, а не одна під одною
        val wideButtons = maxWidth > 560.dp
        val features = listOf(
            Triple(Icons.Filled.Crop, R.string.feat_geometry_title, R.string.feat_geometry_text),
            Triple(Icons.Filled.WbSunny, R.string.feat_light_title, R.string.feat_light_text),
            Triple(Icons.Filled.Palette, R.string.feat_color_title, R.string.feat_color_text),
            Triple(Icons.Filled.AutoFixHigh, R.string.feat_retouch_title, R.string.feat_retouch_text),
            Triple(Icons.Filled.Layers, R.string.feat_masks_title, R.string.feat_masks_text),
            Triple(Icons.Filled.AutoAwesome, R.string.feat_filters_title, R.string.feat_filters_text),
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = AdaptiveLayout.HOME_MAX_WIDTH_DP.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag("home_columns_$columns"),
        ) {
            Spacer(Modifier.height(if (shortScreen) 8.dp else 32.dp))
            Text(stringResource(R.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_subtitle), color = TextDim)
            Spacer(Modifier.height(if (shortScreen) 16.dp else 32.dp))

            val galleryButton: @Composable (Modifier) -> Unit = { m ->
                Button(
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !vm.loading,
                    modifier = m.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, null)
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.home_open_gallery))
                }
            }
            val fileButton: @Composable (Modifier) -> Unit = { m ->
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf("image/*", "application/octet-stream")) },
                    enabled = !vm.loading,
                    modifier = m.height(56.dp),
                ) {
                    Icon(Icons.Filled.FolderOpen, null)
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.home_open_file))
                }
            }
            if (wideButtons) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    galleryButton(Modifier.weight(1f))
                    fileButton(Modifier.weight(1f))
                }
            } else {
                galleryButton(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                fileButton(Modifier.fillMaxWidth())
            }

            if (vm.loading) {
                Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 3.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.home_loading))
                }
            }

            if (vm.recents.isNotEmpty()) {
                Spacer(Modifier.height(if (shortScreen) 16.dp else 28.dp))
                Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("recent_row")) {
                    items(vm.recents, key = { it.id }) { r -> RecentCard(r, enabled = !vm.loading, onOpen = { vm.openRecent(r.id) }, onDelete = { vm.deleteRecent(r.id) }) }
                }
            }

            Spacer(Modifier.height(if (shortScreen) 20.dp else 36.dp))
            // Сітка можливостей: 1 колонка на телефоні, 2 — на фолді / малому планшеті, 3 — на великому
            features.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (icon, title, text) -> Feature(icon, stringResource(title), stringResource(text), Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Accent)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = TextDim)
        }
    }
}

/** Відкладене редагування: мініатюра результату, назва, кнопка видалення. */
@Composable
private fun RecentCard(r: com.photoeditor.app.image.RecentItem, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val thumb = remember(r.thumb) {
        r.thumb?.let { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() }
    }
    val openLabel = stringResource(R.string.cd_open_recent, r.name)
    Column(
        Modifier
            .width(128.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(enabled = enabled, onClickLabel = openLabel, onClick = onOpen)
            .semantics { contentDescription = openLabel },
    ) {
        Box(Modifier.size(128.dp).background(PanelHigh), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Image, null, tint = TextDim)
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(
                    Icons.Filled.Close, stringResource(R.string.cd_delete_recent, r.name), tint = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(3.dp).size(16.dp),
                )
            }
        }
        Text(
            r.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
