package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KiteJsScriptEngineTest {

    @Test
    fun a_script_result_comes_back_as_text() {
        KiteJsScriptEngine().use { js ->
            assertEquals("5", js.evaluate("2 + 3"))
            assertEquals("a,b", js.evaluate("['a', 'b'].join()"))
            assertNull(js.evaluate("undefined"))
        }
    }

    @Test
    fun a_dotted_host_function_is_callable_from_a_script() {
        KiteJsScriptEngine().use { js ->
            var got: List<Any?>? = null
            js.defineFunction("app.alert") { args -> got = args; 1 }
            js.defineValue("app.viewerType", "KitePDF")
            assertEquals("1", js.evaluate("app.alert('hi')"))
            assertEquals(listOf<Any?>("hi"), got)
            assertEquals("KitePDF", js.evaluate("app.viewerType"))
        }
    }

    @Test
    fun a_script_that_never_returns_is_stopped() {
        KiteJsScriptEngine(instructionBudget = 100_000).use { js ->
            assertFailsWith<KiteScriptException> { js.evaluate("while (true) {}", "loop") }
            assertEquals("3", js.evaluate("1 + 2"), "the engine still works after the stop")
        }
    }

    @Test
    fun a_thrown_error_and_a_syntax_error_become_script_exceptions() {
        KiteJsScriptEngine().use { js ->
            val thrown = assertFailsWith<KiteScriptException> { js.evaluate("throw new Error('boom')", "t") }
            assertTrue("boom" in (thrown.message ?: ""), "the message carries the script's own text: ${thrown.message}")
            assertFailsWith<KiteScriptException> { js.evaluate("var = ;", "s") }
        }
    }
}
