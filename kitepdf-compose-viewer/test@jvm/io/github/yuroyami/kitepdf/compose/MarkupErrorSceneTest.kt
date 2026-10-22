package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.core.xml.KiteXmlError
import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A chapter that is not well-formed XML lays out, and its first page says it is in error (#517). */
class MarkupErrorSceneTest {

    private val broken = "<p>Closed.</p>\n<p>Unclosed."
    private val fine = "<p>Fine.</p>"

    @Test
    fun the_book_lists_the_errors_of_each_chapter() {
        val book = EpubDocument.open(multiSpineEpub(listOf(fine, broken, "<p::p>Name.</p::p>")))
        assertEquals(emptyList(), book.markupErrors(0))
        assertEquals(listOf(KiteXmlError(2, 1, "the element <p> is never closed")), book.markupErrors(1))
        assertTrue(book.markupErrors(2).any { "p::p" in it.message }, "${book.markupErrors(2)}")
        // The chapter still lays out what it can.
        assertTrue("Unclosed." in book.page(io.github.yuroyami.kitepdf.core.KiteLocation(1, 0)).textContent().plainText)
    }

    private fun texts(scene: ImageComposeScene): List<String> = onTestUiThread {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return@onTestUiThread out
    }

    private fun shown(chapters: List<String>, showMarkupErrors: Boolean = true): List<String> {
        val state = KiteDocViewState(EpubDocument.open(multiSpineEpub(chapters)))
        ImageComposeScene(width = 300, height = 400, density = Density(1f)) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), showMarkupErrors = showMarkupErrors)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.pumpFrames(10)
            return texts(scene)
        }
    }

    @Test
    fun the_first_page_of_a_broken_chapter_shows_a_notice() {
        val notice = shown(listOf(broken, fine)).filter { "not well-formed" in it }
        assertEquals(listOf("This chapter is not well-formed XML. Line 2: the element <p> is never closed."), notice)
    }

    @Test
    fun a_well_formed_chapter_and_a_host_that_turns_it_off_show_none() {
        assertEquals(emptyList(), shown(listOf(fine)).filter { "not well-formed" in it })
        assertEquals(emptyList(), shown(listOf(broken), showMarkupErrors = false).filter { "not well-formed" in it })
    }
}
