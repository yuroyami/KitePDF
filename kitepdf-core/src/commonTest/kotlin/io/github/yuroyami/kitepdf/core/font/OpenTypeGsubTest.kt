package io.github.yuroyami.kitepdf.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The GSUB engine on tables built here: every lookup type, lookup flags, scripts and stages (#211). */
class OpenTypeGsubTest {

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = u16(v ushr 16) + u16(v and 0xFFFF)
    private fun tag(t: String) = t.encodeToByteArray()
    private fun cat(parts: List<ByteArray>) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun coverage(vararg gids: Int) = u16(1) + u16(gids.size) + cat(gids.map { u16(it) })

    /** A subtable of [head] fields followed by offset-addressed [blocks]; each `-1` in [head] becomes the offset of the next block. */
    private fun table(head: List<Int>, blocks: List<ByteArray>): ByteArray {
        var offset = head.size * 2
        val offsets = ArrayDeque<Int>()
        for (b in blocks) { offsets.addLast(offset); offset += b.size }
        return cat(head.map { if (it == -1) u16(offsets.removeFirst()) else u16(it) }) + cat(blocks)
    }

    private fun single(from: Int, to: Int) = table(listOf(2, -1, 1, to), listOf(coverage(from)))

    private fun multiple(from: Int, vararg to: Int) =
        table(listOf(1, -1, 1, -1), listOf(coverage(from), u16(to.size) + cat(to.map { u16(it) })))

    /** A ligature of [components] into [lig]. */
    private fun ligature(lig: Int, vararg components: Int): ByteArray {
        val rule = u16(lig) + u16(components.size) + cat(components.drop(1).map { u16(it) })
        val set = u16(1) + u16(4) + rule
        return table(listOf(1, -1, 1, -1), listOf(coverage(components[0]), set))
    }

    /** Chained context, format 3: one coverage per glyph, and (sequence index, lookup) records. */
    private fun chain(back: List<Int>, input: List<Int>, ahead: List<Int>, records: List<Pair<Int, Int>>): ByteArray {
        val head = listOf(3, back.size) + back.map { -1 } + listOf(input.size) + input.map { -1 } +
            listOf(ahead.size) + ahead.map { -1 } + listOf(records.size) + records.flatMap { listOf(it.first, it.second) }
        return table(head, (back + input + ahead).map { coverage(it) })
    }

    /** Context, format 3. */
    private fun context(input: List<Int>, records: List<Pair<Int, Int>>): ByteArray =
        table(listOf(3, input.size, records.size) + input.map { -1 } + records.flatMap { listOf(it.first, it.second) }, input.map { coverage(it) })

    private fun reverse(from: Int, ahead: Int, to: Int) = table(listOf(1, -1, 0, 1, -1, 1, to), listOf(coverage(from), coverage(ahead)))

    private fun lookup(type: Int, subtable: ByteArray, flag: Int = 0) = u16(type) + u16(flag) + u16(1) + u16(8) + subtable

    /** A GSUB table: each script's default language takes the features listed with it. */
    private fun gsub(scripts: List<Pair<String, List<Int>>>, features: List<Pair<String, List<Int>>>, lookups: List<ByteArray>): ByteArray {
        val scriptBodies = scripts.map { (_, fs) -> u16(4) + u16(0) + u16(0) + u16(0xFFFF) + u16(fs.size) + cat(fs.map { u16(it) }) }
        val scriptList = table(listOf(scripts.size) + scripts.flatMap { listOf(-2, -2, -1) }, scriptBodies)
            .let { patchTags(it, scripts.map { it.first }, 2, 6) }
        val featureBodies = features.map { (_, ls) -> u16(0) + u16(ls.size) + cat(ls.map { u16(it) }) }
        val featureList = table(listOf(features.size) + features.flatMap { listOf(-2, -2, -1) }, featureBodies)
            .let { patchTags(it, features.map { it.first }, 2, 6) }
        val lookupList = table(listOf(lookups.size) + lookups.map { -1 }, lookups)
        val header = 10
        return u16(1) + u16(0) + u16(header) + u16(header + scriptList.size) + u16(header + scriptList.size + featureList.size) +
            scriptList + featureList + lookupList
    }

    /** Writes each of [tags] over the four placeholder bytes of its record, [stride] bytes apart from [first]. */
    private fun patchTags(b: ByteArray, tags: List<String>, first: Int, stride: Int): ByteArray {
        val out = b.copyOf()
        for ((i, t) in tags.withIndex()) tag(t).copyInto(out, first + i * stride)
        return out
    }

    /** A GDEF table whose glyph classes are [classes], (glyph, class) pairs in glyph order. */
    private fun gdef(vararg classes: Pair<Int, Int>): ByteArray {
        val classDef = u16(2) + u16(classes.size) + cat(classes.map { (g, c) -> u16(g) + u16(g) + u16(c) })
        return u16(1) + u16(0) + u16(12) + u16(0) + u16(0) + u16(0) + classDef
    }

    private fun run(vararg gids: Int) = gids.mapIndexed { i, g -> GsubGlyph(g, i) }.toMutableList()

    private fun shape(table: OpenTypeGsub, glyphs: MutableList<GsubGlyph>, stages: List<List<String>>, script: String = "DFLT"): List<Int> {
        table.substitute(glyphs, script, null, stages, setOf("init"))
        return glyphs.map { it.gid }
    }

    @Test
    fun a_chained_context_substitutes_only_between_its_neighbours() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(
            listOf("DFLT" to listOf(0)),
            listOf("calt" to listOf(0)),
            listOf(lookup(6, chain(back = listOf(1), input = listOf(2), ahead = listOf(3), records = listOf(0 to 1))), lookup(1, single(2, 20))),
        )))
        assertEquals(listOf(1, 20, 3), shape(t, run(1, 2, 3), listOf(listOf("calt"))))
        assertEquals(listOf(5, 2, 3), shape(t, run(5, 2, 3), listOf(listOf("calt"))), "no backtrack, no substitution")
    }

    @Test
    fun a_nested_ligature_shortens_the_run_and_the_later_records_follow_it() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(
            listOf("DFLT" to listOf(0)),
            listOf("calt" to listOf(0)),
            listOf(
                lookup(5, context(input = listOf(1, 2, 3), records = listOf(0 to 1, 1 to 2))),
                lookup(4, ligature(9, 1, 2)),
                lookup(1, single(3, 30)),
            ),
        )))
        // The ligature joins glyphs 0 and 1, so record 1 now points at the glyph that was 2.
        assertEquals(listOf(9, 30), shape(t, run(1, 2, 3), listOf(listOf("calt"))))
    }

    @Test
    fun a_multiple_substitution_keeps_the_cluster_of_its_glyph() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(listOf("DFLT" to listOf(0)), listOf("ccmp" to listOf(0)), listOf(lookup(2, multiple(5, 6, 7))))))
        val glyphs = run(4, 5, 8)
        assertEquals(listOf(4, 6, 7, 8), shape(t, glyphs, listOf(listOf("ccmp"))))
        assertEquals(listOf(0, 1, 1, 2), glyphs.map { it.cluster })
    }

    @Test
    fun a_ligature_skips_the_marks_its_flag_ignores_and_takes_them_into_its_cluster() {
        val table = gsub(listOf("DFLT" to listOf(0)), listOf("liga" to listOf(0)), listOf(lookup(4, ligature(9, 1, 2), flag = 0x8)))
        val t = assertNotNull(OpenTypeGsub.from(table, gdef(1 to 1, 2 to 1, 50 to 3)))
        val glyphs = run(1, 50, 2)
        assertEquals(listOf(9, 50), shape(t, glyphs, listOf(listOf("liga"))))
        assertEquals(listOf(0, 0), glyphs.map { it.cluster })
        assertEquals(2, glyphs[0].components)
        // Without the flag, the mark breaks the ligature.
        val strict = assertNotNull(OpenTypeGsub.from(gsub(listOf("DFLT" to listOf(0)), listOf("liga" to listOf(0)), listOf(lookup(4, ligature(9, 1, 2)))), gdef(50 to 3)))
        assertEquals(listOf(1, 50, 2), shape(strict, run(1, 50, 2), listOf(listOf("liga"))))
    }

    @Test
    fun a_glyph_a_shaper_inserted_has_no_class_and_blocks_a_ligature() {
        // The ligature passes over base glyphs, and glyph 60 is a base in GDEF.
        val table = gsub(listOf("DFLT" to listOf(0)), listOf("liga" to listOf(0)), listOf(lookup(4, ligature(9, 1, 2), flag = 0x2)))
        val t = assertNotNull(OpenTypeGsub.from(table, gdef(1 to 3, 2 to 3, 60 to 1)))
        assertEquals(listOf(9, 60), shape(t, run(1, 60, 2), listOf(listOf("liga"))))
        // A dotted circle a shaper inserted has no class yet, as in HarfBuzz, so it blocks the ligature.
        val glyphs = run(1, 60, 2).also { it[1].inserted = true }
        assertEquals(listOf(1, 60, 2), shape(t, glyphs, listOf(listOf("liga"))))
    }

    @Test
    fun a_mark_attached_inside_a_ligature_does_not_ligate_with_a_mark_after_it() {
        // Lookup 0 joins bases 1 and 2 over mark 50; lookup 1 would join marks 50 and 51.
        val table = gsub(
            listOf("DFLT" to listOf(0, 1)), listOf("ccmp" to listOf(0), "liga" to listOf(1)),
            listOf(lookup(4, ligature(9, 1, 2), flag = 0x8), lookup(4, ligature(70, 50, 51))),
        )
        val t = assertNotNull(OpenTypeGsub.from(table, gdef(1 to 1, 2 to 1, 9 to 2, 50 to 3, 51 to 3, 70 to 3)))
        // Mark 50 attaches to the first component of ligature 9 and mark 51 to nothing, so they
        // stay apart, as HarfBuzz keeps them.
        assertEquals(listOf(9, 50, 51), shape(t, run(1, 50, 2, 51), listOf(listOf("ccmp"), listOf("liga"))))
        // Without the first ligature, the two marks join.
        assertEquals(listOf(1, 70, 3), shape(t, run(1, 50, 51, 3), listOf(listOf("ccmp"), listOf("liga"))))
    }

    @Test
    fun a_reverse_chain_substitutes_in_place_from_the_end() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(listOf("DFLT" to listOf(0)), listOf("rclt" to listOf(0)), listOf(lookup(8, reverse(1, ahead = 2, to = 11))))))
        assertEquals(listOf(1, 11, 2), shape(t, run(1, 1, 2), listOf(listOf("rclt"))))
    }

    @Test
    fun a_positional_feature_reaches_only_the_glyphs_that_name_it() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(listOf("DFLT" to listOf(0)), listOf("init" to listOf(0)), listOf(lookup(1, single(1, 21))))))
        val glyphs = mutableListOf(GsubGlyph(1, 0, setOf("init")), GsubGlyph(1, 1))
        assertEquals(listOf(21, 1), shape(t, glyphs, listOf(listOf("init"))))
    }

    @Test
    fun the_script_of_the_text_picks_its_language_system() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(
            listOf("arab" to listOf(1), "latn" to listOf(0)),
            listOf("liga" to listOf(0), "liga" to listOf(1)),
            listOf(lookup(1, single(1, 2)), lookup(1, single(1, 3))),
        )))
        assertEquals(listOf(3), shape(t, run(1), listOf(listOf("liga")), script = "arab"))
        assertEquals(listOf(2), shape(t, run(1), listOf(listOf("liga")), script = "latn"))
        assertEquals(listOf(2), shape(t, run(1), listOf(listOf("liga")), script = "grek"), "a script the font lacks falls back to latn")
    }

    @Test
    fun a_stage_applies_before_the_next_whatever_the_lookup_order() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(
            listOf("DFLT" to listOf(0, 1)),
            listOf("liga" to listOf(0), "ccmp" to listOf(1)),
            listOf(lookup(1, single(2, 3)), lookup(1, single(1, 2))),
        )))
        assertEquals(listOf(3), shape(t, run(1), listOf(listOf("ccmp"), listOf("liga"))), "ccmp first, then liga")
        assertEquals(listOf(2), shape(t, run(1), listOf(listOf("ccmp", "liga"))), "one stage runs the lookups in their order")
    }

    @Test
    fun one_feature_still_answers_on_its_own() {
        val t = assertNotNull(OpenTypeGsub.from(gsub(
            listOf("DFLT" to listOf(0, 1)),
            listOf("smcp" to listOf(0), "liga" to listOf(1)),
            listOf(lookup(1, single(4, 40)), lookup(4, ligature(9, 1, 2))),
        )))
        assertEquals(40, t.single("smcp", 4))
        assertEquals(listOf(9), t.ligatures("liga", 1)?.map { it.lig })
    }
}
