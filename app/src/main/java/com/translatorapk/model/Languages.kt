package com.translatorapk.model

data class Language(val code: String, val label: String, val flag: String)

val SOURCE_LANGUAGES = listOf(
    Language("auto", "Авто", "A"),
    Language("en", "English", "EN"),
    Language("ru", "Русский", "RU"),
    Language("ja", "日本語", "JA"),
    Language("zh-CN", "中文", "ZH"),
    Language("ko", "한국어", "KO"),
    Language("es", "Español", "ES"),
    Language("de", "Deutsch", "DE"),
    Language("fr", "Français", "FR"),
    Language("it", "Italiano", "IT"),
    Language("pt", "Português", "PT"),
    Language("tr", "Türkçe", "TR"),
    Language("pl", "Polski", "PL"),
    Language("uk", "Українська", "UA"),
    Language("id", "Indonesia", "ID"),
    Language("vi", "Tiếng Việt", "VI"),
    Language("th", "ไทย", "TH"),
    Language("ar", "العربية", "AR"),
    Language("hi", "हिन्दी", "HI")
)

val TARGET_LANGUAGES = SOURCE_LANGUAGES.filter { it.code != "auto" }

fun languageLabel(code: String?): String =
    SOURCE_LANGUAGES.firstOrNull { it.code == code }?.label ?: (code ?: "—")
