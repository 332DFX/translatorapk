package com.translatorapk.core

object FontInspector {

    fun missingCyrillic(bytes: ByteArray): Boolean {
        val cmap = parseCmap(bytes) ?: return false
        val hasLatin = hasGlyph(cmap, 0x0041) || hasGlyph(cmap, 0x0061)
        if (!hasLatin) return false
        val cyrillicProbes = intArrayOf(
            0x0410, 0x0430, 0x0411, 0x0431, 0x0416, 0x0436, 0x042F, 0x044F,
            0x0401, 0x0451, 0x0404, 0x0454, 0x0406, 0x0456
        )
        return cyrillicProbes.none { hasGlyph(cmap, it) }
    }

    private data class Cmap(val format: Int, val bytes: ByteArray, val offset: Int)

    private fun parseCmap(bytes: ByteArray): Cmap? {
        if (bytes.size < 12) return null
        val tag = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        if (tag == "ttcf") return null
        val version = be32(bytes, 0)
        if (version != 0x00010000 && tag != "OTTO" && tag != "true" && tag != "typ1") return null
        val numTables = be16(bytes, 4)
        if (numTables <= 0 || 12 + numTables * 16 > bytes.size) return null

        var cmapOffset = -1
        var cmapLength = 0
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            val tableTag = bytes.copyOfRange(rec, rec + 4).toString(Charsets.US_ASCII)
            if (tableTag == "cmap") {
                cmapOffset = be32(bytes, rec + 8)
                cmapLength = be32(bytes, rec + 12)
                break
            }
        }
        if (cmapOffset < 0 || cmapOffset + 4 > bytes.size) return null
        val cmapEnd = minOf(bytes.size, cmapOffset + cmapLength)
        val count = be16(bytes, cmapOffset + 2)
        if (count <= 0) return null

        var best4 = -1
        var best12 = -1
        for (i in 0 until count) {
            val rec = cmapOffset + 4 + i * 8
            if (rec + 8 > cmapEnd) break
            val platform = be16(bytes, rec)
            val encoding = be16(bytes, rec + 2)
            val sub = cmapOffset + be32(bytes, rec + 4)
            if (sub + 2 > bytes.size) continue
            val format = be16(bytes, sub)
            if (format == 12 && ((platform == 3 && encoding == 10) || (platform == 0 && encoding in 4..6))) {
                best12 = sub
            } else if (format == 4 && ((platform == 3 && encoding == 1) || (platform == 0 && encoding == 3))) {
                best4 = sub
            }
        }
        val chosen = if (best12 >= 0) best12 else best4
        if (chosen < 0) return null
        return Cmap(if (best12 >= 0) 12 else 4, bytes, chosen)
    }

    private fun hasGlyph(cmap: Cmap, codePoint: Int): Boolean = when (cmap.format) {
        4 -> hasGlyph4(cmap.bytes, cmap.offset, codePoint)
        12 -> hasGlyph12(cmap.bytes, cmap.offset, codePoint)
        else -> false
    }

    private fun hasGlyph4(bytes: ByteArray, offset: Int, cp: Int): Boolean {
        if (cp > 0xFFFF) return false
        val segCountX2 = be16(bytes, offset + 6)
        val segCount = segCountX2 / 2
        if (segCount <= 0) return false
        val endCodes = offset + 14
        val startCodes = endCodes + segCountX2 + 2
        val idDelta = startCodes + segCountX2
        val idRangeOffset = idDelta + segCountX2
        for (i in 0 until segCount) {
            val end = be16(bytes, endCodes + i * 2)
            if (cp > end) continue
            val start = be16(bytes, startCodes + i * 2)
            if (cp < start) return false
            val rangeOffset = be16(bytes, idRangeOffset + i * 2)
            val glyph = if (rangeOffset == 0) {
                (cp + be16(bytes, idDelta + i * 2)) and 0xFFFF
            } else {
                val address = idRangeOffset + i * 2 + rangeOffset + (cp - start) * 2
                if (address + 2 > bytes.size) return false
                val raw = be16(bytes, address)
                if (raw == 0) 0 else (raw + be16(bytes, idDelta + i * 2)) and 0xFFFF
            }
            return glyph != 0
        }
        return false
    }

    private fun hasGlyph12(bytes: ByteArray, offset: Int, cp: Int): Boolean {
        val groups = be32(bytes, offset + 12)
        if (groups <= 0) return false
        val base = offset + 16
        var low = 0
        var high = groups - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val rec = base + mid * 12
            if (rec + 12 > bytes.size) return false
            val start = be32(bytes, rec)
            val end = be32(bytes, rec + 4)
            val startGlyph = be32(bytes, rec + 8)
            when {
                cp < start -> high = mid - 1
                cp > end -> low = mid + 1
                else -> return startGlyph + (cp - start) != 0
            }
        }
        return false
    }

    private fun be16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun be32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
