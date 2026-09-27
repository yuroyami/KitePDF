package io.github.yuroyami.kitepdf.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IDE quick fix of each deprecated viewer function keeps every argument where it belongs:
 * each one passed by name, and none dropped. A positional pattern put `overlay` into
 * `chapterPlaceholder` and dropped `onLinkTap` with no compile error (#435).
 */
class DeprecatedReplacementsTest {

    private val source = File("src/commonMain/kotlin/io/github/yuroyami/kitepdf/compose/Deprecated.kt").readText()

    /** One deprecated function: its name, its parameters, and its quick fix, or null when it has none. */
    private class Deprecation(val name: String, val parameters: List<String>, val replacement: String?)

    private fun deprecations(): List<Deprecation> = source.split("@Deprecated(").drop(1).mapNotNull { chunk ->
        val signature = Regex("""public fun (?:BoxScope\.)?(\w+)\(""").find(chunk) ?: return@mapNotNull null
        val head = chunk.substring(0, signature.range.first)
        val replacement = Regex("""ReplaceWith\((.*?)\)\s*,?\s*\)""", RegexOption.DOT_MATCHES_ALL).find(head)?.let { found ->
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(found.groupValues[1]).joinToString("") { it.groupValues[1] }
        }
        val parameters = topLevel(chunk, signature.range.last + 1).map { it.trim().substringBefore(':').trim() }
        Deprecation(signature.groupValues[1], parameters.filter { it.isNotEmpty() }, replacement)
    }

    /** The comma-separated parts of the list that opens at [start], split at depth zero only. */
    private fun topLevel(text: String, start: Int): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var current = StringBuilder()
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                c == '(' || c == '<' || c == '{' || c == '[' -> depth++
                (c == ')' || c == '>' || c == '}' || c == ']') && depth > 0 -> depth--
                c == ')' && depth == 0 -> {
                    parts += current.toString()
                    return parts
                }
                c == '-' && i + 1 < text.length && text[i + 1] == '>' -> {
                    // An arrow is not a closing bracket.
                    current.append("->")
                    i += 2
                    continue
                }
                c == ',' && depth == 0 -> {
                    parts += current.toString()
                    current = StringBuilder()
                    i++
                    continue
                }
            }
            current.append(c)
            i++
        }
        error("unbalanced list at $start")
    }

    @Test
    fun every_quick_fix_passes_every_argument_by_name() {
        val found = deprecations()
        assertTrue(found.size >= 10, "the source was not read: ${found.map { it.name }}")
        for (deprecation in found) {
            val replacement = deprecation.replacement ?: continue
            val open = replacement.indexOf('(')
            if (open < 0) continue
            val arguments = topLevel(replacement, open + 1).map { it.trim() }.filter { it.isNotEmpty() }
            val named = arguments.map { argument ->
                val match = Regex("""^(\w+)\s*=\s*(\w+)$""").find(argument)
                    ?: throw AssertionError("${deprecation.name}: `$argument` is not passed by name as itself")
                assertEquals(match.groupValues[1], match.groupValues[2], "${deprecation.name}: `$argument` moves an argument")
                match.groupValues[1]
            }
            assertEquals(deprecation.parameters.sorted(), named.sorted(), "${deprecation.name}: the quick fix drops or adds an argument")
        }
    }

    @Test
    fun a_function_whose_link_callback_changed_type_has_no_quick_fix() {
        val withLinks = deprecations().filter { "onLinkTap" in it.parameters }
        assertEquals(3, withLinks.size, "PdfView twice and EpubView: ${withLinks.map { it.name }}")
        for (deprecation in withLinks) {
            assertEquals(null, deprecation.replacement, "${deprecation.name} has a quick fix that cannot keep onLinkTap")
        }
    }
}
