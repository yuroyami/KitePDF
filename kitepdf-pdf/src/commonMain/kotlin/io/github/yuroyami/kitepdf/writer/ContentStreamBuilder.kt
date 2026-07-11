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
public class ContentStreamBuilder internal constructor(
    private val fontResolver: (StandardFont) -> String,
    private val imageResolver: (PdfImage) -> String = {
        throw UnsupportedOperationException(
            "drawImage() is only available via PdfBuilder.page { }; this context doesn't register image resources.",
        )
    },
    private val embeddedResolver: (EmbeddedFont) -> EmbeddedBinding = {
        throw UnsupportedOperationException(
            "Embedded fonts are only available via PdfBuilder.page { }; this context doesn't register font resources.",
        )
    },
) {
    private val out = ByteArrayBuilder(128)

    // When the current font is an embedded one, show strings are encoded as
    // Identity-H 2-byte glyph codes instead of single-byte text. Null = a
    // standard-14 font is selected (the default single-byte path).
    private var currentEmbedded: EmbeddedBinding? = null

    /* ─── Graphics state ─────────────────────────────────────────────────── */

    public fun save(): ContentStreamBuilder = op("q")
    public fun restore(): ContentStreamBuilder = op("Q")

    /** Concatenate [a b c d e f] onto the current transformation matrix (`cm`). */
    public fun transform(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double): ContentStreamBuilder {
        num(a); num(b); num(c); num(d); num(e); num(f); return op("cm")
    }

    public fun setLineWidth(w: Double): ContentStreamBuilder { num(w); return op("w") }

    /* ─── Colour ─────────────────────────────────────────────────────────── */

    public fun setFillRgb(r: Double, g: Double, b: Double): ContentStreamBuilder { num(r); num(g); num(b); return op("rg") }
    public fun setStrokeRgb(r: Double, g: Double, b: Double): ContentStreamBuilder { num(r); num(g); num(b); return op("RG") }
    public fun setFillGray(g: Double): ContentStreamBuilder { num(g); return op("g") }
    public fun setStrokeGray(g: Double): ContentStreamBuilder { num(g); return op("G") }

    /* ─── Paths ──────────────────────────────────────────────────────────── */

    public fun moveTo(x: Double, y: Double): ContentStreamBuilder { num(x); num(y); return op("m") }
    public fun lineTo(x: Double, y: Double): ContentStreamBuilder { num(x); num(y); return op("l") }
    public fun rectangle(x: Double, y: Double, w: Double, h: Double): ContentStreamBuilder {
        num(x); num(y); num(w); num(h); return op("re")
    }
    public fun closePath(): ContentStreamBuilder = op("h")
    public fun stroke(): ContentStreamBuilder = op("S")
    public fun fill(): ContentStreamBuilder = op("f")
    public fun fillAndStroke(): ContentStreamBuilder = op("B")
    public fun endPath(): ContentStreamBuilder = op("n")

    /* ─── Clipping ───────────────────────────────────────────────────────── */

    /**
     * Intersect the clipping path with the current path using the nonzero
     * winding rule (`W`). Per the spec, a path-painting operator must follow —
     * call [endPath] to clip without also painting the path, e.g.
     * `rectangle(...); clip(); endPath()`.
     */
    public fun clip(): ContentStreamBuilder = op("W")

    /** Like [clip], but with the even-odd rule (`W*`). */
    public fun clipEvenOdd(): ContentStreamBuilder = op("W*")

    /* ─── Images ─────────────────────────────────────────────────────────── */

    /**
     * Draw [image] into the rectangle ([x], [y]) → ([x]+[width], [y]+[height])
     * in user space ([x], [y] is the bottom-left corner). Wrapped in its own
     * `q … Q` so the image transform doesn't leak into later drawing.
     *
     * Only available through [PdfBuilder.page]; other contexts throw because
     * they don't register image resources.
     */
    public fun drawImage(image: PdfImage, x: Double, y: Double, width: Double, height: Double): ContentStreamBuilder {
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

    public fun beginText(): ContentStreamBuilder = op("BT")
    public fun endText(): ContentStreamBuilder = op("ET")
    public fun setFont(font: StandardFont, size: Double): ContentStreamBuilder {
        currentEmbedded = null
        name(fontResolver(font)); num(size); return op("Tf")
    }

    /**
     * Select an embedded custom [font] at [size]. Subsequent [showText] calls
     * encode their text as Identity-H 2-byte glyph codes for this font, so any
     * Unicode the font covers (CJK included) renders. The font is registered
     * with the owning [PdfBuilder] on first use.
     */
    public fun setFont(font: EmbeddedFont, size: Double): ContentStreamBuilder {
        val binding = embeddedResolver(font)
        currentEmbedded = binding
        name(binding.resourceName); num(size); return op("Tf")
    }
    public fun setLeading(leading: Double): ContentStreamBuilder { num(leading); return op("TL") }
    public fun setCharSpacing(spacing: Double): ContentStreamBuilder { num(spacing); return op("Tc") }
    public fun setWordSpacing(spacing: Double): ContentStreamBuilder { num(spacing); return op("Tw") }
    public fun moveText(tx: Double, ty: Double): ContentStreamBuilder { num(tx); num(ty); return op("Td") }
    public fun nextLine(): ContentStreamBuilder = op("T*")
    public fun showText(text: String): ContentStreamBuilder {
        val emb = currentEmbedded
        if (emb != null) embeddedString(emb, text) else string(text)
        return op("Tj")
    }

    /**
     * Convenience: draw a single line of [text] in [font] at [size], with its
     * baseline at ([x], [y]). Wraps `BT/Tf/Td/Tj/ET`.
     */
    public fun text(font: StandardFont, size: Double, x: Double, y: Double, text: String): ContentStreamBuilder {
        beginText(); setFont(font, size); moveText(x, y); showText(text); return endText()
    }

    /** As [text], but with an embedded custom [font] (Identity-H encoded). */
    public fun text(font: EmbeddedFont, size: Double, x: Double, y: Double, text: String): ContentStreamBuilder {
        beginText(); setFont(font, size); moveText(x, y); showText(text); return endText()
    }

    /** Escape hatch: append literal content-stream source, followed by a newline. */
    public fun raw(content: String): ContentStreamBuilder {
        out.append(content.encodeToByteArray()); return nl()
    }

    internal fun toByteArray(): ByteArray = out.toByteArray()

    /* ─── Token emission ─────────────────────────────────────────────────── */

    private fun num(d: Double) { out.appendAscii(PdfObjectWriter.formatReal(d)); sp() }
    private fun name(n: String) { PdfObjectWriter.writeObject(PdfName(n), out); sp() }
    private fun string(s: String) { PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(s)), out); sp() }

    /**
     * Emit [text] as a show string for the embedded [binding]: each Unicode code
     * point becomes its 2-byte big-endian glyph id (the Identity-H code), and the
     * glyph is recorded so the builder can emit its width and ToUnicode mapping.
     */
    private fun embeddedString(binding: EmbeddedBinding, text: String) {
        val bytes = ByteArrayBuilder(text.length * 2)
        forEachCodePoint(text) { cp ->
            val gid = binding.font.glyphIdForCodePoint(cp)
            binding.usage.record(gid, cp)
            bytes.append((gid ushr 8).toByte())
            bytes.append((gid and 0xFF).toByte())
        }
        PdfObjectWriter.writeObject(PdfString(bytes.toByteArray()), out); sp()
    }

    /** Iterate Unicode code points, combining surrogate pairs into one value. */
    private inline fun forEachCodePoint(s: String, action: (Int) -> Unit) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                action(0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00))
                i += 2
            } else {
                action(c.code)
                i++
            }
        }
    }
    private fun op(operator: String): ContentStreamBuilder { out.appendAscii(operator); return nl() }
    private fun sp() { out.append(' '.code.toByte()) }
    private fun nl(): ContentStreamBuilder { out.append('\n'.code.toByte()); return this }
}
