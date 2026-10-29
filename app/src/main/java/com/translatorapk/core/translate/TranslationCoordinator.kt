package com.translatorapk.core.translate

import com.translatorapk.core.GoogleTranslator
import com.translatorapk.core.PlaceholderGuard
import com.translatorapk.core.TranslatorException
import com.translatorapk.model.TranslationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class TranslatedItem(
    val text: String,
    val warning: String? = null
)

data class TranslationBatchResult(
    val items: List<TranslatedItem>,
    val detectedLanguage: String?,
    val providerLabel: String,
    val quotaFallback: Boolean
)

class TranslationCoordinator(
    private val myMemory: MyMemoryTranslator = MyMemoryTranslator(),
    private val mlKit: MlKitTranslator = MlKitTranslator(),
    private val google: GoogleTranslator = GoogleTranslator()
) {
    @Volatile
    private var myMemoryQuotaDay: String? = null

    suspend fun translate(
        texts: List<String>,
        source: String?,
        target: String,
        mode: TranslationMode,
        googleKey: String,
        onStage: (String) -> Unit,
        onProgress: (Int, Int, String) -> Unit
    ): TranslationBatchResult = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext TranslationBatchResult(emptyList(), source, "—", false)
        }

        if (mode == TranslationMode.GOOGLE) {
            return@withContext translateGoogle(texts, source, target, googleKey, onStage, onProgress)
        }

        val total = texts.size
        val results = MutableList<TranslatedItem?>(total) { null }
        var done = 0
        var usedMyMemory = false
        var usedMlKit = false
        var quotaFallback = false

        onStage("Определение языка")
        val resolvedSource = if (!source.isNullOrBlank() && source != "auto") {
            normalizeCode(source)
        } else {
            mlKit.detectLanguage(texts)
                ?: throw TranslatorException(
                    "Не удалось определить язык оригинала. Выберите язык вручную в настройках."
                )
        }

        val quotaExhausted = myMemoryQuotaDay == LocalDate.now().toString() ||
            mode == TranslationMode.MLKIT

        if (!quotaExhausted) {
            onStage("Перевод MyMemory")
            for (index in texts.indices) {
                val sourceText = texts[index]
                if (sourceText.isBlank()) {
                    results[index] = TranslatedItem(sourceText)
                    done++
                    onProgress(done, total, "MyMemory")
                    continue
                }
                if (sourceText.toByteArray(Charsets.UTF_8).size > 480) {
                    quotaFallback = true
                    break
                }
                try {
                    val masked = PlaceholderGuard.mask(sourceText)
                    val raw = myMemory.translateOne(masked.text, resolvedSource, target)
                    results[index] = restore(raw, masked)
                    usedMyMemory = true
                } catch (e: MyMemoryQuotaException) {
                    myMemoryQuotaDay = LocalDate.now().toString()
                    quotaFallback = true
                    break
                } catch (e: Exception) {
                    quotaFallback = true
                    break
                }
                done++
                onProgress(done, total, "MyMemory")
                delay(120)
            }
        } else if (myMemoryQuotaDay == LocalDate.now().toString()) {
            quotaFallback = true
        }

        val remaining = texts.indices.filter { results[it] == null }
        if (remaining.isNotEmpty()) {
            usedMlKit = true
            onStage("Перевод ML Kit")
            val maskedInputs = remaining.map { PlaceholderGuard.mask(texts[it]) }
            val translated = mlKit.translate(
                texts = maskedInputs.map { it.text },
                source = resolvedSource,
                target = target,
                onStage = onStage,
                onProgress = { mlDone, mlTotal ->
                    onProgress(done + mlDone, total, "ML Kit")
                }
            )
            remaining.forEachIndexed { position, sourceIndex ->
                val raw = translated.getOrNull(position)
                    ?: throw TranslatorException("ML Kit вернул неполный перевод")
                results[sourceIndex] = restore(raw, maskedInputs[position])
            }
            done = total
        }

        val items = results.mapIndexed { index, item ->
            item ?: throw TranslatorException("Строка ${index + 1} осталась без перевода")
        }
        val label = when {
            usedMyMemory && usedMlKit -> "MyMemory + ML Kit"
            usedMyMemory -> "MyMemory"
            else -> "ML Kit"
        }
        TranslationBatchResult(
            items = items,
            detectedLanguage = resolvedSource,
            providerLabel = label,
            quotaFallback = quotaFallback
        )
    }

    private suspend fun translateGoogle(
        texts: List<String>,
        source: String?,
        target: String,
        googleKey: String,
        onStage: (String) -> Unit,
        onProgress: (Int, Int, String) -> Unit
    ): TranslationBatchResult {
        if (googleKey.isBlank()) {
            throw TranslatorException("Добавьте ключ Google Translate в настройках")
        }
        onStage("Перевод Google Translate")
        val masked = texts.map { PlaceholderGuard.mask(it) }
        val translated = ArrayList<String>(texts.size)
        var detected: String? = null
        var done = 0
        for (batchStart in masked.indices step 40) {
            val batchEnd = (batchStart + 40).coerceAtMost(masked.size)
            val batch = masked.subList(batchStart, batchEnd)
            val response = google.translate(
                texts = batch.map { it.text },
                source = source,
                target = target,
                apiKey = googleKey
            )
            if (detected == null) detected = response.detectedLanguage
            translated += response.translations
            done += batch.size
            onProgress(done, texts.size, "Google Translate")
        }
        val items = masked.mapIndexed { index, m ->
            val raw = translated.getOrNull(index)
                ?: throw TranslatorException("Google Translate вернул неполный перевод")
            restore(raw, m)
        }
        return TranslationBatchResult(items, detected, "Google Translate", false)
    }

    private fun restore(raw: String, masked: PlaceholderGuard.Masked): TranslatedItem {
        val missing = PlaceholderGuard.missing(raw, masked.tokens)
        return TranslatedItem(
            text = PlaceholderGuard.restore(raw, masked.tokens),
            warning = if (missing.isEmpty()) null else "Проверьте: ${missing.joinToString(", ")}"
        )
    }

    private fun normalizeCode(code: String): String = when {
        code.startsWith("zh", ignoreCase = true) -> "zh-CN"
        code.startsWith("pt", ignoreCase = true) -> "pt"
        else -> code.substringBefore('-')
    }
}
