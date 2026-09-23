package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The /BC and /TR entries of a soft mask, and the table of a transfer function (ISO 32000-1, Table 144, #68). */
class KiteMaskTransferTest {

    private fun near(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, got $actual")

    @Test
    fun the_identity_keeps_each_level() {
        val t = KiteMaskTransfer.of { it }
        assertEquals(0, t[0]); assertEquals(128, t[128]); assertEquals(255, t[255])
        near(1.0, t.slope); near(0.0, t.offset)
    }

    @Test
    fun an_inverter_swaps_the_levels_and_fits_its_line_exactly() {
        val t = KiteMaskTransfer.of { 1 - it }
        assertEquals(255, t[0]); assertEquals(0, t[255])
        near(-1.0, t.slope); near(1.0, t.offset)
    }

    @Test
    fun values_out_of_range_clamp() {
        assertEquals(255, KiteMaskTransfer.of { 2.0 }[10])
        assertEquals(0, KiteMaskTransfer.of { -1.0 }[10])
        assertEquals(0, KiteMaskTransfer.of { Double.NaN }[10])
        // A level outside 0 to 255 reads the nearest end of the table.
        assertEquals(255, KiteMaskTransfer.of { it }[300])
    }

    @Test
    fun the_byte_table_holds_every_level() {
        val bytes = KiteMaskTransfer.of { 1 - it }.toByteArray()
        assertEquals(256, bytes.size)
        assertEquals(255, bytes[0].toInt() and 255)
        assertEquals(0, bytes[255].toInt() and 255)
    }

    private val group = PdfStream(PdfDictionary(mapOf("Subtype" to PdfName("Form"))), ByteArray(0))

    /** Resolves object 7 to [group] and object 8 to a mask dictionary with an inverting /TR. */
    private val refs = object : IndirectResolver {
        override fun resolve(ref: PdfReference): PdfObject? = when (ref.objectNumber) {
            7L -> group
            8L -> PdfDictionary(mapOf("S" to PdfName("Luminosity"), "G" to PdfReference(7, 0), "TR" to inverter()))
            else -> null
        }
    }

    private fun inverter() = PdfDictionary(
        mapOf(
            "FunctionType" to PdfInt(2), "Domain" to PdfArray(listOf(PdfInt(0), PdfInt(1))),
            "C0" to PdfArray(listOf(PdfInt(1))), "C1" to PdfArray(listOf(PdfInt(0))), "N" to PdfInt(1),
        ),
    )

    private fun mask(vararg entries: Pair<String, PdfObject>): SoftMask.MaskGroup {
        val dict = PdfDictionary(mapOf("S" to PdfName("Luminosity"), "G" to PdfReference(7, 0)) + entries)
        return assertIs<SoftMask.MaskGroup>(ExtGState.parse(PdfDictionary(mapOf("SMask" to dict)), refs).softMask)
    }

    @Test
    fun a_mask_reads_its_backdrop_and_transfer_function() {
        val m = mask("BC" to PdfArray(listOf(PdfInt(1))), "TR" to inverter())
        assertEquals(listOf(1.0), m.backdrop)
        val f = assertNotNull(m.transfer)
        near(1.0, f.evaluate(doubleArrayOf(0.0))[0])
    }

    @Test
    fun the_identity_name_and_a_missing_entry_mean_no_transfer_function() {
        assertNull(mask("TR" to PdfName("Identity")).transfer)
        assertNull(mask().transfer)
        assertNull(mask().backdrop)
    }

    @Test
    fun a_transfer_function_with_three_outputs_is_the_identity() {
        val rgb = PdfDictionary(
            mapOf(
                "FunctionType" to PdfInt(2), "Domain" to PdfArray(listOf(PdfInt(0), PdfInt(1))),
                "C0" to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(0))),
                "C1" to PdfArray(listOf(PdfInt(1), PdfInt(1), PdfInt(1))), "N" to PdfInt(1),
            ),
        )
        assertNull(mask("TR" to rgb).transfer)
    }

    @Test
    fun a_mask_dictionary_may_be_an_indirect_object() {
        val gs = ExtGState.parse(PdfDictionary(mapOf("SMask" to PdfReference(8, 0))), refs)
        val m = assertIs<SoftMask.MaskGroup>(gs.softMask)
        assertEquals(group, m.group)
        assertNotNull(m.transfer)
    }
}
