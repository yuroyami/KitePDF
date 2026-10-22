package io.github.yuroyami.kitepdf.epub

/**
 * Whether pages reflow or keep the size the author set: `rendition:layout`. [ROLL], of EPUB 3.4,
 * keeps each chapter's size too and shows the chapters as one strip with no gap, as a webtoon or
 * a scroll reads (#506).
 */
public enum class EpubLayout { REFLOWABLE, PRE_PAGINATED, ROLL }

/**
 * When a reader shows two pages side by side: `rendition:spread`. The deprecated value
 * `portrait` reads as [BOTH], as EPUB 3.3 asks.
 */
public enum class EpubSpread { AUTO, NONE, LANDSCAPE, BOTH }

/** Which way the author asks the reader to hold the device: `rendition:orientation`. */
public enum class EpubOrientation { AUTO, LANDSCAPE, PORTRAIT }

/** Whether the author asks for pages or for scrolling: `rendition:flow`. */
public enum class EpubFlow { AUTO, PAGINATED, SCROLLED_CONTINUOUS, SCROLLED_DOC }

/**
 * The side of a two-page spread that the first page of a chapter asks for: `page-spread-left`,
 * `page-spread-right`, or `rendition:page-spread-center` for a page shown alone.
 */
public enum class EpubPageSpread { LEFT, RIGHT, CENTER }

/**
 * How a book, or one chapter of it, asks to be shown: the rendition properties of EPUB 3.3
 * (the Packages specification, section 4). The book's values come from the package metadata.
 * A chapter's values are the ones its spine entry sets, and the book's values for the rest.
 * A value that is not set, or that this library does not know, reads as the default.
 */
public class EpubRendition internal constructor(
    public val layout: EpubLayout = EpubLayout.REFLOWABLE,
    public val spread: EpubSpread = EpubSpread.AUTO,
    public val orientation: EpubOrientation = EpubOrientation.AUTO,
    public val flow: EpubFlow = EpubFlow.AUTO,
    /** The side of a spread that the chapter's first page asks for, or null. Always null for a book. */
    public val pageSpread: EpubPageSpread? = null,
) {
    /**
     * True for a roll: [EpubLayout.ROLL], or a pre-paginated book whose flow is
     * `scrolled-continuous`, which EPUB Reading Systems 3.4 asks to read the same way (#506).
     */
    public val isRoll: Boolean
        get() = layout == EpubLayout.ROLL || (layout == EpubLayout.PRE_PAGINATED && flow == EpubFlow.SCROLLED_CONTINUOUS)

    override fun toString(): String = "EpubRendition($layout, $spread, $orientation, $flow, $pageSpread)"

    internal companion object {
        val DEFAULT = EpubRendition()

        /** The book's rendition from its package metadata values, by property name without the prefix. */
        fun ofBook(values: Map<String, String>): EpubRendition = EpubRendition(
            layout = when (values["layout"]?.lowercase()) {
                "pre-paginated" -> EpubLayout.PRE_PAGINATED
                "roll" -> EpubLayout.ROLL
                else -> EpubLayout.REFLOWABLE
            },
            spread = spreadOf(values["spread"]) ?: EpubSpread.AUTO,
            orientation = orientationOf(values["orientation"]) ?: EpubOrientation.AUTO,
            flow = flowOf(values["flow"]) ?: EpubFlow.AUTO,
        )

        /**
         * [book] with the properties of one spine entry, a space-separated list, over it. Of two
         * overrides of one property, the first counts (EPUB Reading Systems 3.3, 5.5.1, #502). A
         * roll ignores the overrides of its layout (EPUB Reading Systems 3.4, #506).
         */
        fun ofChapter(book: EpubRendition, properties: String?): EpubRendition {
            var layout: EpubLayout? = null
            var spread: EpubSpread? = null
            var orientation: EpubOrientation? = null
            var flow: EpubFlow? = null
            var pageSpread: EpubPageSpread? = null
            for (property in properties.orEmpty().split(' ', '\t', '\n', '\r', '\u000C')) {
                val name = property.lowercase()
                when {
                    name == "rendition:layout-pre-paginated" -> layout = layout ?: EpubLayout.PRE_PAGINATED
                    name == "rendition:layout-reflowable" -> layout = layout ?: EpubLayout.REFLOWABLE
                    name.startsWith("rendition:spread-") -> spread = spread ?: spreadOf(name.removePrefix("rendition:spread-"))
                    name.startsWith("rendition:orientation-") ->
                        orientation = orientation ?: orientationOf(name.removePrefix("rendition:orientation-"))
                    name.startsWith("rendition:flow-") -> flow = flow ?: flowOf(name.removePrefix("rendition:flow-"))
                    // EPUB 3.3 drops the prefix from the two sides, and keeps it for the centre.
                    name == "page-spread-left" || name == "rendition:page-spread-left" -> pageSpread = pageSpread ?: EpubPageSpread.LEFT
                    name == "page-spread-right" || name == "rendition:page-spread-right" -> pageSpread = pageSpread ?: EpubPageSpread.RIGHT
                    name == "rendition:page-spread-center" || name == "page-spread-center" -> pageSpread = pageSpread ?: EpubPageSpread.CENTER
                }
            }
            return EpubRendition(
                if (book.isRoll) book.layout else layout ?: book.layout, spread ?: book.spread, orientation ?: book.orientation, flow ?: book.flow, pageSpread,
            )
        }

        private fun spreadOf(value: String?): EpubSpread? = when (value?.lowercase()) {
            "none" -> EpubSpread.NONE
            "landscape" -> EpubSpread.LANDSCAPE
            "portrait", "both" -> EpubSpread.BOTH
            "auto" -> EpubSpread.AUTO
            else -> null
        }

        private fun orientationOf(value: String?): EpubOrientation? = when (value?.lowercase()) {
            "landscape" -> EpubOrientation.LANDSCAPE
            "portrait" -> EpubOrientation.PORTRAIT
            "auto" -> EpubOrientation.AUTO
            else -> null
        }

        private fun flowOf(value: String?): EpubFlow? = when (value?.lowercase()) {
            "paginated" -> EpubFlow.PAGINATED
            "scrolled-continuous" -> EpubFlow.SCROLLED_CONTINUOUS
            "scrolled-doc" -> EpubFlow.SCROLLED_DOC
            "auto" -> EpubFlow.AUTO
            else -> null
        }
    }
}
