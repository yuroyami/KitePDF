package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.search
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-30 corpus smoke: searching a common word in a real-world PDF's first
 * page returns hits without throwing. Skipped when the git-ignored corpus
 * is not present (CI).
 */
class TextSearchCorpusTest {

    private fun corpusPdf(name: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d?.let { File(it, "corpus/pdf/$name") }?.takeIf { it.exists() }
    }

    @Test
    fun searching_a_known_word_in_a_real_pdf_page_returns_hits() {
        // The audit suggested searching "the", but GoldenHour is a 4-page ECG
        // chart with no prose at all; its lead labels (aVR/aVL/aVF) are the
        // real text, so the smoke needle is one of those, case-insensitively.
        val file = corpusPdf("GoldenHour-byIOS.pdf") ?: return
        val doc = KitePDF.open(file.readBytes())
        val hits = doc.pages[0].search("avr")
        assertTrue(hits.isNotEmpty(), "page 0 contains the aVR lead label")
        for (h in hits) {
            assertTrue(h.quads.isNotEmpty())
            for (q in h.quads) {
                assertTrue(
                    q.left >= -1.0 && q.right <= doc.pages[0].displayWidth + 1.0 &&
                        q.bottom >= -1.0 && q.top <= doc.pages[0].displayHeight + 1.0,
                    "quad within the display box: $q",
                )
            }
        }
    }
}
