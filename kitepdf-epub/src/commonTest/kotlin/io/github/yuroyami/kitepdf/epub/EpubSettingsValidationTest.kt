package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invalid reader geometry must fail at configuration time, not inside layout. */
class EpubSettingsValidationTest {

    @Test
    fun page_and_typography_values_must_be_positive_and_finite() {
        assertFailsWith<IllegalArgumentException> { EpubSettings(pageWidth = 0.0) }
        assertFailsWith<IllegalArgumentException> { EpubSettings(pageHeight = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { EpubSettings(fontSize = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { EpubSettings(margin = -1.0) }
        assertFailsWith<IllegalArgumentException> { EpubSettings(lineHeightScale = 0.0) }
    }

    @Test
    fun margins_must_leave_layout_space() {
        assertFailsWith<IllegalArgumentException> {
            EpubSettings(pageWidth = 100.0, pageHeight = 200.0, margin = 50.0)
        }
        assertFailsWith<IllegalArgumentException> {
            EpubSettings(pageWidth = 200.0, pageHeight = 100.0, margin = 50.0)
        }
    }
}
