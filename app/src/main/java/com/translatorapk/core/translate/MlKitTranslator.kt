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

    suspend fun detectLanguage(texts: List<String>): String? = withContext(Dispatchers.IO) {
        val usable = texts.map { it.trim() }.filter { it.length >= 2 }
        if (usable.isEmpty()) return@withContext null

        val identifier = LanguageIdentification.getClient()
        try {
            val joined = usable.take(30).joinToString("\n").take(1_200)
            val joinedCode = identifier.identifyLanguage(joined).await()
            if (joinedCode != "und") return@withContext serviceCode(joinedCode)

            val votes = mutableMapOf<String, Int>()
            for (sample in usable.take(8)) {
                val code = identifier.identifyLanguage(sample.take(400)).await()
                if (code != "und") votes[code] = (votes[code] ?: 0) + 1
            }
            votes.maxByOrNull { it.value }?.key?.let { serviceCode(it) }
        } catch (e: Exception) {
            null
        } finally {
            identifier.close()
        }
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
