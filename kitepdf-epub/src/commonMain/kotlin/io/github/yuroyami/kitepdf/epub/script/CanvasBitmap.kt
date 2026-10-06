package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KitePath.Segment
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.math.abs
import kotlin.math.roundToLong

/** One clip of a context, a path in canvas pixels and its fill rule. A clip region is a list of them, intersected. */
internal class CanvasClip(val path: KitePath, val evenOdd: Boolean)

/**
 * What a canvas shows, kept as SVG that the page paints as it paints an inline `<svg>` (#501).
 *
 * Drawings go into chunks. An erase, which `clearRect` and `destination-out` make, goes into a
 * list of erasers instead of wrapping what came before, and each chunk is masked once by every
 * eraser after it. So a script that clears part of the canvas on each frame never nests the tree
 * deeper. A composite operation that needs what is already drawn as an image folds the chunks
 * into one group first.
 *
 * An element is never changed once a snapshot holds it, so the layout may keep a snapshot while a
 * script goes on drawing.
 */
internal class CanvasBitmap(var width: Int, var height: Int) {

    private class Chunk(val items: ArrayList<KiteXmlNode.Element>, val erasersFrom: Int)

    private var defs = ArrayList<KiteXmlNode.Element>()
    private var chunks = ArrayList<Chunk>()
    private var erasers = ArrayList<KiteXmlNode.Element>()
    private var openGroup: KiteXmlNode.Element? = null
    private var openClip: List<CanvasClip>? = null
    private val clipIds = HashMap<CanvasClip, String>()
    private var nextId = 0
    private var blends = false

    /** Moves on every change, so the page knows to paint the canvas again. */
    var version: Int = 0
        private set

    val isBlank: Boolean get() = chunks.isEmpty()

    /** A fresh id inside this canvas. */
    fun id(): String = "k${nextId++}"

    /** Adds [el] to the definitions under a fresh id, and answers the id. */
    fun define(el: KiteXmlNode.Element): String {
        val id = id()
        el.attrs = el.attrs + ("id" to id)
        defs.add(el)
        return id
    }

    /** Wipes the canvas, as setting its size or `clearRect` over all of it does. */
    fun clear() {
        defs = ArrayList()
        chunks = ArrayList()
        erasers = ArrayList()
        openGroup = null
        openClip = null
        clipIds.clear()
        blends = false
        version++
    }

    /** Notes that a drawing blends with what is under it, so the canvas paints as an isolated group. */
    fun noteBlend() { blends = true }

    /** Draws [el] over what is there, inside [clip]. */
    fun draw(el: KiteXmlNode.Element, clip: List<CanvasClip>) {
        val chunk = chunks.lastOrNull()?.takeIf { it.erasersFrom == erasers.size }
            ?: Chunk(ArrayList(), erasers.size).also { chunks.add(it); openGroup = null; openClip = null }
        if (clip.isEmpty()) {
            chunk.items.add(el)
            openGroup = null
            openClip = null
        } else if (clip === openClip && openGroup != null) {
            openGroup!!.children.add(el)
        } else {
            var outer: KiteXmlNode.Element? = null
            var inner: KiteXmlNode.Element? = null
            for (c in clip) {
                val g = element("g", "clip-path" to "url(#${clipId(c)})")
                if (inner == null) outer = g else inner.children.add(g)
                inner = g
            }
            inner!!.children.add(el)
            chunk.items.add(outer!!)
            openGroup = inner
            openClip = clip
        }
        version++
    }

    /** Draws [el] under everything already drawn, as `destination-over` does. */
    fun drawUnder(el: KiteXmlNode.Element, clip: List<CanvasClip>) {
        val all = fold()
        chunks = ArrayList()
        erasers = ArrayList()
        draw(el, clip)
        if (all != null) draw(all, emptyList())
    }

    /**
     * Takes away what [silhouette] covers, by its alpha: [silhouette] is black where it erases, so
     * a mask of white with it on top keeps the rest (`destination-out`, `clearRect`).
     */
    fun erase(silhouette: KiteXmlNode.Element, clip: List<CanvasClip>) {
        if (chunks.isEmpty()) return
        erasers.add(wrapClip(silhouette, clip))
        openGroup = null
        openClip = null
        version++
    }

    /**
     * Replaces the whole content with what [compose] builds from it, for the composite operations
     * that read the canvas as an image. [compose] gets the content folded into one group, or null
     * when the canvas is blank, and answers the new content.
     */
    fun recompose(compose: (KiteXmlNode.Element?) -> KiteXmlNode.Element?) {
        val all = fold()
        chunks = ArrayList()
        erasers = ArrayList()
        openGroup = null
        openClip = null
        compose(all)?.let { draw(it, emptyList()) }
        version++
    }

    /** Wraps [el] in one group for each clip of [clip]. */
    fun wrapClip(el: KiteXmlNode.Element, clip: List<CanvasClip>): KiteXmlNode.Element {
        var out = el
        for (c in clip.asReversed()) out = element("g", "clip-path" to "url(#${clipId(c)})").also { it.children.add(out) }
        return out
    }

    private fun clipId(c: CanvasClip): String = clipIds.getOrPut(c) {
        val def = element("clipPath", "clipPathUnits" to "userSpaceOnUse")
        def.children.add(element("path", "d" to pathData(c.path), "clip-rule" to if (c.evenOdd) "evenodd" else "nonzero"))
        define(def)
    }

    /**
     * The content as one group, each chunk masked by the erasers after it, or null when blank. The
     * masks go into [masks] under ids that start with [prefix].
     */
    private fun fold(masks: MutableList<KiteXmlNode.Element>, prefix: String): KiteXmlNode.Element? {
        if (chunks.isEmpty()) return null
        val g = element("g")
        for (chunk in chunks) {
            if (chunk.erasersFrom >= erasers.size) { g.children.addAll(chunk.items); continue }
            val id = prefix + masks.size
            val mask = element(
                "mask", "id" to id, "maskUnits" to "userSpaceOnUse", "maskContentUnits" to "userSpaceOnUse",
                "x" to "0", "y" to "0", "width" to "$width", "height" to "$height",
            )
            mask.children.add(element("rect", "x" to "0", "y" to "0", "width" to "$width", "height" to "$height", "fill" to "#ffffff"))
            for (i in chunk.erasersFrom until erasers.size) mask.children.add(erasers[i])
            masks.add(mask)
            g.children.add(element("g", "mask" to "url(#$id)").also { it.children.addAll(chunk.items) })
        }
        return g
    }

    /** The content folded for good: its masks join the definitions. */
    private fun fold(): KiteXmlNode.Element? {
        val masks = ArrayList<KiteXmlNode.Element>()
        val g = fold(masks, "f${nextId++}m")
        defs.addAll(masks)
        return g
    }

    /**
     * The canvas as an `<svg>` its size, for the layout. Later drawings start new groups, so the
     * tree it answers does not change after.
     */
    fun snapshot(): KiteXmlNode.Element {
        openGroup = null
        openClip = null
        val root = element(
            "svg", "xmlns" to SVG_NS, "width" to "$width", "height" to "$height",
            "viewBox" to "0 0 $width $height", "preserveAspectRatio" to "none",
        )
        val content = content()
        if (content != null) root.children.add(content)
        return root
    }

    /**
     * The content as one group with its definitions inside, clipped to the canvas, for drawing
     * this canvas onto another. Its ids start with [prefix], so they do not meet the other canvas's.
     */
    fun copyFor(prefix: String): KiteXmlNode.Element? = content()?.let { rename(it, prefix) }

    private fun content(): KiteXmlNode.Element? {
        if (chunks.isEmpty()) return null
        val masks = ArrayList<KiteXmlNode.Element>()
        val body = fold(masks, "s") ?: return null
        val frame = element("clipPath", "id" to "frame", "clipPathUnits" to "userSpaceOnUse")
        frame.children.add(element("rect", "x" to "0", "y" to "0", "width" to "$width", "height" to "$height"))
        val g = element("g", "clip-path" to "url(#frame)")
        if (blends) g.attrs = g.attrs + ("style" to "isolation:isolate")
        val defsEl = element("defs")
        defsEl.children.add(frame)
        defsEl.children.addAll(defs)
        defsEl.children.addAll(masks)
        g.children.add(defsEl)
        g.children.addAll(body.children)
        return g
    }

    companion object {
        const val SVG_NS = "http://www.w3.org/2000/svg"

        fun element(tag: String, vararg attrs: Pair<String, String>): KiteXmlNode.Element =
            KiteXmlNode.Element(tag, linkedMapOf(*attrs))

        /** [v] in SVG, to a thousandth of a pixel, the same on every platform. */
        fun num(v: Double): String {
            if (!v.isFinite()) return "0"
            val scaled = (v.coerceIn(-1e12, 1e12) * 1000.0).roundToLong()
            val negative = scaled < 0
            val a = abs(scaled)
            val whole = a / 1000
            val frac = a % 1000
            val body = if (frac == 0L) whole.toString() else whole.toString() + "." + frac.toString().padStart(3, '0').trimEnd('0')
            return if (negative && (whole != 0L || frac != 0L)) "-$body" else body
        }

        fun matrix(m: KiteMatrix): String = "matrix(${num6(m.a)} ${num6(m.b)} ${num6(m.c)} ${num6(m.d)} ${num(m.e)} ${num(m.f)})"

        /** A matrix entry, to a millionth, since a scale of 0.001 matters. */
        private fun num6(v: Double): String {
            if (!v.isFinite()) return "0"
            val scaled = (v.coerceIn(-1e9, 1e9) * 1_000_000.0).roundToLong()
            val a = abs(scaled)
            val whole = a / 1_000_000
            val frac = a % 1_000_000
            val body = if (frac == 0L) whole.toString() else whole.toString() + "." + frac.toString().padStart(6, '0').trimEnd('0')
            return if (scaled < 0) "-$body" else body
        }

        /** [path] as SVG path data. */
        fun pathData(path: KitePath, m: KiteMatrix? = null): String = buildString {
            fun p(x: Double, y: Double) {
                append(num(m?.transformX(x, y) ?: x)).append(' ').append(num(m?.transformY(x, y) ?: y))
            }
            for (s in path.segments) {
                if (isNotEmpty()) append(' ')
                when (s) {
                    is Segment.MoveTo -> { append('M'); p(s.x, s.y) }
                    is Segment.LineTo -> { append('L'); p(s.x, s.y) }
                    is Segment.QuadTo -> { append('Q'); p(s.x1, s.y1); append(' '); p(s.x2, s.y2) }
                    is Segment.CurveTo -> { append('C'); p(s.x1, s.y1); append(' '); p(s.x2, s.y2); append(' '); p(s.x3, s.y3) }
                    Segment.Close -> append('Z')
                }
            }
        }

        /** A deep copy of [el] whose ids, and the references to them, start with [prefix]. */
        fun rename(el: KiteXmlNode.Element, prefix: String): KiteXmlNode.Element {
            val attrs = LinkedHashMap<String, String>()
            for ((k, v) in el.attrs) {
                attrs[k] = when {
                    k == "id" -> prefix + v
                    v.contains("url(#") -> v.replace("url(#", "url(#$prefix")
                    k == "href" && v.startsWith("#") -> "#" + prefix + v.drop(1)
                    else -> v
                }
            }
            val out = KiteXmlNode.Element(el.tag, attrs)
            for (c in el.children) out.children.add(if (c is KiteXmlNode.Element) rename(c, prefix) else c)
            return out
        }
    }
}
