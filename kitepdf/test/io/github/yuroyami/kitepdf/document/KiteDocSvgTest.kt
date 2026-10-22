package io.github.yuroyami.kitepdf.document

import kotlin.test.Test
import kotlin.test.assertEquals

/** The umbrella sniffing an `.svg` file and opening it. */
class KiteDocSvgTest {

    private val svg = """
        <?xml version="1.0" encoding="UTF-8"?>
        <svg xmlns="http://www.w3.org/2000/svg" width="64" height="32">
          <circle cx="32" cy="16" r="10" fill="teal"/>
        </svg>
    """.trimIndent().encodeToByteArray()

    @Test
    fun an_svg_file_is_recognised() {
        assertEquals(KiteDocFormat.Svg, KiteDoc.formatOf(svg))
    }

    @Test
    fun an_svg_file_opens_as_one_page() {
        val doc = KiteDoc.open(svg)
        assertEquals(1, doc.pageCount)
        assertEquals(64.0, doc.pages.single().displayWidth)
    }

    @Test
    fun plain_text_is_still_no_format_at_all() {
        assertEquals(null, KiteDoc.formatOf("hello there".encodeToByteArray()))
    }
}
