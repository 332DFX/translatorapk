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

    fun build(project: TranslationProject, onProgress: (Float) -> Unit): File {
        val original = File(project.apkPath)
        if (!original.exists()) throw IllegalStateException("Исходный APK не найден")

        val outputDir = File(context.filesDir, "output").apply { mkdirs() }
        val unsigned = File(outputDir, "unsigned-${System.currentTimeMillis()}.apk")
        val byFile = project.entries
            .filter { it.translation.isNotBlank() }
            .groupBy { it.filePath }

        ZipFile(original).use { zip ->
            val total = zip.size().coerceAtLeast(1)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(unsigned))).use { out ->
                var done = 0
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val changed = byFile[entry.name]
                    val bytes = if (changed != null) {
                        val originalText = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        val kind = changed.first().kind
                        val translations = changed.associate { it.key to it.translation }
                        TextCodec.rebuild(kind, originalText, translations).toByteArray(Charsets.UTF_8)
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
        val signed = File(outputDir, "$baseName-${project.targetLang}.apk")
        signed.delete()
        try {
            ApkSignerUtil.sign(context, unsigned, signed)
        } finally {
            unsigned.delete()
        }
        return signed
    }
}
