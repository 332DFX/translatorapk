package com.translatorapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.translatorapk.data.Settings
import com.translatorapk.model.SOURCE_LANGUAGES
import com.translatorapk.model.TARGET_LANGUAGES
import com.translatorapk.model.TranslationMode
import com.translatorapk.ui.components.LanguagePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onApiKey: (String) -> Unit,
    onTestKey: (String) -> Unit,
    onProvider: (String) -> Unit,
    onSourceLang: (String) -> Unit,
    onTargetLang: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var key by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var keyVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colors.surface
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Translate, contentDescription = null, tint = colors.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Движок перевода",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface
                            )
                            Text(
                                text = "NMT · без LLM и генерации",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    TranslationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = TranslationMode.fromId(settings.provider) == mode,
                            onClick = { onProvider(mode.id) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Column {
                                    Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = mode.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }

                    Text(
                        text = when (TranslationMode.fromId(settings.provider)) {
                            TranslationMode.AUTO ->
                                "Сначала MyMemory — бесплатно и без ключа. Когда дневной лимит " +
                                    "закончится, приложение автоматически продолжит через ML Kit на телефоне."
                            TranslationMode.MLKIT ->
                                "Перевод целиком на устройстве. При первом запуске скачивается " +
                                    "языковая модель, поэтому нужен интернет."
                            TranslationMode.GOOGLE ->
                                "Google Cloud Translation API v2. Нужен ключ и активная квота."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (TranslationMode.fromId(settings.provider) == TranslationMode.GOOGLE) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = colors.surface
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.primary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Google Cloud Translation",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "API v2 · NMT, без LLM",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API key") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = null
                                    )
                                }
                            }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onApiKey(key)
                                    onTestKey(key)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Сохранить и проверить")
                            }
                            OutlinedButton(
                                onClick = { onApiKey(key) },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Сохранить")
                            }
                        }

                        Text(
                            text = "Ключ хранится только на этом устройстве. Первые 500 000 символов " +
                                "в месяц у Google бесплатны. Ключ лучше ограничить Cloud Translation API.",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colors.surface
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Языки по умолчанию",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Оригинал",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    LanguagePicker(
                        selected = settings.sourceLang,
                        languages = SOURCE_LANGUAGES,
                        onSelected = onSourceLang
                    )
                    Text(
                        text = "Перевод",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    LanguagePicker(
                        selected = settings.targetLang,
                        languages = TARGET_LANGUAGES,
                        onSelected = onTargetLang
                    )
                }
            }

            if (TranslationMode.fromId(settings.provider) == TranslationMode.GOOGLE) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = colors.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Как получить ключ",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "1. console.cloud.google.com → создать проект\n" +
                                "2. APIs & Services → Library → Cloud Translation API → Enable\n" +
                                "3. Credentials → Create credentials → API key\n" +
                                "4. Ограничить ключ только Cloud Translation API",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "APK Translator 0.1.0",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}
