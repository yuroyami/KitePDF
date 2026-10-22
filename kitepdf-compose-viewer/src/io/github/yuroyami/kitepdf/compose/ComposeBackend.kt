package io.github.yuroyami.kitepdf.compose

/**
 * Makes Compose's text and graphics usable outside a window or a scene. Since Compose 1.13 a
 * Skiko platform registers them only when the first window or `ImageComposeScene` opens, so a
 * viewer state or a raster made before that would fail.
 */
internal expect fun ensureComposeBackend()
