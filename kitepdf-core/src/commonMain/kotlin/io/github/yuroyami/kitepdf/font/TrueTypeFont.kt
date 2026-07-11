package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.render.PdfPath
import kotlin.math.absoluteValue

/**
 * Pure-Kotlin TrueType / OpenType font parser.
 *
 * Reads the subset of SFNT tables needed to render glyphs: `head`, `maxp`,
 * `hhea`, `hmtx`, `cmap`, `loca`, `glyf`. Composite glyphs are supported with
 * argument transforms; emoji-style colour tables (`COLR`/`CPAL`/`sbix`/`CBDT`)
 * are not yet handled — those are extras layered on top of plain outline
 * data, so without them you still get monochrome shapes.
 *
 * The font keeps the original byte buffer alive; lookups are lazy and cached
 * via [Cache]. That keeps memory flat — most PDFs reference 50–200 glyphs
 * from a font containing thousands.
 */
public class TrueTypeFont private constructor(
    private val reader: TtfReader,
    private val tables: Map<String, Table>,
    public val head: Head,
    public val maxp: Maxp,
    public val hhea: Hhea,
    public val hmtx: Hmtx,
    public val cmap: TtfCMap,
    private val locaOffsets: IntArray,
) {

    /** SFNT version: 0x00010000 (TrueType outlines) or "OTTO" / "true" / "typ1". */
    // Coerce to the spec's valid range (16..16384); a bad value would make
    // fontSize/unitsPerEm blow up to Infinity and render glyphs invisibly.
    public val unitsPerEm: Int get() = head.unitsPerEm.takeIf { it in 16..16384 } ?: 1000
    public val numGlyphs: Int get() = maxp.numGlyphs

    private val cache = HashMap<Int, GlyphOutline?>()
    private val pathCache = HashMap<Int, PdfPath?>()

    /** Convert a unicode codepoint to a glyph index, or 0 (.notdef) if unmapped. */
    public fun glyphIdForCodePoint(codePoint: Int): Int = cmap.glyphIdFor(codePoint)

    /** Glyph advance width in font design units. */
    public fun advanceWidth(glyphId: Int): Int = hmtx.advanceWidth(glyphId)

    /**
     * Long vertical advances from `vhea`/`vmtx` (T-72 vertical writing).
     * Glyphs past `numOfLongVerMetrics` share the last listed advance, per
     * the spec. Null when the face carries no vertical metrics.
     */
    private val vmtxAdvances: IntArray? by lazy {
        val vhea = rawTable("vhea") ?: return@lazy null
        val vmtx = rawTable("vmtx") ?: return@lazy null
        if (vhea.size < 36) return@lazy null
        val numLong = ((vhea[34].toInt() and 0xFF) shl 8) or (vhea[35].toInt() and 0xFF)
        if (numLong <= 0 || vmtx.size < numLong * 4) return@lazy null
        IntArray(numLong) { i -> ((vmtx[i * 4].toInt() and 0xFF) shl 8) or (vmtx[i * 4 + 1].toInt() and 0xFF) }
    }

    /** Glyph advance height in font design units, or null without `vhea`/`vmtx`. */
    public fun advanceHeight(glyphId: Int): Int? {
        val v = vmtxAdvances ?: return null
        if (v.isEmpty() || glyphId < 0) return null
        return if (glyphId < v.size) v[glyphId] else v[v.size - 1]
    }

    /**
     * Raw bytes of the SFNT table [tag] (e.g. `"name"`, `"OS/2"`, `"glyf"`), or
     * null if the font has no such table. Lets the writer's font-embedding path
     * read tables the renderer itself doesn't parse.
     */
    public fun rawTable(tag: String): ByteArray? =
        tables[tag]?.let { reader.slice(it.offset, it.length) }

    /**
     * Raw `glyf` bytes for [glyphId] (empty for an empty/zero-length glyph), used
     * by the subsetter to copy a glyph verbatim into a new font. Does not move the
     * shared reader position ([TtfReader.slice] copies by absolute offset).
     */
    internal fun glyfBytes(glyphId: Int): ByteArray {
        if (glyphId < 0 || glyphId >= maxp.numGlyphs) return EMPTY
        val start = locaOffsets[glyphId]
        val end = locaOffsets[glyphId + 1]
        if (end <= start) return EMPTY
        val glyf = tables["glyf"] ?: return EMPTY
        return reader.slice(glyf.offset + start, end - start)
    }

    /**
     * The component glyph ids a composite [glyphId] references (empty for a simple
     * or empty glyph). The subsetter uses this to pull a composite's dependencies
     * into the subset and to renumber the references.
     */
    internal fun compositeChildGids(glyphId: Int): List<Int> {
        val b = glyfBytes(glyphId)
        if (b.size < 10) return emptyList()
        // numberOfContours < 0 marks a composite glyph.
        if (u16(b, 0).toShort() >= 0) return emptyList()
        val children = ArrayList<Int>(2)
        var pos = 10
        while (pos + 4 <= b.size) {
            val flags = u16(b, pos)
            children.add(u16(b, pos + 2))
            pos += 4
            pos += if (flags and 0x0001 != 0) 4 else 2   // ARGS_ARE_WORDS
            pos += when {                                 // transform
                flags and 0x0008 != 0 -> 2                // WE_HAVE_A_SCALE
                flags and 0x0040 != 0 -> 4                // X_AND_Y_SCALE
                flags and 0x0080 != 0 -> 8                // TWO_BY_TWO
                else -> 0
            }
            if (flags and 0x0020 == 0) break              // MORE_COMPONENTS
        }
        return children
    }

    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    /** Get the outline for [glyphId], or null if the glyph slot is empty (zero-length). */
    public fun outline(glyphId: Int): GlyphOutline? {
        if (cache.containsKey(glyphId)) return cache[glyphId]
        val parsed = parseGlyph(glyphId, depth = 0, active = HashSet())
        cache[glyphId] = parsed
        return parsed
    }

    /** Maximum composite-glyph nesting depth before we abort (malformed-font guard). */
    private val maxCompositeDepth = 8

    /**
     * Outline as a [PdfPath], cached — the `GlyphOutline → PdfPath` conversion
     * is built once per glyph, not on every draw.
     */
    public fun outlinePath(glyphId: Int): PdfPath? {
        if (pathCache.containsKey(glyphId)) return pathCache[glyphId]
        val p = outline(glyphId)?.toPdfPath()
        pathCache[glyphId] = p
        return p
    }

    private fun parseGlyph(glyphId: Int, depth: Int, active: HashSet<Int>): GlyphOutline? {
        if (glyphId < 0 || glyphId >= maxp.numGlyphs) return null
        if (locaOffsets.size <= glyphId + 1) return null
        val start = locaOffsets[glyphId]
        val end = locaOffsets[glyphId + 1]
        if (end <= start) return null   // empty glyph (space, .notdef in many fonts)

        val glyfTable = tables["glyf"] ?: return null
        reader.seek(glyfTable.offset + start)
        val numberOfContours = reader.s16()
        val xMin = reader.s16()
        val yMin = reader.s16()
        val xMax = reader.s16()
        val yMax = reader.s16()
        val bbox = GlyphBbox(xMin, yMin, xMax, yMax)

        return if (numberOfContours >= 0) {
            parseSimpleGlyph(numberOfContours, bbox)
        } else {
            parseCompositeGlyph(glyphId, bbox, depth, active)
        }
    }

    /* ─── Simple glyph (most glyphs) ─────────────────────────────────────── */

    private fun parseSimpleGlyph(numContours: Int, bbox: GlyphBbox): GlyphOutline {
        if (numContours == 0) return GlyphOutline(emptyList(), bbox)

        // endPtsOfContours: numContours entries, last one + 1 = total point count.
        val endPts = IntArray(numContours) { reader.u16() }
        val numPoints = endPts.last() + 1

        // Skip instructions.
        val instructionLength = reader.u16()
        reader.skip(instructionLength)

        // Flags array (variable-length: repeat encoding).
        val flags = IntArray(numPoints)
        var i = 0
        while (i < numPoints) {
            val flag = reader.u8()
            flags[i++] = flag
            if (flag and FLAG_REPEAT != 0) {
                val repeatCount = reader.u8()
                for (r in 0 until repeatCount) {
                    if (i >= numPoints) break
                    flags[i++] = flag
                }
            }
        }

        // X coordinates (deltas from previous; flags determine size and sign).
        val xs = IntArray(numPoints)
        var x = 0
        for (k in 0 until numPoints) {
            val flag = flags[k]
            x += readCoordinate(flag, FLAG_X_SHORT, FLAG_X_SAME_OR_POS)
            xs[k] = x
        }
        val ys = IntArray(numPoints)
        var y = 0
        for (k in 0 until numPoints) {
            val flag = flags[k]
            y += readCoordinate(flag, FLAG_Y_SHORT, FLAG_Y_SAME_OR_POS)
            ys[k] = y
        }

        // Slice points into contours by endPts.
        val contours = mutableListOf<Contour>()
        var startPt = 0
        for (endPt in endPts) {
            val pts = mutableListOf<GlyphPoint>()
            for (k in startPt..endPt) {
                pts.add(GlyphPoint(xs[k], ys[k], onCurve = (flags[k] and FLAG_ON_CURVE) != 0))
            }
            contours.add(Contour(pts))
            startPt = endPt + 1
        }
        return GlyphOutline(contours, bbox)
    }

    /**
     * Composite glyph: a list of (glyph id, transform) entries referring to
     * other glyphs in the same font (e.g. accented characters built from base
     * + accent). We resolve each child via [outline] recursively and apply
     * the embedded 2×3 transform.
     */
    private fun parseCompositeGlyph(
        glyphId: Int,
        bbox: GlyphBbox,
        depth: Int,
        active: HashSet<Int>,
    ): GlyphOutline {
        // Cycle / runaway-recursion guard: a self-referencing or cyclic composite
        // (a malformed-font DoS) would otherwise recurse until StackOverflowError.
        // The public glyph cache is only written after a full parse, so it does
        // not break the cycle on its own — track the active chain here instead.
        if (depth >= maxCompositeDepth || !active.add(glyphId)) {
            return GlyphOutline(emptyList(), bbox)
        }
        try {
            val merged = mutableListOf<Contour>()
            while (true) {
                val flags = reader.u16()
                val childGlyphId = reader.u16()

                // Read the two arguments — they're either x/y offsets (ARGS_ARE_XY_VALUES
                // set) or a pair of point indices for point-matching (flag clear).
                val (arg1, arg2) = if (flags and CG_ARGS_ARE_WORDS != 0) {
                    if (flags and CG_ARGS_ARE_XY_VALUES != 0) reader.s16() to reader.s16()
                    else reader.u16() to reader.u16()
                } else {
                    if (flags and CG_ARGS_ARE_XY_VALUES != 0) reader.s8() to reader.s8()
                    else reader.u8() to reader.u8()
                }
                // Read scale matrix.
                val (sx, sy01, sy10, sy) = when {
                    flags and CG_WE_HAVE_A_SCALE != 0 -> {
                        val s = readF2Dot14()
                        arrayOf(s, 0.0, 0.0, s)
                    }
                    flags and CG_WE_HAVE_X_AND_Y_SCALE != 0 -> {
                        val xS = readF2Dot14(); val yS = readF2Dot14()
                        arrayOf(xS, 0.0, 0.0, yS)
                    }
                    flags and CG_WE_HAVE_TWO_BY_TWO != 0 -> {
                        val a = readF2Dot14(); val b = readF2Dot14()
                        val c = readF2Dot14(); val d = readF2Dot14()
                        arrayOf(a, b, c, d)
                    }
                    else -> arrayOf(1.0, 0.0, 0.0, 1.0)
                }

                // Save reader position; parseGlyph() will jump around in the file.
                val savedPos = reader.pos()
                val child = parseGlyph(childGlyphId, depth + 1, active)
                reader.seek(savedPos)

                child?.let { c ->
                    // Transform the child's points through the 2×2 scale first;
                    // the translation depends on whether we're offsetting or
                    // point-matching, so resolve that below.
                    val scaled = c.contours.map { contour ->
                        contour.points.map {
                            val x = sx * it.x + sy10 * it.y
                            val y = sy01 * it.x + sy * it.y
                            GlyphPoint(x.toInt(), y.toInt(), it.onCurve)
                        }
                    }

                    val (dx, dy) = if (flags and CG_ARGS_ARE_XY_VALUES != 0) {
                        // arg1/arg2 are x/y offsets in the child's (already scaled)
                        // coordinate space — the plain, common case.
                        arg1.toDouble() to arg2.toDouble()
                    } else {
                        // Point-matching: arg1 is a point index into the parent
                        // (points placed so far), arg2 is a point index into this
                        // child. Align so the child point coincides with the parent
                        // point. Indices out of range → no offset (best effort).
                        val parentPt = pointAt(merged.map { it.points }, arg1)
                        val childPt = pointAt(scaled, arg2)
                        if (parentPt != null && childPt != null) {
                            (parentPt.first - childPt.first).toDouble() to
                                (parentPt.second - childPt.second).toDouble()
                        } else {
                            0.0 to 0.0
                        }
                    }

                    for (contour in scaled) {
                        val translated = contour.map {
                            GlyphPoint((it.x + dx).toInt(), (it.y + dy).toInt(), it.onCurve)
                        }
                        merged.add(Contour(translated))
                    }
                }

                if (flags and CG_MORE_COMPONENTS == 0) break
            }
            return GlyphOutline(merged, bbox)
        } finally {
            active.remove(glyphId)
        }
    }

    /**
     * The [n]-th point across all [contours] in order (composite point-matching
     * numbers points sequentially over the whole glyph so far), or null if out of
     * range. Returns raw (x, y) in font design units.
     */
    private fun pointAt(contours: List<List<GlyphPoint>>, n: Int): Pair<Int, Int>? {
        if (n < 0) return null
        var idx = n
        for (pts in contours) {
            if (idx < pts.size) {
                val p = pts[idx]
                return p.x to p.y
            }
            idx -= pts.size
        }
        return null
    }

    private fun readF2Dot14(): Double {
        // 16-bit fixed-point 2.14 — high 2 bits integer, low 14 bits fraction.
        val raw = reader.s16()
        return raw / 16384.0
    }

    /**
     * Read one coordinate component (x or y) per TTF spec §glyf.
     *   shortFlag set    → 1 byte; sameOrPosFlag is sign (1=positive)
     *   shortFlag unset  → if sameOrPos set: same as previous (delta = 0)
     *                      else: 2-byte signed delta
     */
    private fun readCoordinate(flag: Int, shortFlag: Int, sameOrPos: Int): Int {
        return if (flag and shortFlag != 0) {
            val v = reader.u8()
            if (flag and sameOrPos != 0) v else -v
        } else {
            if (flag and sameOrPos != 0) 0 else reader.s16()
        }
    }

    /* ─── Companion: parse(bytes) ─────────────────────────────────────────── */

    public companion object {

        private val EMPTY = ByteArray(0)

        /** Parse a font from a raw `.ttf` / `.otf` byte buffer. */
        public fun parse(bytes: ByteArray): TrueTypeFont {
            val reader = TtfReader(bytes)
            // ── sfnt header ───────────────────────────────────────────────
            val scalerType = reader.u32()
            if (scalerType != 0x00010000L && scalerType != 0x4F54544FL && // "OTTO"
                scalerType != 0x74727565L && scalerType != 0x74797031L      // "true" / "typ1"
            ) {
                throw TtfFormatException("Unrecognised sfnt scaler 0x${scalerType.toString(16)}")
            }
            val numTables = reader.u16()
            reader.skip(6)  // searchRange / entrySelector / rangeShift

            // ── table directory ───────────────────────────────────────────
            val tables = HashMap<String, Table>(numTables)
            repeat(numTables) {
                val tag = reader.tag()
                reader.skip(4)  // checksum
                val offset = reader.s32()
                val length = reader.s32()
                tables[tag] = Table(tag, offset, length)
            }

            // ── required tables ───────────────────────────────────────────
            val head = parseHead(reader, tables.required("head"))
            val maxp = parseMaxp(reader, tables.required("maxp"))
            val hhea = parseHhea(reader, tables.required("hhea"))
            val hmtx = parseHmtx(reader, tables.required("hmtx"), hhea.numberOfHMetrics, maxp.numGlyphs)
            // cmap is optional: CID-keyed fonts (incl. our own subsets) select glyphs
            // via the PDF's /CIDToGIDMap, not the font's cmap, and ship none.
            val cmap = tables["cmap"]?.let { TtfCMap.parse(reader, it) } ?: TtfCMap.empty()
            // glyf/loca are optional too: OpenType/CFF (.otf) fonts carry outlines in a
            // CFF table instead, but still expose cmap/hmtx/head/hhea metrics here.
            val locaOffsets = tables["loca"]
                ?.let { parseLoca(reader, it, head.indexToLocFormat, maxp.numGlyphs) }
                ?: IntArray(0)

            return TrueTypeFont(reader, tables, head, maxp, hhea, hmtx, cmap, locaOffsets)
        }

        private fun parseHead(reader: TtfReader, table: Table): Head {
            reader.seek(table.offset)
            // majorVersion(2) + minorVersion(2) + fontRevision(4) +
            // checkSumAdjustment(4) + magicNumber(4) = 16 bytes before `flags`.
            reader.skip(16)
            val flags = reader.u16()
            val unitsPerEm = reader.u16()
            reader.skip(16)  // created + modified
            val xMin = reader.s16()
            val yMin = reader.s16()
            val xMax = reader.s16()
            val yMax = reader.s16()
            reader.skip(6)  // macStyle + lowestRecPPEM + fontDirectionHint
            val indexToLocFormat = reader.s16()
            return Head(flags, unitsPerEm, xMin, yMin, xMax, yMax, indexToLocFormat)
        }

        private fun parseMaxp(reader: TtfReader, table: Table): Maxp {
            reader.seek(table.offset)
            reader.skip(4)  // version
            return Maxp(numGlyphs = reader.u16())
        }

        private fun parseHhea(reader: TtfReader, table: Table): Hhea {
            reader.seek(table.offset)
            reader.skip(4)  // version
            val ascent = reader.s16()
            val descent = reader.s16()
            val lineGap = reader.s16()
            reader.skip(24)  // various metrics we don't need now
            val numberOfHMetrics = reader.u16()
            return Hhea(ascent, descent, lineGap, numberOfHMetrics)
        }

        private fun parseHmtx(reader: TtfReader, table: Table, numHMetrics: Int, numGlyphs: Int): Hmtx {
            reader.seek(table.offset)
            val widths = IntArray(numGlyphs)
            var lastWidth = 0
            for (i in 0 until numHMetrics) {
                widths[i] = reader.u16()
                lastWidth = widths[i]
                reader.skip(2)  // leftSideBearing (signed; ignored)
            }
            // Glyphs past numHMetrics share the last advance width.
            for (i in numHMetrics until numGlyphs) {
                widths[i] = lastWidth
                // skip remaining lsb entries — they exist but we don't need them
            }
            return Hmtx(widths)
        }

        private fun parseLoca(reader: TtfReader, table: Table, format: Int, numGlyphs: Int): IntArray {
            reader.seek(table.offset)
            val offsets = IntArray(numGlyphs + 1)
            return if (format == 0) {
                // Short format: 16-bit values × 2.
                for (i in 0..numGlyphs) offsets[i] = reader.u16() * 2
                offsets
            } else {
                // Long format: 32-bit values.
                for (i in 0..numGlyphs) offsets[i] = reader.s32()
                offsets
            }
        }

        private fun Map<String, Table>.required(tag: String): Table =
            this[tag] ?: throw TtfFormatException("Required table missing: '$tag'")

        private const val FLAG_ON_CURVE = 0x01
        private const val FLAG_X_SHORT = 0x02
        private const val FLAG_Y_SHORT = 0x04
        private const val FLAG_REPEAT = 0x08
        private const val FLAG_X_SAME_OR_POS = 0x10
        private const val FLAG_Y_SAME_OR_POS = 0x20

        private const val CG_ARGS_ARE_WORDS = 0x0001
        private const val CG_ARGS_ARE_XY_VALUES = 0x0002
        private const val CG_WE_HAVE_A_SCALE = 0x0008
        private const val CG_MORE_COMPONENTS = 0x0020
        private const val CG_WE_HAVE_X_AND_Y_SCALE = 0x0040
        private const val CG_WE_HAVE_TWO_BY_TWO = 0x0080
    }
}

/** SFNT table location in the byte buffer. Public so [TtfCMap] can take one as a parameter. */
public data class Table(val tag: String, val offset: Int, val length: Int)

public data class Head(
    val flags: Int,
    val unitsPerEm: Int,
    val xMin: Int, val yMin: Int, val xMax: Int, val yMax: Int,
    /** 0 = short loca (uint16 × 2), 1 = long loca (uint32). */
    val indexToLocFormat: Int,
)

public data class Maxp(val numGlyphs: Int)

public data class Hhea(val ascent: Int, val descent: Int, val lineGap: Int, val numberOfHMetrics: Int)

public class Hmtx(private val widths: IntArray) {
    public fun advanceWidth(glyphId: Int): Int =
        if (glyphId < 0 || glyphId >= widths.size) 0 else widths[glyphId]
}

/** A single glyph point in font design units. */
public data class GlyphPoint(val x: Int, val y: Int, val onCurve: Boolean)

/** One contour = sequence of points around a closed loop. */
public data class Contour(val points: List<GlyphPoint>)

public data class GlyphBbox(val xMin: Int, val yMin: Int, val xMax: Int, val yMax: Int)

/**
 * Parsed glyph outline. TrueType outlines are sequences of contours, where
 * each contour is points with on/off-curve flags. Off-curve points are
 * quadratic Bézier control points; consecutive off-curve points imply an
 * implied on-curve point at their midpoint.
 *
 * [toPdfPath] is the canonical interpretation that turns these into a
 * sequence of `moveTo`, `lineTo`, `quadTo`, `close` commands.
 *
 * The outline is in font design units (multiply by `fontSize / unitsPerEm`
 * to get PDF user-space units). Y is positive-up — typical TrueType convention.
 */
public data class GlyphOutline(val contours: List<Contour>, val bbox: GlyphBbox) {

    public fun toPdfPath(): PdfPath {
        val b = PdfPath.Builder()
        for (contour in contours) {
            renderContour(contour.points, b)
        }
        return b.build()
    }

    private fun renderContour(points: List<GlyphPoint>, b: PdfPath.Builder) {
        if (points.isEmpty()) return

        // Find a starting on-curve point: if the first point is off-curve we
        // either use the last point (if it's on-curve) or generate an implied
        // midpoint at the start.
        val first = points.first()
        var startIdx = 0
        var startX = first.x.toDouble()
        var startY = first.y.toDouble()
        if (!first.onCurve) {
            val last = points.last()
            if (last.onCurve) {
                startX = last.x.toDouble(); startY = last.y.toDouble()
                startIdx = 0
            } else {
                // Both first and last off-curve — implied midpoint.
                startX = (first.x + last.x) / 2.0
                startY = (first.y + last.y) / 2.0
                startIdx = 0
            }
        } else {
            startIdx = 1
        }

        b.moveTo(startX, startY)

        var curX = startX
        var curY = startY
        var ctrlX = Double.NaN
        var ctrlY = Double.NaN

        // Wrap around: we visit |points| points starting at startIdx.
        for (k in 0 until points.size) {
            val p = points[(startIdx + k) % points.size]
            val px = p.x.toDouble()
            val py = p.y.toDouble()
            if (p.onCurve) {
                if (!ctrlX.isNaN()) {
                    b.quadTo(ctrlX, ctrlY, px, py)
                    ctrlX = Double.NaN
                } else {
                    b.lineTo(px, py)
                }
                curX = px; curY = py
            } else {
                if (!ctrlX.isNaN()) {
                    // Two consecutive off-curve: emit a curve to the midpoint.
                    val mx = (ctrlX + px) / 2.0
                    val my = (ctrlY + py) / 2.0
                    b.quadTo(ctrlX, ctrlY, mx, my)
                    curX = mx; curY = my
                }
                ctrlX = px; ctrlY = py
            }
        }
        // Close the contour. If we ended on an off-curve point, finish back
        // to the start.
        if (!ctrlX.isNaN()) {
            b.quadTo(ctrlX, ctrlY, startX, startY)
        }
        b.close()

        // Silence "unused" — curX/curY are kept for future use (kerning, etc).
        if (curX.absoluteValue < 0) error("unreachable")
        if (curY.absoluteValue < 0) error("unreachable")
    }
}
