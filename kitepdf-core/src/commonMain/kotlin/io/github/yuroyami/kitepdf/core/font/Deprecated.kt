@file:Suppress("unused")

package io.github.yuroyami.kitepdf.core.font

/*
 * Migration alias, this release cycle only. FontFamily collided with
 * androidx.compose.ui.text.font.FontFamily in every Compose call site.
 */

@Deprecated(
    "Renamed to KiteFontFamily",
    ReplaceWith("KiteFontFamily", "io.github.yuroyami.kitepdf.core.font.KiteFontFamily"),
)
public typealias FontFamily = KiteFontFamily
