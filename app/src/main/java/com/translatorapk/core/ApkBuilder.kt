package com.translatorapk.core

import android.content.Context
import com.translatorapk.model.TranslationProject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkBuilder(private val context: Context) {

    data class BuildResult(val file: File, val packageName: String?)

    fun build(
        project: TranslationProject,
        renamePackage: Boolean = false,
        onProgress: (Float) -> Unit
    ): BuildResult {
        val original = File(project.apkPath)
        if (!original.exists()) throw IllegalStateException("Исходный APK не найден")

        val outputDir = File(context.filesDir, "output").apply { mkdirs() }
        val unsigned = File(outputDir, "unsigned-${System.currentTimeMillis()}.apk")
        val translated = project.entries.filter { it.translation.isNotBlank() }
        val byFile = translated
            .filter { !it.filePath.startsWith("pck://") }
            .groupBy { it.filePath }
        val pckGroups = translated
            .filter { it.filePath.startsWith("pck://") }
            .groupBy { it.filePath.removePrefix("pck://").substringBefore("!/") }
        val unityGroups = translated
            .filter { it.filePath.startsWith("unity://") }
            .groupBy { it.filePath.removePrefix("unity://").substringBeforeLast("!") }
        val fonts = project.fontPaths.toSet()
        var builtPackage = project.packageName

        ZipFile(original).use { zip ->
            val total = zip.size().coerceAtLeast(1)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(unsigned))).use { out ->
                var done = 0
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val pckChanged = pckGroups[entry.name]
                    val unityChanged = unityGroups[entry.name]
                    val changed = byFile[entry.name]
                    val bytes = if (entry.name == "AndroidManifest.xml" && renamePackage) {
                        val originalBytes = zip.getInputStream(entry).use { it.readBytes() }
                        val oldPackage = project.packageName
                            ?: throw IllegalStateException("Не удалось определить package name")
                        val renamed = ManifestEditor.renamePackage(originalBytes, ".translated")
                            ?: throw IllegalStateException("Не удалось изменить package name в AndroidManifest.xml")
                        builtPackage = renamed.newPackage
                        renamed.bytes
                    } else if (entry.name in fonts) {
                        fontBytes(entry.name)
                    } else if (unityChanged != null) {
                        val original = zip.getInputStream(entry).use { it.readBytes() }
                        rebuildUnity(original, unityChanged)
                    } else if (pckChanged != null) {
                        val original = zip.getInputStream(entry).use { it.readBytes() }
                        rebuildPck(original, pckChanged)
                    } else if (changed != null) {
                        val original = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == "resources.arsc") {
                            val translations = changed.mapNotNull { item ->
                                item.key.removePrefix("arsc:").toIntOrNull()?.let { it to item.translation }
                            }.toMap()
                            ArscEditor.apply(original, translations)
                        } else {
                            val kind = changed.first().kind
                            val translations = changed.associate { it.key to it.translation }
                            TextCodec.rebuild(kind, original.toString(Charsets.UTF_8), translations)
                                .toByteArray(Charsets.UTF_8)
                        }
                    } else {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }

                    val newEntry = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                        if (entry.method == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            crc = CRC32().apply { update(bytes) }.value
                        } else {
                            method = ZipEntry.DEFLATED
                        }
                    }
                    out.putNextEntry(newEntry)
                    out.write(bytes)
                    out.closeEntry()
                    done++
                    onProgress(done.toFloat() / total)
                }
            }
        }

        val baseName = project.apkName.removeSuffix(".apk").ifBlank { "app" }
        val suffix = if (renamePackage) "-translated" else ""
        val signed = File(outputDir, "$baseName-${project.targetLang}$suffix.apk")
        signed.delete()
        try {
            ApkSignerUtil.sign(context, unsigned, signed)
        } finally {
            unsigned.delete()
        }
        return BuildResult(signed, builtPackage)
    }

    private fun fontBytes(name: String): ByteArray {
        val lower = name.lowercase()
        val file = when {
            lower.contains("bold") && (lower.contains("italic") || lower.contains("oblique")) ->
                "NotoSans-BoldItalic.ttf"
            lower.contains("bold") || lower.contains("black") || lower.contains("heavy") ||
                lower.contains("semibold") || lower.contains("demibold") ->
                "NotoSans-Bold.ttf"
            lower.contains("italic") || lower.contains("oblique") ->
                "NotoSans-Italic.ttf"
            else -> "NotoSans-Regular.ttf"
        }
        return context.assets.open("fonts/$file").use { it.readBytes() }
    }

    private fun rebuildUnity(original: ByteArray, items: List<com.translatorapk.model.TextEntry>): ByteArray {
        val grouped = items.groupBy { it.filePath.substringAfterLast('!').toLongOrNull() ?: -1L }
        val replacements = grouped.mapNotNull { (pathId, entries) ->
            if (pathId < 0) null else pathId to UnityAsset.Replacement(
                kind = entries.first().kind,
                translations = entries.associate { it.key to it.translation }
            )
        }.toMap()
        return UnityAsset.apply(original, replacements)
            ?: throw IllegalStateException("Не удалось пересобрать Unity TextAsset")
    }

    private fun rebuildPck(original: ByteArray, items: List<com.translatorapk.model.TextEntry>): ByteArray {
        val pack = GodotPck.parse(original)
            ?: throw IllegalStateException("Не удалось разобрать Godot PCK")
        val groups = items.groupBy { it.filePath.removePrefix("pck://").substringAfter("!/") }
        val replacements = mutableMapOf<String, ByteArray>()
        for ((innerPath, entries) in groups) {
            val originalBytes = GodotPck.read(pack, innerPath) ?: continue
            val kind = entries.first().kind
            val translations = entries.associate { it.key to it.translation }
            replacements[innerPath] = TextCodec.rebuild(
                kind,
                originalBytes.toString(Charsets.UTF_8),
                translations
            ).toByteArray(Charsets.UTF_8)
        }
        return GodotPck.rebuild(pack, replacements)
            ?: throw IllegalStateException("PCK зашифрован и не может быть пересобран")
    }
}
