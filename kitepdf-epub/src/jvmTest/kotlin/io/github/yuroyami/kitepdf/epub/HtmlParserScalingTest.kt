package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * Opt-in scaling evidence for the open-element searches in #452. Run with:
 * `KITEPDF_BENCH=true ./gradlew :kitepdf-epub:jvmTest --tests "*HtmlParserScalingTest*" --rerun-tasks`
 *
 * Timings exclude fixture construction and verification and carry no wall-clock assertion.
 * Set `KITEPDF_HTML_BENCH_SAMPLES=3` for repeated measurements; the default of one keeps
 * measurements of the old quadratic parser practical. Every measured tree is checked.
 */
class HtmlParserScalingTest {
    private data class Shape(val name: String, val open: String, val close: String, val elementsPerUnit: Int)

    @Test
    fun deep_markup_scaling_preserves_elements_and_text() {
        val enabled = System.getenv("KITEPDF_BENCH") == "true" || System.getProperty("kitepdf.bench") == "true"
        assumeTrue("Run with KITEPDF_BENCH=true to enable HTML parser scaling measurements.", enabled)
        val samples = (System.getenv("KITEPDF_HTML_BENCH_SAMPLES")?.toIntOrNull() ?: 1).coerceIn(1, 10)
        val shapes = listOf(
            Shape("nested div", "<div>", "</div>", 1),
            Shape("nested ol/li", "<ol><li>", "</li></ol>", 2),
            Shape("unmatched closes", "<span></missing>", "</span>", 1),
            Shape("absent implicit li", "<span><li></li>", "</span>", 2),
            Shape("absent implicit dd", "<span><dd></dd>", "</span>", 2),
            Shape("absent implicit td", "<span><td></td>", "</span>", 2),
        )
        println("HTML parser scaling (#452): median parse milliseconds, $samples sample(s); doubling ratios are evidence only.")
        println("| Shape | Units | Elements | Parse ms | Ratio |")
        println("|---|---:|---:|---:|---:|")
        for (shape in shapes) {
            val warmup = fixture(shape, 500)
            repeat(8) { verify(HtmlParser.parse(warmup), shape, 500) }
            var previousNanos: Long? = null
            for (units in listOf(12_500, 25_000, 50_000, 100_000)) {
                val input = fixture(shape, units)
                val nanos = LongArray(samples) {
                    val started = System.nanoTime()
                    val root = HtmlParser.parse(input)
                    val elapsed = System.nanoTime() - started
                    verify(root, shape, units)
                    elapsed
                }.sorted()[samples / 2]
                val ratio = previousNanos?.let { format(nanos.toDouble() / it) } ?: "-"
                println("| ${shape.name} | $units | ${units * shape.elementsPerUnit + 3} | ${format(nanos / 1_000_000.0)} | $ratio |")
                previousNanos = nanos
            }
        }
    }

    private fun fixture(shape: Shape, units: Int): String =
        "<html><body>" + shape.open.repeat(units) + "deep words" + shape.close.repeat(units) +
            "<p>After.</p></body></html>"

    private fun verify(root: KiteXmlNode.Element, shape: Shape, units: Int) {
        var elements = 0
        val text = StringBuilder()
        val pending = arrayListOf<KiteXmlNode>(root)
        while (pending.isNotEmpty()) {
            when (val node = pending.removeAt(pending.lastIndex)) {
                is KiteXmlNode.Element -> {
                    if (node !== root) elements++
                    for (index in node.children.indices.reversed()) pending.add(node.children[index])
                }
                is KiteXmlNode.Text -> text.append(node.text)
                is KiteXmlNode.Comment -> {}
            }
        }
        assertEquals(units * shape.elementsPerUnit + 3, elements, "${shape.name} at $units units")
        assertEquals("deep wordsAfter.", text.toString(), "${shape.name} at $units units")
        val html = root.children.filterIsInstance<KiteXmlNode.Element>().single()
        val body = html.children.filterIsInstance<KiteXmlNode.Element>().single()
        val after = body.children.filterIsInstance<KiteXmlNode.Element>().last()
        assertEquals("p", after.tag, "following paragraph remains a child of body")
        assertEquals("After.", after.textContent())
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
