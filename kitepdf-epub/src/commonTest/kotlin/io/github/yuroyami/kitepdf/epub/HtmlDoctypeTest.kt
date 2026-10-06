package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.script.HtmlDoctype
import kotlin.test.Test
import kotlin.test.assertEquals

/** The document type of an HTML document and the mode it sets (#546). Each expected value is what Chromium's `DOMParser` gives. */
class HtmlDoctypeTest {

    private fun read(markup: String): String {
        val found = HtmlDoctype.read(markup)
        val d = found.doctype
        return (if (found.quirks) "BackCompat " else "CSS1Compat ") + (d?.let { "${it.name}|${it.publicId}|${it.systemId}" } ?: "null")
    }

    @Test
    fun a_document_type_reads_as_chromium_reads_it() {
        val cases = listOf(
            "<!DOCTYPE html>" to "CSS1Compat html||",
            "<!-- c --><!doctype HTML><p>" to "CSS1Compat html||",
            "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">" to "BackCompat html|-//W3C//DTD HTML 4.01 Transitional//EN|",
            "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\" \"http://www.w3.org/TR/html4/loose.dtd\">" to
                "CSS1Compat html|-//W3C//DTD HTML 4.01 Transitional//EN|http://www.w3.org/TR/html4/loose.dtd",
            "<!DOCTYPE html SYSTEM \"about:legacy-compat\">" to "CSS1Compat html||about:legacy-compat",
            "<!DOCTYPE>" to "BackCompat ||",
            "<!DOCTYPE svg>" to "BackCompat svg||",
            "<!DOCTYPE html PUBLIC \"a>b\">" to "BackCompat html|a|",
            "<!DOCTYPE html PUBLIC>" to "BackCompat html||",
            "<!DOCTYPE html bogus>" to "BackCompat html||",
            "x<!DOCTYPE html>" to "BackCompat null",
            "\n\n<!DOCTYPE html  SYSTEM 's' >" to "CSS1Compat html||s",
            "<!DOCTYPE html PUBLIC \"-//IETF//DTD HTML 2.0//EN\">" to "BackCompat html|-//IETF//DTD HTML 2.0//EN|",
            "<!DOCTYPE html PUBLIC \"html\">" to "BackCompat html|html|",
            "<!DOCTYPE html PUBLIC 'p''s'>" to "CSS1Compat html|p|s",
        )
        assertEquals(cases.map { it.second }, cases.map { read(it.first) })
        assertEquals(1, HtmlDoctype.read("<!-- c --><!doctype HTML><p>").commentsBefore)
    }
}
