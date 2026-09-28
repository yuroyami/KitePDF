package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * Draws nothing and notes where the links of an SVG are (#433): the box of what each `<a>` with
 * an `href` draws, and the box of what each element with an `id` draws, cut by the clips open at
 * the time. The walk opens and closes each such element around its content.
 */
internal class SvgLinkCanvas : KiteCanvas {
    val links = ArrayList<Pair<String, KiteRectangle>>()
    val ids = HashMap<String, KiteRectangle>()

    private class Marked(val href: String?, val id: String?) {
        var linkBox: KiteRectangle? = null
        var idBox: KiteRectangle? = null
    }
    private val marked = ArrayList<Marked>()
    // The box of each open clip, cut by the ones outside it. Null is empty.
    private val clips = ArrayList<KiteRectangle?>()

    fun open(href: String?, id: String?) {
        marked += Marked(href, id)
    }

    fun close() {
        val m = marked.removeLastOrNull() ?: return
        m.href?.let { href -> m.linkBox?.let { links += href to it } }
        m.id?.let { id -> m.idBox?.let { ids.getOrPut(id) { it } } }
    }

    /** Adds [box] to the innermost open link and to every open element with an id. */
    private fun mark(box: KiteRectangle?) {
        box ?: return
        val shown = if (clips.isEmpty()) box else clips.last()?.let { cut(box, it) } ?: return
        var linked = false
        for (i in marked.indices.reversed()) {
            val m = marked[i]
            if (m.href != null && !linked) {
                m.linkBox = m.linkBox?.union(shown) ?: shown
                linked = true
            }
            if (m.id != null) m.idBox = m.idBox?.union(shown) ?: shown
        }
    }

    private fun cut(a: KiteRectangle, b: KiteRectangle): KiteRectangle? =
        KiteRectangle(maxOf(a.left, b.left), maxOf(a.bottom, b.bottom), minOf(a.right, b.right), minOf(a.top, b.top))
            .takeIf { it.left <= it.right && it.bottom <= it.top }

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {}
    override fun endPage() {}

    override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) =
        mark(path.bounds(ctm))

    // A stroke reaches half its width past the path on each side.
    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val box = path.bounds() ?: return
        val half = lineWidth.coerceAtLeast(0.0) / 2
        mark(rectangle(box.left - half, box.bottom - half, box.right + half, box.top + half).bounds(ctm))
    }

    // Each glyph's advance, from 0.2 em below the baseline to 0.8 em above it, as text boxes are.
    override fun drawGlyphs(
        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean,
        fontSpec: FontSpec, textToDevice: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
    ) {
        var advance = 0.0
        for (g in glyphs) advance += g.advanceWidth * fontSize / 1000.0 + g.advanceAdjust
        mark(rectangle(0.0, -fontSize * 0.2, advance, fontSize * 0.8).bounds(textToDevice))
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) = mark(rectangle(0.0, 0.0, 1.0, 1.0).bounds(ctm))

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        val box = path.bounds(ctm)
        clips += when {
            clips.isEmpty() -> box
            box == null -> null
            else -> clips.last()?.let { cut(box, it) }
        }
    }

    override fun popClip() {
        clips.removeLastOrNull()
    }

    private fun rectangle(left: Double, bottom: Double, right: Double, top: Double): KitePath =
        KitePath.Builder().apply { rectangle(left, bottom, right - left, top - bottom) }.build()
}
