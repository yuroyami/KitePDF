package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary

/**
 * Catalog `/MarkInfo` dictionary (ISO 32000-1 §14.7.1).
 *
 * Tells consumers whether the document carries tagging information used
 * for accessibility (screen-reader logical reading order), structured
 * extraction, and reflow. Tagged-PDF/A files set [marked] = true.
 *
 * `null` from [PdfDocument.markInfo] means the document doesn't carry a
 * /MarkInfo dict at all — i.e. it's not tagged.
 */
public data class PdfMarkInfo(
    /** True if the document conforms to Tagged PDF (logical structure tree present). */
    val marked: Boolean = false,
    /** True if structure elements carry user property attributes (PDF 1.6+). */
    val userProperties: Boolean = false,
    /** True if some structure elements have unreliable mappings to content (PDF 1.6+). */
    val suspects: Boolean = false,
) {
    public companion object {
        internal fun parse(dict: PdfDictionary?): PdfMarkInfo? {
            if (dict == null) return null
            fun b(key: String) = (dict[key] as? PdfBoolean)?.value ?: false
            return PdfMarkInfo(
                marked = b("Marked"),
                userProperties = b("UserProperties"),
                suspects = b("Suspects"),
            )
        }
    }
}
