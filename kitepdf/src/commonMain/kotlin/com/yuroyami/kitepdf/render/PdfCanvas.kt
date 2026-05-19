package com.yuroyami.kitepdf.render

/**
 * Abstract drawing target for PageRenderer.
 *
 * This is intentionally Compose-agnostic: a real implementation can be backed
 * by Compose Multiplatform DrawScope (sample app), Android Canvas, Skia, an
 * SVG writer, or a test recorder. Coordinates are in PDF user-space (1pt =
 * 1/72 inch, origin at bottom-left), and the renderer is responsible for
 * flipping Y axis when feeding into UI toolkits that put origin top-left.
 *
 * Session-1 scope: interface only. PageRenderer wiring lands in Session-2.
 */
interface PdfCanvas {

    /* ─── Path construction ──────────────────────────────────────────────── */
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double)
    fun closePath()
    fun rectangle(x: Double, y: Double, width: Double, height: Double)

    /* ─── Path painting ──────────────────────────────────────────────────── */
    fun strokePath()
    fun fillPath(evenOdd: Boolean = false)
    fun fillAndStrokePath(evenOdd: Boolean = false)
    fun newPath()  // 'n' operator — discard without painting

    /* ─── Graphics state ─────────────────────────────────────────────────── */
    fun saveState()
    fun restoreState()
    fun concatMatrix(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)
    fun setStrokeColorRgb(r: Double, g: Double, b: Double)
    fun setFillColorRgb(r: Double, g: Double, b: Double)
    fun setStrokeColorGray(g: Double)
    fun setFillColorGray(g: Double)
    fun setLineWidth(width: Double)

    /* ─── Text — session-2: real font glyph rendering ────────────────────── */
    fun beginText()
    fun endText()
    fun setFont(name: String, size: Double)
    fun setTextMatrix(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)
    fun textTranslate(tx: Double, ty: Double)
    fun showText(bytes: ByteArray)
}
