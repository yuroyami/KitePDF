package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.math.abs

/**
 * A Type 1 or CFF `FontMatrix` from its six numbers, or null when it is absent or
 * unusable: fewer than six numbers, a number that is not finite, or no area.
 * A null matrix means the default, `[0.001 0 0 0.001 0 0]`.
 */
internal fun fontMatrixOf(values: List<Double>?): KiteMatrix? {
    if (values == null || values.size < 6) return null
    if (values.take(6).any { !it.isFinite() }) return null
    val m = KiteMatrix(values[0], values[1], values[2], values[3], values[4], values[5])
    return m.takeIf { it.a * it.d - it.b * it.c != 0.0 }
}

/**
 * The map from a font program's own units to PDF glyph space, where an em is 1000
 * units (ISO 32000-1, 9.2.4). It is [fontMatrix] scaled by 1000, or null when that is
 * the identity, as it is for the default matrix.
 */
internal fun glyphSpaceMatrix(fontMatrix: KiteMatrix?): KiteMatrix? {
    val m = fontMatrix ?: return null
    val g = KiteMatrix(m.a * 1000, m.b * 1000, m.c * 1000, m.d * 1000, m.e * 1000, m.f * 1000)
    val identity = abs(g.a - 1) < 1e-9 && abs(g.d - 1) < 1e-9 && abs(g.b) < 1e-9 && abs(g.c) < 1e-9 &&
        abs(g.e) < 1e-6 && abs(g.f) < 1e-6
    return g.takeUnless { identity }
}

/** This path with every point mapped through [m]. */
internal fun KitePath.mappedBy(m: KiteMatrix): KitePath = KitePath(
    segments.map { s ->
        when (s) {
            is KitePath.Segment.MoveTo -> KitePath.Segment.MoveTo(m.transformX(s.x, s.y), m.transformY(s.x, s.y))
            is KitePath.Segment.LineTo -> KitePath.Segment.LineTo(m.transformX(s.x, s.y), m.transformY(s.x, s.y))
            is KitePath.Segment.CurveTo -> KitePath.Segment.CurveTo(
                m.transformX(s.x1, s.y1), m.transformY(s.x1, s.y1),
                m.transformX(s.x2, s.y2), m.transformY(s.x2, s.y2),
                m.transformX(s.x3, s.y3), m.transformY(s.x3, s.y3),
            )
            is KitePath.Segment.QuadTo -> KitePath.Segment.QuadTo(
                m.transformX(s.x1, s.y1), m.transformY(s.x1, s.y1),
                m.transformX(s.x2, s.y2), m.transformY(s.x2, s.y2),
            )
            KitePath.Segment.Close -> s
        }
    },
)
