package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the catalog-level /PageMode + /PageLayout + /ViewerPreferences
 * hints. These are all simple name/boolean accessors on the catalog.
 */
class PageDisplayTest {

    @Test
    fun default_page_mode_is_use_none() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertEquals(PageMode.UseNone, doc.pageMode)
        assertEquals(PageLayout.SinglePage, doc.pageLayout)
    }

    @Test
    fun explicit_page_mode_and_layout() {
        val doc = KitePDF.open(
            buildPdfWithDisplayHints(
                pageMode = "UseOutlines",
                pageLayout = "TwoColumnLeft",
            ),
        )
        assertEquals(PageMode.UseOutlines, doc.pageMode)
        assertEquals(PageLayout.TwoColumnLeft, doc.pageLayout)
    }

    @Test
    fun unknown_page_mode_falls_through_to_other() {
        val doc = KitePDF.open(buildPdfWithDisplayHints(pageMode = "CustomSplash"))
        assertEquals(PageMode.Other, doc.pageMode)
    }

    @Test
    fun viewer_preferences_default_when_absent() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertEquals(PdfViewerPreferences.DEFAULT, doc.viewerPreferences)
        assertEquals(false, doc.viewerPreferences.hideToolbar)
        assertEquals(PdfViewerPreferences.PrintScaling.AppDefault, doc.viewerPreferences.printScaling)
        assertEquals(1, doc.viewerPreferences.numCopies)
    }

    @Test
    fun viewer_preferences_parse_full_dict() {
        val doc = KitePDF.open(
            buildPdfWithDisplayHints(
                pageMode = "FullScreen",
                viewerPrefs = """
                    /HideToolbar true /HideMenubar true /HideWindowUI false
                    /FitWindow true /CenterWindow true /DisplayDocTitle true
                    /NonFullScreenPageMode /UseThumbs /Direction /R2L
                    /ViewArea /MediaBox /ViewClip /BleedBox
                    /PrintArea /TrimBox /PrintClip /ArtBox
                    /PrintScaling /None /Duplex /DuplexFlipLongEdge
                    /PickTrayByPDFSize true /NumCopies 3
                    /PrintPageRange [1 4 10 12]
                """.trimIndent(),
            ),
        )
        val v = doc.viewerPreferences
        assertEquals(true, v.hideToolbar)
        assertEquals(true, v.hideMenubar)
        assertEquals(false, v.hideWindowUI)
        assertEquals(true, v.fitWindow)
        assertEquals(true, v.centerWindow)
        assertEquals(true, v.displayDocTitle)
        assertEquals(PageMode.UseThumbs, v.nonFullScreenPageMode)
        assertEquals(PdfViewerPreferences.ReadingDirection.RightToLeft, v.direction)
        assertEquals(PdfViewerPreferences.PageBoxName.MediaBox, v.viewArea)
        assertEquals(PdfViewerPreferences.PageBoxName.BleedBox, v.viewClip)
        assertEquals(PdfViewerPreferences.PageBoxName.TrimBox, v.printArea)
        assertEquals(PdfViewerPreferences.PageBoxName.ArtBox, v.printClip)
        assertEquals(PdfViewerPreferences.PrintScaling.None, v.printScaling)
        assertEquals(PdfViewerPreferences.Duplex.DuplexFlipLongEdge, v.duplex)
        assertEquals(true, v.pickTrayByPdfSize)
        assertEquals(3, v.numCopies)
        assertEquals(listOf(1..4, 10..12), v.printPageRange)
    }

    /* ─── Builder ─────────────────────────────────────────────────────────── */

    private fun buildPdfWithDisplayHints(
        pageMode: String? = null,
        pageLayout: String? = null,
        viewerPrefs: String? = null,
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.7\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R")
        if (pageMode != null) w(" /PageMode /$pageMode")
        if (pageLayout != null) w(" /PageLayout /$pageLayout")
        if (viewerPrefs != null) w(" /ViewerPreferences << $viewerPrefs >>")
        w(" >>\nendobj\n")

        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")

        val xref = buf.size()
        w("xref\n0 4\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
