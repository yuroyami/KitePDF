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

    fun of(ch: Char): String {
        val i = ch.code
        return table[i] ?: ch.toString().also { table[i] = it }
    }
}
