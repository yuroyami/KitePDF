package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.parser.PdfString
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * One PDF annotation (ISO 32000-1 §12.5).
 *
 * The [subtype] discriminates between the 20+ annotation types defined in
 * the spec. First-class support is provided for:
 *   - [Subtype.Link] — URL or named-destination hyperlinks
 *   - [Subtype.Highlight] — text highlight (yellow overlay)
 *   - [Subtype.Underline] — underline marker
 *   - [Subtype.StrikeOut] — strikethrough marker
 *   - [Subtype.Text] — sticky-note popup
 *
 * Other subtypes are parsed into [Subtype.Other] and exposed in [raw] so
 * callers can pattern-match. The rectangle and contents are always there.
 *
 * For rendering, [appearanceStream] (the /AP /N stream when present) is the
 * canonical way to draw the annotation — it's a Form XObject the spec
 * mandates "shall be used as the visual representation." We expose it as a
 * raw stream so PageRenderer can recursively render it.
 */
data class PdfAnnotation(
    val subtype: Subtype,
    val rect: io.github.yuroyami.kitepdf.Rectangle,
    val contents: String,
    /** Border / highlight / underline colour, or null when /C is omitted. */
    val color: RgbColor?,
    /** Link annotation: URL for /A /URI actions; null otherwise. */
    val uri: String?,
    /** Parsed `/A` action (typed). `null` when the annotation has no action dict. */
    val action: PdfAction?,
    /** Raw `/Dest` value on link annotations — pass through [PdfDocument.resolveDestination]. */
    val rawDestination: io.github.yuroyami.kitepdf.parser.PdfObject?,
    /** /AP /N appearance Form XObject, or null. */
    val appearanceStream: PdfStream?,
    /** Annotation flags (`/F`): bit 2 = Hidden, bit 6 = NoView, etc. (§12.5.3). */
    val flags: Int = 0,
    /** `/QuadPoints` (8 numbers per quad) for text-markup annotations. */
    val quadPoints: List<Double>? = null,
    /** `/InkList` — one list of alternating x/y per ink stroke. */
    val inkLists: List<List<Double>>? = null,
    /** `/Vertices` (Polygon/PolyLine) or `/L` (Line) — alternating x/y. */
    val vertices: List<Double>? = null,
    /** `/IC` interior (fill) colour for Square/Circle/Line/Polygon. */
    val interiorColor: RgbColor? = null,
    /** The raw dict — for callers that need fields we didn't extract. */
    val raw: PdfDictionary,
) {

    /** True when the annotation should not be displayed (Hidden or NoView set). */
    val isHidden: Boolean get() = (flags and FLAG_HIDDEN) != 0 || (flags and FLAG_NOVIEW) != 0

    enum class Subtype {
        Link, Highlight, Underline, StrikeOut, Squiggly, Text, FreeText, Line, Square, Circle,
        Polygon, PolyLine, Ink, Stamp, Caret, Popup, FileAttachment, Sound, Movie, Widget,
        Screen, PrinterMark, TrapNet, Watermark, ThreeD, Other,
    }

    companion object {

        fun parse(dict: PdfDictionary, refs: IndirectResolver): PdfAnnotation {
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
            val flags = dict.getInt("F")?.toInt() ?: 0
            val quadPoints = numArray(dict.getArray("QuadPoints", refs))
            val inkLists = (dict.getArray("InkList", refs))?.mapNotNull { numArray(it as? PdfArray) }
            val vertices = numArray(dict.getArray("Vertices", refs))
                ?: numArray(dict.getArray("L", refs))   // Line endpoints
            val interiorColor = (dict.getArray("IC", refs))?.let { parseColor(it) }
            return PdfAnnotation(
                subtype, rect, contents, color, uri, action, rawDest, appearanceStream,
                flags, quadPoints, inkLists, vertices, interiorColor, dict,
            )
        }

        /**
         * Resolve the annotation's normal (`/N`) appearance. When `/AP /N` is a
         * Form XObject stream, use it directly. When it is a sub-dictionary of
         * named appearance states (checkbox/radio widgets — `/N << /On … /Off … >>`),
         * select the entry named by `/AS`; without it, fall back to `/Off`, then
         * the first state (§12.5.5, §12.7.4.2). Returning null here is what made
         * checkbox/radio widgets render blank before.
         */
        private fun selectAppearance(dict: PdfDictionary, refs: IndirectResolver): PdfStream? {
            val n = dict.getDict("AP", refs)?.get("N")?.resolve(refs) ?: return null
            return when (n) {
                is PdfStream -> n
                is PdfDictionary -> {
                    val state = dict.getName("AS")
                    val pick = (state?.let { n[it] } ?: n["Off"] ?: n.values.firstOrNull())
                    pick?.resolve(refs) as? PdfStream
                }
                else -> null
            }
        }

        private fun numArray(arr: PdfArray?): List<Double>? = arr?.map { v ->
            when (v) { is PdfReal -> v.value; is PdfInt -> v.value.toDouble(); else -> 0.0 }
        }

        private const val FLAG_HIDDEN = 1 shl 1   // bit 2
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
                4 -> io.github.yuroyami.kitepdf.render.ColorSpace.DeviceCMYK
                    .toRgb(doubleArrayOf(n(0), n(1), n(2), n(3)))
                else -> null
            }
        }

        private fun rectFromArray(arr: PdfArray): io.github.yuroyami.kitepdf.Rectangle {
            fun n(idx: Int) = when (val v = arr.getOrNull(idx)) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return io.github.yuroyami.kitepdf.Rectangle(n(0), n(1), n(2), n(3))
        }
    }
}
