package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.KiteRectangle
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
import kotlin.math.max
import kotlin.math.min

/**
 * An annotation for [PdfEditor.addAnnotation] to add to a page (ISO 32000-1, 12.5). Each one is
 * written with its own `/AP` appearance stream, so another viewer draws it as KitePDF does.
 * Coordinates are in the page's default user space, as a [PdfAnnotation] reads them.
 *
 * [contents] is the text of the annotation, or for one that shows no text, a description of it
 * (12.5.2, Table 164, `/Contents`), which a screen reader can say.
 */
public sealed class PdfNewAnnotation {
    public abstract val contents: String
    public abstract val color: RgbColor

    /**
     * A text markup annotation over the quadrilaterals [quadPoints]: eight numbers per quad, in
     * the order `/QuadPoints` keeps them, upper left, upper right, lower left, lower right
     * (12.5.6.10, Table 179), as [PdfAnnotation.quadPoints] and a search hit give them.
     */
    public sealed class TextMarkup : PdfNewAnnotation() {
        public abstract val quadPoints: List<Double>
    }

    /** A highlight, drawn as the colour multiplied over the text (12.5.6.10). */
    public class Highlight(
        override val quadPoints: List<Double>,
        override val color: RgbColor = RgbColor(1.0, 0.9, 0.0),
        override val contents: String = "",
    ) : TextMarkup()

    /** A line under the text of each quad. */
    public class Underline(
        override val quadPoints: List<Double>,
        override val color: RgbColor = RgbColor(0.0, 0.0, 1.0),
        override val contents: String = "",
    ) : TextMarkup()

    /** A line through the middle of the text of each quad. */
    public class StrikeOut(
        override val quadPoints: List<Double>,
        override val color: RgbColor = RgbColor(1.0, 0.0, 0.0),
        override val contents: String = "",
    ) : TextMarkup()

    /** A wavy line under the text of each quad. */
    public class Squiggly(
        override val quadPoints: List<Double>,
        override val color: RgbColor = RgbColor(1.0, 0.0, 0.0),
        override val contents: String = "",
    ) : TextMarkup()

    /**
     * A note: an icon at [rect] that opens [contents] (12.5.6.4). [icon] is one of the names of
     * Table 172, such as `Note`, `Comment`, `Key`, `Help`, `Paragraph` or `Insert`.
     */
    public class Text(
        public val rect: KiteRectangle,
        override val contents: String,
        override val color: RgbColor = RgbColor(1.0, 0.9, 0.0),
        public val icon: String = "Note",
    ) : PdfNewAnnotation()

    /** Text shown on the page inside [rect], in Helvetica of [fontSize] points and [textColor] (12.5.6.6). [color] is its border. */
    public class FreeText(
        public val rect: KiteRectangle,
        override val contents: String,
        public val fontSize: Double = 12.0,
        public val textColor: RgbColor = RgbColor(0.0, 0.0, 0.0),
        override val color: RgbColor = RgbColor(0.0, 0.0, 0.0),
        public val borderWidth: Double = 1.0,
    ) : PdfNewAnnotation()

    /** Free-hand strokes: each list holds alternating x and y (12.5.6.13, `/InkList`). */
    public class Ink(
        public val strokes: List<List<Double>>,
        override val color: RgbColor = RgbColor(1.0, 0.0, 0.0),
        public val width: Double = 1.0,
        override val contents: String = "",
    ) : PdfNewAnnotation()

    /** A rectangle drawn inside [rect], filled with [interiorColor] when it has one (12.5.6.8). */
    public class Square(
        public val rect: KiteRectangle,
        override val color: RgbColor = RgbColor(1.0, 0.0, 0.0),
        public val interiorColor: RgbColor? = null,
        public val width: Double = 1.0,
        override val contents: String = "",
    ) : PdfNewAnnotation()

    /** An ellipse drawn inside [rect], filled with [interiorColor] when it has one (12.5.6.8). */
    public class Circle(
        public val rect: KiteRectangle,
        override val color: RgbColor = RgbColor(1.0, 0.0, 0.0),
        public val interiorColor: RgbColor? = null,
        public val width: Double = 1.0,
        override val contents: String = "",
    ) : PdfNewAnnotation()

    /** A straight line from ([x1], [y1]) to ([x2], [y2]) (12.5.6.7). */
    public class Line(
        public val x1: Double,
        public val y1: Double,
        public val x2: Double,
        public val y2: Double,
        override val color: RgbColor = RgbColor(1.0, 0.0, 0.0),
        public val width: Double = 1.0,
        override val contents: String = "",
    ) : PdfNewAnnotation()

    /**
     * A link over [rect] to the address [uri] (12.5.6.5, 12.6.4.7), drawn with no border. Its
     * appearance is empty: the page under it shows through.
     */
    public class Link(
        public val rect: KiteRectangle,
        public val uri: String,
        override val contents: String = "",
    ) : PdfNewAnnotation() {
        override val color: RgbColor get() = RgbColor(0.0, 0.0, 1.0)
    }
}

/** Builds the dictionary and the appearance of a [PdfNewAnnotation] (#638). */
internal object AnnotationWriter {

    /** The annotation dictionary without its `/AP` and `/P`, and its rectangle. */
    fun dictionary(annotation: PdfNewAnnotation): PdfDictionary {
        val rect = rectOf(annotation)
        val map = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("Annot"),
            "Subtype" to PdfName(subtypeName(annotation)),
            "Rect" to numbers(listOf(rect.left, rect.bottom, rect.right, rect.top)),
            // Print, so the annotation prints as it shows (12.5.3, Table 165).
            "F" to PdfInt(PRINT.toLong()),
        )
        if (annotation.contents.isNotEmpty()) map["Contents"] = PdfString(PdfText.encodeTextString(annotation.contents))
        if (annotation !is PdfNewAnnotation.Link) map["C"] = color(annotation.color)
        when (annotation) {
            is PdfNewAnnotation.TextMarkup -> map["QuadPoints"] = numbers(annotation.quadPoints)
            is PdfNewAnnotation.Text -> {
                map["Name"] = PdfName(annotation.icon)
                map["Open"] = io.github.yuroyami.kitepdf.core.parser.PdfBoolean(false)
            }
            is PdfNewAnnotation.FreeText -> {
                val c = annotation.textColor
                map["DA"] = PdfString("/Helv ${fmt(annotation.fontSize)} Tf ${fmt(c.r)} ${fmt(c.g)} ${fmt(c.b)} rg".encodeToByteArray())
                map["BS"] = border(annotation.borderWidth)
            }
            is PdfNewAnnotation.Ink -> {
                map["InkList"] = PdfArray(annotation.strokes.map(::numbers))
                map["BS"] = border(annotation.width)
            }
            is PdfNewAnnotation.Square -> {
                annotation.interiorColor?.let { map["IC"] = color(it) }
                map["BS"] = border(annotation.width)
            }
            is PdfNewAnnotation.Circle -> {
                annotation.interiorColor?.let { map["IC"] = color(it) }
                map["BS"] = border(annotation.width)
            }
            is PdfNewAnnotation.Line -> {
                map["L"] = numbers(listOf(annotation.x1, annotation.y1, annotation.x2, annotation.y2))
                map["BS"] = border(annotation.width)
            }
            is PdfNewAnnotation.Link -> {
                map["Border"] = numbers(listOf(0.0, 0.0, 0.0))
                map["A"] = PdfDictionary(linkedMapOf("S" to PdfName("URI"), "URI" to PdfString(annotation.uri.encodeToByteArray())))
            }
        }
        return PdfDictionary(map)
    }

    /** The `/AP /N` form of [annotation], whose dictionary is [dict]: a box as large as its rectangle. */
    fun appearance(annotation: PdfNewAnnotation, dict: PdfDictionary, refs: IndirectResolver): PdfStream {
        val rect = rectOf(annotation)
        val w = rect.width
        val h = rect.height
        // A note and a free text draw as KitePDF draws a file's note or free text without an appearance.
        if (annotation is PdfNewAnnotation.Text || annotation is PdfNewAnnotation.FreeText) {
            AnnotationAppearance.synthesize(PdfAnnotation.parse(dict, refs), refs)?.let { return it }
        }
        val out = StringBuilder()
        // The form's space starts at the rectangle's lower left corner.
        fun x(v: Double) = fmt(v - rect.left)
        fun y(v: Double) = fmt(v - rect.bottom)
        val c = annotation.color
        var multiply = false
        when (annotation) {
            is PdfNewAnnotation.Highlight -> {
                multiply = true
                out.append("${rgb(c)} rg ")
                for (q in annotation.quadPoints.chunked(8).filter { it.size == 8 }) {
                    // Upper left, upper right, lower right, lower left.
                    out.append("${x(q[0])} ${y(q[1])} m ${x(q[2])} ${y(q[3])} l ${x(q[6])} ${y(q[7])} l ${x(q[4])} ${y(q[5])} l h f ")
                }
            }
            is PdfNewAnnotation.Underline, is PdfNewAnnotation.StrikeOut, is PdfNewAnnotation.Squiggly -> {
                out.append("${rgb(c)} RG ")
                for (q in (annotation as PdfNewAnnotation.TextMarkup).quadPoints.chunked(8).filter { it.size == 8 }) {
                    val height = kotlin.math.hypot(q[0] - q[4], q[1] - q[5])
                    val stroke = max(height / 14.0, 0.5)
                    out.append("${fmt(stroke)} w ")
                    // From the lower edge (0) to the upper edge (1) of the quad.
                    val at = when (annotation) {
                        is PdfNewAnnotation.StrikeOut -> 0.5
                        else -> 0.08
                    }
                    val sx = q[4] + (q[0] - q[4]) * at
                    val sy = q[5] + (q[1] - q[5]) * at
                    val ex = q[6] + (q[2] - q[6]) * at
                    val ey = q[7] + (q[3] - q[7]) * at
                    if (annotation is PdfNewAnnotation.Squiggly) {
                        val length = kotlin.math.hypot(ex - sx, ey - sy)
                        val waves = max(1, (length / (height / 3.0)).toInt())
                        val nx = -(q[1] - q[5]) / height * (height / 10.0)
                        val ny = (q[0] - q[4]) / height * (height / 10.0)
                        out.append("${x(sx)} ${y(sy)} m ")
                        for (i in 1..waves) {
                            val t = i.toDouble() / waves
                            val up = if (i % 2 == 1) 1.0 else 0.0
                            out.append("${x(sx + (ex - sx) * t + nx * up)} ${y(sy + (ey - sy) * t + ny * up)} l ")
                        }
                        out.append("S ")
                    } else {
                        out.append("${x(sx)} ${y(sy)} m ${x(ex)} ${y(ey)} l S ")
                    }
                }
            }
            is PdfNewAnnotation.Ink -> {
                out.append("${rgb(c)} RG ${fmt(annotation.width)} w 1 J 1 j ")
                for (stroke in annotation.strokes) {
                    val points = stroke.chunked(2).filter { it.size == 2 }
                    if (points.isEmpty()) continue
                    out.append("${x(points[0][0])} ${y(points[0][1])} m ")
                    if (points.size == 1) out.append("${x(points[0][0])} ${y(points[0][1])} l ")
                    for (p in points.drop(1)) out.append("${x(p[0])} ${y(p[1])} l ")
                    out.append("S ")
                }
            }
            is PdfNewAnnotation.Square -> {
                val inset = annotation.width / 2
                out.append("${rgb(c)} RG ${fmt(annotation.width)} w ")
                annotation.interiorColor?.let { out.append("${rgb(it)} rg ") }
                out.append("${fmt(inset)} ${fmt(inset)} ${fmt(w - annotation.width)} ${fmt(h - annotation.width)} re ")
                out.append(if (annotation.interiorColor != null) "B " else "S ")
            }
            is PdfNewAnnotation.Circle -> {
                val inset = annotation.width / 2
                out.append("${rgb(c)} RG ${fmt(annotation.width)} w ")
                annotation.interiorColor?.let { out.append("${rgb(it)} rg ") }
                out.append(ellipse(w / 2, h / 2, w / 2 - inset, h / 2 - inset))
                out.append(if (annotation.interiorColor != null) " B " else " S ")
            }
            is PdfNewAnnotation.Line -> {
                out.append("${rgb(c)} RG ${fmt(annotation.width)} w ")
                out.append("${x(annotation.x1)} ${y(annotation.y1)} m ${x(annotation.x2)} ${y(annotation.y2)} l S ")
            }
            // The page under a link shows through; Text and FreeText drew above unless their rectangle is empty.
            is PdfNewAnnotation.Link, is PdfNewAnnotation.Text, is PdfNewAnnotation.FreeText -> Unit
        }
        val formDict = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Form"),
            "FormType" to PdfInt(1),
            "BBox" to numbers(listOf(0.0, 0.0, w, h)),
        )
        val content = if (multiply) "/KM gs ${out.toString().trim()}" else out.toString().trim()
        if (multiply) {
            formDict["Resources"] = PdfDictionary(
                linkedMapOf("ExtGState" to PdfDictionary(linkedMapOf("KM" to PdfDictionary(linkedMapOf("BM" to PdfName("Multiply")))))),
            )
        }
        return PdfStream(PdfDictionary(formDict), ByteArrayBuilder().apply { append(content.encodeToByteArray()) }.toByteArray())
    }

    /** The rectangle of [annotation]: its own, or the bounds of what it draws, wide enough for its strokes. */
    fun rectOf(annotation: PdfNewAnnotation): KiteRectangle = when (annotation) {
        is PdfNewAnnotation.TextMarkup -> bounds(annotation.quadPoints, 1.0)
        is PdfNewAnnotation.Text -> annotation.rect.normalized()
        is PdfNewAnnotation.FreeText -> annotation.rect.normalized()
        is PdfNewAnnotation.Ink -> bounds(annotation.strokes.flatten(), annotation.width / 2 + 1.0)
        is PdfNewAnnotation.Square -> annotation.rect.normalized()
        is PdfNewAnnotation.Circle -> annotation.rect.normalized()
        is PdfNewAnnotation.Line -> bounds(listOf(annotation.x1, annotation.y1, annotation.x2, annotation.y2), annotation.width / 2 + 1.0)
        is PdfNewAnnotation.Link -> annotation.rect.normalized()
    }

    private fun subtypeName(annotation: PdfNewAnnotation): String = when (annotation) {
        is PdfNewAnnotation.Highlight -> "Highlight"
        is PdfNewAnnotation.Underline -> "Underline"
        is PdfNewAnnotation.StrikeOut -> "StrikeOut"
        is PdfNewAnnotation.Squiggly -> "Squiggly"
        is PdfNewAnnotation.Text -> "Text"
        is PdfNewAnnotation.FreeText -> "FreeText"
        is PdfNewAnnotation.Ink -> "Ink"
        is PdfNewAnnotation.Square -> "Square"
        is PdfNewAnnotation.Circle -> "Circle"
        is PdfNewAnnotation.Line -> "Line"
        is PdfNewAnnotation.Link -> "Link"
    }

    private fun bounds(xy: List<Double>, pad: Double): KiteRectangle {
        require(xy.size >= 2 && xy.size % 2 == 0) { "an annotation needs pairs of coordinates (was ${xy.size} numbers)" }
        require(xy.all { it.isFinite() }) { "an annotation's coordinates must be finite" }
        var left = Double.POSITIVE_INFINITY
        var bottom = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var top = Double.NEGATIVE_INFINITY
        for (i in xy.indices step 2) {
            left = min(left, xy[i]); right = max(right, xy[i])
            bottom = min(bottom, xy[i + 1]); top = max(top, xy[i + 1])
        }
        return KiteRectangle(left - pad, bottom - pad, right + pad, top + pad)
    }

    private fun border(width: Double): PdfDictionary = PdfDictionary(linkedMapOf("W" to PdfReal(width), "S" to PdfName("S")))

    private fun numbers(values: List<Double>): PdfArray = PdfArray(values.map { PdfReal(it) })

    private fun color(c: RgbColor): PdfArray = numbers(listOf(c.r, c.g, c.b))

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): String {
        val kx = rx * 0.5522847498
        val ky = ry * 0.5522847498
        return "${fmt(cx + rx)} ${fmt(cy)} m " +
            "${fmt(cx + rx)} ${fmt(cy + ky)} ${fmt(cx + kx)} ${fmt(cy + ry)} ${fmt(cx)} ${fmt(cy + ry)} c " +
            "${fmt(cx - kx)} ${fmt(cy + ry)} ${fmt(cx - rx)} ${fmt(cy + ky)} ${fmt(cx - rx)} ${fmt(cy)} c " +
            "${fmt(cx - rx)} ${fmt(cy - ky)} ${fmt(cx - kx)} ${fmt(cy - ry)} ${fmt(cx)} ${fmt(cy - ry)} c " +
            "${fmt(cx + kx)} ${fmt(cy - ry)} ${fmt(cx + rx)} ${fmt(cy - ky)} ${fmt(cx + rx)} ${fmt(cy)} c h"
    }

    private fun rgb(c: RgbColor) = "${fmt(c.r)} ${fmt(c.g)} ${fmt(c.b)}"

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    /** The Print flag of an annotation (12.5.3, Table 165, bit 3). */
    private const val PRINT = 4
}
