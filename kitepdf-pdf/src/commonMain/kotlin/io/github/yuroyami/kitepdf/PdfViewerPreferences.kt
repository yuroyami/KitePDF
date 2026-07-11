package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt

/**
 * Catalog `/ViewerPreferences` dictionary (ISO 32000-1 §12.2).
 *
 * Hints from the document author about how to present and print the file.
 * All fields are optional in the spec and have a documented default; we
 * surface those defaults here so callers never have to check for `null`.
 *
 * "Hint" is the operative word — viewers may ignore any or all of these.
 * KitePDF doesn't enforce them; it just exposes them.
 */
public data class PdfViewerPreferences(
    /** Hide the toolbar. Default false. */
    val hideToolbar: Boolean = false,
    /** Hide the menubar. Default false. */
    val hideMenubar: Boolean = false,
    /** Hide window chrome (scrollbars, status, panels). Default false. */
    val hideWindowUI: Boolean = false,
    /** Resize the window to the first page on open. Default false. */
    val fitWindow: Boolean = false,
    /** Centre the window on the screen. Default false. */
    val centerWindow: Boolean = false,
    /** Use /Info /Title (or /dc:title) as the window-title text. Default false; PDF 1.4. */
    val displayDocTitle: Boolean = false,
    /** Which [PageMode] to use when leaving FullScreen. Default [PageMode.UseNone]. */
    val nonFullScreenPageMode: PageMode = PageMode.UseNone,
    /** Reading direction. Default [ReadingDirection.LeftToRight]. */
    val direction: ReadingDirection = ReadingDirection.LeftToRight,
    /** Which page box to display when viewing. Default [PageBoxName.CropBox]. */
    val viewArea: PageBoxName = PageBoxName.CropBox,
    /** Box to clip the rendered page to when viewing. Default [PageBoxName.CropBox]. */
    val viewClip: PageBoxName = PageBoxName.CropBox,
    /** Page box used when printing. Default [PageBoxName.CropBox]. */
    val printArea: PageBoxName = PageBoxName.CropBox,
    /** Box to clip the printed page to. Default [PageBoxName.CropBox]. */
    val printClip: PageBoxName = PageBoxName.CropBox,
    /** Print scaling preference — PDF 1.6+. Default [PrintScaling.AppDefault]. */
    val printScaling: PrintScaling = PrintScaling.AppDefault,
    /** Duplex preference — PDF 1.7+. Default [Duplex.Simplex]. */
    val duplex: Duplex = Duplex.Simplex,
    /** Pick the paper tray by PDF page size — PDF 1.7. Default false. */
    val pickTrayByPdfSize: Boolean = false,
    /** Default number of copies when printing — PDF 1.7. Default 1. */
    val numCopies: Int = 1,
    /**
     * Default page ranges to print as `[firstA lastA firstB lastB …]` (one-based,
     * inclusive). Empty when /PrintPageRange is absent — PDF 1.7.
     */
    val printPageRange: List<IntRange> = emptyList(),
) {

    public enum class ReadingDirection {
        LeftToRight, RightToLeft;
        public companion object {
            public fun fromName(n: String?): ReadingDirection = if (n == "R2L") RightToLeft else LeftToRight
        }
    }

    public enum class PageBoxName {
        MediaBox, CropBox, BleedBox, TrimBox, ArtBox;
        public companion object {
            public fun fromName(n: String?): PageBoxName = when (n) {
                "MediaBox" -> MediaBox
                "BleedBox" -> BleedBox
                "TrimBox" -> TrimBox
                "ArtBox" -> ArtBox
                else -> CropBox
            }
        }
    }

    public enum class PrintScaling {
        /** No automatic scaling — print at 100%. */
        None,
        /** Use the viewer's default. */
        AppDefault;
        public companion object {
            public fun fromName(n: String?): PrintScaling = if (n == "None") None else AppDefault
        }
    }

    public enum class Duplex {
        Simplex, DuplexFlipShortEdge, DuplexFlipLongEdge;
        public companion object {
            public fun fromName(n: String?): Duplex = when (n) {
                "DuplexFlipShortEdge" -> DuplexFlipShortEdge
                "DuplexFlipLongEdge" -> DuplexFlipLongEdge
                else -> Simplex
            }
        }
    }

    public companion object {
        /** Wholly-default preferences — used when the catalog has no /ViewerPreferences. */
        public val DEFAULT: PdfViewerPreferences = PdfViewerPreferences()

        internal fun parse(dict: PdfDictionary, refs: IndirectResolver): PdfViewerPreferences {
            fun bool(key: String, default: Boolean = false): Boolean =
                (dict[key] as? PdfBoolean)?.value ?: default

            val range = (dict.getArray("PrintPageRange", refs))?.let { arr ->
                val out = mutableListOf<IntRange>()
                var i = 0
                while (i + 1 < arr.size) {
                    val a = (arr[i] as? PdfInt)?.value?.toInt()
                    val b = (arr[i + 1] as? PdfInt)?.value?.toInt()
                    if (a != null && b != null && a <= b) out += a..b
                    i += 2
                }
                out
            } ?: emptyList()

            return PdfViewerPreferences(
                hideToolbar = bool("HideToolbar"),
                hideMenubar = bool("HideMenubar"),
                hideWindowUI = bool("HideWindowUI"),
                fitWindow = bool("FitWindow"),
                centerWindow = bool("CenterWindow"),
                displayDocTitle = bool("DisplayDocTitle"),
                nonFullScreenPageMode = PageMode.fromName(dict.getName("NonFullScreenPageMode")),
                direction = ReadingDirection.fromName(dict.getName("Direction")),
                viewArea = PageBoxName.fromName(dict.getName("ViewArea")),
                viewClip = PageBoxName.fromName(dict.getName("ViewClip")),
                printArea = PageBoxName.fromName(dict.getName("PrintArea")),
                printClip = PageBoxName.fromName(dict.getName("PrintClip")),
                printScaling = PrintScaling.fromName(dict.getName("PrintScaling")),
                duplex = Duplex.fromName(dict.getName("Duplex")),
                pickTrayByPdfSize = bool("PickTrayByPDFSize"),
                numCopies = dict.getInt("NumCopies")?.toInt()?.coerceAtLeast(1) ?: 1,
                printPageRange = range,
            )
        }
    }
}
