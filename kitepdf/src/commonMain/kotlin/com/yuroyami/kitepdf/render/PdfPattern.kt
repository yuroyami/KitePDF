package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfStream

/**
 * Parsed `/Pattern` resource entry (ISO 32000-1 §8.7).
 *
 *   - [Tiling] (`PatternType = 1`) — content-stream-based tiling. Parsed
 *     for completeness; the renderer falls back to the pattern's [Tiling.baseColor].
 *   - [Shading] (`PatternType = 2`) — wraps a [PdfShading] under a matrix.
 *     This is the case the renderer paints as a real gradient.
 */
sealed class PdfPattern {

    /** Pattern-space-to-default-space transform (`/Matrix`). */
    abstract val matrix: Matrix

    /** Optional ExtGState applied while painting the pattern. */
    abstract val extGState: ExtGState?

    data class Shading(
        override val matrix: Matrix,
        override val extGState: ExtGState?,
        val shading: PdfShading,
    ) : PdfPattern()

    /**
     * Tiling pattern (`PatternType = 1`). The content stream lives in
     * [contentBytes]; full rendering requires laying out tiles across the
     * filled region. We don't render that yet — callers get the dict
     * description and an opaque grey fallback in [baseColor].
     */
    data class Tiling(
        override val matrix: Matrix,
        override val extGState: ExtGState?,
        val paintType: Int,
        val tilingType: Int,
        val bbox: com.yuroyami.kitepdf.Rectangle,
        val xStep: Double,
        val yStep: Double,
        val contentBytes: ByteArray,
        val baseColor: RgbColor = RgbColor(0.5, 0.5, 0.5),
    ) : PdfPattern() {
        override fun equals(other: Any?): Boolean = other is Tiling &&
            matrix == other.matrix && paintType == other.paintType &&
            tilingType == other.tilingType && bbox == other.bbox &&
            xStep == other.xStep && yStep == other.yStep &&
            contentBytes.contentEquals(other.contentBytes)
        override fun hashCode(): Int = 31 * matrix.hashCode() + contentBytes.contentHashCode()
    }

    /**
     * A pattern we recognise but can't render yet (e.g. tiling, or one that
     * failed to resolve). Fills using it are skipped rather than collapsing to
     * the default colour — which would flood, say, a full-page background
     * pattern solid black.
     */
    object Unsupported : PdfPattern() {
        override val matrix: Matrix = Matrix.IDENTITY
        override val extGState: ExtGState? = null
    }

    companion object {
        fun parse(
            obj: PdfObject?,
            refs: IndirectResolver,
            shadings: Map<String, PdfShading>,
        ): PdfPattern? {
            val resolved = when (obj) {
                is PdfReference -> refs.resolve(obj)
                else -> obj
            } ?: return null
            val dict = when (resolved) {
                is PdfDictionary -> resolved
                is PdfStream -> resolved.dict
                else -> return null
            }
            val patternType = dict.getInt("PatternType")?.toInt() ?: return null
            val matrix = (dict.getArray("Matrix"))?.toMatrix() ?: Matrix.IDENTITY
            val ext = dict.getDict("ExtGState", refs)?.let { ExtGState.parse(it, refs) }
            return when (patternType) {
                2 -> {
                    val sh = PdfShading.parse(dict["Shading"], refs) ?: return null
                    Shading(matrix, ext, sh)
                }
                1 -> {
                    val stream = resolved as? PdfStream ?: return null
                    val bbox = (dict.getArray("BBox"))?.toRectangle()
                        ?: return null
                    val xStep = (dict.getReal("XStep")) ?: 1.0
                    val yStep = (dict.getReal("YStep")) ?: 1.0
                    val paintType = dict.getInt("PaintType")?.toInt() ?: 1
                    val tilingType = dict.getInt("TilingType")?.toInt() ?: 1
                    Tiling(
                        matrix, ext, paintType, tilingType, bbox, xStep, yStep,
                        com.yuroyami.kitepdf.filters.FilterChain.decode(stream),
                    )
                }
                else -> null
            }.also {
                // Cross-ref the shading by name when the resource map provided one
                // (some PDFs reference shadings indirectly). Touch the unused arg.
                if (shadings.isEmpty()) Unit
            }
        }

        private fun PdfArray.toMatrix(): Matrix {
            fun n(i: Int) = when (val v = this.getOrNull(i)) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return Matrix(n(0), n(1), n(2), n(3), n(4), n(5))
        }

        private fun PdfArray.toRectangle(): com.yuroyami.kitepdf.Rectangle? {
            if (size < 4) return null
            fun n(i: Int) = when (val v = this[i]) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return com.yuroyami.kitepdf.Rectangle(n(0), n(1), n(2), n(3))
        }
    }
}
