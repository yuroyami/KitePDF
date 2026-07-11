package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * Generates a text field's `/AP /N` appearance — the Form XObject a conforming
 * reader draws for the field (ISO 32000-1 §12.7.3.3). Without it, a viewer that
 * doesn't regenerate appearances (like KitePDF's own renderer) shows nothing.
 *
 * The `/DA` (default appearance) string is itself content-stream syntax, so we
 * parse it with [ContentStreamParser] to recover the font, size, and colour.
 */
internal object FieldAppearance {

    data class DefaultAppearance(val fontName: String, val fontSize: Double, val colorOps: String)

    fun parseDA(da: String?): DefaultAppearance {
        var fontName = "Helv"
        var size = 0.0
        var color = "0 g"
        if (da != null) {
            for (op in ContentStreamParser.parse(da.encodeToByteArray())) {
                when (op.operator) {
                    "Tf" -> {
                        (op.operands.getOrNull(0) as? PdfName)?.let { fontName = it.value }
                        numberOf(op.operands.getOrNull(1))?.let { size = it }
                    }
                    "g" -> numberOf(op.operands.getOrNull(0))?.let { color = "${fmt(it)} g" }
                    "rg" -> if (op.operands.size >= 3) {
                        color = "${num(op, 0)} ${num(op, 1)} ${num(op, 2)} rg"
                    }
                    "k" -> if (op.operands.size >= 4) {
                        color = "${num(op, 0)} ${num(op, 1)} ${num(op, 2)} ${num(op, 3)} k"
                    }
                }
            }
        }
        return DefaultAppearance(fontName, size, color)
    }

    /**
     * Build the appearance stream for [value] in a [width]×[height] field box.
     * [fontRef] is an already-staged Helvetica font object referenced from the
     * appearance's own `/Resources` under [da]'s font name, so the stream is
     * self-contained.
     */
    fun build(
        value: String,
        width: Double,
        height: Double,
        da: DefaultAppearance,
        fontRef: PdfReference,
    ): PdfStream {
        val size = if (da.fontSize > 0.0) da.fontSize else (height - 2.0).coerceIn(6.0, 12.0)
        // Rough vertical centring of a single line; baseline above the box bottom.
        val baseline = ((height - size) / 2.0 + size * 0.2).coerceAtLeast(2.0)
        val clipW = (width - 2.0).coerceAtLeast(0.0)
        val clipH = (height - 2.0).coerceAtLeast(0.0)

        val content = ByteArrayBuilder(64)
        content.ascii("/Tx BMC\nq\n1 1 ${fmt(clipW)} ${fmt(clipH)} re W n\nBT\n")
        content.ascii("${da.colorOps}\n/${da.fontName} ${fmt(size)} Tf\n2 ${fmt(baseline)} Td\n")
        PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(value)), content)
        content.ascii(" Tj\nET\nQ\nEMC\n")

        val resources = PdfDictionary(
            linkedMapOf("Font" to PdfDictionary(linkedMapOf(da.fontName to fontRef as PdfObject))),
        )
        val bbox = PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(width), PdfReal(height)))
        return PdfStreams.flate(
            content.toByteArray(),
            extra = linkedMapOf(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Form"),
                "FormType" to PdfInt(1),
                "BBox" to bbox,
                "Resources" to resources,
            ),
        )
    }

    private fun num(op: io.github.yuroyami.kitepdf.content.Operation, i: Int): String =
        fmt(numberOf(op.operands.getOrNull(i)) ?: 0.0)

    private fun numberOf(o: PdfObject?): Double? = when (o) {
        is PdfReal -> o.value
        is PdfInt -> o.value.toDouble()
        else -> null
    }

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    private fun ByteArrayBuilder.ascii(s: String) = append(s.encodeToByteArray())
}
