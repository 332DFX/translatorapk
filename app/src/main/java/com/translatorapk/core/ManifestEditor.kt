package com.translatorapk.core

import java.io.ByteArrayOutputStream

object ManifestEditor {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val NO_ENTRY = -1
    private const val TYPE_STRING = 0x03

    private const val FLAG_SORTED = 0x00000001
    private const val FLAG_UTF8 = 0x00000100

    data class Renamed(val bytes: ByteArray, val oldPackage: String, val newPackage: String)

    private data class Chunk(val start: Int, val type: Int, val headerSize: Int, val size: Int)

    private data class StringPool(
        val strings: MutableList<String>,
        val utf8: Boolean,
        val flags: Int,
        val styles: ByteArray,
        val styleOffsets: IntArray
    )

    fun renamePackage(manifest: ByteArray, suffix: String): Renamed? {
        if (manifest.size < 8 || u16(manifest, 0) != RES_XML_TYPE) return null
        val total = u32(manifest, 4)
        if (total !in 8..manifest.size) return null

        val chunks = mutableListOf<Chunk>()
        var pos = 0
        while (pos + 8 <= total) {
            val type = u16(manifest, pos)
            val headerSize = u16(manifest, pos + 2)
            val size = u32(manifest, pos + 4)
            if (size < 8 || pos + size > total) return null
            chunks += Chunk(pos, type, headerSize, size)
            pos += size
        }
        if (chunks.isEmpty() || pos != total) return null

        val poolChunk = chunks.firstOrNull { it.type == RES_STRING_POOL_TYPE } ?: return null
        val pool = readStringPool(manifest, poolChunk) ?: return null
        val oldPackage = findPackage(manifest, chunks, pool.strings) ?: return null
        val newPackage = if (oldPackage.endsWith(suffix)) oldPackage else oldPackage + suffix
        if (newPackage == oldPackage) return Renamed(manifest, oldPackage, newPackage)

        val added = mutableListOf<String>()
        val modified = mutableMapOf<Int, ByteArray>()
        for (chunk in chunks) {
            if (chunk.type != RES_XML_START_ELEMENT_TYPE) continue
            modified[chunk.start] = modifyElement(
                manifest.copyOfRange(chunk.start, chunk.start + chunk.size),
                pool.strings,
                oldPackage,
                newPackage,
                added
            )
        }

        val rebuiltPool = buildStringPool(
            pool.strings + added,
            pool.utf8,
            pool.flags,
            pool.styles,
            pool.styleOffsets
        )

        val out = ByteArrayOutputStream(manifest.size + added.size * 24)
        writeU16(out, RES_XML_TYPE)
        writeU16(out, 8)
        writeU32(out, 0)
        for (chunk in chunks) {
            when (chunk.type) {
                RES_STRING_POOL_TYPE -> out.write(rebuiltPool)
                RES_XML_START_ELEMENT_TYPE -> out.write(modified[chunk.start])
                else -> out.write(manifest, chunk.start, chunk.size)
            }
        }
        val result = out.toByteArray()
        putU32(result, 4, result.size)
        return Renamed(result, oldPackage, newPackage)
    }

    private fun findPackage(manifest: ByteArray, chunks: List<Chunk>, strings: List<String>): String? {
        for (chunk in chunks) {
            if (chunk.type != RES_XML_START_ELEMENT_TYPE) continue
            val headerSize = u16(manifest, chunk.start + 2)
            val name = strings.getOrNull(u32(manifest, chunk.start + headerSize + 4)) ?: continue
            if (name != "manifest") continue
            val attrStart = u16(manifest, chunk.start + headerSize + 8)
            val attrSize = u16(manifest, chunk.start + headerSize + 10)
            val count = u16(manifest, chunk.start + headerSize + 12)
            val base = chunk.start + headerSize + attrStart
            for (i in 0 until count) {
                val a = base + i * attrSize
                if (a + 20 > chunk.start + chunk.size) break
                val attrName = strings.getOrNull(u32(manifest, a + 4)) ?: continue
                if (attrName != "package") continue
                val raw = u32(manifest, a + 8)
                val type = manifest[a + 15].toInt() and 0xFF
                val data = u32(manifest, a + 16)
                val index = if (raw != NO_ENTRY) raw else if (type == TYPE_STRING) data else NO_ENTRY
                return strings.getOrNull(index)
            }
        }
        return null
    }

    private fun modifyElement(
        chunk: ByteArray,
        strings: List<String>,
        oldPackage: String,
        newPackage: String,
        added: MutableList<String>
    ): ByteArray {
        val headerSize = u16(chunk, 2)
        val element = strings.getOrNull(u32(chunk, headerSize + 4)) ?: return chunk
        val attrStart = u16(chunk, headerSize + 8)
        val attrSize = u16(chunk, headerSize + 10)
        val count = u16(chunk, headerSize + 12)
        val base = headerSize + attrStart
        for (i in 0 until count) {
            val a = base + i * attrSize
            if (a + 20 > chunk.size) break
            val attrName = strings.getOrNull(u32(chunk, a + 4)) ?: continue
            val raw = u32(chunk, a + 8)
            val type = chunk[a + 15].toInt() and 0xFF
            val data = u32(chunk, a + 16)
            val valueIndex = if (raw != NO_ENTRY) raw else if (type == TYPE_STRING) data else NO_ENTRY
            val value = strings.getOrNull(valueIndex) ?: continue
            val replaced = transform(element, attrName, value, oldPackage, newPackage) ?: continue
            if (replaced == value) continue
            val newIndex = strings.size + added.size
            added += replaced
            putU32(chunk, a + 8, newIndex)
            chunk[a + 12] = 8
            chunk[a + 13] = 0
            chunk[a + 14] = 0
            chunk[a + 15] = TYPE_STRING.toByte()
            putU32(chunk, a + 16, newIndex)
        }
        return chunk
    }

    private fun transform(
        element: String,
        attribute: String,
        value: String,
        oldPackage: String,
        newPackage: String
    ): String? {
        fun replacePackage(s: String): String = when {
            s == oldPackage -> newPackage
            s.startsWith("$oldPackage.") -> newPackage + s.substring(oldPackage.length)
            else -> s.replace(oldPackage, newPackage)
        }

        if (element == "manifest" && attribute == "package") return newPackage
        if (attribute == "authorities" || attribute == "taskAffinity" || attribute == "sharedUserId") {
            return replacePackage(value)
        }
        if (attribute == "permission" || attribute == "readPermission" || attribute == "writePermission") {
            return replacePackage(value)
        }
        if (attribute == "process") return if (value.startsWith(":")) value else replacePackage(value)

        if (attribute in CLASS_ATTRIBUTES) {
            return when {
                value.startsWith(".") -> oldPackage + value
                else -> value
            }
        }

        if (attribute == "name") {
            return when (element) {
                "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation" ->
                    if (value.startsWith(".")) oldPackage + value else value
                "permission", "uses-permission", "uses-permission-sdk-23" -> replacePackage(value)
                else -> null
            }
        }
        return null
    }

    private val CLASS_ATTRIBUTES = setOf(
        "targetActivity",
        "parentActivityName",
        "backupAgent",
        "manageSpaceActivity",
        "appComponentFactory",
        "zygotePreloadName"
    )

    private fun readStringPool(bytes: ByteArray, chunk: Chunk): StringPool? {
        if (chunk.size < 28 || chunk.headerSize < 28) return null
        val count = u32(bytes, chunk.start + 8)
        val styleCount = u32(bytes, chunk.start + 12)
        if (count < 0 || styleCount < 0 || count > 1_000_000 || styleCount > 1_000_000) return null
        val flags = u32(bytes, chunk.start + 16)
        val stringsStart = u32(bytes, chunk.start + 20)
        val stylesStart = u32(bytes, chunk.start + 24)
        val offsetsStart = chunk.start + chunk.headerSize
        if (offsetsStart + count * 4 > chunk.start + chunk.size) return null
        if (stringsStart !in 0..chunk.size) return null

        val utf8 = flags and FLAG_UTF8 != 0
        val strings = mutableListOf<String>()
        for (i in 0 until count) {
            val offset = u32(bytes, offsetsStart + i * 4)
            val at = chunk.start + stringsStart + offset
            if (at < chunk.start || at >= chunk.start + chunk.size) {
                strings += ""
                continue
            }
            strings += try {
                if (utf8) readUtf8(bytes, at) else readUtf16(bytes, at)
            } catch (_: Exception) {
                ""
            }
        }

        val styleOffsets = IntArray(styleCount)
        if (styleCount > 0 && stylesStart in 1..chunk.size) {
            val stylesAt = chunk.start + stylesStart
            for (i in 0 until styleCount) {
                styleOffsets[i] = if (stylesAt + i * 4 + 4 <= chunk.start + chunk.size) {
                    u32(bytes, stylesAt + i * 4)
                } else 0
            }
        }
        val styles = if (styleCount > 0 && stylesStart in 1 until chunk.size) {
            bytes.copyOfRange(chunk.start + stylesStart, chunk.start + chunk.size)
        } else {
            ByteArray(0)
        }
        return StringPool(strings, utf8, flags, styles, styleOffsets)
    }

    private fun readUtf8(bytes: ByteArray, offset: Int): String {
        val (byteLength, afterByteLength) = readUtf8Length(bytes, offset)
        val (_, afterCharLength) = readUtf8Length(bytes, afterByteLength)
        return String(bytes, afterCharLength, byteLength, Charsets.UTF_8)
    }

    private fun readUtf8Length(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val first = bytes[offset].toInt() and 0xFF
        return if (first and 0x80 != 0) {
            (((first and 0x7F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)) to (offset + 2)
        } else {
            first to (offset + 1)
        }
    }

    private fun readUtf16(bytes: ByteArray, offset: Int): String {
        var length = u16(bytes, offset)
        var at = offset + 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or u16(bytes, at)
            at += 2
        }
        val chars = CharArray(length) { i -> u16(bytes, at + i * 2).toChar() }
        return String(chars)
    }

    private fun buildStringPool(
        strings: List<String>,
        utf8: Boolean,
        flags: Int,
        styles: ByteArray,
        styleOffsets: IntArray
    ): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { index, value ->
            offsets[index] = data.size()
            if (utf8) writeUtf8(data, value) else writeUtf16(data, value)
        }
        val stringCount = strings.size
        val stringsStart = 28 + stringCount * 4
        val stylesStart = if (styleOffsets.isNotEmpty() && styles.isNotEmpty()) stringsStart + data.size() else 0
        val total = stringsStart + data.size() + styles.size
        val out = ByteArrayOutputStream(total)
        writeU16(out, RES_STRING_POOL_TYPE)
        writeU16(out, 28)
        writeU32(out, total)
        writeU32(out, stringCount)
        writeU32(out, styleOffsets.size)
        writeU32(out, flags and FLAG_SORTED.inv())
        writeU32(out, stringsStart)
        writeU32(out, stylesStart)
        offsets.forEach { writeU32(out, it) }
        data.writeTo(out)
        if (styles.isNotEmpty()) out.write(styles)
        return out.toByteArray()
    }

    private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        writeUtf8Length(out, data.size)
        writeUtf8Length(out, value.length)
        out.write(data)
        out.write(0)
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        if (length > 0x7F) {
            out.write(0x80 or (length ushr 8))
            out.write(length and 0xFF)
        } else {
            out.write(length)
        }
    }

    private fun writeUtf16(out: ByteArrayOutputStream, value: String) {
        val length = value.length
        if (length > 0x7FFF) {
            writeU16(out, 0x8000 or (length ushr 16))
            writeU16(out, length and 0xFFFF)
        } else {
            writeU16(out, length)
        }
        for (char in value) writeU16(out, char.code)
        writeU16(out, 0)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
