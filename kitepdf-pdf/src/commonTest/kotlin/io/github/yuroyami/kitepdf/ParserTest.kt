package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.Parser
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfNull
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserTest {

    private fun parse(src: String) = Parser(src.encodeToByteArray()).readObject()

    @Test fun parses_null() { assertEquals(PdfNull, parse("null")) }
    @Test fun parses_true() { assertEquals(PdfBoolean(true), parse("true")) }
    @Test fun parses_false() { assertEquals(PdfBoolean(false), parse("false")) }
    @Test fun parses_int() { assertEquals(PdfInt(42), parse("42")) }
    @Test fun parses_real() { assertEquals(PdfReal(3.14), parse("3.14")) }
    @Test fun parses_name() { assertEquals(PdfName("Foo"), parse("/Foo")) }

    @Test
    fun parses_array_of_mixed_objects() {
        val arr = parse("[1 2.5 /Bar (text) [42] << /Key true >>]")
        assertIs<PdfArray>(arr)
        assertEquals(PdfInt(1), arr[0])
        assertEquals(PdfReal(2.5), arr[1])
        assertEquals(PdfName("Bar"), arr[2])
        assertIs<PdfArray>(arr[4])
        assertIs<PdfDictionary>(arr[5])
    }

    @Test
    fun parses_dict_with_indirect_reference() {
        val dict = parse("<< /Root 1 0 R /Size 12 >>") as PdfDictionary
        assertEquals(PdfReference(1, 0), dict["Root"])
        assertEquals(PdfInt(12), dict["Size"])
    }

    @Test
    fun reference_in_array_followed_by_another_int() {
        // The lookahead must NOT consume "10" as the start of another ref.
        val arr = parse("[7 0 R 10]") as PdfArray
        assertEquals(2, arr.size)
        assertEquals(PdfReference(7, 0), arr[0])
        assertEquals(PdfInt(10), arr[1])
    }
}
