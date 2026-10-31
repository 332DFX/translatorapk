package com.translatorapk.model

enum class EngineType(val title: String, val subtitle: String) {
    NATIVE("Android", "resources.arsc, строки приложения"),
    GODOT("Godot", "PCK, .po, .tres, CSV"),
    UNITY("Unity", "assets/bin/Data, TextAsset"),
    UNREAL("Unreal", ".locres, .pak"),
    GENERIC("Generic", "JSON, CSV, текстовые файлы"),
    UNKNOWN("Неизвестно", "Только ZIP-анализ")
}

enum class EntryKind { JSON, CSV, LINES, ARSC, PO, TRES }

enum class EntryStatus { PENDING, TRANSLATED, EDITED, ERROR }

enum class NoteLevel { INFO, WARNING }

data class TextEntry(
    val id: String,
    val filePath: String,
    val key: String,
    val source: String,
    val translation: String = "",
    val kind: EntryKind,
    val status: EntryStatus = EntryStatus.PENDING,
    val warning: String? = null
)

data class AnalysisNote(val level: NoteLevel, val text: String)

data class TranslationProject(
    val apkName: String,
    val apkPath: String,
    val packageName: String?,
    val engine: EngineType,
    val sourceLang: String?,
    val targetLang: String,
    val entries: List<TextEntry>,
    val notes: List<AnalysisNote>,
    val detectedLanguage: String? = null,
    val fontPaths: List<String> = emptyList()
)

enum class EntryFilter { ALL, PENDING, TRANSLATED, EDITED, ERRORS }

enum class Screen { HOME, PROJECT, SETTINGS }

enum class TranslationMode(val id: String, val title: String, val subtitle: String) {
    GOOGLE_FREE("google_free", "Google (Бесплатно)", "Быстрый онлайн-перевод без ограничений и ключа"),
    AUTO("auto", "MyMemory + ML Kit", "Бесплатно, ML Kit при лимите"),
    MLKIT("mlkit", "ML Kit на устройстве", "Без ключа, модель скачивается"),
    GOOGLE("google", "Google Cloud API", "Нужен API-ключ");

    companion object {
        fun fromId(id: String?): TranslationMode =
            entries.firstOrNull { it.id == id } ?: GOOGLE_FREE
    }
}
