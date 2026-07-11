package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.font.FontFamily
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-73: reader settings are a cascade layer above author-important CSS.
 * One test per setting; all-default settings must change nothing.
 */
class ReaderSettingsTest {

    private val body =
        """<body><p style="font-family:monospace; color:#0000ff">Alpha beta gamma delta epsilon zeta eta theta</p></body>"""

    private fun open(settings: EpubSettings, bodyHtml: String = body): EpubDocument =
        EpubDocument.open(EpubFixtures.epub(bodyHtml), settings)

    private fun glyphCalls(doc: EpubDocument): List<RecordingCanvas.Call.Glyphs> {
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, Matrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
    }

    @Test
    fun font_family_override_beats_author_css() {
        val default = glyphCalls(open(EpubSettings()))
        assertTrue(default.all { it.fontSpec.family == FontFamily.Monospace }, "author monospace honoured by default")

        val forced = glyphCalls(open(EpubSettings(fontFamily = ReaderFontFamily.SERIF)))
        assertTrue(forced.isNotEmpty() && forced.all { it.fontSpec.family == FontFamily.Serif },
            "reader SERIF must override the author's monospace")
    }

    @Test
    fun text_color_override_beats_author_color() {
        val default = glyphCalls(open(EpubSettings()))
        assertTrue(default.all { it.color.b > 0.9 && it.color.r < 0.1 }, "author blue honoured by default")

        val night = glyphCalls(open(EpubSettings(textColor = RgbColor(1.0, 1.0, 1.0))))
        assertTrue(night.isNotEmpty() && night.all { it.color.r > 0.99 && it.color.g > 0.99 && it.color.b > 0.99 },
            "reader white must override the author's blue")
    }

    @Test
    fun background_color_paints_under_everything() {
        val bg = RgbColor(0.1, 0.1, 0.2)
        val canvas = RecordingCanvas()
        open(EpubSettings(backgroundColor = bg)).pages[0].renderTo(canvas, Matrix.IDENTITY)
        val paintCalls = canvas.calls.filter { it is RecordingCanvas.Call.Fill || it is RecordingCanvas.Call.Glyphs }
        val first = paintCalls.first() as RecordingCanvas.Call.Fill
        assertEquals(bg, first.color, "first paint is the reader background")

        // Default: no page-background fill precedes the text.
        val plain = RecordingCanvas()
        open(EpubSettings()).pages[0].renderTo(plain, Matrix.IDENTITY)
        val fills = plain.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertTrue(fills.none { it.color == bg }, "no reader background by default")
    }

    @Test
    fun line_height_scale_multiplies_leading() {
        val two = "<body><p>one line here</p><p>second paragraph line</p></body>"
        fun firstBlockTop(doc: EpubDocument) = doc.pages[0].textContent().blocks.map { it.lines.first().bounds.top }
        val d1 = open(EpubSettings(), two)
        val d2 = open(EpubSettings(lineHeightScale = 2.0), two)
        val gap1 = firstBlockTop(d1)[1] - firstBlockTop(d1)[0]
        val gap2 = firstBlockTop(d2)[1] - firstBlockTop(d2)[0]
        assertTrue(gap2 > gap1 * 1.5, "doubling line height must visibly grow the block gap: $gap1 -> $gap2")
    }

    @Test
    fun justify_true_fills_lines_and_false_unjustifies() {
        val text = "<body><p>${"word ".repeat(60)}</p></body>"
        val contentWidth = 400.0 - 2 * 36.0 - 2 * 12.0 // page margins + UA body 1em margins

        val forced = open(EpubSettings(justify = true), text)
        val lines = forced.pages[0].textContent().blocks[0].lines
        assertTrue(lines.size > 2, "fixture wraps")
        for (line in lines.dropLast(1)) {
            assertTrue(abs(line.bounds.right - (36.0 + 12.0 + contentWidth)) <= 1.0,
                "justified line must reach the content edge: right=${line.bounds.right}")
        }

        val authored = """<body><p style="text-align:justify">${"word ".repeat(60)}</p></body>"""
        val unjust = open(EpubSettings(justify = false), authored)
        val ragged = unjust.pages[0].textContent().blocks[0].lines
        assertTrue(ragged.dropLast(1).any { abs(it.bounds.right - (36.0 + 12.0 + contentWidth)) > 1.0 },
            "justify=false must defeat the author's text-align:justify")
    }

    @Test
    fun use_publisher_css_false_drops_author_rules_and_inline_styles() {
        val styled = """<body><p style="color:#ff0000; font-family:monospace">Styled text here</p></body>"""
        val default = glyphCalls(open(EpubSettings(), styled))
        assertTrue(default.all { it.color.r > 0.9 }, "inline red honoured by default")

        val stripped = glyphCalls(open(EpubSettings(usePublisherCss = false), styled))
        assertTrue(stripped.isNotEmpty() && stripped.all { it.color.r < 0.1 && it.color.g < 0.1 && it.color.b < 0.1 },
            "usePublisherCss=false leaves UA black text")
        assertTrue(stripped.all { it.fontSpec.family != FontFamily.Monospace },
            "inline monospace dropped with publisher CSS")
    }

    @Test
    fun all_default_settings_change_nothing() {
        // The reader layer must be inert by default: identical draw stream for
        // an explicitly-constructed default settings object.
        val a = RecordingCanvas()
        open(EpubSettings()).pages[0].renderTo(a, Matrix.IDENTITY)
        val b = RecordingCanvas()
        open(
            EpubSettings(
                fontFamily = null, lineHeightScale = 1.0, textColor = null,
                backgroundColor = null, justify = null, usePublisherCss = true,
            ),
        ).pages[0].renderTo(b, Matrix.IDENTITY)
        assertEquals(a.calls.size, b.calls.size)
        assertEquals(
            a.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>(),
            b.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>(),
        )
    }
}
