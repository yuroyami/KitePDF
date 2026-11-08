package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Only a stream that is an object of its own resolves an indirect /Length (#965). */
class NestedStreamLengthTest {

    private var calls = 0
    private val resolver = IndirectResolver { calls++; PdfInt(3) }

    @Test
    fun aStreamInsideAnArrayFindsItsEndByTheKeyword() {
        val obj = Parser("[[<< /Length 5 0 R >> stream\nabc\nendstream]]".encodeToByteArray(), resolver).readObject()
        val stream = ((obj as PdfArray)[0] as PdfArray)[0] as PdfStream
        assertEquals("abc", stream.rawBytes.decodeToString())
        assertEquals(0, calls, "the nested parse did not resolve")
    }

    @Test
    fun aTopLevelStreamStillResolvesItsLength() {
        val obj = Parser("<< /Length 5 0 R >> stream\nabcdef\nendstream".encodeToByteArray(), resolver).readObject()
        assertEquals(1, calls)
        // /Length 3 does not end at the keyword, so the scan decides; the resolve still ran.
        assertEquals("abcdef", (obj as PdfStream).rawBytes.decodeToString())
    }
}
