package com.translatorapk.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.translatorapk.data.Settings
import com.translatorapk.model.SOURCE_LANGUAGES
import com.translatorapk.model.TARGET_LANGUAGES
import com.translatorapk.model.languageLabel
import com.translatorapk.ui.components.LanguagePicker

@Composable
fun HomeScreen(
    settings: Settings,
    hasApiKey: Boolean,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onSourceLang: (String) -> Unit,
    onTargetLang: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.background,
                        colors.background,
                        colors.primary.copy(alpha = 0.10f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(colors.primary, colors.secondary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Translate,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "APK Translator",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onBackground
                    )
                    Text(
                        text = "Перевод игр и приложений",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
                FilledTonalIconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Настройки")
                }
            }

            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = colors.tertiary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Новый перевод",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                    }

                    Text(
                        text = "Выберите APK, укажите язык оригинала и перевода. " +
                            "Приложение разберёт файл, покажет строки карточками и соберёт новый APK.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguagePicker(
                            selected = settings.sourceLang,
                            languages = SOURCE_LANGUAGES,
                            onSelected = onSourceLang,
                            labelPrefix = "Из",
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        LanguagePicker(
                            selected = settings.targetLang,
                            languages = TARGET_LANGUAGES,
                            onSelected = onTargetLang,
                            labelPrefix = "В",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (settings.sourceLang == "auto") {
                                    "Язык оригинала определится автоматически"
                                } else {
                                    "Оригинал: ${languageLabel(settings.sourceLang)}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "→ ${languageLabel(settings.targetLang)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.secondary
                            )
                        }
                    }

                    Button(
                        onClick = onImport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Выбрать APK", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (hasApiKey) colors.surface else colors.errorContainer,
                contentColor = if (hasApiKey) colors.onSurface else colors.onErrorContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = if (hasApiKey) colors.secondary else colors.onErrorContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasApiKey) "Ключ Google Translate подключён" else "Нужен ключ Google Translate",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (hasApiKey) {
                                "Используется Cloud Translation API"
                            } else {
                                "Добавьте ключ в настройках, чтобы переводить текст"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text(if (hasApiKey) "Изменить" else "Добавить")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Как это работает",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground
                )
                StepRow("1", "Импорт", "APK распаковывается и анализируется: движок, файлы, строки.")
                StepRow("2", "Перевод", "Google Translate переводит пакетами с защитой плейсхолдеров.")
                StepRow("3", "Правки", "Каждую строку можно отредактировать в карточке.")
                StepRow("4", "Сборка", "Новый APK собирается, подписывается и сохраняется.")
            }

            Text(
                text = "Переводите только те приложения, на которые у вас есть права.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
