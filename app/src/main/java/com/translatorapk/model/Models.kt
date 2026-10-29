package com.translatorapk.model

enum class EngineType(val title: String, val subtitle: String) {
    NATIVE("Android", "resources.arsc, строки приложения"),
    GODOT("Godot", "PCK, .translation, CSV"),
    UNITY("Unity", "assets/bin/Data, TextAsset"),
    UNREAL("Unreal", ".locres, .pak"),
    GENERIC("Generic", "JSON, CSV, текстовые файлы"),
    UNKNOWN("Неизвестно", "Только ZIP-анализ")
}

enum class EntryKind { JSON, CSV, LINES }

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
    val detectedLanguage: String? = null
)

enum class EntryFilter { ALL, PENDING, TRANSLATED, EDITED, ERRORS }

enum class Screen { HOME, PROJECT, SETTINGS }
