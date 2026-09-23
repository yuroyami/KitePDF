package io.github.yuroyami.kitepdf.core.font

/**
 * Reads the program in a `/FontFile3` stream. Since PDF 1.6 the stream may hold a whole
 * OpenType font instead of a bare CFF program (ISO 32000-1, Table 126). The stream's
 * `/Subtype` is not trusted: the first bytes decide.
 */
internal object FontFile3 {

    /** True when [bytes] start with an sfnt header: TrueType outlines, `OTTO` or `true`. */
    fun isSfnt(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val tag = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return tag == 0x00010000 || tag == 0x4F54544F || tag == 0x74727565
    }

    /**
     * The CFF program in [bytes]: the bytes themselves, or the `CFF ` table of an OpenType
     * font, whose units per em default to its `head` value. Null when an OpenType font has
     * no `CFF ` table or the program does not parse.
     */
    fun cff(bytes: ByteArray): CffFont? = runCatching {
        if (!isSfnt(bytes)) return@runCatching CffFont.parse(bytes)
        val sfnt = TrueTypeFont.parse(bytes)
        sfnt.rawTable("CFF ")?.let { CffFont.parse(it, sfnt.unitsPerEm) }
    }.getOrNull()

    /** The font in [bytes] when they hold an OpenType font with TrueType (`glyf`) outlines, else null. */
    fun trueType(bytes: ByteArray): TrueTypeFont? = runCatching {
        if (isSfnt(bytes)) TrueTypeFont.parse(bytes).takeIf { it.hasTable("glyf") } else null
    }.getOrNull()
}
