package io.github.yuroyami.kitepdf.epub.css

/**
 * Where a rule came from -- decides cascade precedence before specificity.
 * [READER] carries reader-app overrides built from `EpubSettings` (font
 * family, colors, justification); it ranks above author-important, so the
 * user's explicit preference beats the publisher's stylesheet.
 */
internal enum class Origin { UA, AUTHOR, INLINE, READER }

/** One `property: value` pair, with its `!important` flag. */
internal data class Declaration(val property: String, val value: String, val important: Boolean)

/**
 * A parsed rule: its grouped [selectors] (from a comma list) and [declarations].
 * Source order for the cascade is the rule's index in the resolver's flattened,
 * cascade-ordered list, so it isn't stored here.
 */
internal class StyleRule(
    val selectors: List<Selector>,
    val declarations: List<Declaration>,
    val origin: Origin,
    /** The media query lists of the `@media` blocks around the rule, all of which must match (#841). */
    val media: List<String> = emptyList(),
)

/** An `@font-face` rule: a named face, its candidate source URLs, and its style. */
internal class FontFaceRule(
    val family: String, // lowercased, unquoted
    val srcUrls: List<String>, // in declared (preference) order
    val bold: Boolean,
    val italic: Boolean,
    /** The `font-weight` descriptor as a range, one weight for a single value (CSS Fonts 4, 4.4; #929). */
    val weights: IntRange = if (bold) 700..700 else 400..400,
    /** The `unicode-range` descriptor, or null for every character (CSS Fonts 4, 4.5; #929). */
    val unicodeRanges: List<IntRange>? = null,
)

/** Everything the parser pulls from a stylesheet: style rules + `@font-face`s. */
internal class ParsedCss(val rules: List<StyleRule>, val fontFaces: List<FontFaceRule>)
