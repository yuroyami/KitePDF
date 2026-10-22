package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * The parts of the Streams Standard that Chromium does not have yet (#536): ReadableStream.from(),\n * a transformer's cancel() and the argument count of a source's cancel(). Each expected line is what\n * Node.js 26 logs for the same script, as its streams follow the current standard.
 */
class StreamsSpecTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function log(s) { out.push(s); }
        function err(e) { return e && typeof e === 'object' && 'name' in e ? e.name + ': ' + e.message : 'value ' + String(e); }
        function tryit(name, f) { try { log(name + ' ' + f()); } catch (e) { log(name + ' threw ' + err(e)); } }
        function show(v) {
          if (v === undefined) return 'undefined';
          if (v === null || typeof v !== 'object') return JSON.stringify(v);
          if (ArrayBuffer.isView(v)) return Object.prototype.toString.call(v).slice(8, -1) + '[' + Array.prototype.join.call(new Uint8Array(v.buffer, v.byteOffset, v.byteLength), ',') + ']@' + v.byteOffset + '/' + v.buffer.byteLength;
          if (Array.isArray(v)) return '[' + v.map(show).join(',') + ']';
          var keys = Object.keys(v);
          return '{' + keys.map(function (k) { return k + ':' + show(v[k]); }).join(',') + '}';
        }
        function names(o) { return Object.getOwnPropertyNames(o).sort().join(','); }
        function done(p, name) { return p.then(function (v) { log(name + ' fulfilled ' + show(v)); }, function (e) { log(name + ' rejected ' + err(e)); }); }
        function readAll(reader, name) {
          return reader.read().then(function (r) { log(name + ' read ' + show(r)); if (!r.done) return readAll(reader, name); }, function (e) { log(name + ' read rejected ' + err(e)); });
        }
        var steps = [];
        function step(name, f) { steps.push([name, f]); }
        function next() {
          var s = steps.shift();
          if (!s) { log('done'); console.log('S536B\n' + out.join('\n')); return; }
          log('== ' + s[0]);
          var p;
          try { p = s[1](); } catch (e) { log('step threw ' + err(e)); }
          Promise.resolve(p).then(function () { setTimeout(next, 0); }, function (e) { log('step rejected ' + err(e)); setTimeout(next, 0); });
        }

        step('from', function () {
          tryit('from 5', function () { try { ReadableStream.from(5); return 'ok'; } catch (e) { return 'threw ' + e.name; } });
          tryit('from null', function () { try { ReadableStream.from(null); return 'ok'; } catch (e) { return 'threw ' + e.name; } });
          var a = ReadableStream.from(['x', Promise.resolve('y'), 'z']);
          return readAll(a.getReader(), 'from array').then(function () {
            var gen = (function* () { yield 1; yield 2; })();
            return readAll(ReadableStream.from(gen).getReader(), 'from generator');
          }).then(function () {
            var asyncIt = { i: 0 };
            asyncIt[Symbol.asyncIterator] = function () { var self = this; return { next: function () { self.i++; return Promise.resolve(self.i > 2 ? { done: true } : { done: false, value: 'async' + self.i }); }, return: function (v) { log('async return ' + show(v)); return Promise.resolve({ done: true }); } }; };
            var s = ReadableStream.from(asyncIt);
            var r = s.getReader();
            return r.read().then(function (x) { log('async read ' + show(x)); return r.cancel('stop it'); }).then(function () { log('async cancelled'); });
          }).then(function () {
            return readAll(ReadableStream.from(new ReadableStream({ start: function (c) { c.enqueue('inner'); c.close(); } })).getReader(), 'from stream');
          }).then(function () {
            var bad = {}; bad[Symbol.iterator] = function () { return { next: function () { return 5; } }; };
            return ReadableStream.from(bad).getReader().read().then(function () { log('bad iterator result fulfilled'); }, function (e) { log('bad iterator result rejected ' + e.name); });
          });
        });

        step('transformer cancel', function () {
          var ts = new TransformStream({ cancel: function (r) { log('transformer cancel ' + show(r) + ' ' + arguments.length); } });
          var w = ts.writable.getWriter();
          return done(ts.readable.cancel('rc'), 'readable cancel').then(function () { return done(w.closed, 'writable closed'); }).then(function () {
            var ts2 = new TransformStream({ cancel: function (r) { log('transformer cancel on abort ' + show(r)); } });
            var r2 = ts2.readable.getReader();
            return done(ts2.writable.abort('ab'), 'abort').then(function () { return done(r2.closed, 'readable closed'); });
          }).then(function () {
            var ts3 = new TransformStream({ cancel: function () { throw new TypeError('cancel threw'); } });
            return done(ts3.readable.cancel('x'), 'throwing cancel');
          }).then(function () {
            var ts4 = new TransformStream({ cancel: function () { return Promise.reject('async no'); } });
            var w4 = ts4.writable.getWriter();
            return done(ts4.readable.cancel('x'), 'rejecting cancel').then(function () { return done(w4.closed, 'writer after rejecting cancel'); });
          }).then(function () {
            var calls = 0, abortPromise;
            var ts5 = new TransformStream({ cancel: function () { if (++calls === 1) abortPromise = ts5.writable.abort('b'); } });
            return new Promise(function (res) { setTimeout(res, 0); }).then(function () {
              return done(ts5.readable.cancel('a'), 'cancel with abort inside');
            }).then(function () { return done(abortPromise, 'abort inside cancel'); }).then(function () { log('cancel calls ' + calls); });
          });
        });
        step('source cancel arguments', function () {
          var s = new ReadableStream({ cancel: function (r) { log('source cancel ' + show(r) + ' ' + arguments.length); } });
          var w = new WritableStream({ abort: function (r) { log('sink abort ' + show(r) + ' ' + arguments.length); } });
          return Promise.all([s.cancel('c'), w.abort('a')]);
        });
        setTimeout(next, 0);
    """.trimIndent()

    private val expected = listOf(
        """== from""",
        """from 5 threw TypeError""",
        """from null threw TypeError""",
        """from array read {done:false,value:"x"}""",
        """from array read {done:false,value:"y"}""",
        """from array read {done:false,value:"z"}""",
        """from array read {done:true,value:undefined}""",
        """from generator read {done:false,value:1}""",
        """from generator read {done:false,value:2}""",
        """from generator read {done:true,value:undefined}""",
        """async read {done:false,value:"async1"}""",
        """async return "stop it"""",
        """async cancelled""",
        """from stream read {done:false,value:"inner"}""",
        """from stream read {done:true,value:undefined}""",
        """bad iterator result rejected TypeError""",
        """== transformer cancel""",
        """transformer cancel "rc" 1""",
        """readable cancel fulfilled undefined""",
        """writable closed rejected value rc""",
        """transformer cancel on abort "ab"""",
        """abort fulfilled undefined""",
        """readable closed rejected value ab""",
        """throwing cancel rejected TypeError: cancel threw""",
        """rejecting cancel rejected value async no""",
        """writer after rejecting cancel rejected value async no""",
        """cancel with abort inside rejected value b""",
        """abort inside cancel rejected value b""",
        """cancel calls 1""",
        """== source cancel arguments""",
        """source cancel "c" 1""",
        """sink abort "a" 1""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("S536B") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_streams_as_the_standard_says(): TestResult = scriptTest {
        assertEquals(expected.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_streams_as_the_standard_says(): TestResult = scriptTest {
        assertEquals(expected.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
