package io.github.tieo.taghistory.apple.plist

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Hand-rolled XML plist reader + writer.
 *
 * Not a general XML library — it understands just the plist grammar, which
 * has given us three concrete benefits over dd-plist: we accept Apple's
 * bare `<dict>` SPD fragments (plistlib-compatible), we preserve dictionary
 * insertion order (diff-stable against the Python reference), and we
 * surface type mismatches at parse time rather than on unchecked cast.
 *
 * Intentionally permissive: whitespace between tags is ignored, XML
 * declarations and DOCTYPEs are skipped, comments and PIs are tolerated.
 * Strict: unknown element tags raise [PlistParseException] — we would
 * rather fail loudly than silently drop data Apple started sending.
 */
object XmlPlist {

    /**
     * Parse a UTF-8 XML plist document or fragment. Accepts:
     *   - `<?xml?>` + `<!DOCTYPE ...>` + `<plist>...</plist>`
     *   - just `<plist>...</plist>`
     *   - a bare `<dict>...</dict>` or `<array>...</array>` (Apple SPD
     *     emits this; Python's plistlib accepts it; dd-plist rejects it).
     */
    fun parse(xml: String): PlistValue {
        val lexer = XmlLexer(xml)
        skipPrologue(lexer)
        // `<plist>` wrapper is optional — Apple SPD omits it.
        if (lexer.peekElementName() == "plist") {
            lexer.consumeStartTag("plist")
            skipWhitespaceAndMisc(lexer)
            val value = parseValue(lexer)
            skipWhitespaceAndMisc(lexer)
            lexer.consumeEndTag("plist")
            return value
        }
        return parseValue(lexer)
    }

    fun parse(bytes: ByteArray): PlistValue = parse(bytes.decodeToString())

    /**
     * Encode a [PlistValue] to a UTF-8 XML plist document. Emits the
     * standard `<?xml?>`+DOCTYPE prologue; passes it back through [parse]
     * produces an equal value.
     */
    fun encode(value: PlistValue): ByteArray = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" """)
        append(""""http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        append("""<plist version="1.0">""")
        encodeValue(value, this)
        append("</plist>")
    }.encodeToByteArray()

    // -----------------------------------------------------------------
    // Parser
    // -----------------------------------------------------------------

    private fun parseValue(lexer: XmlLexer): PlistValue {
        skipWhitespaceAndMisc(lexer)
        val tag = lexer.peekElementName()
            ?: throw PlistParseException("Expected a plist element, got EOF")
        return when (tag) {
            "dict" -> parseDict(lexer)
            "array" -> parseArray(lexer)
            "string" -> PlistValue.Str(parseTextElement(lexer, "string"))
            "integer" -> PlistValue.Int64(parseTextElement(lexer, "integer").trim().toLong())
            "real" -> PlistValue.Real(parseTextElement(lexer, "real").trim().toDouble())
            "data" -> PlistValue.Data(decodeBase64(parseTextElement(lexer, "data")))
            "date" -> PlistValue.Date(parseIso8601(parseTextElement(lexer, "date").trim()))
            "true" -> { lexer.consumeEmptyElement("true"); PlistValue.Bool(true) }
            "false" -> { lexer.consumeEmptyElement("false"); PlistValue.Bool(false) }
            "null" -> { lexer.consumeEmptyElement("null"); PlistValue.Null }
            else -> throw PlistParseException("Unknown plist element <$tag>")
        }
    }

    private fun parseDict(lexer: XmlLexer): PlistValue.Dict {
        lexer.consumeStartTag("dict")
        val out = linkedMapOf<String, PlistValue>()
        while (true) {
            skipWhitespaceAndMisc(lexer)
            if (lexer.peekEndTag() == "dict") {
                lexer.consumeEndTag("dict")
                return PlistValue.Dict(out)
            }
            if (lexer.peekElementName() != "key") {
                throw PlistParseException(
                    "Expected <key> inside <dict>, got <${lexer.peekElementName()}>")
            }
            val key = parseTextElement(lexer, "key")
            skipWhitespaceAndMisc(lexer)
            out[key] = parseValue(lexer)
        }
    }

    private fun parseArray(lexer: XmlLexer): PlistValue.Array {
        lexer.consumeStartTag("array")
        val items = mutableListOf<PlistValue>()
        while (true) {
            skipWhitespaceAndMisc(lexer)
            if (lexer.peekEndTag() == "array") {
                lexer.consumeEndTag("array")
                return PlistValue.Array(items)
            }
            items.add(parseValue(lexer))
        }
    }

    private fun parseTextElement(lexer: XmlLexer, tag: String): String {
        val attrs = lexer.consumeStartTagFull(tag)
        if (attrs.selfClosed) return ""
        val text = lexer.readTextUntilEnd(tag)
        return decodeXmlEntities(text)
    }

    // -----------------------------------------------------------------
    // Writer
    // -----------------------------------------------------------------

    private fun encodeValue(value: PlistValue, out: StringBuilder) {
        when (value) {
            is PlistValue.Dict -> {
                if (value.entries.isEmpty()) { out.append("<dict/>"); return }
                out.append("<dict>")
                for ((k, v) in value.entries) {
                    out.append("<key>").append(escapeXml(k)).append("</key>")
                    encodeValue(v, out)
                }
                out.append("</dict>")
            }
            is PlistValue.Array -> {
                if (value.items.isEmpty()) { out.append("<array/>"); return }
                out.append("<array>")
                for (item in value.items) encodeValue(item, out)
                out.append("</array>")
            }
            is PlistValue.Str ->
                out.append("<string>").append(escapeXml(value.value)).append("</string>")
            is PlistValue.Int64 -> out.append("<integer>").append(value.value).append("</integer>")
            is PlistValue.Real -> out.append("<real>").append(value.value).append("</real>")
            is PlistValue.Bool -> out.append(if (value.value) "<true/>" else "<false/>")
            is PlistValue.Data -> out.append("<data>").append(encodeBase64(value.bytes)).append("</data>")
            is PlistValue.Date -> out.append("<date>").append(formatIso8601(value.epochMillis)).append("</date>")
            is PlistValue.Uid -> {
                // Apple's XML convention for UID — dd-plist uses a dict wrapper.
                out.append("<dict><key>CF\$UID</key><integer>")
                    .append(value.value)
                    .append("</integer></dict>")
            }
            is PlistValue.Null -> out.append("<null/>")
        }
    }

    // -----------------------------------------------------------------
    // Prologue / misc-skipping
    // -----------------------------------------------------------------

    private fun skipPrologue(lexer: XmlLexer) {
        while (true) {
            skipWhitespaceAndMisc(lexer)
            val peek = lexer.peekRaw(5)
            when {
                peek.startsWith("<?xml") -> lexer.skipProcessingInstruction()
                peek.startsWith("<?") -> lexer.skipProcessingInstruction()
                peek.startsWith("<!DOC") -> lexer.skipDoctype()
                peek.startsWith("<!--") -> lexer.skipComment()
                else -> return
            }
        }
    }

    private fun skipWhitespaceAndMisc(lexer: XmlLexer) {
        while (true) {
            lexer.skipWhitespace()
            val peek = lexer.peekRaw(4)
            when {
                peek.startsWith("<!--") -> lexer.skipComment()
                peek.startsWith("<?") -> lexer.skipProcessingInstruction()
                peek.startsWith("<!DOC") -> lexer.skipDoctype()
                else -> return
            }
        }
    }
}

class PlistParseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

// ----------------------------------------------------------------------
// Lexer
// ----------------------------------------------------------------------

private class XmlLexer(private val src: String) {
    private var pos = 0

    fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    fun peekRaw(n: Int): String {
        val end = (pos + n).coerceAtMost(src.length)
        return src.substring(pos, end)
    }

    fun skipProcessingInstruction() {
        require(src.startsWith("<?", pos)) { "not a PI at $pos" }
        val end = src.indexOf("?>", pos + 2)
        if (end < 0) throw PlistParseException("Unterminated processing instruction")
        pos = end + 2
    }

    fun skipDoctype() {
        require(src.startsWith("<!DOC", pos)) { "not a DOCTYPE at $pos" }
        // Handle possible internal subset [...] — scan depth-naively.
        var depth = 0
        pos += 2
        while (pos < src.length) {
            val c = src[pos]
            if (c == '[') depth++
            else if (c == ']') depth--
            else if (c == '>' && depth == 0) { pos++; return }
            pos++
        }
        throw PlistParseException("Unterminated DOCTYPE")
    }

    fun skipComment() {
        require(src.startsWith("<!--", pos)) { "not a comment at $pos" }
        val end = src.indexOf("-->", pos + 4)
        if (end < 0) throw PlistParseException("Unterminated comment")
        pos = end + 3
    }

    /** Peek the element name if the next token is a start tag. */
    fun peekElementName(): String? {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '<') return null
        if (pos + 1 < src.length && src[pos + 1] == '/') return null
        if (pos + 1 < src.length && (src[pos + 1] == '?' || src[pos + 1] == '!')) return null
        var i = pos + 1
        val start = i
        while (i < src.length) {
            val c = src[i]
            if (c.isWhitespace() || c == '>' || c == '/') break
            i++
        }
        return src.substring(start, i)
    }

    /** Peek the element name if the next token is an end tag. */
    fun peekEndTag(): String? {
        skipWhitespace()
        if (pos + 1 >= src.length || src[pos] != '<' || src[pos + 1] != '/') return null
        var i = pos + 2
        val start = i
        while (i < src.length) {
            val c = src[i]
            if (c.isWhitespace() || c == '>') break
            i++
        }
        return src.substring(start, i)
    }

    fun consumeStartTag(expected: String) {
        val attrs = consumeStartTagFull(expected)
        if (attrs.selfClosed) {
            throw PlistParseException("Expected open <$expected>, got self-closed")
        }
    }

    fun consumeEmptyElement(expected: String) {
        // Tolerate <tag/> or <tag></tag>.
        val attrs = consumeStartTagFull(expected)
        if (!attrs.selfClosed) consumeEndTag(expected)
    }

    fun consumeEndTag(expected: String) {
        skipWhitespace()
        if (!src.startsWith("</", pos)) {
            throw PlistParseException(
                "Expected </$expected> at pos=$pos, got '${peekRaw(10)}'")
        }
        val end = src.indexOf('>', pos + 2)
        if (end < 0) throw PlistParseException("Unterminated end tag")
        val name = src.substring(pos + 2, end).trim()
        if (name != expected) {
            throw PlistParseException("Expected </$expected>, got </$name>")
        }
        pos = end + 1
    }

    data class StartTag(val name: String, val selfClosed: Boolean)

    fun consumeStartTagFull(expected: String): StartTag {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '<') {
            throw PlistParseException(
                "Expected <$expected>, got '${peekRaw(10)}' at pos=$pos")
        }
        val end = src.indexOf('>', pos + 1)
        if (end < 0) throw PlistParseException("Unterminated tag")
        val body = src.substring(pos + 1, end).trim()
        val selfClosed = body.endsWith("/")
        val nameEnd = body.indexOfFirst { it.isWhitespace() || it == '/' }
        val name = if (nameEnd < 0) body else body.substring(0, nameEnd)
        if (name != expected) {
            throw PlistParseException("Expected <$expected>, got <$name>")
        }
        pos = end + 1
        return StartTag(name, selfClosed)
    }

    /** Read text content up to but not including `</tag>`, including CDATA. */
    fun readTextUntilEnd(tag: String): String {
        val buf = StringBuilder()
        while (pos < src.length) {
            if (src.startsWith("<![CDATA[", pos)) {
                val cdataEnd = src.indexOf("]]>", pos + 9)
                if (cdataEnd < 0) throw PlistParseException("Unterminated CDATA")
                buf.append(src, pos + 9, cdataEnd)
                pos = cdataEnd + 3
                continue
            }
            if (src.startsWith("<!--", pos)) { skipComment(); continue }
            if (src.startsWith("</", pos)) {
                consumeEndTag(tag)
                return buf.toString()
            }
            if (src[pos] == '<') {
                throw PlistParseException(
                    "Unexpected nested tag inside <$tag>: '${peekRaw(10)}'")
            }
            buf.append(src[pos])
            pos++
        }
        throw PlistParseException("Unterminated <$tag>")
    }
}

// ----------------------------------------------------------------------
// XML entity + base64 + date helpers
// ----------------------------------------------------------------------

private fun decodeXmlEntities(s: String): String {
    if ('&' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '&') { out.append(c); i++; continue }
        val end = s.indexOf(';', i + 1)
        if (end < 0) { out.append(c); i++; continue }
        val name = s.substring(i + 1, end)
        out.append(
            when {
                name == "amp" -> "&"
                name == "lt" -> "<"
                name == "gt" -> ">"
                name == "quot" -> "\""
                name == "apos" -> "'"
                name.startsWith("#x") || name.startsWith("#X") -> {
                    val code = name.substring(2).toInt(16)
                    Char(code).toString()
                }
                name.startsWith("#") -> Char(name.substring(1).toInt()).toString()
                else -> throw PlistParseException("Unknown XML entity &$name;")
            }
        )
        i = end + 1
    }
    return out.toString()
}

private fun escapeXml(s: String): String {
    if (s.none { it == '&' || it == '<' || it == '>' }) return s
    val out = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(s: String): ByteArray {
    val clean = s.filterNot { it.isWhitespace() }
    return if (clean.isEmpty()) ByteArray(0) else Base64.decode(clean)
}

@OptIn(ExperimentalEncodingApi::class)
private fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

/**
 * Parse an Apple plist ISO 8601 date. Always in UTC with the `Z` suffix,
 * e.g. `2024-03-01T12:00:00Z`. Fractional seconds are accepted.
 */
internal fun parseIso8601(s: String): Long {
    // Expected: YYYY-MM-DDTHH:MM:SS(.fff)?Z
    require(s.length >= 20 && s[4] == '-' && s[7] == '-' && s[10] == 'T'
            && s[13] == ':' && s[16] == ':') {
        "Invalid ISO-8601 date: $s"
    }
    val year = s.substring(0, 4).toInt()
    val month = s.substring(5, 7).toInt()
    val day = s.substring(8, 10).toInt()
    val hour = s.substring(11, 13).toInt()
    val min = s.substring(14, 16).toInt()
    val sec = s.substring(17, 19).toInt()
    var fracMillis = 0L
    var idx = 19
    if (idx < s.length && s[idx] == '.') {
        var end = idx + 1
        while (end < s.length && s[end].isDigit()) end++
        val fracStr = s.substring(idx + 1, end).padEnd(3, '0').substring(0, 3)
        fracMillis = fracStr.toLong()
        idx = end
    }
    require(idx < s.length && (s[idx] == 'Z' || s[idx] == 'z')) {
        "Apple plist dates must be UTC (Z), got: $s"
    }
    val daysFromEpoch = daysFromCivil(year.toLong(), month, day)
    val seconds = daysFromEpoch * 86_400L + hour * 3600L + min * 60L + sec
    return seconds * 1000L + fracMillis
}

internal fun formatIso8601(epochMillis: Long): String {
    // Floor division so negative epoch times (before 1970) still land on the
    // correct civil date. Can't use Math.floorDiv — it isn't on Kotlin/Native.
    val totalSeconds = floorDiv(epochMillis, 1000L)
    val millisPart = epochMillis - totalSeconds * 1000
    val days = floorDiv(totalSeconds, 86_400L)
    val timeOfDay = totalSeconds - days * 86_400L
    val (y, m, d) = civilFromDays(days)
    val hour = (timeOfDay / 3600).toInt()
    val min = ((timeOfDay % 3600) / 60).toInt()
    val sec = (timeOfDay % 60).toInt()
    return buildString {
        append(y.toString().padStart(4, '0')).append('-')
        append(m.toString().padStart(2, '0')).append('-')
        append(d.toString().padStart(2, '0')).append('T')
        append(hour.toString().padStart(2, '0')).append(':')
        append(min.toString().padStart(2, '0')).append(':')
        append(sec.toString().padStart(2, '0'))
        if (millisPart != 0L) {
            append('.').append(millisPart.toString().padStart(3, '0'))
        }
        append('Z')
    }
}

/**
 * Howard Hinnant's days_from_civil algorithm — converts a proleptic
 * Gregorian (year, month, day) to days from the Unix epoch (1970-01-01).
 * Pure arithmetic; works on multiplatform without a time library.
 */
private fun daysFromCivil(y: Long, mIn: Int, d: Int): Long {
    val yAdj = if (mIn <= 2) y - 1 else y
    val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
    val yoe = (yAdj - era * 400) // [0, 399]
    val m = if (mIn > 2) mIn - 3 else mIn + 9 // [0, 11]
    val doy = (153 * m + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private data class CivilDate(val year: Long, val month: Int, val day: Int)

private fun civilFromDays(z: Long): CivilDate {
    val zShifted = z + 719_468L
    val era = (if (zShifted >= 0) zShifted else zShifted - 146_096L) / 146_097L
    val doe = zShifted - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val yFinal = if (m <= 2L) y + 1 else y
    return CivilDate(yFinal, m, d)
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if ((a xor b) < 0 && q * b != a) q - 1 else q
}
