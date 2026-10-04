package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.script.WhatwgDecoder
import io.github.yuroyami.kitepdf.epub.script.WhatwgEncoding
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encodings of the Encoding Standard that a book's scripts decode with (#532).
 *
 * The tests of web-platform-tests check the interfaces over them, and every single-byte index, in
 * `WebPlatformTest`. These check the CJK decoders, the state a stream keeps between calls, and,
 * when the standard's repository is in `~/.cache/kitepdf/whatwg-encoding` or in the folder that
 * the system property `kitepdf.whatwgEncoding` names, every pointer of every index:
 *
 * ```
 * git clone https://github.com/whatwg/encoding ~/.cache/kitepdf/whatwg-encoding
 * ```
 */
class WhatwgEncodingTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun decode(encoding: String, vararg values: Int): String? =
        WhatwgDecoder(encoding, fatal = false, ignoreBom = false).decode(bytes(*values), flush = true)

    private fun units(text: String?): String = text?.map { "%04X".format(it.code) }?.joinToString(" ") ?: "error"

    private fun assertDecodes(expected: String, encoding: String, vararg values: Int) =
        assertEquals(units(expected), units(decode(encoding, *values)), "$encoding ${values.joinToString(" ") { "%02X".format(it) }}")

    @Test
    fun a_label_names_its_encoding() {
        assertEquals("UTF-8", WhatwgEncoding.forLabel(" \t\n\u000C\rUTF8 "))
        assertEquals("UTF-8", WhatwgEncoding.forLabel("unicode-1-1-utf-8"))
        assertEquals("windows-1252", WhatwgEncoding.forLabel("latin1"))
        assertEquals("windows-1252", WhatwgEncoding.forLabel("ASCII"))
        assertEquals("UTF-16LE", WhatwgEncoding.forLabel("utf-16"))
        assertEquals("Shift_JIS", WhatwgEncoding.forLabel("x-sjis"))
        assertEquals("GBK", WhatwgEncoding.forLabel("gb2312"))
        assertEquals("ISO-8859-8-I", WhatwgEncoding.forLabel("logical"))
        assertEquals("replacement", WhatwgEncoding.forLabel("iso-2022-kr"))
        assertEquals("x-user-defined", WhatwgEncoding.forLabel("x-user-defined"))
        assertNull(WhatwgEncoding.forLabel("utf-32"))
        // Only ASCII white space goes, and only ASCII letters change case: the Kelvin sign lowercases to k.
        assertNull(WhatwgEncoding.forLabel("\u000Butf-8"))
        assertNull(WhatwgEncoding.forLabel("\u212Aoi8-r"))
    }

    @Test
    fun each_cjk_decoder_reads_its_characters() {
        assertDecodes("\u3042", "Shift_JIS", 0x82, 0xA0)
        assertDecodes("\uFF61\u0080", "Shift_JIS", 0xA1, 0x80)
        assertDecodes("\uE000", "Shift_JIS", 0xF0, 0x40)
        assertDecodes("\u3042", "EUC-JP", 0xA4, 0xA2)
        assertDecodes("\u02D8", "EUC-JP", 0x8F, 0xA2, 0xAF)
        assertDecodes("\uFF61", "EUC-JP", 0x8E, 0xA1)
        assertDecodes("\u3042", "ISO-2022-JP", 0x1B, 0x24, 0x42, 0x24, 0x22, 0x1B, 0x28, 0x42)
        assertDecodes("\u00A5\u203E", "ISO-2022-JP", 0x1B, 0x28, 0x4A, 0x5C, 0x7E)
        assertDecodes("\uFF61", "ISO-2022-JP", 0x1B, 0x28, 0x49, 0x21)
        assertDecodes("\u4E2D", "Big5", 0xA4, 0xA4)
        assertDecodes("\u00CA\u0304\u00CA\u030C\u00EA\u0304\u00EA\u030C", "Big5", 0x88, 0x62, 0x88, 0x64, 0x88, 0xA3, 0x88, 0xA5)
        assertDecodes("\u4E2D\u20AC\u20AC", "GBK", 0xD6, 0xD0, 0x80, 0xA2, 0xE3)
        assertDecodes("\uAC00", "EUC-KR", 0xB0, 0xA1)
        assertDecodes("\u0080", "gb18030", 0x81, 0x30, 0x81, 0x30)
        assertDecodes("\uE7C7", "gb18030", 0x81, 0x35, 0xF4, 0x37)
        assertDecodes("\uFFFF", "gb18030", 0x84, 0x31, 0xA4, 0x39)
        assertDecodes("\uD800\uDC00", "gb18030", 0x90, 0x30, 0x81, 0x30)
        assertDecodes("\uDBFF\uDFFF", "gb18030", 0xE3, 0x32, 0x9A, 0x35)
        assertDecodes("\uF780\uF7FF", "x-user-defined", 0x80, 0xFF)
    }

    @Test
    fun errors_become_what_the_standard_says() {
        assertDecodes("\uFFFD\uFFFD\uFFFD", "UTF-8", 0xF0, 0x80, 0x80)
        assertDecodes("\uFFFD", "UTF-8", 0xF0, 0x90, 0x80)
        assertDecodes("\uFFFDA", "UTF-8", 0xE0, 0x41)
        assertDecodes("\uFFFD\uFFFD\uFFFD", "UTF-8", 0xED, 0xA0, 0x80)
        assertDecodes("\uFFFDA", "UTF-16LE", 0x00, 0xD8, 0x41, 0x00)
        assertDecodes("\uFFFD\uFFFD", "UTF-16BE", 0xD8, 0x00, 0xD8, 0x00)
        assertDecodes("A\uFFFD", "UTF-16LE", 0x41, 0x00, 0x42)
        // A byte that cannot trail a lead goes back to be read again when it is ASCII.
        assertDecodes("\uFFFD ", "Shift_JIS", 0x81, 0x20)
        assertDecodes("\uFFFD@", "Big5", 0x81, 0x40)
        assertDecodes("\uFFFD", "Big5", 0x81, 0x80)
        assertDecodes("\uFFFD0A", "gb18030", 0x81, 0x30, 0x41)
        assertDecodes("\uFFFD0\u4E04", "gb18030", 0x81, 0x30, 0x81, 0x41)
        assertDecodes("\uFFFD", "gb18030", 0x81, 0x30, 0x81)
        assertDecodes("\uFFFD", "gb18030", 0x84, 0x31, 0xA5, 0x30)
        assertDecodes("\uFFFD", "EUC-KR", 0xB0)
        // ISO-2022-JP: an escape that changes nothing before the next is an error, as is a broken one.
        assertDecodes("\uFFFD", "ISO-2022-JP", 0x1B, 0x24, 0x42, 0x1B, 0x28, 0x42)
        assertDecodes("\uFFFDA", "ISO-2022-JP", 0x1B, 0x41)
        assertDecodes("\uFFFD(A", "ISO-2022-JP", 0x1B, 0x28, 0x41)
        assertDecodes("\uFFFD", "ISO-2022-JP", 0x1B)
        assertDecodes("\uFFFD", "ISO-2022-JP", 0x0E)
        assertDecodes("\uFFFD", "ISO-2022-JP", 0x1B, 0x24, 0x42, 0x24, 0x1B, 0x28, 0x42)
        assertDecodes("\uFFFD", "replacement", 0x41, 0x42)
        assertDecodes("", "replacement")
    }

    @Test
    fun a_stream_decodes_as_one_call_does() {
        val random = Random(532)
        val encodings = listOf(
            "UTF-8", "UTF-16LE", "UTF-16BE", "windows-1252", "ISO-8859-8-I", "gb18030", "GBK", "Big5", "EUC-JP",
            "ISO-2022-JP", "Shift_JIS", "EUC-KR", "replacement", "x-user-defined",
        )
        // Bytes that each decoder has a use for, so the random input meets its sequences and its errors.
        val alphabet = intArrayOf(0x00, 0x1B, 0x24, 0x28, 0x30, 0x35, 0x40, 0x41, 0x42, 0x49, 0x4A, 0x5C, 0x7E, 0x80, 0x81,
            0x84, 0x88, 0x8E, 0x8F, 0x90, 0xA1, 0xA4, 0xB0, 0xBB, 0xBF, 0xD8, 0xDC, 0xE0, 0xE3, 0xED, 0xEF, 0xF0, 0xF4, 0xFE, 0xFF)
        for (encoding in encodings) {
            repeat(300) {
                val input = ByteArray(random.nextInt(1, 24)) { alphabet[random.nextInt(alphabet.size)].toByte() }
                val whole = WhatwgDecoder(encoding, fatal = false, ignoreBom = false).decode(input, flush = true)
                // Each chunk goes to a decoder made again from the state of the one before.
                var state: List<Int>? = null
                val out = StringBuilder()
                var at = 0
                while (at < input.size) {
                    val end = minOf(input.size, at + random.nextInt(1, 4))
                    val decoder = WhatwgDecoder(encoding, fatal = false, ignoreBom = false, state)
                    out.append(decoder.decode(input, at, end, flush = false))
                    state = decoder.state
                    at = end
                }
                out.append(WhatwgDecoder(encoding, fatal = false, ignoreBom = false, state).decode(input, 0, 0, flush = true))
                assertEquals(units(whole), units(out.toString()), "$encoding ${input.joinToString(" ") { "%02X".format(it) }}")
            }
        }
    }

    @Test
    fun a_fatal_decoder_stops_at_an_error_and_keeps_what_it_has_not_read() {
        val decoder = WhatwgDecoder("UTF-8", fatal = true, ignoreBom = false)
        assertNull(decoder.decode(bytes(0x41, 0xFF, 0x42), flush = false))
        val next = WhatwgDecoder("UTF-8", fatal = true, ignoreBom = false, decoder.state)
        assertEquals("BC", next.decode(bytes(0x43), flush = true))
        assertNull(WhatwgDecoder("UTF-16LE", fatal = true, ignoreBom = false).decode(bytes(0x41), flush = true))
        assertEquals("", WhatwgDecoder("UTF-16LE", fatal = true, ignoreBom = false).decode(bytes(0x41), flush = false))
    }

    @Test
    fun a_bom_goes_once_unless_it_is_ignored() {
        val bom = intArrayOf(0xEF, 0xBB, 0xBF)
        assertEquals("\uFEFFA", decode("UTF-8", *bom, *bom, 0x41))
        assertEquals("\uFEFF\uFEFFA", WhatwgDecoder("UTF-8", fatal = false, ignoreBom = true).decode(bytes(*bom, *bom, 0x41), flush = true))
        assertEquals("A", decode("UTF-16BE", 0xFE, 0xFF, 0x00, 0x41))
        assertEquals("\u00EF\u00BB\u00BF", decode("windows-1252", *bom))
        val first = WhatwgDecoder("UTF-8", fatal = false, ignoreBom = false)
        assertEquals("", first.decode(bytes(0xEF), flush = false))
        assertEquals("A", WhatwgDecoder("UTF-8", fatal = false, ignoreBom = false, first.state).decode(bytes(0xBB, 0xBF, 0x41), flush = true))
    }

    @Test
    fun decode_takes_the_encoding_of_a_bom() {
        assertEquals("A", WhatwgEncoding.decode(bytes(0xFE, 0xFF, 0x00, 0x41), "windows-1252"))
        assertEquals("A\uFEFF", WhatwgEncoding.decode(bytes(0xEF, 0xBB, 0xBF, 0x41, 0xEF, 0xBB, 0xBF), "UTF-16LE"))
        assertEquals("\u4142", WhatwgEncoding.decode(bytes(0x41, 0x42), "UTF-16BE"))
        assertEquals("\uFFFD", WhatwgEncoding.decode(bytes(0x41), "replacement"))
    }

    @Test
    fun the_utf8_encoder_writes_a_lone_surrogate_as_a_replacement_character() {
        assertEquals(listOf(0x61, 0xEF, 0xBF, 0xBD, 0xF0, 0x9F, 0x98, 0x80, 0xEF, 0xBF, 0xBD),
            WhatwgEncoding.utf8Encode("a\uDC00\uD83D\uDE00\uD800").map { it.toInt() and 0xFF })
        val text = "a\u00E9\uD83D\uDE00"
        assertEquals(2, WhatwgEncoding.utf8EncodeInto(text, 3).first)
        assertEquals(3, WhatwgEncoding.utf8EncodeInto(text, 3).second.size)
        assertEquals(2, WhatwgEncoding.utf8EncodeInto(text, 6).first)
        assertEquals(4, WhatwgEncoding.utf8EncodeInto(text, 7).first)
        assertEquals(0, WhatwgEncoding.utf8EncodeInto(text, 0).first)
    }

    @Test
    fun base64_is_forgiving_one_way_and_strict_the_other() {
        assertEquals("a", WhatwgEncoding.atob("YQ"))
        assertEquals("a", WhatwgEncoding.atob(" Y\tQ\n=\u000C=\r"))
        assertEquals("a", WhatwgEncoding.atob("YR=="))
        assertEquals("\u00FF\u00FE", WhatwgEncoding.atob("//4="))
        assertNull(WhatwgEncoding.atob("YQ="))
        assertNull(WhatwgEncoding.atob("a"))
        assertNull(WhatwgEncoding.atob("===="))
        assertNull(WhatwgEncoding.atob("YQ\u00A0"))
        assertEquals("", WhatwgEncoding.atob(""))
        assertEquals("YQ==", WhatwgEncoding.btoa("a"))
        assertEquals("//4=", WhatwgEncoding.btoa("\u00FF\u00FE"))
        assertEquals("YWJj", WhatwgEncoding.btoa("abc"))
        assertNull(WhatwgEncoding.btoa("\u0100"))
    }

    /** The standard's repository, or null when it is not here. */
    private val repository: File? = (System.getProperty("kitepdf.whatwgEncoding")?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache/kitepdf/whatwg-encoding")).takeIf { File(it, "index-big5.txt").exists() }

    /** An index file as its pointers and code points. */
    private fun index(name: String): Map<Int, Int> = File(repository.orSkip("the Encoding Standard's repository"), "index-$name.txt")
        .readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line -> line.split('\t').let { it[0].trim().toInt() to it[1].removePrefix("0x").toInt(16) } }

    private fun check(encoding: String, index: Map<Int, Int>, pointers: Iterable<Int>, bytesOf: (Int) -> IntArray) {
        var checked = 0
        for (pointer in pointers) {
            val cp = index[pointer] ?: continue
            val expected = StringBuilder().appendCodePoint(cp).toString()
            assertDecodes(expected, encoding, *bytesOf(pointer))
            checked++
        }
        assertTrue(checked > 0, "no pointer of $encoding checked")
    }

    @Test
    fun every_pointer_of_every_index_decodes_to_its_code_point() {
        val jis0208 = index("jis0208")
        check("Shift_JIS", jis0208, jis0208.keys.filter { it !in 8836..10715 }) { p ->
            val lead = p / 188
            val trail = p % 188
            intArrayOf(lead + if (lead < 0x1F) 0x81 else 0xC1, trail + if (trail < 0x3F) 0x40 else 0x41)
        }
        check("EUC-JP", jis0208, jis0208.keys.filter { it < 94 * 94 }) { p -> intArrayOf(p / 94 + 0xA1, p % 94 + 0xA1) }
        check("ISO-2022-JP", jis0208, jis0208.keys.filter { it < 94 * 94 }) { p ->
            intArrayOf(0x1B, 0x24, 0x42, p / 94 + 0x21, p % 94 + 0x21, 0x1B, 0x28, 0x42)
        }
        val jis0212 = index("jis0212")
        check("EUC-JP", jis0212, jis0212.keys) { p -> intArrayOf(0x8F, p / 94 + 0xA1, p % 94 + 0xA1) }
        val big5 = index("big5")
        check("Big5", big5, big5.keys) { p ->
            val trail = p % 157
            intArrayOf(p / 157 + 0x81, trail + if (trail < 0x3F) 0x40 else 0x62)
        }
        val eucKr = index("euc-kr")
        check("EUC-KR", eucKr, eucKr.keys) { p -> intArrayOf(p / 190 + 0x81, p % 190 + 0x41) }
        val gb = index("gb18030")
        val twoBytes = { p: Int -> intArrayOf(p / 190 + 0x81, p % 190 + if (p % 190 < 0x3F) 0x40 else 0x41) }
        check("gb18030", gb, gb.keys, twoBytes)
        check("GBK", gb, gb.keys, twoBytes)
        // Every other index is of a single-byte encoding, which one of its labels names, and a byte it leaves out is an error.
        val multiByte = setOf("big5", "euc-kr", "gb18030", "gb18030-ranges", "iso-2022-jp-katakana", "jis0208", "jis0212")
        val singleByte = repository!!.list()!!.filter { it.startsWith("index-") }.map { it.removePrefix("index-").removeSuffix(".txt") } - multiByte
        assertEquals(27, singleByte.size, "$singleByte")
        for (name in singleByte) {
            val table = index(name)
            val encoding = checkNotNull(WhatwgEncoding.forLabel(name)) { name }
            for (p in 0..127) assertDecodes(table[p]?.let { StringBuilder().appendCodePoint(it).toString() } ?: "\uFFFD", encoding, p + 0x80)
        }
    }

    @Test
    fun every_gb18030_range_decodes_as_the_ranges_say() {
        val ranges = index("gb18030-ranges").toSortedMap()
        fun expected(pointer: Int): Int? {
            if (pointer in 39420..188999 || pointer > 1237575) return null
            if (pointer == 7457) return 0xE7C7
            val offset = ranges.headMap(pointer + 1).lastKey()
            return ranges.getValue(offset) + pointer - offset
        }
        val pointers = (0..39419) + (189000..1237575 step 997) + listOf(1237575, 39420, 188999, 1237576, 1237599)
        for (pointer in pointers) {
            var p = pointer
            val b1 = p / 12600 + 0x81; p %= 12600
            val b2 = p / 1260 + 0x30; p %= 1260
            val b3 = p / 10 + 0x81
            val b4 = p % 10 + 0x30
            val cp = expected(pointer)
            assertDecodes(if (cp == null) "\uFFFD" else StringBuilder().appendCodePoint(cp).toString(), "gb18030", b1, b2, b3, b4)
        }
    }
}
