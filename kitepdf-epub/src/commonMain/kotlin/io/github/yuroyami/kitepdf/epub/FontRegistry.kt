package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.font.CffFont
import io.github.yuroyami.kitepdf.font.OpenTypeGsub
import io.github.yuroyami.kitepdf.font.OpenTypeKern
import io.github.yuroyami.kitepdf.font.OpenTypeMarks
import io.github.yuroyami.kitepdf.font.TrueTypeFont
import io.github.yuroyami.kitepdf.render.PdfPath

/**
 * One embedded `@font-face`, wrapping a parsed core [TrueTypeFont]. Provides the
 * per-glyph data the layout needs to draw real outlines: glyph id (cmap), outline
 * (in font units), and advance normalised to 1/1000 em (matching the width
 * convention every backend now uses after the advance-scale fix).
 *
 * Scope: TrueType (`glyf`) AND OpenType-CFF (`.otf`) programs. Both are SFNT
 * containers, so `cmap`/`hmtx`/`head` (glyph id + advances + unitsPerEm) always
 * come from the core [TrueTypeFont]; only the OUTLINE source differs — `glyf` for
 * TrueType, a [CffFont] parsed from the `CFF ` table for OpenType. (Advances still
 * come from `hmtx` even for CFF, so the CFF-charstring-width gap doesn't apply to
 * SFNT-wrapped `.otf`.) WOFF 1.0 and WOFF2 wrappers are unwrapped to bare SFNT
 * before parsing ([Woff] / [Woff2]).
 */
internal class EmbeddedFace(
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    private val ttf: TrueTypeFont,
    private val cff: CffFont? = null,
    private val kern: OpenTypeKern? = null,
    private val gsub: OpenTypeGsub? = null,
    private val marks: OpenTypeMarks? = null,
) {
    val unitsPerEm: Int get() = ttf.unitsPerEm

    /** Raw advance of [gid] in font design units (unitsPerEm), for mark math. */
    fun advanceRaw(gid: Int): Int = ttf.advanceWidth(gid)

    /** GPOS mark-to-base attachment offset (font units) placing [mark] on [base], or null. */
    fun markOffset(base: Int, mark: Int): Pair<Double, Double>? = marks?.offset(base, mark)

    /** True when the font has Arabic contextual-joining GSUB features. */
    val hasArabicJoining: Boolean get() = gsub?.hasArabicJoining == true

    fun gidFor(codePoint: Int): Int = ttf.glyphIdForCodePoint(codePoint)

    /** GSUB single substitution for [feature] applied to [gid], or [gid] unchanged. */
    fun substSingle(feature: String, gid: Int): Int = gsub?.single(feature, gid) ?: gid

    /** GSUB ligature rules whose first component is [firstGid] under [feature] (longest first). */
    fun ligatures(feature: String, firstGid: Int): List<OpenTypeGsub.LigRule>? =
        gsub?.ligatures(feature, firstGid)

    // TrueType `glyf` outline when present; else the OpenType `CFF ` outline. The
    // glyph id is the shared SFNT glyph order (== the CFF CharStrings index in .otf).
    fun outline(gid: Int): PdfPath? = ttf.outlinePath(gid) ?: cff?.outline(gid)

    /** Advance of [gid] in 1/1000 em. */
    fun advance1000(gid: Int): Int = to1000(ttf.advanceWidth(gid))

    /** Kerning adjustment between [left] and [right] in 1/1000 em (0 if none). */
    fun kern1000(left: Int, right: Int): Int {
        val k = kern ?: return 0
        return to1000(k.between(left, right))
    }

    private fun to1000(v: Int) = if (unitsPerEm == 1000) v else (v.toDouble() * 1000.0 / unitsPerEm).toInt()
}

/**
 * Resolves a run's CSS `font-family` name + weight/style to an [EmbeddedFace], or
 * null to fall back to the Standard-14 substitute path. Matching prefers an exact
 * style, then relaxes weight, then any face of the family.
 */
internal class FontRegistry(private val faces: List<EmbeddedFace>) {

    fun match(family: String?, bold: Boolean, italic: Boolean): EmbeddedFace? {
        if (family == null || faces.isEmpty()) return null
        val fam = family.lowercase()
        return faces.firstOrNull { it.family == fam && it.bold == bold && it.italic == italic }
            ?: faces.firstOrNull { it.family == fam && it.italic == italic }
            ?: faces.firstOrNull { it.family == fam }
    }

    /**
     * Per-glyph fallback when the matched face has no glyph for [codePoint]
     * (its cmap returns gid 0, `.notdef`): any other registered face that
     * actually carries the codepoint, preferring the requested style. Null
     * means no embedded face can draw it and the caller should use the
     * generic system-font path instead of painting tofu.
     */
    fun fallbackFor(codePoint: Int, bold: Boolean, italic: Boolean): EmbeddedFace? =
        faces.firstOrNull { it.bold == bold && it.italic == italic && it.gidFor(codePoint) != 0 }
            ?: faces.firstOrNull { it.gidFor(codePoint) != 0 }

    /** A face whose cmap covers every char of [text], or null (ruby readings). */
    fun coveringAll(text: String): EmbeddedFace? =
        faces.firstOrNull { f -> text.all { f.gidFor(it.code) != 0 } }

    val isEmpty: Boolean get() = faces.isEmpty()

    companion object {
        val EMPTY = FontRegistry(emptyList())

        /**
         * Parse [bytes] as a TrueType (`glyf`) or OpenType-CFF (`.otf`) face; null
         * if the SFNT itself won't parse. For an `.otf` (no `glyf` table) the `CFF `
         * table is parsed for outlines; metrics still come from the SFNT `hmtx`.
         */
        fun face(family: String, bold: Boolean, italic: Boolean, bytes: ByteArray): EmbeddedFace? {
            // WOFF is a wrapped SFNT: unwrap first (1.0 = zlib tables, 2.0 = brotli
            // stream + glyf/loca transform).
            val sfnt = when {
                Woff.isWoff(bytes) -> Woff.toSfnt(bytes) ?: return null
                Woff2.isWoff2(bytes) -> Woff2.toSfnt(bytes) ?: return null
                else -> bytes
            }
            val ttf = runCatching { TrueTypeFont.parse(sfnt) }.getOrNull() ?: return null
            val cff = if (ttf.rawTable("glyf") == null) {
                ttf.rawTable("CFF ")?.let { runCatching { CffFont.parse(it) }.getOrNull() }
            } else null
            val gpos = ttf.rawTable("GPOS")
            val kern = OpenTypeKern.from(ttf.rawTable("kern"), gpos)
            val gsub = OpenTypeGsub.from(ttf.rawTable("GSUB"))
            val marks = OpenTypeMarks.from(gpos)
            return EmbeddedFace(family, bold, italic, ttf, cff, kern, gsub, marks)
        }
    }
}
