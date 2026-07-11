@file:Suppress("unused")

package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.render.KiteCanvas
import io.github.yuroyami.kitepdf.render.KiteFunction
import io.github.yuroyami.kitepdf.render.KitePath
import io.github.yuroyami.kitepdf.render.KitePattern
import io.github.yuroyami.kitepdf.render.KiteShading

/*
 * T-23 migration aliases, one release cycle only: the core substrate types
 * lost their Pdf- prefix (they are format-neutral; EPUB renders through them
 * too). These aliases live in :kitepdf-pdf, not :kitepdf-core, so the core
 * stays clean.
 */

@Deprecated("Renamed to KiteCanvas", ReplaceWith("KiteCanvas", "io.github.yuroyami.kitepdf.render.KiteCanvas"))
public typealias PdfCanvas = KiteCanvas

@Deprecated("Renamed to KitePath", ReplaceWith("KitePath", "io.github.yuroyami.kitepdf.render.KitePath"))
public typealias PdfPath = KitePath

@Deprecated("Renamed to KiteShading", ReplaceWith("KiteShading", "io.github.yuroyami.kitepdf.render.KiteShading"))
public typealias PdfShading = KiteShading

@Deprecated("Renamed to KitePattern", ReplaceWith("KitePattern", "io.github.yuroyami.kitepdf.render.KitePattern"))
public typealias PdfPattern = KitePattern

@Deprecated("Renamed to KiteFunction", ReplaceWith("KiteFunction", "io.github.yuroyami.kitepdf.render.KiteFunction"))
public typealias PdfFunction = KiteFunction
