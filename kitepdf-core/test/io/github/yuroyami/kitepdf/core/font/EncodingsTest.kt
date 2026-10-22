package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four encodings in [Encodings] are reproducible from public data, and
 * these tests check the reproduction rules rather than trusting the values.
 *
 * The rules come from ISO 32000-1, Annex D. Where a rule says an encoding is
 * some platform code page, the check is against a property of that page that
 * holds independently of this table: for the upper half of WinAnsi, code page
 * 1252 agrees with Latin-1, so a glyph name there must resolve back to its own
 * byte value through the Adobe Glyph List.
 */
class EncodingsTest {

    @Test
    fun every_table_covers_all_256_codes() {
        assertEquals(256, Encodings.pdfDocToUnicode.size)
        assertEquals(256, Encodings.standardEncoding.size)
        assertEquals(256, Encodings.macRomanEncoding.size)
        assertEquals(256, Encodings.winAnsiEncoding.size)
        assertEquals(256, Encodings.macExpertEncoding.size)
    }

    @Test
    fun mac_expert_carries_the_full_expert_vector() {
        val t = Encodings.macExpertEncoding
        // 165 defined codes, per the vector in ISO 32000-1 Annex D.4.
        assertEquals(165, t.count { it != null })
        // Spot checks across every region of the table.
        assertEquals("space", t[32])
        assertEquals("Acutesmall", t[39])
        assertEquals("zerooldstyle", t[48])
        assertEquals("threequartersemdash", t[61])
        assertEquals("Ethsmall", t[68])
        assertEquals("ff", t[86])
        assertEquals("parenleftinferior", t[91])
        assertEquals("Asmall", t[97])
        assertEquals("rupiah", t[125])
        assertEquals("eightsuperior", t[161])
        assertEquals("AEsmall", t[190])
        assertEquals("questiondownsmall", t[192])
        assertEquals("onefitted", t[124])
        assertNull(t[0])
        assertNull(t[127])
        assertNull(t[255])
    }

    @Test
    fun every_glyph_name_resolves_in_the_adobe_glyph_list() {
        val tables = mapOf(
            "standardEncoding" to Encodings.standardEncoding,
            "macRomanEncoding" to Encodings.macRomanEncoding,
            "winAnsiEncoding" to Encodings.winAnsiEncoding,
            "macExpertEncoding" to Encodings.macExpertEncoding,
        )
        for ((name, table) in tables) {
            for (code in 0..255) {
                val glyph = table[code] ?: continue
                assertNotNull(
                    GlyphList.unicodeFor(glyph),
                    "$name code $code names '$glyph', which is not in the Adobe Glyph List",
                )
            }
        }
    }

    /**
     * Code page 1252 agrees with Latin-1 from 0xA0 up, so each glyph name there
     * must map back to its own byte, except the two codes Annex D reassigns.
     */
    @Test
    fun winansi_upper_half_agrees_with_latin1_through_the_glyph_list() {
        for (code in 0xA0..0xFF) {
            if (code == 0xA0 || code == 0xAD) continue
            val glyph = assertNotNull(Encodings.winAnsiEncoding[code], "WinAnsi $code is undefined")
            assertEquals(
                code, GlyphList.unicodeFor(glyph),
                "WinAnsi $code names '$glyph', which is not U+${code.toString(16)}",
            )
        }
    }

    @Test
    fun winansi_follows_the_three_rules_annex_d_states() {
        assertEquals("space", Encodings.winAnsiEncoding[0xA0], "no-break space shows as space")
        assertEquals("hyphen", Encodings.winAnsiEncoding[0xAD], "soft hyphen shows as hyphen")
        // Every code above octal 40 that the page leaves unused shows as bullet,
        // so nothing in that range is undefined.
        for (code in 0x20..0xFF) {
            assertNotNull(Encodings.winAnsiEncoding[code], "WinAnsi $code should not be undefined")
        }
        assertEquals("bullet", Encodings.winAnsiEncoding[0x81], "an unused code shows as bullet")
    }

    @Test
    fun macroman_is_mac_os_roman_for_latin_text_only() {
        // The codes whose Mac OS Roman glyph falls outside the Latin text
        // repertoire: mathematical operators, two Greek letters, the lozenge
        // and the private-use logo. PDF leaves them undefined.
        val outsideLatinText = listOf(
            0xAD, 0xB0, 0xB2, 0xB3, 0xB6, 0xB7, 0xB8, 0xB9,
            0xBA, 0xBD, 0xC3, 0xC5, 0xC6, 0xD7, 0xF0,
        )
        for (code in outsideLatinText) {
            assertNull(Encodings.macRomanEncoding[code], "MacRoman $code should be undefined")
        }
        assertEquals("space", Encodings.macRomanEncoding[0xCA], "no-break space shows as space")
        assertEquals("currency", Encodings.macRomanEncoding[0xDB], "the pre-Mac OS 8.5 assignment")
    }

    @Test
    fun standard_encoding_matches_ascii_apart_from_the_two_quotes() {
        for (code in 0x20..0x7E) {
            val expected = when (code) {
                0x27 -> "quoteright"
                0x60 -> "quoteleft"
                else -> Encodings.winAnsiEncoding[code]
            }
            assertEquals(
                expected, Encodings.standardEncoding[code],
                "StandardEncoding $code should be $expected",
            )
        }
    }

    @Test
    fun pdfdoc_is_latin1_with_the_deviations_annex_d_states() {
        // Latin-1 identity across the printable ASCII range and the upper half.
        for (code in 0x20..0x7E) {
            assertEquals(code, Encodings.pdfDocToUnicode[code], "PDFDoc $code should be U+$code")
        }
        for (code in 0xA1..0xFF) {
            if (code == 0xAD) continue
            assertEquals(code, Encodings.pdfDocToUnicode[code], "PDFDoc $code should be U+$code")
        }
        assertEquals(0x20AC, Encodings.pdfDocToUnicode[0xA0], "PDFDoc puts the Euro here, not a no-break space")
        assertEquals(0, Encodings.pdfDocToUnicode[0x7F], "DEL is undefined")
        assertEquals(0, Encodings.pdfDocToUnicode[0xAD], "soft hyphen is undefined")
        // The accent block PDFDoc puts in the C0 space.
        assertEquals(0x02D8, Encodings.pdfDocToUnicode[0x18], "breve")
        assertEquals(0x02DC, Encodings.pdfDocToUnicode[0x1F], "tilde")
    }

    @Test
    fun the_glyph_list_has_no_truncated_duplicate_names() {
        // A generation slip once left "ilde" beside "tilde" at U+02DC. A name
        // that is a proper suffix of another name at the same codepoint is the
        // signature of that mistake.
        assertNull(GlyphList.unicodeFor("ilde"), "'ilde' is not an Adobe Glyph List name")
        assertEquals(0x02DC, GlyphList.unicodeFor("tilde"))
    }
}
