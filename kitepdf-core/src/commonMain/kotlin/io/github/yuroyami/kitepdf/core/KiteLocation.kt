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

    /** The chapter this position lives in. Resolving needs only this chapter. */
    public abstract val chapter: Int

    /**
     * A page index in a PDF. Pages are fixed, so the index IS the position.
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
     *   characters of reading order. Re-flowing moves the words to another
     *   page, but not to another offset. Hyphenation can shift it by a few
     *   characters; this is a reading position, not a pointer.
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
