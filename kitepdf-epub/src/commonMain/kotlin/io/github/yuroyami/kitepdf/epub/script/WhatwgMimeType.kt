package io.github.yuroyami.kitepdf.epub.script

/**
 * A MIME type as the MIME Sniffing Standard parses and serializes one (4.4 and 4.5): a type and
 * a subtype, in lower case, and its parameters in the order they came, each name once. A book's
 * `FileReader` reads the charset of a blob's type through it (#533).
 *
 * ```kotlin
 * WhatwgMimeType.parse("Text/HTML;Charset=\"gbk\"")?.serialize()   // text/html;charset=gbk
 * ```
 */
internal class WhatwgMimeType private constructor(
    val type: String,
    val subtype: String,
    /** The parameters, by their names in lower case, in order. */
    val parameters: Map<String, String>,
) {
    /** The type and subtype without parameters: `text/html`. */
    val essence: String get() = "$type/$subtype"

    /** The MIME type as a string again, a parameter value quoted where it is not a token. */
    fun serialize(): String = buildString {
        append(type).append('/').append(subtype)
        for ((name, value) in parameters) {
            append(';').append(name).append('=')
            if (value.isNotEmpty() && value.all(::isTokenChar)) {
                append(value)
            } else {
                append('"')
                for (c in value) {
                    if (c == '"' || c == '\\') append('\\')
                    append(c)
                }
                append('"')
            }
        }
    }

    companion object {
        /** [input] as a MIME type, or null where the standard's parser fails. */
        fun parse(input: String): WhatwgMimeType? {
            val text = input.trim(::isHttpWhitespace)
            var at = text.indexOf('/')
            if (at < 0) return null
            val type = text.substring(0, at)
            if (type.isEmpty() || !type.all(::isTokenChar)) return null
            at++
            val semicolon = text.indexOf(';', at).let { if (it < 0) text.length else it }
            val subtype = text.substring(at, semicolon).trimEnd(::isHttpWhitespace)
            if (subtype.isEmpty() || !subtype.all(::isTokenChar)) return null
            at = semicolon
            val parameters = LinkedHashMap<String, String>()
            while (at < text.length) {
                at++
                while (at < text.length && isHttpWhitespace(text[at])) at++
                val nameStart = at
                while (at < text.length && text[at] != ';' && text[at] != '=') at++
                val name = text.substring(nameStart, at).lowercaseAscii()
                if (at < text.length) {
                    if (text[at] == ';') continue
                    at++
                }
                if (at >= text.length) break
                val value: String
                if (text[at] == '"') {
                    val quoted = StringBuilder()
                    at++
                    while (at < text.length) {
                        val c = text[at++]
                        if (c == '"') break
                        if (c == '\\') {
                            if (at >= text.length) { quoted.append('\\'); break }
                            quoted.append(text[at++])
                        } else {
                            quoted.append(c)
                        }
                    }
                    value = quoted.toString()
                    while (at < text.length && text[at] != ';') at++
                } else {
                    val valueStart = at
                    while (at < text.length && text[at] != ';') at++
                    value = text.substring(valueStart, at).trimEnd(::isHttpWhitespace)
                    if (value.isEmpty()) continue
                }
                if (name.isNotEmpty() && name.all(::isTokenChar) && value.all(::isQuotedStringChar) && name !in parameters) {
                    parameters[name] = value
                }
            }
            return WhatwgMimeType(type.lowercaseAscii(), subtype.lowercaseAscii(), parameters)
        }

        private fun isHttpWhitespace(c: Char): Boolean = c == '\n' || c == '\r' || c == '\t' || c == ' '

        /** An HTTP token code point: an ASCII letter or digit, or one of `!#$%&'*+-.^_`|~`. */
        private fun isTokenChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

        /** An HTTP quoted-string token code point: a tab, printable ASCII, or U+0080 to U+00FF. */
        private fun isQuotedStringChar(c: Char): Boolean = c == '\t' || c in ' '..'~' || c in '\u0080'..'\u00FF'

        /** Lower case for ASCII letters only, as the standard's "ASCII lowercase". */
        private fun String.lowercaseAscii(): String =
            if (none { it in 'A'..'Z' }) this else buildString(length) { for (c in this@lowercaseAscii) append(if (c in 'A'..'Z') c + 32 else c) }
    }
}
