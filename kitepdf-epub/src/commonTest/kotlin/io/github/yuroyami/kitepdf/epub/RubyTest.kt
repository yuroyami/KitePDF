package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-64: `<ruby>` support. Step 1: the reading (`<rt>`) is never spliced inline
 * into the base text and `<rp>` fallback punctuation never renders. Step 2:
 * the reading paints as its own overlay run at half size, centered above the
 * base, the line grows by the reading's ascent, and a reading wider than its
 * base pads the base's envelope symmetrically.
 */
class RubyTest {

    private fun glyphCalls(body: String): List<RecordingCanvas.Call.Glyphs> {
        val doc = EpubDocument.open(EpubFixtures.epub(body))
        return doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
    }

    /* ── step 1: no text corruption ──────────────────────────────────────── */

    @Test
    fun ruby_reading_is_not_spliced_into_the_base_text() {
        val runs = glyphCalls("<body><p>その<ruby>漢字<rt>かんじ</rt></ruby>です</p></body>")
        val all = runs.joinToString("") { it.text }
        assertTrue("漢字" in all, "the ruby base must render")
        assertTrue("その" in all && "です" in all, "surrounding text must render")
        assertTrue(
            runs.none { "漢字かんじ" in it.text || "かんじです" in it.text },
            "the reading must never run together with the base text",
        )
    }

    @Test
    fun ruby_fallback_parentheses_are_dropped() {
        val runs = glyphCalls("<body><p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>字</p></body>")
        val all = runs.joinToString("") { it.text }
        assertTrue("漢" in all && "字" in all)
        assertFalse("(" in all || ")" in all, "<rp> fallback punctuation must not render")
    }

    /* ── step 2: the reading paints above the base ───────────────────────── */

    @Test
    fun reading_renders_as_separate_overlay_run() {
        val runs = glyphCalls("<body><p>その<ruby>漢字<rt>かんじ</rt></ruby>です</p></body>")
        assertTrue(runs.any { it.text == "かんじ" }, "the reading renders as its own run")
    }

    @Test
    fun reading_sits_above_the_base_at_half_size() {
        // Default settings: fontSize 12pt, margin 36pt, generic (system) metrics.
        val runs = glyphCalls("<body><p><ruby>漢字<rt>かんじ</rt></ruby></p></body>")
        val base = runs.first { "漢" in it.text }
        val ruby = runs.first { it.text == "かんじ" }
        assertEquals(6.0, ruby.fontSize, 1e-9, "reading is 0.5em of the 12pt base")
        // renderTo was driven with an identity device CTM, so f is the y-up page
        // coordinate: the overlay's baseline sits base-ascent (0.8 x 12 = 9.6pt)
        // ABOVE the base baseline.
        assertEquals(9.6, ruby.textToDevice.f - base.textToDevice.f, 1e-6, "reading baseline raise")
        // Centered: the two runs share a horizontal midpoint.
        val baseW = base.glyphs.sumOf { it.advanceWidth } * 12.0 / 1000.0
        val rubyW = ruby.glyphs.sumOf { it.advanceWidth } * 6.0 / 1000.0
        assertEquals(
            base.textToDevice.e + baseW / 2, ruby.textToDevice.e + rubyW / 2, 1e-6,
            "reading centered over its base",
        )
    }

    @Test
    fun line_with_ruby_grows_by_the_reading_ascent() {
        // A ruby line grows by the reading ascent (0.5 x 12 x 0.8 = 4.8pt), so
        // the SECOND line sits 4.8pt lower than in the ruby-free document.
        // (Baseline-to-baseline distance alone can't show it: the ruby line's
        // ascent grows by the same amount, cancelling out in that difference.)
        fun line2Baseline(body: String): Double {
            val runs = glyphCalls(body)
            return runs.first { "い" in it.text }.textToDevice.f // identity CTM: y-up
        }
        val plain = line2Baseline("<body><p>あ<br/>い</p></body>")
        val ruby = line2Baseline("<body><p><ruby>あ<rt>x</rt></ruby><br/>い</p></body>")
        assertEquals(4.8, plain - ruby, 1e-6, "line 2 drops by the ruby line's extra ascent")
    }

    @Test
    fun wide_reading_pads_the_base_envelope_symmetrically() {
        // Base 漢 is one 12pt-wide CJK cell; the 4-kana reading at 6pt is 24pt
        // wide, so the base envelope pads to 24pt and the next char starts there.
        val runs = glyphCalls("<body><p><ruby>漢<rt>かんじゃ</rt></ruby>あ</p></body>")
        // Content origin: 36pt page margin + the UA sheet's body{margin:1em} = 12pt.
        val origin = 36.0 + 12.0
        val ruby = runs.first { it.text == "かんじゃ" }
        val next = runs.first { it.text == "あ" }
        val rubyW = ruby.glyphs.sumOf { it.advanceWidth } * 6.0 / 1000.0
        assertTrue(rubyW > 12.0, "precondition: reading wider than its base")
        assertEquals(origin + rubyW, next.textToDevice.e, 0.01, "next char starts after the padded envelope")
        // Base is centered inside the padded envelope.
        val base = runs.first { it.text == "漢" }
        val baseW = base.glyphs.sumOf { it.advanceWidth } * 12.0 / 1000.0
        assertEquals(origin + rubyW / 2, base.textToDevice.e + baseW / 2, 0.01, "base centered in envelope")
    }

    @Test
    fun ruby_base_never_splits_across_lines() {
        // A ruby base of several CJK chars must stay one token even at a width
        // where plain CJK would break per character: the whole base wraps to
        // line 2 instead of splitting.
        val runs = glyphCalls(
            "<body><p>あああ<ruby>漢字熟語<rt>かんじじゅくご</rt></ruby></p></body>",
        )
        val baseRun = runs.first { "漢" in it.text }
        assertTrue("漢字熟語" in baseRun.text, "the ruby base stays one unbroken run")
    }
}
