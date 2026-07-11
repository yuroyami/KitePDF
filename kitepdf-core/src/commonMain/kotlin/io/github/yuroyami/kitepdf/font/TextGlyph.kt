package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.render.PdfPath

/**
 * One glyph laid out from a `Tj` / `TJ` show-text byte string.
 *
 * The presence of [TextGlyph] lets the renderer walk a *code-unit-aware*
 * sequence rather than iterating bytes one at a time — necessary for Type 0
 * composite fonts where each glyph consumes 2 bytes (Identity-H) or other
 * variable-length CMap encodings.
 *
 * Simple fonts produce one [TextGlyph] per byte; CIDFonts produce one per
 * (1- or 2-byte) code unit consumed from the input.
 */
public data class TextGlyph(
    /** Byte offset in the input where this code unit started. */
    val byteOffset: Int,
    /** How many bytes this code unit consumed (1 for simple, 2 for Identity-H). */
    val byteCount: Int,
    /** Glyph index in the embedded font (TrueType / CFF / Type 1), or -1 if unresolved. */
    val gid: Int,
    /** Decoded unicode text for this glyph — empty if no mapping is known. */
    val text: String,
    /** Glyph advance width in font design units (1/1000 em for most fonts). */
    val advanceWidth: Double,
    /** Outline ready to draw (TTF/CFF/Type 1 already resolved), or null if no embedded font. */
    val outline: PdfPath?,
    /** True iff this code unit corresponds to ASCII space (0x20) — needed for Tw word spacing. */
    val isWordSpace: Boolean,
    /**
     * Positioning offset in font design units (GPOS mark attachment), applied to
     * this glyph's origin without affecting the pen advance. Scaled by the same
     * `fontSize/unitsPerEm` as the outline. Zero for normal glyphs.
     */
    val xOffset: Double = 0.0,
    val yOffset: Double = 0.0,
)
