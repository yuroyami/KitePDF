package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
