package io.github.yuroyami.kitepdf.epub

/** Just enough JSON for the test data of web-platform-tests: objects, arrays, strings with their escapes, numbers, booleans and null. */
internal class Json(private val text: String) {
    private var at = 0

    fun value(): Any? {
        space()
        return when (val c = text[at]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> string()
            't' -> { at += 4; true }
            'f' -> { at += 5; false }
            'n' -> { at += 4; null }
            else -> {
                val start = at
                while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
                check(start < at) { "unexpected $c at $start" }
                text.substring(start, at).toDouble()
            }
        }
    }

    private fun obj(): Map<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        space()
        if (text[at] == '}') { at++; return map }
        while (true) {
            space()
            val key = string()
            space()
            expect(':')
            map[key] = value()
            space()
            if (text[at++] == '}') return map
        }
    }

    private fun arr(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        space()
        if (text[at] == ']') { at++; return list }
        while (true) {
            list += value()
            space()
            if (text[at++] == ']') return list
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = text[at++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> when (val e = text[at++]) {
                    'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    else -> out.append(e)
                }
                else -> out.append(c)
            }
        }
    }

    private fun space() { while (at < text.length && text[at].isWhitespace()) at++ }

    private fun expect(c: Char) { check(text[at++] == c) { "expected $c at ${at - 1}" } }
}
