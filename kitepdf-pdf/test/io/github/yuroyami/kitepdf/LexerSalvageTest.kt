package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfNull
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.parser.Parser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A malformed token keeps the object around it, and a malformed number reads as one number (#806, #809). */
class LexerSalvageTest {

    private fun obj(src: String) = Parser(src.encodeToByteArray()).readObject()

    @Test
    fun aBadTokenInsideAnObjectKeepsTheObject() {
        val d = obj("<< /A /App#2G /B <41 4G> /C Unknown /D 25000000000000000000 /E 1 >>") as PdfDictionary
        assertEquals(PdfName("App#2G"), d["A"])
        assertContentEquals(byteArrayOf(0x41, 0x40), (d["B"] as PdfString).bytes)
        assertEquals(PdfNull, d["C"])
        assertEquals(PdfInt(Long.MAX_VALUE), d["D"])
        assertEquals(PdfInt(1), d["E"])
        assertEquals(PdfName("A B"), obj("/A#20B"))
    }

    @Test
    fun malformedNumbersReadAsOneNumber() {
        val ops = ContentStreamParser.parse("0.000000000000-5684342 100 m 100 --100 200 300 re 72 - Td".encodeToByteArray())
        assertEquals(listOf("m", "re", "Td"), ops.map { it.operator })
        assertEquals(listOf(PdfReal(0.0), PdfInt(100)), ops[0].operands)
        assertEquals(listOf(PdfInt(100), PdfInt(-100), PdfInt(200), PdfInt(300)), ops[1].operands)
        assertEquals(listOf(PdfInt(72), PdfInt(0)), ops[2].operands)
        assertEquals(PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(-5), PdfInt(10))), obj("[0 0 --5 10]"))
        assertEquals(PdfReal(-0.5), obj("-.5"))
        assertEquals(PdfInt(5), obj("+5"))
    }
}
