package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.render.KitePath

/**
 * [glyphs], a run of host-font text in [spec], each with its outline from the bundled face that
 * stands in for [spec]'s generic family ([standardFaceOutline]), for a canvas whose host has no
 * face of that family, as a browser has none (#593). The outlines are in units of 1000 to the
 * em, and the advances stay the document's. Null when a glyph that draws something has no
 * outline there, so that the run keeps the host's own text.
 */
public fun standardFaceGlyphs(glyphs: List<TextGlyph>, spec: FontSpec): List<TextGlyph>? = glyphs.map { glyph ->
    if (drawsNothing(glyph.text)) return@map glyph.copy(outline = null)
    glyph.copy(outline = standardFaceOutline(glyph.text, spec) ?: return null)
}

/** True for text that leaves no ink: white space, and format characters such as a soft hyphen or a zero-width joiner. */
private fun drawsNothing(text: String): Boolean = text.all { it.isWhitespace() || it.category == CharCategory.FORMAT }

/**
 * The outline of the one character [text] in the bundled face that stands in for [spec]'s
 * generic family: Nimbus Roman for serif, Nimbus Sans for sans serif and Nimbus Mono PS for
 * monospace, in [spec]'s weight and slant. These are the faces of the standard 14 fonts (#197),
 * whose widths an EPUB lays its text out with ([Standard14Widths]), so a glyph drawn from them
 * fills the advance the layout gave it. A canvas whose host has no face of the family draws
 * from them, as a browser must, where Skia knows one face only (#593).
 *
 * The outline is in glyph space, 1000 units to the em with y up. Null for text of more or
 * fewer than one character, for white space, and for a character the face has no glyph for.
 */
internal fun standardFaceOutline(text: String, spec: FontSpec): KitePath? {
    if (text.isEmpty()) return null
    val high = text[0]
    val codePoint = if (high.isHighSurrogate() && text.length == 2 && text[1].isLowSurrogate()) {
        0x10000 + ((high.code - 0xD800) shl 10) + (text[1].code - 0xDC00)
    } else {
        if (text.length != 1) return null
        high.code
    }
    if (high.isWhitespace()) return null
    val face = StandardFaces.faceOf(spec)
    val program = Standard14Fonts.program(face) ?: return null
    val gid = StandardFaces.glyphIds(face)[codePoint] ?: return null
    return program.glyphSpaceOutline(gid)?.takeIf { !it.isEmpty() }
}

/** Which standard face stands in for a generic family, and its glyphs by character. */
private object StandardFaces {

    // Indexed by (bold ? 2 : 0) or (italic ? 1 : 0), as in the EPUB's font metrics.
    private val SERIF = arrayOf("Times-Roman", "Times-Italic", "Times-Bold", "Times-BoldItalic")
    private val SANS = arrayOf("Helvetica", "Helvetica-Oblique", "Helvetica-Bold", "Helvetica-BoldOblique")
    private val MONO = arrayOf("Courier", "Courier-Oblique", "Courier-Bold", "Courier-BoldOblique")

    private val glyphIds: Map<String, Lazy<Map<Int, Int>>> =
        (SERIF + SANS + MONO).associateWith { face -> lazy { readGlyphIds(face) } }

    fun faceOf(spec: FontSpec): String {
        val faces = when (spec.family) {
            KiteFontFamily.Serif -> SERIF
            KiteFontFamily.SansSerif -> SANS
            KiteFontFamily.Monospace -> MONO
        }
        return faces[(if (spec.bold) 2 else 0) or (if (spec.italic) 1 else 0)]
    }

    fun glyphIds(face: String): Map<Int, Int> = glyphIds[face]?.value ?: emptyMap()

    /** Each character of [face]'s program to its glyph, through the Adobe Glyph List; the first glyph of a character wins. */
    private fun readGlyphIds(face: String): Map<Int, Int> {
        val program = Standard14Fonts.program(face) ?: return emptyMap()
        val out = HashMap<Int, Int>(program.glyphNames.size)
        for ((gid, name) in program.glyphNames.withIndex()) {
            if (gid == 0 || name == null) continue
            val codePoint = GlyphList.unicodeFor(name) ?: continue
            if (codePoint !in out) out[codePoint] = gid
        }
        return out
    }
}
