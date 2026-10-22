package io.github.yuroyami.kitepdf.javascript

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** Runs [body] once the script engine is loaded, which on JavaScript and WebAssembly takes a wait. */
internal fun scriptTest(body: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.minutes) {
    KiteJsScriptEngine.load()
    body()
}
