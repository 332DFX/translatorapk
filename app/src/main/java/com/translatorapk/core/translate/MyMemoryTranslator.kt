package com.translatorapk.core.translate

import com.translatorapk.core.TranslatorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MyMemoryQuotaException(message: String) : Exception(message)

class MyMemoryTranslator(
    private val client: OkHttpClient = httpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun translateOne(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext text

            val url = "https://api.mymemory.translated.net/get".toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("langpair", "${source.lowercase()}|${target.lowercase()}")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "APKTranslator/0.1 (Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 429) {
                    throw MyMemoryQuotaException("Дневной лимит MyMemory исчерпан")
                }
                if (!response.isSuccessful) {
                    if (body.contains("MYMEMORY WARNING", ignoreCase = true)) {
                        throw MyMemoryQuotaException("Дневной лимит MyMemory исчерпан")
                    }
                    throw TranslatorException("Ошибка MyMemory (${response.code})")
                }

                val root = runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw TranslatorException("Не удалось разобрать ответ MyMemory") }

                val status = root["responseStatus"]?.jsonPrimitive?.intOrNull ?: 200
                val quota = root["quotaFinished"]?.jsonPrimitive?.booleanOrNull ?: false
                val details = root["responseDetails"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val translated = root["responseData"]?.jsonObject
                    ?.get("translatedText")?.jsonPrimitive?.contentOrNull

                val quotaReached = quota ||
                    status == 403 || status == 429 ||
                    details.contains("USED ALL AVAILABLE FREE TRANSLATIONS", ignoreCase = true) ||
                    translated.orEmpty().contains("MYMEMORY WARNING", ignoreCase = true)

                if (quotaReached) {
                    throw MyMemoryQuotaException("Дневной лимит MyMemory исчерпан")
                }
                if (status != 200 || translated.isNullOrBlank()) {
                    throw TranslatorException(details.ifBlank { "MyMemory не вернул перевод" })
                }
                htmlUnescape(translated)
            }
        }

    private fun htmlUnescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private companion object {
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
