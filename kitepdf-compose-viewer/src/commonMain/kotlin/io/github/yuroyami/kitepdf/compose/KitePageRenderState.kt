package io.github.yuroyami.kitepdf.compose

/**
 * Where the viewer's raster of one page stands, as [KiteDocViewState.pageRenderState] reports it.
 * A page that failed keeps the last bitmap it had, and [KiteDocViewState.retryPage] renders it
 * again.
 */
public enum class KitePageRenderState {
    /** A raster of the page is on its way. The page shows its last bitmap, or its placeholder. */
    Loading,

    /** The page shows a raster of its current size. */
    Ready,

    /** The last raster failed, for example with an `OutOfMemoryError`. */
    Failed,
}
