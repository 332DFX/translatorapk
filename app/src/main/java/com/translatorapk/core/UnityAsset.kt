package com.translatorapk.core

import com.translatorapk.model.EntryKind
import java.io.ByteArrayOutputStream

object UnityAsset {

    private const val TEXT_ASSET_CLASS_ID = 49
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

    data class Extracted(
        val pathId: Long,
        val kind: EntryKind,
        val items: List<ParsedItem>
    )

    data class Replacement(
        val kind: EntryKind,
        val translations: Map<String, String>
    )

    fun extract(bytes: ByteArray, limit: Int): List<Extracted>? {
        val file = parse(bytes) ?: return null
        val result = mutableListOf<Extracted>()
        for (obj in file.objects) {
            if (result.size >= limit) break
            if (obj.classId != TEXT_ASSET_CLASS_ID) continue
            if (obj.byteSize !in 1..MAX_SCRIPT_BYTES) continue
            val data = bytes.copyOfRange(obj.offset.toInt(), (obj.offset + obj.byteSize).toInt())
            val textAsset = parseTextAsset(data, file.littleEndian) ?: continue
            if (!TextCodec.isProbablyText(textAsset.script.toByteArray(Charsets.UTF_8))) continue
            val kind = kindFor(textAsset.name, textAsset.script) ?: continue
            val parsed = runCatching { TextCodec.parse(kind, textAsset.script) }.getOrNull() ?: continue
            if (parsed.isEmpty()) continue
            result += Extracted(obj.pathId, kind, parsed)
        }
        return result
    }

    fun apply(bytes: ByteArray, replacements: Map<Long, Replacement>): ByteArray? {
        if (replacements.isEmpty()) return bytes
        val file = parse(bytes) ?: return null
        val dataBuffer = ByteArrayOutputStream()
        val newObjects = HashMap<Long, ByteArray>()

        for (obj in file.objects) {
            val original = bytes.copyOfRange(obj.offset.toInt(), (obj.offset + obj.byteSize).toInt())
            val replacement = replacements[obj.pathId]
            if (replacement != null && obj.classId == TEXT_ASSET_CLASS_ID) {
                val textAsset = parseTextAsset(original, file.littleEndian)
                if (textAsset != null) {
                    val script = TextCodec.rebuild(replacement.kind, textAsset.script, replacement.translations)
                    rebuildTextAsset(textAsset, script, file.littleEndian)?.let { newObjects[obj.pathId] = it }
                }
            }
        }

        val metadata = bytes.copyOfRange(file.metadataStart, file.dataOffset.toInt())
        val offsets = HashMap<Long, Pair<Int, Int>>()
        for (obj in file.objects) {
            val data = newObjects[obj.pathId] ?: bytes.copyOfRange(obj.offset.toInt(), (obj.offset + obj.byteSize).toInt())
            val start = dataBuffer.size()
            dataBuffer.write(data)
            while (dataBuffer.size() % 8 != 0) dataBuffer.write(0)
            offsets[obj.pathId] = start to data.size
        }

        for (obj in file.objects) {
            val (start, size) = offsets[obj.pathId] ?: continue
            if (file.version >= 22) {
                writeU64(metadata, obj.byteStartPos - file.metadataStart, start.toLong(), file.littleEndian)
            } else {
                writeU32(metadata, obj.byteStartPos - file.metadataStart, start, file.littleEndian)
            }
            writeU32(metadata, obj.byteSizePos - file.metadataStart, size, file.littleEndian)
        }

        val data = dataBuffer.toByteArray()
        val out = ByteArray(file.metadataStart + metadata.size + data.size)
        System.arraycopy(bytes, 0, out, 0, file.metadataStart)
        System.arraycopy(metadata, 0, out, file.metadataStart, metadata.size)
        System.arraycopy(data, 0, out, file.metadataStart + metadata.size, data.size)
        if (file.version >= 22) {
            writeU32(out, 4, out.size, false)
            writeU64(out, 24, out.size.toLong(), false)
        } else {
            writeU32(out, 4, out.size, false)
        }
        return out
    }

    private data class TextAsset(
        val name: String,
        val script: String,
        val trailing: ByteArray
    )

    private fun parseTextAsset(bytes: ByteArray, littleEndian: Boolean): TextAsset? {
        val reader = Reader(bytes, littleEndian, 0, bytes.size)
        return try {
            val name = reader.readString()
            val script = reader.readString()
            val trailing = bytes.copyOfRange(reader.pos, bytes.size)
            TextAsset(name, script, trailing)
        } catch (e: Exception) {
            null
        }
    }

    private fun rebuildTextAsset(asset: TextAsset, script: String, littleEndian: Boolean): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            writeUnityString(out, asset.name, littleEndian)
            writeUnityString(out, script, littleEndian)
            out.write(asset.trailing)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeUnityString(out: ByteArrayOutputStream, value: String, littleEndian: Boolean) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU32(out, bytes.size, littleEndian)
        out.write(bytes)
        while (out.size() % 4 != 0) out.write(0)
    }

    private fun kindFor(name: String, script: String): EntryKind? {
        val lower = name.lowercase()
        when {
            lower.endsWith(".json") || lower.contains("json") -> return EntryKind.JSON
            lower.endsWith(".csv") || lower.endsWith(".tsv") -> return EntryKind.CSV
            lower.contains("csv") || lower.contains("localization") || lower.contains("locale") ->
                return EntryKind.CSV
        }
        val sample = script.removePrefix("\uFEFF").trimStart()
        if (sample.startsWith("{") || sample.startsWith("[")) return EntryKind.JSON
        val first = sample.lineSequence().firstOrNull { it.isNotBlank() } ?: return EntryKind.LINES
        if (first.contains('\t')) return EntryKind.CSV
        val best = listOf(',', ';').maxByOrNull { c -> first.count { it == c } } ?: return EntryKind.LINES
        return if (first.count { it == best } > 0) EntryKind.CSV else EntryKind.LINES
    }

    private data class ObjectEntry(
        val pathId: Long,
        val byteStart: Long,
        val byteSize: Int,
        val byteStartPos: Int,
        val byteSizePos: Int,
        val classId: Int,
        val offset: Long
    )

    private data class File(
        val version: Int,
        val littleEndian: Boolean,
        val metadataStart: Int,
        val dataOffset: Long,
        val objects: List<ObjectEntry>
    )

    private fun parse(bytes: ByteArray): File? {
        if (bytes.size < 48) return null
        val head = Reader(bytes, false, 0, bytes.size)
        return try {
            head.u32()
            head.u32()
            val version = head.i32()
            if (version < 9 || version > 30) return null
            val firstDataOffset = head.u32().toLong()
            val endianFlag = head.u8()
            head.bytes(3)
            var metadataStart = 20
            var dataOffset = firstDataOffset
            if (version >= 22) {
                head.i32()
                val fileSize64 = head.i64()
                dataOffset = head.i64()
                head.i64()
                if (fileSize64 > bytes.size) return null
                metadataStart = 48
            }
            if (dataOffset <= metadataStart || dataOffset > bytes.size) return null
            val littleEndian = endianFlag == 0
            val meta = Reader(bytes, littleEndian, metadataStart, dataOffset.toInt())
            if (version >= 7) meta.readCString()
            if (version >= 8) meta.i32()
            val typeTree = if (version >= 13) meta.u8() != 0 else true
            val typeCount = meta.i32()
            if (typeCount < 0 || typeCount > 100_000) return null
            val classIds = IntArray(typeCount)
            for (i in 0 until typeCount) {
                val classId = meta.i32()
                classIds[i] = classId
                if (version >= 16) meta.u8()
                var scriptTypeIndex = -1
                if (version >= 17) scriptTypeIndex = meta.i16()
                if (version >= 13) {
                    if ((version < 16 && classId < 0) || (version >= 16 && classId == 114)) meta.bytes(16)
                    meta.bytes(16)
                }
                if (typeTree) {
                    if (version < 12 && version != 10) {
                        skipOldTypeTree(meta, version)
                    } else if (version >= 23) {
                        meta.bytes(16)
                        val size = meta.u32()
                        if (size < 0 || size > meta.remaining()) return null
                        meta.pos += size
                    } else {
                        val nodeCount = meta.i32()
                        val stringBytes = meta.i32()
                        if (nodeCount < 0 || stringBytes < 0) return null
                        val nodeSize = if (version >= 19) 32 else 24
                        val skip = nodeCount.toLong() * nodeSize + stringBytes
                        if (skip < 0 || skip > meta.remaining()) return null
                        meta.pos += skip.toInt()
                    }
                }
                if (typeTree && version >= 21) {
                    val deps = meta.i32()
                    if (deps < 0) return null
                    val skip = deps.toLong() * 4
                    if (skip > meta.remaining()) return null
                    meta.pos += skip.toInt()
                }
            }

            var bigId = false
            if (version in 7..13) bigId = meta.i32() != 0
            val objectCount = meta.i32()
            if (objectCount < 0 || objectCount > 2_000_000) return null
            val objects = ArrayList<ObjectEntry>(objectCount)
            for (i in 0 until objectCount) {
                val pathId = when {
                    bigId -> meta.i64()
                    version < 14 -> meta.i32().toLong()
                    else -> {
                        meta.align4()
                        meta.i64()
                    }
                }
                val byteStartPos = meta.pos
                val byteStart = if (version >= 22) meta.i64() else meta.u32().toLong()
                val byteSizePos = meta.pos
                val byteSize = meta.u32()
                val typeId = meta.i32()
                val classId = if (version < 16) {
                    meta.u16()
                } else {
                    if (typeId !in classIds.indices) return null
                    classIds[typeId]
                }
                if (version < 11) meta.u16()
                if (version in 11..16) meta.i16()
                if (version == 15 || version == 16) meta.u8()
                val offset = dataOffset + byteStart
                if (offset < 0 || byteSize < 0 || offset + byteSize > bytes.size) return null
                objects += ObjectEntry(pathId, byteStart, byteSize, byteStartPos, byteSizePos, classId, offset)
            }
            File(version, littleEndian, metadataStart, dataOffset, objects)
        } catch (e: Exception) {
            null
        }
    }

    private fun skipOldTypeTree(reader: Reader, version: Int) {
        reader.readCString()
        reader.readCString()
        reader.i32()
        if (version == 2) reader.i32()
        if (version != 3) reader.i32()
        reader.i32()
        reader.i32()
        if (version != 3) reader.i32()
        val children = reader.i32()
        if (children < 0 || children > 10_000) throw IllegalArgumentException("bad type tree")
        repeat(children) { skipOldTypeTree(reader, version) }
    }

    private class Reader(
        private val bytes: ByteArray,
        private val littleEndian: Boolean,
        var pos: Int,
        private val end: Int
    ) {
        fun remaining(): Int = end - pos

        private fun need(count: Int) {
            if (count < 0 || pos + count > end) throw IllegalArgumentException("out of bounds")
        }

        fun u8(): Int {
            need(1)
            return bytes[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            need(2)
            val a = bytes[pos].toInt() and 0xFF
            val b = bytes[pos + 1].toInt() and 0xFF
            pos += 2
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }

        fun i16(): Int {
            val v = u16()
            return if (v and 0x8000 != 0) v - 0x10000 else v
        }

        fun u32(): Int {
            need(4)
            val a = bytes[pos].toInt() and 0xFF
            val b = bytes[pos + 1].toInt() and 0xFF
            val c = bytes[pos + 2].toInt() and 0xFF
            val d = bytes[pos + 3].toInt() and 0xFF
            pos += 4
            return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun i32(): Int = u32()

        fun i64(): Long {
            need(8)
            var value = 0L
            if (littleEndian) {
                for (i in 0 until 8) value = value or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
            } else {
                for (i in 0 until 8) value = (value shl 8) or (bytes[pos + i].toLong() and 0xFF)
            }
            pos += 8
            return value
        }

        fun bytes(count: Int): ByteArray {
            need(count)
            val out = bytes.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        fun align4() {
            pos = (pos + 3) and 3.inv()
        }

        fun readCString(): String {
            val start = pos
            while (pos < end && bytes[pos] != 0.toByte()) pos++
            val value = String(bytes, start, pos - start, Charsets.UTF_8)
            if (pos < end) pos++
            return value
        }

        fun readString(): String {
            val length = i32()
            if (length < 0 || length > remaining()) throw IllegalArgumentException("bad string")
            val value = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            align4()
            return value
        }
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            out.write(value and 0xFF); out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF); out.write((value ushr 24) and 0xFF)
        } else {
            out.write((value ushr 24) and 0xFF); out.write((value ushr 16) and 0xFF)
            out.write((value ushr 8) and 0xFF); out.write(value and 0xFF)
        }
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (offset < 0 || offset + 4 > bytes.size) return
        if (littleEndian) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value ushr 24) and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 3] = (value and 0xFF).toByte()
        }
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: Long, littleEndian: Boolean) {
        if (offset < 0 || offset + 8 > bytes.size) return
        if (littleEndian) {
            for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        } else {
            for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
        }
    }
}
