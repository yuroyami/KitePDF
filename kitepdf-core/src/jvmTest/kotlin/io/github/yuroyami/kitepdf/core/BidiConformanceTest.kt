package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.text.Bidi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bidi algorithm against the conformance files of Unicode 17 (#320, #323). Each line of
 * BidiCharacterTest.txt and BidiTest.txt gives the resolved levels and the visual order of
 * one paragraph.
 *
 * The repo holds a sample of each file in the test resources. The full files are large, and
 * their tests run when the files are in `~/.cache/kitepdf/ucd-17`, or in the folder that the
 * system property `kitepdf.ucd` names:
 *
 * ```
 * mkdir -p ~/.cache/kitepdf/ucd-17 && cd ~/.cache/kitepdf/ucd-17
 * curl -O https://www.unicode.org/Public/17.0.0/ucd/BidiCharacterTest.txt
 * curl -O https://www.unicode.org/Public/17.0.0/ucd/BidiTest.txt
 * ```
 */
class BidiConformanceTest {

    private fun resource(name: String): List<String> =
        javaClass.getResourceAsStream("/bidi/$name")!!.bufferedReader().readLines()

    private fun full(name: String): List<String>? {
        val dir = System.getProperty("kitepdf.ucd")?.let(::File) ?: File(System.getProperty("user.home"), ".cache/kitepdf/ucd-17")
        return File(dir, name).takeIf { it.exists() }?.readLines()
    }

    @Test
    fun the_sample_of_bidi_character_test_passes() = checkCharacterTest(resource("BidiCharacterTest-sample.txt"))

    @Test
    fun the_sample_of_bidi_test_passes() = checkClassTest(resource("BidiTest-sample.txt"))

    @Test
    fun bidi_character_test_passes_in_full() = checkCharacterTest(full("BidiCharacterTest.txt").orSkip("BidiCharacterTest.txt"))

    @Test
    fun bidi_test_passes_in_full() = checkClassTest(full("BidiTest.txt").orSkip("BidiTest.txt"))

    /** Each line: code points; paragraph direction (0, 1 or 2 for auto); paragraph level; levels; visual order. */
    private fun checkCharacterTest(lines: List<String>) {
        val failures = ArrayList<String>()
        var total = 0
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            total++
            val f = line.split(';')
            val cps = f[0].trim().split(' ').map { it.toInt(16) }.toIntArray()
            val direction = f[1].trim().toInt()
            val expectedLevels = f[3].trim().split(' ')
            val expectedOrder = f[4].trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() }
            val paraLevel = if (direction == 2) Bidi.baseLevel(cps) else direction
            val levels = Bidi.resolveLevels(cps, paraLevel)
            val order = Bidi.reorderVisually(levels).filter { expectedLevels[it] != "x" }
            val levelsMatch = cps.indices.all { expectedLevels[it] == "x" || expectedLevels[it].toInt() == levels[it] }
            if (paraLevel != f[2].trim().toInt() || !levelsMatch || order != expectedOrder) {
                failures += "$line\n  got paragraph $paraLevel, levels ${levels.toList()}, order $order"
            }
        }
        println("bidi character tests: ${total - failures.size} of $total pass")
        assertTrue(failures.isEmpty(), failures.take(10).joinToString("\n"))
    }

    /** `@Levels` and `@Reorder` lines, then lines of bidi classes with a set of paragraph directions: 1 auto, 2 LTR, 4 RTL. */
    private fun checkClassTest(lines: List<String>) {
        val failures = ArrayList<String>()
        var total = 0
        var expectedLevels = emptyList<String>()
        var expectedOrder = emptyList<Int>()
        for (line in lines) {
            when {
                line.isBlank() || line.startsWith("#") -> continue
                line.startsWith("@Levels:") -> expectedLevels = line.substringAfter(':').trim().split(' ').filter { it.isNotEmpty() }
                line.startsWith("@Reorder:") -> expectedOrder = line.substringAfter(':').trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() }
                else -> {
                    val types = line.substringBefore(';').trim().split(' ').map { CLASSES.getValue(it) }.toIntArray()
                    val set = line.substringAfter(';').trim().toInt()
                    for ((bit, level) in listOf(1 to -1, 2 to 0, 4 to 1)) {
                        if (set and bit == 0) continue
                        total++
                        val paraLevel = if (level < 0) Bidi.baseLevelOfTypes(types) else level
                        val levels = Bidi.resolve(types, null, paraLevel, intArrayOf(types.size))
                        val order = Bidi.reorderVisually(levels).filter { expectedLevels[it] != "x" }
                        val levelsMatch = types.indices.all { expectedLevels[it] == "x" || expectedLevels[it].toInt() == levels[it] }
                        if (!levelsMatch || order != expectedOrder) {
                            failures += "$line (direction $bit)\n  expected $expectedLevels / $expectedOrder, got ${levels.toList()} / $order"
                        }
                    }
                }
            }
        }
        println("bidi class tests: ${total - failures.size} of $total pass")
        assertTrue(failures.isEmpty(), failures.take(10).joinToString("\n"))
    }

    private val CLASSES = mapOf(
        "L" to Bidi.L, "R" to Bidi.R, "AL" to Bidi.AL, "EN" to Bidi.EN, "ES" to Bidi.ES, "ET" to Bidi.ET, "AN" to Bidi.AN,
        "CS" to Bidi.CS, "B" to Bidi.B, "S" to Bidi.S, "WS" to Bidi.WS, "ON" to Bidi.ON, "NSM" to Bidi.NSM, "BN" to Bidi.BN,
        "LRE" to Bidi.LRE, "LRO" to Bidi.LRO, "RLE" to Bidi.RLE, "RLO" to Bidi.RLO, "PDF" to Bidi.PDF, "LRI" to Bidi.LRI,
        "RLI" to Bidi.RLI, "FSI" to Bidi.FSI, "PDI" to Bidi.PDI,
    )
}
