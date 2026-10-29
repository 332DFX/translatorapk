package com.translatorapk.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translatorapk.core.ApkAnalyzer
import com.translatorapk.core.ApkBuilder
import com.translatorapk.core.GoogleTranslator
import com.translatorapk.core.PlaceholderGuard
import com.translatorapk.core.TranslatorException
import com.translatorapk.data.Settings
import com.translatorapk.data.SettingsStore
import com.translatorapk.model.EntryFilter
import com.translatorapk.model.EntryStatus
import com.translatorapk.model.Screen
import com.translatorapk.model.TranslationProject
import com.translatorapk.util.ConnectivityObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val screen: Screen = Screen.HOME,
    val project: TranslationProject? = null,
    val analyzing: Boolean = false,
    val translating: Boolean = false,
    val compiling: Boolean = false,
    val stage: String = "",
    val progress: Float? = null,
    val error: String? = null,
    val message: String? = null,
    val resultApk: String? = null,
    val filter: EntryFilter = EntryFilter.ALL,
    val query: String = "",
    val detectedLanguage: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val analyzer = ApkAnalyzer(application)
    private val builder = ApkBuilder(application)
    private val translator = GoogleTranslator()
    private val settingsStore = SettingsStore(application)
    private val connectivity = ConnectivityObserver(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val online: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun selectScreen(screen: Screen) {
        _uiState.update { it.copy(screen = screen) }
    }

    fun importApk(uri: Uri) {
        if (_uiState.value.analyzing) return
        _uiState.update {
            it.copy(analyzing = true, stage = "Копирование APK", progress = null, error = null, message = null)
        }
        viewModelScope.launch {
            try {
                val currentSettings = settings.value
                val file = withContext(Dispatchers.IO) { analyzer.importToCache(uri) }
                _uiState.update { it.copy(stage = "Анализ файла") }
                val project = withContext(Dispatchers.IO) {
                    analyzer.analyze(
                        file = file,
                        sourceLang = currentSettings.sourceLang.takeUnless { s -> s == "auto" },
                        targetLang = currentSettings.targetLang,
                        onStage = { stage -> _uiState.update { it.copy(stage = stage) } }
                    )
                }
                _uiState.update {
                    it.copy(
                        analyzing = false,
                        project = project,
                        screen = Screen.PROJECT,
                        stage = "",
                        detectedLanguage = project.sourceLang,
                        message = if (project.entries.isEmpty()) {
                            "Текст для перевода не найден"
                        } else {
                            "Готово: ${project.entries.size} строк"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(analyzing = false, stage = "", error = e.message ?: "Не удалось прочитать APK")
                }
            }
        }
    }

    fun translateAll() {
        val project = _uiState.value.project ?: return
        if (_uiState.value.translating) return
        if (!online.value) {
            _uiState.update { it.copy(error = "Нет подключения к интернету. Подключитесь и повторите.") }
            return
        }
        val apiKey = settings.value.apiKey
        if (apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    error = "Добавьте ключ Google Translate в настройках",
                    screen = Screen.SETTINGS
                )
            }
            return
        }

        _uiState.update {
            it.copy(translating = true, stage = "Подготовка", progress = 0f, error = null, message = null)
        }

        viewModelScope.launch {
            try {
                val unique = project.entries.map { it.source }.distinct()
                val result = mutableMapOf<String, TranslatedSource>()
                val batches = chunk(unique)
                var done = 0
                var detected: String? = null

                for (batch in batches) {
                    val masked = batch.map { PlaceholderGuard.mask(it) }
                    val response = translator.translate(
                        texts = masked.map { it.text },
                        source = project.sourceLang,
                        target = project.targetLang,
                        apiKey = apiKey
                    )
                    if (detected == null) detected = response.detectedLanguage

                    batch.forEachIndexed { index, source ->
                        val maskedText = response.translations.getOrNull(index) ?: return@forEachIndexed
                        val tokens = masked[index].tokens
                        val missing = PlaceholderGuard.missing(maskedText, tokens)
                        val restored = PlaceholderGuard.restore(maskedText, tokens)
                        result[source] = TranslatedSource(
                            text = restored,
                            warning = if (missing.isEmpty()) null else "Проверьте: ${missing.joinToString(", ")}"
                        )
                    }

                    done += batch.size
                    _uiState.update {
                        it.copy(
                            stage = "Перевод ${done.coerceAtMost(unique.size)} из ${unique.size}",
                            progress = done.toFloat() / unique.size.coerceAtLeast(1)
                        )
                    }
                }

                val updated = project.entries.map { entry ->
                    val translated = result[entry.source]
                    if (translated == null) {
                        entry
                    } else {
                        entry.copy(
                            translation = translated.text,
                            status = EntryStatus.TRANSLATED,
                            warning = translated.warning
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        translating = false,
                        project = project.copy(entries = updated, detectedLanguage = detected),
                        detectedLanguage = detected,
                        stage = "",
                        progress = null,
                        message = "Переведено строк: $done"
                    )
                }
            } catch (e: TranslatorException) {
                _uiState.update {
                    it.copy(translating = false, stage = "", progress = null, error = e.message)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        translating = false,
                        stage = "",
                        progress = null,
                        error = e.message ?: "Ошибка перевода"
                    )
                }
            }
        }
    }

    fun updateTranslation(id: String, text: String) {
        val project = _uiState.value.project ?: return
        val entries = project.entries.map { entry ->
            if (entry.id != id) return@map entry
            entry.copy(
                translation = text,
                status = if (text.isBlank()) EntryStatus.PENDING else EntryStatus.EDITED
            )
        }
        _uiState.update { it.copy(project = project.copy(entries = entries)) }
    }

    fun setFilter(filter: EntryFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun compile() {
        val project = _uiState.value.project ?: return
        if (_uiState.value.compiling) return
        if (project.entries.none { it.translation.isNotBlank() }) {
            _uiState.update { it.copy(error = "Сначала переведите хотя бы одну строку") }
            return
        }
        _uiState.update {
            it.copy(compiling = true, stage = "Сборка APK", progress = 0f, error = null, message = null)
        }
        viewModelScope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    builder.build(project) { progress ->
                        _uiState.update {
                            it.copy(stage = "Сборка и подпись", progress = progress)
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        compiling = false,
                        stage = "",
                        progress = null,
                        resultApk = output.absolutePath,
                        message = "APK собран и подписан"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        compiling = false,
                        stage = "",
                        progress = null,
                        error = e.message ?: "Не удалось собрать APK"
                    )
                }
            }
        }
    }

    fun setApiKey(value: String) {
        viewModelScope.launch { settingsStore.setApiKey(value.trim()) }
    }

    fun setTargetLang(value: String) {
        viewModelScope.launch { settingsStore.setTargetLang(value) }
        _uiState.value.project?.let { project ->
            _uiState.update { it.copy(project = project.copy(targetLang = value)) }
        }
    }

    fun setSourceLang(value: String) {
        viewModelScope.launch { settingsStore.setSourceLang(value) }
        _uiState.value.project?.let { project ->
            _uiState.update {
                it.copy(project = project.copy(sourceLang = value.takeUnless { v -> v == "auto" }))
            }
        }
    }

    fun testApiKey(key: String = settings.value.apiKey) {
        val apiKey = key.trim()
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(error = "Введите ключ Google Translate") }
            return
        }
        viewModelScope.launch {
            try {
                val response = translator.translate(
                    texts = listOf("Hello"),
                    source = "en",
                    target = settings.value.targetLang,
                    apiKey = apiKey
                )
                _uiState.update {
                    it.copy(message = "Ключ работает: ${response.translations.firstOrNull().orEmpty()}")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Ключ не работает") }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    fun clearResult() {
        _uiState.update { it.copy(resultApk = null) }
    }

    fun resetProject() {
        _uiState.update {
            it.copy(
                project = null,
                screen = Screen.HOME,
                filter = EntryFilter.ALL,
                query = "",
                detectedLanguage = null
            )
        }
    }

    private fun chunk(texts: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var chars = 0
        for (text in texts) {
            if (current.isNotEmpty() && (current.size >= 40 || chars + text.length > 20_000)) {
                result += current
                current = mutableListOf()
                chars = 0
            }
            current += text
            chars += text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private data class TranslatedSource(val text: String, val warning: String?)

    fun resultFile(): File? = _uiState.value.resultApk?.let { File(it) }
}
