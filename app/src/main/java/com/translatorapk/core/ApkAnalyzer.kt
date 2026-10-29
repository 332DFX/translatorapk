package com.translatorapk.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.translatorapk.model.AnalysisNote
import com.translatorapk.model.EngineType
import com.translatorapk.model.EntryKind
import com.translatorapk.model.EntryStatus
import com.translatorapk.model.NoteLevel
import com.translatorapk.model.TextEntry
import com.translatorapk.model.TranslationProject
import java.io.File
import java.util.zip.ZipFile

class ApkAnalyzer(private val context: Context) {

    fun importToCache(uri: Uri): File {
        val name = displayName(uri) ?: "app.apk"
        val safeName = name.replace(Regex("""[^\w.\- ]"""), "_")
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val target = File(dir, System.currentTimeMillis().toString() + "_" + safeName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось открыть выбранный файл")
        return target
    }

    fun analyze(
        file: File,
        sourceLang: String?,
        targetLang: String,
        onStage: (String) -> Unit = {}
    ): TranslationProject {
        onStage("Чтение APK")
        val entries = mutableListOf<TextEntry>()
        val notes = mutableListOf<AnalysisNote>()
        var engine = EngineType.UNKNOWN

        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            engine = detectEngine(names)
            onStage("Движок: ${engine.title}")

            if (engine == EngineType.NATIVE) {
                notes += AnalysisNote(
                    NoteLevel.WARNING,
                    "Строки в resources.arsc (обычные res/values) в этой версии не изменяются. " +
                        "Переводимые текстовые assets обрабатываются, но для полного перевода нужен apktool-пайплайн."
                )
            }
            if (engine == EngineType.UNITY) {
                notes += AnalysisNote(
                    NoteLevel.WARNING,
                    "Unity AssetBundle и IL2CPP пока не изменяются. Обрабатываются только текстовые ассеты (JSON/CSV/TXT)."
                )
            }
            if (engine == EngineType.GODOT) {
                notes += AnalysisNote(
                    NoteLevel.WARNING,
                    "Файлы внутри PCK пока не распаковываются. Обрабатываются только текстовые файлы вне PCK."
                )
            }
            if (engine == EngineType.UNREAL) {
                notes += AnalysisNote(
                    NoteLevel.WARNING,
                    "Бинарные .locres/.pak пока не изменяются. Обрабатываются только текстовые файлы."
                )
            }

            val candidates = names.filter { name ->
                !name.endsWith("/") &&
                    !name.startsWith("META-INF/") &&
                    !name.startsWith("lib/") &&
                    !name.startsWith("res/") &&
                    !name.startsWith("assets/bin/Data/") &&
                    kindFor(name) != null &&
                    (zip.getEntry(name)?.size?.let { it in 1L..MAX_FILE_BYTES } == true)
            }

            onStage("Извлечение текста")
            var total = 0
            for (name in candidates) {
                if (total >= MAX_ENTRIES) break
                val kind = kindFor(name) ?: continue
                val zipEntry = zip.getEntry(name) ?: continue
                val bytes = runCatching { zip.getInputStream(zipEntry).use { it.readBytes() } }.getOrNull() ?: continue
                if (!TextCodec.isProbablyText(bytes)) continue
                val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: continue
                val parsed = runCatching { TextCodec.parse(kind, text) }.getOrElse { continue }
                for (item in parsed) {
                    if (total >= MAX_ENTRIES) break
                    total++
                    entries += TextEntry(
                        id = "$name::${item.key}",
                        filePath = name,
                        key = item.key,
                        source = item.text,
                        kind = kind,
                        status = EntryStatus.PENDING
                    )
                }
            }
            if (entries.isEmpty()) {
                notes += AnalysisNote(
                    NoteLevel.INFO,
                    "Текстовых файлов, которые можно перевести в этой версии, не найдено. " +
                        "Приложение может хранить строки в ресурсах или скомпилированном коде."
                )
            } else {
                notes += AnalysisNote(NoteLevel.INFO, "Найдено строк: ${entries.size} в ${candidates.size} файлах.")
            }
        }

        val packageName = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        }.getOrNull()

        return TranslationProject(
            apkName = file.name.substringAfter('_', file.name),
            apkPath = file.absolutePath,
            packageName = packageName,
            engine = engine,
            sourceLang = sourceLang?.takeUnless { it == "auto" },
            targetLang = targetLang,
            entries = entries,
            notes = notes
        )
    }

    private fun detectEngine(names: List<String>): EngineType {
        return when {
            names.any { it.endsWith(".pck") || it.contains("libgodot", ignoreCase = true) } -> EngineType.GODOT
            names.any { it.startsWith("assets/bin/Data/") || it.endsWith("libunity.so") } -> EngineType.UNITY
            names.any { it.endsWith(".locres") || it.endsWith(".pak") || it.endsWith(".utoc") || it.endsWith(".ucas") } -> EngineType.UNREAL
            names.contains("resources.arsc") -> EngineType.NATIVE
            names.contains("classes.dex") -> EngineType.NATIVE
            else -> EngineType.GENERIC
        }
    }

    private fun kindFor(name: String): EntryKind? = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> EntryKind.JSON
        "csv", "tsv" -> EntryKind.CSV
        "txt", "properties", "ini", "cfg", "conf", "yml", "yaml", "lines" -> EntryKind.LINES
        else -> null
    }

    private fun displayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024L
        const val MAX_ENTRIES = 6000
    }
}
