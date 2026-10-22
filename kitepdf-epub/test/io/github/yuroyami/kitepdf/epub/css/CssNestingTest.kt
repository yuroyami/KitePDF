package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.KiteWarningSink
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.HtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Excessive CSS nesting is skipped without losing the surrounding stylesheet (#451). */
class CssNestingTest {

    private fun nest(open: String, depth: Int, body: String) =
        open.repeat(depth) + body + "}".repeat(depth * open.count { it == '{' })

    private fun warningsDuring(block: () -> Unit): List<String> {
        val previous = KiteWarnings.sink
        val warnings = ArrayList<String>()
        KiteWarnings.sink = KiteWarningSink { warnings.add(it) }
        try {
            block()
        } finally {
            KiteWarnings.sink = previous
        }
        return warnings
    }

    private fun tags(css: ParsedCss): List<String?> = css.rules.map { it.selectors.single().parts.single().tag }

    @Test
    fun media_and_supports_at_the_limit_keep_rules_and_font_faces() {
        for (open in listOf("@media screen{", "@supports (display:block){", "@media screen{@supports (display:block){")) {
            val depth = CssParser.MAX_NESTING / open.count { it == '{' }
            val warnings = warningsDuring {
                val parsed = CssParser.parseAll(
                    nest(open, depth, "p{color:red}@font-face{font-family:kept;src:url(kept.otf)}"),
                    Origin.AUTHOR,
                )
                assertEquals(listOf("p"), tags(parsed))
                assertEquals(listOf("kept"), parsed.fontFaces.map { it.family })
            }
            assertTrue(warnings.isEmpty(), "valid nesting emits no warning: $warnings")
        }
    }

    @Test
    fun blocks_beyond_the_limit_keep_siblings_at_the_limit_and_after_the_outer_block() {
        for (open in listOf("@media screen{", "@supports (display:block){")) {
            val warnings = warningsDuring {
                val parsed = CssParser.parseAll(
                    "h1{color:blue}" + nest(open, CssParser.MAX_NESTING,
                        "p{color:red}" + nest(open, 1, "b{color:red}@font-face{font-family:lost;src:url(lost.otf)}") +
                            "em{color:green}@font-face{font-family:kept;src:url(kept.otf)}",
                    ) + "h2{color:green}",
                    Origin.AUTHOR,
                )
                assertEquals(listOf("h1", "p", "em", "h2"), tags(parsed))
                assertEquals(listOf("kept"), parsed.fontFaces.map { it.family })
            }
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("CSS blocks nested"))
        }
    }

    @Test
    fun thousands_of_mixed_blocks_are_skipped_with_one_warning_and_recovery() {
        val warnings = warningsDuring {
            val css = "h1{color:blue}" + "@media screen{@supports (display:block){".repeat(2_000) +
                "p{color:red}" + "}}".repeat(2_000) + "h2{color:green}"
            assertEquals(listOf("h1", "h2"), tags(CssParser.parseAll(css, Origin.AUTHOR)))
        }
        assertEquals(1, warnings.size)
    }

    @Test
    fun an_unclosed_overdeep_block_keeps_the_rules_before_it() {
        val warnings = warningsDuring {
            val css = "p{color:blue}" + "@media screen{".repeat(2_000) + "p{color:red}"
            assertEquals(listOf("p"), tags(CssParser.parseAll(css, Origin.AUTHOR)))
        }
        assertEquals(1, warnings.size)
    }

    @Test
    fun nested_negation_matches_as_written_and_an_overdeep_one_drops_only_its_rule() {
        val paragraph = HtmlParser.parse("<p>x</p>").children.filterIsInstance<KiteXmlNode.Element>().single()
        for (depth in listOf(2, 3, CssParser.MAX_NESTING)) {
            val warnings = warningsDuring {
                val css = ":not(".repeat(depth) + ".hidden" + ")".repeat(depth) + "{color:red}p{color:blue}"
                val rules = CssParser.parse(css, Origin.AUTHOR)
                assertEquals(2, rules.size)
                // An even number of negations is .hidden, which the paragraph is not.
                assertEquals(depth % 2 == 1, rules.first().selectors.single().matches(paragraph), "depth $depth")
                assertTrue(rules.last().selectors.single().matches(paragraph))
            }
            assertTrue(warnings.isEmpty(), "nesting within the limit emits no warning: $warnings")
        }
        for (depth in listOf(CssParser.MAX_NESTING + 1, 2_000)) {
            val warnings = warningsDuring {
                val css = ":not(".repeat(depth) + ".hidden" + ")".repeat(depth) + "{color:red}p{color:blue}"
                val rules = CssParser.parse(css, Origin.AUTHOR)
                assertEquals(1, rules.size)
                assertTrue(rules.single().selectors.single().matches(paragraph))
            }
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("nested beyond"))
        }
    }

    @Test
    fun sibling_negations_and_negation_text_in_attributes_are_not_nesting() {
        val paragraph = HtmlParser.parse("""<p data-note=":not(.x)">x</p>""").children.filterIsInstance<KiteXmlNode.Element>().single()
        val warnings = warningsDuring {
            assertTrue(assertNotNull(Selector.parse("p:not(.a):not(.b)")).matches(paragraph))
            assertFalse(assertNotNull(Selector.parse("""p:not([data-note=":not(.x)"])""")).matches(paragraph))
            assertTrue(assertNotNull(Selector.parse("p:not([data-note=other])")).matches(paragraph))
        }
        assertTrue(warnings.isEmpty(), "ordinary negation emits no warning: $warnings")
    }
}
