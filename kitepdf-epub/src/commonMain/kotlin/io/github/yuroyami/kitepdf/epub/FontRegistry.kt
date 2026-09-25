package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.CffFont
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import io.github.yuroyami.kitepdf.core.font.OpenTypeKern
import io.github.yuroyami.kitepdf.core.font.OpenTypeMarks
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import io.github.yuroyami.kitepdf.core.render.KitePath

/**
 * The parsed program behind one or more [EmbeddedFace]s: the SFNT tables, the
 * CFF outlines of an `.otf`, and its kerning, substitution and mark tables.
 * This is what a font costs in memory (its bytes, plus one cached path per
 * glyph drawn), so a book parses each font file once and every `@font-face`
 * that names the file shares the result (#224).
 *
 * Scope: TrueType (`glyf`) AND OpenType-CFF (`.otf`) programs. Both are SFNT
 * containers, so `cmap`/`hmtx`/`head` (glyph id + advances + unitsPerEm) always
 * come from the core [TrueTypeFont]; only the OUTLINE source differs: `glyf` for
 * TrueType, a [CffFont] parsed from the `CFF ` table for OpenType. (Advances still
 * come from `hmtx` even for CFF, so the CFF-charstring-width gap doesn't apply to
 * SFNT-wrapped `.otf`.) WOFF 1.0 and WOFF2 wrappers are unwrapped to bare SFNT
 * before parsing ([Woff] / [Woff2]).
 */
internal class FontProgram(
    val ttf: TrueTypeFont,
    val cff: CffFont? = null,
    val kern: OpenTypeKern? = null,
    val gsub: OpenTypeGsub? = null,
    val marks: OpenTypeMarks? = null,
) {
    companion object {
        /**
         * Parse [bytes] as a TrueType (`glyf`) or OpenType-CFF (`.otf`) program; null
         * if the SFNT itself won't parse. For an `.otf` (no `glyf` table) the `CFF `
         * table is parsed for outlines; metrics still come from the SFNT `hmtx`.
         */
        fun parse(bytes: ByteArray): FontProgram? {
            // WOFF is a wrapped SFNT: unwrap first (1.0 = zlib tables, 2.0 = brotli
            // stream + glyf/loca transform).
            val sfnt = when {
                Woff.isWoff(bytes) -> Woff.toSfnt(bytes) ?: return null
                Woff2.isWoff2(bytes) -> Woff2.toSfnt(bytes) ?: return null
                else -> bytes
            }
            val ttf = runCatching { TrueTypeFont.parse(sfnt) }.getOrNull() ?: return null
            val cff = if (ttf.rawTable("glyf") == null) {
                (ttf.rawTable("CFF ") ?: ttf.rawTable("CFF2"))?.let { runCatching { CffFont.parse(it) }.getOrNull() }
            } else null
            val gpos = ttf.rawTable("GPOS")
            return FontProgram(
                ttf, cff,
                kern = OpenTypeKern.from(ttf.rawTable("kern"), gpos),
                gsub = OpenTypeGsub.from(ttf.rawTable("GSUB")),
                marks = OpenTypeMarks.from(gpos),
            )
        }
    }
}

/**
 * One embedded `@font-face`: a family name and style over a [FontProgram] it
 * may share with other faces. Provides the per-glyph data the layout needs to
 * draw real outlines: glyph id (cmap), outline (in font units), and advance
 * normalised to 1/1000 em (matching the width convention every backend now
 * uses after the advance-scale fix).
 */
internal class EmbeddedFace(
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    private val program: FontProgram,
) {
    private val ttf: TrueTypeFont get() = program.ttf
    private val cff: CffFont? get() = program.cff
    private val kern: OpenTypeKern? get() = program.kern
    private val gsub: OpenTypeGsub? get() = program.gsub
    private val marks: OpenTypeMarks? get() = program.marks

    val unitsPerEm: Int get() = ttf.unitsPerEm

    /** Raw advance of [gid] in font design units (unitsPerEm), for mark math. */
    fun advanceRaw(gid: Int): Int = ttf.advanceWidth(gid)

    /** GPOS mark-to-base attachment offset (font units) placing [mark] on [base], or null. */
    fun markOffset(base: Int, mark: Int): Pair<Double, Double>? = marks?.offset(base, mark)

    /** GPOS mark-to-mark offset stacking [mark] on the mark [below] it, or null. */
    fun markStackOffset(below: Int, mark: Int): Pair<Double, Double>? = marks?.stackOffset(below, mark)

    /** GPOS mark-to-ligature offset placing [mark] on [component] of [lig], or null. */
    fun markLigatureOffset(lig: Int, mark: Int, component: Int): Pair<Double, Double>? =
        marks?.ligatureOffset(lig, mark, component)

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
    fun outline(gid: Int): KitePath? = ttf.outlinePath(gid) ?: cff?.outline(gid)

    /** Advance of [gid] in 1/1000 em. */
    fun advance1000(gid: Int): Int = to1000(ttf.advanceWidth(gid))

    /** Vertical advance of [gid] in 1/1000 em, or null without `vhea`/`vmtx`. */
    fun advanceHeight1000(gid: Int): Int? = ttf.advanceHeight(gid)?.let { to1000(it) }

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

    /** This registry plus [extra], which loses every tie because it is matched last. */
    fun with(extra: List<EmbeddedFace>): FontRegistry =
        if (extra.isEmpty()) this else FontRegistry(faces + extra)

    companion object {
        val EMPTY = FontRegistry(emptyList())

        /** One face over a freshly parsed [FontProgram]; null when [bytes] will not parse. */
        fun face(family: String, bold: Boolean, italic: Boolean, bytes: ByteArray): EmbeddedFace? =
            FontProgram.parse(bytes)?.let { EmbeddedFace(family, bold, italic, it) }
    }
}
