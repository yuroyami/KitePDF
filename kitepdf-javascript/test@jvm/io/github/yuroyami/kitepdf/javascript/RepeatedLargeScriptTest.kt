package io.github.yuroyami.kitepdf.javascript

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A large script that runs many times in one engine runs from one compiled copy. Each run loaded
 * a new copy, which stayed in the engine's native memory until a host GC, and the 6,066th run of
 * a 64 KB script failed with "the engine ran out of memory" (#837).
 */
class RepeatedLargeScriptTest {

    @Test
    fun a_large_script_runs_seven_thousand_times_in_one_engine() {
        val source = "(function () { var x = 0; " + "x += 1; ".repeat(8000) + "return x })()"
        KiteJsScriptEngine().use { js ->
            repeat(7000) { run ->
                assertEquals("8000", js.evaluate(source, "action"), "run ${run + 1}")
            }
        }
    }
}
