package io.github.yuroyami.kitepdf.epub

/**
 * The `url()` and `@import` rewrites of a book's style sheets, in time linear in the sheet. The
 * regular expressions they replace backtracked on text that almost matched, so a 160 KB sheet of
 * `url(` repeated held up a chapter's layout for 45 seconds (#989). Each scan matches what its
 * pattern matched, and finds where a run ends from a table built in one pass, so a failed match
 * costs a few steps, not the rest of the sheet.
 */
internal object CssScan {

    /** CSS white space as `\s` read it: space, tab, line feed, line tabulation, form feed, return. */
    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /** For each index, the first index at or after it whose character [stop] accepts, or the length. */
    private inline fun nextStop(text: String, stop: (Char) -> Boolean): IntArray {
        val out = IntArray(text.length + 1)
        out[text.length] = text.length
        for (i in text.length - 1 downTo 0) out[i] = if (stop(text[i])) i else out[i + 1]
        return out
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && isSpace(text[i])) i++
        return i
    }

    /**
     * Every `url(...)` of [css], as `url\(\s*(["']?)([^"')]*)\1\s*\)` without case matched it, replaced
     * by what [replace] makes of the whole match and its content.
     */
    fun replaceUrls(css: String, replace: (whole: String, content: String) -> String): String {
        val term = nextStop(css) { it == '"' || it == '\'' || it == ')' }
        val out = StringBuilder(css.length)
        var copied = 0
        var i = 0
        while (true) {
            val at = css.indexOf("url(", i, ignoreCase = true)
            if (at < 0) break
            var j = skipSpace(css, at + 4)
            val quote = if (j < css.length && (css[j] == '"' || css[j] == '\'')) css[j] else null
            if (quote != null) j++
            val end = term[j]
            var close = -1
            if (end < css.length) {
                if (quote == null) {
                    if (css[end] == ')') close = end
                } else if (css[end] == quote) {
                    val k = skipSpace(css, end + 1)
                    if (k < css.length && css[k] == ')') close = k
                }
            }
            if (close < 0) { i = at + 1; continue }
            out.append(css, copied, at)
            out.append(replace(css.substring(at, close + 1), css.substring(j, end)))
            copied = close + 1
            i = close + 1
        }
        if (copied == 0) return css
        out.append(css, copied, css.length)
        return out.toString()
    }

    /**
     * Every `@import` rule of [css], as `@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?[^;{]*;`
     * without case matched it, replaced by what [replace] makes of its target.
     */
    fun replaceImports(css: String, replace: (target: String) -> String): String = replaceImports(css) { target, _ -> replace(target) }

    /** [replaceImports], with the conditions that follow the target, such as a media query list, trimmed. */
    fun replaceImports(css: String, replace: (target: String, conditions: String) -> String): String {
        val ruleEnd = nextStop(css) { it == ';' || it == '{' }
        val out = StringBuilder(css.length)
        var copied = 0
        var i = 0
        while (true) {
            val at = css.indexOf("@import", i, ignoreCase = true)
            if (at < 0) break
            i = at + 1
            var j = at + 7
            if (j >= css.length || !isSpace(css[j])) continue
            j = skipSpace(css, j)
            if (css.regionMatches(j, "url(", 0, 4, ignoreCase = true)) j = skipSpace(css, j + 4)
            if (j < css.length && (css[j] == '"' || css[j] == '\'')) j++
            val start = j
            while (j < css.length && css[j] != '"' && css[j] != '\'' && css[j] != ')' && css[j] != ';' && !isSpace(css[j])) j++
            if (j == start) continue
            val target = css.substring(start, j)
            if (j < css.length && (css[j] == '"' || css[j] == '\'')) j++
            j = skipSpace(css, j)
            if (j < css.length && css[j] == ')') j++
            val end = ruleEnd[j]
            if (end >= css.length || css[end] != ';') continue
            out.append(css, copied, at)
            out.append(replace(target, css.substring(j, end).trim()))
            copied = end + 1
            i = end + 1
        }
        if (copied == 0) return css
        out.append(css, copied, css.length)
        return out.toString()
    }
}
