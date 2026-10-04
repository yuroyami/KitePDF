package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.script.Idna
import io.github.yuroyami.kitepdf.epub.script.Nfc
import io.github.yuroyami.kitepdf.epub.script.codePoints
import io.github.yuroyami.kitepdf.epub.script.fromCodePoints
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The host name steps of the URL parser against the conformance files of Unicode 17 (#520):
 * IdnaTestV2.txt for UTS #46 and NormalizationTest.txt for NFC.
 *
 * The repo holds a sample of each file in the test resources. The full files run when they are
 * in `~/.cache/kitepdf/ucd-17`, or in the folder that the system property `kitepdf.ucd` names:
 *
 * ```
 * mkdir -p ~/.cache/kitepdf/ucd-17 && cd ~/.cache/kitepdf/ucd-17
 * curl -O https://www.unicode.org/Public/17.0.0/idna/IdnaTestV2.txt
 * curl -O https://www.unicode.org/Public/17.0.0/ucd/NormalizationTest.txt
 * ```
 */
class IdnaConformanceTest {

    private fun resource(name: String): List<String> =
        javaClass.getResourceAsStream("/idna/$name")!!.bufferedReader().readLines()

    private fun full(name: String): List<String>? {
        val dir = System.getProperty("kitepdf.ucd")?.let(::File) ?: File(System.getProperty("user.home"), ".cache/kitepdf/ucd-17")
        return File(dir, name).takeIf { it.exists() }?.readLines()
    }

    @Test
    fun the_sample_of_idna_test_passes() = checkIdnaTest(resource("IdnaTestV2-sample.txt"))

    @Test
    fun the_sample_of_normalization_test_passes() = checkNormalizationTest(resource("NormalizationTest-sample.txt"), everyCodePoint = false)

    @Test
    fun idna_test_passes_in_full() = checkIdnaTest(full("IdnaTestV2.txt").orSkip("IdnaTestV2.txt"))

    @Test
    fun normalization_test_passes_in_full() = checkNormalizationTest(full("NormalizationTest.txt").orSkip("NormalizationTest.txt"), everyCodePoint = true)

    /** A field of IdnaTestV2.txt with its \uXXXX and \x{XXXX} escapes read. */
    private fun unescape(field: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < field.length) {
            when {
                field.startsWith("\\u", i) -> { out.append(field.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                field.startsWith("\\x{", i) -> {
                    val end = field.indexOf('}', i)
                    out.appendCodePoint(field.substring(i + 3, end).toInt(16))
                    i = end + 1
                }
                else -> out.append(field[i++])
            }
        }
        return out.toString()
    }

    /**
     * Each line: source; toUnicode; its status; toAsciiN; its status; toAsciiT; its status. A blank
     * value takes the one before it, and a status in brackets means the operation fails. Both
     * ToASCII operations run with every flag on, as the file is written for.
     */
    private fun checkIdnaTest(lines: List<String>) {
        val failures = ArrayList<String>()
        var total = 0
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            total++
            val f = line.substringBefore('#').split(';').map { it.trim() }
            fun value(s: String) = if (s == "\"\"") "" else unescape(s)
            val source = value(f[0])
            val toUnicode = if (f[1].isEmpty()) source else value(f[1])
            val toUnicodeStatus = f[2]
            val asciiN = if (f[3].isEmpty()) toUnicode else value(f[3])
            val asciiNStatus = f[4].ifEmpty { toUnicodeStatus }
            val asciiT = if (f[5].isEmpty()) asciiN else value(f[5])
            val asciiTStatus = f[6].ifEmpty { asciiNStatus }
            for ((transitional, expected, status) in listOf(Triple(false, asciiN, asciiNStatus), Triple(true, asciiT, asciiTStatus))) {
                val actual = Idna.toAscii(
                    source, checkHyphens = true, checkBidi = true, checkJoiners = true, useStd3AsciiRules = true,
                    transitional = transitional, verifyDnsLength = true,
                )
                val fails = status.isNotEmpty() && status != "[]"
                val ok = if (fails) actual == null else actual == expected
                if (!ok) failures += "${if (transitional) "T" else "N"} $line: got $actual"
            }
        }
        assertTrue(total > 0)
        assertTrue(failures.isEmpty(), "${failures.size} of ${total * 2} fail:\n" + failures.take(30).joinToString("\n"))
    }

    /**
     * Each line: c1; c2; c3; c4; c5, in hex code points, where NFC gives c2 of c1, c2 and c3, and
     * c4 of c4 and c5. Every code point that part 1 does not list is its own NFC.
     */
    private fun checkNormalizationTest(lines: List<String>, everyCodePoint: Boolean) {
        val failures = ArrayList<String>()
        val listed = HashSet<Int>()
        var part = ""
        var total = 0
        for (line in lines) {
            if (line.startsWith("@Part")) { part = line.substringBefore(' '); continue }
            if (line.isBlank() || line.startsWith("#")) continue
            total++
            val c = line.substringBefore('#').split(';').take(5).map { col -> col.trim().split(' ').map { it.toInt(16) }.toIntArray() }
            if (part == "@Part1") listed += c[0].single()
            fun nfc(x: IntArray) = Nfc.normalize(x)
            val ok = nfc(c[0]).contentEquals(c[1]) && nfc(c[1]).contentEquals(c[1]) && nfc(c[2]).contentEquals(c[1]) &&
                nfc(c[3]).contentEquals(c[3]) && nfc(c[4]).contentEquals(c[3]) &&
                Nfc.normalize(fromCodePoints(c[0])) == fromCodePoints(c[1])
            if (!ok) failures += "$line: got ${nfc(c[0]).joinToString(" ") { it.toString(16).uppercase() }}"
        }
        if (everyCodePoint) {
            for (cp in 0..0x10FFFF) {
                if (cp in listed || cp in 0xD800..0xDFFF) continue
                if (!Nfc.normalize(intArrayOf(cp)).contentEquals(intArrayOf(cp))) failures += "U+${cp.toString(16).uppercase()} is not its own NFC"
            }
        }
        assertTrue(total > 0)
        assertTrue(failures.isEmpty(), "${failures.size} of $total fail:\n" + failures.take(30).joinToString("\n"))
    }

    @Test
    fun a_lone_surrogate_survives_the_code_point_round_trip() {
        val text = charArrayOf('a', '\uD800', 'b').concatToString()
        assertTrue(fromCodePoints(codePoints(text)) == text)
    }
}
