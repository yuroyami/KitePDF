package io.github.yuroyami.kitepdf.bundle

/**
 * Marker for the KitePDF umbrella artifact. Depending on the `kitepdf`
 * coordinate pulls every document handler (PDF, EPUB, ...) transitively via
 * `api`; this object just gives the aggregate a home package. Consumers use the
 * handlers' own entry points (e.g. `PdfDocument`, `EpubDocument`) directly.
 */
public object KitePDFBundle {
    /** The umbrella Maven coordinate. */
    public const val COORDINATE: String = "io.github.yuroyami:kitepdf"
}
