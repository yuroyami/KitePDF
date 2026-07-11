package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.font.CffFont
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont

/**
 * A TrueType font loaded for *embedding* into a PDF the writer produces.
 *
 * This is the from-scratch writer's path to non-Latin / custom text. The whole
 * font program is embedded as a Type0 / CIDFontType2 composite font with
 * `Identity-H` encoding plus a `/ToUnicode` map, so the output renders and
 * extracts arbitrary Unicode (including CJK). Embedding the full program — no
 * subsetting yet — matches MuPDF's default `pdf_add_cid_font` behaviour.
 *
 * Load once and reuse the handle across pages and documents:
 * ```
 * val font = EmbeddedFont.load(myTtfBytes)
 * val pdf = PdfBuilder()
 *     .page { text(font, 24.0, 72.0, 700.0, "日本語 — Hello") }
 *     .build()
 * ```
 *
 * Scope: only TrueType (`glyf` outline) fonts are supported. OpenType/CFF
 * (`.otf`, sfnt tag "OTTO") and glyph subsetting are later milestones; [load]
 * rejects CFF fonts with a clear error rather than embedding something broken.
 */
public class EmbeddedFont private constructor(
    internal val fontBytes: ByteArray,
    internal val ttf: TrueTypeFont,
    /** The CFF program when this is an OpenType/CFF (`.otf`) font; null for TrueType (`glyf`). */
    internal val cff: CffFont?,
    /** PostScript name written as `/BaseFont` and `/FontName`. */
    public val postScriptName: String,
    /** When true, only the glyphs the document uses are embedded (a subset tag is added). */
    public val subset: Boolean,
) {

    /** True for OpenType/CFF (`.otf`) fonts (embedded as CIDFontType0); false for TrueType. */
    public val isCff: Boolean get() = cff != null

    /** Map a Unicode code point to a glyph id (0 = `.notdef` when unmapped). */
    internal fun glyphIdForCodePoint(codePoint: Int): Int = ttf.glyphIdForCodePoint(codePoint)

    public companion object {
        private const val SCALER_TTCF = 0x74746366L   // TrueType Collection

        /**
         * Parse [fontBytes] (a TrueType `.ttf` or OpenType/CFF `.otf` font) for
         * embedding. [name] overrides the `/BaseFont` name; when null it is read
         * from the font's `name` table (PostScript name), falling back to
         * `"KiteFont"`. When [subset] (default) only the glyphs the document uses
         * are embedded; pass false to embed the whole program.
         *
         * @throws UnsupportedOperationException for a TrueType Collection (`.ttc`).
         */
        public fun load(fontBytes: ByteArray, name: String? = null, subset: Boolean = true): EmbeddedFont {
            require(fontBytes.size >= 12) { "Not a font file: only ${fontBytes.size} bytes" }
            if (scaler(fontBytes) == SCALER_TTCF) throw UnsupportedOperationException(
                "TrueType Collections (.ttc) are not supported; extract a single .ttf/.otf first",
            )
            val ttf = TrueTypeFont.parse(fontBytes)
            val cff = ttf.rawTable("CFF ")?.let { CffFont.parse(it) }
            val resolved = (name ?: postScriptNameOf(ttf))?.let(::sanitizeName)?.takeIf { it.isNotEmpty() }
            return EmbeddedFont(fontBytes, ttf, cff, resolved ?: "KiteFont", subset)
        }

        private fun scaler(b: ByteArray): Long =
            ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)

        /** PostScript name (name-table nameID 6); prefers the Macintosh (ASCII) record. */
        private fun postScriptNameOf(ttf: TrueTypeFont): String? {
            val t = ttf.rawTable("name") ?: return null
            if (t.size < 6) return null
            fun u16(o: Int) = ((t[o].toInt() and 0xFF) shl 8) or (t[o + 1].toInt() and 0xFF)
            val count = u16(2)
            val storage = u16(4)
            var fallback: String? = null
            for (k in 0 until count) {
                val r = 6 + k * 12
                if (r + 12 > t.size) break
                if (u16(r + 6) != 6) continue            // nameID 6 = PostScript name
                val len = u16(r + 8)
                val start = storage + u16(r + 10)
                if (len <= 0 || start < 0 || start + len > t.size) continue
                val s = when (u16(r)) {                   // platformID
                    1 -> buildString {                    // Macintosh — single-byte ASCII
                        for (i in 0 until len) append((t[start + i].toInt() and 0xFF).toChar())
                    }
                    0, 3 -> buildString {                 // Unicode / Windows — UTF-16BE
                        var i = 0
                        while (i + 1 < len) {
                            append((((t[start + i].toInt() and 0xFF) shl 8) or (t[start + i + 1].toInt() and 0xFF)).toChar())
                            i += 2
                        }
                    }
                    else -> continue
                }
                if (u16(r) == 1) return s                 // Mac record is simplest — take it
                fallback = s
            }
            return fallback
        }

        /** Keep only bytes legal in a PDF name token (PostScript names are ASCII). */
        private fun sanitizeName(raw: String): String = buildString {
            for (c in raw) if (c.code in 0x21..0x7E && c !in "()<>[]{}/%#") append(c)
        }.take(63)
    }
}

/**
 * Accumulates the glyphs a document actually shows from one [EmbeddedFont], so
 * the embedder emits `/W` widths and a `/ToUnicode` map only for glyphs in use.
 */
internal class EmbeddedFontUsage {
    val usedGids = HashSet<Int>()

    /** First Unicode code point seen for each gid — drives the `/ToUnicode` map. */
    val gidToUnicode = HashMap<Int, Int>()

    fun record(gid: Int, codePoint: Int) {
        usedGids.add(gid)
        if (codePoint > 0) gidToUnicode.getOrPut(gid) { codePoint }
    }
}

/** Per-builder binding of an [EmbeddedFont] to its `/Resources` name and usage sink. */
internal class EmbeddedBinding(
    val resourceName: String,
    val font: EmbeddedFont,
    val usage: EmbeddedFontUsage,
)
