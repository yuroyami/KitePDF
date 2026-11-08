package io.github.yuroyami.kitepdf.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sniffing rules for books whose bytes are not the UTF-8 everyone assumes. */
class TextEncodingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun a_utf8_bom_is_stripped() {
        val b = bytes(0xEF, 0xBB, 0xBF) + "hi".encodeToByteArray()
        assertEquals("hi", TextEncoding.decode(b))
        assertEquals("UTF-8", TextEncoding.sniff(b))
    }

    @Test
    fun utf16_with_a_bom_decodes_both_ways() {
        val le = bytes(0xFF, 0xFE, 0x68, 0x00, 0x69, 0x00)
        val be = bytes(0xFE, 0xFF, 0x00, 0x68, 0x00, 0x69)
        assertEquals("hi", TextEncoding.decode(le))
        assertEquals("hi", TextEncoding.decode(be))
        assertEquals("UTF-16LE", TextEncoding.sniff(le))
        assertEquals("UTF-16BE", TextEncoding.sniff(be))
    }

    @Test
    fun utf16_without_a_bom_is_caught_by_the_xml_opener() {
        val le = "<?xml version=\"1.0\"?><p>hi</p>".flatMap { listOf(it.code, 0) }
        val b = ByteArray(le.size) { le[it].toByte() }
        assertEquals("UTF-16LE", TextEncoding.sniff(b))
        assertEquals("<?xml version=\"1.0\"?><p>hi</p>", TextEncoding.decode(b))
    }

    @Test
    fun an_xml_declaration_names_the_encoding() {
        val head = "<?xml version=\"1.0\" encoding=\"windows-1252\"?><p>"
        val b = head.encodeToByteArray() + bytes(0x93, 0x41, 0x94) + "</p>".encodeToByteArray()
        assertEquals("windows-1252", TextEncoding.sniff(b))
        assertEquals("$head“A”</p>", TextEncoding.decode(b))
    }

    @Test
    fun an_html_meta_charset_names_the_encoding() {
        val head = "<html><head><meta charset=\"iso-8859-1\"></head><body>"
        val b = head.encodeToByteArray() + bytes(0xE9) + "</body></html>".encodeToByteArray()
        assertEquals("windows-1252", TextEncoding.sniff(b), "latin-1 is decoded as its superset")
        assertEquals("${head}é</body></html>", TextEncoding.decode(b))
    }

    @Test
    fun a_legacy_http_equiv_meta_also_names_it() {
        val head = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\">"
        val b = head.encodeToByteArray() + bytes(0x80)
        assertEquals("$head€", TextEncoding.decode(b))
    }

    @Test
    fun a_bom_beats_a_contradicting_declaration() {
        val b = bytes(0xEF, 0xBB, 0xBF) +
            "<?xml version=\"1.0\" encoding=\"windows-1252\"?>".encodeToByteArray()
        assertEquals("UTF-8", TextEncoding.sniff(b))
    }

    @Test
    fun undeclared_valid_utf8_stays_utf8() {
        val b = "café 中".encodeToByteArray()
        assertEquals("UTF-8", TextEncoding.sniff(b))
        assertEquals("café 中", TextEncoding.decode(b))
    }

    @Test
    fun undeclared_bytes_that_are_not_utf8_fall_back_to_1252() {
        val b = "caf".encodeToByteArray() + bytes(0xE9)
        assertEquals("windows-1252", TextEncoding.sniff(b))
        assertEquals("café", TextEncoding.decode(b))
    }

    @Test
    fun a_declaration_that_lies_about_utf8_still_falls_back() {
        val head = "<?xml version=\"1.0\" encoding=\"utf-8\"?><p>"
        val b = head.encodeToByteArray() + bytes(0x92) + "</p>".encodeToByteArray()
        assertEquals("windows-1252", TextEncoding.sniff(b))
        assertEquals("$head’</p>", TextEncoding.decode(b))
    }

    @Test
    fun the_caller_hint_is_used_when_the_bytes_say_nothing() {
        val b = bytes(0xE9)
        assertEquals("windows-1252", TextEncoding.sniff(b, hint = "utf-8"))
        assertEquals("é", TextEncoding.decode(b, hint = "iso-8859-1"))
    }

    @Test
    fun the_1252_high_range_maps_to_its_real_characters() {
        val b = bytes(0x80, 0x85, 0x91, 0x92, 0x93, 0x94, 0x96, 0x97, 0x99, 0x8D)
        // 0x8D stands for no character of its own, so it stays the C1 control.
        assertEquals("\u20AC\u2026\u2018\u2019\u201C\u201D\u2013\u2014\u2122\u008D", TextEncoding.decode(b))
    }

    @Test
    fun an_empty_input_is_empty_text() {
        assertEquals("", TextEncoding.decode(ByteArray(0)))
    }

    @Test
    fun a_latin9_document_decodes_as_latin9() {
        // cœur 5 € ŠšŽžŒŸ in ISO-8859-15 read as c½ur 5 ¤ ¦¨´¸¼¾ (#878).
        val head = "<?xml version=\"1.0\" encoding=\"ISO-8859-15\"?><p>c".encodeToByteArray()
        val body = bytes(0xBD) + "ur 5 ".encodeToByteArray() + bytes(0xA4, 0x20, 0xA6, 0xA8, 0xB4, 0xB8, 0xBC, 0xBE) + "</p>".encodeToByteArray()
        assertEquals("ISO-8859-15", TextEncoding.sniff(head + body))
        assertTrue(TextEncoding.decode(head + body).endsWith("<p>c\u0153ur 5 \u20AC \u0160\u0161\u017D\u017E\u0152\u0178</p>"))
    }

    @Test
    fun a_declared_single_byte_encoding_decodes_with_its_own_table() {
        // Привет in windows-1251 and in KOI8-R, and Greek in ISO-8859-7, which used to read as windows-1252.
        fun doc(encoding: String, vararg body: Int) =
            "<?xml version=\"1.0\" encoding=\"$encoding\"?><p>".encodeToByteArray() + bytes(*body) + "</p>".encodeToByteArray()
        val cp1251 = doc("windows-1251", 0xCF, 0xF0, 0xE8, 0xE2, 0xE5, 0xF2)
        assertEquals("windows-1251", TextEncoding.sniff(cp1251))
        assertTrue(TextEncoding.decode(cp1251).endsWith("<p>\u041F\u0440\u0438\u0432\u0435\u0442</p>"))
        val koi8 = doc("koi8-r", 0xF0, 0xD2, 0xC9, 0xD7, 0xC5, 0xD4)
        assertEquals("KOI8-R", TextEncoding.sniff(koi8))
        assertTrue(TextEncoding.decode(koi8).endsWith("<p>\u041F\u0440\u0438\u0432\u0435\u0442</p>"))
        val greek = doc("ISO-8859-7", 0xE1, 0xE2, 0xE3)
        assertTrue(TextEncoding.decode(greek).endsWith("<p>\u03B1\u03B2\u03B3</p>"))
        // A byte the index leaves out reads as U+FFFD, as the WHATWG decoder gives it.
        assertTrue(TextEncoding.decode(doc("ISO-8859-7", 0xAE)).endsWith("<p>\uFFFD</p>"))
        // A label the standard does not know keeps the old fallback.
        assertEquals("windows-1252", TextEncoding.sniff(doc("x-unknown", 0xE9)))
    }
}
