package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Builds a page content stream (ISO 32000-1 §8–§9) from typed drawing
 * operations, emitting the raw operator bytes. Each PDF operator takes its
 * operands first, then the operator keyword: `r g b rg`, `x y m`, `(str) Tj`.
 *
 * Coordinates are in the default user space (origin bottom-left, points).
 * Numbers are formatted without scientific notation (via [PdfObjectWriter]);
 * names and strings are escaped the same way the serializer escapes them.
 *
 * Fonts and images are referenced through [fontResolver] / [imageResolver],
 * which map a [StandardFont] / [PdfImage] to the resource name the owning
 * [PdfBuilder] will register in `/Resources`.
 */
class ContentStreamBuilder internal constructor(
    private val fontResolver: (StandardFont) -> String,
    private val imageResolver: (PdfImage) -> String = {
        throw UnsupportedOperationException(
            "drawImage() is only available via PdfBuilder.page { }; this context doesn't register image resources.",
        )
    },
) {
    private val out = ByteArrayBuilder(128)

    /* ─── Graphics state ─────────────────────────────────────────────────── */

    fun save(): ContentStreamBuilder = op("q")
    fun restore(): ContentStreamBuilder = op("Q")

    /** Concatenate [a b c d e f] onto the current transformation matrix (`cm`). */
    fun transform(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double): ContentStreamBuilder {
        num(a); num(b); num(c); num(d); num(e); num(f); return op("cm")
    }

    fun setLineWidth(w: Double): ContentStreamBuilder { num(w); return op("w") }

    /* ─── Colour ─────────────────────────────────────────────────────────── */

    fun setFillRgb(r: Double, g: Double, b: Double): ContentStreamBuilder { num(r); num(g); num(b); return op("rg") }
    fun setStrokeRgb(r: Double, g: Double, b: Double): ContentStreamBuilder { num(r); num(g); num(b); return op("RG") }
    fun setFillGray(g: Double): ContentStreamBuilder { num(g); return op("g") }
    fun setStrokeGray(g: Double): ContentStreamBuilder { num(g); return op("G") }

    /* ─── Paths ──────────────────────────────────────────────────────────── */

    fun moveTo(x: Double, y: Double): ContentStreamBuilder { num(x); num(y); return op("m") }
    fun lineTo(x: Double, y: Double): ContentStreamBuilder { num(x); num(y); return op("l") }
    fun rectangle(x: Double, y: Double, w: Double, h: Double): ContentStreamBuilder {
        num(x); num(y); num(w); num(h); return op("re")
    }
    fun closePath(): ContentStreamBuilder = op("h")
    fun stroke(): ContentStreamBuilder = op("S")
    fun fill(): ContentStreamBuilder = op("f")
    fun fillAndStroke(): ContentStreamBuilder = op("B")
    fun endPath(): ContentStreamBuilder = op("n")

    /* ─── Clipping ───────────────────────────────────────────────────────── */

    /**
     * Intersect the clipping path with the current path using the nonzero
     * winding rule (`W`). Per the spec, a path-painting operator must follow —
     * call [endPath] to clip without also painting the path, e.g.
     * `rectangle(...); clip(); endPath()`.
     */
    fun clip(): ContentStreamBuilder = op("W")

    /** Like [clip], but with the even-odd rule (`W*`). */
    fun clipEvenOdd(): ContentStreamBuilder = op("W*")

    /* ─── Images ─────────────────────────────────────────────────────────── */

    /**
     * Draw [image] into the rectangle ([x], [y]) → ([x]+[width], [y]+[height])
     * in user space ([x], [y] is the bottom-left corner). Wrapped in its own
     * `q … Q` so the image transform doesn't leak into later drawing.
     *
     * Only available through [PdfBuilder.page]; other contexts throw because
     * they don't register image resources.
     */
    fun drawImage(image: PdfImage, x: Double, y: Double, width: Double, height: Double): ContentStreamBuilder {
        save()
        // An image XObject is drawn in a 1×1 unit square at the origin; scale it
        // to the requested rectangle. (No Y-flip: top-first raster order is
        // handled by the writer's sample layout.)
        transform(width, 0.0, 0.0, height, x, y)
        name(imageResolver(image))
        op("Do")
        return restore()
    }

    /* ─── Text ───────────────────────────────────────────────────────────── */

    fun beginText(): ContentStreamBuilder = op("BT")
    fun endText(): ContentStreamBuilder = op("ET")
    fun setFont(font: StandardFont, size: Double): ContentStreamBuilder {
        name(fontResolver(font)); num(size); return op("Tf")
    }
    fun setLeading(leading: Double): ContentStreamBuilder { num(leading); return op("TL") }
    fun setCharSpacing(spacing: Double): ContentStreamBuilder { num(spacing); return op("Tc") }
    fun setWordSpacing(spacing: Double): ContentStreamBuilder { num(spacing); return op("Tw") }
    fun moveText(tx: Double, ty: Double): ContentStreamBuilder { num(tx); num(ty); return op("Td") }
    fun nextLine(): ContentStreamBuilder = op("T*")
    fun showText(text: String): ContentStreamBuilder { string(text); return op("Tj") }

    /**
     * Convenience: draw a single line of [text] in [font] at [size], with its
     * baseline at ([x], [y]). Wraps `BT/Tf/Td/Tj/ET`.
     */
    fun text(font: StandardFont, size: Double, x: Double, y: Double, text: String): ContentStreamBuilder {
        beginText(); setFont(font, size); moveText(x, y); showText(text); return endText()
    }

    /** Escape hatch: append literal content-stream source, followed by a newline. */
    fun raw(content: String): ContentStreamBuilder {
        out.append(content.encodeToByteArray()); return nl()
    }

    internal fun toByteArray(): ByteArray = out.toByteArray()

    /* ─── Token emission ─────────────────────────────────────────────────── */

    private fun num(d: Double) { out.appendAscii(PdfObjectWriter.formatReal(d)); sp() }
    private fun name(n: String) { PdfObjectWriter.writeObject(PdfName(n), out); sp() }
    private fun string(s: String) { PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(s)), out); sp() }
    private fun op(operator: String): ContentStreamBuilder { out.appendAscii(operator); return nl() }
    private fun sp() { out.append(' '.code.toByte()) }
    private fun nl(): ContentStreamBuilder { out.append('\n'.code.toByte()); return this }
}
