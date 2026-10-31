package com.translatorapk.core

import com.translatorapk.model.EntryKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ParsedItem(val key: String, val text: String)

object TextCodec {

    private const val SEP = "\u001F"
    private const val KV = "kv"
    private const val LINE = "ln"
    private const val CELL = "cell"

    private val json = Json { ignoreUnknownKeys = true }

    fun isProbablyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.any { it == 0.toByte() }) return false
        val sample = bytes.take(minOf(bytes.size, 4096))
        var control = 0
        for (b in sample) {
            val c = b.toInt() and 0xFF
            if (c < 0x09) control++
            if (c in 0x0E..0x1F) control++
        }
        return control * 100 / sample.size < 5
    }

    private val CODE_LITERALS = setOf(
        "true", "false", "null", "nil", "none", "self", "this",
        "func", "var", "const", "class", "extends", "signal", "enum",
        "return", "break", "continue", "pass", "match", "await", "yield",
        "and", "or", "not", "if", "else", "elif", "while", "for", "in", "is", "as",
        "on", "off", "void", "undefined", "nan", "inf"
    )

    fun isTranslatable(raw: String): Boolean {
        val s = raw.trim()
        if (s.length < 2 || s.length > 4000) return false
        if (s.lowercase() in CODE_LITERALS) return false
        if (s.startsWith("&\"") || s.startsWith("^\"")) return false
        if (s.startsWith("res://") || s.startsWith("user://") || s.startsWith("uid://")) return false
        if (s.startsWith("http://") || s.startsWith("https://") || s.contains("://")) return false
        if (s.startsWith("data:")) return false
        if (s.matches(Regex("""^[\d\s.,:+\-*/%()\[\]]+$"""))) return false
        if (s.matches(Regex("""^#[0-9a-fA-F]{3,8}$"""))) return false
        if (s.matches(Regex("""^[\w.\-]+\.(png|jpg|jpeg|webp|gif|ogg|mp3|wav|ttf|otf|json|xml|csv|txt)$""", RegexOption.IGNORE_CASE))) return false
        if (!s.any { it.isLetter() }) return false
        if (s.length > 24 && !s.contains(' ') && (s.contains('/') || s.contains('\\'))) return false
        val letterCount = s.count { it.isLetter() }
        if (letterCount < 2) return false
        return true
    }

    fun parse(kind: EntryKind, text: String): List<ParsedItem> = when (kind) {
        EntryKind.JSON -> parseJson(text)
        EntryKind.CSV -> parseCsv(text).second
        EntryKind.LINES -> parseLines(text)
        EntryKind.ARSC -> emptyList()
        EntryKind.PO -> parsePo(text)
        EntryKind.TRES -> parseTres(text)
    }

    fun rebuild(kind: EntryKind, original: String, translations: Map<String, String>): String = when (kind) {
        EntryKind.JSON -> rebuildJson(original, translations)
        EntryKind.CSV -> rebuildCsv(original, translations)
        EntryKind.LINES -> rebuildLines(original, translations)
        EntryKind.ARSC -> original
        EntryKind.PO -> rebuildPo(original, translations)
        EntryKind.TRES -> rebuildTres(original, translations)
    }

    // ---------- JSON ----------

    private fun parseJson(text: String): List<ParsedItem> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ParsedItem>()
        fun walk(element: JsonElement, path: List<String>) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> walk(v, path + k) }
                is JsonArray -> element.forEachIndexed { i, v -> walk(v, path + i.toString()) }
                is JsonPrimitive -> if (element.isString && isTranslatable(element.content)) {
                    out += ParsedItem(path.joinToString(SEP), element.content)
                }
            }
        }
        walk(root, emptyList())
        return out
    }

    private fun rebuildJson(original: String, translations: Map<String, String>): String {
        var root = runCatching { json.parseToJsonElement(original) }.getOrNull() ?: return original
        translations.forEach { (key, value) ->
            val path = key.split(SEP)
            root = setAt(root, path, value)
        }
        val pretty = original.contains('\n')
        val encoder = if (pretty) Json { prettyPrint = true } else Json
        return encoder.encodeToString(JsonElement.serializer(), root)
    }

    private fun setAt(element: JsonElement, path: List<String>, value: String): JsonElement {
        if (path.isEmpty()) return JsonPrimitive(value)
        val head = path.first()
        val rest = path.drop(1)
        return when (element) {
            is JsonObject -> JsonObject(
                element.toMutableMap().also { map ->
                    val child = map[head] ?: JsonPrimitive("")
                    map[head] = setAt(child, rest, value)
                }
            )
            is JsonArray -> {
                val index = head.toIntOrNull() ?: return element
                if (index !in element.indices) return element
                JsonArray(element.mapIndexed { i, child -> if (i == index) setAt(child, rest, value) else child })
            }
            else -> element
        }
    }

    // ---------- CSV ----------

    private fun delimiterFor(text: String): Char {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val candidates = listOf(';', ',', '\t')
        val best = candidates.maxByOrNull { c -> first.count { it == c } }
        return if (best != null && first.count { it == best } > 0) best else ','
    }

    private fun parseCsv(text: String): Pair<Char, List<ParsedItem>> {
        val delimiter = delimiterFor(text)
        val rows = readCsv(text, delimiter)
        val out = mutableListOf<ParsedItem>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, cell ->
                if (isTranslatable(cell)) {
                    out += ParsedItem("$CELL$SEP$r$SEP$c", cell)
                }
            }
        }
        return delimiter to out
    }

    private fun readCsv(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> {
                    row.add(cell.toString()); cell.clear()
                }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }

    private fun rebuildCsv(original: String, translations: Map<String, String>): String {
        val delimiter = delimiterFor(original)
        val rows = readCsv(original, delimiter).map { it.toMutableList() }.toMutableList()
        translations.forEach { (key, value) ->
            val parts = key.split(SEP)
            if (parts.size != 3 || parts[0] != CELL) return@forEach
            val r = parts[1].toIntOrNull() ?: return@forEach
            val c = parts[2].toIntOrNull() ?: return@forEach
            if (r in rows.indices && c in rows[r].indices) rows[r][c] = value
        }
        val trailingNewline = original.endsWith("\n")
        val body = rows.joinToString("\n") { row -> row.joinToString(delimiter.toString()) { escapeCsv(it, delimiter) } }
        return if (trailingNewline) "$body\n" else body
    }

    private fun escapeCsv(value: String, delimiter: Char): String {
        val needQuote = value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
        return if (needQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    // ---------- PO ----------

    private data class PoField(
        val name: String,
        val value: String,
        val rawLines: List<String>
    )

    private data class PoEntry(
        val header: Boolean,
        val comments: List<String>,
        val fields: List<PoField>
    )

    private fun parsePoEntries(text: String): List<PoEntry> {
        val blocks = mutableListOf<MutableList<String>>()
        var block = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.isBlank()) {
                if (block.isNotEmpty()) {
                    blocks += block
                    block = mutableListOf()
                }
            } else {
                block += line
            }
        }
        if (block.isNotEmpty()) blocks += block

        val entries = mutableListOf<PoEntry>()
        for (raw in blocks) {
            val comments = raw.takeWhile { it.trimStart().startsWith("#") }
            val body = raw.drop(comments.size)
            if (body.isEmpty()) {
                if (comments.isNotEmpty()) entries += PoEntry(false, comments, emptyList())
                continue
            }
            val fields = mutableListOf<PoField>()
            var i = 0
            while (i < body.size) {
                val parsed = parsePoField(body[i])
                if (parsed == null) {
                    i++
                    continue
                }
                val rawLines = mutableListOf(body[i])
                val value = StringBuilder(parsed.second)
                i++
                while (i < body.size) {
                    val cont = parsePoContinuation(body[i]) ?: break
                    rawLines += body[i]
                    value.append(cont)
                    i++
                }
                fields += PoField(parsed.first, value.toString(), rawLines)
            }
            if (fields.isEmpty() && comments.isEmpty()) continue
            val header = fields.any { it.name == "msgid" && it.value.isEmpty() }
            entries += PoEntry(header, comments, fields)
        }
        return entries
    }

    private fun parsePoField(line: String): Pair<String, String>? {
        val trimmed = line.trimStart()
        val space = trimmed.indexOf(' ')
        if (space <= 0) return null
        val name = trimmed.substring(0, space)
        if (name != "msgid" && name != "msgstr" && name != "msgid_plural" &&
            name != "msgctxt" && !name.matches(Regex("""msgstr\[\d+\]"""))
        ) return null
        val quoted = parsePoQuoted(trimmed.substring(space))
        return if (quoted != null) name to quoted else null
    }

    private fun parsePoContinuation(line: String): String? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith('"')) return null
        return parsePoQuoted(trimmed)
    }

    private fun parsePoQuoted(fragment: String): String? {
        val start = fragment.indexOf('"')
        if (start < 0) return null
        var end = start + 1
        var escaped = false
        while (end < fragment.length) {
            val c = fragment[end]
            if (!escaped && c == '"') break
            if (!escaped && c == '\\') escaped = true else escaped = false
            end++
        }
        if (end >= fragment.length) return null
        return unescapePo(fragment.substring(start + 1, end))
    }

    private fun unescapePo(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }

    private fun escapePo(value: String): String {
        val out = StringBuilder()
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun parsePo(text: String): List<ParsedItem> {
        val out = mutableListOf<ParsedItem>()
        parsePoEntries(text).forEachIndexed { index, entry ->
            if (entry.header) return@forEachIndexed
            val msgid = entry.fields.firstOrNull { it.name == "msgid" }?.value.orEmpty()
            val plurals = entry.fields.filter { it.name.matches(Regex("""msgstr\[\d+\]""")) }
            if (plurals.isNotEmpty()) {
                for (field in plurals) {
                    if (isTranslatable(field.value)) {
                        out += ParsedItem("po:$index:${field.name}", field.value)
                    }
                }
                if (plurals.all { it.value.isBlank() }) {
                    val source = entry.fields.firstOrNull { it.name == "msgid_plural" }?.value ?: msgid
                    if (isTranslatable(source)) out += ParsedItem("po:$index:msgstr[0]", source)
                }
            } else {
                val msgstr = entry.fields.firstOrNull { it.name == "msgstr" }?.value.orEmpty()
                val source = msgstr.ifBlank { msgid }
                if (isTranslatable(source)) out += ParsedItem("po:$index:msgstr", source)
            }
        }
        return out
    }

    private fun rebuildPo(original: String, translations: Map<String, String>): String {
        if (translations.isEmpty()) return original
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val trailingNewline = original.endsWith("\n") || original.endsWith("\r")
        val entries = parsePoEntries(original)
        val blocks = mutableListOf<String>()
        entries.forEachIndexed { index, entry ->
            if (entry.header || entry.fields.isEmpty()) {
                blocks += (entry.comments + entry.fields.flatMap { it.rawLines }).joinToString(newline)
                return@forEachIndexed
            }
            val lines = mutableListOf<String>()
            lines += entry.comments
            for (field in entry.fields) {
                val translated = translations["po:$index:${field.name}"]
                if (translated != null) {
                    lines += "${field.name} \"${escapePo(translated)}\""
                } else {
                    lines += field.rawLines
                }
            }
            blocks += lines.joinToString(newline)
        }
        val body = blocks.joinToString(newline + newline)
        return if (trailingNewline) body + newline else body
    }

    // ---------- LINES ----------

    private fun leadingKey(line: String): Pair<String, String>? {
        val eq = line.indexOf('=')
        val colon = line.indexOf(':')
        val idx = when {
            eq >= 0 && colon >= 0 -> minOf(eq, colon)
            eq >= 0 -> eq
            colon >= 0 -> colon
            else -> -1
        }
        if (idx <= 0) return null
        val key = line.substring(0, idx)
        if (key.contains(' ') || key.contains('/')) return null
        return key to line.substring(idx + 1).trimStart()
    }

    private fun parseLines(text: String): List<ParsedItem> {
        val out = mutableListOf<ParsedItem>()
        text.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("//")) return@forEachIndexed
            val kv = leadingKey(line)
            if (kv != null) {
                if (isTranslatable(kv.second)) out += ParsedItem("$KV$SEP$index", kv.second)
            } else if (isTranslatable(trimmed)) {
                out += ParsedItem("$LINE$SEP$index", trimmed)
            }
        }
        return out
    }

    private fun rebuildLines(original: String, translations: Map<String, String>): String {
        val lines = original.lines().toMutableList()
        translations.forEach { (key, value) ->
            val parts = key.split(SEP)
            if (parts.size != 2) return@forEach
            val index = parts[1].toIntOrNull() ?: return@forEach
            if (index !in lines.indices) return@forEach
            val line = lines[index]
            when (parts[0]) {
                KV -> {
                    val eq = line.indexOf('=')
                    val colon = line.indexOf(':')
                    val idx = when {
                        eq >= 0 && colon >= 0 -> minOf(eq, colon)
                        eq >= 0 -> eq
                        colon >= 0 -> colon
                        else -> -1
                    }
                    if (idx > 0) {
                        val sep = if (idx == eq) "=" else ":"
                        lines[index] = line.substring(0, idx) + sep + " " + value
                    }
                }
                LINE -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    lines[index] = indent + value
                }
            }
        }
        return lines.joinToString("\n")
    }

    // ---------- TRES (Godot Text Resource / Dialogue Manager / Scenes) ----------

    private val TRES_EXTRACT_REGEX = Regex(
        """^(\s*)(&\"(text|character|prompt)\"|(?:text|title|description|tooltip_text|hint_tooltip|placeholder_text))\s*([:=])\s*\"(.*)\"\s*(,?)$"""
    )

    private fun parseTres(text: String): List<ParsedItem> {
        val out = mutableListOf<ParsedItem>()
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i]
            val match = TRES_EXTRACT_REGEX.matchEntire(line) ?: continue
            val field = match.groupValues[2]
            val rawVal = match.groupValues[5]
            val unescaped = unescapePo(rawVal)
            if (isTranslatable(unescaped)) {
                out += ParsedItem("tres:$i:$field", unescaped)
            }
        }
        return out
    }

    private fun rebuildTres(original: String, translations: Map<String, String>): String {
        if (translations.isEmpty()) return original
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val trailingNewline = original.endsWith("\n") || original.endsWith("\r")
        val lines = original.lines().toMutableList()
        for ((key, translated) in translations) {
            val parts = key.split(':')
            if (parts.size < 3 || parts[0] != "tres") continue
            val lineIdx = parts[1].toIntOrNull() ?: continue
            if (lineIdx !in lines.indices) continue
            val line = lines[lineIdx]
            val match = TRES_EXTRACT_REGEX.matchEntire(line) ?: continue
            val indent = match.groupValues[1]
            val field = match.groupValues[2]
            val sep = match.groupValues[4]
            val comma = match.groupValues[6]
            val escaped = escapePo(translated)
            lines[lineIdx] = "$indent$field $sep \"$escaped\"$comma"
        }
        val body = lines.joinToString(newline)
        return if (trailingNewline) body + newline else body
    }
}
