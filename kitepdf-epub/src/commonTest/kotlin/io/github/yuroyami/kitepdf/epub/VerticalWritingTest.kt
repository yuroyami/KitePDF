package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Vertical writing (tategaki). A spine root resolving
 * `writing-mode: vertical-rl` lays out with the inline axis running down the
 * page and columns advancing right-to-left: full-width glyphs stand upright,
 * Latin runs rotate 90 degrees clockwise, ruby sits to the RIGHT of its base
 * column, and pagination slices along x. Horizontal books are proven
 * untouched by the draw-stream-hash harness in the native-renderer module.
 */
class VerticalWritingTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 300.0, fontSize = 10.0, margin = 20.0)

    private fun open(body: String): EpubDocument {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<style>html{writing-mode:vertical-rl}</style>$body"),
            settings,
        )
        assertNotNull(doc)
        return doc
    }

    private fun glyphCalls(doc: EpubDocument, page: Int = 0): List<RecordingCanvas.Call.Glyphs> =
        RecordingCanvas().also { doc.pages[page].renderTo(it) }
            .calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()

    @Test
    fun vertical_rl_gates_on_the_spine_root() {
        assertTrue(open("<p>縦</p>").isVertical, "vertical-rl root resolves vertical")
        val horizontal = EpubDocument.open(EpubFixtures.epub("<p>横</p>"), settings)
        assertNotNull(horizontal)
        assertFalse(horizontal.isVertical, "no writing-mode stays horizontal")
    }

    @Test
    fun upright_cjk_runs_down_the_rightmost_column() {
        val doc = open("<p>日本語のテスト</p>")
        val calls = glyphCalls(doc).filter { it.text.isNotBlank() }
        assertEquals(7, calls.size, "one upright call per full-width glyph")

        // One column: every glyph shares its x; the pen advances DOWN the
        // page by exactly 1em (canvas y is up, so f decreases by fontSize).
        val xs = calls.map { it.textToDevice.e }
        assertTrue(xs.all { abs(it - xs[0]) < 1e-6 }, "single column: $xs")
        assertTrue(
            xs[0] < 200.0 - 20.0 && xs[0] > 200.0 - 20.0 - 30.0,
            "column near the right content edge: ${xs[0]}",
        )
        val ys = calls.map { it.textToDevice.f }
        for (k in 1 until ys.size) {
            assertEquals(-10.0, ys[k] - ys[k - 1], 1e-6, "1em advance down the page")
        }
        // Golden first-glyph position: pen = margin + the paragraph's 1em
        // text-indent, glyph centre half an advance below, baseline
        // UPRIGHT_CENTER (0.38em) below that: f = 300 - (20 + 10 + 5 + 3.8).
        assertEquals(261.2, ys[0], 0.01, "golden first-glyph baseline")
    }

    @Test
    fun latin_runs_rotate_90_degrees_clockwise() {
        val doc = open("<p>縦ABC横</p>")
        val calls = glyphCalls(doc)
        val latin = calls.first { it.text == "ABC" }
        val m = latin.textToDevice
        assertEquals(0.0, m.a, 1e-9)
        assertEquals(-1.0, m.b, 1e-9)
        assertEquals(1.0, m.c, 1e-9)
        assertEquals(0.0, m.d, 1e-9)

        // The rotated baseline sits ON the column's em axis, which is
        // adv/2 - 0.38em to the right of an upright glyph's origin.
        val upright = calls.first { it.text == "縦" }
        assertEquals(1.2, m.e - upright.textToDevice.e, 0.01, "shared column axis")
        // Reading order runs down the page: the Latin run starts below 縦.
        assertTrue(m.f < upright.textToDevice.f, "ABC below the first glyph")
        // And the trailing 横 continues below the rotated segment.
        val tail = calls.first { it.text == "横" }
        assertTrue(tail.textToDevice.f < m.f, "横 below ABC")
    }

    @Test
    fun ruby_reading_sits_right_of_its_base_column() {
        val doc = open("<p>日<ruby>本<rt>ほん</rt></ruby>語</p>")
        val calls = glyphCalls(doc).filter { it.text.isNotBlank() }
        val ruby = calls.filter { it.fontSize == 5.0 }
        val base = calls.filter { it.fontSize == 10.0 }
        assertTrue(ruby.isNotEmpty(), "ruby reading painted")
        assertTrue(base.isNotEmpty(), "base text painted")
        assertTrue(
            ruby.minOf { it.textToDevice.e } > base.maxOf { it.textToDevice.e },
            "the reading's column is right of the base column",
        )
    }

    @Test
    fun pagination_slices_along_x_into_column_pages() {
        val doc = open("<p>" + "縦書きのテスト文章です。".repeat(60) + "</p>")
        assertTrue(doc.pages.size > 1, "long vertical text spans pages: ${doc.pages.size}")
        for (p in doc.pages.indices) {
            val xs = glyphCalls(doc, p).map { it.textToDevice.e }
            assertTrue(xs.isNotEmpty(), "page $p paints glyphs")
            assertTrue(
                xs.all { it > 10.0 && it < 190.0 },
                "page $p columns stay inside the page: ${xs.minOrNull()}..${xs.maxOrNull()}",
            )
        }
    }

    @Test
    fun text_extraction_keeps_logical_order() {
        val doc = open("<p>日本語のテスト</p>")
        val text = doc.pages[0].textContent().plainText
        assertContains(text, "日本語のテスト")
    }

    /* ─── vertical-lr, and the geometry a reader touches ─────────────────── */

    private fun openLr(body: String): EpubDocument {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<style>html{writing-mode:vertical-lr}</style>$body"),
            settings,
        )
        assertNotNull(doc)
        return doc
    }

    @Test
    fun vertical_lr_also_lays_out_vertically() {
        val doc = openLr("<p>\u7E26\u66F8\u304D</p>")
        assertTrue(doc.isVertical, "vertical-lr is a vertical mode")
        assertTrue(doc.isVerticalLr, "and it is the left-to-right one")
        val calls = glyphCalls(doc).filter { it.text.isNotBlank() }
        assertEquals(3, calls.size, "one upright call per full-width glyph")
    }

    @Test
    fun the_two_vertical_modes_start_from_opposite_edges() {
        val rl = glyphCalls(open("<p>\u7E26\u66F8\u304D</p>")).first { it.text.isNotBlank() }
        val lr = glyphCalls(openLr("<p>\u7E26\u66F8\u304D</p>")).first { it.text.isNotBlank() }
        assertTrue(
            lr.textToDevice.e < rl.textToDevice.e,
            "vertical-lr starts at the left edge, vertical-rl at the right (lr=${lr.textToDevice.e}, rl=${rl.textToDevice.e})",
        )
    }

    @Test
    fun a_selection_quad_on_a_vertical_page_is_a_column_slice() {
        val doc = open("<p>\u65E5\u672C\u8A9E\u306E\u30C6\u30B9\u30C8</p>")
        val text = doc.pages[0].textContent()
        val line = text.blocks.first().lines.first()
        assertTrue(line.vertical, "the line knows it is a column")
        // Char edges run DOWN the page, so they grow with the glyph index.
        assertTrue(line.charEdges.last() > line.charEdges.first(), "edges advance down the page")
        assertTrue(line.bounds.right > line.bounds.left, "the column has a width")
        assertTrue(
            line.bounds.left > doc.pageWidth / 2.0,
            "a vertical-rl column starts on the right half (got ${line.bounds.left})",
        )
    }

    @Test
    fun a_point_inside_a_column_hits_the_char_under_it() {
        val doc = open("<p>\u65E5\u672C\u8A9E\u306E\u30C6\u30B9\u30C8</p>")
        val text = doc.pages[0].textContent()
        val line = text.blocks.first().lines.first()
        val midX = (line.bounds.left + line.bounds.right) / 2.0
        val third = (line.charEdges[2] + line.charEdges[3]) / 2.0
        assertEquals(2, text.charIndexAt(midX, third), "the third glyph down is index 2")
    }

    @Test
    fun a_search_hit_on_a_vertical_page_is_quaded_down_the_column() {
        val doc = open("<p>\u65E5\u672C\u8A9E\u306E\u30C6\u30B9\u30C8</p>")
        val hits = doc.pages[0].textContent().search("\u672C\u8A9E")
        assertEquals(1, hits.size)
        val quad = hits.single().quads.single()
        assertTrue(quad.top > quad.bottom, "the match runs down the page")
        assertTrue(quad.right > quad.left, "and spans the column's width")
    }
}

/**
 * A book whose OPF declares `primary-writing-mode: vertical-rl` implies
 * right-to-left page progression, derived only when the
 * spine declares no `page-progression-direction` of its own. The signal is
 * the OPF meta, not the chapter CSS: reading a chapter at open would break
 * the lazy-open guarantee (LazyParseTest).
 */
class VerticalRtlDerivationTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 300.0, fontSize = 10.0, margin = 20.0)

    @Test
    fun a_vertical_book_with_no_declared_direction_reads_rtl() {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<p>縦</p>", primaryWritingMode = "vertical-rl"),
            settings,
        )
        assertNotNull(doc)
        assertTrue(doc.metadata.rightToLeft, "primary-writing-mode vertical-rl implies rtl progression")
    }

    @Test
    fun a_declared_ltr_direction_beats_the_derivation() {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<p>縦</p>", spineDirection = "ltr", primaryWritingMode = "vertical-rl"),
            settings,
        )
        assertNotNull(doc)
        assertFalse(doc.metadata.rightToLeft, "an explicit ltr declaration wins")
    }

    @Test
    fun a_horizontal_book_stays_ltr() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>横</p>"), settings)
        assertNotNull(doc)
        assertFalse(doc.metadata.rightToLeft)
    }
}
