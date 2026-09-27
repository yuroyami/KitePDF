package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Path
import io.github.yuroyami.kitepdf.core.font.FontSpec

/**
 * The glyph contours of [text] in the host face that stands in for [fontSpec], at 1000 px per
 * em, with the baseline at y = 0 and y down. Null when the platform has no such face. Text
 * selection paths are rectangles, so they cannot stand in for these (#415).
 */
internal expect fun hostTextPath(text: String, fontSpec: FontSpec): Path?
