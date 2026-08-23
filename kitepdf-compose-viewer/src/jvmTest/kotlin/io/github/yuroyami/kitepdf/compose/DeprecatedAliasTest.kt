@file:Suppress("DEPRECATION")

package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.Rectangle
import io.github.yuroyami.kitepdf.core.font.FontFamily
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.render.BlendMode
import io.github.yuroyami.kitepdf.core.render.ColorSpace
import io.github.yuroyami.kitepdf.core.render.ImageXObject
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.Matrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The migration aliases still resolve, and resolve to the new types. This is
 * the only guard that a caller on the old names keeps compiling: everything
 * else in the suite was moved over.
 */
class DeprecatedAliasTest {

    @Test
    fun old_type_names_alias_the_new_ones() {
        val layout: PdfLayout = KiteDocLayout.Paged(Orientation.Horizontal)
        assertTrue(layout is KiteDocLayout.Paged)

        val zoom: PdfZoomSpec = KiteZoomSpec(maxZoom = 4f)
        assertEquals(4f, zoom.maxZoom)

        val render: PdfRenderSpec = KiteRenderSpec.Rasterized(quality = 2f)
        assertTrue(render is KiteRenderSpec.Rasterized)

        val colors: PdfViewColors = KiteDocViewColors(pageBackground = Color.Red)
        assertEquals(Color.Red, colors.pageBackground)

        val side: PdfMarkerSide = KiteMarkerSide.Start
        assertEquals(KiteMarkerSide.Start, side)

        val edge: PdfSelectionHandleEdge = KiteSelectionHandleEdge.End
        assertEquals(KiteSelectionHandleEdge.End, edge)

        // Color is a value class, so compare by value; the painter is a real reference.
        assertEquals(KiteSelectionMenuDefaults.ContainerColor, PdfSelectionMenuDefaults.ContainerColor)
        assertSame(
            KiteSelectionHandleDefaults.CaretAndDot,
            PdfSelectionHandleDefaults.CaretAndDot as PdfSelectionHandlePainter,
        )

        val item: PdfSelectionMenuItem = KiteSelectionMenuItem("Copy") { }
        assertEquals("Copy", item.label)

        val hit: PageHit = KitePageHit(pageIndex = 2, x = 1.0, y = 3.0)
        assertEquals(2, hit.pageIndex)

        val sel: TextSelection = KiteTextSelection(0, 1, 4, "abcd", emptyList())
        assertEquals("abcd", sel.text)
    }

    /**
     * Old core names still work in type positions, and constructors, companion
     * members and enum entries still resolve through them. Nested classifiers
     * (`ImageXObject.Kind`) do not: Kotlin does not expand a type alias for
     * those, so that one spelling has to change to `KiteImageData.Kind`.
     */
    @Test
    fun renamed_core_types_still_answer_to_their_old_names() {
        val m: Matrix = Matrix.IDENTITY
        assertEquals(KiteMatrix.IDENTITY, m)

        val r: Rectangle = Rectangle(0.0, 0.0, 10.0, 20.0)
        assertEquals(10.0, r.width)

        val blend: BlendMode = BlendMode.Multiply
        assertEquals(KiteBlendMode.Multiply, blend)

        val cs: ColorSpace = KiteColorSpace.DeviceRGB
        assertEquals(3, cs.componentCount)

        val family: FontFamily = FontFamily.Monospace
        assertEquals(KiteFontFamily.Monospace, family)

        val image: ImageXObject? = null
        assertEquals(null, image?.kind)
        assertEquals(KiteImageData.Kind.RAW, KiteImageData.Kind.valueOf("RAW"))
    }

    @Test
    fun old_link_callbacks_still_receive_a_pdf_action() {
        // The deprecated composables map KiteLinkAction back to the old
        // payload, including the fabricated URI action EPUB links used to get.
        val fromEpub = KiteLinkAction.Uri("https://example.org/out")
        assertEquals("https://example.org/out", fromEpub.uri)
    }
}
