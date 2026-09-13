package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.font.PdfFont

/**
 * Graphics state (ISO 32000-1 §8.4).
 *
 * Every PDF content stream runs inside a stack of graphics states. `q` pushes
 * a copy; `Q` pops back. `cm`, color setters, font setters etc. mutate the
 * top of the stack. We model this with an immutable [GraphicsState] data
 * class and a stack on the side (see [GraphicsStack]). That is easier to reason
 * about than a mutable struct, and it costs one small allocation per `q`.
 *
 * Text state (Tm, Tlm, font, size, spacing) is tracked separately on
 * [TextState] and lives only inside `BT…ET` blocks.
 */
public data class GraphicsState(
    val ctm: KiteMatrix = KiteMatrix.IDENTITY,
    val strokeColor: RgbColor = RgbColor.BLACK,
    val fillColor: RgbColor = RgbColor.BLACK,
    val lineWidth: Double = 1.0,
    val text: TextState = TextState(),
    /** Current non-stroke colour space (`cs` operator). */
    val fillColorSpace: KiteColorSpace = KiteColorSpace.DeviceGray,
    /** Current stroke colour space (`CS` operator). */
    val strokeColorSpace: KiteColorSpace = KiteColorSpace.DeviceGray,
    /** Per-pixel alpha multiplier for fills (ExtGState `/ca`), 0..1. */
    val fillAlpha: Double = 1.0,
    /** Per-pixel alpha multiplier for strokes (ExtGState `/CA`), 0..1. */
    val strokeAlpha: Double = 1.0,
    /** Blend mode applied to paints (ExtGState `/BM`). */
    val blendMode: KiteBlendMode = KiteBlendMode.Normal,
    /** Active soft mask (ExtGState `/SMask`); null when none. */
    val softMask: SoftMask? = null,
    /** The CTM when [softMask] was set: the mask is drawn under it, never under a later `cm` (#67). */
    val softMaskCtm: KiteMatrix? = null,
    /**
     * Active fill pattern, set by `scn` when the fill colour-space is
     * `/Pattern`. When non-null, [fillColor] is ignored and paint operators
     * route through the canvas's gradient/tile path. Cleared by any plain
     * colour setter (`g`, `rg`, `k`, `cs`, `sc`).
     */
    val fillPattern: KitePattern? = null,
    /** Active stroke pattern: same semantics as [fillPattern] for `SCN`. */
    val strokePattern: KitePattern? = null,
    /** Dash pattern (`d` operator): on/off lengths in user-space units; null/empty = solid. */
    val dashArray: List<Double>? = null,
    /** Dash phase offset (`d` operator), user-space units. */
    val dashPhase: Double = 0.0,
    /** Line cap (`J`): 0 butt, 1 round, 2 projecting square. */
    val lineCap: Int = 0,
    /** Line join (`j`): 0 miter, 1 round, 2 bevel. */
    val lineJoin: Int = 0,
    /** Miter limit (`M`). */
    val miterLimit: Double = 10.0,
)

/** Per-`BT/ET` block text state, reset at BT and mutated by text operators. */
public data class TextState(
    val font: PdfFont? = null,
    val fontSize: Double = 12.0,
    /** Tm: maps text-space (glyph origin units) to user-space. */
    val textMatrix: KiteMatrix = KiteMatrix.IDENTITY,
    /** Tlm: starting matrix for the *next* line, separate from textMatrix. */
    val lineMatrix: KiteMatrix = KiteMatrix.IDENTITY,
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
public data class RgbColor(val r: Double, val g: Double, val b: Double) {
    public companion object {
        public val BLACK: RgbColor = RgbColor(0.0, 0.0, 0.0)
        public val WHITE: RgbColor = RgbColor(1.0, 1.0, 1.0)
        public fun gray(g: Double): RgbColor = RgbColor(g, g, g)
    }
}

/**
 * Merge the non-null fields of [ext] into this state. Spec semantics for
 * `gs <name>`: only entries present in the ExtGState override; everything
 * else passes through.
 */
public fun GraphicsState.applyExtGState(ext: ExtGState): GraphicsState = copy(
    fillAlpha = ext.fillAlpha ?: fillAlpha,
    strokeAlpha = ext.strokeAlpha ?: strokeAlpha,
    blendMode = ext.blendMode ?: blendMode,
    softMask = when (ext.softMask) {
        SoftMask.None -> null
        is SoftMask.MaskGroup -> ext.softMask
        null -> softMask
    },
    softMaskCtm = when (ext.softMask) {
        SoftMask.None -> null
        is SoftMask.MaskGroup -> ctm
        null -> softMaskCtm
    },
    lineWidth = ext.lineWidth ?: lineWidth,
    lineCap = ext.lineCap ?: lineCap,
    lineJoin = ext.lineJoin ?: lineJoin,
    miterLimit = ext.miterLimit ?: miterLimit,
    // /D replaces the dash; an empty or all-zero array means solid, as for d (#107).
    dashArray = if (ext.dashArray == null) dashArray else ext.dashArray.takeIf { ds -> ds.isNotEmpty() && ds.any { it > 0.0 } },
    dashPhase = if (ext.dashArray == null) dashPhase else ext.dashPhase,
)

/**
 * Mutable stack façade. Holds the current state plus a save stack for `q`/`Q`.
 *
 * Callers update via the typed mutators (`replaceCtm`, `setFillColor`, etc.)
 * or read the current state directly. The stack is bounded by [maxDepth], the
 * limit MuPDF uses, to defend against PDFs that q-spam without Q. Past it a
 * save pushes nothing and its matching restore pops nothing, so restores keep
 * pairing with their saves (ISO 32000-1, 8.4.4) instead of each one consuming
 * a real frame (#49).
 */
public class GraphicsStack(initial: GraphicsState = GraphicsState(), private val maxDepth: Int = 4096) {
    private val stack = ArrayDeque<GraphicsState>().apply { addLast(initial) }
    private var overflow = 0

    public val current: GraphicsState
        get() = stack.last()

    public fun save() {
        if (stack.size >= maxDepth) { overflow++; return }
        stack.addLast(current)
    }

    public fun restore() {
        if (overflow > 0) { overflow--; return }
        if (stack.size > 1) stack.removeLast()
    }

    public fun replace(next: GraphicsState) {
        stack[stack.size - 1] = next
    }

    public fun mutateText(block: (TextState) -> TextState) {
        replace(current.copy(text = block(current.text)))
    }
}
