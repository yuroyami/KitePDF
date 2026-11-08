package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A blend mode array takes its first known mode (#782), and CalCMYK renders as DeviceCMYK (#786). */
class ColorStateSalvageTest {

    private val noRefs = IndirectResolver { null }

    @Test
    fun theFirstRecognizedBlendModeOfAnArrayApplies() {
        fun bm(vararg names: String) = ExtGState.parse(PdfDictionary(mapOf("BM" to PdfArray(names.map { PdfName(it) }))), noRefs).blendMode
        assertEquals(KiteBlendMode.Multiply, bm("Foo", "Multiply"))
        assertEquals(KiteBlendMode.Screen, bm("Screen", "Multiply"))
        assertEquals(KiteBlendMode.Normal, bm("Foo", "Bar"))
    }

    @Test
    fun calCmykRendersAsDeviceCmyk() {
        assertSame(KiteColorSpace.DeviceCMYK, KiteColorSpace.resolve(PdfArray(listOf(PdfName("CalCMYK"), PdfDictionary(emptyMap()))), noRefs))
        assertSame(KiteColorSpace.DeviceCMYK, KiteColorSpace.resolve(PdfName("CalCMYK"), noRefs))
    }
}
