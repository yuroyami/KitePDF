@file:Suppress("unused")

package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle

/*
 * Migration aliases, this release cycle only. Matrix, BlendMode and ColorSpace
 * all collide with types from Compose, Android and Skia that any renderer
 * imports too, and ImageXObject named a PDF construct in the shared core that
 * the EPUB handler also builds.
 */

@Deprecated(
    "Renamed to KiteMatrix",
    ReplaceWith("KiteMatrix", "io.github.yuroyami.kitepdf.core.render.KiteMatrix"),
)
public typealias Matrix = KiteMatrix

@Deprecated(
    "Renamed to KiteBlendMode",
    ReplaceWith("KiteBlendMode", "io.github.yuroyami.kitepdf.core.render.KiteBlendMode"),
)
public typealias BlendMode = KiteBlendMode

@Deprecated(
    "Renamed to KiteColorSpace",
    ReplaceWith("KiteColorSpace", "io.github.yuroyami.kitepdf.core.render.KiteColorSpace"),
)
public typealias ColorSpace = KiteColorSpace

@Deprecated(
    "Renamed to KiteImageData, which is what both handlers actually produce",
    ReplaceWith("KiteImageData", "io.github.yuroyami.kitepdf.core.render.KiteImageData"),
)
public typealias ImageXObject = KiteImageData

@Deprecated(
    "Renamed to KiteRectangle, and it now lives only in io.github.yuroyami.kitepdf.core",
    ReplaceWith("KiteRectangle", "io.github.yuroyami.kitepdf.core.KiteRectangle"),
)
public typealias Rectangle = KiteRectangle
