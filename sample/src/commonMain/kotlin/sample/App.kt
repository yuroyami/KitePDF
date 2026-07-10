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
import io.github.yuroyami.kitepdf.compose.PdfLayout
import io.github.yuroyami.kitepdf.compose.PdfNavigationControls
import io.github.yuroyami.kitepdf.compose.PdfThumbnailStrip
import io.github.yuroyami.kitepdf.compose.PdfView
import io.github.yuroyami.kitepdf.compose.PdfViewColors
import io.github.yuroyami.kitepdf.compose.PdfZoomSpec
import io.github.yuroyami.kitepdf.compose.encodeToPng
import io.github.yuroyami.kitepdf.compose.rememberPdfViewState

/**
 * KitePDF sample — the whole document through the one [PdfView] composable,
 * plus the export callback wired to [encodeToPng].
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var demo by remember { mutableStateOf(Demo.RECT) }
            val bytes = demo.bytes
            val doc = remember(bytes) {
                runCatching { KitePDF.open(bytes) }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()
                DemoSelector(current = demo, onSelect = { demo = it })
                doc.fold(
                    onSuccess = { DocumentDisplay(it, Modifier.weight(1f)) },
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
            text = "Pure-Kotlin PDF library — v${KitePDF.VERSION}",
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
private enum class LayoutChoice(val label: String, val layout: PdfLayout) {
    VERTICAL("Scroll ↓", PdfLayout.Continuous(Orientation.Vertical)),
    HORIZONTAL("Scroll →", PdfLayout.Continuous(Orientation.Horizontal)),
    PAGER("Pager", PdfLayout.Paged(Orientation.Horizontal)),
}

@Composable
private fun DocumentDisplay(doc: PdfDocument, modifier: Modifier = Modifier) {
    // Fed by PdfView's onPageRendered callback below — proves the export path.
    var exportNote by remember(doc) { mutableStateOf("rendering…") }
    var layoutChoice by remember { mutableStateOf(LayoutChoice.VERTICAL) }
    val state = rememberPdfViewState(doc)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Metadata strip
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("PDF ${doc.version}") })
            AssistChip(onClick = {}, label = { Text("${doc.pageCount} page(s)") })
            AssistChip(onClick = {}, label = { Text(exportNote) })
        }
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
                    PdfView(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                        layout = layoutChoice.layout,
                        zoomSpec = PdfZoomSpec(maxZoom = 6f),
                        colors = PdfViewColors(viewportBackground = Color(0xFF1E1E1E)),
                        onPageRendered = { index, image ->
                            val png = image.encodeToPng()
                            exportNote = "page ${index + 1} → ${png?.size ?: 0} B PNG"
                        },
                        overlay = { s ->
                            // HUD-style controls floating over the pages; the
                            // same state also drives the thumbnail strip below.
                            PdfNavigationControls(
                                s,
                                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            )
                        },
                    )
                    PdfThumbnailStrip(
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
                    Text("Extracted text:", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = doc.pages.first().extractText().ifEmpty { "(empty)" },
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

private enum class Demo(val label: String, val bytes: ByteArray) {
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
 * T-80's app-side half: the viewer exposes [PdfViewState.selection] (made by
 * long-press + drag on any page) but never touches the clipboard itself —
 * copying is the app's decision. Long-press text in the viewer, then hit Copy.
 */
@Composable
private fun PdfSelectionActions(state: io.github.yuroyami.kitepdf.compose.PdfViewState) {
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
