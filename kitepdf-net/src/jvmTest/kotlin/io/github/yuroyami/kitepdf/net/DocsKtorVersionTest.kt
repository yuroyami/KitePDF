package io.github.yuroyami.kitepdf.net

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Ktor coordinate that the README and the docs tell a reader to add names the Ktor version
 * this artifact is built on, since Ktor's modules are meant to share one version. The docs once
 * kept 3.5.2 after the build moved to 3.6.0 (#493).
 */
class DocsKtorVersionTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "gradle/libs.versions.toml").isFile }

    @Test
    fun the_docs_name_the_ktor_version_of_the_build() {
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        val ktor = Regex("""(?m)^ktor\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1)
            ?: error("the version catalog names no ktor version")
        val pages = listOf(File(root, "README.md")) + File(root, "docs").listFiles { f -> f.extension == "md" }.orEmpty()
        val found = pages.flatMap { page ->
            Regex("""io\.ktor:[a-z0-9-]+:([0-9][0-9A-Za-z.+-]*)""").findAll(page.readText())
                .map { "${page.name}: ${it.value}" to it.groupValues[1] }.toList()
        }
        assertTrue(found.isNotEmpty(), "no page names a Ktor coordinate, so this test checks nothing")
        assertEquals(emptyList(), found.filter { it.second != ktor }.map { it.first }, "the build uses Ktor $ktor")
    }
}
