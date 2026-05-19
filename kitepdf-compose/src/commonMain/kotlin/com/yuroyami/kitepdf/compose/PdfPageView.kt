package com.yuroyami.kitepdf.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import com.yuroyami.kitepdf.PdfDocument
import com.yuroyami.kitepdf.PdfPage
import com.yuroyami.kitepdf.render.Matrix as PdfMatrix
import com.yuroyami.kitepdf.render.PageRenderer

/**
 * Render a single [PdfPage] into a Compose [Canvas] surface.
 *
 *   PdfPageView(page = doc.pages[0], modifier = Modifier.fillMaxWidth())
 *
 * Fills the available width and lays out at the page's natural aspect ratio.
 * The canvas surface defaults to white; PDFs that don't paint a background
 * inherit that. Pass [background] to override.
 *
 * @param background page background colour drawn before content (rare PDFs
 *   paint their own background — most assume white paper).
 */
@Composable
fun PdfPageView(
    page: PdfPage,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
) {
    val pageWidth = page.width
    val pageHeight = page.height
    val aspectRatio = (pageWidth / pageHeight).toFloat()
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(background),
    ) {
        val scale = size.width / pageWidth.toFloat()
        // Device CTM: scale uniformly to fill, flip Y so origin is top-left.
        val deviceCtm = PdfMatrix(
            a = scale.toDouble(), b = 0.0,
            c = 0.0, d = -scale.toDouble(),
            e = 0.0, f = size.height.toDouble(),
        )
        val canvas = ComposeCanvas(this, textMeasurer)
        // The document is the resolver — but PdfPage doesn't expose it directly;
        // call into render() via a public Page.renderTo(canvas) extension instead.
        page.renderTo(canvas, deviceCtm)
    }
}

/**
 * Convenience for rendering a whole document into a column of pages.
 * Lays out one [PdfPageView] per page; the caller wraps in a [androidx.compose.foundation.lazy.LazyColumn]
 * if they care about lazy materialisation on long docs.
 */
@Composable
fun PdfDocumentPages(
    document: PdfDocument,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        for (page in document.pages) {
            PdfPageView(
                page = page,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { Modifier },
            )
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { it },
            )
        }
    }
}
