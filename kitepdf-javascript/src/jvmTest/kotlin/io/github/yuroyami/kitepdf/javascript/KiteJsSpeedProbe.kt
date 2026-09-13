package io.github.yuroyami.kitepdf.javascript

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Times three small kernels shaped like DoomPDF's script (asm.js integer code,
 * string and array code, object property traffic) on KiteJS, twice each so the
 * second run is warm. The kernels live in `resources/doom/kernels.js`, which
 * Node runs as well for the V8 side of the comparison; `KITEPDF_KERNELS_JS`
 * points at another file. Opt-in through `KITEPDF_DOOM=true`. Never fails; it
 * writes `build/doom/speed.txt`.
 */
class KiteJsSpeedProbe {

    @Test
    fun kernel_times() {
        assumeTrue("Run with KITEPDF_DOOM=true to probe KiteJS.", System.getenv("KITEPDF_DOOM") == "true")
        val kernels = System.getenv("KITEPDF_KERNELS_JS")?.let(::File)?.takeIf { it.exists() }?.readText()
            ?: javaClass.getResource("/doom/kernels.js")!!.readText()
        val out = File("build/doom").apply { mkdirs() }
        val report = StringBuilder()
        KiteJsScriptEngine(instructionBudget = 0).use { engine ->
            engine.evaluate(kernels, "kernels")
            for (name in listOf("asm", "strings", "objects")) {
                repeat(2) { pass ->
                    val line = engine.evaluate("runKernel('$name')", name)
                    report.append(if (pass == 0) "cold " else "warm ").append(line).append('\n')
                }
            }
        }
        println(report)
        File(out, "speed.txt").writeText(report.toString())
    }
}
