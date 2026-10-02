package io.github.yuroyami.kitepdf.difftest

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorpusSelectionTest {
    @Test
    fun samples_the_whole_document_and_preserves_short_fixtures() {
        val pages = CorpusSelection.Pages()
        assertEquals(emptyList(), pages.indices(0))
        assertEquals((0..5).toList(), pages.indices(6))
        assertEquals(listOf(0, 19, 39, 59, 79, 99), pages.indices(100))
        assertEquals(listOf(0, 1, 2, 3, 4, 6), pages.indices(7))
        assertEquals(listOf(0), CorpusSelection.Pages(maxPages = 1).indices(100))
        val large = pages.indices(Int.MAX_VALUE)
        assertEquals(6, large.distinct().size)
        assertEquals(Int.MAX_VALUE - 1, large.last())
        assertEquals(large.sorted(), large)
    }

    @Test
    fun explicit_all_pages_overrides_the_cap() {
        assertEquals((0..19).toList(), CorpusSelection.Pages(maxPages = 1, allPages = true).indices(20))
    }

    @Test
    fun malformed_properties_cannot_silently_shrink_coverage() {
        assertEquals(6, CorpusSelection.parseMaxPages(null))
        assertEquals(1, CorpusSelection.parseMaxPages("1"))
        assertFalse(CorpusSelection.parseAllPages(null))
        assertFalse(CorpusSelection.parseAllPages("false"))
        assertTrue(CorpusSelection.parseAllPages("true"))
        for (value in listOf("", "0", "-1", "1.5", "six", "2147483648")) {
            assertFailsWith<IllegalArgumentException>(value) { CorpusSelection.parseMaxPages(value) }
        }
        for (value in listOf("", "TRUE", "yes", "1", " true")) {
            assertFailsWith<IllegalArgumentException>(value) { CorpusSelection.parseAllPages(value) }
        }
        assertFailsWith<IllegalArgumentException> { CorpusSelection.Pages(0) }
        assertFailsWith<IllegalArgumentException> { CorpusSelection.Pages().indices(-1) }
    }

    @Test
    fun discovers_every_document_recursively_with_case_insensitive_extensions() = withDirectory { root ->
        val paths = listOf("z.pdf", "a.PDF", "nested/b.PdF", "nested/deep/c.pdf", "d.pdf")
        for (path in paths + "ignored.txt" + "book.EPUB") File(root, path).apply { parentFile.mkdirs(); writeText(path) }
        val docs = CorpusSelection.discover(root, "pdf")
        assertEquals(paths.sorted(), docs.map { it.file.relativeTo(root).invariantSeparatorsPath })
        assertEquals(5, docs.size)
        assertEquals("a", docs.first().name)
        assertEquals(listOf("book.EPUB"), CorpusSelection.discover(root, "epub").map { it.file.name })
        assertEquals(docs, CorpusSelection.discover(root, "pdf"))
    }

    @Test
    fun names_do_not_alias_nested_files_fixtures_or_unsafe_paths() = withDirectory { root ->
        val paths = listOf("same.pdf", "nested/same.PDF", "elsewhere/same.pdf", "fixture.pdf", "unsafe name.pdf")
        for (path in paths) File(root, path).apply { parentFile.mkdirs(); writeText(path) }
        val docs = CorpusSelection.discover(root, "pdf", setOf("fixture"))
        assertEquals(paths.size, docs.map { it.name.lowercase() }.distinct().size)
        assertTrue(docs.all { Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.name) })
        assertTrue(docs.none { it.name == "fixture" || it.name == "same" })
        withDirectory { otherRoot ->
            for (path in paths) File(otherRoot, path).apply { parentFile.mkdirs(); writeText(path) }
            assertEquals(docs.map { it.name }, CorpusSelection.discover(otherRoot, "pdf", setOf("fixture")).map { it.name })
        }
    }

    @Test
    fun an_explicit_missing_or_non_directory_corpus_is_an_error() = withDirectory { root ->
        val missing = File(root, "missing")
        assertEquals(emptyList(), CorpusSelection.discover(missing, "pdf"))
        assertFailsWith<IllegalArgumentException> { CorpusSelection.resolveDirectory("kitepdf.corpus", missing.path, root) }
        val ordinaryFile = File(root, "file.pdf").apply { writeText("pdf") }
        assertFailsWith<IllegalArgumentException> { CorpusSelection.resolveDirectory("kitepdf.corpus", ordinaryFile.path, root) }
        assertEquals(root, CorpusSelection.resolveDirectory("kitepdf.corpus", root.path, null))
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("kitepdf-corpus-selection-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
