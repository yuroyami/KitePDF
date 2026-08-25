package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GlyphList
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #6, second half: Cyrillic used to fall to the flat no-glyph fallback
 * (500, Courier 600) because uniToGlyph only ingested the WinAnsi and
 * Standard encodings. The Standard-14 AFM data in core already carries exact
 * per-face Cyrillic widths under AFII glyph names; these tests pin that the
 * mapping now reaches them, face by face and style by style.
 */
class FontMetricsCyrillicTest {

    private fun w(ch: Char, family: GenericFont = GenericFont.SERIF, bold: Boolean = false, italic: Boolean = false) =
        FontMetrics.advance1000(ch.code, bold = bold, italic = italic, family = family)

    /** The AFII name for [ch], resolved the same way the fix resolves it. */
    private fun afiiNameOf(ch: Char): String? =
        (10017..10196).firstNotNullOfOrNull { n ->
            "afii$n".takeIf { GlyphList.unicodeFor(it) == ch.code }
        }

    private val russian = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    private val ukrainianAndBelarusian = "іїєґў"

    private fun assertExact(ch: Char, face: String, family: GenericFont, bold: Boolean, italic: Boolean) {
        val name = afiiNameOf(ch)
        assertNotNull(name, "no AFII name for '$ch' (U+${ch.code.toString(16)})")
        val expected = Standard14Widths.widthOf(face, name)
        assertNotNull(expected, "$face has no width for $name ('$ch')")
        assertEquals(expected, w(ch, family, bold, italic), "'$ch' in $face")
    }

    @Test
    fun every_russian_letter_is_exact_in_every_serif_and_sans_style() {
        val faces = listOf(
            Triple("Times-Roman", GenericFont.SERIF, false to false),
            Triple("Times-Italic", GenericFont.SERIF, false to true),
            Triple("Times-Bold", GenericFont.SERIF, true to false),
            Triple("Times-BoldItalic", GenericFont.SERIF, true to true),
            Triple("Helvetica", GenericFont.SANS, false to false),
            Triple("Helvetica-Oblique", GenericFont.SANS, false to true),
            Triple("Helvetica-Bold", GenericFont.SANS, true to false),
            Triple("Helvetica-BoldOblique", GenericFont.SANS, true to true),
        )
        for ((face, family, style) in faces) {
            for (ch in russian + russian.uppercase()) {
                assertExact(ch, face, family, style.first, style.second)
            }
        }
    }

    @Test
    fun the_reviewed_spot_values_hold() {
        // Values checked against the AFM data during the GH2 review, pinned
        // as numbers so a regressed mapping cannot hide behind a
        // self-consistent helper.
        assertEquals(833, w('ш', family = GenericFont.SANS, bold = true), "Helvetica-Bold sha")
        assertEquals(770, w('ш'), "Times-Roman sha")
        assertEquals(722, w('А'), "Times-Roman capital A")
    }

    @Test
    fun ukrainian_and_belarusian_extras_are_exact() {
        for (ch in ukrainianAndBelarusian + ukrainianAndBelarusian.uppercase()) {
            assertExact(ch, "Times-Roman", GenericFont.SERIF, bold = false, italic = false)
        }
    }

    @Test
    fun courier_stays_monospaced() {
        for (ch in (russian + russian.uppercase() + ukrainianAndBelarusian)) {
            assertEquals(600, w(ch, family = GenericFont.MONO), "'$ch' in Courier")
        }
    }

    @Test
    fun latin_is_untouched_by_the_ingest_order() {
        assertEquals(Standard14Widths.widthOf("Times-Roman", "a"), w('a'))
        assertEquals(Standard14Widths.widthOf("Helvetica-Bold", "m"), w('m', family = GenericFont.SANS, bold = true))
    }

    @Test
    fun width_classes_are_told_apart() {
        assertTrue(w('ш') > w('н'), "sha must be wider than en")
        assertTrue(w('н') > w('г'), "en must be wider than ghe")
        assertTrue(w('м') > w('и'), "em must be wider than i")
    }
}
