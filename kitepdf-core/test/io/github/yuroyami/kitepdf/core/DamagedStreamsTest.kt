package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteShading
import kotlin.test.Test
import kotlin.test.assertTrue

/** A stream that does not decode degrades its object, and never fails the page (CONTRIBUTING, lenient salvage). */
class DamagedStreamsTest {

    private val refs = IndirectResolver { null }
    private val notZlib = PdfStream(PdfDictionary(mapOf("Filter" to PdfName("FlateDecode"))), "not zlib at all".encodeToByteArray())

    @Test
    fun anIndexedLookupThatDoesNotDecodeReadsAsEmpty() {
        // #647: the shading's colour space threw out of KiteShading.parse and failed the page.
        val cs = PdfArray(listOf(PdfName("Indexed"), PdfName("DeviceRGB"), PdfInt(1), notZlib))
        assertTrue(KiteColorSpace.resolve(cs, refs) is KiteColorSpace.Indexed)
        val shading = PdfDictionary(mapOf(
            "ShadingType" to PdfInt(2), "ColorSpace" to cs,
            "Coords" to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(1), PdfInt(0))),
        ))
        KiteShading.parse(shading, refs)
    }

    @Test
    fun aCidToGidMapThatDoesNotDecodeIsIdentity() {
        // #646: Table 117's default.
        val map = io.github.yuroyami.kitepdf.core.font.CidToGidMap.from(notZlib)
        kotlin.test.assertEquals(42, map.map(42))
    }
}
