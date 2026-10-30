package com.translatorapk.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.translatorapk.model.AnalysisNote
import com.translatorapk.core.engines.EngineRegistry
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
        val fontPaths = mutableListOf<String>()
        var engine = EngineType.UNKNOWN

        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val adapter = EngineRegistry.detect(names)
            engine = adapter.type
            onStage("Движок: ${engine.title}")
            adapter.limitation()?.let { limitation ->
                notes += AnalysisNote(NoteLevel.WARNING, limitation)
            }

            val candidates = names.filter { name ->
                !name.endsWith("/") &&
                    !name.startsWith("META-INF/") &&
                    !name.startsWith("lib/") &&
                    !name.startsWith("res/") &&
                    !name.startsWith("assets/bin/Data/") &&
                    !isGodotCache(name) &&
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
                val parsed = runCatching { TextCodec.parse(kind, text) }.getOrNull() ?: continue
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
            if (targetLang in CYRILLIC_TARGETS) {
                onStage("Проверка шрифтов")
                for (name in names) {
                    if (!name.endsWith(".ttf", true) && !name.endsWith(".otf", true)) continue
                    if (iconLike(name)) continue
                    val font = zip.getEntry(name) ?: continue
                    if (font.size !in 1L..MAX_FONT_BYTES) continue
                    val fontBytes = runCatching {
                        zip.getInputStream(font).use { it.readBytes() }
                    }.getOrNull() ?: continue
                    if (FontInspector.missingCyrillic(fontBytes)) {
                        fontPaths += name
                    }
                }
                if (fontPaths.isNotEmpty()) {
                    notes += AnalysisNote(
                        NoteLevel.INFO,
                        "Шрифтов без кириллицы: ${fontPaths.size}. Они будут заменены на Noto Sans."
                    )
                }
            }

            val arsc = zip.getEntry("resources.arsc")
            if (arsc != null && total < MAX_ENTRIES && arsc.size in 1L..MAX_ARSC_BYTES) {
                onStage("Разбор resources.arsc")
                val arscBytes = runCatching {
                    zip.getInputStream(arsc).use { it.readBytes() }
                }.getOrNull()
                if (arscBytes != null) {
                    val extracted = runCatching {
                        ArscEditor.extract(arscBytes, targetLang, MAX_ENTRIES - total)
                    }.getOrDefault(emptyList())
                    for (item in extracted) {
                        entries += TextEntry(
                            id = "resources.arsc::arsc:${item.index}",
                            filePath = "resources.arsc",
                            key = "arsc:${item.index}",
                            source = item.text,
                            kind = EntryKind.ARSC,
                            status = EntryStatus.PENDING
                        )
                        total++
                    }
                    if (extracted.isNotEmpty()) {
                        notes += AnalysisNote(
                            NoteLevel.INFO,
                            "Найдено строк Android-ресурсов: ${extracted.size}"
                        )
                    }
                }
            }

            if (engine == EngineType.UNITY && total < MAX_ENTRIES) {
                onStage("Разбор Unity TextAsset")
                var unityFiles = 0
                var unityStrings = 0
                val unityCandidates = names.filter { looksLikeUnitySerialized(it) }
                for (name in unityCandidates) {
                    if (total >= MAX_ENTRIES) break
                    val unityEntry = zip.getEntry(name) ?: continue
                    if (unityEntry.size !in 1L..MAX_UNITY_BYTES) continue
                    val unityBytes = runCatching {
                        zip.getInputStream(unityEntry).use { it.readBytes() }
                    }.getOrNull() ?: continue
                    val extracted = runCatching {
                        UnityAsset.extract(unityBytes, MAX_ENTRIES - total)
                    }.getOrNull() ?: continue
                    if (extracted.isNotEmpty()) unityFiles++
                    for (asset in extracted) {
                        for (item in asset.items) {
                            if (total >= MAX_ENTRIES) break
                            total++
                            unityStrings++
                            entries += TextEntry(
                                id = "unity://$name!${asset.pathId}::${item.key}",
                                filePath = "unity://$name!${asset.pathId}",
                                key = item.key,
                                source = item.text,
                                kind = asset.kind,
                                status = EntryStatus.PENDING
                            )
                        }
                    }
                }
                if (unityStrings > 0) {
                    notes += AnalysisNote(
                        NoteLevel.INFO,
                        "Unity TextAsset: файлов $unityFiles, строк $unityStrings."
                    )
                }
                val hasBundle = names.any { it.endsWith("data.unity3d") || it.endsWith(".bundle") }
                if (hasBundle) {
                    notes += AnalysisNote(
                        NoteLevel.WARNING,
                        "AssetBundle/data.unity3d пока не изменяются. " +
                            "Переводятся только TextAsset в несжатых serialized-файлах."
                    )
                }
            }

            val pckNames = names.filter { it.endsWith(".pck", true) }
            for (pckName in pckNames) {
                if (total >= MAX_ENTRIES) break
                val pckEntry = zip.getEntry(pckName) ?: continue
                if (pckEntry.size !in 1L..MAX_PCK_BYTES) continue
                onStage("Разбор Godot PCK: ${pckName.substringAfterLast('/')}")
                val pckBytes = runCatching {
                    zip.getInputStream(pckEntry).use { it.readBytes() }
                }.getOrNull() ?: continue
                val pack = runCatching { GodotPck.parse(pckBytes) }.getOrNull()
                if (pack == null) {
                    notes += AnalysisNote(
                        NoteLevel.WARNING,
                        "$pckName: PCK зашифрован или имеет неподдерживаемый формат."
                    )
                    continue
                }
                var pckStrings = 0
                for (inner in pack.entries) {
                    if (total >= MAX_ENTRIES) break
                    if (inner.flags and 1 != 0) continue
                    if (isGodotCache(inner.path)) continue
                    if (inner.size !in 1L..MAX_FILE_BYTES) continue
                    val kind = kindFor(inner.path) ?: continue
                    val innerBytes = GodotPck.read(pack, inner.path) ?: continue
                    if (!TextCodec.isProbablyText(innerBytes)) continue
                    val text = runCatching { innerBytes.toString(Charsets.UTF_8) }.getOrNull() ?: continue
                    val parsed = runCatching { TextCodec.parse(kind, text) }.getOrNull() ?: continue
                    for (item in parsed) {
                        if (total >= MAX_ENTRIES) break
                        total++
                        pckStrings++
                        entries += TextEntry(
                            id = "$pckName!/${inner.path}::${item.key}",
                            filePath = "pck://$pckName!/${inner.path}",
                            key = item.key,
                            source = item.text,
                            kind = kind,
                            status = EntryStatus.PENDING
                        )
                    }
                }
                notes += AnalysisNote(
                    NoteLevel.INFO,
                    "$pckName: файлов ${pack.entries.size}, строк $pckStrings. " +
                        "Бинарные .translation пока не изменяются."
                )
            }

            if (entries.isEmpty()) {
                notes += AnalysisNote(
                    NoteLevel.INFO,
                    "Текстовых файлов, которые можно перевести в этой версии, не найдено. " +
                        "Приложение может хранить строки в ресурсах или скомпилированном коде."
                )
            } else {
                notes += AnalysisNote(NoteLevel.INFO, "Найдено строк: ${entries.size}")
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
            notes = notes,
            fontPaths = fontPaths
        )
    }

    private fun isGodotCache(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.contains("/.godot/") ||
            normalized.startsWith(".godot/") ||
            normalized.contains("/.import/") ||
            normalized.startsWith(".import/") ||
            normalized.endsWith("global_script_class_cache.cfg")
    }

    private fun looksLikeUnitySerialized(name: String): Boolean {
        if (!name.startsWith("assets/bin/Data/")) return false
        val base = name.substringAfterLast('/')
        if (base.endsWith(".resource") || base.endsWith(".resS") ||
            base.endsWith(".unity3d") || base.endsWith(".bundle")
        ) return false
        return base.endsWith(".assets") ||
            base == "globalgamemanagers" ||
            base == "globalgamemanagers.assets" ||
            base.startsWith("level") ||
            base.contains("sharedassets") ||
            base.endsWith(".serialized")
    }

    private fun iconLike(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(
            "icon", "awesome", "material", "glyph", "symbol", "emoji",
            "fontello", "icomoon", "fa-", "brands", "social"
        ).any { lower.contains(it) }
    }

    private fun kindFor(name: String): EntryKind? = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> EntryKind.JSON
        "po", "pot" -> EntryKind.PO
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
        const val MAX_ARSC_BYTES = 32 * 1024 * 1024L
        const val MAX_FONT_BYTES = 12 * 1024 * 1024L
        const val MAX_PCK_BYTES = 96 * 1024 * 1024L
        const val MAX_UNITY_BYTES = 96 * 1024 * 1024L
        const val MAX_ENTRIES = 6000
        val CYRILLIC_TARGETS = setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn")
    }
}
