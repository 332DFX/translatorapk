package com.translatorapk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "translator_settings")

data class Settings(
    val apiKey: String = "",
    val targetLang: String = "ru",
    val sourceLang: String = "auto",
    val provider: String = "auto"
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            apiKey = prefs[API_KEY].orEmpty(),
            targetLang = prefs[TARGET_LANG] ?: "ru",
            sourceLang = prefs[SOURCE_LANG] ?: "auto",
            provider = prefs[PROVIDER] ?: "auto"
        )
    }

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[API_KEY] = value }
    }

    suspend fun setTargetLang(value: String) {
        context.dataStore.edit { it[TARGET_LANG] = value }
    }

    suspend fun setSourceLang(value: String) {
        context.dataStore.edit { it[SOURCE_LANG] = value }
    }

    suspend fun setProvider(value: String) {
        context.dataStore.edit { it[PROVIDER] = value }
    }

    private companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val TARGET_LANG = stringPreferencesKey("target_lang")
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val PROVIDER = stringPreferencesKey("provider")
    }
}
