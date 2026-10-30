package com.translatorapk.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object GodotPck {

    private const val MAGIC = 0x43504447
    private const val DIR_ENCRYPTED = 1
    private const val REL_FILEBASE = 2
    private const val ALIGN = 32

    data class Entry(
        val path: String,
        val offset: Long,
        val size: Long,
        val md5: ByteArray,
        val flags: Int
    )

    data class Pack(
        val version: Int,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val packFlags: Int,
        val fileBase: Long,
        val start: Int,
        val entries: List<Entry>,
        val bytes: ByteArray
    )

    fun parse(bytes: ByteArray): Pack? {
        if (bytes.size < 88) return null
        val start = findMagic(bytes) ?: return null
        val version = u32(bytes, start + 4)
        if (version !in 1..2) return null
        val major = u32(bytes, start + 8)
        val minor = u32(bytes, start + 12)
        val patch = u32(bytes, start + 16)
        var pos = start + 20
        var packFlags = 0
        var fileBase = 0L
        if (version >= 2) {
            packFlags = u32(bytes, pos)
            pos += 4
            fileBase = u64(bytes, pos)
            pos += 8
            if (packFlags and DIR_ENCRYPTED != 0) return null
        }
        pos += 64
        val count = u32(bytes, pos)
        pos += 4
        if (count < 0 || count > 1_000_000) return null
        if (version >= 2 && packFlags and REL_FILEBASE != 0) fileBase += start

        val entries = ArrayList<Entry>(count)
        for (i in 0 until count) {
            if (pos + 8 > bytes.size) return null
            val pathLen = u32(bytes, pos)
            pos += 4
            if (pathLen < 0 || pos + pathLen + 8 + 8 + 16 > bytes.size) return null
            val path = String(bytes, pos, pathLen, Charsets.UTF_8).trimEnd('\u0000')
            pos += pathLen
            val rawOffset = u64(bytes, pos)
            pos += 8
            val size = u64(bytes, pos)
            pos += 8
            if (size < 0 || size > bytes.size) return null
            val md5 = bytes.copyOfRange(pos, pos + 16)
            pos += 16
            var flags = 0
            if (version >= 2) {
                if (pos + 4 > bytes.size) return null
                flags = u32(bytes, pos)
                pos += 4
            }
            val absolute = if (version >= 2) fileBase + rawOffset else rawOffset
            if (absolute < 0 || absolute + size > bytes.size) return null
            entries += Entry(path, absolute, size, md5, flags)
        }
        return Pack(version, major, minor, patch, packFlags, fileBase, start, entries, bytes)
    }

    fun read(pack: Pack, path: String): ByteArray? {
        val entry = pack.entries.firstOrNull { it.path == path } ?: return null
        return pack.bytes.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt())
    }

    fun rebuild(pack: Pack, replacements: Map<String, ByteArray>): ByteArray? {
        if (pack.packFlags and DIR_ENCRYPTED != 0) return null
        if (pack.entries.any { it.flags and 1 != 0 }) return null

        val headerSize = if (pack.version >= 2) 100 else 88
        val index = ByteArrayOutputStream()
        val offsetPositions = IntArray(pack.entries.size)
        val data = ArrayList<ByteArray>(pack.entries.size)
        val sizes = LongArray(pack.entries.size)
        val md5s = ArrayList<ByteArray>(pack.entries.size)
        val flagsList = IntArray(pack.entries.size)

        pack.entries.forEachIndexed { i, entry ->
            val bytes = replacements[entry.path]
                ?: pack.bytes.copyOfRange(entry.offset.toInt(), (entry.offset + entry.size).toInt())
            data += bytes
            sizes[i] = bytes.size.toLong()
            md5s += MessageDigest.getInstance("MD5").digest(bytes)
            flagsList[i] = entry.flags

            val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
            val padding = if (pack.version >= 2) (4 - pathBytes.size % 4) % 4 else 0
            writeU32(index, pathBytes.size + padding)
            index.write(pathBytes)
            repeat(padding) { index.write(0) }
            offsetPositions[i] = index.size()
            writeU64(index, 0)
            writeU64(index, sizes[i])
            index.write(md5s[i])
            if (pack.version >= 2) writeU32(index, flagsList[i])
        }

        val indexBytes = index.toByteArray()
        var fileBase = align(headerSize + indexBytes.size, ALIGN)
        val out = ByteArrayOutputStream()
        val header = ByteArray(headerSize)
        writeU32(header, 0, MAGIC)
        writeU32(header, 4, pack.version)
        writeU32(header, 8, pack.major)
        writeU32(header, 12, pack.minor)
        writeU32(header, 16, pack.patch)
        if (pack.version >= 2) {
            writeU32(header, 20, 0)
            writeU64(header, 24, fileBase.toLong())
        }
        writeU32(header, headerSize - 4, pack.entries.size)
        out.write(header)
        out.write(indexBytes)
        while (out.size() < fileBase) out.write(0)

        val offsets = LongArray(pack.entries.size)
        data.forEachIndexed { i, bytes ->
            offsets[i] = if (pack.version >= 2) out.size().toLong() - fileBase else out.size().toLong()
            out.write(bytes)
            val padded = align(out.size(), ALIGN)
            while (out.size() < padded) out.write(0)
        }

        val result = out.toByteArray()
        offsets.forEachIndexed { i, offset ->
            val at = headerSize + offsetPositions[i]
            writeU64(result, at, offset)
        }
        return result
    }

    private fun findMagic(bytes: ByteArray): Int? {
        val limit = minOf(bytes.size - 4, 4096)
        for (i in 0..limit) {
            if (u32(bytes, i) == MAGIC) return i
        }
        return null
    }

    private fun align(value: Int, alignment: Int): Int =
        if (value % alignment == 0) value else value + (alignment - value % alignment)

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeU64(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}
