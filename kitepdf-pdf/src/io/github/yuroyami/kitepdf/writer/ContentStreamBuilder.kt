package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfString

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

    /** The size of the font that the last `Tf` set, which [showGlyphs] scales a text rise by. */
    private var currentSize = 0.0

    /* ─── Graphics state ─────────────────────────────────────────────────── */

    /**
     * `q`. The text font is graphics state, which `q` saves and `Q` restores (ISO 32000-1, 8.4.2
     * and 9.3.1), so the font that [showText] encodes for is saved with it (#733). A font that
     * [raw] content sets is outside what the builder tracks.
     */
    public fun save(): ContentStreamBuilder {
        savedEmbedded.add(currentEmbedded)
        savedSizes.add(currentSize)
        return op("q")
    }

    /** `Q`, back to the font that the matching [save] saw; with no matching save, the font stays. */
    public fun restore(): ContentStreamBuilder {
        if (savedEmbedded.isNotEmpty()) currentEmbedded = savedEmbedded.removeAt(savedEmbedded.lastIndex)
        if (savedSizes.isNotEmpty()) currentSize = savedSizes.removeAt(savedSizes.lastIndex)
        return op("Q")
    }

    private val savedEmbedded = ArrayList<EmbeddedBinding?>()
    private val savedSizes = ArrayList<Double>()

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
    public fun setFillCmyk(c: Double, m: Double, y: Double, k: Double): ContentStreamBuilder { num(c); num(m); num(y); num(k); return op("k") }
    public fun setStrokeCmyk(c: Double, m: Double, y: Double, k: Double): ContentStreamBuilder { num(c); num(m); num(y); num(k); return op("K") }

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
     * winding rule (`W`). Per the spec, a path-painting operator must follow.
     * Call [endPath] to clip without also painting the path, e.g.
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
        currentSize = size
        name(fontResolver(font)); num(size); return op("Tf")
    }

    /**
     * Select an embedded custom [font] at [size]. Subsequent [showText] calls
     * encode their text as Identity-H 2-byte glyph codes for this font, one glyph
     * per character through the font's `cmap`, in the order of the string. The
     * builder does not shape text: no GSUB substitution, no mark positioning and
     * no bidi reordering, so Arabic letters do not join, marks of Devanagari or
     * Thai do not move into place, and right-to-left text runs left to right.
     * Scripts without those needs, CJK among them, render as written (#750). For
     * the others, [showGlyphs] writes glyphs that a shaper placed, and the
     * `shapedText` and `paragraph` calls of the `kitepdf` module shape the text
     * first (#945). The font is registered with the owning [PdfBuilder] on first use.
     */
    public fun setFont(font: EmbeddedFont, size: Double): ContentStreamBuilder {
        val binding = embeddedResolver(font)
        currentEmbedded = binding
        currentSize = size
        name(binding.resourceName); num(size); return op("Tf")
    }

    /**
     * `TJ` in the current embedded font: each glyph of [glyphs] at its own place, for text that
     * a shaper has already laid out (#945), such as the lines of `KiteShaper` in `kitepdf-epub`
     * or the `shapedText` and `paragraph` calls of the `kitepdf` module.
     *
     * Each [PdfGlyph.x] counts from where the text position stands, and [PdfGlyph.y] from the
     * baseline, both in thousandths of an em, so the builder writes the moves between glyph
     * widths as `TJ` adjustments and a glyph off the baseline with `Ts`. The text of each glyph
     * goes into the font's `/ToUnicode` map. With [actualText], the run is a `/Span` marked-content
     * sequence whose `/ActualText` is that string (ISO 32000-1, 14.9.4), so text extraction gives
     * the logical string of a line whose glyph order no reader can undo.
     *
     * With [whenAmbiguous], the `/Span` is written only when the `/ToUnicode` map cannot give the
     * glyphs' text back: when one glyph id stands for two texts, as a Devanagari vowel sign does
     * in the clusters of two consonants, and the map holds only one of them.
     *
     * @throws IllegalStateException when the current font is not an [EmbeddedFont].
     */
    public fun showGlyphs(glyphs: List<PdfGlyph>, actualText: String? = null, whenAmbiguous: Boolean = false): ContentStreamBuilder {
        val emb = currentEmbedded ?: throw IllegalStateException("showGlyphs needs an EmbeddedFont set with setFont")
        val upem = emb.font.ttf.unitsPerEm.toDouble()
        val span = actualText != null && (!whenAmbiguous || ambiguous(emb.usage, glyphs))
        if (span) {
            name("Span")
            out.appendAscii("<</ActualText ")
            PdfObjectWriter.writeObject(PdfString(PdfText.encodeTextString(actualText)), out)
            out.appendAscii(">> ")
            op("BDC")
        }
        var pen = 0.0
        var rise = 0.0
        val run = StringBuilder()
        var open = false
        fun flush() {
            if (!open) return
            out.appendAscii(run.append("] TJ\n").toString())
            run.clear(); open = false
        }
        for (g in glyphs) {
            if (g.y != rise) {
                flush()
                num(g.y * currentSize / 1000.0); op("Ts")
                rise = g.y
            }
            if (!open) { run.append('['); open = true }
            val gap = g.x - pen
            // TJ subtracts its numbers from the pen, in thousandths of an em.
            if (kotlin.math.abs(gap) > 0.0005) run.append(PdfObjectWriter.formatReal(-gap)).append(' ')
            run.append('<').append(HEX[(g.gid ushr 12) and 0xF]).append(HEX[(g.gid ushr 8) and 0xF])
                .append(HEX[(g.gid ushr 4) and 0xF]).append(HEX[g.gid and 0xF]).append("> ")
            emb.usage.record(g.gid, g.text)
            // The pen moves by the width the font's /W gives the glyph, rounded as the embedder writes it.
            pen = g.x + kotlin.math.round(emb.font.ttf.advanceWidth(g.gid) * 1000.0 / upem)
        }
        flush()
        if (rise != 0.0) { num(0.0); op("Ts") }
        if (span) op("EMC")
        return this
    }

    /** Whether a glyph of [glyphs] stands for other text than the map of [usage], or an earlier glyph of the run, gives its id. */
    private fun ambiguous(usage: EmbeddedFontUsage, glyphs: List<PdfGlyph>): Boolean {
        val run = HashMap<Int, String>()
        for (g in glyphs) {
            val known = usage.gidToUnicode[g.gid] ?: run[g.gid]
            if (known != null && known != g.text) return true
            if (g.text.isNotEmpty()) run.getOrPut(g.gid) { g.text }
        }
        return false
    }

    /** As [showGlyphs], in its own text object: [glyphs] in [font] at [size], with the line starting at ([x], [y]). */
    public fun glyphs(
        font: EmbeddedFont, size: Double, x: Double, y: Double, glyphs: List<PdfGlyph>, actualText: String? = null, whenAmbiguous: Boolean = false,
    ): ContentStreamBuilder {
        beginText(); setFont(font, size); moveText(x, y); showGlyphs(glyphs, actualText, whenAmbiguous); return endText()
    }
    public fun setLeading(leading: Double): ContentStreamBuilder { num(leading); return op("TL") }
    public fun setCharSpacing(spacing: Double): ContentStreamBuilder { num(spacing); return op("Tc") }
    public fun setWordSpacing(spacing: Double): ContentStreamBuilder { num(spacing); return op("Tw") }
    public fun moveText(tx: Double, ty: Double): ContentStreamBuilder { num(tx); num(ty); return op("Td") }
    public fun nextLine(): ContentStreamBuilder = op("T*")

    /** `Tj` in the current font: WinAnsi bytes for a standard font, one glyph a character for an embedded one, unshaped (see [setFont]). */
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

    /** As [text], but with an embedded custom [font] (Identity-H encoded), with no shaping: see [setFont]. */
    public fun text(font: EmbeddedFont, size: Double, x: Double, y: Double, text: String): ContentStreamBuilder {
        beginText(); setFont(font, size); moveText(x, y); showText(text); return endText()
    }

    /**
     * Escape hatch: append literal content-stream source, followed by a newline. A `Tf`, `q` or `Q`
     * in it is outside what the builder tracks, so [showText] keeps encoding for the font it set.
     */
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
    private val HEX = "0123456789ABCDEF"
    private fun sp() { out.append(' '.code.toByte()) }
    private fun nl(): ContentStreamBuilder { out.append('\n'.code.toByte()); return this }
}

/**
 * One glyph that [ContentStreamBuilder.showGlyphs] draws: glyph id [gid] of an [EmbeddedFont], at
 * [x] from the start of the run and [y] above the baseline, both in thousandths of an em, for
 * the characters [text]. The later glyphs of a cluster, which stand for no character of their
 * own, have an empty [text].
 */
public class PdfGlyph(
    public val gid: Int,
    public val x: Double,
    public val y: Double = 0.0,
    public val text: String = "",
)
