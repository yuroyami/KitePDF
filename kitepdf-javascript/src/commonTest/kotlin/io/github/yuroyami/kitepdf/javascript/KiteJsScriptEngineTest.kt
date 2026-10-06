package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KiteJsScriptEngineTest {

    @Test
    fun a_script_result_comes_back_as_text(): TestResult = scriptTest {
        KiteJsScriptEngine().use { js ->
            assertEquals("5", js.evaluate("2 + 3"))
            assertEquals("a,b", js.evaluate("['a', 'b'].join()"))
            assertNull(js.evaluate("undefined"))
        }
    }

    @Test
    fun a_dotted_host_function_is_callable_from_a_script(): TestResult = scriptTest {
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
    fun a_script_that_never_returns_is_stopped(): TestResult = scriptTest {
        KiteJsScriptEngine(instructionBudget = 100_000).use { js ->
            assertFailsWith<KiteScriptException> { js.evaluate("while (true) {}", "loop") }
            assertEquals("3", js.evaluate("1 + 2"), "the engine still works after the stop")
        }
    }

    @Test
    fun a_thrown_error_and_a_syntax_error_become_script_exceptions(): TestResult = scriptTest {
        KiteJsScriptEngine().use { js ->
            val thrown = assertFailsWith<KiteScriptException> { js.evaluate("throw new Error('boom')", "t") }
            assertTrue("boom" in (thrown.message ?: ""), "the message carries the script's own text: ${thrown.message}")
            assertFailsWith<KiteScriptException> { js.evaluate("var = ;", "s") }
        }
    }

    @Test
    fun a_script_that_recurses_without_end_gets_a_range_error(): TestResult = scriptTest {
        KiteJsScriptEngine().use { js ->
            val thrown = assertFailsWith<KiteScriptException> { js.evaluate("(function f() { return f() + 1; })()", "r") }
            assertTrue("RangeError" in (thrown.message ?: ""), "${thrown.message}")
            assertEquals("3", js.evaluate("1 + 2"), "the engine still works after the overflow")
        }
    }

    @Test
    fun a_runner_thread_takes_a_deep_recursion_as_well(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage(script = "var d = 0; function g() { d++; g(); } try { g(); } catch (e) { console.log(e.name + ' ' + d); }")
        val console = ArrayList<String>()
        EpubScriptRunner(book, onConsole = { _, message -> console += message }).use { scripts ->
            scripts.chapterOpened(0)
            val depth = console.single { it.startsWith("RangeError ") }.substringAfter(' ').toInt()
            // An own thread lets a script use 8 MiB, about 8,400 calls; the web caps it at about 800.
            val floor = if (startScriptThread().let { thread -> thread.isOwnThread.also { thread.close() } }) 5_000 else 700
            assertTrue(depth > floor, "a script recursed only $depth calls deep")
        }
    }
}
