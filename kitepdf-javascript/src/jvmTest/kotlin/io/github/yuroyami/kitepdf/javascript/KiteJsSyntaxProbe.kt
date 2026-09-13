package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Prints which language features the bundled KiteJS accepts. Never fails: it
 * exists to explain a benchmark result, not to gate one. Opt-in like the
 * benchmark, through `KITEPDF_DOOM=true`.
 */
class KiteJsSyntaxProbe {

    @Test
    fun which_features_parse() {
        assumeTrue("Run with KITEPDF_DOOM=true to probe KiteJS.", System.getenv("KITEPDF_DOOM") == "true")
        val out = File("build/doom").apply { mkdirs() }
        val report = StringBuilder()
        KiteJsScriptEngine(instructionBudget = 0).use { engine ->
            for ((name, source) in PROBES) {
                val result = try {
                    "ok: " + engine.evaluate(source, name)
                } catch (e: KiteScriptException) {
                    "FAIL: " + generateSequence<Throwable>(e) { it.cause }.last().message?.lineSequence()?.first()
                }
                report.append(name.padEnd(28)).append(result).append('\n')
            }
        }
        println(report)
        File(out, "syntax-probe.txt").writeText(report.toString())
    }

    private companion object {
        val PROBES = listOf(
            "spread in array" to "[...'ab'].length",
            "spread in call" to "Math.max(...[1, 2])",
            "spread in push" to "var r = []; r.push(...[1, 2, 3]); r.length",
            "let and const" to "let a = 1; const b = 2; a + b",
            "arrow function" to "[1, 2].map(x => x * 2).join()",
            "for of" to "var s = 0; for (let k of [1, 2]) s += k; s",
            "template literal" to "`a${1 + 1}b`",
            "padStart" to "'7'.padStart(3, 0)",
            "binary literal string" to "+('0b' + '101')",
            "includes" to "'WASD'.includes('W')",
            "defineProperty getter" to "var o = {}; Object.defineProperty(o, 'v', { get: function () { return 4; } }); o.v",
            "literal getter" to "({ get v() { return 5; } }).v",
            "class" to "class A { m() { return 6; } } new A().m()",
            "destructuring" to "var [p, q] = [1, 2]; p + q",
            "default parameter" to "(function (x = 3) { return x; })()",
            "Object.keys" to "Object.keys({ a: 1, b: 2 }).length",
            "typed subarray" to "new Uint8Array([1, 2, 3]).subarray(1).length",
            "typed set" to "var t = new Uint8Array(4); t.set([9, 8], 1); t[1]",
            "ArrayBuffer 16 MB" to "new Int32Array(new ArrayBuffer(16777216)).length",
            "Math.imul" to "Math.imul(0xffffffff, 5)",
            "Math.clz32" to "Math.clz32(1)",
            "Math.fround" to "Math.fround(1.5)",
            "Date.now" to "typeof Date.now()",
            "globalThis" to "typeof globalThis",
            "use asm module" to "(function (g, e, b) { 'use asm'; var h = new g.Int32Array(b); function f(x) { x = x | 0; h[0] = x; return (x + 1) | 0; } return { f: f }; })({ Int32Array: Int32Array }, {}, new ArrayBuffer(65536)).f(41)",
            "String.fromCharCode.apply" to "String.fromCharCode.apply(null, [72, 105])",
            "regex match g" to "'abcdefgh'.match(/.{1,3}/g).length",
            "label and continue" to "var n = 0; outer: for (var i = 0; i < 3; i++) { for (var j = 0; j < 3; j++) { if (j == 1) continue outer; n++; } } n",
            "try catch stack" to "try { null.x } catch (e) { typeof e.stack }",
            "arguments" to "(function () { return arguments.length; })(1, 2, 3)",
        )
    }
}
