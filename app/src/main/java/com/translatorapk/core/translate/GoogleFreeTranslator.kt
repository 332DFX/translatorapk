package com.translatorapk.core.translate

import com.translatorapk.core.TranslatorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GoogleFreeTranslator(
    private val client: OkHttpClient = httpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun translateOne(text: String, source: String?, target: String): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext text

            val sl = source?.lowercase()?.takeIf { it.isNotBlank() && it != "auto" } ?: "auto"
            val tl = target.lowercase()

            val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl()
                .newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", sl)
                .addQueryParameter("tl", tl)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TranslatorException("Google Free returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseSingleResponse(body)
            }
        }

    suspend fun translateBatch(
        texts: List<String>,
        source: String?,
        target: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        val results = MutableList(texts.size) { "" }
        val sl = source?.lowercase()?.takeIf { it.isNotBlank() && it != "auto" } ?: "auto"
        val tl = target.lowercase()

        val chunkSize = 20
        var processed = 0

        for (i in texts.indices step chunkSize) {
            val end = minOf(i + chunkSize, texts.size)
            val subList = texts.subList(i, end)

            val queryBuilder = StringBuilder()
            subList.forEachIndexed { idx, item ->
                queryBuilder.append("\n~~~$idx~~~\n").append(item)
            }

            val translatedSub = runCatching {
                val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("client", "gtx")
                    .addQueryParameter("sl", sl)
                    .addQueryParameter("tl", tl)
                    .addQueryParameter("dt", "t")
                    .addQueryParameter("q", queryBuilder.toString())
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                    .get()
                    .build()

                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw TranslatorException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }

                val fullText = parseSingleResponse(body)
                val split = fullText.split(Regex("""~~~\d+~~~"""))
                val extracted = split.drop(1).map { it.trim('\r', '\n') }
                if (extracted.size == subList.size) extracted else null
            }.getOrNull()

            if (translatedSub != null) {
                translatedSub.forEachIndexed { idx, s ->
                    results[i + idx] = s
                }
            } else {
                // Fallback to one-by-one translation for this chunk
                subList.forEachIndexed { idx, text ->
                    results[i + idx] = runCatching {
                        translateOne(text, sl, tl)
                    }.getOrDefault(text)
                    delay(50)
                }
            }

            processed += subList.size
            onProgress(processed, texts.size)
            delay(100)
        }

        results
    }

    private fun parseSingleResponse(jsonBody: String): String {
        val root = runCatching { json.parseToJsonElement(jsonBody).jsonArray }.getOrNull()
            ?: throw TranslatorException("Не удалось разобрать ответ Google Translate")

        val sentences = root.getOrNull(0)?.jsonArray
            ?: throw TranslatorException("Пустой ответ перевода")

        val sb = StringBuilder()
        for (elem in sentences) {
            val part = elem.jsonArray.getOrNull(0)?.jsonPrimitive?.content
            if (part != null) sb.append(part)
        }
        return sb.toString()
    }

    private companion object {
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
