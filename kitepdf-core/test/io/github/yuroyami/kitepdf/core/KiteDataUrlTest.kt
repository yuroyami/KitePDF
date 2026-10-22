package io.github.yuroyami.kitepdf.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `data:` URLs decode as the data URL processor of the Fetch standard does (#514). */
class KiteDataUrlTest {

    @Test
    fun base64_forgives_white_space_and_missing_padding() {
        val url = KiteDataUrl.decode("data:image/png;base64,SGVs\n bG8g\td29y bGQ")!!
        assertEquals("image/png", url.mediaType)
        assertEquals("Hello world", url.bytes.decodeToString())
        assertEquals("Hello world", KiteDataUrl.decode("data:;base64,SGVsbG8gd29ybGQ=")!!.bytes.decodeToString())
        assertEquals("Hi", KiteDataUrl.decode("data:;base64,SGk=")!!.bytes.decodeToString())
        assertEquals("Hi", KiteDataUrl.decode("data:;base64,SGk")!!.bytes.decodeToString())
    }

    @Test
    fun base64_that_is_not_base64_does_not_decode() {
        assertNull(KiteDataUrl.decode("data:;base64,SGk*"), "a character outside the alphabet")
        assertNull(KiteDataUrl.decode("data:;base64,SGVsb"), "one character over a whole number of quads")
        assertNull(KiteDataUrl.decode("data:;base64,S=k="), "padding inside")
    }

    @Test
    fun a_percent_encoded_body_decodes_to_its_bytes() {
        val svg = KiteDataUrl.decode("data:image/svg+xml,%3Csvg%20fill='%23fff'/%3E")!!
        assertEquals("image/svg+xml", svg.essence)
        assertEquals("<svg fill='#fff'/>", svg.bytes.decodeToString())
        // Base64 percent-encoded, as some writers escape every reserved character.
        assertEquals("Hi", KiteDataUrl.decode("data:text/plain;base64,SGk%3D")!!.bytes.decodeToString())
        // A character outside ASCII is its UTF-8 bytes.
        assertContentEquals("é".encodeToByteArray(), KiteDataUrl.decode("data:,é")!!.bytes)
    }

    @Test
    fun the_header_gives_the_media_type_with_the_defaults_of_fetch() {
        assertEquals("text/plain;charset=US-ASCII", KiteDataUrl.decode("data:,x")!!.mediaType)
        assertEquals("text/plain;charset=utf-8", KiteDataUrl.decode("data:;charset=utf-8,x")!!.mediaType)
        assertEquals("image/svg+xml;charset=utf-8", KiteDataUrl.decode("data:image/svg+xml;charset=utf-8,x")!!.mediaType)
        assertEquals("image/svg+xml", KiteDataUrl.essenceOf("DATA:Image/SVG+XML;charset=utf-8,<svg/>"))
        assertEquals("image/png", KiteDataUrl.decode("  Data:image/png;BASE64 ,SGk=  ")!!.mediaType)
    }

    @Test
    fun a_fragment_is_not_part_of_the_body() {
        assertEquals("ab", KiteDataUrl.decode("data:,ab#cd")!!.bytes.decodeToString())
    }

    @Test
    fun only_a_data_url_is_one() {
        assertTrue(KiteDataUrl.isDataUrl(" data:,x"))
        assertFalse(KiteDataUrl.isDataUrl("images/data:x.png"))
        assertFalse(KiteDataUrl.isDataUrl("https://example.com/data:,x"))
        assertNull(KiteDataUrl.decode("data:no-comma"))
        assertNull(KiteDataUrl.essenceOf("pic.png"))
    }
}
