package com.translatorapk.core

object PlaceholderGuard {

    private val pattern = Regex(
        """%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]|\$\{[^}]+\}|\{[A-Za-z_][\w.]*\}|\{\d+\}|<[^>]{1,60}>|&[a-zA-Z#0-9]+;|\\[ntr]"""
    )

    data class Masked(val text: String, val tokens: List<String>)

    fun mask(source: String): Masked {
        val tokens = mutableListOf<String>()
        val masked = pattern.replace(source) { match ->
            tokens += match.value
            "\uE000${tokens.size - 1}\uE001"
        }
        return Masked(masked, tokens)
    }

    fun restore(maskedTranslation: String, tokens: List<String>): String {
        var result = maskedTranslation
        tokens.forEachIndexed { index, token ->
            result = result.replace("\uE000$index\uE001", token)
        }
        return result
    }

    fun intact(maskedTranslation: String, tokens: List<String>): Boolean {
        return tokens.indices.all { maskedTranslation.contains("\uE000$it\uE001") }
    }

    fun missing(maskedTranslation: String, tokens: List<String>): List<String> {
        return tokens.filterIndexed { index, _ -> !maskedTranslation.contains("\uE000$index\uE001") }
    }
}
