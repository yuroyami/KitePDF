package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.cbz.CbzDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KiteWrongPasswordException
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubFormatException
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.svg.SvgDocument
import io.github.yuroyami.kitepdf.xps.XpsDocument
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin/Native turns an exception into an NSError only for a function marked `@Throws`, and ends
 * the process for any other, so a Swift app that opens a locked PDF with a wrong password would end
 * (#706). On the JVM the annotation is a `throws` clause, so it is checked here by reflection: each
 * open function declares what its KDoc says it throws.
 */
class ThrowsContractTest {

    private val bytes = ByteArray::class.java
    private val string = String::class.java
    private val settings = EpubSettings::class.java

    private fun declares(owner: Class<*>, name: String, expected: List<Class<out Throwable>>, vararg params: Class<*>) {
        val declared = owner.getMethod(name, *params).exceptionTypes.toList()
        for (e in expected) assertTrue(e in declared, "${owner.simpleName}.$name(${params.joinToString { it.simpleName }}) should declare ${e.simpleName}, declares $declared")
    }

    @Test
    fun theOpenFunctionsDeclareWhatTheyThrow() {
        val pdf = listOf(PdfFormatException::class.java, KiteWrongPasswordException::class.java)
        declares(PdfDocument.Companion::class.java, "open", pdf, bytes, bytes, Boolean::class.javaPrimitiveType!!)
        declares(PdfDocument.Companion::class.java, "open", pdf, bytes, string, Boolean::class.javaPrimitiveType!!)
        val any = listOf(KiteFormatException::class.java, KiteWrongPasswordException::class.java)
        declares(KiteDoc::class.java, "open", any, bytes, string, settings)
        declares(KiteDoc::class.java, "openBase64", any, string, string, settings)
        declares(EpubDocument.Companion::class.java, "open", listOf(EpubFormatException::class.java), bytes, settings)
        declares(CbzDocument.Companion::class.java, "open", listOf(KiteFormatException::class.java), bytes)
        declares(SvgDocument.Companion::class.java, "open", listOf(KiteFormatException::class.java), bytes)
        declares(XpsDocument.Companion::class.java, "open", listOf(KiteFormatException::class.java), bytes)
        val sources = Class.forName("io.github.yuroyami.kitepdf.document.Sources_jvmKt")
        val local = any + java.io.IOException::class.java
        val doc = KiteDoc::class.java
        declares(sources, "openFile", local, doc, string, string, settings)
        declares(sources, "openFile", local, doc, string, string, settings, Int::class.javaPrimitiveType!!)
        declares(sources, "open", local, doc, File::class.java, string, settings)
        declares(sources, "open", local, doc, InputStream::class.java, string, settings)
    }
}
