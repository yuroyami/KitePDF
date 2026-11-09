package io.github.yuroyami.kitepdf.core

/**
 * Marks the raw object-model surface: direct access to xref tables, trailer
 * dictionaries, indirect-object resolution, and the low-level editor
 * primitives. The FILE FORMAT these expose is stable; the Kotlin surface is
 * not, and may change between minor releases without a deprecation cycle.
 * High-level API (pages, outlines, form fields, rendering, search) never
 * requires this opt-in.
 *
 * It covers `IndirectResolver.resolve`, so a call through the interface or any
 * helper that takes the document as a resolver needs it too, and the members
 * that hand out raw dictionaries and references or stage raw objects: the
 * document's `xref`, `trailer`, `rawBytes`, `catalog` and `resolve`, a page's
 * `reference`, `resources` and `annotationReferences`, and the editor's
 * `allocateReference`, `addObject`, `updateObject`, `setTrailerEntry` and
 * `addFlateStream` (#712). The object classes themselves (`PdfDictionary`,
 * `PdfArray` and the rest) are not marked; a caller reaches the graph only
 * through a marked member.
 */
@RequiresOptIn(
    message = "Raw PDF object-model API: stable file format, unstable Kotlin surface. " +
        "May change between minor releases.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class KiteRawApi
