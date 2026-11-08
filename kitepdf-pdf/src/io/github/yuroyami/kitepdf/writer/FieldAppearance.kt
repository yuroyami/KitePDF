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
import io.github.yuroyami.kitepdf.missingAsNull

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
        /** The field wraps its value on several lines (`/Ff` bit 13, #204). */
        multiline: Boolean = false,
        /** `/Q`: 0 left, 1 centre, 2 right. */
        quadding: Int = 0,
        /** The metrics the value is measured with: those of the font [fontRef] names (#707). */
        metrics: StandardFont = StandardFont.Helvetica,
        /** The widget's own box, turn and comb cells, as [lookOf] reads them. */
        look: FieldLook = FieldLook(),
    ): PdfStream {
        val turn = turnOf(look.rotation, width, height)
        val w = turn.width
        val h = turn.height
        val size = if (da.fontSize > 0.0) da.fontSize else if (multiline) 12.0 else (h - 2.0).coerceIn(6.0, 12.0)

        val content = ByteArrayBuilder(64)
        // ISO 32000-1, 12.7.3.3: the value draws over the widget's /MK background and border (#715).
        val boxed = drawBox(content, look, w, h)
        val clip = if (boxed) (look.borderWidth + 1.0).coerceAtLeast(1.0) else 1.0
        val pad = if (boxed) clip else 2.0
        content.ascii("/Tx BMC\nq\n${fmt(clip)} ${fmt(clip)} ${fmt((w - 2 * clip).coerceAtLeast(0.0))} ${fmt((h - 2 * clip).coerceAtLeast(0.0))} re W n\n")
        if (look.combCells > 0) {
            FieldText.writeComb(value, look.combCells, w, h, size, metrics, da.fontName, da.colorOps, content)
        } else {
            val paragraphs = plainParagraphs(value, metrics, size, da.colorOps, quadding)
            FieldText.write(FieldText.lines(paragraphs, w - 2 * pad, multiline), w, h, pad, multiline, content) { da.fontName }
        }
        content.ascii("Q\nEMC\n")
        if (boxed) content.ascii("Q\n")

        val resources = PdfDictionary(
            linkedMapOf("Font" to PdfDictionary(linkedMapOf(da.fontName to fontRef as PdfObject))),
        )
        val bbox = PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(w), PdfReal(h)))
        val extra = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Form"),
            "FormType" to PdfInt(1),
            "BBox" to bbox,
            "Resources" to resources,
        )
        turn.matrix?.let { extra["Matrix"] = it }
        return PdfStreams.flate(content.toByteArray(), extra = extra)
    }

    /** A widget's look for its appearance: its /MK colours and border, its turn, and its comb cells. */
    internal class FieldLook(
        val background: String? = null,
        val border: String? = null,
        val borderWidth: Double = 0.0,
        /** /MK /R: 0, 90, 180 or 270 degrees counterclockwise (ISO 32000-1, 12.5.6.19, Table 189). */
        val rotation: Int = 0,
        /** The cells of a comb field, /MaxLen, or 0 when it is none (12.7.4.3, Table 228). */
        val combCells: Int = 0,
    )

    /** The look of [widget] of a field with [flags] and [maxLength]. */
    internal fun lookOf(widget: PdfDictionary, refs: IndirectResolver, flags: Int, maxLength: Int?): FieldLook {
        val mk = missingAsNull { widget.getDict("MK", refs) }
        val background = colorOps(missingAsNull { mk?.getArray("BG", refs) }, stroking = false)
        val border = colorOps(missingAsNull { mk?.getArray("BC", refs) }, stroking = true)
        return FieldLook(
            background, border, borderWidthOf(widget, refs, hasBorderColor = border != null),
            rotationOf(mk, refs), combCellsOf(flags, maxLength),
        )
    }

    internal fun rotationOf(mk: PdfDictionary?, refs: IndirectResolver): Int {
        val r = numberOf(missingAsNull { mk?.get("R")?.resolve(refs) })?.toInt() ?: return 0
        return ((r % 360 + 360) % 360).let { if (it % 90 == 0) it else 0 }
    }

    /** Table 228: a comb has /MaxLen cells when Comb is set and Multiline, Password and FileSelect are clear. */
    internal fun combCellsOf(flags: Int, maxLength: Int?): Int =
        if ((flags and COMB) != 0 && maxLength != null && maxLength > 0 && (flags and (MULTILINE or PASSWORD or FILE_SELECT)) == 0) maxLength else 0

    /** The box an appearance lays out in, and the /Matrix that turns it onto the widget (12.5.5, Algorithm 8.1; #722). */
    internal class Turn(val width: Double, val height: Double, val matrix: PdfArray?)

    internal fun turnOf(rotation: Int, width: Double, height: Double): Turn {
        fun m(vararg v: Double) = PdfArray(v.map { PdfReal(it) })
        return when (rotation) {
            90 -> Turn(height, width, m(0.0, 1.0, -1.0, 0.0, width, 0.0))
            180 -> Turn(width, height, m(-1.0, 0.0, 0.0, -1.0, width, height))
            270 -> Turn(height, width, m(0.0, -1.0, 1.0, 0.0, 0.0, height))
            else -> Turn(width, height, null)
        }
    }

    /** The background and border of [look] in a box [width] by [height], in a q that the caller closes; false when it has none. */
    private fun drawBox(content: ByteArrayBuilder, look: FieldLook, width: Double, height: Double): Boolean {
        val border = look.border?.takeIf { look.borderWidth > 0.0 }
        if (look.background == null && border == null) return false
        content.ascii("q\n")
        if (look.background != null) content.ascii("${look.background}\n0 0 ${fmt(width)} ${fmt(height)} re f\n")
        if (border != null) {
            val inset = look.borderWidth / 2.0
            content.ascii("$border ${fmt(look.borderWidth)} w\n")
            content.ascii("${fmt(inset)} ${fmt(inset)} ${fmt(width - look.borderWidth)} ${fmt(height - look.borderWidth)} re S\n")
        }
        return true
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
        /** `/MK /R`: the widget's turn, 0, 90, 180 or 270, which turns the mark with it (#722). */
        rotation: Int = 0,
    ): PdfStream {
        // The box and mark are laid out upright in the turned box and /Matrix turns them onto /Rect.
        val turn = turnOf(rotation, width, height)
        @Suppress("NAME_SHADOWING") val width = turn.width
        @Suppress("NAME_SHADOWING") val height = turn.height
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
            ).also { extra -> turn.matrix?.let { extra["Matrix"] = it } },
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
        /** The colour a script gave the text (grey, RGB or CMYK components), in place of the `/DA` colour. */
        textColor: List<Double>? = null,
    ): PdfStream? {
        if (width <= 0.0 || height <= 0.0) return null
        val fieldType = inherited(widget, "FT", refs)?.let { (it as? PdfName)?.value } ?: return null
        val flags = (inherited(widget, "Ff", refs) as? PdfInt)?.value?.toInt() ?: 0
        // A reference to a missing object reads as absent, so the widget draws what it can (#441).
        val mk = missingAsNull { widget.getDict("MK", refs) }
        val background = colorOps(missingAsNull { mk?.getArray("BG", refs) }, stroking = false)
        val border = colorOps(missingAsNull { mk?.getArray("BC", refs) }, stroking = true)
        val borderWidth = borderWidthOf(widget, refs, hasBorderColor = border != null)
        val da = parseDA((inherited(widget, "DA", refs) as? PdfString)?.asText()).let { da ->
            val ops = textColor?.let { c -> colorOps(PdfArray(c.map { PdfReal(it) }), stroking = false) }
            if (ops == null) da else da.copy(colorOps = ops)
        }
        val quadding = (inherited(widget, "Q", refs) as? PdfInt)?.value?.toInt() ?: 0

        val isPushButton = fieldType == "Btn" && (flags and PUSH_BUTTON) != 0
        val text = when {
            isPushButton -> (missingAsNull { mk?.get("CA")?.resolve(refs) } as? PdfString)?.asText() ?: ""
            // ISO 32000-1, 12.7.4.3, Table 228: a password field echoes its characters in an unreadable
            // form, so the page, a raster of it and its text never show the value (#954).
            fieldType == "Tx" && (flags and PASSWORD) != 0 -> masked(valueOverride ?: valueText(inherited(widget, "V", refs), refs))
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
                rotation = rotationOf(mk, refs),
                zapfFont = PdfDictionary(
                    linkedMapOf(
                        "Type" to PdfName("Font"),
                        "Subtype" to PdfName("Type1"),
                        "BaseFont" to PdfName("ZapfDingbats"),
                    ),
                ),
            )
        }

        // /MK /R turns the box the value lays out in (#722).
        val turn = turnOf(rotationOf(mk, refs), width, height)
        val boxWidth = turn.width
        val boxHeight = turn.height
        val content = ByteArrayBuilder(96)
        content.ascii("q\n")
        if (background != null) {
            content.ascii("$background\n0 0 ${fmt(boxWidth)} ${fmt(boxHeight)} re f\n")
        }
        if (border != null && borderWidth > 0.0) {
            val inset = borderWidth / 2.0
            content.ascii("$border ${fmt(borderWidth)} w\n")
            content.ascii(
                "${fmt(inset)} ${fmt(inset)} ${fmt(boxWidth - borderWidth)} ${fmt(boxHeight - borderWidth)} re S\n",
            )
        }
        val multiline = fieldType == "Tx" && (flags and MULTILINE) != 0
        val size = if (da.fontSize > 0.0) da.fontSize else if (multiline) 12.0 else autoSize(boxHeight, isPushButton)
        val comb = if (fieldType == "Tx") combCellsOf(flags, (inherited(widget, "MaxLen", refs) as? PdfInt)?.value?.toInt()) else 0
        // A rich text field draws its styled value (§12.7.3.4) until a reader or a script changes it.
        val rich = if (fieldType == "Tx" && (flags and RICH_TEXT) != 0 && valueOverride == null) {
            richValueOf(widget, refs)?.let { rv ->
                RichText.paragraphs(rv, (inherited(widget, "DS", refs) as? PdfString)?.asText(), size, da.colorOps)
            }
        } else null
        // A push button centres its caption; a variable text field follows /Q
        // (ISO 32000-1 §12.7.3.1, Table 222): 0 left, 1 centre, 2 right.
        val paragraphs = rich ?: plainParagraphs(text, StandardFont.Helvetica, size, da.colorOps, if (isPushButton) 1 else quadding)
        val used = LinkedHashSet<StandardFont>()
        if (paragraphs.any { p -> p.runs.any { it.text.isNotEmpty() } }) {
            val pad = (borderWidth + 1.0).coerceAtLeast(1.0)
            content.ascii("/Tx BMC\nq\n")
            content.ascii("${fmt(pad)} ${fmt(pad)} ${fmt((boxWidth - 2 * pad).coerceAtLeast(0.0))} ${fmt((boxHeight - 2 * pad).coerceAtLeast(0.0))} re W n\n")
            if (comb > 0 && rich == null) {
                // A comb draws one character in each of its cells (12.7.4.3, Table 228; #719).
                used += StandardFont.Helvetica
                FieldText.writeComb(text, comb, boxWidth, boxHeight, size, StandardFont.Helvetica, da.fontName, da.colorOps, content)
            } else {
                // The plain value draws in the font the default appearance names, and rich text in the standard fonts.
                FieldText.write(FieldText.lines(paragraphs, boxWidth - 2 * pad, multiline), boxWidth, boxHeight, pad, multiline, content) { font ->
                    used += font
                    if (rich == null) da.fontName else FieldText.conventionalName(font)
                }
            }
            content.ascii("Q\nEMC\n")
        }
        content.ascii("Q\n")

        val fonts = LinkedHashMap<String, PdfObject>()
        for (font in used.ifEmpty { setOf(StandardFont.Helvetica) }) {
            val name = if (rich == null) da.fontName else FieldText.conventionalName(font)
            fonts[name] = PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Font"),
                    "Subtype" to PdfName("Type1"),
                    "BaseFont" to PdfName(font.baseFont),
                    "Encoding" to PdfName("WinAnsiEncoding"),
                ),
            )
        }
        return PdfStream(
            dict = PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("XObject"),
                    "Subtype" to PdfName("Form"),
                    "FormType" to PdfInt(1),
                    "BBox" to PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(boxWidth), PdfReal(boxHeight))),
                    "Resources" to PdfDictionary(linkedMapOf("Font" to PdfDictionary(fonts))),
                ).also { d -> turn.matrix?.let { d["Matrix"] = it } },
            ),
            rawBytes = content.toByteArray(),
        )
    }

    /** [text] as paragraphs of one style, a line break starting each new one. */
    internal fun plainParagraphs(text: String, font: StandardFont, size: Double, color: String, align: Int): List<FieldParagraph> =
        text.split("\r\n", "\r", "\n").map { line -> FieldParagraph(listOf(FieldRun(line, font, size, color)), align) }

    /** The rich text value `/RV` of the field, a text string or a text stream, or null (§12.7.3.4). */
    internal fun richValueOf(dict: PdfDictionary, refs: IndirectResolver): String? = when (val rv = inherited(dict, "RV", refs)) {
        is PdfString -> rv.asText()
        // A stream that does not decode leaves the plain value to draw.
        is PdfStream -> runCatching { io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(rv).decodeToString() }.getOrNull()
        else -> null
    }

    /**
     * The glyph `/MK /CA` asks for on a check box or a radio button, as a ZapfDingbats character
     * (ISO 32000-1 §12.5.6.19, Table 189): `4` is the check, `l` the filled circle of a radio.
     */
    private fun markOf(mk: PdfDictionary?, refs: IndirectResolver): Char =
        (missingAsNull { mk?.get("CA")?.resolve(refs) } as? PdfString)?.asText()?.firstOrNull() ?: '4'

    /** The `/AP /N` state that turns the widget on, or null when it names none. */
    private fun onStateNameOf(widget: PdfDictionary, refs: IndirectResolver): String? {
        val normal = missingAsNull { widget.getDict("AP", refs)?.get("N")?.resolve(refs) } as? PdfDictionary ?: return null
        return normal.map.keys.firstOrNull { it != "Off" }
    }

    /** An entry of the widget, or of the nearest ancestor field that has it (§12.7.3.2). */
    private fun inherited(widget: PdfDictionary, key: String, refs: IndirectResolver): PdfObject? {
        var node: PdfDictionary? = widget
        var guard = 0
        while (node != null && guard++ < MAX_PARENT_DEPTH) {
            val current: PdfDictionary = node
            missingAsNull { current[key]?.resolve(refs) }?.let { return it }
            node = missingAsNull { current["Parent"]?.resolve(refs) } as? PdfDictionary
        }
        return null
    }

    /** `/BS /W` first, then `/Border`, then the default of 1 when a border colour is set (§12.5.4). */
    private fun borderWidthOf(widget: PdfDictionary, refs: IndirectResolver, hasBorderColor: Boolean): Double {
        missingAsNull { widget.getDict("BS", refs) }?.let { bs -> numberOf(missingAsNull { bs["W"]?.resolve(refs) })?.let { return it } }
        missingAsNull { widget.getArray("Border", refs) }?.let { border ->
            numberOf(missingAsNull { border.getOrNull(2)?.resolve(refs) })?.let { return it }
        }
        return if (hasBorderColor) 1.0 else 0.0
    }

    /** A field value as the text to draw: a string for text, a name for a choice. */
    private fun valueText(value: PdfObject?, refs: IndirectResolver): String = when (val v = missingAsNull { value?.resolve(refs) }) {
        is PdfString -> v.asText()
        is PdfName -> v.value
        is PdfArray -> (missingAsNull { v.getOrNull(0)?.resolve(refs) } as? PdfString)?.asText() ?: ""
        else -> ""
    }

    /**
     * The size for `/DA` font size 0, which means "fit the box" (§12.7.3.3). A button's caption
     * keeps a little more room around it than a field's value.
     */
    private fun autoSize(height: Double, isButton: Boolean): Double =
        (height - if (isButton) 4.0 else 2.0).coerceIn(4.0, 12.0)

    /** `/Ff` bit 13: the text field wraps its value on several lines. */
    private const val MULTILINE = 1 shl 12

    /** `/Ff` bit 14: the text field takes a password, which it never shows (Table 228). */
    internal const val PASSWORD = 1 shl 13

    /** One asterisk for each character of [value], what a password field shows of it (#954). */
    internal fun masked(value: String): String {
        var count = 0
        for (c in value) if (!c.isLowSurrogate()) count++
        return "*".repeat(count)
    }

    /** `/Ff` bit 26: the text field's value is rich text, in `/RV`. */
    private const val RICH_TEXT = 1 shl 25

    /** Table 228: the Comb flag, bit 25, and FileSelect, bit 21. */
    private const val COMB = 1 shl 24
    private const val FILE_SELECT = 1 shl 20

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
