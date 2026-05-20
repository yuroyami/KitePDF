package com.yuroyami.kitepdf.render

/**
 * PDF blend modes (ISO 32000-1 §11.3.5).
 *
 * 16 standard modes: 12 *separable* (each colour channel computed independently)
 * and 4 *non-separable* (operate on HSL). The renderer maps these to the
 * backend's native blend modes — Compose Multiplatform exposes most of them
 * via `androidx.compose.ui.graphics.BlendMode`.
 *
 * Unknown / non-blending modes fall back to [Normal].
 */
enum class BlendMode {
    Normal,
    Multiply, Screen, Overlay, Darken, Lighten,
    ColorDodge, ColorBurn, HardLight, SoftLight,
    Difference, Exclusion,
    Hue, Saturation, Color, Luminosity;

    companion object {
        /** Parse a PDF `/BM` name. Arrays of names — take the first one we recognise. */
        fun parse(name: String?): BlendMode = when (name) {
            "Normal", "Compatible" -> Normal
            "Multiply" -> Multiply
            "Screen" -> Screen
            "Overlay" -> Overlay
            "Darken" -> Darken
            "Lighten" -> Lighten
            "ColorDodge" -> ColorDodge
            "ColorBurn" -> ColorBurn
            "HardLight" -> HardLight
            "SoftLight" -> SoftLight
            "Difference" -> Difference
            "Exclusion" -> Exclusion
            "Hue" -> Hue
            "Saturation" -> Saturation
            "Color" -> Color
            "Luminosity" -> Luminosity
            else -> Normal
        }
    }
}
