package io.github.yuroyami.kitepdf.writer

/**
 * One item of an outline that [PdfEditor.setOutline] writes (ISO 32000-1, 12.3.3; #921).
 *
 * The item goes to page [pageIndex] (0-based, in the order the editor has the pages), with [top]
 * the height on the page in default user space to scroll to, or to the whole page when [top] is
 * null; or to the named destination [namedDestination]; or nowhere when both are null. [open]
 * says whether its [children] show at first.
 */
public class PdfOutlineItem(
    public val title: String,
    public val pageIndex: Int? = null,
    public val top: Double? = null,
    public val namedDestination: String? = null,
    public val children: List<PdfOutlineItem> = emptyList(),
    public val open: Boolean = true,
) {
    init {
        require(pageIndex == null || namedDestination == null) { "an outline item goes to a page or to a named destination, not both" }
    }
}

/**
 * One range of page labels that [PdfEditor.setPageLabels] writes (ISO 32000-1, 12.4.2, Table 159;
 * #921): from page [firstPage] (0-based) to the next range, each page is labelled [prefix] and
 * then its number in [style], counting from [start]. A null [style] labels with the prefix alone.
 */
public class PdfPageLabel(
    public val firstPage: Int,
    public val style: Style? = Style.Decimal,
    public val prefix: String = "",
    public val start: Int = 1,
) {
    init {
        require(firstPage >= 0) { "a page index is 0 or more, not $firstPage" }
        require(start >= 1) { "a page label counts from 1 or more, not $start" }
    }

    /** The numbering styles of Table 159. */
    public enum class Style(internal val code: String) {
        /** 1, 2, 3 */
        Decimal("D"),

        /** I, II, III */
        UppercaseRoman("R"),

        /** i, ii, iii */
        LowercaseRoman("r"),

        /** A to Z, then AA to ZZ */
        UppercaseLetters("A"),

        /** a to z, then aa to zz */
        LowercaseLetters("a"),
    }
}
