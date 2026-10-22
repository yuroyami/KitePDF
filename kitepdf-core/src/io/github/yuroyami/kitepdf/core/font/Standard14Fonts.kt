package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.compression.Inflate
import kotlin.io.encoding.Base64

/**
 * The outlines of the standard 14 fonts, for a PDF that names one and does not embed it.
 * ISO 32000-1, 9.6.2.2 says that a reader shall have these fonts, or their metrics and
 * suitable substitutes. The programs are the URW base 35 fonts that MuPDF draws them
 * with: Nimbus Sans for Helvetica, Nimbus Roman for Times, Nimbus Mono PS for Courier,
 * Standard Symbols PS for Symbol and Dingbats for ZapfDingbats. They have the metrics of
 * the Adobe fonts, so they agree with [Standard14Widths].
 *
 * Each program is parsed once per process, on first use, and shared: [CffFont] guards
 * its own caches.
 */
internal object Standard14Fonts {

    private val files: Map<String, String> = mapOf(
        "Helvetica" to "NimbusSans-Regular",
        "Helvetica-Bold" to "NimbusSans-Bold",
        "Helvetica-Oblique" to "NimbusSans-Italic",
        "Helvetica-BoldOblique" to "NimbusSans-BoldItalic",
        "Times-Roman" to "NimbusRoman-Regular",
        "Times-Bold" to "NimbusRoman-Bold",
        "Times-Italic" to "NimbusRoman-Italic",
        "Times-BoldItalic" to "NimbusRoman-BoldItalic",
        "Courier" to "NimbusMonoPS-Regular",
        "Courier-Bold" to "NimbusMonoPS-Bold",
        "Courier-Oblique" to "NimbusMonoPS-Italic",
        "Courier-BoldOblique" to "NimbusMonoPS-BoldItalic",
        "Symbol" to "StandardSymbolsPS",
        "ZapfDingbats" to "Dingbats",
    )

    private val programs: Map<String, Lazy<CffFont?>> = files.values.distinct().associateWith { file -> lazy { load(file) } }

    /**
     * The program for [baseFont], which may be a standard name or one that stands for it,
     * such as `Arial,Bold` for Helvetica-Bold. Null for any other font.
     */
    fun program(baseFont: String): CffFont? {
        val file = files[Standard14Widths.canonicalName(baseFont)] ?: return null
        return programs[file]?.value
    }

    private fun load(file: String): CffFont? = runCatching {
        val chunks = Standard14FontData.chunks(file) ?: return null
        CffFont.parse(Inflate.decode(Base64.decode(chunks.joinToString(""))))
    }.getOrNull()
}
