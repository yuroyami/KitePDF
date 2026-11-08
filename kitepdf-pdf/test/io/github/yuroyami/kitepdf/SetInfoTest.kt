package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each setInfo call changes only what it is given, an earlier call's values included (ISO 32000-1, 14.3.3; #686). */
class SetInfoTest {

    private fun built(withInfo: Boolean): ByteArray {
        val b = PdfBuilder().page(width = 100.0, height = 100.0) {}
        if (withInfo) b.setInfo(title = "Old", subject = "Kept")
        return b.build(compress = false)
    }

    @Test
    fun twoCallsKeepBothValues() {
        for (withInfo in listOf(false, true)) {
            for (incremental in listOf(true, false)) {
                val editor = PdfDocument.open(built(withInfo)).edit()
                editor.setInfo(title = "Title A")
                editor.setInfo(author = "Author B")
                val out = PdfDocument.open(if (incremental) editor.saveIncremental() else editor.saveRewritten())
                val label = "info=$withInfo incremental=$incremental"
                assertEquals("Title A", out.info.title, label)
                assertEquals("Author B", out.info.author, label)
                if (withInfo) assertEquals("Kept", out.info.subject, label)
            }
        }
    }
}
