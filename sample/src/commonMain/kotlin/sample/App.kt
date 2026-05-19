package sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuroyami.kitepdf.KitePDF
import com.yuroyami.kitepdf.PdfDocument
import com.yuroyami.kitepdf.compose.PdfPageView

/**
 * KitePDF sample — Session 2: actually renders pages onto a Compose Canvas.
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
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()
                DemoSelector(current = demo, onSelect = { demo = it })
                doc.fold(
                    onSuccess = { DocumentDisplay(it) },
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

@Composable
private fun DocumentDisplay(doc: PdfDocument) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Metadata strip
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("PDF ${doc.version}") })
            AssistChip(onClick = {}, label = { Text("${doc.pageCount} page(s)") })
            doc.pages.firstOrNull()?.let { p ->
                AssistChip(onClick = {}, label = { Text("${p.width.toInt()} × ${p.height.toInt()} pt") })
            }
        }

        // Side-by-side: rendered page | extracted text
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Rendered page
            Card(
                modifier = Modifier.widthIn(max = 500.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    PdfPageView(page = doc.pages.first())
                }
            }

            // Extracted text
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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

        // Multi-page docs: stack remaining pages.
        if (doc.pageCount > 1) {
            HorizontalDivider()
            Text("Additional pages:", style = MaterialTheme.typography.titleMedium)
            for (i in 1 until doc.pageCount) {
                Card(
                    modifier = Modifier.widthIn(max = 500.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Box(modifier = Modifier.padding(8.dp)) {
                        PdfPageView(page = doc.pages[i])
                    }
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
