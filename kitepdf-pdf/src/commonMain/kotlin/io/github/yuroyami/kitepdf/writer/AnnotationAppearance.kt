package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * Appearances for the annotations that have no geometry of their own to draw from:
 * the note and file attachment icons, the caret, the stamp and free text (ISO 32000-1,
 * 12.5.6). A reader should draw one when `/AP` is missing (12.5.5). Each is a Form
 * XObject as large as the annotation, so it maps onto `/Rect` one to one.
 */
internal object AnnotationAppearance {

    /** The appearance for [annot], or null for a subtype this object does not draw. */
    fun synthesize(annot: PdfAnnotation, refs: IndirectResolver): PdfStream? {
        val w = annot.rect.width
        val h = annot.rect.height
        if (!(w > 0.0 && h > 0.0 && w.isFinite() && h.isFinite())) return null
        val name = (resolved(annot.raw["Name"], refs) as? PdfName)?.value
        return when (annot.subtype) {
            PdfAnnotation.Subtype.Text -> icon(noteIcon(name), annot.color ?: NOTE_YELLOW, w, h)
            PdfAnnotation.Subtype.FileAttachment -> (annot.color ?: NOTE_YELLOW).let { icon(attachmentIcon(name, it), it, w, h) }
            PdfAnnotation.Subtype.Caret -> caret(annot.color ?: CARET_BLUE, w, h)
            PdfAnnotation.Subtype.Stamp -> stamp(name ?: "Draft", annot.color, w, h)
            PdfAnnotation.Subtype.FreeText -> freeText(annot, refs, w, h)
            else -> null
        }
    }

    /* ── icons ───────────────────────────────────────────────────────────── */

    /**
     * An icon drawn in a 20 by 20 design box, scaled to fit the annotation and kept at
     * its top-left corner. The current fill is [color] and the current stroke is black.
     */
    private fun icon(design: String, color: RgbColor, w: Double, h: Double): PdfStream {
        val s = minOf(w, h) / ICON_SIZE
        val content = "q ${fmt(s)} 0 0 ${fmt(s)} 0 ${fmt(h - ICON_SIZE * s)} cm\n" +
            "${rgb(color)} rg 0 G 1 j 1 J\n$design" + "Q\n"
        return form(content.encodeToByteArray(), w, h)
    }

    /** The `/Name` icons of a text annotation (ISO 32000-1, 12.5.6.4). `Note` is the default. */
    private fun noteIcon(name: String?): String = when (name) {
        "Comment" -> "1 w 2 18 m 18 18 l 18 7 l 9.5 7 l 5 3 l 6 7 l 2 7 l h B\n" +
            "0.8 w 5 14.5 m 15 14.5 l 5 11 m 12.5 11 l S\n"
        "Help" -> "1 w ${circle(10.0, 10.0, 8.5)} B\n" +
            "1.8 w 7.2 12.6 m 7.2 16.2 12.8 16.2 12.8 12.6 c 12.8 10.4 10 10.2 10 7.6 c S\n" +
            "q 0 g ${circle(10.0, 4.4, 1.1)} f Q\n"
        "Insert" -> "1 w 2 3 m 10 17 l 18 3 l h B\n"
        else -> "1 w 3 1 m 17 1 l 17 15 l 13 19 l 3 19 l h B\n" +
            "13 19 m 13 15 l 17 15 l S\n" +
            "0.8 w 6 12 m 14 12 l 6 8.5 m 14 8.5 l 6 5 m 12 5 l S\n"
    }

    /** The `/Name` icons of a file attachment (ISO 32000-1, 12.5.6.15). `PushPin` is the default. */
    private fun attachmentIcon(name: String?, color: RgbColor): String = when (name) {
        "Paperclip" -> {
            val clip = "9.5 7 m 9.5 14 l 9.5 16.8 13 16.8 13 14 c 13 5 l 13 1.5 6.5 1.5 6.5 5 c " +
                "6.5 16 l 6.5 19.8 16 19.8 16 16 c 16 8 l"
            // A black wire with the annotation's colour down its middle.
            "2.6 w $clip S\nq ${rgb(color)} RG 1.2 w $clip S Q\n"
        }
        "Graph" -> "1 w 3 2 3.5 9 re 8.25 2 3.5 14 re 13.5 2 3.5 6 re B\n"
        "Tag" -> "1 w 1.5 10 m 7.5 16.5 l 18.5 16.5 l 18.5 3.5 l 7.5 3.5 l h B\n${circle(7.5, 10.0, 1.6)} S\n"
        else -> "1.6 w 9 11 m 3 3 l S\n1 w ${circle(12.5, 12.5, 5.5)} B\n"
    }

    /* ── caret, stamp and free text ──────────────────────────────────────── */

    /** A caret fills its rectangle (ISO 32000-1, 12.5.6.11). */
    private fun caret(color: RgbColor, w: Double, h: Double): PdfStream {
        val content = "${rgb(color)} rg 0 0 m ${fmt(w / 2)} ${fmt(h)} l ${fmt(w)} 0 l " +
            "${fmt(w / 2)} ${fmt(h * 0.3)} l h f\n"
        return form(content.encodeToByteArray(), w, h)
    }

    /**
     * A stamp shows its `/Name` in capitals inside a frame (ISO 32000-1, 12.5.6.12).
     * `NotApproved` reads NOT APPROVED. The text fits the frame's width.
     */
    private fun stamp(name: String, color: RgbColor?, w: Double, h: Double): PdfStream {
        val text = stampText(name)
        val c = color ?: if (text in GREEN_STAMPS) STAMP_GREEN else STAMP_RED
        val frame = minOf(2.0, minOf(w, h) / 10.0)
        val pad = frame * 2.0 + 1.0
        val unit = StandardFont.TimesBold.stringWidth(text, 1.0).coerceAtLeast(0.01)
        val size = minOf((w - 2 * pad) / unit, (h - 2 * pad) / TIMES_BOLD_CAP, h * 0.6).coerceAtLeast(1.0)
        val x = (w - unit * size) / 2
        val y = (h - size * TIMES_BOLD_CAP) / 2
        val out = ByteArrayBuilder(128)
        out.ascii("q ${rgb(c)} rg ${rgb(c)} RG ${fmt(frame)} w 1 j\n")
        out.ascii("${fmt(frame / 2)} ${fmt(frame / 2)} ${fmt(w - frame)} ${fmt(h - frame)} re S\n")
        out.ascii("BT /TiBo ${fmt(size)} Tf ${fmt(x)} ${fmt(y)} Td ")
        PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(text)), out)
        out.ascii(" Tj ET Q\n")
        return form(out.toByteArray(), w, h, mapOf("TiBo" to StandardFont.TimesBold))
    }

    /** `NotApproved` → `NOT APPROVED`. Acrobat's set prefixes, as in `SBApproved`, are dropped. */
    private fun stampText(name: String): String {
        var n = name.removePrefix("#")
        for (prefix in STAMP_PREFIXES) {
            if (n.length > prefix.length + 1 && n.startsWith(prefix) &&
                n[prefix.length].isUpperCase() && n[prefix.length + 1].isLowerCase()
            ) n = n.substring(prefix.length)
        }
        val sb = StringBuilder()
        for ((i, ch) in n.withIndex()) {
            if (i > 0 && ch.isUpperCase() && n[i - 1].isLowerCase()) sb.append(' ')
            sb.append(ch.uppercaseChar())
        }
        return sb.toString().ifBlank { "DRAFT" }
    }

    /**
     * Free text draws `/Contents` in the `/DA` font, size and colour, wrapped to the
     * inner box that `/RD` leaves and aligned by `/Q` (ISO 32000-1, 12.5.6.6). Acrobat
     * keeps the fill in `/C` and PDF 2.0 names it `/IC`; the border takes the text
     * colour. A callout line, `/CL`, is drawn in the border colour.
     */
    private fun freeText(annot: PdfAnnotation, refs: IndirectResolver, w: Double, h: Double): PdfStream {
        val dict = annot.raw
        val da = FieldAppearance.parseDA((resolved(dict["DA"], refs) as? PdfString)?.asText())
        val font = standardFontFor(da.fontName)
        // The name goes into the content stream as it is, so it must need no escaping.
        val fontResource = da.fontName.takeIf { SAFE_NAME.matches(it) } ?: "F0"
        val size = if (da.fontSize > 0.0) da.fontSize else DEFAULT_TEXT_SIZE
        val quadding = (resolved(dict["Q"], refs) as? PdfInt)?.value?.toInt() ?: 0
        val border = annot.borderWidth?.takeIf { it >= 0.0 } ?: 1.0
        val fill = annot.interiorColor ?: annot.color
        val rd = (resolved(dict["RD"], refs) as? PdfArray)?.mapNotNull { numberOf(resolved(it, refs)) }
            ?.takeIf { it.size == 4 && it.all { d -> d >= 0.0 } }
        val left = rd?.get(0) ?: 0.0
        val bottom = rd?.get(3) ?: 0.0
        val boxW = w - left - (rd?.get(2) ?: 0.0)
        val boxH = h - bottom - (rd?.get(1) ?: 0.0)

        val out = ByteArrayBuilder(256)
        out.ascii("q\n")
        if (boxW > 0.0 && boxH > 0.0) {
            fill?.let { out.ascii("${rgb(it)} rg ${fmt(left)} ${fmt(bottom)} ${fmt(boxW)} ${fmt(boxH)} re f\n") }
        }
        val strokeColor = strokeOps(da.colorOps)
        val callout = (resolved(dict["CL"], refs) as? PdfArray)?.mapNotNull { numberOf(resolved(it, refs)) }
        if (callout != null && (callout.size == 4 || callout.size == 6)) {
            out.ascii("$strokeColor ${fmt(border.coerceAtLeast(0.5))} w ")
            for (i in callout.indices step 2) {
                out.ascii("${fmt(callout[i] - annot.rect.left)} ${fmt(callout[i + 1] - annot.rect.bottom)} ${if (i == 0) "m" else "l"} ")
            }
            out.ascii("S\n")
        }
        if (border > 0.0 && boxW > border && boxH > border) {
            out.ascii("$strokeColor ${fmt(border)} w ")
            out.ascii("${fmt(left + border / 2)} ${fmt(bottom + border / 2)} ${fmt(boxW - border)} ${fmt(boxH - border)} re S\n")
        }
        val pad = border + TEXT_PADDING
        val textW = boxW - 2 * pad
        if (textW > 0.0 && boxH > 2 * pad && annot.contents.isNotBlank()) {
            out.ascii("${fmt(left + pad)} ${fmt(bottom + pad)} ${fmt(textW)} ${fmt(boxH - 2 * pad)} re W n\n")
            out.ascii("BT ${da.colorOps} /$fontResource ${fmt(size)} Tf\n")
            var baseline = bottom + boxH - pad - size * ASCENT
            for (line in wrap(annot.contents, font, size, textW)) {
                if (baseline < bottom + pad - size) break
                val lineW = font.stringWidth(line, size)
                val x = left + pad + when (quadding) {
                    1 -> (textW - lineW) / 2
                    2 -> textW - lineW
                    else -> 0.0
                }
                out.ascii("1 0 0 1 ${fmt(x)} ${fmt(baseline)} Tm ")
                PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(line)), out)
                out.ascii(" Tj\n")
                baseline -= size * LEADING
            }
            out.ascii("ET\n")
        }
        out.ascii("Q\n")
        return form(out.toByteArray(), w, h, mapOf(fontResource to font))
    }

    /** Greedy word wrap of each paragraph of [text]. A word wider than [width] breaks where it must. */
    private fun wrap(text: String, font: StandardFont, size: Double, width: Double): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split("\r\n", "\r", "\n")) {
            var line = ""
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (font.stringWidth(candidate, size) <= width) { line = candidate; continue }
                if (line.isNotEmpty()) lines.add(line)
                line = word
                while (line.length > 1 && font.stringWidth(line, size) > width) {
                    var cut = line.length - 1
                    while (cut > 1 && font.stringWidth(line.substring(0, cut), size) > width) cut--
                    lines.add(line.substring(0, cut))
                    line = line.substring(cut)
                }
            }
            lines.add(line)
        }
        return lines
    }

    /**
     * The standard font behind a `/DA` font name: Acrobat's own `/DR` names such as
     * `Helv` and `TiBo`, or a family name. Anything else is Helvetica.
     */
    private fun standardFontFor(resourceName: String): StandardFont {
        ACROBAT_FONTS[resourceName]?.let { return it }
        val n = resourceName.lowercase()
        return when {
            "times" in n -> StandardFont.TimesRoman
            "cour" in n -> StandardFont.Courier
            else -> StandardFont.Helvetica
        }
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private fun form(content: ByteArray, w: Double, h: Double, fonts: Map<String, StandardFont> = emptyMap()): PdfStream {
        val dict = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Form"),
            "FormType" to PdfInt(1),
            "BBox" to PdfArray(listOf(PdfReal(0.0), PdfReal(0.0), PdfReal(w), PdfReal(h))),
        )
        if (fonts.isNotEmpty()) {
            val fontDicts = LinkedHashMap<String, PdfObject>()
            for ((resource, font) in fonts) fontDicts[resource] = fontDict(font)
            dict["Resources"] = PdfDictionary(linkedMapOf("Font" to PdfDictionary(fontDicts)))
        }
        return PdfStream(PdfDictionary(dict), content)
    }

    private fun fontDict(font: StandardFont): PdfDictionary = PdfDictionary(
        linkedMapOf(
            "Type" to PdfName("Font"),
            "Subtype" to PdfName("Type1"),
            "BaseFont" to PdfName(font.baseFont),
            "Encoding" to PdfName("WinAnsiEncoding"),
        ),
    )

    /** A circle as four Bézier arcs. */
    private fun circle(cx: Double, cy: Double, r: Double): String {
        val k = r * 0.5522847498
        return "${fmt(cx + r)} ${fmt(cy)} m " +
            "${fmt(cx + r)} ${fmt(cy + k)} ${fmt(cx + k)} ${fmt(cy + r)} ${fmt(cx)} ${fmt(cy + r)} c " +
            "${fmt(cx - k)} ${fmt(cy + r)} ${fmt(cx - r)} ${fmt(cy + k)} ${fmt(cx - r)} ${fmt(cy)} c " +
            "${fmt(cx - r)} ${fmt(cy - k)} ${fmt(cx - k)} ${fmt(cy - r)} ${fmt(cx)} ${fmt(cy - r)} c " +
            "${fmt(cx + k)} ${fmt(cy - r)} ${fmt(cx + r)} ${fmt(cy - k)} ${fmt(cx + r)} ${fmt(cy)} c h"
    }

    /** The stroking twin of a `/DA` fill colour: `rg` becomes `RG`, `g` becomes `G`, `k` becomes `K`. */
    private fun strokeOps(fillOps: String): String {
        val parts = fillOps.trim().split(' ')
        val op = when (parts.lastOrNull()) {
            "rg" -> "RG"
            "g" -> "G"
            "k" -> "K"
            else -> return "0 G"
        }
        return (parts.dropLast(1) + op).joinToString(" ")
    }

    private fun resolved(obj: PdfObject?, refs: IndirectResolver): PdfObject? =
        obj?.let { runCatching { it.resolve(refs) }.getOrNull() }

    private fun numberOf(o: PdfObject?): Double? = when (o) {
        is PdfReal -> o.value
        is PdfInt -> o.value.toDouble()
        else -> null
    }

    private fun rgb(c: RgbColor) = "${fmt(c.r)} ${fmt(c.g)} ${fmt(c.b)}"

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    private fun ByteArrayBuilder.ascii(s: String) = append(s.encodeToByteArray())

    private const val ICON_SIZE = 20.0
    private const val DEFAULT_TEXT_SIZE = 12.0
    private const val TEXT_PADDING = 2.0
    private const val ASCENT = 0.8
    private const val LEADING = 1.15

    /** Times-Bold capital height in em, from its AFM. */
    private const val TIMES_BOLD_CAP = 0.676

    private val NOTE_YELLOW = RgbColor(1.0, 0.9, 0.2)
    private val CARET_BLUE = RgbColor(0.0, 0.0, 1.0)
    private val STAMP_RED = RgbColor(0.8, 0.1, 0.1)
    private val STAMP_GREEN = RgbColor(0.1, 0.5, 0.1)
    private val GREEN_STAMPS = setOf("APPROVED", "FINAL", "COMPLETED")
    private val STAMP_PREFIXES = listOf("SB", "SH")
    private val SAFE_NAME = Regex("[A-Za-z0-9_.+-]+")

    private val ACROBAT_FONTS = mapOf(
        "Helv" to StandardFont.Helvetica, "HeBo" to StandardFont.HelveticaBold,
        "HeOb" to StandardFont.HelveticaOblique, "HeBO" to StandardFont.HelveticaBoldOblique,
        "TiRo" to StandardFont.TimesRoman, "TiBo" to StandardFont.TimesBold,
        "TiIt" to StandardFont.TimesItalic, "TiBI" to StandardFont.TimesBoldItalic,
        "Cour" to StandardFont.Courier, "CoBo" to StandardFont.CourierBold,
        "CoOb" to StandardFont.CourierOblique, "CoBO" to StandardFont.CourierBoldOblique,
    )
}
