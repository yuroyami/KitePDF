package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * T-40: parsing of the stream-based mesh shadings (ISO 32000-1 §8.7.4.5):
 * types 4/5 (Gouraud triangles) into [KiteShading.TriangleMesh] and types 6/7
 * (Coons / tensor patches) into a pre-tessellated [KiteShading.PatchMesh].
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

    private class Layout(dict: PdfDictionary, refs: IndirectResolver, cs: ColorSpace) {
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

        fun mapValue(raw: Long, bits: Int, decodeIdx: Int): Double {
            val max = if (bits >= 63) Long.MAX_VALUE.toDouble() else ((1L shl bits) - 1).toDouble()
            val lo = decode[decodeIdx * 2]
            val hi = decode[decodeIdx * 2 + 1]
            return lo + raw.toDouble() * (hi - lo) / max
        }

        fun readColor(bs: BitStream, cs: ColorSpace): RgbColor {
            val comps = DoubleArray(ncomp) { i -> mapValue(bs.read(bpcomp), bpcomp, 2 + i) }
            return if (function != null) cs.toRgb(function.evaluate(comps))
            else cs.toRgb(comps)
        }

        fun readPoint(bs: BitStream): Pair<Double, Double> {
            val x = mapValue(bs.read(bpc), bpc, 0)
            val y = mapValue(bs.read(bpc), bpc, 1)
            return x to y
        }
    }

    private class Vertex(val x: Double, val y: Double, val color: RgbColor)

    fun parseTriangles(
        type: Int, dict: PdfDictionary, stream: PdfStream,
        cs: ColorSpace, bg: RgbColor?, bbox: Rectangle?, refs: IndirectResolver,
    ): KiteShading? {
        val layout = Layout(dict, refs, cs)
        val bs = BitStream(FilterChain.decode(stream))
        val minVertexBits = layout.bpc * 2L + layout.bpcomp * layout.ncomp
        val triangles = ArrayList<KiteShading.MeshTriangle>()

        fun tri(a: Vertex, b: Vertex, c: Vertex) = KiteShading.MeshTriangle(
            doubleArrayOf(a.x, b.x, c.x),
            doubleArrayOf(a.y, b.y, c.y),
            arrayOf(a.color, b.color, c.color),
        )

        if (type == 5) {
            val perRow = dict.getInt("VerticesPerRow")?.toInt() ?: return null
            if (perRow < 2) return null
            var prev: List<Vertex>? = null
            while (bs.remaining >= minVertexBits * perRow) {
                val row = (0 until perRow).map {
                    val (x, y) = layout.readPoint(bs)
                    Vertex(x, y, layout.readColor(bs, cs))
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
            fun readVertex(): Vertex {
                val (x, y) = layout.readPoint(bs)
                return Vertex(x, y, layout.readColor(bs, cs))
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
        return KiteShading.TriangleMesh(cs, bg, bbox, triangles)
    }

    fun parsePatches(
        type: Int, dict: PdfDictionary, stream: PdfStream,
        cs: ColorSpace, bg: RgbColor?, bbox: Rectangle?, refs: IndirectResolver,
    ): KiteShading? {
        val layout = Layout(dict, refs, cs)
        val bs = BitStream(FilterChain.decode(stream))
        val pointsPerPatch = if (type == 7) 16 else 12
        val quads = ArrayList<KiteShading.FlatQuad>()

        // Previous patch state for edge sharing (12-point Coons numbering:
        // p1..p12 counterclockwise from the bottom-left corner; colours at
        // p1, p4, p7, p10).
        var px: DoubleArray? = null
        var py: DoubleArray? = null
        var pc: Array<RgbColor>? = null

        while (quads.size < MAX_QUADS) {
            if (bs.remaining < layout.bpf) break
            val flag = bs.read(layout.bpf).toInt()
            val newPoints = if (flag == 0) pointsPerPatch else pointsPerPatch - 4
            val newColors = if (flag == 0) 4 else 2
            val neededBits = newPoints * 2L * layout.bpc + newColors.toLong() * layout.ncomp * layout.bpcomp
            if (bs.remaining < neededBits) break

            val x = DoubleArray(12)
            val y = DoubleArray(12)
            val c = arrayOfNulls<RgbColor>(4)
            var readFrom = 0
            if (flag != 0) {
                val ox = px ?: return null
                val oy = py ?: return null
                val oc = pc ?: return null
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
                readFrom = 4
            }
            for (k in readFrom until 12) {
                val (vx, vy) = layout.readPoint(bs)
                x[k] = vx
                y[k] = vy
            }
            // The tensor type's 4 interior points steer exact evaluation only;
            // read them to keep the stream aligned, then ignore (Coons-level
            // approximation, same as MuPDF's tessellation quality target).
            if (type == 7) repeat(4) { layout.readPoint(bs) }
            val colorFrom = if (flag == 0) 0 else 2
            for (k in colorFrom until 4) c[k] = layout.readColor(bs, cs)

            @Suppress("UNCHECKED_CAST")
            val colors = c as Array<RgbColor>
            tessellateCoons(x, y, colors, quads)
            px = x; py = y; pc = colors
        }
        if (quads.isEmpty()) return null
        return KiteShading.PatchMesh(cs, bg, bbox, quads)
    }

    /** Cubic Bézier point. */
    private fun bez(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    /**
     * Coons surface point from the 12 boundary control points. Edges
     * (counterclockwise): bottom p1..p4, right p4..p7, top p7..p10 (right to
     * left), left p10..p1 (top to bottom); corners p1, p4, p7, p10.
     */
    private fun coons(x: DoubleArray, y: DoubleArray, u: Double, v: Double): Pair<Double, Double> {
        fun edgeX(i0: Int, i1: Int, i2: Int, i3: Int, t: Double) = bez(x[i0], x[i1], x[i2], x[i3], t)
        fun edgeY(i0: Int, i1: Int, i2: Int, i3: Int, t: Double) = bez(y[i0], y[i1], y[i2], y[i3], t)
        // Bottom(u): p1->p4, Top(u): p10->p7, Left(v): p1->p10, Right(v): p4->p7.
        val bx = edgeX(0, 1, 2, 3, u); val by = edgeY(0, 1, 2, 3, u)
        val tx = edgeX(9, 8, 7, 6, u); val ty = edgeY(9, 8, 7, 6, u)
        val lx = edgeX(0, 11, 10, 9, v); val ly = edgeY(0, 11, 10, 9, v)
        val rx = edgeX(3, 4, 5, 6, v); val ry = edgeY(3, 4, 5, 6, v)
        val cx = (1 - u) * (1 - v) * x[0] + u * (1 - v) * x[3] + u * v * x[6] + (1 - u) * v * x[9]
        val cy = (1 - u) * (1 - v) * y[0] + u * (1 - v) * y[3] + u * v * y[6] + (1 - u) * v * y[9]
        return ((1 - v) * bx + v * tx + (1 - u) * lx + u * rx - cx) to
            ((1 - v) * by + v * ty + (1 - u) * ly + u * ry - cy)
    }

    private fun tessellateCoons(
        x: DoubleArray, y: DoubleArray, corners: Array<RgbColor>,
        out: MutableList<KiteShading.FlatQuad>,
    ) {
        val n = PATCH_GRID
        // Grid points (n+1)^2 via Coons evaluation.
        val gx = Array(n + 1) { DoubleArray(n + 1) }
        val gy = Array(n + 1) { DoubleArray(n + 1) }
        for (i in 0..n) for (j in 0..n) {
            val (sx, sy) = coons(x, y, i.toDouble() / n, j.toDouble() / n)
            gx[i][j] = sx
            gy[i][j] = sy
        }
        fun lerp(a: RgbColor, b: RgbColor, t: Double) = RgbColor(
            a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t,
        )
        for (i in 0 until n) for (j in 0 until n) {
            val u = (i + 0.5) / n
            val v = (j + 0.5) / n
            // Bilinear corner colours: c1@(0,0)=p1, c2@(1,0)=p4, c3@(1,1)=p7, c4@(0,1)=p10.
            val bottom = lerp(corners[0], corners[1], u)
            val top = lerp(corners[3], corners[2], u)
            val color = lerp(bottom, top, v)
            out.add(
                KiteShading.FlatQuad(
                    doubleArrayOf(gx[i][j], gx[i + 1][j], gx[i + 1][j + 1], gx[i][j + 1]),
                    doubleArrayOf(gy[i][j], gy[i + 1][j], gy[i + 1][j + 1], gy[i][j + 1]),
                    color,
                ),
            )
        }
    }

    private const val PATCH_GRID = 8
    private const val MAX_TRIANGLES = 65_536
    private const val MAX_QUADS = 65_536
}

private fun io.github.yuroyami.kitepdf.parser.PdfArray.numAt(i: Int): Double = when (val v = this[i]) {
    is PdfReal -> v.value
    is PdfInt -> v.value.toDouble()
    else -> 0.0
}

/**
 * Renders the T-40 shading types through plain [KiteCanvas.fillPath] calls, so
 * every backend supports them identically with zero bespoke code:
 *
 *  - [KiteShading.FunctionBased]: a 64x64 cell grid over the domain, each cell
 *    filled with the function's colour at its centre, mapped by the shading
 *    /Matrix.
 *  - [KiteShading.TriangleMesh]: each triangle recursively subdivided (depth
 *    3, 64 sub-triangles) with vertex-colour interpolation — a backend-free
 *    Gouraud approximation smoother than per-triangle flat fill.
 *  - [KiteShading.PatchMesh]: the pre-tessellated flat quads.
 *
 * Returns false for axial/radial/unsupported so the caller proceeds to its
 * native gradient path. [clipPath] (the pattern/`sh` fill region) and the
 * shading /BBox clip via push/popClip around the cells.
 */
public fun KiteCanvas.paintComplexShading(
    shading: KiteShading,
    ctm: Matrix,
    clipPath: KitePath?,
    alpha: Double = 1.0,
    blendMode: BlendMode = BlendMode.Normal,
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
                for (i in 0 until n) for (j in 0 until n) {
                    val cx0 = x0 + (x1 - x0) * i / n
                    val cx1 = x0 + (x1 - x0) * (i + 1) / n
                    val cy0 = y0 + (y1 - y0) * j / n
                    val cy1 = y0 + (y1 - y0) * (j + 1) / n
                    val color = shading.colorAt((cx0 + cx1) / 2, (cy0 + cy1) / 2)
                    val cell = KitePath.Builder().apply {
                        rectangle(cx0, cy0, cx1 - cx0, cy1 - cy0)
                    }.build()
                    fillPath(cell, cellCtm, color, evenOdd = false, alpha = alpha, blendMode = blendMode)
                }
            }
            is KiteShading.TriangleMesh -> {
                for (t in shading.triangles) {
                    subdivideAndFill(
                        t.x[0], t.y[0], t.colors[0],
                        t.x[1], t.y[1], t.colors[1],
                        t.x[2], t.y[2], t.colors[2],
                        depth = 3, ctm = ctm, alpha = alpha, blendMode = blendMode,
                    )
                }
            }
            is KiteShading.PatchMesh -> {
                for (q in shading.quads) {
                    val p = KitePath.Builder().apply {
                        moveTo(q.xs[0], q.ys[0])
                        lineTo(q.xs[1], q.ys[1])
                        lineTo(q.xs[2], q.ys[2])
                        lineTo(q.xs[3], q.ys[3])
                        close()
                    }.build()
                    fillPath(p, ctm, q.color, evenOdd = false, alpha = alpha, blendMode = blendMode)
                }
            }
            else -> Unit
        }
    } finally {
        repeat(clips) { popClip() }
    }
    return true
}

private fun KiteCanvas.subdivideAndFill(
    x0: Double, y0: Double, c0: RgbColor,
    x1: Double, y1: Double, c1: RgbColor,
    x2: Double, y2: Double, c2: RgbColor,
    depth: Int, ctm: Matrix, alpha: Double, blendMode: BlendMode,
) {
    if (depth == 0) {
        val color = RgbColor((c0.r + c1.r + c2.r) / 3, (c0.g + c1.g + c2.g) / 3, (c0.b + c1.b + c2.b) / 3)
        val p = KitePath.Builder().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
            lineTo(x2, y2)
            close()
        }.build()
        fillPath(p, ctm, color, evenOdd = false, alpha = alpha, blendMode = blendMode)
        return
    }
    fun mid(a: RgbColor, b: RgbColor) = RgbColor((a.r + b.r) / 2, (a.g + b.g) / 2, (a.b + b.b) / 2)
    val mx01 = (x0 + x1) / 2; val my01 = (y0 + y1) / 2; val mc01 = mid(c0, c1)
    val mx12 = (x1 + x2) / 2; val my12 = (y1 + y2) / 2; val mc12 = mid(c1, c2)
    val mx20 = (x2 + x0) / 2; val my20 = (y2 + y0) / 2; val mc20 = mid(c2, c0)
    subdivideAndFill(x0, y0, c0, mx01, my01, mc01, mx20, my20, mc20, depth - 1, ctm, alpha, blendMode)
    subdivideAndFill(mx01, my01, mc01, x1, y1, c1, mx12, my12, mc12, depth - 1, ctm, alpha, blendMode)
    subdivideAndFill(mx20, my20, mc20, mx12, my12, mc12, x2, y2, c2, depth - 1, ctm, alpha, blendMode)
    subdivideAndFill(mx01, my01, mc01, mx12, my12, mc12, mx20, my20, mc20, depth - 1, ctm, alpha, blendMode)
}
