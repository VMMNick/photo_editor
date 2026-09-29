package com.photoeditor.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.photoeditor.app.ui.EditorScreen
import com.photoeditor.app.ui.EditorViewModel
import com.photoeditor.app.ui.HomeScreen
import com.photoeditor.app.ui.theme.PhotoEditorTheme

class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Тема завжди темна — світлі значки статус-бару незалежно від системної теми
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val openedFromIntent = savedInstanceState == null && handleIntent(intent)
        // Нове фото не прийшло ззовні — пробуємо відновити незавершене редагування
        // (після того як Android закрив процес у фоні або застосунок змахнули з «Останніх»)
        if (!openedFromIntent) vm.restoreSessionIfAny()

        setContent {
            PhotoEditorTheme {
                if (vm.info == null) {
                    HomeScreen(vm, onPicked = ::openUri)
                } else {
                    EditorScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Застосунок пішов у фон: зберігаємо стан, поки процес ще живий
        vm.saveSessionNow()
    }

    /** @return true, якщо з Intent отримано фото і його почали відкривати */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri ?: return false
        openUri(uri)
        return true
    }

    private fun openUri(uri: Uri) {
        // Для документів із SAF — зберігаємо доступ, щоб експорт міг перечитати оригінал
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        vm.open(uri)
    }
}
