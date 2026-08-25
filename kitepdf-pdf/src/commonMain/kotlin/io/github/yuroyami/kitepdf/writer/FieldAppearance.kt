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
 * Generates a form field's `/AP /N` appearance, the Form XObject a conforming
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


    /**
     * Appearance for one state of a checkbox or radio widget.
     *
     * A file that leaves `/AP` out (or leaves out the "on" state) shows
     * nothing when the box is ticked, because a viewer has no appearance to
     * paint. This draws one: the widget's own `/MK` background and border,
     * plus, when [on], the mark itself.
     *
     * The mark is a ZapfDingbats glyph, which is how PDF has always drawn
     * these: `4` is the check, `l` the filled circle a radio uses, and `8`,
     * `n`, `H`, `u` the cross, square, star and diamond a file can ask for
     * through `/MK /CA`. Radios get a round border, checkboxes a square one.
     */
    fun buildToggle(
        width: Double,
        height: Double,
        on: Boolean,
        radio: Boolean,
        mark: Char,
        background: String?,
        border: String?,
        borderWidth: Double,
        markColor: String,
        zapfRef: PdfReference,
    ): PdfStream {
        val content = ByteArrayBuilder(64)
        content.ascii("q\n")
        val bw = borderWidth.coerceAtLeast(0.0)
        val inset = bw / 2.0
        if (background != null || (border != null && bw > 0.0)) {
            if (radio) {
                val cx = width / 2.0
                val cy = height / 2.0
                val r = (minOf(width, height) / 2.0 - inset).coerceAtLeast(0.0)
                if (background != null) {
                    content.ascii("$background\n"); circle(content, cx, cy, r + inset); content.ascii("f\n")
                }
                if (border != null && bw > 0.0) {
                    content.ascii("$border ${fmt(bw)} w\n"); circle(content, cx, cy, r); content.ascii("S\n")
                }
            } else {
                if (background != null) {
                    content.ascii("$background\n0 0 ${fmt(width)} ${fmt(height)} re f\n")
                }
                if (border != null && bw > 0.0) {
                    content.ascii("$border ${fmt(bw)} w\n")
                    content.ascii("${fmt(inset)} ${fmt(inset)} ${fmt(width - bw)} ${fmt(height - bw)} re S\n")
                }
            }
        }
        if (on) {
            // The glyph is sized to the box and centred on its own advance.
            val size = (minOf(width, height) - 2.0 * bw - 2.0).coerceAtLeast(4.0)
            val glyphWidth = size * ZAPF_ADVANCE
            val x = (width - glyphWidth) / 2.0
            val y = (height - size * ZAPF_CAP) / 2.0
            content.ascii("q\n$markColor\nBT\n/ZaDb ${fmt(size)} Tf\n${fmt(x)} ${fmt(y)} Td\n")
            PdfObjectWriter.writeObject(PdfString(byteArrayOf(mark.code.toByte())), content)
            content.ascii(" Tj\nET\nQ\n")
        }
        content.ascii("Q\n")

        val resources = PdfDictionary(
            linkedMapOf("Font" to PdfDictionary(linkedMapOf("ZaDb" to zapfRef as PdfObject))),
        )
        return PdfStreams.flate(
            content.toByteArray(),
            extra = linkedMapOf(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Form"),
                "FormType" to PdfInt(1),
                "BBox" to PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(width), PdfReal(height))),
                "Resources" to resources,
            ),
        )
    }

    /** Four Bezier arcs, the usual circle approximation. */
    private fun circle(out: ByteArrayBuilder, cx: Double, cy: Double, r: Double) {
        if (r <= 0.0) return
        val k = r * 0.5522847498
        out.ascii("${fmt(cx + r)} ${fmt(cy)} m\n")
        out.ascii("${fmt(cx + r)} ${fmt(cy + k)} ${fmt(cx + k)} ${fmt(cy + r)} ${fmt(cx)} ${fmt(cy + r)} c\n")
        out.ascii("${fmt(cx - k)} ${fmt(cy + r)} ${fmt(cx - r)} ${fmt(cy + k)} ${fmt(cx - r)} ${fmt(cy)} c\n")
        out.ascii("${fmt(cx - r)} ${fmt(cy - k)} ${fmt(cx - k)} ${fmt(cy - r)} ${fmt(cx)} ${fmt(cy - r)} c\n")
        out.ascii("${fmt(cx + k)} ${fmt(cy - r)} ${fmt(cx + r)} ${fmt(cy - k)} ${fmt(cx + r)} ${fmt(cy)} c\n")
    }

    /**
     * A PDF colour operator for an `/MK` colour array: 1 number is grey,
     * 3 is RGB, 4 is CMYK, and an empty array means "no colour at all".
     */
    fun colorOps(arr: PdfArray?, stroking: Boolean): String? {
        if (arr == null || arr.isEmpty()) return null
        val v = (0 until arr.size).mapNotNull { numberOf(arr.getOrNull(it)) }
        val op = when (v.size) {
            1 -> if (stroking) "G" else "g"
            3 -> if (stroking) "RG" else "rg"
            4 -> if (stroking) "K" else "k"
            else -> return null
        }
        return v.joinToString(" ") { fmt(it) } + " " + op
    }

    /** ZapfDingbats check glyph advance and cap height, both in em. */
    private const val ZAPF_ADVANCE = 0.79
    private const val ZAPF_CAP = 0.72

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
