package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** How [ContentStreamParser] turns operand tokens into objects (ISO 32000-1, 7.8.2). */
class ContentStreamParserTest {

    private fun parse(src: String) = ContentStreamParser.parse(src.encodeToByteArray())

    private fun op(operator: String, vararg operands: Long) = Operation(operator, operands.map { PdfInt(it) })

    @Test
    fun operands_come_back_whole() {
        val ops = parse("0.5 g [(Ke) -120 (rn)] TJ /Span << /MCID 3 /OC 5 0 R >> BDC 1 0 0 1 72 720 cm")
        assertEquals(listOf("g", "TJ", "BDC", "cm"), ops.map { it.operator })
        assertEquals(listOf<PdfObject>(PdfReal(0.5)), ops[0].operands)
        val tj = assertIs<PdfArray>(ops[1].operands.single())
        assertEquals("Ke", assertIs<PdfString>(tj[0]).bytes.decodeToString())
        assertEquals(PdfInt(-120), tj[1])
        assertEquals("rn", assertIs<PdfString>(tj[2]).bytes.decodeToString())
        assertEquals(PdfName("Span"), ops[2].operands[0])
        val properties = assertIs<PdfDictionary>(ops[2].operands[1])
        assertEquals(PdfInt(3), properties["MCID"])
        // A reference inside a container operand stays a reference, as in MuPDF.
        assertEquals(PdfReference(5, 0), properties["OC"])
        assertEquals(op("cm", 1, 0, 0, 1, 72, 720), ops[3])
    }

    @Test
    fun an_integer_operand_is_never_a_reference() {
        // Operands are direct objects, so "1 0 R" is two numbers and an unknown operator. MuPDF drops them too.
        assertEquals(listOf(op("R", 1, 0), op("m", 10, 20)), parse("1 0 R 10 20 m"))
    }

    @Test
    fun a_dictionary_operand_is_never_a_stream() {
        // Operands are never streams, so the operators after a stray "stream" keyword survive.
        val ops = parse("/P << /A 1 >> stream 1 2 m endstream 3 4 l")
        assertEquals(listOf("stream", "m", "endstream", "l"), ops.map { it.operator })
        assertEquals(op("m", 1, 2), ops[1])
        assertEquals(op("l", 3, 4), ops[3])
    }

    @Test
    fun a_malformed_array_drops_only_its_own_operator() {
        assertEquals(listOf(op("m", 1, 2), op("l", 3, 4)), parse("1 2 m [1 2 >> 3 4 l"))
    }
}
