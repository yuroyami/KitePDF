package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * An OpenType CFF font whose "é" is the seac of "e" and "acute" embeds with its accent, whole or
 * subset, though a CID-keyed CFF has no glyph names for seac to find them by (#902).
 */
@OptIn(ExperimentalEncodingApi::class)
class SeacEmbedTest {

    companion object {
        /** Built with fontTools' FontBuilder: boxes for e, acute, c, a and f, and eacute as "500 0 0 101 194 endchar". */
        val FONT: ByteArray by lazy { Base64.decode("T1RUTwAJAIAAAwAQQ0ZGILLEjtQAAAJIAAAAqU9TLzJBGUICAAABAAAAAGBjbWFwAlgBugAAAcgAAABgaGVhZC83OdQAAACcAAAANmhoZWEEsgGZAAAA1AAAACRobXR4DqYAAAAAAvQAAAAgbWF4cAAIUAAAAAD4AAAABm5hbWWkMk+qAAABYAAAAGZwb3N0AAMAAAAAAigAAAAgAAEAAAABAAAs1oPiXw889QADA+gAAAAA5xZ7HAAAAADnFnscADIAAAHCArwAAAADAAIAAAAAAAAAAQAAAyD/OAAAAfQAAABkAZAAAQAAAAAAAAAAAAAAAAAAAAgAAFAAAAgAAAADAdUBkAAFAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMAAAAAAAAAAAAAAAA/Pz8/AAAAIADpAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAQANgABAAAAAAABAAkAAAABAAAAAAACAAcACQADAAEECQABABIAEAADAAEECQACAA4AIktpdGUgU2VhY1JlZ3VsYXIASwBpAHQAZQAgAFMAZQBhAGMAUgBlAGcAdQBsAGEAcgAAAAAAAgAAAAMAAAAUAAMAAQAAABQABABMAAAADgAIAAIABgAgAGEAYwBmALQA6f//AAAAIABhAGMAZQC0AOn////n/6T/oQAA/07/GgABAAAAAAAAAAgAAAAAAAAAAQAGAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAQBAAEBAQlLaXRlU2VhYwABAQET+BsCvYv4VvlQBcMPi/c9EtIRAAEBAQpLaXRlIFNlYWMAAAAARgB9AM8ARABCAEcAAQAIAQENGSgwPEhUV/iIvRb4JPlQ/CQGDviIvRb4JPiI/CQGDviI9yr4uhX3XPcq+1wGDviIi4vw91YO+IjHFvfo+Fb76AYO+Ii9FvgG+HT8BgYO+IjvFvdc+VD7XAYO944OAAAAAfQAAAH0AAAB9AAAAfQAAAH0AAAB9AAAAfQAAAD6AAA=") }

        fun pdf(subset: Boolean): ByteArray {
            val font = EmbeddedFont.load(FONT, subset = subset)
            return PdfBuilder().page(width = 300.0, height = 200.0) { text(font, 48.0, 10.0, 100.0, "café") }.build()
        }
    }

    private fun bounds(path: KitePath): List<Double> {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (s in path.segments) when (s) {
            is KitePath.Segment.MoveTo -> { xs += s.x; ys += s.y }
            is KitePath.Segment.LineTo -> { xs += s.x; ys += s.y }
            is KitePath.Segment.CurveTo -> { xs += s.x3; ys += s.y3 }
            is KitePath.Segment.QuadTo -> { xs += s.x2; ys += s.y2 }
            KitePath.Segment.Close -> {}
        }
        return listOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    @Test
    fun the_accented_letter_keeps_its_base_and_its_accent() {
        for (subset in listOf(true, false)) {
            val doc = PdfDocument.open(pdf(subset))
            assertEquals("café", doc.pages[0].extractText().trim())
            val canvas = RecordingCanvas()
            doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
            val call = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().first()
            val e = call.glyphs.first { it.text == "é" }
            val outline = assertNotNull(e.outline, "subset = $subset: é has an outline")
            val scale = 1000.0 / call.unitsPerEm
            // fontTools draws it from (50, 0) to (450, 700): the box of e and the accent above it.
            assertEquals(listOf(50.0, 0.0, 450.0, 700.0), bounds(outline).map { kotlin.math.round(it * scale) }, "subset = $subset")
        }
    }
}
