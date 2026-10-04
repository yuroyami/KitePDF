package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.script.WhatwgUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The URL parser of the script layer against the web-platform-tests data of the URL Standard
 * (#520), which the test resources hold at a pinned commit: every parse of urltestdata.json,
 * every setter of setters_tests.json and every host of toascii.json.
 */
class WhatwgUrlTest {

    private fun json(name: String): Any? =
        Json(javaClass.getResourceAsStream("/wpt-url/$name")!!.readBytes().decodeToString()).value()

    /** The parts the URL class shows, by the names the data gives them. */
    private fun WhatwgUrl.part(name: String): String = when (name) {
        "href" -> href()
        "protocol" -> protocol
        "username" -> username
        "password" -> password
        "host" -> hostWithPort
        "hostname" -> hostname
        "port" -> portString
        "pathname" -> pathname
        "search" -> search
        "hash" -> hash
        "origin" -> origin()
        "searchParams" -> WhatwgUrl.serializeForm(WhatwgUrl.parseForm(query.orEmpty()))
        else -> error("no part $name")
    }

    private val parts = listOf("href", "protocol", "username", "password", "host", "hostname", "port", "pathname", "search", "hash", "origin", "searchParams")

    @Test
    fun every_parse_of_urltestdata_gives_the_url_the_standard_does() {
        val failures = ArrayList<String>()
        var total = 0
        for (case in json("urltestdata.json") as List<*>) {
            if (case !is Map<*, *>) continue
            total++
            val input = case["input"] as String
            val base = case["base"] as String?
            val url = WhatwgUrl.parse(input, base)
            val name = "<$input> against <$base>"
            if (case["failure"] == true) {
                if (url != null) failures += "$name parses to ${url.href()}, and should fail"
                continue
            }
            if (url == null) { failures += "$name fails, and should parse to ${case["href"]}"; continue }
            for (part in parts) {
                val expected = case[part] as String? ?: continue
                val actual = url.part(part)
                if (actual != expected) failures += "$name: $part is <$actual>, and should be <$expected>"
            }
        }
        assertTrue(total > 800)
        assertTrue(failures.isEmpty(), "${failures.size} failures of $total cases:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun every_setter_of_setters_tests_does_what_the_standard_does() {
        val failures = ArrayList<String>()
        var total = 0
        for ((setter, cases) in json("setters_tests.json") as Map<*, *>) {
            if (setter == "comment") continue
            for (case in cases as List<*>) {
                case as Map<*, *>
                total++
                val href = case["href"] as String
                val value = case["new_value"] as String
                val url = WhatwgUrl.parse(href)!!
                url.set(setter as String, value)
                for ((part, expected) in case["expected"] as Map<*, *>) {
                    val actual = url.part(part as String)
                    if (actual != expected) failures += "$setter = <$value> on <$href>: $part is <$actual>, and should be <$expected>"
                }
            }
        }
        assertTrue(total > 250)
        assertTrue(failures.isEmpty(), "${failures.size} failures of $total cases:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun every_host_of_toascii_goes_through_the_domain_parser_as_the_standard_says() {
        val failures = ArrayList<String>()
        var total = 0
        for (case in json("toascii.json") as List<*>) {
            if (case !is Map<*, *>) continue
            total++
            val input = case["input"] as String
            val output = case["output"] as String?
            val url = WhatwgUrl.parse("https://$input/x")
            if (url?.hostname != output) failures += "<$input> gives host <${url?.hostname}>, and should give <$output>"
            if (output != null && url?.href() != "https://$output/x") failures += "<$input> gives <${url?.href()}>"
            for (setter in listOf("host", "hostname")) {
                val set = WhatwgUrl.parse("https://x/x")!!
                set.set(setter, input)
                if (set.hostname != (output ?: "x")) failures += "$setter = <$input> gives <${set.hostname}>, and should give <${output ?: "x"}>"
            }
        }
        assertTrue(total > 80)
        assertTrue(failures.isEmpty(), "${failures.size} failures of $total cases:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun the_books_own_scheme_keeps_a_tuple_origin_and_stops_at_its_root() {
        val chapter = WhatwgUrl.parse("epub://a1b2c3/EPUB/content_001.xhtml")!!
        assertEquals("null", chapter.origin(), "a scheme that is not special has an opaque origin")
        assertEquals("epub://a1b2c3", chapter.origin(tupleScheme = "epub"))
        val root = WhatwgUrl.parse("../..", chapter)!!
        assertEquals("epub://a1b2c3/", root.href())
        assertEquals("epub://a1b2c3/media/imgs/monastery.jpg", WhatwgUrl.parse("../../../../../media/imgs/monastery.jpg", root)!!.href())
        assertEquals("epub://a1b2c3/media/imgs/monastery.jpg", WhatwgUrl.parse("/media/imgs/monastery.jpg", root)!!.href())
    }

    @Test
    fun form_urlencoded_parses_and_serializes_as_the_standard_does() {
        assertEquals(listOf("a" to "b c", "" to "x", "é" to "", "%" to "�"), WhatwgUrl.parseForm("a=b+c&=x&%C3%A9&&%=%C3"))
        assertEquals("a=b+c&%C3%A9=%26%3D%2B&*-._=%7E", WhatwgUrl.serializeForm(listOf("a" to "b c", "é" to "&=+", "*-._" to "~")))
    }

    @Test
    fun a_lone_surrogate_decodes_as_a_replacement_character() {
        // The JVM's own UTF-8 encoder writes a lone surrogate as '?', which is not what the standard's is.
        val text = charArrayOf('a', '\uD800', '=', '\uDC00', '%', '4', '1').concatToString()
        assertEquals(listOf("a�" to "�A"), WhatwgUrl.parseForm(text))
    }

    /** Just enough JSON for the test data: objects, arrays, strings with their escapes, numbers, booleans and null. */
    private class Json(private val text: String) {
        private var at = 0

        fun value(): Any? {
            space()
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> { at += 4; true }
                'f' -> { at += 5; false }
                'n' -> { at += 4; null }
                else -> {
                    val start = at
                    while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
                    check(start < at) { "unexpected $c at $start" }
                    text.substring(start, at).toDouble()
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            space()
            if (text[at] == '}') { at++; return map }
            while (true) {
                space()
                val key = string()
                space()
                expect(':')
                map[key] = value()
                space()
                if (text[at++] == '}') return map
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            space()
            if (text[at] == ']') { at++; return list }
            while (true) {
                list += value()
                space()
                if (text[at++] == ']') return list
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text[at++]) {
                        'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun space() { while (at < text.length && text[at].isWhitespace()) at++ }

        private fun expect(c: Char) { check(text[at++] == c) { "expected $c at ${at - 1}" } }
    }
}
