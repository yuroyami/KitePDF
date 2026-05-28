package com.yuroyami.kitepdf.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Convenience for rendering a whole document into a vertical column of pages.
 * Eagerly lays out every page; for long documents prefer wrapping
 * [PdfPageView] in your own `LazyColumn` so off-screen pages aren't measured.
 *
 * @param pageSpacing vertical gap between consecutive pages.
 * @param pageModifier modifier applied to each individual [PdfPageView]
 *   (the page already fills the available width at its natural aspect ratio).
 */
@Composable
fun PdfDocumentPages(
    document: PdfDocument,
    modifier: Modifier = Modifier,
    pageSpacing: Dp = 8.dp,
    pageModifier: Modifier = Modifier.fillMaxWidth(),
) {
    Column(modifier = modifier) {
        for ((i, page) in document.pages.withIndex()) {
            if (i > 0) Spacer(modifier = Modifier.height(pageSpacing))
            PdfPageView(page = page, modifier = pageModifier)
        }
    }
}
