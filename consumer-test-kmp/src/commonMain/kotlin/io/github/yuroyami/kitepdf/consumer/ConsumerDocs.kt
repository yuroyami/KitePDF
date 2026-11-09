package io.github.yuroyami.kitepdf.consumer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont

/** A one-page PDF of 200 by 100 points with a line of Helvetica [text], made by `PdfBuilder`. */
fun helloPdf(text: String = "Hello"): ByteArray =
    PdfBuilder()
        .page(width = 200.0, height = 100.0) { text(StandardFont.Helvetica, 24.0, 20.0, 40.0, text) }
        .build()

/** The page count of a document of any format, read from its bytes. */
fun pageCountOf(bytes: ByteArray): Int = KiteDoc.open(bytes).use { it.pageCount }

/** The plain text of the first page of [bytes]. */
fun firstPageText(bytes: ByteArray): String? =
    KiteDoc.open(bytes).use { it.pages.first().textContent()?.plainText }

/** The viewer, from common code, as an app with a shared UI shows a document. */
@Composable
fun ConsumerViewer(document: KiteDocument, modifier: Modifier = Modifier) {
    KiteDocView(document, modifier)
}
