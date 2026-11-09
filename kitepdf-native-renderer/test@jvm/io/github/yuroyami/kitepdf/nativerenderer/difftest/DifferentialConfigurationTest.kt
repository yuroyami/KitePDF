package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DifferentialConfigurationTest {

    @Test
    fun page_limit_must_be_a_positive_integer() {
        assertEquals(6, DiffHarness.parseMaxPages(null))
        assertEquals(2, DiffHarness.parseMaxPages("2"))

        listOf("0", "-1", "not-a-number").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                DiffHarness.parseMaxPages(value)
            }
        }
    }

    @Test
    fun dpi_must_be_a_positive_integer() {
        assertEquals(DiffHarness.DEFAULT_DPI, DifferentialTest.parseDpi(null))
        assertEquals(144, DifferentialTest.parseDpi("144"))

        listOf("0", "-1", "not-a-number").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                DifferentialTest.parseDpi(value)
            }
        }
    }

    @Test
    fun diff_budget_must_be_finite_and_normalized() {
        assertEquals(0.05, DifferentialTest.parseBudget(null))
        assertEquals(0.20, DifferentialTest.parseBudget("0.20"))

        listOf("NaN", "Infinity", "-0.1", "1.1", "not-a-number").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                DifferentialTest.parseBudget(value)
            }
        }
    }

    @Test
    fun explicit_corpus_path_must_be_an_existing_directory() {
        val root = Files.createTempDirectory("kite-corpus-config-test-").toFile()
        try {
            val corpus = File(root, "corpus").apply { mkdirs() }
            assertEquals(
                corpus,
                Corpus.resolveCorpusDirectory("kitepdf.corpus", corpus.absolutePath, fallback = null),
            )

            assertFailsWith<IllegalArgumentException> {
                Corpus.resolveCorpusDirectory(
                    "kitepdf.corpus",
                    File(root, "missing").absolutePath,
                    fallback = corpus,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun a_page_fails_only_when_it_scores_clearly_worse_than_its_baseline() {
        val base = DiffBaseline.Score(0.001, 0.004, 40)
        assertNull(DiffBaseline.regression(base, base))
        assertNull(DiffBaseline.regression(base, DiffBaseline.Score(0.0017, 0.0069, 88)), "within every margin")
        assertNotNull(DiffBaseline.regression(base, DiffBaseline.Score(0.0018, 0.004, 40)), "mean error")
        assertNotNull(DiffBaseline.regression(base, DiffBaseline.Score(0.001, 0.0071, 40)), "changed pixels")
        assertNotNull(DiffBaseline.regression(base, DiffBaseline.Score(0.001, 0.004, 89)), "largest channel error")
        assertTrue(DiffBaseline.improved(base, DiffBaseline.Score(0.0, 0.004, 40)))
        assertFalse(DiffBaseline.improved(base, DiffBaseline.Score(0.0008, 0.004, 40)))
    }

    /** The issue's case: a pale square lost from syn-text moved none of the three page scores past its margin (#775). */
    @Test
    fun a_small_pale_shape_that_went_missing_fails_its_tile() {
        val base = DiffBaseline.Score(0.00015, 0.00096, 31, tile = 0.004)
        val lost = DiffBaseline.Score(0.00043, 0.00281, 39, tile = 0.15)
        assertNull(DiffBaseline.regression(base.copy(tile = null), lost), "the three page scores let it pass")
        assertNotNull(DiffBaseline.regression(base, lost), "the tile fails it")
        assertNull(DiffBaseline.regression(base, base.copy(tile = 0.02)), "within the tile margin")
        assertNull(DiffBaseline.regression(base, lost.copy(tile = null)), "a page with no tile score recorded is not checked by it")
    }

    @Test
    fun rewriting_the_baseline_keeps_corpus_documents_and_drops_removed_fixtures() {
        val f = Files.createTempFile("kite-baseline-", ".txt").toFile()
        try {
            val old = DiffBaseline.Score(0.00100, 0.00200, 30)
            val now = DiffBaseline.Score(0.00050, 0.00100, 20)
            DiffBaseline.write(
                f,
                old = mapOf("gone p0" to old, "kept p0" to old, "sha1-0123456789ab p2" to old),
                current = mapOf("kept p0" to now, "kept p10" to now, "kept p1" to now),
                fixtures = setOf("kept"),
            )
            val read = DiffBaseline.read(f)
            assertEquals(listOf("kept p0", "kept p1", "kept p10", "sha1-0123456789ab p2"), read.keys.toList())
            assertEquals(now, read["kept p0"])
            assertEquals(old, read["sha1-0123456789ab p2"])
        } finally {
            f.delete()
        }
    }
}
