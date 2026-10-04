package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.script.WhatwgEncoding
import io.github.yuroyami.kitepdf.epub.script.WhatwgMimeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MIME type parser of the script layer against the web-platform-tests data of the MIME
 * Sniffing Standard (#533), which the test resources hold at a pinned commit: every input of
 * mime-types.json and generated-mime-types.json parses to the MIME type the data serializes, or
 * fails where it does, and its charset names the encoding the data gives.
 */
class WhatwgMimeTypeTest {

    private fun cases(name: String): List<Map<*, *>> =
        (Json(javaClass.getResourceAsStream("/wpt-mime/$name")!!.readBytes().decodeToString()).value() as List<*>)
            .filterIsInstance<Map<*, *>>()

    private fun check(name: String, atLeast: Int) {
        val failures = ArrayList<String>()
        val all = cases(name)
        for (case in all) {
            val input = case["input"] as String
            val parsed = WhatwgMimeType.parse(input)
            val serialized = parsed?.serialize()
            if (serialized != case["output"]) failures += "<$input> gives <$serialized>, and should give <${case["output"]}>"
            if (case.containsKey("encoding")) {
                val encoding = parsed?.parameters?.get("charset")?.let(WhatwgEncoding::forLabel)
                if (encoding != case["encoding"]) failures += "<$input> names the encoding $encoding, and should name ${case["encoding"]}"
            }
        }
        assertTrue(all.size >= atLeast, "only ${all.size} cases in $name")
        assertTrue(failures.isEmpty(), "${failures.size} failures of ${all.size} cases:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun every_mime_type_of_mime_types_json_parses_as_the_standard_says() = check("mime-types.json", atLeast = 70)

    @Test
    fun every_generated_mime_type_parses_as_the_standard_says() = check("generated-mime-types.json", atLeast = 800)

    @Test
    fun the_essence_drops_the_parameters_and_a_value_keeps_its_case() {
        val type = WhatwgMimeType.parse(" Text/Plain ; Charset = x ; charset=Windows-1252 ; q=\"a\\\"b\"")!!
        assertEquals("text/plain", type.essence)
        assertEquals(mapOf("charset" to "Windows-1252", "q" to "a\"b"), type.parameters)
        assertEquals("text/plain;charset=Windows-1252;q=\"a\\\"b\"", type.serialize())
    }
}
