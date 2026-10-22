package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Immutable
import io.github.yuroyami.kitepdf.core.xml.KiteXmlError
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The words that [KiteDocView] and its widgets give a screen reader. The defaults are English;
 * provide a translated set through [LocalKiteViewerStrings]:
 *
 * ```kotlin
 * CompositionLocalProvider(
 *     LocalKiteViewerStrings provides KiteViewerStrings(
 *         page = { number, count -> if (count == null) "Seite $number" else "Seite $number von $count" },
 *         previousPage = "Vorherige Seite",
 *         nextPage = "Nächste Seite",
 *     ),
 * ) {
 *     KiteDocView(state)
 * }
 * ```
 *
 * @param page the name of a page from its number, starting at 1, and the page count. The count
 *   is null while a book still lays out and does not know it.
 * @param previousPage the name of the button that turns back.
 * @param nextPage the name of the button that turns forward.
 * @param highlightColor the name of a colour of the selection menu from its place, starting at 1,
 *   and the count of colours.
 * @param formField the name of the input of a form field that has no tooltip and no name.
 * @param link the name of a link that has no text on the page and no address.
 * @param openLinkQuestion the question the viewer asks before it opens an address outside the
 *   document, such as a web page (#519).
 * @param openLink the button that opens that address.
 * @param cancel the button that closes the question and opens nothing.
 * @param markupErrors the notice on the first page of a chapter whose markup is not well-formed
 *   XML, from the first error and the count of errors (#517).
 */
@Immutable
public class KiteViewerStrings(
    public val page: (number: Int, count: Int?) -> String = { number, count ->
        if (count == null) "Page $number" else "Page $number of $count"
    },
    public val previousPage: String = "Previous page",
    public val nextPage: String = "Next page",
    public val highlightColor: (number: Int, count: Int) -> String = { number, count -> "Highlight colour $number of $count" },
    public val formField: String = "Form field",
    public val link: String = "Link",
    public val openLinkQuestion: String = "Open this link?",
    public val openLink: String = "Open",
    public val cancel: String = "Cancel",
    public val markupErrors: (first: KiteXmlError, count: Int) -> String = { first, count ->
        "This chapter is not well-formed XML. Line ${first.line}: ${first.message}." +
            if (count > 1) " ${count - 1} more errors follow." else ""
    },
)

/** The [KiteViewerStrings] of the viewer and the widgets below it. */
public val LocalKiteViewerStrings: ProvidableCompositionLocal<KiteViewerStrings> = staticCompositionLocalOf { KiteViewerStrings() }
