package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Parsing of the stream-based mesh shadings (ISO 32000-1 §8.7.4.5):
 * types 4/5 (Gouraud triangles) into [KiteShading.TriangleMesh] and types 6/7
 * (Coons / tensor patches) into [KiteShading.PatchMesh].
 *
 * Vertex data is a continuous big-endian bit stream: per-value widths come
 * from /BitsPerFlag, /BitsPerCoordinate and /BitsPerComponent, and raw values
 * map through the /Decode ranges. With a /Function entry the colour data is a
 * single parametric t; otherwise it is one value per colour component.
 */
internal object MeshShadingParser {

    private class BitStream(private val bytes: ByteArray) {
        private var pos = 0L // bit position
        val remaining: Long get() = bytes.size * 8L - pos

        fun read(bits: Int): Long {
            var v = 0L
            repeat(bits) {
                val byteIdx = (pos ushr 3).toInt()
                val bitIdx = 7 - (pos and 7L).toInt()
                v = (v shl 1) or ((bytes[byteIdx].toInt() ushr bitIdx) and 1).toLong()
                pos++
            }
            return v
        }

        /** Type-4 vertices align to byte boundaries between records. */
        fun alignToByte() {
            pos = (pos + 7) and 7L.inv()
        }
    }

    private class Layout(dict: PdfDictionary, refs: IndirectResolver, private val cs: KiteColorSpace) {
        val bpc = dict.getInt("BitsPerCoordinate")?.toInt() ?: 16
        val bpcomp = dict.getInt("BitsPerComponent")?.toInt() ?: 8
        val bpf = dict.getInt("BitsPerFlag")?.toInt() ?: 8
        val function: KiteFunction? = KiteFunction.parse(dict["Function"], refs)
        val ncomp = if (function != null) 1 else cs.componentCount
        val decode: DoubleArray

        init {
            val arr = dict.getArray("Decode")
            val needed = 2 * (2 + ncomp)
            decode = DoubleArray(needed) { i ->
                if (arr != null && i < arr.size) arr.numAt(i) else if (i % 2 == 0) 0.0 else 1.0
            }
        }

        /** With a /Function, the function sampled over the /Decode range of t, as MuPDF samples it. */
        val colorTable: KiteShading.MeshColorTable? = function?.let { f ->
            val t0 = decode[4]
            val t1 = decode[5]
            val input = DoubleArray(1)
            KiteShading.MeshColorTable(
                t0, t1,
                Array(256) { i ->
                    input[0] = t0 + i / 255.0 * (t1 - t0)
                    cs.toRgb(f.evaluate(input))
                },
            )
        }

        fun mapValue(raw: Long, bits: Int, decodeIdx: Int): Double {
            val max = if (bits >= 63) Long.MAX_VALUE.toDouble() else ((1L shl bits) - 1).toDouble()
            val lo = decode[decodeIdx * 2]
            val hi = decode[decodeIdx * 2 + 1]
            return lo + raw.toDouble() * (hi - lo) / max
        }

        fun readColor(bs: BitStream): VertexColor {
            val comps = DoubleArray(ncomp) { i -> mapValue(bs.read(bpcomp), bpcomp, 2 + i) }
            return if (function != null) VertexColor(cs.toRgb(function.evaluate(comps)), comps[0])
            else VertexColor(cs.toRgb(comps), 0.0)
        }

        fun readPoint(bs: BitStream): Pair<Double, Double> {
            val x = mapValue(bs.read(bpc), bpc, 0)
            val y = mapValue(bs.read(bpc), bpc, 1)
            return x to y
        }
    }

    /** The colour of one vertex, and its parametric value [t] when the mesh has a /Function. */
    private class VertexColor(val rgb: RgbColor, val t: Double)

    private class Vertex(val x: Double, val y: Double, val color: VertexColor)

    fun parseTriangles(
        type: Int, dict: PdfDictionary, stream: PdfStream,
        cs: KiteColorSpace, bg: RgbColor?, bbox: KiteRectangle?, refs: IndirectResolver,
    ): KiteShading? {
        val layout = Layout(dict, refs, cs)
        val bs = BitStream(FilterChain.decode(stream))
        val minVertexBits = layout.bpc * 2L + layout.bpcomp * layout.ncomp
        val triangles = ArrayList<KiteShading.MeshTriangle>()

        fun tri(a: Vertex, b: Vertex, c: Vertex) = KiteShading.MeshTriangle(
            doubleArrayOf(a.x, b.x, c.x),
            doubleArrayOf(a.y, b.y, c.y),
            arrayOf(a.color.rgb, b.color.rgb, c.color.rgb),
            if (layout.function != null) doubleArrayOf(a.color.t, b.color.t, c.color.t) else null,
        )

        if (type == 5) {
            val perRow = dict.getInt("VerticesPerRow")?.toInt() ?: return null
            if (perRow < 2) return null
            var prev: List<Vertex>? = null
            while (bs.remaining >= minVertexBits * perRow) {
                val row = (0 until perRow).map {
                    val (x, y) = layout.readPoint(bs)
                    Vertex(x, y, layout.readColor(bs))
                }
                prev?.let { p ->
                    for (j in 0 until perRow - 1) {
                        triangles.add(tri(p[j], p[j + 1], row[j]))
                        triangles.add(tri(row[j], p[j + 1], row[j + 1]))
                    }
                }
                prev = row
            }
        } else {
            var va: Vertex? = null
            var vb: Vertex? = null
            var vc: Vertex? = null
            // Each type 4 vertex record, flag included, is padded to a byte
            // boundary (ISO 32000-1, 8.7.4.5.5, #157).
            fun readVertex(): Vertex {
                val (x, y) = layout.readPoint(bs)
                val v = Vertex(x, y, layout.readColor(bs))
                bs.alignToByte()
                return v
            }
            while (bs.remaining >= layout.bpf + minVertexBits && triangles.size < MAX_TRIANGLES) {
                val flag = bs.read(layout.bpf).toInt()
                val v = readVertex()
                when (flag) {
                    0 -> {
                        if (bs.remaining < 2 * (layout.bpf + minVertexBits)) break
                        bs.read(layout.bpf) // flags of the 2nd/3rd vertex are 0 by spec
                        val v2 = readVertex()
                        bs.read(layout.bpf)
                        val v3 = readVertex()
                        va = v; vb = v2; vc = v3
                        triangles.add(tri(v, v2, v3))
                    }
                    1 -> {
                        val b = vb ?: return null
                        val c = vc ?: return null
                        va = b; vb = c; vc = v
                        triangles.add(tri(b, c, v))
                    }
                    2 -> {
                        val a = va ?: return null
                        val c = vc ?: return null
                        vb = c; vc = v
                        triangles.add(tri(a, c, v))
                    }
                    else -> return null
                }
            }
        }
        if (triangles.isEmpty()) return null
        return KiteShading.TriangleMesh(cs, bg, bbox, triangles, layout.colorTable)
    }

    fun parsePatches(
        type: Int, dict: PdfDictionary, stream: PdfStream,
        cs: KiteColorSpace, bg: RgbColor?, bbox: KiteRectangle?, refs: IndirectResolver,
    ): KiteShading? {
        val layout = Layout(dict, refs, cs)
        val bs = BitStream(FilterChain.decode(stream))
        val pointsPerPatch = if (type == 7) 16 else 12
        val patches = ArrayList<KiteShading.MeshPatch>()

        // The previous patch, for a patch that shares one of its edges: its points in stream
        // order, which start with the 12 boundary points p1..p12 counterclockwise from the
        // bottom-left corner, and the colours of its corners p1, p4, p7 and p10.
        var px: DoubleArray? = null
        var py: DoubleArray? = null
        var pc: Array<VertexColor>? = null

        while (patches.size < MAX_PATCHES) {
            if (bs.remaining < layout.bpf) break
            val flag = bs.read(layout.bpf).toInt()
            val newPoints = if (flag == 0) pointsPerPatch else pointsPerPatch - 4
            val newColors = if (flag == 0) 4 else 2
            val neededBits = newPoints * 2L * layout.bpc + newColors.toLong() * layout.ncomp * layout.bpcomp
            if (bs.remaining < neededBits) break

            val x = DoubleArray(pointsPerPatch)
            val y = DoubleArray(pointsPerPatch)
            val c = arrayOfNulls<VertexColor>(4)
            val ox = px
            val oy = py
            val oc = pc
            // A patch that shares an edge needs a patch before it, and the flag names one of three edges.
            var shares = false
            if (flag in 1..3 && ox != null && oy != null && oc != null) {
                shares = true
                // Shared edge per spec table 85: the previous patch's edge
                // (flag 1 = right, 2 = top, 3 = left) becomes the new bottom.
                val idx = when (flag) {
                    1 -> intArrayOf(3, 4, 5, 6)   // p4 p5 p6 p7
                    2 -> intArrayOf(6, 7, 8, 9)   // p7 p8 p9 p10
                    else -> intArrayOf(9, 10, 11, 0) // p10 p11 p12 p1
                }
                for (k in 0 until 4) {
                    x[k] = ox[idx[k]]
                    y[k] = oy[idx[k]]
                }
                val cIdx = when (flag) {
                    1 -> intArrayOf(1, 2) // c2 c3
                    2 -> intArrayOf(2, 3) // c3 c4
                    else -> intArrayOf(3, 0) // c4 c1
                }
                c[0] = oc[cIdx[0]]
                c[1] = oc[cIdx[1]]
            }
            for (k in (if (flag == 0) 0 else 4) until pointsPerPatch) {
                val (vx, vy) = layout.readPoint(bs)
                x[k] = vx
                y[k] = vy
            }
            for (k in (if (flag == 0) 0 else 2) until 4) c[k] = layout.readColor(bs)
            // Any other patch with a flag other than 0 is skipped, as MuPDF skips it.
            if (flag != 0 && !shares) continue

            val colors = c.requireNoNulls()
            patches.add(tensorPatch(x, y, colors, layout.function != null))
            px = x; py = y; pc = colors
        }
        if (patches.isEmpty()) return null
        return KiteShading.PatchMesh(cs, bg, bbox, patches, layout.colorTable)
    }

    /**
     * The patch of the points [x], [y] in stream order: the 12 boundary points of ISO 32000-1,
     * Table 85, then, for a tensor-product patch, its 4 interior points (Table 86). A Coons
     * patch gets its interior points from the equations of 8.7.4.5.8, as MuPDF does.
     */
    private fun tensorPatch(x: DoubleArray, y: DoubleArray, colors: Array<VertexColor>, hasFunction: Boolean): KiteShading.MeshPatch {
        val px = DoubleArray(16)
        val py = DoubleArray(16)
        for (k in 0 until 12) {
            px[BOUNDARY[k]] = x[k]
            py[BOUNDARY[k]] = y[k]
        }
        if (x.size == 16) {
            for (k in 0 until 4) {
                px[INTERIOR[k]] = x[12 + k]
                py[INTERIOR[k]] = y[12 + k]
            }
        } else {
            for ((k, terms) in COONS_INTERIOR.withIndex()) {
                px[INTERIOR[k]] = interiorPoint(px, terms)
                py[INTERIOR[k]] = interiorPoint(py, terms)
            }
        }
        return KiteShading.MeshPatch(
            px, py,
            Array(4) { colors[it].rgb },
            if (hasFunction) DoubleArray(4) { colors[it].t } else null,
        )
    }

    /** One interior point of a Coons patch, from the 8 points [terms] names: (-4 a + 6 (b + c) - 2 (d + e) + 3 (f + g) - h) / 9. */
    private fun interiorPoint(p: DoubleArray, terms: IntArray): Double =
        (-4 * p[terms[0]] + 6 * (p[terms[1]] + p[terms[2]]) - 2 * (p[terms[3]] + p[terms[4]]) +
            3 * (p[terms[5]] + p[terms[6]]) - p[terms[7]]) / 9

    /** Where each boundary point of the stream goes: p(i, j) at index 4 i + j. */
    private val BOUNDARY = intArrayOf(0, 1, 2, 3, 7, 11, 15, 14, 13, 12, 8, 4)

    /** Where the interior points p11, p12, p22 and p21 of a type 7 stream go. */
    private val INTERIOR = intArrayOf(5, 6, 10, 9)

    /** The points that make each of p11, p12, p22 and p21 of a Coons patch (ISO 32000-1, 8.7.4.5.8). */
    private val COONS_INTERIOR = arrayOf(
        intArrayOf(0, 1, 4, 3, 12, 13, 7, 15),
        intArrayOf(3, 2, 7, 0, 15, 14, 4, 12),
        intArrayOf(15, 14, 11, 12, 3, 2, 8, 0),
        intArrayOf(12, 13, 8, 15, 0, 1, 11, 3),
    )

    private const val MAX_TRIANGLES = 65_536
    private const val MAX_PATCHES = 65_536
}

private fun io.github.yuroyami.kitepdf.core.parser.PdfArray.numAt(i: Int): Double = when (val v = this[i]) {
    is PdfReal -> v.value
    is PdfInt -> v.value.toDouble()
    else -> 0.0
}

/**
 * Renders the complex shading types, so every backend supports them identically
 * with no code of its own:
 *
 *  - [KiteShading.FunctionBased]: a 64x64 cell grid over the domain, each cell
 *    filled with the function's colour at its centre, mapped by the shading
 *    /Matrix. For an opaque, normal paint, neighbouring cells overlap by about half
 *    a device pixel, so the page does not show through the seams between their
 *    anti-aliased edges.
 *  - [KiteShading.TriangleMesh] and [KiteShading.PatchMesh]: rasterized here into
 *    one image of the device pixels they cover, which the canvas draws with
 *    [KiteCanvas.drawImage]. See [paintMesh].
 *
 * Returns false for axial/radial/unsupported so the caller proceeds to its
 * native gradient path. [clipPath] (the pattern/`sh` fill region) and the
 * shading /BBox clip via push/popClip around the paint.
 */
public fun KiteCanvas.paintComplexShading(
    shading: KiteShading,
    ctm: KiteMatrix,
    clipPath: KitePath?,
    alpha: Double = 1.0,
    blendMode: KiteBlendMode = KiteBlendMode.Normal,
): Boolean {
    when (shading) {
        is KiteShading.FunctionBased, is KiteShading.TriangleMesh, is KiteShading.PatchMesh -> Unit
        else -> return false
    }
    var clips = 0
    if (clipPath != null) {
        pushClip(clipPath, ctm, evenOdd = false)
        clips++
    }
    shading.bbox?.let { b ->
        val p = KitePath.Builder().apply {
            rectangle(b.left, b.bottom, b.right - b.left, b.top - b.bottom)
        }.build()
        pushClip(p, ctm, evenOdd = false)
        clips++
    }
    try {
        when (shading) {
            is KiteShading.FunctionBased -> {
                val n = 64
                val x0 = shading.domain[0]
                val x1 = shading.domain[1]
                val y0 = shading.domain[2]
                val y1 = shading.domain[3]
                val cellCtm = ctm.concat(shading.matrix)
                val xStep = (x1 - x0) / n
                val yStep = (y1 - y0) / n
                // Adjacent anti-aliased fills otherwise expose the page through
                // their shared edges (a visible 64x64 hairline grid). For the
                // normal opaque case, overlap internal edges by half a device
                // pixel. The outer domain still lands exactly on its boundary,
                // and non-normal/translucent paints avoid double compositing.
                //
                // Under shear, the scale perpendicular to an x-edge is
                // |det|/scaleY (and vice versa), hence the inverse formulas.
                // Cap at half a cell so near-singular/subpixel transforms do
                // not let paint order overwhelm the sampled colour.
                val det = kotlin.math.abs(cellCtm.a * cellCtm.d - cellCtm.b * cellCtm.c)
                val scaleX = cellCtm.scaleX()
                val scaleY = cellCtm.scaleY()
                val canOverlap = alpha >= 1.0 && blendMode == KiteBlendMode.Normal &&
                    det.isFinite() && scaleX.isFinite() && scaleY.isFinite() && det > 1e-12
                val overlapX = if (canOverlap) {
                    kotlin.math.min(kotlin.math.abs(xStep) * 0.5, 0.5 * scaleY / det)
                } else {
                    0.0
                }
                val overlapY = if (canOverlap) {
                    kotlin.math.min(kotlin.math.abs(yStep) * 0.5, 0.5 * scaleX / det)
                } else {
                    0.0
                }
                val xDirection = if (xStep < 0.0) -1.0 else 1.0
                val yDirection = if (yStep < 0.0) -1.0 else 1.0
                for (i in 0 until n) for (j in 0 until n) {
                    val cx0 = x0 + xStep * i
                    val cx1 = cx0 + xStep
                    val cy0 = y0 + yStep * j
                    val cy1 = cy0 + yStep
                    val color = shading.colorAt((cx0 + cx1) / 2, (cy0 + cy1) / 2)
                    val left = cx0 - if (i > 0) xDirection * overlapX else 0.0
                    val right = cx1 + if (i + 1 < n) xDirection * overlapX else 0.0
                    val bottom = cy0 - if (j > 0) yDirection * overlapY else 0.0
                    val top = cy1 + if (j + 1 < n) yDirection * overlapY else 0.0
                    val cell = KitePath.Builder().apply {
                        rectangle(left, bottom, right - left, top - bottom)
                    }.build()
                    fillPath(cell, cellCtm, color, evenOdd = false, alpha = alpha, blendMode = blendMode)
                }
            }
            else -> paintMesh(shading, ctm, clipPath, alpha, blendMode)
        }
    } finally {
        repeat(clips) { popClip() }
    }
    return true
}

/**
 * Draws a triangle or patch mesh as one image of the device pixels it covers. Each
 * triangle interpolates the colours of its corners over its pixels, as Gouraud
 * shading does (ISO 32000-1, 8.7.4.5.5). In a mesh with a /Function it
 * interpolates t and looks the colour up in the [KiteShading.MeshColorTable], as MuPDF
 * does. A later triangle paints over an earlier one. One image has no seams between
 * cells, and a translucent or blended paint composites once (#126, #196).
 *
 * A patch is split into 2^d by 2^d quads on its exact tensor-product surface, two
 * triangles each, with one d for the whole mesh so neighbours that share an edge split
 * it at the same points. d is at least 3, MuPDF's 8 by 8, and grows until no quad strays
 * more than [MESH_FLATNESS] device pixels from its corners.
 *
 * The image covers the mesh, cut to [clipPath] and to the /BBox of the shading. Past
 * [MESH_MAX_PIXELS] it is drawn at a lower resolution and scaled up.
 */
private fun KiteCanvas.paintMesh(shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?, alpha: Double, blendMode: KiteBlendMode) {
    val box = DeviceBox()
    var depth = 0
    val table = when (shading) {
        is KiteShading.TriangleMesh -> {
            for (t in shading.triangles) for (k in 0..2) box.add(ctm, t.x[k], t.y[k])
            shading.colorTable
        }
        is KiteShading.PatchMesh -> {
            var worst = 0.0
            for (p in shading.patches) {
                for (k in 0 until 16) box.add(ctm, p.x[k], p.y[k])
                worst = max(worst, patchDeviation(p, ctm))
            }
            depth = MESH_MIN_DEPTH
            while (depth < MESH_MAX_DEPTH && worst / (1 shl (2 * depth)) > MESH_FLATNESS) depth++
            while (depth > 1 && shading.patches.size.toLong() shl (2 * depth) > MESH_MAX_QUADS) depth--
            shading.colorTable
        }
        else -> return
    }
    if (clipPath != null) box.intersect(DeviceBox().apply { addPath(clipPath, ctm) })
    shading.bbox?.let { b ->
        box.intersect(DeviceBox().apply {
            add(ctm, b.left, b.bottom); add(ctm, b.right, b.bottom); add(ctm, b.right, b.top); add(ctm, b.left, b.top)
        })
    }
    val x0 = floor(box.minX)
    val y0 = floor(box.minY)
    val w = ceil(box.maxX) - x0
    val h = ceil(box.maxY) - y0
    if (!(w >= 1.0 && h >= 1.0) || !(w * h).isFinite()) return
    val scale = if (w * h <= MESH_MAX_PIXELS) 1.0 else sqrt(MESH_MAX_PIXELS / (w * h))
    val raster = MeshRaster(x0, y0, scale, ceil(w * scale).toInt().coerceAtLeast(1), ceil(h * scale).toInt().coerceAtLeast(1), table)
    when (shading) {
        is KiteShading.TriangleMesh -> for (t in shading.triangles) raster.triangle(t, ctm)
        is KiteShading.PatchMesh -> for (p in shading.patches) raster.patch(p, ctm, depth)
    }
    val image = raster.image() ?: return
    // The unit square of the image covers the device rectangle of the raster, first row at y0.
    val dw = raster.width / scale
    val dh = raster.height / scale
    val imageCtm = KiteMatrix(dw, 0.0, 0.0, -dh, x0, y0 + dh)
    if (blendMode == KiteBlendMode.Normal) drawImage(image, imageCtm, alpha) else drawImage(image, imageCtm, alpha, blendMode)
}

/** How far the control points of [p] under [ctm] stray from the bilinear surface of its corners, in device units. */
private fun patchDeviation(p: KiteShading.MeshPatch, ctm: KiteMatrix): Double {
    var worst = 0.0
    for (i in 0..3) for (j in 0..3) {
        val s = i / 3.0
        val t = j / 3.0
        val bx = p.x[0] * (1 - s) * (1 - t) + p.x[3] * (1 - s) * t + p.x[15] * s * t + p.x[12] * s * (1 - t)
        val by = p.y[0] * (1 - s) * (1 - t) + p.y[3] * (1 - s) * t + p.y[15] * s * t + p.y[12] * s * (1 - t)
        val dx = p.x[4 * i + j] - bx
        val dy = p.y[4 * i + j] - by
        worst = max(worst, hypot(ctm.a * dx + ctm.c * dy, ctm.b * dx + ctm.d * dy))
    }
    return worst
}

/** A box in device space that grows with each point added. It is empty until the first point. */
private class DeviceBox {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY

    fun add(ctm: KiteMatrix, x: Double, y: Double) {
        val dx = ctm.transformX(x, y)
        val dy = ctm.transformY(x, y)
        minX = min(minX, dx); maxX = max(maxX, dx)
        minY = min(minY, dy); maxY = max(maxY, dy)
    }

    /** Adds every point of [path], control points included, which bound its curves. */
    fun addPath(path: KitePath, ctm: KiteMatrix) {
        for (s in path.segments) when (s) {
            is KitePath.Segment.MoveTo -> add(ctm, s.x, s.y)
            is KitePath.Segment.LineTo -> add(ctm, s.x, s.y)
            is KitePath.Segment.CurveTo -> { add(ctm, s.x1, s.y1); add(ctm, s.x2, s.y2); add(ctm, s.x3, s.y3) }
            is KitePath.Segment.QuadTo -> { add(ctm, s.x1, s.y1); add(ctm, s.x2, s.y2) }
            KitePath.Segment.Close -> Unit
        }
    }

    fun intersect(other: DeviceBox) {
        minX = max(minX, other.minX); maxX = min(maxX, other.maxX)
        minY = max(minY, other.minY); maxY = min(maxY, other.maxY)
    }
}

/**
 * The pixels of a mesh: [width] by [height] of them, where pixel (0, 0) starts at the
 * device point ([x0], [y0]) and one device unit spans [scale] pixels.
 */
private class MeshRaster(
    private val x0: Double,
    private val y0: Double,
    private val scale: Double,
    val width: Int,
    val height: Int,
    private val table: KiteShading.MeshColorTable?,
) {
    private val rgb = ByteArray(width * height * 3)
    private val alpha = ByteArray(width * height)
    private var painted = 0
    private val tableRgb: IntArray? = table?.let { t -> IntArray(t.colors.size) { packed(t.colors[it]) } }

    /** The colour values at the corners of the current triangle: 3 channels each, or 1 for t. */
    private val corners = DoubleArray(9)
    private val longValue = DoubleArray(3)
    private val shortValue = DoubleArray(3)

    fun triangle(t: KiteShading.MeshTriangle, ctm: KiteMatrix) {
        val tv = t.t
        val channels = if (tableRgb != null && tv != null) 1 else 3
        for (k in 0..2) {
            if (channels == 1) corners[k] = tv!![k] else {
                corners[3 * k] = t.colors[k].r
                corners[3 * k + 1] = t.colors[k].g
                corners[3 * k + 2] = t.colors[k].b
            }
        }
        fill(
            ctm.transformX(t.x[0], t.y[0]), ctm.transformY(t.x[0], t.y[0]),
            ctm.transformX(t.x[1], t.y[1]), ctm.transformY(t.x[1], t.y[1]),
            ctm.transformX(t.x[2], t.y[2]), ctm.transformY(t.x[2], t.y[2]),
            corners, 0, channels, 2 * channels, channels,
        )
    }

    /**
     * Splits [p] into 2^[depth] by 2^[depth] quads on its surface and paints each as two
     * triangles. The corner colours spread bilinearly over the patch. The order is MuPDF's:
     * j (the second index of p(i, j)) ascending outside, i descending inside.
     */
    fun patch(p: KiteShading.MeshPatch, ctm: KiteMatrix, depth: Int) {
        val n = 1 shl depth
        val cx = DoubleArray(16) { ctm.transformX(p.x[it], p.y[it]) }
        val cy = DoubleArray(16) { ctm.transformY(p.x[it], p.y[it]) }
        val basis = Array(n + 1) { bernstein(it.toDouble() / n) }
        val tv = p.t
        val channels = if (tableRgb != null && tv != null) 1 else 3
        val corner = Array(4) { k ->
            if (channels == 1) doubleArrayOf(tv!![k]) else p.colors[k].let { doubleArrayOf(it.r, it.g, it.b) }
        }
        val stride = n + 1
        val gridX = DoubleArray(stride * stride)
        val gridY = DoubleArray(stride * stride)
        val values = DoubleArray(stride * stride * channels)
        for (a in 0..n) for (b in 0..n) {
            val wa = basis[a]
            val wb = basis[b]
            var sx = 0.0
            var sy = 0.0
            for (i in 0..3) for (j in 0..3) {
                val w = wa[i] * wb[j]
                sx += w * cx[4 * i + j]
                sy += w * cy[4 * i + j]
            }
            val g = a * stride + b
            gridX[g] = sx
            gridY[g] = sy
            // The corners p(0,0), p(0,3), p(3,3) and p(3,0) sit at (s, t) = (0,0), (0,1), (1,1) and (1,0).
            val s = a.toDouble() / n
            val t = b.toDouble() / n
            for (k in 0 until channels) {
                values[g * channels + k] = corner[0][k] * (1 - s) * (1 - t) + corner[1][k] * (1 - s) * t +
                    corner[2][k] * s * t + corner[3][k] * s * (1 - t)
            }
        }
        for (b in 0 until n) for (a in n - 1 downTo 0) {
            val v0 = a * stride + b
            val v1 = a * stride + b + 1
            val v2 = (a + 1) * stride + b + 1
            val v3 = (a + 1) * stride + b
            fill(gridX[v0], gridY[v0], gridX[v1], gridY[v1], gridX[v3], gridY[v3], values, v0 * channels, v1 * channels, v3 * channels, channels)
            fill(gridX[v3], gridY[v3], gridX[v2], gridY[v2], gridX[v1], gridY[v1], values, v3 * channels, v2 * channels, v1 * channels, channels)
        }
    }

    /**
     * Paints the triangle with device corners a, b and c, whose colour values start at
     * [oa], [ob] and [oc] in [v], [channels] of them each. The pixels follow MuPDF's rule
     * (`fz_paint_triangle` in draw-mesh.c), so a mesh matches the reference to the pixel.
     * A row is painted where its top edge crosses the triangle, from the column of the
     * left crossing up to the column of the right crossing, which is left out. The colour
     * runs linearly from the left crossing to the right one. Triangles that share an edge
     * split its pixels between them, with no gap.
     */
    private fun fill(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
        v: DoubleArray, oa: Int, ob: Int, oc: Int, channels: Int,
    ) {
        // The corners in raster space, in order of y: top 1, middle 2, bottom 3.
        var x1 = (ax - x0) * scale
        var y1 = (ay - y0) * scale
        var o1 = oa
        var x2 = (bx - x0) * scale
        var y2 = (by - y0) * scale
        var o2 = ob
        var x3 = (cx - x0) * scale
        var y3 = (cy - y0) * scale
        var o3 = oc
        if (y2 < y1) { val tx = x1; val ty = y1; val to = o1; x1 = x2; y1 = y2; o1 = o2; x2 = tx; y2 = ty; o2 = to }
        if (y3 < y1) { val tx = x1; val ty = y1; val to = o1; x1 = x3; y1 = y3; o1 = o3; x3 = tx; y3 = ty; o3 = to }
        if (y3 < y2) { val tx = x2; val ty = y2; val to = o2; x2 = x3; y2 = y3; o2 = o3; x3 = tx; y3 = ty; o3 = to }
        if (!(y3 > y1) || !(y3 - y1).isFinite() || !x1.isFinite() || !x2.isFinite() || !x3.isFinite()) return
        val firstRow = max(0.0, ceil(y1)).toInt()
        val endRow = min(height.toDouble(), ceil(y3)).toInt()
        for (row in firstRow until endRow) {
            val y = row.toDouble()
            // The long edge runs from the top corner to the bottom one. The short edge in
            // play is the upper one above the middle corner and the lower one from there on.
            val fl = (y - y1) / (y3 - y1)
            val xl = x1 + (x3 - x1) * fl
            val upper = y < y2
            val sa = if (upper) o1 else o2
            val sb = if (upper) o2 else o3
            val fs = if (upper) (y - y1) / (y2 - y1) else (y - y2) / (y3 - y2)
            val xs = if (upper) x1 + (x2 - x1) * fs else x2 + (x3 - x2) * fs
            for (k in 0 until channels) {
                longValue[k] = v[o1 + k] + (v[o3 + k] - v[o1 + k]) * fl
                shortValue[k] = v[sa + k] + (v[sb + k] - v[sa + k]) * fs
            }
            val longLeft = xl <= xs
            val left = if (longLeft) xl else xs
            val right = if (longLeft) xs else xl
            val leftValue = if (longLeft) longValue else shortValue
            val rightValue = if (longLeft) shortValue else longValue
            val fx0 = floor(left)
            val fx1 = floor(right)
            if (fx0 >= fx1) continue
            val span = fx1 - fx0
            val firstCol = max(0.0, fx0).toInt()
            val endCol = min(width.toDouble(), fx1).toInt()
            for (col in firstCol until endCol) {
                val f = (col - fx0) / span
                val i = row * width + col
                if (channels == 1) {
                    val c = tableRgb!![table!!.indexOf(leftValue[0] + (rightValue[0] - leftValue[0]) * f)]
                    put(i, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                } else {
                    put(
                        i,
                        byte(leftValue[0] + (rightValue[0] - leftValue[0]) * f),
                        byte(leftValue[1] + (rightValue[1] - leftValue[1]) * f),
                        byte(leftValue[2] + (rightValue[2] - leftValue[2]) * f),
                    )
                }
            }
        }
    }

    private fun put(i: Int, r: Int, g: Int, b: Int) {
        rgb[3 * i] = r.toByte()
        rgb[3 * i + 1] = g.toByte()
        rgb[3 * i + 2] = b.toByte()
        if (alpha[i].toInt() == 0) {
            alpha[i] = 0xFF.toByte()
            painted++
        }
    }

    /** The painted pixels as an image, with an alpha plane when some are not painted, or null when none are. */
    fun image(): KiteImageData? {
        if (painted == 0) return null
        val mask = if (painted < width * height) alpha else null
        return KiteImageData(
            width = width, height = height, bitsPerComponent = 8,
            colorSpace = "DeviceRGB", kind = KiteImageData.Kind.RAW,
            encodedBytes = ByteArray(0), pixelBytes = rgb,
            softMaskAlpha = mask,
            softMaskWidth = if (mask != null) width else 0,
            softMaskHeight = if (mask != null) height else 0,
            resolvedColorSpace = KiteColorSpace.DeviceRGB,
            interpolate = true,
        )
    }

    private companion object {
        /** A colour value as a byte, truncated as MuPDF truncates it. */
        fun byte(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).toInt()

        fun packed(c: RgbColor): Int = (byte(c.r) shl 16) or (byte(c.g) shl 8) or byte(c.b)

        /** The 4 cubic Bernstein weights at [u]. */
        fun bernstein(u: Double): DoubleArray {
            val v = 1 - u
            return doubleArrayOf(v * v * v, 3 * u * v * v, 3 * u * u * v, u * u * u)
        }
    }
}

/** The fewest halvings of a patch in each direction: MuPDF's fixed 3, or 8 by 8 quads. */
private const val MESH_MIN_DEPTH = 3

/** The most halvings of a patch in each direction: 64 by 64 quads. */
private const val MESH_MAX_DEPTH = 6

/** How far, in device pixels, a quad of a patch may stray from the straight lines between its corners. */
private const val MESH_FLATNESS = 0.5

/** The most quads a patch mesh splits into, all patches together. A mesh with more patches splits each less. */
private const val MESH_MAX_QUADS = 1L shl 19

/** The most pixels in the image of one mesh, about 1448 by 1448. */
private const val MESH_MAX_PIXELS = 2_097_152.0
