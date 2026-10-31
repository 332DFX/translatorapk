package com.translatorapk.model

data class Language(
    val code: String,
    val label: String,
    val flag: String,
    val nameRu: String = label
)

val SOURCE_LANGUAGES = listOf(
    Language("auto", "Авто", "A", "Авто"),
    Language("en", "English", "EN", "Английский"),
    Language("ru", "Русский", "RU", "Русский"),
    Language("ja", "日本語", "JA", "Японский"),
    Language("zh-CN", "中文", "ZH", "Китайский"),
    Language("ko", "한국어", "KO", "Корейский"),
    Language("es", "Español", "ES", "Испанский"),
    Language("de", "Deutsch", "DE", "Немецкий"),
    Language("fr", "Français", "FR", "Французский"),
    Language("it", "Italiano", "IT", "Итальянский"),
    Language("pt", "Português", "PT", "Португальский"),
    Language("tr", "Türkçe", "TR", "Турецкий"),
    Language("pl", "Polski", "PL", "Польский"),
    Language("uk", "Українська", "UA", "Украинский"),
    Language("id", "Indonesia", "ID", "Индонезийский"),
    Language("vi", "Tiếng Việt", "VI", "Вьетнамский"),
    Language("th", "ไทย", "TH", "Тайский"),
    Language("ar", "العربية", "AR", "Арабский"),
    Language("hi", "हिन्दी", "HI", "Хинди")
)

val TARGET_LANGUAGES = SOURCE_LANGUAGES.filter { it.code != "auto" }

fun languageLabel(code: String?): String =
    SOURCE_LANGUAGES.firstOrNull { it.code == code }?.label ?: (code ?: "—")

fun languageNameRu(code: String?): String {
    val normalized = when {
        code.isNullOrBlank() -> return "—"
        code.startsWith("zh", ignoreCase = true) -> "zh-CN"
        else -> code.substringBefore('-')
    }
    return SOURCE_LANGUAGES.firstOrNull { it.code.equals(normalized, ignoreCase = true) }?.nameRu
        ?: SOURCE_LANGUAGES.firstOrNull { it.code.startsWith(normalized, ignoreCase = true) }?.nameRu
        ?: code
}

fun formatLanguagesEnumeration(codes: List<String>): String {
    if (codes.isEmpty()) return "не определены"
    val names = codes.map { languageNameRu(it) }.distinct()
    return names.joinToString(", ")
}
