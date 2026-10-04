package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.script.DOM_PRELUDE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DOM a chapter's scripts see is JavaScript in the book's realm, so it calls the built-ins it
 * took before the book ran, and never one a script can replace (#540). This reads the source of
 * the DOM as Node's `prefer-primordials` rule reads Node's: below the part that takes the
 * built-ins, it fails on a method called by name on anything but the host's table, on a global
 * built-in that the DOM does not shadow, on a static of one it does, on syntax that calls the
 * iterator or the instanceof of an object, on a regular expression made where a script can reach
 * its prototype, and on a descriptor, a proxy's handler or an event's dictionary that inherits.
 */
class DomPreludeSourceTest {

    @Test
    fun the_dom_calls_no_built_in_that_a_script_can_replace() {
        val start = DOM_PRELUDE.indexOf(END_OF_BUILT_INS)
        assertTrue(start > 0, "the part that takes the built-ins ends with its marker")
        assertEquals(emptyList(), violations(DOM_PRELUDE, from = start))
    }

    @Test
    fun the_check_finds_each_kind_of_call_it_looks_for() {
        // A check that finds nothing in any source would pass the test above for nothing.
        val bad = listOf(
            "list.push(x);" to "the method push",
            "o['push'](x);" to "a computed member",
            "var k = Object.keys(o);" to "the global Object",
            "var s = String.fromCharCode(65);" to "the static String.fromCharCode",
            "Promise.resolve(1);" to "the static Promise.resolve",
            "function* f() { yield* g(); }" to "yield*",
            "for (var k in o) {}" to "for-in",
            "for (var x of list) {}" to "for-of",
            "f(...args);" to "a spread",
            "if (x instanceof Y) {}" to "instanceof",
            "var re = /a/g;" to "a regular expression /a/g",
            "ObjectDefineProperty(o, 'k', { value: 1 });" to "a descriptor",
            "var p = new Proxy({}, { get: function () {} });" to "a proxy handler",
            "var e = new FocusEvent('blur', { relatedTarget: el });" to "an event dictionary",
        )
        for ((source, why) in bad) {
            val found = violations(source, from = 0)
            assertTrue(found.any { why in it }, "$source gives $found")
        }
        val good = """
            var out = [];
            ArrayPush(out, K.attr(id, 'class'));
            ObjectDefineProperty(o, 'k', { __proto__: null, value: 1 });
            var p = new Proxy({}, { __proto__: null, get: function () { return 2 / 1; } });
            var e = new FocusEvent('blur', { __proto__: null, relatedTarget: el });
            var x = (a + b) / 2, y = Error.prototype, s = 'Object.keys(o) /a/';
            for (var g = steps(); !GeneratorNext(g).done;) yield;
        """.trimIndent()
        assertEquals(emptyList(), violations(good, from = 0))
    }

    private class Token(val kind: Kind, val text: String, val at: Int)

    private enum class Kind { NAME, NUMBER, STRING, TEMPLATE, REGEX, PUNCTUATOR }

    /** The tokens of [source], without its comments and white space. */
    private fun tokens(source: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        fun regexAllowed(): Boolean {
            val last = out.lastOrNull() ?: return true
            return when (last.kind) {
                Kind.NAME -> last.text in KEYWORDS_BEFORE_EXPRESSION
                Kind.PUNCTUATOR -> last.text != ")" && last.text != "]" && last.text != "}"
                else -> false
            }
        }
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2) + 2
                c == '\'' || c == '"' || c == '`' -> {
                    var j = i + 1
                    while (source[j] != c) j += if (source[j] == '\\') 2 else 1
                    out += Token(if (c == '`') Kind.TEMPLATE else Kind.STRING, source.substring(i, j + 1), i)
                    i = j + 1
                }
                c == '/' && regexAllowed() -> {
                    var j = i + 1
                    var inClass = false
                    while (inClass || source[j] != '/') {
                        when (source[j]) {
                            '\\' -> j++
                            '[' -> inClass = true
                            ']' -> inClass = false
                        }
                        j++
                    }
                    j++
                    while (j < source.length && source[j].isLetter()) j++
                    out += Token(Kind.REGEX, source.substring(i, j), i)
                    i = j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i
                    while (j < source.length && (source[j].isLetterOrDigit() || source[j] == '_' || source[j] == '$')) j++
                    out += Token(Kind.NAME, source.substring(i, j), i)
                    i = j
                }
                c.isDigit() || (c == '.' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                    var j = i
                    while (j < source.length && (source[j].isLetterOrDigit() || source[j] == '.')) j++
                    out += Token(Kind.NUMBER, source.substring(i, j), i)
                    i = j
                }
                else -> {
                    val p = PUNCTUATORS.first { source.startsWith(it, i) }
                    out += Token(Kind.PUNCTUATOR, p, i)
                    i += p.length
                }
            }
        }
        return out
    }

    /** What [source] does, from offset [from] on, that a script could change, one line for each. */
    private fun violations(source: String, from: Int): List<String> {
        val all = tokens(source)
        val out = ArrayList<String>()
        fun line(t: Token) = source.substring(0, t.at).count { it == '\n' } + 1
        fun report(t: Token, why: String) {
            if (t.at >= from) out += "line ${line(t)}: $why"
        }
        fun text(i: Int) = all.getOrNull(i)?.text
        /** The index of the token that closes the bracket at [open]. */
        fun closing(open: Int): Int {
            var depth = 0
            for (j in open until all.size) {
                when (all[j].text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (--depth == 0) return j
                }
            }
            return all.size
        }
        /** The object literals passed as arguments of the call whose parenthesis is at [open]. */
        fun literalArguments(open: Int): List<Int> {
            val close = closing(open)
            val literals = ArrayList<Int>()
            var j = open + 1
            while (j < close) {
                if (all[j].text == "{" && (text(j - 1) == "(" || text(j - 1) == ",")) literals += j
                j = if (all[j].text in OPENERS) closing(j) + 1 else j + 1
            }
            return literals
        }
        for ((i, t) in all.withIndex()) {
            val before = all.getOrNull(i - 1)
            val after = all.getOrNull(i + 1)
            val isMember = before?.text == "."
            when (t.kind) {
                Kind.REGEX -> report(t, "a regular expression ${t.text}, whose methods a script can replace")
                Kind.PUNCTUATOR -> when {
                    t.text == "..." -> report(t, "a spread, which calls the iterator of the array")
                    t.text == "(" && before?.text == "]" -> report(t, "a call of a computed member")
                    t.text == "*" && before?.text == "yield" -> report(t, "yield*, which calls the next of the generator prototype")
                }
                Kind.NAME -> when {
                    isMember && after?.text == "(" && all.getOrNull(i - 2)?.text != "K" ->
                        report(t, "a call of the method ${t.text} by name")
                    t.text == "instanceof" -> report(t, "instanceof, which calls the Symbol.hasInstance of the constructor")
                    t.text == "for" && text(i + 1) == "(" && text(i + 4) in setOf("in", "of") ->
                        report(t, "for-${text(i + 4)}, which reads what a script can change")
                    !isMember && t.text in UNSHADOWED && after?.text != ":" ->
                        report(t, "the global ${t.text}, which a script can replace")
                    !isMember && t.text in SHADOWED && after?.text == "." && text(i + 2) != "prototype" ->
                        report(t, "the static ${t.text}.${text(i + 2)}, which a script can replace")
                    !isMember && t.text == "ObjectDefineProperty" && after?.text == "(" ->
                        for (literal in literalArguments(i + 1)) {
                            if (text(literal + 1) != "__proto__") report(all[literal], "a descriptor that inherits from Object.prototype")
                        }
                    !isMember && before?.text == "new" && t.text == "Proxy" && after?.text == "(" ->
                        for (literal in literalArguments(i + 1).drop(1)) {
                            if (text(literal + 1) != "__proto__") report(all[literal], "a proxy handler that inherits from Object.prototype")
                        }
                    !isMember && before?.text == "new" && t.text.endsWith("Event") && after?.text == "(" ->
                        for (literal in literalArguments(i + 1)) {
                            if (text(literal + 1) != "__proto__") report(all[literal], "an event dictionary that inherits from Object.prototype")
                        }
                }
                else -> {}
            }
        }
        return out
    }

    private companion object {
        const val END_OF_BUILT_INS = "/* ---- end of the built-ins ---- */"

        /** The constructors and functions that the DOM takes from the global when it starts, and shadows. */
        val SHADOWED = setOf(
            "String", "Number", "Error", "TypeError", "RangeError", "Map", "WeakMap", "Proxy", "Uint8Array", "Promise", "Function",
            "parseInt", "isNaN", "isFinite",
        )

        /** The global built-ins of ECMAScript that the DOM does not shadow. */
        val UNSHADOWED = setOf(
            "globalThis", "eval", "parseFloat", "decodeURI", "decodeURIComponent", "encodeURI", "encodeURIComponent", "escape",
            "unescape", "AggregateError", "Array", "ArrayBuffer", "Atomics", "BigInt", "BigInt64Array", "BigUint64Array", "Boolean",
            "DataView", "Date", "EvalError", "FinalizationRegistry", "Float32Array", "Float64Array", "Int8Array", "Int16Array",
            "Int32Array", "Intl", "Iterator", "JSON", "Math", "Object", "ReferenceError", "Reflect", "RegExp", "Set",
            "SharedArrayBuffer", "Symbol", "SyntaxError", "Uint8ClampedArray", "Uint16Array", "Uint32Array", "URIError", "WeakRef",
            "WeakSet",
        )

        val KEYWORDS_BEFORE_EXPRESSION = setOf("return", "typeof", "case", "in", "of", "new", "delete", "void", "throw", "instanceof", "yield", "else", "do")

        val OPENERS = setOf("(", "[", "{")

        /** The punctuators of ECMAScript, the longer of two that start alike first. */
        val PUNCTUATORS = listOf(
            ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=",
            "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<",
            ">>", "**",
            "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%", "&", "|", "^", "!", "~", "?", ":", "=", ".", "@", "#",
        )
    }
}
