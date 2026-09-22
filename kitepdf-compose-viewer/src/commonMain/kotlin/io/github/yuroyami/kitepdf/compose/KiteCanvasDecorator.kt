package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.render.KiteCanvas

/**
 * Wraps the page's canvas to filter ink, inspect glyphs or insert drawing calls
 * during the page paint pass. Return a [KiteCanvas] delegating unchanged calls
 * to the supplied canvas. It already includes the optional reader theme, so
 * custom colours pass through that theme too; paper and viewer overlays remain
 * outside the decorator.
 *
 * A fresh wrapper is created for each paint pass. Rasterization can call this
 * on a background thread and repeat the pass on Main for system-font text, so
 * wrappers must be repeatable and must not retain the supplied canvas. A cache
 * hit performs no paint pass. Remember the function in composition for cache
 * reuse, and provide a new function when captured rendering settings change.
 *
 * Coordinates follow [KiteCanvas]'s page/device matrix contract. This is an
 * application rendering hook, independent of the document format.
 */
public typealias KiteCanvasDecorator = (KiteCanvas) -> KiteCanvas
