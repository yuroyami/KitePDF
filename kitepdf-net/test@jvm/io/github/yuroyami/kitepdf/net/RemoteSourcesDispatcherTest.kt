package io.github.yuroyami.kitepdf.net

import io.github.yuroyami.kitepdf.document.KiteDoc
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one remote loading test that needs threads of its own, so the JVM runs it alone. */
class RemoteSourcesDispatcherTest {

    /** The parse runs off the caller's dispatcher, so a call from the main thread does not parse there (#827). */
    @Test
    fun the_download_parses_off_the_dispatcher_that_called() {
        // A PDF without its cross-reference table, which the open repairs and warns about.
        val broken = String(NetFixtures.samplePdf(), Charsets.ISO_8859_1).let { it.substring(0, it.indexOf("xref")) + "%%EOF\n" }.toByteArray(Charsets.ISO_8859_1)
        val threads = java.util.Collections.synchronizedList(ArrayList<String>())
        val saved = io.github.yuroyami.kitepdf.core.KiteWarnings.sink
        io.github.yuroyami.kitepdf.core.KiteWarnings.sink = io.github.yuroyami.kitepdf.core.KiteWarningSink { threads += Thread.currentThread().name }
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val doc = runBlocking(main.asCoroutineDispatcher()) { KiteDoc.openUrl("https://example.org/a.pdf", NetFixtures.clientServing(broken)) }
            assertEquals(2, doc.pageCount)
        } finally {
            io.github.yuroyami.kitepdf.core.KiteWarnings.sink = saved
            main.shutdown()
        }
        assertTrue(threads.isNotEmpty(), "the repair warned")
        assertTrue(threads.none { it.startsWith("fake-main") }, "the parse ran on $threads")
    }
}
