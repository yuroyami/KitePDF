package io.github.yuroyami.kitepdf.epub

/**
 * One shared String per character, for glyph text. Layout makes one glyph per
 * character of the book, and a private one-character String per glyph was a
 * fifth of the memory a laid-out chapter kept (#220). Strings are immutable,
 * so sharing changes nothing a caller can observe.
 *
 * Races on the table are benign: two threads that miss the same slot both
 * store an equal String, and a reader that sees null makes its own.
 */
internal object CharText {
    private val table = arrayOfNulls<String>(0x10000)

    /** The text of [cp]. A character outside the BMP is rare, so it gets a String of its own (#319). */
    fun of(cp: Int): String {
        if (cp < 0x10000) return table[cp] ?: cp.toChar().toString().also { table[cp] = it }
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}
