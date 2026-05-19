package sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuroyami.kitepdf.KitePDF
import com.yuroyami.kitepdf.PdfDocument

/**
 * KitePDF sample app — opens an embedded demo PDF, parses it with KitePDF,
 * and shows the parsed structure + extracted text. Demonstrates that the
 * library works without any platform-specific code.
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var report by remember { mutableStateOf<Report?>(null) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { report = analyze("Hello World (1 page)", DemoPdf.helloWorld) }) {
                        Text("Open: hello-world.pdf")
                    }
                    OutlinedButton(onClick = { report = analyze("Two Pages", DemoPdf.twoPages) }) {
                        Text("Open: two-pages.pdf")
                    }
                }

                report?.let { ReportCard(it) }
            }
        }
    }
}

@Composable
private fun ReportCard(report: Report) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(report.title, style = MaterialTheme.typography.titleMedium)
            if (report.error != null) {
                Text(
                    "Error: ${report.error}",
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                MetaRow("PDF version", report.pdfVersion ?: "—")
                MetaRow("Bytes", report.byteCount.toString())
                MetaRow("Pages", report.pageCount.toString())
                report.pageSizes.forEachIndexed { i, size ->
                    MetaRow("  Page ${i + 1}", "${size.first} × ${size.second} pt")
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = report.extractedText.ifEmpty { "(no text extracted)" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private data class Report(
    val title: String,
    val byteCount: Int,
    val pdfVersion: String? = null,
    val pageCount: Int = 0,
    val pageSizes: List<Pair<Int, Int>> = emptyList(),
    val extractedText: String = "",
    val error: String? = null,
)

private fun analyze(title: String, bytes: ByteArray): Report {
    return try {
        val doc: PdfDocument = KitePDF.open(bytes)
        val text = buildString {
            for ((i, page) in doc.pages.withIndex()) {
                if (i > 0) append("\n---\n")
                append("=== Page ${i + 1} ===\n")
                append(page.extractText())
            }
        }
        Report(
            title = title,
            byteCount = bytes.size,
            pdfVersion = doc.version,
            pageCount = doc.pageCount,
            pageSizes = doc.pages.map { it.width.toInt() to it.height.toInt() },
            extractedText = text,
        )
    } catch (t: Throwable) {
        Report(title = title, byteCount = bytes.size, error = "${t::class.simpleName}: ${t.message}")
    }
}
