package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
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
        zapfFont: PdfObject,
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
            linkedMapOf("Font" to PdfDictionary(linkedMapOf("ZaDb" to zapfFont))),
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

    /* ─── Appearance for a widget that carries none ────────────────────────── */

    /**
     * The appearance a reader draws for a form widget with no `/AP` stream.
     *
     * ISO 32000-1 §12.7.3.3 says a reader constructs field appearances when
     * `/NeedAppearances` is true, and §12.5.5 says it should generate an
     * appearance for any annotation that arrives without one. MuPDF does it for
     * every widget on page load, whatever the flag says, so a form written
     * without appearances still shows its boxes, its captions and its values.
     *
     * The result is a Form XObject whose resources hold a direct font
     * dictionary, so the renderer needs nothing from the file to draw it.
     * Returns null when the widget is not a form field.
     */
    internal fun synthesize(
        widget: PdfDictionary,
        width: Double,
        height: Double,
        refs: IndirectResolver,
        /** What the field shows now, when a reader or a script has changed it. */
        valueOverride: String? = null,
    ): PdfStream? {
        if (width <= 0.0 || height <= 0.0) return null
        val fieldType = inherited(widget, "FT", refs)?.let { (it as? PdfName)?.value } ?: return null
        val flags = (inherited(widget, "Ff", refs) as? PdfInt)?.value?.toInt() ?: 0
        val mk = widget.getDict("MK", refs)
        val background = colorOps(mk?.getArray("BG", refs), stroking = false)
        val border = colorOps(mk?.getArray("BC", refs), stroking = true)
        val borderWidth = borderWidthOf(widget, refs, hasBorderColor = border != null)
        val da = parseDA((inherited(widget, "DA", refs) as? PdfString)?.asText())
        val quadding = (inherited(widget, "Q", refs) as? PdfInt)?.value?.toInt() ?: 0

        val isPushButton = fieldType == "Btn" && (flags and PUSH_BUTTON) != 0
        val text = when {
            isPushButton -> (mk?.get("CA")?.resolve(refs) as? PdfString)?.asText() ?: ""
            fieldType == "Tx" || fieldType == "Ch" -> valueOverride ?: valueText(inherited(widget, "V", refs), refs)
            else -> ""
        }
        // A check box or a radio button draws its box and, when it is on, its mark.
        if (fieldType == "Btn" && !isPushButton) {
            val state = valueOverride ?: valueText(inherited(widget, "V", refs), refs)
            val onState = onStateNameOf(widget, refs)
            val on = state.isNotEmpty() && state != "Off" && (onState == null || state == onState)
            return buildToggle(
                width = width,
                height = height,
                on = on,
                radio = (flags and RADIO) != 0,
                mark = markOf(mk, refs),
                background = background,
                border = border,
                borderWidth = borderWidth,
                markColor = da.colorOps,
                zapfFont = PdfDictionary(
                    linkedMapOf(
                        "Type" to PdfName("Font"),
                        "Subtype" to PdfName("Type1"),
                        "BaseFont" to PdfName("ZapfDingbats"),
                    ),
                ),
            )
        }

        val content = ByteArrayBuilder(96)
        content.ascii("q\n")
        if (background != null) {
            content.ascii("$background\n0 0 ${fmt(width)} ${fmt(height)} re f\n")
        }
        if (border != null && borderWidth > 0.0) {
            val inset = borderWidth / 2.0
            content.ascii("$border ${fmt(borderWidth)} w\n")
            content.ascii(
                "${fmt(inset)} ${fmt(inset)} ${fmt(width - borderWidth)} ${fmt(height - borderWidth)} re S\n",
            )
        }
        if (text.isNotEmpty()) {
            val pad = (borderWidth + 1.0).coerceAtLeast(1.0)
            val size = if (da.fontSize > 0.0) da.fontSize else autoSize(height, isPushButton)
            val advance = StandardFont.Helvetica.stringWidth(text, size)
            // A push button centres its caption; a variable text field follows /Q
            // (ISO 32000-1 §12.7.3.1, Table 222): 0 left, 1 centre, 2 right.
            val x = when {
                isPushButton || quadding == 1 -> ((width - advance) / 2.0).coerceAtLeast(pad)
                quadding == 2 -> (width - pad - advance).coerceAtLeast(pad)
                else -> pad
            }
            val baseline = ((height - size) / 2.0 + size * 0.2).coerceAtLeast(pad)
            content.ascii("/Tx BMC\nq\n")
            content.ascii("${fmt(pad)} ${fmt(pad)} ${fmt((width - 2 * pad).coerceAtLeast(0.0))} ${fmt((height - 2 * pad).coerceAtLeast(0.0))} re W n\n")
            content.ascii("BT\n${da.colorOps}\n/${da.fontName} ${fmt(size)} Tf\n${fmt(x)} ${fmt(baseline)} Td\n")
            PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(text)), content)
            content.ascii(" Tj\nET\nQ\nEMC\n")
        }
        content.ascii("Q\n")

        val font = PdfDictionary(
            linkedMapOf(
                "Type" to PdfName("Font"),
                "Subtype" to PdfName("Type1"),
                "BaseFont" to PdfName("Helvetica"),
                "Encoding" to PdfName("WinAnsiEncoding"),
            ),
        )
        return PdfStream(
            dict = PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("XObject"),
                    "Subtype" to PdfName("Form"),
                    "FormType" to PdfInt(1),
                    "BBox" to PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(width), PdfReal(height))),
                    "Resources" to PdfDictionary(
                        linkedMapOf("Font" to PdfDictionary(linkedMapOf(da.fontName to font as PdfObject))),
                    ),
                ),
            ),
            rawBytes = content.toByteArray(),
        )
    }

    /**
     * The glyph `/MK /CA` asks for on a check box or a radio button, as a ZapfDingbats character
     * (ISO 32000-1 §12.5.6.19, Table 189): `4` is the check, `l` the filled circle of a radio.
     */
    private fun markOf(mk: PdfDictionary?, refs: IndirectResolver): Char =
        (mk?.get("CA")?.resolve(refs) as? PdfString)?.asText()?.firstOrNull() ?: '4'

    /** The `/AP /N` state that turns the widget on, or null when it names none. */
    private fun onStateNameOf(widget: PdfDictionary, refs: IndirectResolver): String? {
        val normal = widget.getDict("AP", refs)?.get("N")?.resolve(refs) as? PdfDictionary ?: return null
        return normal.map.keys.firstOrNull { it != "Off" }
    }

    /** An entry of the widget, or of the nearest ancestor field that has it (§12.7.3.2). */
    private fun inherited(widget: PdfDictionary, key: String, refs: IndirectResolver): PdfObject? {
        var node: PdfDictionary? = widget
        var guard = 0
        while (node != null && guard++ < MAX_PARENT_DEPTH) {
            node[key]?.resolve(refs)?.let { return it }
            node = node["Parent"]?.resolve(refs) as? PdfDictionary
        }
        return null
    }

    /** `/BS /W` first, then `/Border`, then the default of 1 when a border colour is set (§12.5.4). */
    private fun borderWidthOf(widget: PdfDictionary, refs: IndirectResolver, hasBorderColor: Boolean): Double {
        widget.getDict("BS", refs)?.let { bs -> numberOf(bs["W"]?.resolve(refs))?.let { return it } }
        widget.getArray("Border", refs)?.let { border -> numberOf(border.getOrNull(2)?.resolve(refs))?.let { return it } }
        return if (hasBorderColor) 1.0 else 0.0
    }

    /** A field value as the text to draw: a string for text, a name for a choice. */
    private fun valueText(value: PdfObject?, refs: IndirectResolver): String = when (val v = value?.resolve(refs)) {
        is PdfString -> v.asText()
        is PdfName -> v.value
        is PdfArray -> (v.getOrNull(0)?.resolve(refs) as? PdfString)?.asText() ?: ""
        else -> ""
    }

    /**
     * The size for `/DA` font size 0, which means "fit the box" (§12.7.3.3). A button's caption
     * keeps a little more room around it than a field's value.
     */
    private fun autoSize(height: Double, isButton: Boolean): Double =
        (height - if (isButton) 4.0 else 2.0).coerceIn(4.0, 12.0)

    /** `/Ff` bit 17: the button is a push button, so it has a caption instead of a state. */
    private const val PUSH_BUTTON = 1 shl 16

    /** `/Ff` bit 16: the button is one of a radio group, so its box is round. */
    private const val RADIO = 1 shl 15

    /** A malformed `/Parent` chain must not loop forever. */
    private const val MAX_PARENT_DEPTH = 32

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
