package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfString
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs DoomPDF, the Doom port that lives in a PDF's page-open script, on KiteJS
 * with the smallest fake viewer API it needs, and measures start-up and frames.
 *
 * Opt-in, because one run takes minutes: `KITEPDF_DOOM=true` plus the file
 * `corpus/pdf/doom.pdf` (https://doompdf.pages.dev/doom.pdf). Writes the last
 * frame, the game's console and a progress log under `build/doom/`.
 */
class DoomPdfBenchmark {

    @Test
    fun doom_starts_and_renders_frames_on_kitejs() {
        val enabled = System.getenv("KITEPDF_DOOM") == "true" || System.getProperty("kitepdf.doom") == "true"
        assumeTrue("Run with KITEPDF_DOOM=true to run DoomPDF on KiteJS.", enabled)
        val pdf = repoCorpus("pdf")?.let { File(it, "doom.pdf") }
        assumeTrue("corpus/pdf/doom.pdf is missing", pdf != null && pdf.exists())

        val out = File("build/doom").apply { mkdirs() }
        val log = File(out, "progress.log").also { it.writeText("") }
        fun note(line: String) { println(line); log.appendText(line + "\n") }
        fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

        val t0 = System.nanoTime()
        val doc = PdfDocument.open(pdf!!.readBytes())
        val page0 = doc.catalog.getDict("Pages", doc)?.getArray("Kids", doc)?.get(0)?.resolve(doc) as PdfDictionary
        val original = (page0.getDict("AA", doc)?.getDict("O", doc)?.get("JS")?.resolve(doc) as PdfString).asText()
        note("open and extract: ${ms(t0)} ms, script ${original.length} chars, ${doc.pages[0].annotations.size} annotations")
        note("form model: acroForm=${doc.acroForm != null}, formFields=${doc.formFields.size}")
        var script = original

        val fields = LinkedHashMap<String, String>()
        var fieldSets = 0L
        val timers = ArrayList<String>()
        val alerts = ArrayList<String>()
        KiteJsScriptEngine(instructionBudget = 0).use { engine ->
            engine.defineFunction("__fieldSet") { a -> fields[a[0].toString()] = a[1].toString(); fieldSets++; null }
            engine.defineFunction("app.setInterval") { a -> timers += a[0].toString(); timers.size }
            engine.defineFunction("app.clearInterval") { null }
            engine.defineFunction("app.alert") { a -> alerts += a[0].toString(); note("alert: ${a[0]}"); 1 }
            engine.defineValue("app.viewerType", "KitePDF")
            engine.defineFunction("console.println") { a -> note("console: ${a[0]}"); null }
            engine.evaluate(PRELUDE, "prelude")

            // The script's base64 decoder uses spread syntax. Until KiteJS parses it, the
            // three sites are rewritten to ES5 so the rest of the game can be measured.
            fun parses(source: String) = runCatching { engine.evaluate(source, "probe") }.isSuccess
            for ((old, new) in SPREAD_REWRITES) {
                val needed = if (old.startsWith("result.push")) !parses("Math.max(...[1, 2])") else !parses("[...'ab'].length")
                if (!needed) continue
                val count = script.windowed(old.length, 1).count { it == old }
                script = script.replace(old, new)
                note("KiteJS rejects the spread syntax in `$old`; rewrote $count site(s) to ES5")
            }

            // The WAD is a 5.6M-character base64 string decoded by the script's own
            // function. Time that function on a slice first, so a slow decode is known early.
            val b64 = Regex("b64_to_uint8array\\(\"([A-Za-z0-9+/=]{100,})\"\\)").find(script)
            assertNotNull(b64, "the embedded WAD was not found in the script")
            val decoder = script.substring(script.indexOf("function b64_to_uint8array"), script.indexOf("var file_data"))
            engine.evaluate(decoder, "decoder")
            engine.defineValue("__slice", b64.groupValues[1].substring(0, 40_000))
            val tSlice = System.nanoTime()
            engine.evaluate("b64_to_uint8array(__slice).length", "slice")
            val sliceMs = ms(tSlice)
            note("wad decode: $sliceMs ms per 40k chars, cold")
            engine.defineValue("__all", b64.groupValues[1])
            val tAll = System.nanoTime()
            engine.evaluate("b64_to_uint8array(__all).length", "all")
            note("wad decode: ${ms(tAll) / 1000} s for the whole WAD (${b64.groupValues[1].length} chars)")

            val rt = Runtime.getRuntime()
            System.gc()
            val heapBefore = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            val t1 = System.nanoTime()
            val failure = runCatching { engine.evaluate(script, "doom.pdf") }.exceptionOrNull()
            note("start-up: ${ms(t1) / 1000} s, $fieldSets field sets, ${timers.size} timers, ${alerts.size} alerts" +
                (failure?.let { ", threw ${it.message}" } ?: ""))
            System.gc()
            note("heap: $heapBefore MB before, ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB after start-up")
            // The game prints through its own console fields, so they hold any error text.
            fun dump() {
                File(out, "frame.txt").writeText((199 downTo 0).joinToString("\n") { fields["field_$it"] ?: "" })
                File(out, "console.txt").writeText((24 downTo 0).joinToString("\n") { fields["console_$it"] ?: "" })
                (24 downTo 0).mapNotNull { fields["console_$it"] }.filter { it.isNotBlank() }.forEach { note("game console: $it") }
            }
            dump()

            val tick = timers.firstOrNull { "_doomjs_tick" in it }
            assertNotNull(tick, "the game loop timer was never set; alerts: $alerts")
            val deadline = System.nanoTime() + 180_000_000_000L
            val times = ArrayList<Long>()
            while (times.size < 300 && System.nanoTime() < deadline) {
                val before = fieldSets
                val t = System.nanoTime()
                engine.evaluate(tick, "tick")
                times += ms(t)
                val n = times.size
                if (n <= 5 || n % 10 == 0) note("tick $n: ${times.last()} ms, ${fieldSets - before} field sets")
            }
            dump()
            val sorted = times.sorted()
            note("ticks: ${times.size}, min ${sorted.first()} ms, median ${sorted[sorted.size / 2]} ms, max ${sorted.last()} ms")
            System.gc()
            note("heap after the run: ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB")
            assertTrue(alerts.isEmpty(), "the script reported errors: $alerts")
            assertTrue(fields.keys.count { it.startsWith("field_") } > 100, "no screen rows were written")
        }
    }

    /** The repo-root `corpus/<sub>` directory, found by walking up to settings.gradle.kts. */
    private fun repoCorpus(sub: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").exists()) return File(d, "corpus/$sub")
            d = d.parentFile
        }
        return null
    }

    private companion object {
        /** DoomPDF's three spread sites, verbatim, and the same code without spread. */
        val SPREAD_REWRITES = listOf(
            "result.push(...bytes.slice(0,3 - (str[4*i+2]==\"=\") - (str[4*i+3]==\"=\")));" to
                "Array.prototype.push.apply(result, bytes.slice(0,3 - (str[4*i+2]==\"=\") - (str[4*i+3]==\"=\")));",
            "[...\"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/\"]" to
                "\"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/\".split(\"\")",
            "[...str.slice(4*i,4*i+4)]" to "str.slice(4*i,4*i+4).split(\"\")",
        )

        /** The smallest field API the script uses: `getField(name).value` as a setter that reports to Kotlin. */
        val PRELUDE = """
            var __fields = {};
            function getField(name) {
              var f = __fields[name];
              if (!f) {
                f = { name: name, _v: "" };
                Object.defineProperty(f, "value", {
                  get: function () { return this._v; },
                  set: function (v) { this._v = String(v); __fieldSet(name, this._v); }
                });
                __fields[name] = f;
              }
              return f;
            }
            var event = { change: "", value: "", rc: true, willCommit: false };
        """.trimIndent()
    }
}
