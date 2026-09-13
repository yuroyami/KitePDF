package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Compares KiteJS against V8 on the integer and typed-array semantics that
 * compiled C code (asm.js) depends on. The expected values in
 * `resources/doom/v8-semantics.json` were written by Node 26 from the same
 * expressions; `KITEPDF_SEMANTICS_JSON` points at a fresh table. Opt-in through
 * `KITEPDF_DOOM=true`. Never fails; it writes `build/doom/semantics.txt` so a
 * difference can be filed.
 */
class KiteJsSemanticsProbe {

    @Test
    fun which_semantics_match_v8() {
        assumeTrue("Run with KITEPDF_DOOM=true to probe KiteJS.", System.getenv("KITEPDF_DOOM") == "true")
        val json = System.getenv("KITEPDF_SEMANTICS_JSON")?.let(::File)?.takeIf { it.exists() }?.readText()
            ?: javaClass.getResource("/doom/v8-semantics.json")!!.readText()
        val out = File("build/doom").apply { mkdirs() }
        // Minimal parse of the array Node wrote: entries are flat objects of three strings.
        val entry = Regex("\"name\": \"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"expr\": \"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"expected\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
        val report = StringBuilder()
        var same = 0
        var different = 0
        KiteJsScriptEngine(instructionBudget = 0).use { engine ->
            for (m in entry.findAll(json)) {
                val (name, expr, expected) = m.destructured
                val actual = try {
                    engine.evaluate(unescape(expr), name) ?: "null"
                } catch (e: KiteScriptException) {
                    "THROWS " + generateSequence<Throwable>(e) { it.cause }.last().message?.lineSequence()?.first()
                }
                if (actual == unescape(expected)) same++ else {
                    different++
                    report.append("DIFFERENT ").append(name).append('\n')
                        .append("    expr:     ").append(unescape(expr)).append('\n')
                        .append("    v8:       ").append(unescape(expected)).append('\n')
                        .append("    kitejs:   ").append(actual).append('\n')
                }
            }
        }
        report.insert(0, "same: $same, different: $different\n")
        println(report)
        File(out, "semantics.txt").writeText(report.toString())
    }
}
