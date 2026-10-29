package com.translatorapk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.translatorapk.model.Screen
import com.translatorapk.model.TranslationMode
import com.translatorapk.ui.components.BusyOverlay
import com.translatorapk.ui.components.OfflineBanner
import com.translatorapk.ui.screens.HomeScreen
import com.translatorapk.ui.screens.ProjectScreen
import com.translatorapk.ui.screens.SettingsScreen
import com.translatorapk.vm.AppViewModel
import java.io.File

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val providerReady = TranslationMode.fromId(settings.provider) != TranslationMode.GOOGLE ||
        settings.apiKey.isNotBlank()
    val snackbar = remember { SnackbarHostState() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importApk(uri)
    }

    LaunchedEffect(state.error, state.message) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.clearMessages()
        }
    }

    BackHandler(enabled = state.screen != Screen.HOME) {
        vm.selectScreen(Screen.HOME)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            OfflineBanner(online = online, onRetry = {})
            Box(modifier = Modifier.weight(1f)) {
                when (state.screen) {
                    Screen.HOME -> HomeScreen(
                        settings = settings,
                        hasApiKey = providerReady,
                        hasProject = state.project != null,
                        onResume = { vm.selectScreen(Screen.PROJECT) },
                        onImport = {
                            importer.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/zip",
                                    "application/octet-stream"
                                )
                            )
                        },
                        onOpenSettings = { vm.selectScreen(Screen.SETTINGS) },
                        onSourceLang = vm::setSourceLang,
                        onTargetLang = vm::setTargetLang
                    )

                    Screen.PROJECT -> {
                        val project = state.project
                        if (project == null) {
                            HomeScreen(
                                settings = settings,
                                hasApiKey = providerReady,
                                onImport = { importer.launch(arrayOf("application/vnd.android.package-archive")) },
                                onOpenSettings = { vm.selectScreen(Screen.SETTINGS) },
                                onSourceLang = vm::setSourceLang,
                                onTargetLang = vm::setTargetLang
                            )
                        } else {
                            ProjectScreen(
                                project = project,
                                filter = state.filter,
                                query = state.query,
                                translating = state.translating,
                                compiling = state.compiling,
                                stage = state.stage,
                                progress = state.progress,
                                onBack = { vm.selectScreen(Screen.HOME) },
                                onTranslate = vm::translateAll,
                                onCompile = vm::compile,
                                onFilter = vm::setFilter,
                                onQuery = vm::setQuery,
                                onEdit = vm::updateTranslation
                            )
                        }
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onBack = { vm.selectScreen(Screen.HOME) },
                        onApiKey = vm::setApiKey,
                        onTestKey = vm::testApiKey,
                        onProvider = vm::setProvider,
                        onSourceLang = vm::setSourceLang,
                        onTargetLang = vm::setTargetLang
                    )
                }
            }
        }

        BusyOverlay(
            visible = state.analyzing || state.translating || state.compiling,
            stage = state.stage,
            progress = state.progress,
            modifier = Modifier.fillMaxSize()
        )

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }

    state.resultApk?.let { path ->
        AlertDialog(
            onDismissRequest = vm::clearResult,
            title = { Text("APK готов") },
            text = {
                Text(
                    text = "Файл собран и подписан:\n\n${File(path).name}\n\n" +
                        "Подпись отличается от оригинала, поэтому перед установкой может " +
                        "потребоваться удалить исходное приложение.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { shareApk(context, path) }) {
                    Text("Поделиться")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::clearResult) {
                    Text("Закрыть")
                }
            }
        )
    }
}

private fun shareApk(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться APK"))
}
