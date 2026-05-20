package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.font.PdfFont

/**
 * Graphics state (ISO 32000-1 §8.4).
 *
 * Every PDF content stream runs inside a stack of graphics states. `q` pushes
 * a copy; `Q` pops back. `cm`, color setters, font setters etc. mutate the
 * top of the stack. We model this with an immutable [GraphicsState] data
 * class and a stack on the side (see [GraphicsStack]) — easier to reason
 * about than a mutable struct, and the cost is a small allocation per `q`.
 *
 * Text state (Tm, Tlm, font, size, spacing) is tracked separately on
 * [TextState] and lives only inside `BT…ET` blocks.
 */
data class GraphicsState(
    val ctm: Matrix = Matrix.IDENTITY,
    val strokeColor: RgbColor = RgbColor.BLACK,
    val fillColor: RgbColor = RgbColor.BLACK,
    val lineWidth: Double = 1.0,
    val text: TextState = TextState(),
    /** Current non-stroke colour space (`cs` operator). */
    val fillColorSpace: ColorSpace = ColorSpace.DeviceGray,
    /** Current stroke colour space (`CS` operator). */
    val strokeColorSpace: ColorSpace = ColorSpace.DeviceGray,
    /** Per-pixel alpha multiplier for fills (ExtGState `/ca`), 0..1. */
    val fillAlpha: Double = 1.0,
    /** Per-pixel alpha multiplier for strokes (ExtGState `/CA`), 0..1. */
    val strokeAlpha: Double = 1.0,
    /** Blend mode applied to paints (ExtGState `/BM`). */
    val blendMode: BlendMode = BlendMode.Normal,
    /** Active soft mask (ExtGState `/SMask`); null when none. */
    val softMask: SoftMask? = null,
    /**
     * Active fill pattern — set by `scn` when the fill colour-space is
     * `/Pattern`. When non-null, [fillColor] is ignored and paint operators
     * route through the canvas's gradient/tile path. Cleared by any plain
     * colour setter (`g`, `rg`, `k`, `cs`, `sc`).
     */
    val fillPattern: PdfPattern? = null,
    /** Active stroke pattern — same semantics as [fillPattern] for `SCN`. */
    val strokePattern: PdfPattern? = null,
    /** Dash pattern (`d` operator): on/off lengths in user-space units; null/empty = solid. */
    val dashArray: List<Double>? = null,
    /** Dash phase offset (`d` operator), user-space units. */
    val dashPhase: Double = 0.0,
)

/** Per-`BT/ET` block text state — reset at BT, mutated by text operators. */
data class TextState(
    val font: PdfFont? = null,
    val fontSize: Double = 12.0,
    /** Tm: maps text-space (glyph origin units) to user-space. */
    val textMatrix: Matrix = Matrix.IDENTITY,
    /** Tlm: starting matrix for the *next* line — separate from textMatrix. */
    val lineMatrix: Matrix = Matrix.IDENTITY,
    /** Tc: extra spacing added after each glyph, in unscaled text-space. */
    val charSpacing: Double = 0.0,
    /** Tw: extra spacing added after each space (0x20) glyph. */
    val wordSpacing: Double = 0.0,
    /** Th: horizontal scale, 100.0 = 100 %. */
    val horizontalScaling: Double = 100.0,
    /** TL: distance between baselines, used by T*, ', ". */
    val leading: Double = 0.0,
    /** Ts: baseline offset, positive = raised. */
    val rise: Double = 0.0,
    /** Tr: 0 fill, 1 stroke, 2 fill+stroke, 3 invisible. */
    val renderingMode: Int = 0,
)

/** Simple RGB colour in [0,1]³. Greyscale collapses to all-equal channels. */
data class RgbColor(val r: Double, val g: Double, val b: Double) {
    companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
        val WHITE = RgbColor(1.0, 1.0, 1.0)
        fun gray(g: Double) = RgbColor(g, g, g)
    }
}

/**
 * Merge the non-null fields of [ext] into this state. Spec semantics for
 * `gs <name>`: only entries present in the ExtGState override; everything
 * else passes through.
 */
fun GraphicsState.applyExtGState(ext: ExtGState): GraphicsState = copy(
    fillAlpha = ext.fillAlpha ?: fillAlpha,
    strokeAlpha = ext.strokeAlpha ?: strokeAlpha,
    blendMode = ext.blendMode ?: blendMode,
    softMask = when (ext.softMask) {
        SoftMask.None -> null
        is SoftMask.MaskGroup -> ext.softMask
        null -> softMask
    },
    lineWidth = ext.lineWidth ?: lineWidth,
)

/**
 * Mutable stack façade. Holds the current state plus a save stack for `q`/`Q`.
 *
 * Callers update via the typed mutators (`replaceCtm`, `setFillColor`, etc.)
 * or read the current state directly. The stack is bounded by [maxDepth] to
 * defend against pathological PDFs that q-spam without Q.
 */
class GraphicsStack(initial: GraphicsState = GraphicsState(), private val maxDepth: Int = 64) {
    private val stack = ArrayDeque<GraphicsState>().apply { addLast(initial) }

    val current: GraphicsState
        get() = stack.last()

    fun save() {
        if (stack.size >= maxDepth) return  // silent clamp; production PDFs never need this
        stack.addLast(current)
    }

    fun restore() {
        if (stack.size > 1) stack.removeLast()
    }

    fun replace(next: GraphicsState) {
        stack[stack.size - 1] = next
    }

    fun mutateText(block: (TextState) -> TextState) {
        replace(current.copy(text = block(current.text)))
    }
}
