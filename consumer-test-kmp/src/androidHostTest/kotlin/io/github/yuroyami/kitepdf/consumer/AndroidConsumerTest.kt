package io.github.yuroyami.kitepdf.consumer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the common code against the Android variant of each KitePDF module, so the classes that an
 * AAR carries must load on the Android runtime classpath (#678).
 */
class AndroidConsumerTest {

    @Test
    fun a_built_pdf_opens_from_the_android_artifacts() {
        val bytes = helloPdf("Hello Android")
        assertEquals(1, pageCountOf(bytes))
        assertEquals("Hello Android", firstPageText(bytes)?.trim())
    }
}
