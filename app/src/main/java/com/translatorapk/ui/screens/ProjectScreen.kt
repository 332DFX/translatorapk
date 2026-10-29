package com.translatorapk.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.translatorapk.model.EntryFilter
import com.translatorapk.model.EntryStatus
import com.translatorapk.model.NoteLevel
import com.translatorapk.model.TextEntry
import com.translatorapk.model.TranslationProject
import com.translatorapk.model.languageLabel
import com.translatorapk.ui.components.StringCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    project: TranslationProject,
    filter: EntryFilter,
    query: String,
    translating: Boolean,
    compiling: Boolean,
    stage: String,
    progress: Float?,
    onBack: () -> Unit,
    onTranslate: () -> Unit,
    onCompile: () -> Unit,
    onFilter: (EntryFilter) -> Unit,
    onQuery: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val translatedCount = project.entries.count { it.translation.isNotBlank() }
    val total = project.entries.size
    val filtered = remember(project.entries, filter, query) {
        project.entries.filter { entry ->
            val matchesFilter = when (filter) {
                EntryFilter.ALL -> true
                EntryFilter.PENDING -> entry.translation.isBlank()
                EntryFilter.TRANSLATED -> entry.status == EntryStatus.TRANSLATED
                EntryFilter.EDITED -> entry.status == EntryStatus.EDITED
                EntryFilter.ERRORS -> entry.status == EntryStatus.ERROR || entry.warning != null
            }
            val q = query.trim()
            val matchesQuery = q.isEmpty() ||
                entry.source.contains(q, ignoreCase = true) ||
                entry.translation.contains(q, ignoreCase = true) ||
                entry.filePath.contains(q, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.apkName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${project.engine.title} · ${project.packageName ?: "package неизвестен"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
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
        },
        bottomBar = {
            Surface(
                color = colors.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (translating || compiling) {
                        LinearProgressIndicator(
                            progress = { progress?.coerceIn(0f, 1f) ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.primary
                        )
                        Text(
                            text = stage,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Переведено $translatedCount из $total",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (total == 0) "0%" else "${translatedCount * 100 / total}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.secondary
                        )
                    }
                    Button(
                        onClick = onCompile,
                        enabled = translatedCount > 0 && !compiling && !translating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.Build, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Скомпилировать в APK", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = colors.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${languageLabel(project.sourceLang ?: "auto")} → ${languageLabel(project.targetLang)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface
                                )
                                if (project.detectedLanguage != null) {
                                    Text(
                                        text = "Определён язык: ${languageLabel(project.detectedLanguage)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.secondary
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = onTranslate,
                                enabled = !translating && !compiling && total > 0
                            ) {
                                Icon(
                                    Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (translatedCount == 0) "Перевести" else "Перевести заново")
                            }
                        }

                        project.notes.forEach { note ->
                            val color = if (note.level == NoteLevel.WARNING) colors.tertiary else colors.onSurfaceVariant
                            Text(
                                text = note.text,
                                style = MaterialTheme.typography.labelMedium,
                                color = color
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Поиск по строкам") }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == EntryFilter.ALL,
                        onClick = { onFilter(EntryFilter.ALL) },
                        label = { Text("Все $total") }
                    )
                    FilterChip(
                        selected = filter == EntryFilter.PENDING,
                        onClick = { onFilter(EntryFilter.PENDING) },
                        label = { Text("Ожидают ${total - translatedCount}") }
                    )
                    FilterChip(
                        selected = filter == EntryFilter.TRANSLATED,
                        onClick = { onFilter(EntryFilter.TRANSLATED) },
                        label = { Text("Переведены") }
                    )
                    FilterChip(
                        selected = filter == EntryFilter.EDITED,
                        onClick = { onFilter(EntryFilter.EDITED) },
                        label = { Text("Изменены") }
                    )
                    FilterChip(
                        selected = filter == EntryFilter.ERRORS,
                        onClick = { onFilter(EntryFilter.ERRORS) },
                        label = { Text("Ошибки") }
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colors.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (total == 0) "Нет строк для перевода" else "Ничего не найдено",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface
                            )
                            Text(
                                text = if (total == 0) {
                                    "Этот APK хранит текст в ресурсах или коде, которые пока не поддержаны."
                                } else {
                                    "Измените фильтр или поисковый запрос."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(filtered, key = { it.id }) { entry: TextEntry ->
                StringCard(
                    entry = entry,
                    onTranslationChange = { onEdit(entry.id, it) }
                )
            }
        }
    }
}
