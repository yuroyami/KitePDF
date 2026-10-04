package io.github.yuroyami.kitepdf.epub.css

/** A token of CSS Syntax 3, 4: its kind, where it sits in the input, and what it holds. */
internal class CssToken(
    val type: Int,
    /** Where the token begins and ends in [CssTokenizer.input]. */
    val start: Int,
    val end: Int,
    /** The name of an ident, function, at-keyword or hash, the text of a string, a delim's character, or a dimension's unit. */
    val value: String = "",
    /** A hash whose value starts an ident sequence (type "id"), or a number written as an integer. */
    val flag: Boolean = false,
    /** The numeric value of a number, percentage or dimension. */
    val number: Double = 0.0,
) {
    companion object {
        const val IDENT = 0
        const val FUNCTION = 1
        const val AT_KEYWORD = 2
        const val HASH = 3
        const val STRING = 4
        const val BAD_STRING = 5
        const val URL = 6
        const val BAD_URL = 7
        const val DELIM = 8
        const val NUMBER = 9
        const val PERCENTAGE = 10
        const val DIMENSION = 11
        const val WHITESPACE = 12
        const val CDO = 13
        const val CDC = 14
        const val COLON = 15
        const val SEMICOLON = 16
        const val COMMA = 17
        const val LEFT_BRACKET = 18
        const val RIGHT_BRACKET = 19
        const val LEFT_PAREN = 20
        const val RIGHT_PAREN = 21
        const val LEFT_BRACE = 22
        const val RIGHT_BRACE = 23
    }

    fun isDelim(c: Char): Boolean = type == DELIM && value.length == 1 && value[0] == c
}

/**
 * The tokenizer of CSS Syntax 3, 4.3, over [text] after the preprocessing of 3.3: CR, FF and CR LF
 * read as LF and NUL as U+FFFD. Comments make no token.
 */
internal class CssTokenizer(text: String) {

    val input: String = preprocess(text)
    private var i = 0

    fun tokens(): List<CssToken> {
        val out = ArrayList<CssToken>()
        while (true) out.add(next() ?: return out)
    }

    private fun at(k: Int): Char = if (k < input.length) input[k] else EOF

    private fun next(): CssToken? {
        skipComments()
        if (i >= input.length) return null
        val start = i
        val c = input[i]
        return when {
            isWhitespace(c) -> {
                while (i < input.length && isWhitespace(input[i])) i++
                CssToken(CssToken.WHITESPACE, start, i)
            }
            c == '"' || c == '\'' -> string(c)
            c == '#' -> {
                if (isIdentCodePoint(at(i + 1)) || validEscape(i + 1)) {
                    i++
                    val id = startsIdent(i)
                    val name = identSequence()
                    CssToken(CssToken.HASH, start, i, name, flag = id)
                } else delim()
            }
            c == '(' -> single(CssToken.LEFT_PAREN)
            c == ')' -> single(CssToken.RIGHT_PAREN)
            c == '+' -> if (startsNumber(i)) numeric() else delim()
            c == ',' -> single(CssToken.COMMA)
            c == '-' -> when {
                startsNumber(i) -> numeric()
                at(i + 1) == '-' && at(i + 2) == '>' -> { i += 3; CssToken(CssToken.CDC, start, i) }
                startsIdent(i) -> identLike()
                else -> delim()
            }
            c == '.' -> if (startsNumber(i)) numeric() else delim()
            c == ':' -> single(CssToken.COLON)
            c == ';' -> single(CssToken.SEMICOLON)
            c == '<' -> if (at(i + 1) == '!' && at(i + 2) == '-' && at(i + 3) == '-') { i += 4; CssToken(CssToken.CDO, start, i) } else delim()
            c == '@' -> if (startsIdent(i + 1)) { i++; val name = identSequence(); CssToken(CssToken.AT_KEYWORD, start, i, name) } else delim()
            c == '[' -> single(CssToken.LEFT_BRACKET)
            c == '\\' -> if (validEscape(i)) identLike() else delim()
            c == ']' -> single(CssToken.RIGHT_BRACKET)
            c == '{' -> single(CssToken.LEFT_BRACE)
            c == '}' -> single(CssToken.RIGHT_BRACE)
            c in '0'..'9' -> numeric()
            isIdentStart(c) -> identLike()
            else -> delim()
        }
    }

    private fun single(type: Int): CssToken { i++; return CssToken(type, i - 1, i) }

    private fun delim(): CssToken {
        val start = i
        val c = input[i]
        i += if (c.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) 2 else 1
        return CssToken(CssToken.DELIM, start, i, input.substring(start, i))
    }

    private fun skipComments() {
        while (i + 1 < input.length && input[i] == '/' && input[i + 1] == '*') {
            val close = input.indexOf("*/", i + 2)
            i = if (close < 0) input.length else close + 2
        }
    }

    private fun string(quote: Char): CssToken {
        val start = i
        i++
        val sb = StringBuilder()
        while (i < input.length) {
            val c = input[i]
            when {
                c == quote -> { i++; return CssToken(CssToken.STRING, start, i, sb.toString()) }
                c == '\n' -> return CssToken(CssToken.BAD_STRING, start, i)
                c == '\\' -> when {
                    i + 1 >= input.length -> i++
                    input[i + 1] == '\n' -> i += 2
                    else -> { i++; appendCodePoint(sb, escaped()) }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return CssToken(CssToken.STRING, start, i, sb.toString())
    }

    private fun numeric(): CssToken {
        val start = i
        if (input[i] == '+' || input[i] == '-') i++
        var integer = true
        while (at(i) in '0'..'9') i++
        if (at(i) == '.' && at(i + 1) in '0'..'9') {
            integer = false
            i++
            while (at(i) in '0'..'9') i++
        }
        val e = at(i)
        if ((e == 'e' || e == 'E') && (at(i + 1) in '0'..'9' || ((at(i + 1) == '+' || at(i + 1) == '-') && at(i + 2) in '0'..'9'))) {
            integer = false
            i += 2
            while (at(i) in '0'..'9') i++
        }
        val repr = input.substring(if (input[start] == '+') start + 1 else start, i)
        val number = repr.toDoubleOrNull() ?: 0.0
        return when {
            startsIdent(i) -> { val unit = identSequence(); CssToken(CssToken.DIMENSION, start, i, unit, integer, number) }
            at(i) == '%' -> { i++; CssToken(CssToken.PERCENTAGE, start, i, "", integer, number) }
            else -> CssToken(CssToken.NUMBER, start, i, "", integer, number)
        }
    }

    private fun identLike(): CssToken {
        val start = i
        val name = identSequence()
        if (at(i) != '(') return CssToken(CssToken.IDENT, start, i, name)
        i++
        if (!asciiEquals(name, "url")) return CssToken(CssToken.FUNCTION, start, i, name)
        var k = i
        while (isWhitespace(at(k)) && isWhitespace(at(k + 1))) k++
        val q = if (isWhitespace(at(k))) at(k + 1) else at(k)
        if (q == '"' || q == '\'') return CssToken(CssToken.FUNCTION, start, i, name)
        return url(start)
    }

    private fun url(start: Int): CssToken {
        while (isWhitespace(at(i))) i++
        val sb = StringBuilder()
        while (i < input.length) {
            val c = input[i]
            when {
                c == ')' -> { i++; return CssToken(CssToken.URL, start, i, sb.toString()) }
                isWhitespace(c) -> {
                    while (isWhitespace(at(i))) i++
                    if (i >= input.length || at(i) == ')') { if (i < input.length) i++; return CssToken(CssToken.URL, start, i, sb.toString()) }
                    return badUrl(start)
                }
                c == '"' || c == '\'' || c == '(' || isNonPrintable(c) -> return badUrl(start)
                c == '\\' -> if (validEscape(i)) { i++; appendCodePoint(sb, escaped()) } else return badUrl(start)
                else -> { sb.append(c); i++ }
            }
        }
        return CssToken(CssToken.URL, start, i, sb.toString())
    }

    private fun badUrl(start: Int): CssToken {
        while (i < input.length) {
            if (input[i] == ')') { i++; break }
            if (validEscape(i)) { i++; escaped() } else i++
        }
        return CssToken(CssToken.BAD_URL, start, i)
    }

    private fun identSequence(): String {
        val sb = StringBuilder()
        while (i < input.length) {
            val c = input[i]
            when {
                isIdentCodePoint(c) -> { sb.append(c); i++ }
                validEscape(i) -> { i++; appendCodePoint(sb, escaped()) }
                else -> break
            }
        }
        return sb.toString()
    }

    /** The code point of an escape whose backslash the cursor just passed (4.3.7). */
    private fun escaped(): Int {
        if (i >= input.length) return 0xFFFD
        val c = input[i]
        if (!isHex(c)) {
            if (c.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) {
                val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (input[i + 1].code - 0xDC00)
                i += 2
                return cp
            }
            i++
            return c.code
        }
        var cp = 0
        var n = 0
        while (n < 6 && i < input.length && isHex(input[i])) { cp = cp * 16 + input[i].digitToInt(16); i++; n++ }
        if (i < input.length && isWhitespace(input[i])) i++
        return if (cp == 0 || cp in 0xD800..0xDFFF || cp > 0x10FFFF) 0xFFFD else cp
    }

    /** Whether a backslash at [k] starts an escape: one before a newline does not, one at the end does (4.3.8). */
    private fun validEscape(k: Int): Boolean = at(k) == '\\' && at(k + 1) != '\n'

    private fun startsIdent(k: Int): Boolean {
        val c = at(k)
        return when {
            c == '-' -> isIdentStart(at(k + 1)) || at(k + 1) == '-' || validEscape(k + 1)
            isIdentStart(c) -> true
            c == '\\' -> validEscape(k)
            else -> false
        }
    }

    private fun startsNumber(k: Int): Boolean {
        val c = at(k)
        return when (c) {
            '+', '-' -> at(k + 1) in '0'..'9' || (at(k + 1) == '.' && at(k + 2) in '0'..'9')
            '.' -> at(k + 1) in '0'..'9'
            else -> c in '0'..'9'
        }
    }

    companion object {
        private const val EOF = '￿'

        fun preprocess(text: String): String {
            if (text.none { it == '\r' || it == '\u000C' || it == '\u0000' }) return text
            val sb = StringBuilder(text.length)
            var k = 0
            while (k < text.length) {
                when (val c = text[k]) {
                    '\r' -> { sb.append('\n'); if (k + 1 < text.length && text[k + 1] == '\n') k++ }
                    '\u000C' -> sb.append('\n')
                    '\u0000' -> sb.append('�')
                    else -> sb.append(c)
                }
                k++
            }
            return sb.toString()
        }

        fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n'
        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        private fun isIdentStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || (c.code >= 0x80 && c != EOF)
        fun isIdentCodePoint(c: Char): Boolean = isIdentStart(c) || c in '0'..'9' || c == '-'
        private fun isNonPrintable(c: Char): Boolean = c.code <= 0x08 || c.code == 0x0B || c.code in 0x0E..0x1F || c.code == 0x7F

        fun appendCodePoint(sb: StringBuilder, cp: Int) {
            if (cp < 0x10000) sb.append(cp.toChar())
            else {
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }
}

/** Whether [a] and [b] are equal, ignoring the case of ASCII letters only. */
internal fun asciiEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (k in a.indices) if (asciiLower(a[k]) != asciiLower(b[k])) return false
    return true
}

internal fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

/** [s] with its ASCII letters lowercased, and every other character as it is. */
internal fun asciiLower(s: String): String {
    if (s.none { it in 'A'..'Z' }) return s
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(asciiLower(c))
    return sb.toString()
}
