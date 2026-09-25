package io.github.yuroyami.kitepdf.core.render

/**
 * How a colour in an ICC-based colour space converts to the screen (ISO 32000-1, 8.6.5.8).
 * Only a profile with a lookup table per intent converts differently for each one, and
 * [RelativeColorimetric] is the default. The order is the ICC order, from 0 to 3.
 */
public enum class KiteRenderingIntent {
    /** Compresses the gamut of the source into the gamut of the screen. */
    Perceptual,

    /** Keeps the colours inside both gamuts, and maps the white of the medium to white. */
    RelativeColorimetric,

    /** Keeps the colours saturated at the cost of their accuracy. */
    Saturation,

    /** Keeps the colours inside both gamuts, and keeps the colour of the medium, such as paper. */
    AbsoluteColorimetric;

    public companion object {
        /** The intent that a PDF name stands for. A name this does not know means [RelativeColorimetric] (8.6.5.8). */
        public fun fromPdfName(name: String?): KiteRenderingIntent = when (name) {
            "Perceptual" -> Perceptual
            "Saturation" -> Saturation
            "AbsoluteColorimetric" -> AbsoluteColorimetric
            else -> RelativeColorimetric
        }
    }
}
