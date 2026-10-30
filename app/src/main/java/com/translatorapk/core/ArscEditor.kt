package com.translatorapk.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object ArscEditor {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val TYPE_STRING = 0x03
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val UTF8_FLAG = 0x100

    data class Replacement(val index: Int, val text: String)

    fun extract(bytes: ByteArray, targetLang: String?, limit: Int): List<Replacement> {
        if (bytes.size < 12) return emptyList()
        if (u16(bytes, 0) != RES_TABLE_TYPE) return emptyList()
        val tableSize = minOf(u32(bytes, 4), bytes.size)
        val tableHeader = u16(bytes, 2)
        val pool = parsePool(bytes, tableHeader) ?: return emptyList()

        val found = LinkedHashMap<Int, String>()
        var offset = tableHeader
        while (offset + 8 <= tableSize) {
            val type = u16(bytes, offset)
            val headerSize = u16(bytes, offset + 2)
            val chunkSize = u32(bytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > tableSize) break
            if (type == RES_TABLE_PACKAGE_TYPE) {
                extractPackage(bytes, offset, headerSize, chunkSize, pool.strings, found, targetLang, limit)
            }
            offset += chunkSize
        }
        return found.map { Replacement(it.key, it.value) }
    }

    fun apply(bytes: ByteArray, translations: Map<Int, String>): ByteArray {
        if (translations.isEmpty()) return bytes
        if (bytes.size < 12 || u16(bytes, 0) != RES_TABLE_TYPE) return bytes
        val tableSize = minOf(u32(bytes, 4), bytes.size)
        val tableHeader = u16(bytes, 2)
        val pool = parsePool(bytes, tableHeader) ?: return bytes
        if (translations.keys.any { it !in pool.strings.indices }) return bytes

        val values = pool.strings.toMutableList()
        translations.forEach { (index, text) -> values[index] = text }
        val newPool = buildPool(values, pool)
        val rest = bytes.copyOfRange(tableHeader + pool.size, tableSize)
        val out = ByteArray(tableHeader + newPool.size + rest.size)
        System.arraycopy(bytes, 0, out, 0, tableHeader)
        System.arraycopy(newPool, 0, out, tableHeader, newPool.size)
        System.arraycopy(rest, 0, out, tableHeader + newPool.size, rest.size)
        writeU32(out, 4, out.size)
        return out
    }

    private fun extractPackage(
        bytes: ByteArray,
        start: Int,
        headerSize: Int,
        size: Int,
        globalStrings: List<String>,
        found: LinkedHashMap<Int, String>,
        targetLang: String?,
        limit: Int
    ) {
        val end = start + size
        if (start + 12 > end) return
        val typeStringsOffset = u32(bytes, start + 268)
        val typePool = parsePool(bytes, start + typeStringsOffset) ?: return
        val typeNames = typePool.strings

        var offset = start + headerSize
        while (offset + 20 <= end && found.size < limit) {
            val type = u16(bytes, offset)
            val chunkHeader = u16(bytes, offset + 2)
            val chunkSize = u32(bytes, offset + 4)
            if (chunkSize < 8 || offset + chunkSize > end) break
            if (type == RES_TABLE_TYPE_TYPE) {
                val typeId = u8(bytes, offset + 8)
                val entryCount = u32(bytes, offset + 12)
                val entriesStart = u32(bytes, offset + 16)
                val configStart = offset + 20
                val configSize = u32(bytes, configStart)
                val isDefault = configSize >= 4 &&
                    (configSize == 4 || (configStart + configSize <= end &&
                        (4 until configSize).all { bytes[configStart + it] == 0.toByte() }))
                val name = typeNames.getOrNull(typeId - 1)
                if (isDefault && name == "string") {
                    val flags = u8(bytes, offset + 9)
                    if (flags and (FLAG_SPARSE or FLAG_OFFSET16) != 0) {
                        offset += chunkSize
                        continue
                    }
                    val offsetsAt = offset + chunkHeader
                    if (offsetsAt + entryCount * 4 > end) {
                        offset += chunkSize
                        continue
                    }
                    val entriesBase = offset + entriesStart
                    for (i in 0 until entryCount) {
                        if (found.size >= limit) break
                        val entryOffset = u32(bytes, offsetsAt + i * 4)
                        if (entryOffset == 0xFFFFFFFF.toInt()) continue
                        val entry = entriesBase + entryOffset
                        if (entry + 8 > end) continue
                        val entrySize = u16(bytes, entry)
                        val entryFlags = u16(bytes, entry + 2)
                        if (entryFlags and ENTRY_FLAG_COMPLEX != 0) continue
                        val value = entry + entrySize
                        if (value + 8 > end) continue
                        val dataType = u8(bytes, value + 3)
                        val data = u32(bytes, value + 4)
                        if (dataType != TYPE_STRING || data !in globalStrings.indices) continue
                        val text = globalStrings[data]
                        if (!shouldTranslate(text, targetLang)) continue
                        found.putIfAbsent(data, text)
                    }
                }
            }
            offset += chunkSize
        }
    }

    private fun shouldTranslate(text: String, targetLang: String?): Boolean {
        if (text.isBlank() || text.length > 4000) return false
        if (!text.any { it.isLetter() }) return false
        if (text.startsWith("http://", true) || text.startsWith("https://", true) || text.contains("://")) return false
        if (text.startsWith("data:")) return false
        if (text.matches(Regex("""^[\w.\-]+\.(png|jpg|jpeg|webp|gif|ogg|mp3|wav|ttf|otf|xml|json|arsc|dex)$""", RegexOption.IGNORE_CASE))) return false
        if (text.matches(Regex("""^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+){2,}$"""))) return false
        if (targetLang == "ru") {
            val letters = text.count { it.isLetter() }
            val cyrillic = text.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            if (letters > 0 && cyrillic * 100 / letters >= 35) return false
        }
        return true
    }

    private data class Pool(
        val start: Int,
        val size: Int,
        val headerSize: Int,
        val stringCount: Int,
        val styleCount: Int,
        val flags: Int,
        val stringsStart: Int,
        val stylesStart: Int,
        val strings: List<String>,
        val styleOffsets: IntArray,
        val styleData: ByteArray
    )

    private fun parsePool(bytes: ByteArray, start: Int): Pool? {
        if (start + 28 > bytes.size) return null
        if (u16(bytes, start) != RES_STRING_POOL_TYPE) return null
        val headerSize = u16(bytes, start + 2)
        val size = u32(bytes, start + 4)
        if (size < 28 || start + size > bytes.size) return null
        val stringCount = u32(bytes, start + 8)
        val styleCount = u32(bytes, start + 12)
        if (stringCount < 0 || styleCount < 0) return null
        if (start + headerSize + stringCount * 4 + styleCount * 4 > start + size) return null
        val flags = u32(bytes, start + 16)
        val stringsStart = u32(bytes, start + 20)
        val stylesStart = u32(bytes, start + 24)
        val isUtf8 = flags and UTF8_FLAG != 0

        val offsetsAt = start + headerSize
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val offset = u32(bytes, offsetsAt + i * 4)
            val at = start + stringsStart + offset
            strings += runCatching { readString(bytes, at, isUtf8) }.getOrDefault("")
        }
        val styleOffsetsAt = offsetsAt + stringCount * 4
        val styleOffsets = IntArray(styleCount) { u32(bytes, styleOffsetsAt + it * 4) }
        val styleData = if (styleCount > 0 && stylesStart > 0) {
            bytes.copyOfRange(start + stylesStart, start + size)
        } else {
            ByteArray(0)
        }
        return Pool(start, size, headerSize, stringCount, styleCount, flags, stringsStart, stylesStart, strings, styleOffsets, styleData)
    }

    private fun readString(bytes: ByteArray, start: Int, utf8: Boolean): String {
        var p = start
        return if (utf8) {
            var len = u8(bytes, p++)
            if (len and 0x80 != 0) {
                len = ((len and 0x7F) shl 8) or u8(bytes, p++)
            }
            var len16 = u8(bytes, p++)
            if (len16 and 0x80 != 0) {
                p++
            }
            String(bytes, p, len, StandardCharsets.UTF_8)
        } else {
            var len = u16(bytes, p)
            p += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or u16(bytes, p)
                p += 2
            }
            String(bytes, p, len * 2, StandardCharsets.UTF_16LE)
        }
    }

    private fun buildPool(values: List<String>, old: Pool): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(values.size)
        values.forEachIndexed { index, value ->
            offsets[index] = data.size()
            val utf8 = value.toByteArray(StandardCharsets.UTF_8)
            val units = value.length
            if (utf8.size > 0x7F) {
                data.write((utf8.size shr 8) or 0x80)
                data.write(utf8.size and 0xFF)
            } else {
                data.write(utf8.size)
            }
            if (units > 0x7F) {
                data.write((units shr 8) or 0x80)
                data.write(units and 0xFF)
            } else {
                data.write(units)
            }
            data.write(utf8)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val stringData = data.toByteArray()

        val offsetsBytes = values.size * 4
        val styleOffsetsBytes = old.styleCount * 4
        val headerSize = 28
        val stringsStart = headerSize + offsetsBytes + styleOffsetsBytes
        val stylesStart = if (old.styleCount > 0) align4(stringsStart + stringData.size) else 0
        val total = if (old.styleCount > 0) stylesStart + old.styleData.size else stringsStart + stringData.size

        val out = ByteArray(total)
        writeU16(out, 0, RES_STRING_POOL_TYPE)
        writeU16(out, 2, headerSize)
        writeU32(out, 4, total)
        writeU32(out, 8, values.size)
        writeU32(out, 12, old.styleCount)
        writeU32(out, 16, UTF8_FLAG)
        writeU32(out, 20, stringsStart)
        writeU32(out, 24, stylesStart)
        offsets.forEachIndexed { i, value -> writeU32(out, headerSize + i * 4, value) }
        if (old.styleCount > 0) {
            old.styleOffsets.forEachIndexed { i, value ->
                writeU32(out, headerSize + offsetsBytes + i * 4, value)
            }
        }
        System.arraycopy(stringData, 0, out, stringsStart, stringData.size)
        if (old.styleCount > 0 && old.styleData.isNotEmpty()) {
            System.arraycopy(old.styleData, 0, out, stylesStart, old.styleData.size)
        }
        return out
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
