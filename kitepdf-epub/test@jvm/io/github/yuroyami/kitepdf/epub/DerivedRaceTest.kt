package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Text built from one layout is never cached in the next (#890). */
class DerivedRaceTest {

    private fun KiteXmlNode.Element.copy(parent: KiteXmlNode.Element? = null): KiteXmlNode.Element {
        val out = KiteXmlNode.Element(tag, LinkedHashMap(attrs))
        out.parent = parent
        for (c in children) {
            out.children += when (c) {
                is KiteXmlNode.Element -> c.copy(out)
                is KiteXmlNode.Text -> KiteXmlNode.Text(c.text.replace("OLD", "NEW"))
                is KiteXmlNode.Comment -> KiteXmlNode.Comment(c.text)
            }
        }
        return out
    }

    @Test
    fun text_built_while_the_chapter_changes_stays_out_of_the_new_cache() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>OLD TEXT</p>"))
        doc.prepareChapter(0)
        val building = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = thread {
            // Stands for the old page's text: an empty page, which the new layout must not keep.
            doc.structuredTextOf(0, 0) { _ ->
                building.countDown()
                release.await(10, TimeUnit.SECONDS)
                io.github.yuroyami.kitepdf.core.KiteStructuredText(emptyList())
            }
        }
        assertTrue(building.await(10, TimeUnit.SECONDS))
        doc.replaceChapterTree(0, doc.chapterTree(0).copy())
        release.countDown()
        old.join(10_000)
        val text = doc.page(KiteLocation(0, 0)).textContent().plainText.replace(Regex("\\s+"), " ").trim()
        assertEquals("NEW TEXT", text)
    }
}
