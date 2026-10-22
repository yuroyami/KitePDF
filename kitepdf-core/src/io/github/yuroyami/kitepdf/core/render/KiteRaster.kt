package io.github.yuroyami.kitepdf.core.render

/**
 * Pixels that a [KiteRasterStep] reads and writes: [width] by [height] pixels, row by row from
 * the top, each one an sRGB colour with straight alpha packed as `0xAARRGGBB`. That is the
 * layout of `BufferedImage.TYPE_INT_ARGB` on the JVM and of `Bitmap.getPixels` on Android, so
 * those canvases hand their pixels over without a conversion.
 */
public class KiteRaster(public val width: Int, public val height: Int, public val pixels: IntArray) {

    /** A transparent raster of [width] by [height] pixels. */
    public constructor(width: Int, height: Int) : this(width, height, IntArray(width * height))

    init {
        require(width >= 0 && height >= 0 && width.toLong() * height == pixels.size.toLong()) {
            "a $width by $height raster needs ${width.toLong() * height} pixels, not ${pixels.size}"
        }
    }

    /** The pixel in column [x] of row [y], counted from the top left. */
    public operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** A raster with the same size and its own copy of the pixels. */
    public fun copy(): KiteRaster = KiteRaster(width, height, pixels.copyOf())
}

/**
 * What [KiteCanvas.rasterStep] hands its step: a box of device pixels, and the canvas's own
 * drawing into rasters of that box. A canvas that works at a lower resolution than the device,
 * to stay within its memory budget, gives smaller rasters and says so through [toPixels].
 */
public interface KiteRasterScope {

    /** The width of every raster of this step, in pixels. */
    public val width: Int

    /** The height of every raster of this step, in pixels. */
    public val height: Int

    /**
     * The map from the space of the step's region, the `ctm` given to [KiteCanvas.rasterStep],
     * to the pixels of its rasters: x runs right and y down, and (0, 0) is the top left corner
     * of the top left pixel. A length of one unit in that space covers this many pixels, so a
     * step that blurs or moves by a length in user units scales it through this map.
     */
    public val toPixels: KiteMatrix

    /**
     * What lies under the box now, as the next paint would see it, or null when this canvas
     * cannot read its target back, such as inside a layer of the platform that keeps its
     * pixels to itself. A step that needs the backdrop returns false on null, and the caller
     * then draws without the step.
     */
    public fun backdrop(): KiteRaster?

    /**
     * Paints [content] through the canvas into a new raster of the box. While [content] runs,
     * every paint of the canvas goes to that raster instead of its target, and the content may
     * clip, open groups and apply soft masks as usual. The raster starts as a copy of [initial],
     * which must have this step's size, or transparent when it is null. The clips in force when
     * the step began do not apply here: [draw] applies them.
     */
    public fun render(initial: KiteRaster? = null, content: () -> Unit): KiteRaster

    /**
     * Composites [raster], which must have this step's size, onto the box as one paint in
     * [blendMode] at [alpha] (ISO 32000-1, 11.3.6), inside the clips that were in force when
     * the step began.
     */
    public fun draw(raster: KiteRaster, alpha: Double = 1.0, blendMode: KiteBlendMode = KiteBlendMode.Normal)
}

/** One step on the pixels of a box, which [KiteCanvas.rasterStep] runs. */
public fun interface KiteRasterStep {

    /**
     * Reads and draws through [scope]. Returns false, before it draws anything, when it cannot
     * run here, for example when it needs a backdrop that [KiteRasterScope.backdrop] cannot
     * give. The caller then draws without the step.
     */
    public fun run(scope: KiteRasterScope): Boolean
}
