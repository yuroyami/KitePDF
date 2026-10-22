package sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.compose.KiteDocLayout
import io.github.yuroyami.kitepdf.compose.KiteNavigationControls
import io.github.yuroyami.kitepdf.compose.KiteThumbnailStrip
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteDocViewColors
import io.github.yuroyami.kitepdf.compose.KiteZoomSpec
import io.github.yuroyami.kitepdf.compose.encodeToPng
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState

/**
 * KitePDF sample: the whole document through the one [KiteDocView] composable,
 * plus the export callback wired to [encodeToPng].
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var demo by remember { mutableStateOf(Demo.EPUB) }
            // EPUB reader settings live up here: changing the font size builds a
            // new document, so the state below has to be rebuilt with it.
            var fontSize by remember { mutableStateOf(12.0) }
            var resume by remember { mutableStateOf<KiteBookmark?>(null) }

            val opened = remember(demo) { runCatching { KiteDoc.open(demo.bytes) } }
            // withFontSize re-flows the same parsed book. It never re-reads the file.
            val doc = remember(opened, fontSize) {
                opened.map { if (it is EpubDocument && it.fontSize != fontSize) it.withFontSize(fontSize) else it }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()
                DemoSelector(
                    current = demo,
                    onSelect = {
                        resume = null
                        demo = it
                    },
                )
                doc.fold(
                    onSuccess = {
                        DocumentDisplay(
                            doc = it,
                            resume = resume,
                            fontSize = fontSize,
                            onFontSize = { size, mark ->
                                // Save the place first, then swap the document.
                                resume = mark
                                fontSize = size
                            },
                            modifier = Modifier.weight(1f),
                        )
                    },
                    onFailure = { ErrorCard(it) },
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "KitePDF",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Pure-Kotlin PDF library, v${KitePDF.VERSION}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun DemoSelector(current: Demo, onSelect: (Demo) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in Demo.entries) {
            FilterChip(
                selected = d == current,
                onClick = { onSelect(d) },
                label = { Text(d.label) },
            )
        }
    }
}

/** The layout modes the sample lets you flip between. */
private enum class LayoutChoice(val label: String, val layout: KiteDocLayout) {
    VERTICAL("Scroll ↓", KiteDocLayout.Continuous(Orientation.Vertical)),
    HORIZONTAL("Scroll →", KiteDocLayout.Continuous(Orientation.Horizontal)),
    PAGER("Pager", KiteDocLayout.Paged(Orientation.Horizontal)),
}

@Composable
private fun DocumentDisplay(
    doc: KiteDocument,
    resume: KiteBookmark?,
    fontSize: Double,
    onFontSize: (Double, KiteBookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    // KiteDocView's onPageRendered callback below feeds this. It proves the export path.
    var exportNote by remember(doc) { mutableStateOf("rendering…") }
    var layoutChoice by remember { mutableStateOf(LayoutChoice.VERTICAL) }
    // A new document (a font size change) makes a new state, opened at the
    // bookmark the reader was on.
    val state = rememberKiteDocViewState(doc, resume ?: KiteBookmark.Page(0))
    val book = doc as? EpubDocument

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Metadata strip. A reflowable book has no final page count until it is
        // fully laid out, so the total is marked approximate until then.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(if (book != null) "EPUB" else "PDF ${(doc as PdfDocument).version}") },
            )
            AssistChip(
                onClick = {},
                label = {
                    val total = if (state.isComplete) "${state.knownPageCount}" else "~${state.knownPageCount}"
                    Text("$total page(s)")
                },
            )
            AssistChip(onClick = {}, label = { Text(exportNote) })
        }
        if (book != null) ReaderSettings(state, fontSize, onFontSize)
        // Layout switcher: same document + state, three layouts.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in LayoutChoice.entries) {
                FilterChip(
                    selected = c == layoutChoice,
                    onClick = { layoutChoice = c },
                    label = { Text(c.label) },
                )
            }
        }
        PdfSelectionActions(state)

        // Side-by-side: the viewer | extracted text
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.widthIn(max = 500.dp).fillMaxHeight(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column {
                    // ONE composable: any layout, pinch/double-tap zoom, HUD
                    // overlay. The callback hands back each rendered page as a
                    // saveable image.
                    KiteDocView(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                        layout = layoutChoice.layout,
                        zoomSpec = KiteZoomSpec(maxZoom = 6f),
                        colors = KiteDocViewColors(viewportBackground = Color(0xFF1E1E1E)),
                        // What a chapter shows while it is still being laid out.
                        chapterPlaceholder = { chapter ->
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Chapter ${chapter + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                        },
                        onPageRendered = { index, image ->
                            val png = image.encodeToPng()
                            exportNote = "page ${index + 1} → ${png?.size ?: 0} B PNG"
                        },
                        overlay = { s ->
                            // HUD-style controls floating over the pages; the
                            // same state also drives the thumbnail strip below.
                            KiteNavigationControls(
                                s,
                                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            )
                        },
                    )
                    KiteThumbnailStrip(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                        thumbnailHeight = 56.dp,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("Text on this page:", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    // The page being read, not page one: asking for doc.pages
                    // would lay out every chapter of a reflowable book.
                    val here = state.currentLocation
                    Text(
                        text = runCatching { doc.page(here).textContent()?.plainText }
                            .getOrNull()
                            ?.ifEmpty { "(empty)" }
                            ?: "(laying out chapter ${here.chapter + 1}…)",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(t: Throwable) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${t::class.simpleName}: ${t.message ?: "(no message)"}",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Font size for a reflowable book, and the recipe that makes it not lose the
 * reader's place: take a bookmark, rebuild the document, reopen at the bookmark.
 * A bookmark is a position in the text, so it survives the re-flow that a
 * different font size causes; a page number would not.
 */
@Composable
private fun ReaderSettings(
    state: io.github.yuroyami.kitepdf.compose.KiteDocViewState,
    fontSize: Double,
    onFontSize: (Double, KiteBookmark) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Font", style = MaterialTheme.typography.labelLarge)
        for (size in listOf(10.0, 12.0, 15.0, 19.0)) {
            FilterChip(
                selected = size == fontSize,
                onClick = { if (size != fontSize) onFontSize(size, state.currentBookmark()) },
                label = { Text("${size.toInt()}pt") },
            )
        }
        AssistChip(
            onClick = {},
            label = { Text("chapter ${state.currentLocation.chapter + 1}") },
        )
    }
}

private enum class Demo(val label: String, val bytes: ByteArray) {
    EPUB("EPUB book", DemoEpub.book),
    RECT("Rectangles & color", DemoPdf.rectanglesAndText),
    FONTS("Multiple fonts", DemoPdf.multipleFonts),
    CLIP("Clipping (W)", DemoPdf.clippedShapes),
    BLEND("Transparency + blends", DemoPdf.transparencyAndBlending),
    CMYK("CMYK + annotations", DemoPdf.cmykAndAnnotations),
    IMAGE("Image XObject", DemoPdf.imagePlaceholder),
    TWO_PAGE("Two pages", DemoPdf.twoPages),
    HELLO("Hello world", DemoPdf.helloWorld),
}

/**
 * The app-side half of text selection: the viewer exposes [KiteDocViewState.selection]
 * (made by long-press + drag on any page) but never touches the clipboard itself.
 * Copying is the app's decision. Long-press text in the viewer, then press Copy.
 */
@Composable
private fun PdfSelectionActions(state: io.github.yuroyami.kitepdf.compose.KiteDocViewState) {
    val selection = state.selection ?: return
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(selection.text))
                state.clearSelection()
            },
            label = { Text("Copy \"${selection.text.take(24)}${if (selection.text.length > 24) "…" else ""}\"") },
        )
        AssistChip(onClick = { state.clearSelection() }, label = { Text("Clear") })
    }
}
