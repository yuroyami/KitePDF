package io.github.yuroyami.kitepdf.core

/**
 * Marks the raw object-model surface: direct access to xref tables, trailer
 * dictionaries, indirect-object resolution, and the low-level editor
 * primitives. The FILE FORMAT these expose is stable; the Kotlin surface is
 * not, and may change between minor releases without a deprecation cycle.
 * High-level API (pages, outlines, form fields, rendering, search) never
 * requires this opt-in.
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
