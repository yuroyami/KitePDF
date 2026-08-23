@file:Suppress("unused")

package io.github.yuroyami.kitepdf.core

/*
 * Migration aliases, this release cycle only. These types kept bare names that
 * collide with Compose, Android and AWT types every consumer of this library
 * also imports, which forced aliased imports at nearly every call site.
 */

@Deprecated(
    "Renamed to KiteRectangle",
    ReplaceWith("KiteRectangle", "io.github.yuroyami.kitepdf.core.KiteRectangle"),
)
public typealias Rectangle = KiteRectangle
