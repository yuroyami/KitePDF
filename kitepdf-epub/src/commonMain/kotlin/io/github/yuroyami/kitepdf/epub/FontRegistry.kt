package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.font.TrueTypeFont
import io.github.yuroyami.kitepdf.render.PdfPath

/**
 * One embedded `@font-face`, wrapping a parsed core [TrueTypeFont]. Provides the
 * per-glyph data the layout needs to draw real outlines: glyph id (cmap), outline
 * (in font units), and advance normalised to 1/1000 em (matching the width
 * convention every backend now uses after the advance-scale fix).
 *
 * Scope: TrueType (`glyf`) programs, which carry `hmtx` advances. OpenType-CFF
 * and WOFF-compressed fonts are a follow-up (CFF advances aren't exposed by the
 * core engine; WOFF needs per-table inflate/brotli).
 */
internal class EmbeddedFace(
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    private val ttf: TrueTypeFont,
) {
    val unitsPerEm: Int get() = ttf.unitsPerEm

    fun gidFor(codePoint: Int): Int = ttf.glyphIdForCodePoint(codePoint)

    fun outline(gid: Int): PdfPath? = ttf.outlinePath(gid)

    /** Advance of [gid] in 1/1000 em. */
    fun advance1000(gid: Int): Int {
        val a = ttf.advanceWidth(gid)
        return if (unitsPerEm == 1000) a else (a.toDouble() * 1000.0 / unitsPerEm).toInt()
    }
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

    val isEmpty: Boolean get() = faces.isEmpty()

    companion object {
        val EMPTY = FontRegistry(emptyList())

        /** Parse [bytes] as a TrueType face; null if it isn't parseable `glyf`-based TrueType. */
        fun face(family: String, bold: Boolean, italic: Boolean, bytes: ByteArray): EmbeddedFace? {
            val ttf = runCatching { TrueTypeFont.parse(bytes) }.getOrNull() ?: return null
            return EmbeddedFace(family, bold, italic, ttf)
        }
    }
}
