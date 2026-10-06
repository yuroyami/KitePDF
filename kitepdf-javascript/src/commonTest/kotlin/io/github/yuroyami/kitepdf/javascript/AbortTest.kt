package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * AbortController and AbortSignal, and the signal option of addEventListener (#607). Each expected\n * line is what headless Chromium logs for the same chapter.
 */
class AbortTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function log(s) { out.push(s); }
        function tryit(name, f) { try { log(name + ' ' + f()); } catch (e) { log(name + ' threw ' + e.name + ': ' + e.message); } }
        function show(v) { return v === null || typeof v !== 'object' ? String(v) : Object.prototype.toString.call(v) + (v instanceof DOMException ? '(' + v.name + ': ' + v.message + ' ' + v.code + ')' : ''); }
        tryit('lengths', function () { return AbortController.length + ' ' + AbortSignal.length + ' ' + AbortSignal.abort.length + ' ' + AbortSignal.timeout.length + ' ' + AbortSignal.any.length; });
        tryit('proto', function () { return (Object.getPrototypeOf(AbortSignal) === EventTarget) + ' ' + Object.getOwnPropertyNames(AbortSignal.prototype).sort().join(',') + ' | ' + Object.getOwnPropertyNames(AbortController.prototype).sort().join(','); });
        tryit('new signal', function () { new AbortSignal(); return 'ok'; });
        tryit('call controller', function () { AbortController(); return 'ok'; });
        var c = new AbortController();
        var s = c.signal;
        tryit('fresh', function () { return show(s) + ' ' + s.aborted + ' ' + s.reason + ' ' + (c.signal === s) + ' ' + show(c); });
        tryit('throwIfAborted fresh', function () { s.throwIfAborted(); return 'ok'; });
        s.addEventListener('abort', function (e) { log('abort listener ' + e.type + ' ' + e.isTrusted + ' ' + e.bubbles + ' ' + e.cancelable + ' ' + show(e) + ' ' + s.aborted + ' ' + show(s.reason)); });
        s.onabort = function (e) { log('onabort ' + (e.target === s)); };
        log('before abort');
        c.abort();
        log('after abort ' + s.aborted + ' ' + show(s.reason));
        c.abort('again');
        log('after second ' + show(s.reason));
        tryit('throwIfAborted', function () { s.throwIfAborted(); return 'ok'; });
        var c2 = new AbortController();
        c2.abort('why');
        tryit('reason string', function () { return show(c2.signal.reason); });
        tryit('throw reason', function () { try { c2.signal.throwIfAborted(); } catch (e) { return 'caught ' + show(e); } });
        var c3 = new AbortController();
        c3.abort(undefined);
        tryit('undefined reason', function () { return show(c3.signal.reason); });
        var c4 = new AbortController(); c4.abort(null);
        tryit('null reason', function () { return show(c4.signal.reason); });
        tryit('static abort', function () { var a = AbortSignal.abort(); return a.aborted + ' ' + show(a.reason) + ' ' + show(AbortSignal.abort(5).reason); });
        tryit('any empty', function () { var a = AbortSignal.any([]); return a.aborted; });
        tryit('any aborted', function () { var a = AbortSignal.any([new AbortController().signal, AbortSignal.abort('first'), AbortSignal.abort('second')]); return a.aborted + ' ' + show(a.reason); });
        tryit('any bad', function () { AbortSignal.any([1]); return 'ok'; });
        tryit('any notiter', function () { AbortSignal.any(5); return 'ok'; });
        tryit('any none', function () { AbortSignal.any(); return 'ok'; });
        var d1 = new AbortController(), d2 = new AbortController();
        var any = AbortSignal.any([d1.signal, d2.signal]);
        var any2 = AbortSignal.any([any]);
        any.addEventListener('abort', function () { log('any abort ' + show(any.reason) + ' ' + any2.aborted); });
        any2.addEventListener('abort', function () { log('any2 abort ' + show(any2.reason)); });
        d1.signal.addEventListener('abort', function () { log('d1 abort ' + any.aborted); });
        d2.abort('d2 reason');
        d1.abort('d1 reason');
        log('after any ' + show(any.reason));
        tryit('timeout bad', function () { AbortSignal.timeout(-1); return 'ok'; });
        tryit('timeout none', function () { AbortSignal.timeout(); return 'ok'; });
        var t = AbortSignal.timeout(5);
        t.onabort = function () { log('timeout fired ' + show(t.reason)); };
        tryit('abort no this', function () { AbortController.prototype.abort.call({}); return 'ok'; });
        tryit('aborted getter no this', function () { Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'aborted').get.call({}); return 'ok'; });
        tryit('tags', function () { return String(new AbortController()) + ' ' + String(new AbortController().signal); });

        var et = new EventTarget();
        var lc = new AbortController();
        et.addEventListener('ping', function () { log('signal listener'); }, { signal: lc.signal });
        et.addEventListener('ping', function () { log('plain listener'); });
        et.dispatchEvent(new Event('ping'));
        lc.abort();
        et.dispatchEvent(new Event('ping'));
        et.addEventListener('ping', function () { log('added with aborted'); }, { signal: lc.signal });
        et.dispatchEvent(new Event('ping'));
        tryit('signal 5', function () { et.addEventListener('ping', function () {}, { signal: 5 }); return 'ok'; });
        tryit('signal null', function () { et.addEventListener('ping', function () {}, { signal: null }); return 'ok'; });
        var order = [];
        var oc = new AbortController();
        oc.signal.addEventListener('abort', function () { order.push('first'); Promise.resolve().then(function () { order.push('first job'); }); });
        oc.signal.addEventListener('abort', function () { order.push('second'); });
        var et2 = new EventTarget();
        et2.addEventListener('x', function () { order.push('removed listener ran'); }, { signal: oc.signal });
        oc.signal.addEventListener('abort', function () { et2.dispatchEvent(new Event('x')); order.push('third'); });
        oc.abort();
        order.push('sync');
        Promise.resolve().then(function () { log('order ' + order.join(',')); });
        var tc = AbortSignal.timeout(1);
        tc.addEventListener('abort', function () { order.push('t first'); Promise.resolve().then(function () { order.push('t job'); }); });
        tc.addEventListener('abort', function () { order.push('t second'); });
        setTimeout(function () { log('timeout order ' + order.join(',')); }, 30);
        setTimeout(function () { log('done'); console.log('A607\n' + out.join('\n')); }, 50);
    """.trimIndent()

    private val chromium = listOf(
        """lengths 0 0 0 1 1""",
        """proto true aborted,constructor,onabort,reason,throwIfAborted | abort,constructor,signal""",
        """new signal threw TypeError: Failed to construct 'AbortSignal': Illegal constructor""",
        """call controller threw TypeError: Failed to construct 'AbortController': Please use the 'new' operator, this DOM object constructor cannot be called as a function.""",
        """fresh [object AbortSignal] false undefined true [object AbortController]""",
        """throwIfAborted fresh ok""",
        """before abort""",
        """abort listener abort true false false [object Event] true [object DOMException](AbortError: signal is aborted without reason 20)""",
        """onabort true""",
        """after abort true [object DOMException](AbortError: signal is aborted without reason 20)""",
        """after second [object DOMException](AbortError: signal is aborted without reason 20)""",
        """throwIfAborted threw AbortError: signal is aborted without reason""",
        """reason string why""",
        """throw reason caught why""",
        """undefined reason [object DOMException](AbortError: signal is aborted without reason 20)""",
        """null reason null""",
        """static abort true [object DOMException](AbortError: signal is aborted without reason 20) 5""",
        """any empty false""",
        """any aborted true first""",
        """any bad threw TypeError: Failed to execute 'any' on 'AbortSignal': Failed to convert value to 'AbortSignal'.""",
        """any notiter threw TypeError: Failed to execute 'any' on 'AbortSignal': The provided value cannot be converted to a sequence.""",
        """any none threw TypeError: Failed to execute 'any' on 'AbortSignal': 1 argument required, but only 0 present.""",
        """any abort d2 reason true""",
        """any2 abort d2 reason""",
        """d1 abort true""",
        """after any d2 reason""",
        """timeout bad threw TypeError: Failed to execute 'timeout' on 'AbortSignal': Value is outside the 'unsigned long long' value range.""",
        """timeout none threw TypeError: Failed to execute 'timeout' on 'AbortSignal': 1 argument required, but only 0 present.""",
        """abort no this threw TypeError: Illegal invocation""",
        """aborted getter no this threw TypeError: Illegal invocation""",
        """tags [object AbortController] [object AbortSignal]""",
        """signal listener""",
        """plain listener""",
        """plain listener""",
        """plain listener""",
        """signal 5 threw TypeError: Failed to execute 'addEventListener' on 'EventTarget': Failed to read the 'signal' property from 'AddEventListenerOptions': Failed to convert value to 'AbortSignal'.""",
        """signal null threw TypeError: Failed to execute 'addEventListener' on 'EventTarget': Failed to read the 'signal' property from 'AddEventListenerOptions': Failed to convert value to 'AbortSignal'.""",
        """order first,second,third,sync,first job""",
        """timeout fired [object DOMException](TimeoutError: signal timed out 23)""",
        """timeout order first,second,third,sync,first job,t first,t job,t second""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("A607") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_aborts_signals_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = false))
    }

    @Test
    fun an_html_chapter_aborts_signals_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = true))
    }
}
