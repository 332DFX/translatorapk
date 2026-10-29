package com.translatorapk.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class BatchTranslation(
    val translations: List<String>,
    val detectedLanguage: String?
)

class GoogleTranslator(
    private val client: OkHttpClient = defaultClient()
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    @Serializable
    private data class Translation(
        val translatedText: String,
        val detectedSourceLanguage: String? = null
    )

    @Serializable
    private data class DataBody(val translations: List<Translation>)

    @Serializable
    private data class ResponseBody(val data: DataBody)

    @Serializable
    private data class ErrorDetail(val message: String? = null)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail? = null)

    @Serializable
    private data class DetectData(val detections: List<List<DetectItem>>)

    @Serializable
    private data class DetectItem(val language: String, val confidence: Double? = null)

    @Serializable
    private data class DetectResponse(val data: DetectData)

    suspend fun translate(
        texts: List<String>,
        source: String?,
        target: String,
        apiKey: String
    ): BatchTranslation = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext BatchTranslation(emptyList(), null)
        if (apiKey.isBlank()) throw TranslatorException("Не задан ключ Google Translate. Откройте настройки.")

        val url = "https://translation.googleapis.com/language/translate/v2".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", apiKey)
            .build()

        val payload = buildPayload(texts, target, source)
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.decodeFromString(ErrorBody.serializer(), body).error?.message
                }.getOrNull()
                throw TranslatorException(message ?: "Ошибка Google Translate (${response.code})")
            }
            val parsed = runCatching { json.decodeFromString(ResponseBody.serializer(), body) }
                .getOrElse { throw TranslatorException("Не удалось разобрать ответ Google Translate") }
            val items = parsed.data.translations
            BatchTranslation(
                translations = items.map { htmlUnescape(it.translatedText) },
                detectedLanguage = items.mapNotNull { it.detectedSourceLanguage }.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
            )
        }
    }

    suspend fun detect(text: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val url = "https://translation.googleapis.com/language/translate/v2/detect".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        val payload = JsonObject(mapOf("q" to JsonPrimitive(text))).toString()
        val request = Request.Builder().url(url).post(payload.toRequestBody(mediaType)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            runCatching {
                json.decodeFromString(DetectResponse.serializer(), body)
                    .data.detections.firstOrNull()?.firstOrNull()?.language
            }.getOrNull()
        }
    }

    private fun buildPayload(texts: List<String>, target: String, source: String?): String {
        val map = LinkedHashMap<String, JsonElement>()
        map["q"] = JsonArray(texts.map { JsonPrimitive(it) })
        map["target"] = JsonPrimitive(target)
        map["format"] = JsonPrimitive("text")
        if (!source.isNullOrBlank() && source != "auto") {
            map["source"] = JsonPrimitive(source)
        }
        return JsonObject(map).toString()
    }

    private fun htmlUnescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

class TranslatorException(message: String) : Exception(message)
