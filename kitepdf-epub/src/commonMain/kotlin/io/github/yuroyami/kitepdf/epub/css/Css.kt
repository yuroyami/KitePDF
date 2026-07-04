package io.github.yuroyami.kitepdf.epub.css

/** Where a rule came from -- decides cascade precedence before specificity. */
internal enum class Origin { UA, AUTHOR, INLINE }

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
)

/** An `@font-face` rule: a named face, its candidate source URLs, and its style. */
internal class FontFaceRule(
    val family: String, // lowercased, unquoted
    val srcUrls: List<String>, // in declared (preference) order
    val bold: Boolean,
    val italic: Boolean,
)

/** Everything the parser pulls from a stylesheet: style rules + `@font-face`s. */
internal class ParsedCss(val rules: List<StyleRule>, val fontFaces: List<FontFaceRule>)
