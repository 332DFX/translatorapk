package com.translatorapk.core.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.translatorapk.core.TranslatorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MlKitTranslator {

    suspend fun detectLanguages(
        texts: List<String>,
        targetLang: String? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val usable = texts.map { it.trim() }.filter { it.length >= 2 && it.any { c -> c.isLetter() } }
        if (usable.isEmpty()) return@withContext emptyList()

        val normalizedTarget = targetLang?.lowercase()?.substringBefore('-')
        val detectedCounts = mutableMapOf<String, Int>()

        // 1. Fast script & alphabet heuristic
        for (sample in usable) {
            val hLang = detectScriptHeuristic(sample)
            if (hLang != null && hLang != normalizedTarget) {
                detectedCounts[hLang] = (detectedCounts[hLang] ?: 0) + 1
            }
        }

        // 2. ML Kit LanguageIdentification client
        val identifier = runCatching { LanguageIdentification.getClient() }.getOrNull()
        if (identifier != null) {
            try {
                val step = (usable.size / 80).coerceAtLeast(1)
                val samples = usable.filterIndexed { index, _ -> index % step == 0 }.take(80)

                for (sample in samples) {
                    val code = identifier.identifyLanguage(sample.take(400)).await()
                    if (code != "und") {
                        val sCode = serviceCode(code)
                        val baseCode = sCode.lowercase().substringBefore('-')
                        if (baseCode != normalizedTarget) {
                            detectedCounts[sCode] = (detectedCounts[sCode] ?: 0) + 2
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore ML Kit exceptions on missing models, fallback to heuristics
            } finally {
                identifier.close()
            }
        }

        val totalVotes = detectedCounts.values.sum().coerceAtLeast(1)
        detectedCounts.entries
            .filter { it.value >= 2 || (it.value.toFloat() / totalVotes) >= 0.05f }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    suspend fun detectLanguage(texts: List<String>): String? =
        detectLanguages(texts).firstOrNull()

    private fun detectScriptHeuristic(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        // Japanese: Hiragana, Katakana
        if (s.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }) return "ja"
        // Korean: Hangul
        if (s.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }) return "ko"
        // Chinese: CJK Ideographs
        if (s.any { it in '\u4E00'..'\u9FFF' }) return "zh-CN"
        // Cyrillic (Ukrainian / Russian)
        if (s.any { it in "іїєґІЇЄҐ" }) return "uk"
        if (s.any { it in '\u0400'..'\u04FF' }) return "ru"
        // Turkish specific letters
        if (s.any { it in "ğşışĞŞIİ" }) return "tr"
        // Generic Latin with English indicators
        if (s.any { it in 'a'..'z' || it in 'A'..'Z' }) return "en"
        // Arabic
        if (s.any { it in '\u0600'..'\u06FF' }) return "ar"
        // Thai
        if (s.any { it in '\u0E00'..'\u0E7F' }) return "th"
        return null
    }

    suspend fun translate(
        texts: List<String>,
        source: String,
        target: String,
        onStage: (String) -> Unit,
        onProgress: (Int, Int) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val sourceCode = mlKitCode(source)
            ?: throw TranslatorException("ML Kit не поддерживает язык оригинала: $source")
        val targetCode = mlKitCode(target)
            ?: throw TranslatorException("ML Kit не поддерживает язык перевода: $target")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = Translation.getClient(options)
        try {
            onStage("ML Kit: загрузка модели $source → $target")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

            val result = ArrayList<String>(texts.size)
            texts.forEachIndexed { index, text ->
                if (text.isBlank()) {
                    result += text
                } else {
                    result += translator.translate(text).await()
                }
                onProgress(index + 1, texts.size)
            }
            result
        } catch (e: TranslatorException) {
            throw e
        } catch (e: Exception) {
            throw TranslatorException(
                "ML Kit не смог перевести: ${e.message ?: "проверьте Google Play Services и интернет"}"
            )
        } finally {
            translator.close()
        }
    }

    private fun mlKitCode(code: String): String? {
        val normalized = when {
            code.startsWith("zh", ignoreCase = true) -> "zh"
            code.startsWith("pt", ignoreCase = true) -> "pt"
            else -> code.substringBefore('-')
        }
        return TranslateLanguage.fromLanguageTag(normalized)
    }

    private fun serviceCode(code: String): String = when {
        code.startsWith("zh", ignoreCase = true) -> "zh-CN"
        code.startsWith("pt", ignoreCase = true) -> "pt"
        else -> code.substringBefore('-')
    }
}
