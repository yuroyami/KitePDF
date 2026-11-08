package io.github.yuroyami.kitepdf.core.parser

import kotlin.test.Test
import kotlin.test.assertEquals

/** A text string is UTF-16BE, UTF-8 or PDFDocEncoding by its byte order mark (ISO 32000-2, 7.9.2.2.1; #690). */
class PdfTextStringTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun withoutAMarkTheStringIsPdfDocEncoding() {
        // 0x84 em dash, 0xA0 euro, 0x93 fi, 0x96 OE, 0x8E right double quote (Annex D.3).
        val s = PdfString(bytes('A'.code, 0x84, 'B'.code, ' '.code, 0xA0, ' '.code, 0x93, ' '.code, 0x96, 0x8E))
        assertEquals("A—B € ﬁ Œ”", s.asText())
        assertEquals("Café", PdfString(bytes('C'.code, 'a'.code, 'f'.code, 0xE9)).asText())
    }

    @Test
    fun aUtf8MarkStartsAUtf8String() {
        val s = PdfString(bytes(0xEF, 0xBB, 0xBF) + "Café €".encodeToByteArray())
        assertEquals("Café €", s.asText())
    }

    @Test
    fun aUtf16MarkStillStartsUtf16() {
        assertEquals("é", PdfString(bytes(0xFE, 0xFF, 0x00, 0xE9)).asText())
    }
}
