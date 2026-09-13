package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * One PDF annotation (ISO 32000-1 §12.5).
 *
 * The [subtype] discriminates between the 20+ annotation types defined in
 * the spec. First-class support is provided for:
 *   - [Subtype.Link]: URL or named-destination hyperlinks
 *   - [Subtype.Highlight]: text highlight (yellow overlay)
 *   - [Subtype.Underline]: underline marker
 *   - [Subtype.StrikeOut]: strikethrough marker
 *   - [Subtype.Text]: sticky-note popup
 *
 * Other subtypes are parsed into [Subtype.Other] and exposed in [raw] so
 * callers can pattern-match. The rectangle and contents are always there.
 *
 * For rendering, [appearanceStream] (the /AP /N stream when present) is the
 * canonical way to draw the annotation. It is a Form XObject the spec
 * mandates "shall be used as the visual representation." We expose it as a
 * raw stream so PageRenderer can recursively render it.
 */
public data class PdfAnnotation(
    val subtype: Subtype,
    val rect: io.github.yuroyami.kitepdf.core.KiteRectangle,
    val contents: String,
    /** Border / highlight / underline colour, or null when /C is omitted. */
    val color: RgbColor?,
    /** Link annotation: URL for /A /URI actions; null otherwise. */
    val uri: String?,
    /** Parsed `/A` action (typed). `null` when the annotation has no action dict. */
    val action: PdfAction?,
    /** Raw `/Dest` value on link annotations. Pass through [PdfDocument.resolveDestination]. */
    val rawDestination: io.github.yuroyami.kitepdf.core.parser.PdfObject?,
    /** /AP /N appearance Form XObject, or null. */
    val appearanceStream: PdfStream?,
    /** Annotation flags (`/F`): bit 2 = Hidden, bit 6 = NoView, etc. (§12.5.3). */
    val flags: Int = 0,
    /** `/QuadPoints` (8 numbers per quad) for text-markup annotations. */
    val quadPoints: List<Double>? = null,
    /** `/InkList`: one list of alternating x/y per ink stroke. */
    val inkLists: List<List<Double>>? = null,
    /** `/Vertices` (Polygon/PolyLine) or `/L` (Line): alternating x/y. */
    val vertices: List<Double>? = null,
    /** `/IC` interior (fill) colour for Square/Circle/Line/Polygon. */
    val interiorColor: RgbColor? = null,
    /**
     * Declared border width in points, from `/BS /W` or the third element of `/Border`, or null
     * when the annotation declares neither and the §12.5.4 default of 1 applies.
     *
     * A link that says `/Border [0 0 0]` is asking for no visible frame, which is the common case
     * for links styled as coloured text. Ignoring it drew a box around them anyway.
     */
    val borderWidth: Double? = null,
    /** Border style from `/BS /S`: `S` solid, `D` dashed, `B` beveled, `I` inset, `U` underline. Null when undeclared. */
    val borderStyle: String? = null,
    /** Dash array from `/BS /D`, or the one `/Border` may carry as its fourth element. */
    val borderDash: List<Double>? = null,
    /** The raw dict, for callers that need fields we didn't extract. */
    val raw: PdfDictionary,
) {

    /** True when the annotation should not be displayed (Hidden or NoView set). */
    val isHidden: Boolean get() = (flags and FLAG_HIDDEN) != 0 || (flags and FLAG_NOVIEW) != 0

    /**
     * The Invisible flag (§12.5.3, Table 165, bit 1): a viewer with no handler
     * for a non-standard subtype hides the annotation instead of drawing its
     * appearance.
     */
    val isInvisible: Boolean get() = (flags and FLAG_INVISIBLE) != 0

    /**
     * The NoZoom flag (§12.5.3, Table 165, bit 4): the appearance keeps its own
     * size, with the upper-left corner of the rectangle fixed, instead of
     * stretching to the rectangle.
     */
    val isNoZoom: Boolean get() = (flags and FLAG_NOZOOM) != 0

    public enum class Subtype {
        Link, Highlight, Underline, StrikeOut, Squiggly, Text, FreeText, Line, Square, Circle,
        Polygon, PolyLine, Ink, Stamp, Caret, Popup, FileAttachment, Sound, Movie, Widget,
        Screen, PrinterMark, TrapNet, Watermark, ThreeD, Other,
    }

    public companion object {

        public fun parse(dict: PdfDictionary, refs: IndirectResolver): PdfAnnotation {
            val subtypeName = dict.getName("Subtype") ?: ""
            val subtype = parseSubtype(subtypeName)
            val rect = (dict.getArray("Rect") ?: PdfArray(emptyList())).let { rectFromArray(it) }
            val contents = when (val c = dict["Contents"]) {
                is PdfString -> c.asText()
                else -> ""
            }
            val color = (dict.getArray("C", refs))?.let { parseColor(it) }
            val action = PdfAction.parse(dict.getDict("A", refs), refs)
            val uri = (action as? PdfAction.Uri)?.uri ?: legacyUriFallback(dict, refs)
            val rawDest = dict["Dest"]
            val appearanceStream = selectAppearance(dict, refs)
            // /F may be an indirect reference; read unresolved it gave no flags (#165).
            val flags = when (val f = dict["F"]?.resolve(refs)) {
                is PdfInt -> f.value.toInt()
                is PdfReal -> f.value.toInt()
                else -> 0
            }
            val quadPoints = numArray(dict.getArray("QuadPoints", refs))
            val inkLists = (dict.getArray("InkList", refs))?.mapNotNull { numArray(it as? PdfArray) }
            val vertices = numArray(dict.getArray("Vertices", refs))
                ?: numArray(dict.getArray("L", refs))   // Line endpoints
            val interiorColor = (dict.getArray("IC", refs))?.let { parseColor(it) }
            return PdfAnnotation(
                subtype, rect, contents, color, uri, action, rawDest, appearanceStream,
                flags, quadPoints, inkLists, vertices, interiorColor,
                borderWidth = parseBorderWidth(dict, refs), borderStyle = parseBorderStyle(dict, refs),
                borderDash = parseBorderDash(dict, refs), raw = dict,
            )
        }

        /**
         * Declared border width, `/BS /W` first and the third element of `/Border` second.
         *
         * §12.5.4: `/BS` supersedes `/Border` when both are present, and `/Border` is
         * `[hRadius vRadius width …]`, so the width lives at index 2. Null means neither was
         * declared and the caller should apply the default of 1.
         */
        private fun parseBorderWidth(dict: PdfDictionary, refs: IndirectResolver): Double? {
            dict.getDict("BS", refs)?.let { bs ->
                when (val w = bs["W"]) {
                    is PdfReal -> return w.value
                    is PdfInt -> return w.value.toDouble()
                    else -> Unit
                }
            }
            val border = dict.getArray("Border", refs) ?: return null
            return when (val w = border.getOrNull(2)) {
                is PdfReal -> w.value
                is PdfInt -> w.value.toDouble()
                else -> null
            }
        }

        /** `/BS /S` (ISO 32000-1, 12.5.4, Table 166). */
        private fun parseBorderStyle(dict: PdfDictionary, refs: IndirectResolver): String? =
            (dict.getDict("BS", refs)?.get("S")?.resolve(refs) as? PdfName)?.value

        /** `/BS /D` first, then the dash array `/Border` may carry as its fourth element (ISO 32000-1, 12.5.4). */
        private fun parseBorderDash(dict: PdfDictionary, refs: IndirectResolver): List<Double>? {
            val bsDash = dict.getDict("BS", refs)?.get("D")?.resolve(refs) as? PdfArray
            val legacy = dict.getArray("Border", refs)?.getOrNull(3)?.resolve(refs) as? PdfArray
            return numArray(bsDash ?: legacy)?.takeIf { d -> d.isNotEmpty() && d.all { it >= 0.0 } && d.any { it > 0.0 } }
        }

        /**
         * Resolve the annotation's normal (`/N`) appearance. When `/AP /N` is a
         * Form XObject stream, use it directly. When it is a sub-dictionary of
         * named appearance states (checkbox/radio widgets: `/N << /On … /Off … >>`),
         * select the entry named by `/AS`; without it, fall back to `/Off`, then
         * the first state (§12.5.5, §12.7.4.2). Returning null here is what made
         * checkbox/radio widgets render blank before.
         */
        private fun selectAppearance(dict: PdfDictionary, refs: IndirectResolver): PdfStream? {
            val n = dict.getDict("AP", refs)?.get("N")?.resolve(refs) ?: return null
            return when (n) {
                is PdfStream -> n
                is PdfDictionary -> {
                    // A named state with no entry has no appearance, so nothing
                    // paints (#59). Only a missing /AS falls back to /Off, then
                    // the first state. /AS may be an indirect reference.
                    val state = (dict["AS"]?.resolve(refs) as? PdfName)?.value
                    val pick = if (state != null) n[state] else n["Off"] ?: n.values.firstOrNull()
                    pick?.resolve(refs) as? PdfStream
                }
                else -> null
            }
        }

        private fun numArray(arr: PdfArray?): List<Double>? = arr?.map { v ->
            when (v) { is PdfReal -> v.value; is PdfInt -> v.value.toDouble(); else -> 0.0 }
        }

        private const val FLAG_INVISIBLE = 1      // bit 1
        private const val FLAG_HIDDEN = 1 shl 1   // bit 2
        private const val FLAG_NOZOOM = 1 shl 3   // bit 4
        private const val FLAG_NOVIEW = 1 shl 5   // bit 6

        /**
         * Edge case: annotations whose /A dict lacks the spec-required /S
         * type entry but still carries a /URI string. The action parser
         * returns [PdfAction.Unknown] for these; surface the URL anyway.
         */
        private fun legacyUriFallback(dict: PdfDictionary, refs: IndirectResolver): String? {
            val action = dict.getDict("A", refs) ?: return null
            return (action["URI"] as? PdfString)?.asText()
        }

        private fun parseSubtype(name: String): Subtype = when (name) {
            "Link" -> Subtype.Link
            "Highlight" -> Subtype.Highlight
            "Underline" -> Subtype.Underline
            "StrikeOut" -> Subtype.StrikeOut
            "Squiggly" -> Subtype.Squiggly
            "Text" -> Subtype.Text
            "FreeText" -> Subtype.FreeText
            "Line" -> Subtype.Line
            "Square" -> Subtype.Square
            "Circle" -> Subtype.Circle
            "Polygon" -> Subtype.Polygon
            "PolyLine" -> Subtype.PolyLine
            "Ink" -> Subtype.Ink
            "Stamp" -> Subtype.Stamp
            "Caret" -> Subtype.Caret
            "Popup" -> Subtype.Popup
            "FileAttachment" -> Subtype.FileAttachment
            "Sound" -> Subtype.Sound
            "Movie" -> Subtype.Movie
            "Widget" -> Subtype.Widget
            "Screen" -> Subtype.Screen
            "PrinterMark" -> Subtype.PrinterMark
            "TrapNet" -> Subtype.TrapNet
            "Watermark" -> Subtype.Watermark
            "3D" -> Subtype.ThreeD
            else -> Subtype.Other
        }

        private fun parseColor(arr: PdfArray): RgbColor? {
            fun n(idx: Int) = when (val v = arr.getOrNull(idx)) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return when (arr.size) {
                1 -> RgbColor.gray(n(0))
                3 -> RgbColor(n(0), n(1), n(2))
                4 -> io.github.yuroyami.kitepdf.core.render.KiteColorSpace.DeviceCMYK
                    .toRgb(doubleArrayOf(n(0), n(1), n(2), n(3)))
                else -> null
            }
        }

        /**
         * `/Rect` gives two diagonally opposite corners in any order (§7.9.5), so the result is
         * normalised. Without it a `[x2 y2 x1 y1]` annotation produced an inverted box that no
         * containment test could match and no border could paint, which made every link on the
         * page silently dead.
         */
        private fun rectFromArray(arr: PdfArray): io.github.yuroyami.kitepdf.core.KiteRectangle {
            fun n(idx: Int) = when (val v = arr.getOrNull(idx)) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return io.github.yuroyami.kitepdf.core.KiteRectangle(n(0), n(1), n(2), n(3)).normalized()
        }
    }
}
