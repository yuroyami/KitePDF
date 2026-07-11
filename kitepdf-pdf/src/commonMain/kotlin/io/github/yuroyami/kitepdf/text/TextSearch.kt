package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage

/**
 * T-30/T-81: text search over PDF pages, as a thin delegate to the shared
 * core walker on [io.github.yuroyami.kitepdf.core.KiteStructuredText] (the same
 * engine EPUB search uses). A hit is a [KiteSearchHit]: display-space quads
 * (one per line touched) plus the matched text and page index.
 */
public typealias PdfSearchHit = KiteSearchHit

/**
 * Find [needle] on this page. Matches may cross line boundaries inside a
 * block (a break reads as one space, or joins directly after a hyphenated
 * line with the hyphen dropped); they never cross block boundaries.
 *
 * Case-insensitive matching compares per-position (`regionMatches`), which
 * keeps indices aligned for any script; note the usual locale caveat that
 * a Turkish dotless ı will not match `I` under locale-free folding.
 *
 * Quads are in DISPLAY space: the y-down `[0, displayWidth] x
 * [0, displayHeight]` box with page rotation folded in.
 */
public fun PdfPage.search(needle: String, ignoreCase: Boolean = true): List<PdfSearchHit> =
    textContent().search(needle, ignoreCase, pageIndex = index)

/**
 * Find [needle] across the whole document, page by page. The result is a
 * lazy [Sequence], so a UI can surface incremental hits while later pages
 * are still being extracted. See [PdfPage.search] for matching rules.
 */
public fun PdfDocument.search(needle: String, ignoreCase: Boolean = true): Sequence<PdfSearchHit> =
    pages.asSequence().flatMap { it.search(needle, ignoreCase) }
