package io.github.yuroyami.kitepdf.core

/**
 * Where a page sits in the CURRENT layout: chapter, then page inside it.
 *
 * A document is a list of chapters, and each chapter is a list of pages. A PDF
 * is one chapter; an EPUB is one chapter per spine item. Chapters exist because
 * a reflowable book can lay out one of them without the others, so a reader can
 * open at chapter 20 without paginating chapters 0 to 19 first.
 *
 * This is coordinates, not identity. Change the font size and the same words
 * are at a different [KiteLocation]. To remember a reading position across a
 * re-flow, save a [KiteBookmark] instead.
 */
public data class KiteLocation(
    val chapter: Int,
    val page: Int,
) : Comparable<KiteLocation> {

    init {
        require(chapter >= 0) { "chapter must be >= 0 (was $chapter)" }
        require(page >= 0) { "page must be >= 0 (was $page)" }
    }

    /** Reading order: earlier chapter first, then earlier page. */
    override fun compareTo(other: KiteLocation): Int =
        if (chapter != other.chapter) chapter.compareTo(other.chapter) else page.compareTo(other.page)

    override fun toString(): String = "$chapter:$page"

    public companion object {
        /** The first page of the first chapter. */
        public val START: KiteLocation = KiteLocation(0, 0)
    }
}

/**
 * A reading position that survives a re-flow. Save this when the reader leaves,
 * hand it back when they return, and they land where they were even if the font
 * size, page size or margins changed in between.
 *
 * Resolve one with [KiteDocument.locate]. Each handler has its own subtype
 * because "the same place" means something different per format: a PDF page
 * index never moves, while a paragraph in a book does.
 */
public sealed class KiteBookmark {

    /** The chapter this position lives in. Resolving a [Flow] bookmark needs only this chapter. */
    public abstract val chapter: Int

    /**
     * A page index in a PDF. Pages are fixed, so the index IS the position.
     * A reflowable book can resolve one too, but it lays out every chapter
     * before the page to count them. A book's position is a [Flow] bookmark.
     */
    public data class Page(val pageIndex: Int) : KiteBookmark() {
        init { require(pageIndex >= 0) { "pageIndex must be >= 0 (was $pageIndex)" } }
        override val chapter: Int get() = 0
    }

    /**
     * A place inside one reflowable chapter.
     *
     * @param chapter the spine item, zero-based.
     * @param charOffset how far into the chapter's text the position is, in
     *   characters (code points) of reading order, not glyphs. Re-flowing moves
     *   the words to another page, but not to another offset: a new font, a
     *   ligature or an added hyphen does not change it. It resolves to the page
     *   that holds that character, so it is a reading position, not a pointer.
     * @param fragment an element id (`<h2 id="part-two">`), when the position
     *   came from a link or a table-of-contents entry. It wins over
     *   [charOffset] when it resolves.
     */
    public data class Flow(
        override val chapter: Int,
        val charOffset: Int = 0,
        val fragment: String? = null,
    ) : KiteBookmark() {
        init {
            require(chapter >= 0) { "chapter must be >= 0 (was $chapter)" }
            require(charOffset >= 0) { "charOffset must be >= 0 (was $charOffset)" }
        }
    }
}

/**
 * A stretch of a reflowable book's text that survives a reflow, such as the words a reader
 * highlighted (#944). [start] is the first character and [end] the one just after the last, as
 * [KiteBookmark.Flow] offsets count them, so a new font, page size or margin moves the words to
 * other pages but not to other offsets. [KiteDocument.locate] turns a range into the quads of
 * the current layout, across a page break and across chapters.
 *
 * [text] is the text of the range, and [prefix] and [suffix] a few characters on each side, as
 * the W3C Web Annotation `TextQuoteSelector` keeps them. When the book's text changed, so that
 * the offsets no longer hold [text], they find it again, or the range is reported lost; it never
 * lands on other words.
 *
 * Save a range with [encode] and read it back with [decode].
 */
public data class KiteTextRange(
    val start: KiteBookmark.Flow,
    val end: KiteBookmark.Flow,
    val text: String,
    val prefix: String = "",
    val suffix: String = "",
) {
    /**
     * The range as one line of text, `kiterange:1;` followed by the chapters, the offsets and the
     * three texts, each percent-encoded, so it can be stored in a database or a file.
     */
    public fun encode(): String =
        "kiterange:1;${start.chapter};${start.charOffset};${end.chapter};${end.charOffset};" +
            "${escape(text)};${escape(prefix)};${escape(suffix)}"

    public companion object {
        /** The range that [encode] wrote, or null for text it did not write. */
        public fun decode(encoded: String): KiteTextRange? {
            if (!encoded.startsWith("kiterange:1;")) return null
            val parts = encoded.removePrefix("kiterange:1;").split(';')
            if (parts.size != 7) return null
            val numbers = parts.take(4).map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return KiteTextRange(
                KiteBookmark.Flow(numbers[0], numbers[1]), KiteBookmark.Flow(numbers[2], numbers[3]),
                unescape(parts[4]) ?: return null, unescape(parts[5]) ?: return null, unescape(parts[6]) ?: return null,
            )
        }

        private const val HEX = "0123456789ABCDEF"

        private fun escape(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A || c == '-'.code || c == '_'.code || c == '.'.code) append(c.toChar())
                else append('%').append(HEX[c ushr 4]).append(HEX[c and 0xF])
            }
        }

        private fun unescape(s: String): String? {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 3 > s.length) return null
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out += v.toByte(); i += 3
                } else if (c.code < 0x80) {
                    out += c.code.toByte(); i++
                } else return null
            }
            return out.toByteArray().decodeToString()
        }
    }
}

/**
 * Where a [KiteTextRange] is in the current layout, as [KiteDocument.locate] finds it: one
 * [KiteSearchHit] for each page it touches, in reading order. [range] is where its text is now:
 * the range itself, or, when the book's text changed, the place its quote was found again, in
 * which case [repaired] is true. Store that [range] to skip the search next time.
 */
public class KiteTextRangeHits(
    public val range: KiteTextRange,
    public val hits: List<KiteSearchHit>,
    public val repaired: Boolean,
)
