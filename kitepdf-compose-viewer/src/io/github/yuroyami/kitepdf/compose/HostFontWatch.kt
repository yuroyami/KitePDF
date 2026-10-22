package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/**
 * Tells when a font lands for the text that a page drew through Compose's text (#595).
 *
 * A browser has no face for every script. Compose downloads a face for the code points that no
 * face it has covers, and once that face lands it marks each paragraph that met them as stale, so
 * its own text draws again. A page drawn before then holds a box for each such character. The
 * canvas keeps the code points of its runs, one set for each face the runs asked for, and
 * [Log.watch] measures each set as one paragraph, which Compose marks as it marks the runs. The
 * page is then known to need drawing again, with one paragraph kept for each face, not one for
 * each run.
 */
internal class HostFontWatch private constructor(private val paragraphs: List<ParagraphIntrinsics>) {

    /** True once a font has landed. It reads snapshot state, so a draw or a snapshot flow that reads it hears the change. */
    val stale: Boolean get() = paragraphs.any { it.hasStaleResolvedFonts }

    /** The code points that a canvas drew through Compose's text, by the face they asked for. */
    class Log {
        private val faces = LinkedHashMap<TextStyle, LinkedHashSet<String>>()

        /** Notes the code points of [text], drawn in [style]. Only the face matters, so size and colour are left out. */
        fun note(style: TextStyle, text: String) {
            val face = TextStyle(
                fontFamily = style.fontFamily,
                fontWeight = style.fontWeight,
                fontStyle = style.fontStyle,
                localeList = style.localeList,
            )
            val seen = faces.getOrPut(face) { LinkedHashSet() }
            var i = 0
            while (i < text.length) {
                val n = if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
                if (!text[i].isWhitespace()) seen += text.substring(i, i + n)
                i += n
            }
        }

        /**
         * A watch on the code points noted so far, measured by [measurer] on the thread the text
         * drew on, or null when no text went through Compose's text.
         */
        fun watch(measurer: TextMeasurer): HostFontWatch? {
            val noted = faces.filterValues { it.isNotEmpty() }
            if (noted.isEmpty()) return null
            return HostFontWatch(
                noted.map { (face, codePoints) ->
                    measurer.measure(text = codePoints.joinToString(""), style = face).multiParagraph.intrinsics
                },
            )
        }
    }
}
