package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec

/**
 * The glyph contours of [text] in the host face that stands in for [fontSpec], at 1000 px per
 * em, with the baseline at y = 0 and y down. Null when the platform has no such face. Text
 * selection paths are rectangles, so they cannot stand in for these (#415).
 */
internal expect fun hostTextPath(text: String, fontSpec: FontSpec): Path?

/**
 * The host face that draws a CJK [fontSpec] in its own language, as a Compose family, or null to
 * draw in the generic family of [FontSpec.family] under the locale of [FontSpec.language]. A
 * locale picks a face of the right language for a character the generic face lacks, but not its
 * style, so a Mincho font would draw in a Gothic face (#472). Called on the host text thread only.
 */
internal expect fun hostFontFamily(fontSpec: FontSpec): FontFamily?
