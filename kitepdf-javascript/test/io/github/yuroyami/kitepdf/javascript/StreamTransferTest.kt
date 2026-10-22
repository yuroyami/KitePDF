package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * Streams that move through postMessage and structuredClone in a book's scripts (#608). Each\n * expected line is what headless Chromium logs for the same chapter.
 */
class StreamTransferTest {

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
          if (!s) { log('done'); console.log('T608\n' + out.join('\n')); return; }
          log('== ' + s[0]);
          var p;
          try { p = s[1](); } catch (e) { log('step threw ' + err(e)); }
          Promise.resolve(p).then(function () { setTimeout(next, 0); }, function (e) { log('step rejected ' + err(e)); setTimeout(next, 0); });
        }


        function source(chunks, name) {
          return new ReadableStream({
            start: function (c) { for (var i = 0; i < chunks.length; i++) c.enqueue(chunks[i]); if (name !== 'open') c.close(); },
            cancel: function (r) { log('source cancel ' + show(r)); }
          });
        }
        step('not transferred', function () {
          tryit('clone readable', function () { structuredClone(new ReadableStream()); return 'ok'; });
          tryit('clone writable', function () { structuredClone(new WritableStream()); return 'ok'; });
          tryit('clone transform', function () { structuredClone(new TransformStream()); return 'ok'; });
          tryit('clone in object', function () { structuredClone({ s: new ReadableStream() }); return 'ok'; });
        });
        step('errors', function () {
          var rs = new ReadableStream(); rs.getReader();
          tryit('locked readable', function () { structuredClone(rs, { transfer: [rs] }); return 'ok'; });
          var ws = new WritableStream(); ws.getWriter();
          tryit('locked writable', function () { structuredClone(ws, { transfer: [ws] }); return 'ok'; });
          var ts = new TransformStream(); ts.readable.getReader();
          tryit('locked transform readable', function () { structuredClone(ts, { transfer: [ts] }); return 'ok'; });
          var ts2 = new TransformStream(); ts2.writable.getWriter();
          tryit('locked transform writable', function () { structuredClone(ts2, { transfer: [ts2] }); return 'ok'; });
          var d = new ReadableStream();
          tryit('duplicate', function () { structuredClone(d, { transfer: [d, d] }); return 'ok'; });
          tryit('after failure locked', function () { return d.locked; });
          var twice = new ReadableStream();
          structuredClone(twice, { transfer: [twice] });
          tryit('twice', function () { structuredClone(twice, { transfer: [twice] }); return 'ok'; });
          var buf = new ArrayBuffer(4), mixed = new ReadableStream(); mixed.getReader();
          var lk = new ReadableStream(); lk.getReader();
          tryit('function and locked', function () { structuredClone([function f() {}, lk], { transfer: [lk] }); return 'ok'; });
          var fr = new ReadableStream();
          tryit('function and free', function () { try { structuredClone([function g() {}, fr], { transfer: [fr] }); } catch (e) { return err(e) + ' locked ' + fr.locked; } return 'ok'; });
          var mc0 = new MessageChannel();
          tryit('port and locked', function () { try { structuredClone(0, { transfer: [lk, mc0.port1, mc0.port1] }); } catch (e) { return err(e); } return 'ok'; });
          tryit('buffer kept on failure', function () { try { structuredClone([buf, mixed], { transfer: [buf, mixed] }); } catch (e) { return e.name + ' ' + buf.byteLength; } return 'ok'; });
        });
        step('readable', function () {
          var rs = source(['a', 'b', { n: 1 }]);
          var c = structuredClone(rs, { transfer: [rs] });
          log('type ' + Object.prototype.toString.call(c) + ' same ' + (c === rs) + ' original locked ' + rs.locked + ' clone locked ' + c.locked);
          return readAll(c.getReader(), 'clone');
        });
        step('readable in object', function () {
          var rs = source([1]);
          var o = structuredClone({ s: rs, t: rs }, { transfer: [rs] });
          log('same in clone ' + (o.s === o.t) + ' ' + Object.prototype.toString.call(o.s));
          return readAll(o.s.getReader(), 'object');
        });
        step('only in list', function () {
          var rs = source([1]);
          var out = structuredClone(5, { transfer: [rs] });
          log('value ' + out + ' locked ' + rs.locked);
        });
        step('readable cancel', function () {
          var rs = source(['x'], 'open');
          var c = structuredClone(rs, { transfer: [rs] });
          var r = c.getReader();
          return r.read().then(function (x) { log('first ' + show(x)); return done(r.cancel('stop'), 'cancel'); }).then(function () {
            return new Promise(function (res) { setTimeout(res, 20); });
          });
        });
        step('readable error', function () {
          var ctl;
          var rs = new ReadableStream({ start: function (c) { ctl = c; } });
          var c = structuredClone(rs, { transfer: [rs] });
          var r = c.getReader();
          setTimeout(function () { ctl.error(new TypeError('boom')); }, 5);
          return done(r.read(), 'read after error').then(function () { return done(r.closed, 'closed'); });
        });
        step('readable error not clonable', function () {
          var ctl;
          var rs = new ReadableStream({ start: function (c) { ctl = c; } });
          var c = structuredClone(rs, { transfer: [rs] });
          var r = c.getReader();
          setTimeout(function () { ctl.error(function () {}); }, 5);
          return done(r.read(), 'read after odd error');
        });
        step('chunk not clonable', function () {
          var rs = new ReadableStream({ start: function (c) { c.enqueue('ok'); c.enqueue(function () {}); c.enqueue('after'); }, cancel: function (r) { log('bad chunk cancel ' + err(r)); } });
          var c = structuredClone(rs, { transfer: [rs] });
          return readAll(c.getReader(), 'bad chunk').then(function () { return new Promise(function (res) { setTimeout(res, 20); }); });
        });
        step('writable', function () {
          var ws = new WritableStream({
            write: function (chunk) { log('sink write ' + show(chunk)); },
            close: function () { log('sink close'); }
          });
          var c = structuredClone(ws, { transfer: [ws] });
          log('type ' + Object.prototype.toString.call(c) + ' original locked ' + ws.locked);
          var w = c.getWriter();
          log('desired ' + w.desiredSize);
          return Promise.all([done(w.write('one'), 'write one'), done(w.write({ k: [2] }), 'write two'), done(w.close(), 'close')]).then(function () {
            return new Promise(function (res) { setTimeout(res, 20); });
          });
        });
        step('writable abort', function () {
          var ws = new WritableStream({ abort: function (r) { log('sink abort ' + show(r)); } });
          var c = structuredClone(ws, { transfer: [ws] });
          return done(c.abort('why'), 'abort').then(function () { return new Promise(function (res) { setTimeout(res, 20); }); });
        });
        step('writable sink error', function () {
          var ws = new WritableStream({ write: function () { throw new RangeError('sink no'); } });
          var c = structuredClone(ws, { transfer: [ws] });
          var w = c.getWriter();
          return done(w.write('a'), 'write').then(function () { return new Promise(function (res) { setTimeout(res, 20); }); }).then(function () {
            return done(w.write('b'), 'write after error');
          }).then(function () { return done(w.closed, 'writer closed'); });
        });
        step('transform', function () {
          var ts = new TransformStream({ transform: function (chunk, c) { c.enqueue(String(chunk).toUpperCase()); } });
          var c = structuredClone(ts, { transfer: [ts] });
          log('type ' + Object.prototype.toString.call(c) + ' locked ' + ts.readable.locked + ' ' + ts.writable.locked);
          var w = c.writable.getWriter();
          w.write('ab'); w.write('cd'); w.close();
          return readAll(c.readable.getReader(), 'transform');
        });
        step('transform parts', function () {
          var ts = new TransformStream();
          var o = structuredClone({ t: ts, r: ts.readable }, { transfer: [ts] });
          log('readable kept ' + (o.r === o.t.readable));
        });
        step('post message', function () {
          var mc = new MessageChannel();
          var rs = source(['via port']);
          var got = new Promise(function (res) { mc.port2.onmessage = function (e) { res(e.data); }; });
          mc.port1.postMessage(rs, [rs]);
          log('posted locked ' + rs.locked);
          return got.then(function (s) { log('received ' + Object.prototype.toString.call(s)); return readAll(s.getReader(), 'port'); });
        });
        step('window post message', function () {
          var rs = source(['via window']);
          var got = new Promise(function (res) { window.addEventListener('message', function f(e) { window.removeEventListener('message', f); res(e.data); }); });
          window.postMessage({ s: rs }, '*', [rs]);
          return got.then(function (d) { return readAll(d.s.getReader(), 'window'); });
        });
        step('backpressure', function () {
          var pulls = 0;
          var rs = new ReadableStream({ pull: function (c) { pulls++; if (pulls <= 5) c.enqueue(pulls); else c.close(); } }, { highWaterMark: 0 });
          var c = structuredClone(rs, { transfer: [rs] });
          return new Promise(function (res) { setTimeout(res, 30); }).then(function () {
            log('pulls before read ' + pulls);
            return readAll(c.getReader(), 'pulled');
          }).then(function () { log('pulls ' + pulls); });
        });
        step('bytes', function () {
          var rs = new ReadableStream({ type: 'bytes', start: function (c) { c.enqueue(new Uint8Array([1, 2, 3])); c.close(); } });
          var c = structuredClone(rs, { transfer: [rs] });
          tryit('byob on clone', function () { c.getReader({ mode: 'byob' }); return 'ok'; });
          return readAll(c.getReader(), 'bytes');
        });
        setTimeout(next, 0);
    """.trimIndent()

    private val chromium = listOf(
        """== not transferred""",
        """clone readable threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A ReadableStream could not be cloned because it was not transferred.""",
        """clone writable threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A WritableStream could not be cloned because it was not transferred.""",
        """clone transform threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A TransformStream could not be cloned because it was not transferred.""",
        """clone in object threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A ReadableStream could not be cloned because it was not transferred.""",
        """== errors""",
        """locked readable threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A ReadableStream could not be cloned because it was locked""",
        """locked writable threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A WritableStream could not be cloned because it was locked""",
        """locked transform readable threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A TransformStream could not be cloned because it was locked""",
        """locked transform writable threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A TransformStream could not be cloned because it was locked""",
        """duplicate threw DataCloneError: Failed to execute 'structuredClone' on 'Window': ReadableStream at index 1 is a duplicate of an earlier ReadableStream.""",
        """after failure locked false""",
        """twice threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A ReadableStream could not be cloned because it was locked""",
        """function and locked threw DataCloneError: Failed to execute 'structuredClone' on 'Window': function f() {} could not be cloned.""",
        """function and free DataCloneError: Failed to execute 'structuredClone' on 'Window': function g() {} could not be cloned. locked false""",
        """port and locked DataCloneError: Failed to execute 'structuredClone' on 'Window': Message port at index 2 is a duplicate of an earlier port.""",
        """buffer kept on failure DataCloneError 4""",
        """== readable""",
        """type [object ReadableStream] same false original locked true clone locked false""",
        """clone read {done:false,value:"a"}""",
        """clone read {done:false,value:"b"}""",
        """clone read {done:false,value:{n:1}}""",
        """clone read {done:true,value:undefined}""",
        """== readable in object""",
        """same in clone true [object ReadableStream]""",
        """object read {done:false,value:1}""",
        """object read {done:true,value:undefined}""",
        """== only in list""",
        """value 5 locked true""",
        """== readable cancel""",
        """first {done:false,value:"x"}""",
        """cancel fulfilled undefined""",
        """source cancel "stop"""",
        """== readable error""",
        """read after error rejected TypeError: boom""",
        """closed rejected TypeError: boom""",
        """== readable error not clonable""",
        """read after odd error rejected DataCloneError: function () {} could not be cloned.""",
        """== chunk not clonable""",
        """bad chunk read {done:false,value:"ok"}""",
        """bad chunk cancel DataCloneError: function () {} could not be cloned.""",
        """bad chunk read rejected DataCloneError: function () {} could not be cloned.""",
        """== writable""",
        """type [object WritableStream] original locked true""",
        """desired 1""",
        """write one fulfilled undefined""",
        """sink write "one"""",
        """write two fulfilled undefined""",
        """close fulfilled undefined""",
        """sink write {k:[2]}""",
        """sink close""",
        """== writable abort""",
        """abort fulfilled undefined""",
        """sink abort "why"""",
        """== writable sink error""",
        """write fulfilled undefined""",
        """write after error rejected RangeError: sink no""",
        """writer closed rejected RangeError: sink no""",
        """== transform""",
        """type [object TransformStream] locked true true""",
        """transform read {done:false,value:"AB"}""",
        """transform read {done:false,value:"CD"}""",
        """transform read {done:true,value:undefined}""",
        """== transform parts""",
        """step threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A ReadableStream could not be cloned because it was not transferred.""",
        """== post message""",
        """posted locked true""",
        """received [object ReadableStream]""",
        """port read {done:false,value:"via port"}""",
        """port read {done:true,value:undefined}""",
        """== window post message""",
        """window read {done:false,value:"via window"}""",
        """window read {done:true,value:undefined}""",
        """== backpressure""",
        """pulls before read 1""",
        """pulled read {done:false,value:1}""",
        """pulled read {done:false,value:2}""",
        """pulled read {done:false,value:3}""",
        """pulled read {done:false,value:4}""",
        """pulled read {done:false,value:5}""",
        """pulled read {done:true,value:undefined}""",
        """pulls 6""",
        """== bytes""",
        """byob on clone threw TypeError: Failed to execute 'getReader' on 'ReadableStream': Cannot use a BYOB reader with a non-byte stream""",
        """bytes read {done:false,value:Uint8Array[1,2,3]@0/3}""",
        """bytes read {done:true,value:undefined}""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("T608") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_streams_move_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_streams_move_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
