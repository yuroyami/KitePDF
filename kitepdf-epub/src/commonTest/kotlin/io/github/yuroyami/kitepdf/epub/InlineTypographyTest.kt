package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-67: text-transform, letter-spacing, word-spacing, font-variant:
 * small-caps. Geometry asserts use the extraction charEdges (which mirror the
 * wrap advances); the synthesized small-caps path asserts drawn run sizes.
 */
class InlineTypographyTest {

    private fun open(body: String, css: String = "", pageWidth: Double = 400.0): EpubDocument =
        EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style>$body</body>"),
            EpubSettings(pageWidth = pageWidth, pageHeight = 640.0),
        ) ?: error("fixture failed to open")

    private fun firstLine(doc: EpubDocument) = doc.pages[0].textContent().blocks[0].lines[0]

    /* ── text-transform ──────────────────────────────────────────────────── */

    @Test
    fun uppercase_and_lowercase() {
        assertEquals("HELLO WORLD", firstLine(open("<p>hello world</p>", "p{text-transform:uppercase}")).text)
        assertEquals("hello world", firstLine(open("<p>HELLO WORLD</p>", "p{text-transform:lowercase}")).text)
    }

    @Test
    fun capitalize_uppercases_word_starts_only() {
        assertEquals(
            "Hello Brave World",
            firstLine(open("<p>hello brave world</p>", "p{text-transform:capitalize}")).text,
        )
        // Apostrophes are not word separators.
        assertEquals("Don't", firstLine(open("<p>don't</p>", "p{text-transform:capitalize}")).text)
    }

    @Test
    fun capitalize_survives_a_mid_word_inline_split() {
        // The word continues across an inline element: no capital on the tail.
        assertEquals(
            "Hello",
            firstLine(open("<p>he<span>llo</span></p>", "p{text-transform:capitalize}")).text,
        )
    }

    /* ── letter-spacing / word-spacing ───────────────────────────────────── */

    @Test
    fun letter_spacing_widens_every_advance() {
        val plain = firstLine(open("<p>abc</p>"))
        val spaced = firstLine(open("<p>abc</p>", "p{letter-spacing:2pt}"))
        for (i in 0 until 3) {
            val d0 = plain.charEdges[i + 1] - plain.charEdges[i]
            val d1 = spaced.charEdges[i + 1] - spaced.charEdges[i]
            assertEquals(2.0, d1 - d0, 0.05, "char $i advance grows by the letter-spacing")
        }
    }

    @Test
    fun word_spacing_widens_spaces_only() {
        val plain = firstLine(open("<p>a b</p>"))
        val spaced = firstLine(open("<p>a b</p>", "p{word-spacing:5pt}"))
        val spacePlain = plain.charEdges[2] - plain.charEdges[1]
        val spaceSpaced = spaced.charEdges[2] - spaced.charEdges[1]
        assertEquals(5.0, spaceSpaced - spacePlain, 0.05, "the space advance grows by word-spacing")
        assertEquals(
            plain.charEdges[1] - plain.charEdges[0],
            spaced.charEdges[1] - spaced.charEdges[0],
            1e-9,
            "letter advances unchanged",
        )
    }

    @Test
    fun justify_still_fills_the_line_with_letter_spacing() {
        val doc = open(
            "<p>alpha beta gamma delta epsilon zeta eta theta iota kappa</p>",
            "p{text-align:justify;letter-spacing:1pt;margin:0}",
            pageWidth = 220.0,
        )
        val block = doc.pages[0].textContent().blocks[0]
        assertTrue(block.lines.size >= 2, "precondition: wrapped")
        val line = block.lines[0]
        // Content width: 220 - 72 page margins - 24 body margin = 124pt.
        val width = line.charEdges.last() - line.charEdges.first()
        assertEquals(124.0, width, 0.6, "justified line fills the content width")
    }

    /* ── small-caps ──────────────────────────────────────────────────────── */

    @Test
    fun synthesized_small_caps_draws_uppercase_at_reduced_size() {
        val doc = open("<p>Hi there</p>", "p{font-variant:small-caps}")
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        // 'H' stays full size; 'i'/'there' render as uppercase at 0.8x.
        val full = runs.first { "H" in it.text }
        val small = runs.first { "I" in it.text && it.fontSize < 12.0 }
        assertEquals(12.0, full.fontSize, 1e-9)
        assertEquals(9.6, small.fontSize, 1e-9, "synthesized small caps at 0.8em")
        val smallText = runs.filter { it.fontSize < 12.0 }.joinToString("") { it.text }
        assertTrue(smallText.all { !it.isLowerCase() }, "small-cap glyphs are uppercase forms (got '$smallText')")
    }

    @Test
    fun small_caps_leaves_uppercase_and_digits_alone() {
        val doc = open("<p>AB12</p>", "p{font-variant:small-caps}")
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertTrue(runs.all { it.fontSize == 12.0 }, "nothing to synthesize: single full-size run")
    }
}
