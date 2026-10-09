package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver

/** A font resolver for a test that measures text before any scene registers Compose's text. */
internal fun testFontFamilyResolver(): FontFamily.Resolver {
    ensureComposeBackend()
    return createFontFamilyResolver()
}
