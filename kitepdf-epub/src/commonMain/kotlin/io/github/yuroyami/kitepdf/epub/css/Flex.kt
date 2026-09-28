package io.github.yuroyami.kitepdf.epub.css

/** `flex-direction` (CSS Flexible Box Layout 1, 5.1). */
internal enum class FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }

/** `flex-wrap` (5.2). */
internal enum class FlexWrap { NOWRAP, WRAP, WRAP_REVERSE }

/**
 * `justify-content` and `align-content` (8.2, 8.4). STRETCH is also their initial `normal`: a flex
 * container places its items from the start then, and a grid stretches its auto tracks.
 */
internal enum class FlexJustify { START, END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY, STRETCH, LEFT, RIGHT }

/** `align-items` and `align-self` (8.3). AUTO is `align-self`'s initial value: the container's `align-items`. */
internal enum class FlexAlign { AUTO, STRETCH, START, END, CENTER, BASELINE }

/** `flex-basis` (7.2): `auto` takes the item's width, `content` its content, else a length or a share of the container. */
internal sealed class FlexBasis {
    object Auto : FlexBasis()
    object Content : FlexBasis()
    class Length(val pt: Double) : FlexBasis()
    class Percent(val fraction: Double) : FlexBasis()
}

/**
 * The flex properties of one element (#33): those it uses as a flex container, and those it
 * uses as a flex item. None of them is inherited.
 */
internal data class FlexStyle(
    val direction: FlexDirection = FlexDirection.ROW,
    val wrap: FlexWrap = FlexWrap.NOWRAP,
    val justify: FlexJustify = FlexJustify.STRETCH,
    val alignItems: FlexAlign = FlexAlign.STRETCH,
    val alignContent: FlexJustify = FlexJustify.STRETCH,
    val rowGap: Double = 0.0,
    val columnGap: Double = 0.0,
    val grow: Double = 0.0,
    val shrink: Double = 1.0,
    val basis: FlexBasis = FlexBasis.Auto,
    val alignSelf: FlexAlign = FlexAlign.AUTO,
    val order: Int = 0,
) {
    val row: Boolean get() = direction == FlexDirection.ROW || direction == FlexDirection.ROW_REVERSE
    val reverse: Boolean get() = direction == FlexDirection.ROW_REVERSE || direction == FlexDirection.COLUMN_REVERSE
}

/** Reads the flex properties into a [FlexStyle]. A value it does not understand changes nothing. */
internal object FlexValues {

    /** The names this reader handles, with and without the `-webkit-` prefix. */
    val PROPERTIES = setOf(
        "flex-direction", "flex-wrap", "flex-flow", "justify-content", "align-items", "align-self", "align-content",
        "gap", "row-gap", "column-gap", "grid-gap", "grid-row-gap", "grid-column-gap",
        "order", "flex-grow", "flex-shrink", "flex-basis", "flex",
    )

    /**
     * [style] with [prop] set to [value], or null when the value is not valid. [length] reads a
     * length in points, with percentages of the page width, as widths do.
     */
    fun apply(style: FlexStyle, prop: String, value: String, length: (String) -> Double?): FlexStyle? {
        val v = value.trim().lowercase()
        val words = v.split(WHITESPACE).filter { it.isNotEmpty() }
        return when (prop.removePrefix("-webkit-")) {
            "flex-direction" -> direction(v)?.let { style.copy(direction = it) }
            "flex-wrap" -> wrap(v)?.let { style.copy(wrap = it) }
            "flex-flow" -> {
                var s = style
                for (w in words) s = direction(w)?.let { s.copy(direction = it) } ?: wrap(w)?.let { s.copy(wrap = it) } ?: return null
                s
            }
            "justify-content" -> justify(words.lastOrNull() ?: return null)?.let { style.copy(justify = it) }
            "align-content" -> justify(words.lastOrNull() ?: return null)?.let { style.copy(alignContent = it) }
            "align-items" -> align(words.lastOrNull() ?: return null)?.takeIf { it != FlexAlign.AUTO }?.let { style.copy(alignItems = it) }
            "align-self" -> align(words.lastOrNull() ?: return null)?.let { style.copy(alignSelf = it) }
            "gap", "grid-gap" -> {
                val row = gap(words.getOrNull(0) ?: return null, length) ?: return null
                val column = words.getOrNull(1)?.let { gap(it, length) ?: return null } ?: row
                style.copy(rowGap = row, columnGap = column)
            }
            "row-gap", "grid-row-gap" -> gap(v, length)?.let { style.copy(rowGap = it) }
            "column-gap", "grid-column-gap" -> gap(v, length)?.let { style.copy(columnGap = it) }
            "order" -> v.toIntOrNull()?.let { style.copy(order = it) }
            "flex-grow" -> factor(v)?.let { style.copy(grow = it) }
            "flex-shrink" -> factor(v)?.let { style.copy(shrink = it) }
            "flex-basis" -> basis(v, length)?.let { style.copy(basis = it) }
            "flex" -> flex(style, words, length)
            else -> null
        }
    }

    private fun direction(v: String): FlexDirection? = when (v) {
        "row" -> FlexDirection.ROW
        "row-reverse" -> FlexDirection.ROW_REVERSE
        "column" -> FlexDirection.COLUMN
        "column-reverse" -> FlexDirection.COLUMN_REVERSE
        else -> null
    }

    private fun wrap(v: String): FlexWrap? = when (v) {
        "nowrap" -> FlexWrap.NOWRAP
        "wrap" -> FlexWrap.WRAP
        "wrap-reverse" -> FlexWrap.WRAP_REVERSE
        else -> null
    }

    // CSS Box Alignment 3: `safe` and `unsafe` come first, so the keyword is the last word.
    private fun justify(v: String): FlexJustify? = when (v) {
        "normal", "stretch" -> FlexJustify.STRETCH
        "flex-start", "start", "baseline" -> FlexJustify.START
        "flex-end", "end" -> FlexJustify.END
        "center" -> FlexJustify.CENTER
        "space-between" -> FlexJustify.SPACE_BETWEEN
        "space-around" -> FlexJustify.SPACE_AROUND
        "space-evenly" -> FlexJustify.SPACE_EVENLY
        "left" -> FlexJustify.LEFT
        "right" -> FlexJustify.RIGHT
        else -> null
    }

    private fun align(v: String): FlexAlign? = when (v) {
        "auto" -> FlexAlign.AUTO
        "stretch", "normal" -> FlexAlign.STRETCH
        "flex-start", "start", "self-start" -> FlexAlign.START
        "flex-end", "end", "self-end" -> FlexAlign.END
        "center" -> FlexAlign.CENTER
        "baseline" -> FlexAlign.BASELINE
        else -> null
    }

    private fun gap(v: String, length: (String) -> Double?): Double? =
        if (v == "normal") 0.0 else length(v)?.takeIf { it >= 0.0 && it.isFinite() }

    private fun factor(v: String): Double? = v.toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }

    private fun basis(v: String, length: (String) -> Double?): FlexBasis? = when {
        v == "auto" -> FlexBasis.Auto
        v == "content" || v == "max-content" || v == "fit-content" -> FlexBasis.Content
        v.endsWith("%") -> v.dropLast(1).trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }?.let { FlexBasis.Percent(it / 100.0) }
        // A bare zero is a length in the flex shorthand's basis.
        else -> (if (v == "0") 0.0 else length(v))?.takeIf { it >= 0.0 && it.isFinite() }?.let { FlexBasis.Length(it) }
    }

    /** The `flex` shorthand (7.3): `none`, `auto`, `initial`, or grow, shrink and basis in the orders it allows. */
    private fun flex(style: FlexStyle, words: List<String>, length: (String) -> Double?): FlexStyle? {
        when (words.singleOrNull()) {
            "none" -> return style.copy(grow = 0.0, shrink = 0.0, basis = FlexBasis.Auto)
            "auto" -> return style.copy(grow = 1.0, shrink = 1.0, basis = FlexBasis.Auto)
            "initial" -> return style.copy(grow = 0.0, shrink = 1.0, basis = FlexBasis.Auto)
        }
        if (words.isEmpty() || words.size > 3) return null
        val numbers = ArrayList<Double>()
        var basis: FlexBasis? = null
        for (w in words) {
            // A unitless number is a factor while a factor is still open, a unitless zero too (7.3).
            val number = w.toDoubleOrNull()
            if (number != null && numbers.size < 2) {
                if (number < 0.0 || !number.isFinite()) return null
                numbers += number
            } else {
                if (basis != null) return null
                basis = basis(w, length) ?: return null
            }
        }
        // A number and nothing else leaves the basis at 0, so the items share the room by their grow alone.
        return style.copy(
            grow = numbers.getOrElse(0) { 1.0 },
            shrink = numbers.getOrElse(1) { 1.0 },
            basis = basis ?: FlexBasis.Length(0.0),
        )
    }

    private val WHITESPACE = Regex("\\s+")
}
