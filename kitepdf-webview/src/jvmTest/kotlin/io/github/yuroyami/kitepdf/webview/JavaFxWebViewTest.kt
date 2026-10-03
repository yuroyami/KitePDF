package io.github.yuroyami.kitepdf.webview

import io.github.yuroyami.kitepdf.webview.FxTests.near
import io.github.yuroyami.kitepdf.webview.FxTests.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JavaFX's web view on the desktop draws a scripted page offscreen, runs its script when the
 * reader taps it, loads only what the book holds, and hands its links over (#41).
 */
class JavaFxWebViewTest {

    private val red = 0xFF0000
    private val blue = 0x0000FF
    private val green = 0x00A000

    @Test
    fun a_tap_on_the_button_runs_the_script_and_the_next_frame_shows_it() {
        FxTests.assumeJavaFx()
        val doc = WebBooks.scriptedPage()
        val server = BookServer.acquire(doc)
        val host = FxTests.Recorder()
        val surface = assertNotNull(JavaFxWebEngine.open(server.urlOf("OEBPS/page.xhtml"), host))
        try {
            surface.resize(300, 200, 1f)
            assertTrue(waitFor { host.frame?.let { it.width == 300 && near(it.at(150, 30), red) } == true }, "the band is red at first")
            surface.mouse(KiteMouseAction.PRESS, 70f, 120f, KiteDesktopInput.BUTTON_PRIMARY, KiteDesktopInput.BUTTON_PRIMARY, 0)
            surface.mouse(KiteMouseAction.RELEASE, 70f, 120f, KiteDesktopInput.BUTTON_PRIMARY, 0, 0)
            assertTrue(waitFor { host.frame?.let { near(it.at(150, 30), blue) } == true }, "the tap turned the band blue")

            // Twice the scale: the same page, in twice the pixels.
            surface.resize(300, 200, 2f)
            assertTrue(waitFor { host.frame?.let { it.width == 600 && it.height == 400 && near(it.at(300, 60), blue) } == true }, "a frame at twice the scale")
            assertTrue(server.requested.containsAll(listOf("OEBPS/page.xhtml", "OEBPS/page.css", "OEBPS/page.js")), "${server.requested}")
        } finally {
            surface.close()
            BookServer.release(server)
        }
    }

    @Test
    fun every_file_comes_from_the_book_and_nothing_from_the_network() {
        FxTests.assumeJavaFx()
        FxTests.Outside().use { outside ->
            val doc = WebBooks.quizChapter(outside.url)
            val server = BookServer.acquire(doc)
            val host = FxTests.Recorder()
            val surface = assertNotNull(JavaFxWebEngine.open(server.urlOf("OEBPS/quiz.xhtml"), host))
            try {
                surface.resize(200, 100, 1f)
                assertTrue(waitFor { host.frame?.let { near(it.at(150, 80), green) } == true }, "the quiz shows")
                assertTrue(waitFor { "OEBPS/dot.svg" in server.requested }, "the picture of the book loads: ${server.requested}")
                assertTrue(waitFor { "OEBPS/root.svg" in server.requested }, "a path from the root of the container loads: ${server.requested}")
                Thread.sleep(500)
                assertEquals(0, outside.hits.get(), "the picture outside the book was asked for")
                for (path in server.requested) assertNotNull(doc.resource(path), "$path is not in the book")
            } finally {
                surface.close()
                BookServer.release(server)
            }
        }
    }

    @Test
    fun a_link_in_the_page_goes_to_the_host_and_the_page_stays() {
        FxTests.assumeJavaFx()
        val doc = WebBooks.quizChapter()
        val server = BookServer.acquire(doc)
        val host = FxTests.Recorder()
        val surface = assertNotNull(JavaFxWebEngine.open(server.urlOf("OEBPS/quiz.xhtml"), host))
        try {
            surface.resize(200, 100, 1f)
            assertTrue(waitFor { host.frame?.let { near(it.at(150, 80), green) } == true })
            // The link is a block of 100 by 40 below the two small pictures.
            surface.mouse(KiteMouseAction.PRESS, 40f, 30f, KiteDesktopInput.BUTTON_PRIMARY, KiteDesktopInput.BUTTON_PRIMARY, 0)
            surface.mouse(KiteMouseAction.RELEASE, 40f, 30f, KiteDesktopInput.BUTTON_PRIMARY, 0, 0)
            assertTrue(waitFor { host.links.isNotEmpty() }, "the link reached the host")
            assertEquals(WebBooks.NEXT, server.hrefOf(host.links.single()))
            Thread.sleep(300)
            assertTrue(host.frame?.let { near(it.at(150, 80), green) } == true, "the quiz is still in place")
        } finally {
            surface.close()
            BookServer.release(server)
        }
    }

    /**
     * A script that sets the page's location is a link too. The host gets it, and the island
     * goes back to its own document. Loading that document from inside WebKit's navigation
     * crashed WebKit, so a click that came before the page had loaded, and its bridge, took the
     * whole process down.
     */
    @Test
    fun a_page_that_moves_itself_hands_the_link_over_and_comes_back() {
        FxTests.assumeJavaFx()
        val doc = WebBooks.book(
            items = listOf(
                WebBooks.Item("c.xhtml", "application/xhtml+xml", spine = true),
                WebBooks.Item("move.xhtml", "application/xhtml+xml"),
                WebBooks.Item("next.xhtml", "application/xhtml+xml", spine = true),
            ),
            files = mapOf(
                "c.xhtml" to WebBooks.xhtml(body = "<p>Chapter.</p>"),
                "move.xhtml" to WebBooks.xhtml(
                    head = """<style type="text/css">html, body { margin: 0; height: 100%; background: rgb(0, 160, 0); }</style>""",
                    body = """<button type="button" onclick="location.href = 'next.xhtml'" style="margin: 0; width: 100px; height: 40px;">Move</button>""",
                ),
                "next.xhtml" to WebBooks.xhtml(body = "<p>Next.</p>"),
            ),
        )
        val server = BookServer.acquire(doc)
        val host = FxTests.Recorder()
        val surface = assertNotNull(JavaFxWebEngine.open(server.urlOf("OEBPS/move.xhtml"), host))
        try {
            surface.resize(200, 100, 1f)
            assertTrue(waitFor { host.frame?.let { near(it.at(150, 80), green) } == true })
            repeat(3) {
                surface.mouse(KiteMouseAction.PRESS, 40f, 20f, KiteDesktopInput.BUTTON_PRIMARY, KiteDesktopInput.BUTTON_PRIMARY, 0)
                surface.mouse(KiteMouseAction.RELEASE, 40f, 20f, KiteDesktopInput.BUTTON_PRIMARY, 0, 0)
                assertTrue(waitFor { host.links.size > it }, "the move reached the host")
                // A frame drawn after the move: the island's own document again, not the next one.
                host.frame = null
                assertTrue(waitFor { host.frame?.let { near(it.at(150, 80), green) } == true }, "the island is back on its document")
            }
            assertEquals(listOf("OEBPS/next.xhtml"), host.links.map { server.hrefOf(it) }.distinct())
        } finally {
            surface.close()
            BookServer.release(server)
        }
    }
}
