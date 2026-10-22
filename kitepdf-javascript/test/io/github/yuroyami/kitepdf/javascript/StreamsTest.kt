package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * The Streams Standard in a book's scripts (#536): readable, writable and transform streams, their\n * readers, writers and controllers, the queuing strategies, TextEncoderStream, TextDecoderStream\n * and Blob.stream(). Each expected line is what headless Chromium logs for the same chapter.
 */
class StreamsTest {

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
          if (!s) { log('done'); console.log('S536\n' + out.join('\n')); return; }
          log('== ' + s[0]);
          var p;
          try { p = s[1](); } catch (e) { log('step threw ' + err(e)); }
          Promise.resolve(p).then(function () { setTimeout(next, 0); }, function (e) { log('step rejected ' + err(e)); setTimeout(next, 0); });
        }

        step('shape', function () {
          tryit('lengths', function () { return [ReadableStream, ReadableStreamDefaultReader, ReadableStreamBYOBReader, WritableStream, WritableStreamDefaultWriter, TransformStream, CountQueuingStrategy, ByteLengthQueuingStrategy, TextEncoderStream, TextDecoderStream].map(function (c) { return c.name + c.length; }).join(' '); });
          tryit('rs', function () { return names(ReadableStream.prototype); });
          tryit('reader', function () { return names(ReadableStreamDefaultReader.prototype) + ' | ' + names(ReadableStreamBYOBReader.prototype); });
          tryit('controllers', function () { return names(ReadableStreamDefaultController.prototype) + ' | ' + names(ReadableByteStreamController.prototype) + ' | ' + names(ReadableStreamBYOBRequest.prototype); });
          tryit('ws', function () { return names(WritableStream.prototype) + ' | ' + names(WritableStreamDefaultWriter.prototype) + ' | ' + names(WritableStreamDefaultController.prototype); });
          tryit('ts', function () { return names(TransformStream.prototype) + ' | ' + names(TransformStreamDefaultController.prototype); });
          tryit('strategies', function () { return names(CountQueuingStrategy.prototype) + ' | ' + names(ByteLengthQueuingStrategy.prototype); });
          tryit('text streams', function () { return names(TextEncoderStream.prototype) + ' | ' + names(TextDecoderStream.prototype); });
          tryit('async iterator', function () { return (ReadableStream.prototype[Symbol.asyncIterator] === ReadableStream.prototype.values) + ' ' + ReadableStream.prototype.values.name; });
          tryit('controller ctor', function () { new ReadableStreamDefaultController(); return 'ok'; });
          tryit('reader ctor', function () { var r = new ReadableStreamDefaultReader(new ReadableStream()); return Object.prototype.toString.call(r); });
          tryit('reader ctor bad', function () { new ReadableStreamDefaultReader({}); return 'ok'; });
          tryit('call', function () { ReadableStream(); return 'ok'; });
          tryit('blob', function () { return typeof Blob.prototype.stream + ' ' + typeof Blob.prototype.textStream; });
        });
        step('constructor errors', function () {
          tryit('type x', function () { new ReadableStream({ type: 'x' }); return 'ok'; });
          tryit('start 5', function () { new ReadableStream({ start: 5 }); return 'ok'; });
          tryit('pull 5', function () { new ReadableStream({ pull: 5 }); return 'ok'; });
          tryit('hwm -1', function () { new ReadableStream({}, { highWaterMark: -1 }); return 'ok'; });
          tryit('hwm NaN', function () { new ReadableStream({}, { highWaterMark: NaN }); return 'ok'; });
          tryit('size 5', function () { new ReadableStream({}, { size: 5 }); return 'ok'; });
          tryit('bytes size', function () { new ReadableStream({ type: 'bytes' }, { size: function () { return 1; } }); return 'ok'; });
          tryit('auto 0', function () { new ReadableStream({ type: 'bytes', autoAllocateChunkSize: 0 }); return 'ok'; });
          tryit('source 5', function () { new ReadableStream(5); return 'ok'; });
          tryit('source null', function () { new ReadableStream(null); return 'ok'; });
          tryit('strategy 5', function () { new ReadableStream({}, 5); return 'ok'; });
          tryit('start throws', function () { new ReadableStream({ start: function () { throw new RangeError('boom'); } }); return 'ok'; });
          tryit('ws type', function () { new WritableStream({ type: 'bytes' }); return 'ok'; });
          tryit('ts readableType', function () { new TransformStream({ readableType: 'bytes' }); return 'ok'; });
          tryit('ts writableType', function () { new TransformStream({ writableType: 'bytes' }); return 'ok'; });
        });
        step('default stream', function () {
          var ctl;
          var rs = new ReadableStream({
            start: function (c) { ctl = c; log('start ' + c.desiredSize + ' ' + Object.prototype.toString.call(c)); c.enqueue('a'); log('after enqueue ' + c.desiredSize); },
            pull: function (c) { log('pull ' + c.desiredSize); },
          }, { highWaterMark: 2 });
          log('constructed ' + rs.locked);
          var reader = rs.getReader();
          log('locked ' + rs.locked + ' ' + Object.prototype.toString.call(reader));
          reader.closed.then(function () { log('closed fulfilled'); });
          return Promise.resolve().then(function () {
            log('tick');
            var p1 = reader.read(), p2 = reader.read();
            log('reads made ' + ctl.desiredSize);
            ctl.enqueue('b');
            ctl.enqueue('c');
            log('enqueued ' + ctl.desiredSize);
            ctl.close();
            tryit('enqueue closed', function () { ctl.enqueue('d'); return 'ok'; });
            tryit('close twice', function () { ctl.close(); return 'ok'; });
            tryit('desired closing', function () { return ctl.desiredSize; });
            return Promise.all([done(p1, 'p1'), done(p2, 'p2'), readAll(reader, 'rest')]);
          }).then(function () {
            tryit('desired closed', function () { return ctl.desiredSize; });
            tryit('error closed', function () { ctl.error(new Error('late')); return 'ok'; });
          });
        });
        step('pull order', function () {
          var n = 0;
          var rs = new ReadableStream({
            pull: function (c) { n++; log('pull ' + n + ' ' + c.desiredSize); if (n <= 3) c.enqueue(n); else c.close(); },
          }, new CountQueuingStrategy({ highWaterMark: 2 }));
          var reader = rs.getReader();
          return readAll(reader, 'pulled');
        });
        step('pull promise', function () {
          var n = 0;
          var rs = new ReadableStream({
            pull: function (c) { n++; log('pull ' + n); return new Promise(function (r) { setTimeout(function () { log('pull ' + n + ' settles'); c.enqueue('x' + n); if (n === 2) c.close(); r(); }, 1); }); },
          });
          return readAll(rs.getReader(), 'slow');
        });
        step('error', function () {
          var ctl;
          var rs = new ReadableStream({ start: function (c) { ctl = c; } });
          var reader = rs.getReader();
          var p = reader.read();
          ctl.error(new TypeError('bad'));
          tryit('enqueue errored', function () { ctl.enqueue(1); return 'ok'; });
          tryit('close errored', function () { ctl.close(); return 'ok'; });
          tryit('desired errored', function () { return ctl.desiredSize; });
          return Promise.all([done(p, 'pending read'), done(reader.closed, 'closed'), done(reader.read(), 'later read'), done(rs.cancel(), 'cancel locked')]);
        });
        step('cancel', function () {
          var rs = new ReadableStream({
            start: function (c) { c.enqueue(1); },
            cancel: function (r) { log('underlying cancel ' + show(r)); return 'ignored'; },
          });
          var reader = rs.getReader();
          return Promise.all([done(reader.cancel('why'), 'reader cancel'), done(reader.read(), 'read after cancel'), done(reader.closed, 'closed')]).then(function () {
            var rs2 = new ReadableStream({ cancel: function () { throw new RangeError('cancel threw'); } });
            return done(rs2.cancel(), 'cancel throws');
          }).then(function () {
            var rs3 = new ReadableStream({ cancel: function () { return Promise.reject(new SyntaxError('cancel rejects')); } });
            return done(rs3.cancel(), 'cancel rejects');
          });
        });
        step('locking', function () {
          var rs = new ReadableStream();
          var r1 = rs.getReader();
          tryit('second reader', function () { rs.getReader(); return 'ok'; });
          tryit('reader ctor locked', function () { new ReadableStreamDefaultReader(rs); return 'ok'; });
          var pending = r1.read();
          var closed = r1.closed;
          r1.releaseLock();
          log('released ' + rs.locked + ' ' + (r1.closed !== closed));
          return Promise.all([done(pending, 'pending on release'), done(closed, 'old closed'), done(r1.closed, 'new closed'), done(r1.read(), 'read released'), done(r1.cancel(), 'cancel released')]).then(function () {
            tryit('release twice', function () { r1.releaseLock(); return 'ok'; });
            tryit('mode x', function () { rs.getReader({ mode: 'x' }); return 'ok'; });
            tryit('byob on default', function () { rs.getReader({ mode: 'byob' }); return 'ok'; });
            tryit('options 5', function () { rs.getReader(5); return 'ok'; });
            tryit('getter no this', function () { Object.getOwnPropertyDescriptor(ReadableStream.prototype, 'locked').get.call({}); return 'ok'; });
            return done(ReadableStream.prototype.cancel.call({}), 'cancel no this');
          });
        });
        step('tee', function () {
          var rs = new ReadableStream({
            start: function (c) { c.enqueue('t1'); c.enqueue({ o: 1 }); c.close(); },
            cancel: function (r) { log('tee cancel ' + show(r)); },
          });
          var branches = rs.tee();
          log('tee ' + branches.length + ' ' + rs.locked + ' ' + Array.isArray(branches));
          return Promise.all([readAll(branches[0].getReader(), 'b0'), readAll(branches[1].getReader(), 'b1')]).then(function () {
            var src = new ReadableStream({ cancel: function (r) { log('composite ' + show(r)); } });
            var b = src.tee();
            return Promise.all([done(b[0].cancel('r0'), 'cancel b0'), done(b[1].cancel('r1'), 'cancel b1')]);
          }).then(function () {
            var ctl;
            var src = new ReadableStream({ start: function (c) { ctl = c; } });
            var b = src.tee();
            var p = Promise.all([done(b[0].getReader().closed, 'b0 closed'), done(b[1].getReader().closed, 'b1 closed')]);
            ctl.error(new Error('tee error'));
            return p;
          });
        });
        step('async iteration', function () {
          var rs = new ReadableStream({ start: function (c) { c.enqueue(1); c.enqueue(2); c.enqueue(3); c.close(); }, cancel: function (r) { log('iter cancel ' + show(r)); } });
          var it = rs.values();
          log('iterator ' + Object.prototype.toString.call(it) + ' ' + names(Object.getPrototypeOf(it)) + ' ' + (typeof it[Symbol.asyncIterator]) + ' ' + rs.locked);
          return it.next().then(function (r) { log('next ' + show(r)); return it.return('stop'); }).then(function (r) {
            log('return ' + show(r) + ' ' + rs.locked);
            return it.next();
          }).then(function (r) {
            log('next after return ' + show(r));
            var rs2 = new ReadableStream({ start: function (c) { c.enqueue('k'); }, cancel: function () { log('should not cancel'); } });
            var it2 = rs2[Symbol.asyncIterator]({ preventCancel: true });
            return it2.next().then(function (r) { log('it2 ' + show(r)); return it2.return(); }).then(function (r) { log('it2 return ' + show(r) + ' ' + rs2.locked); });
          }).then(function () {
            var rs3 = new ReadableStream({ start: function (c) { c.error(new RangeError('iter error')); } });
            return done(rs3.values().next(), 'errored next');
          });
        });
        step('byte stream byob', function () {
          var ctl;
          var rs = new ReadableStream({ type: 'bytes', start: function (c) { ctl = c; log('byte start ' + Object.prototype.toString.call(c) + ' ' + c.desiredSize + ' ' + c.byobRequest); } });
          var reader = rs.getReader({ mode: 'byob' });
          log('byob reader ' + Object.prototype.toString.call(reader));
          var p = reader.read(new Uint8Array(4));
          return Promise.resolve().then(function () {
            var req = ctl.byobRequest;
            log('request ' + Object.prototype.toString.call(req) + ' ' + show(req.view));
            req.view[0] = 7; req.view[1] = 8;
            req.respond(2);
            log('responded ' + ctl.byobRequest);
            return done(p, 'byob read');
          }).then(function () {
            ctl.enqueue(new Uint8Array([1, 2, 3, 4, 5, 6]));
            return done(reader.read(new Uint8Array(4)), 'byob from queue');
          }).then(function () {
            return done(reader.read(new Uint16Array(2)), 'byob u16 rest');
          }).then(function () {
            var p2 = reader.read(new Uint8Array(3), { min: 3 });
            ctl.enqueue(new Uint8Array([9]));
            ctl.enqueue(new Uint8Array([10, 11, 12]));
            return done(p2, 'byob min');
          }).then(function () {
            tryit('read empty view', function () { return reader.read(new Uint8Array(0)).then(function () {}, function (e) { log('empty view rejected ' + err(e)); }) && 'returned'; });
            return done(reader.read(new Uint8Array(0)), 'zero view');
          }).then(function () {
            return done(reader.read(5), 'read 5');
          }).then(function () {
            return done(reader.read(new Uint8Array(2), { min: 3 }), 'min too big');
          }).then(function () {
            ctl.close();
            return done(reader.read(new Uint8Array(2)), 'byob after close');
          }).then(function () {
            return done(reader.closed, 'byob closed');
          });
        });
        step('byte stream default reader', function () {
          var n = 0;
          var rs = new ReadableStream({ type: 'bytes', autoAllocateChunkSize: 3, pull: function (c) { n++; var r = c.byobRequest; log('auto pull ' + n + ' ' + (r && show(r.view))); if (n > 2) { c.close(); r.respond(0); return; } r.view[0] = n; r.respond(1); } });
          return readAll(rs.getReader(), 'auto').then(function () {
            var ctl;
            var rs2 = new ReadableStream({ type: 'bytes', start: function (c) { ctl = c; } });
            var r = rs2.getReader();
            var p = r.read();
            ctl.enqueue(new Uint8Array([5, 6]));
            tryit('enqueue empty', function () { ctl.enqueue(new Uint8Array(0)); return 'ok'; });
            tryit('enqueue not view', function () { ctl.enqueue('x'); return 'ok'; });
            tryit('respond no request', function () { return ctl.byobRequest; });
            return done(p, 'default on bytes');
          }).then(function () {
            var ctl;
            var rs3 = new ReadableStream({ type: 'bytes', start: function (c) { ctl = c; c.enqueue(new Uint8Array([1, 2, 3])); c.close(); } });
            var b = rs3.tee();
            return Promise.all([readAll(b[0].getReader(), 'bt0'), readAll(b[1].getReader(), 'bt1')]);
          }).then(function () {
            var ctl;
            var rs4 = new ReadableStream({ type: 'bytes', start: function (c) { ctl = c; } });
            var r4 = rs4.getReader({ mode: 'byob' });
            var buf = new ArrayBuffer(4);
            var view = new Uint8Array(buf, 1, 2);
            var p = r4.read(view);
            log('view detached ' + buf.byteLength + ' ' + buf.detached);
            return Promise.resolve().then(function () {
              var req = ctl.byobRequest;
              log('req view ' + show(req.view));
              tryit('respond too big', function () { req.respond(3); return 'ok'; });
              var nv = new Uint8Array(req.view.buffer, req.view.byteOffset, 1);
              nv[0] = 42;
              req.respondWithNewView(nv);
              return done(p, 'new view');
            });
          });
        });
        step('writable', function () {
          var ctl;
          var ws = new WritableStream({
            start: function (c) { ctl = c; log('ws start ' + Object.prototype.toString.call(c) + ' ' + Object.prototype.toString.call(c.signal)); },
            write: function (chunk, c) { log('sink write ' + show(chunk) + ' ' + (c === ctl)); return new Promise(function (r) { setTimeout(r, 1); }); },
            close: function () { log('sink close ' + arguments.length); },
            abort: function (r) { log('sink abort ' + show(r)); },
          }, { highWaterMark: 2 });
          var w = ws.getWriter();
          log('writer ' + Object.prototype.toString.call(w) + ' ' + w.desiredSize + ' ' + ws.locked);
          w.ready.then(function () { log('ready 1'); });
          var p1 = w.write('w1');
          log('after w1 ' + w.desiredSize);
          var p2 = w.write('w2');
          log('after w2 ' + w.desiredSize);
          var p3 = w.write('w3');
          log('after w3 ' + w.desiredSize);
          w.ready.then(function () { log('ready 2 ' + w.desiredSize); });
          var pc = w.close();
          tryit('write after close', function () { w.write('late').then(null, function (e) { log('late write rejected ' + err(e)); }); return 'returned'; });
          return Promise.all([done(p1, 'w1'), done(p2, 'w2'), done(p3, 'w3'), done(pc, 'close'), done(w.closed, 'closed')]).then(function () {
            log('desired closed ' + w.desiredSize);
            return done(w.close(), 'close twice');
          }).then(function () {
            var ws2 = new WritableStream({ write: function () { log('ws2 write'); }, abort: function (r) { log('ws2 sink abort ' + show(r)); } });
            var w2 = ws2.getWriter();
            var pw = w2.write('x');
            var pa = w2.abort('reason a');
            return Promise.all([done(pw, 'write before abort'), done(pa, 'abort'), done(w2.closed, 'closed after abort'), done(w2.ready, 'ready after abort'), done(w2.write('y'), 'write after abort')]);
          }).then(function () {
            var ws3 = new WritableStream({ write: function () { throw new RangeError('sink write threw'); } });
            var w3 = ws3.getWriter();
            return Promise.all([done(w3.write('a'), 'write threw'), done(w3.closed, 'closed errored'), done(w3.write('b'), 'write errored')]);
          }).then(function () {
            var ws4 = new WritableStream();
            var w4 = ws4.getWriter();
            tryit('second writer', function () { ws4.getWriter(); return 'ok'; });
            w4.releaseLock();
            return Promise.all([done(w4.closed, 'released closed'), done(w4.ready, 'released ready'), done(w4.write(1), 'released write')]).then(function () {
              tryit('released desired', function () { return w4.desiredSize; });
              var ctl5;
              var ws5 = new WritableStream({ start: function (c) { ctl5 = c; } });
              var w5 = ws5.getWriter();
              ctl5.error(new SyntaxError('ctl error'));
              return Promise.all([done(w5.closed, 'controller error closed'), done(ws5.abort(), 'abort locked')]);
            });
          });
        });
        step('transform', function () {
          var ts = new TransformStream({
            start: function (c) { log('ts start ' + Object.prototype.toString.call(c) + ' ' + c.desiredSize); },
            transform: function (chunk, c) { log('transform ' + show(chunk)); c.enqueue(String(chunk).toUpperCase()); c.enqueue('!'); },
            flush: function (c) { log('flush'); c.enqueue('end'); },
          });
          var w = ts.writable.getWriter();
          var r = ts.readable.getReader();
          w.write('ab');
          w.write('cd');
          w.close();
          return readAll(r, 'ts').then(function () {
            var id = new TransformStream();
            var iw = id.writable.getWriter(), ir = id.readable.getReader();
            log('identity desired ' + iw.desiredSize);
            var pw = iw.write('same');
            log('identity after write ' + iw.desiredSize);
            return Promise.all([done(pw, 'identity write'), done(ir.read(), 'identity read')]);
          }).then(function () {
            var ts2 = new TransformStream({ transform: function (chunk, c) { c.terminate(); log('terminated'); tryit('enqueue terminated', function () { c.enqueue(1); return 'ok'; }); } });
            var w2 = ts2.writable.getWriter(), r2 = ts2.readable.getReader();
            return Promise.all([done(w2.write('t'), 'write terminate'), done(r2.read(), 'read terminate'), done(w2.closed, 'writable after terminate')]);
          }).then(function () {
            var ts3 = new TransformStream({ transform: function () { throw new TypeError('transform threw'); } });
            var w3 = ts3.writable.getWriter(), r3 = ts3.readable.getReader();
            return Promise.all([done(w3.write('e'), 'write error'), done(r3.read(), 'read error')]);
          }).then(function () {
            var ts4 = new TransformStream(), w4 = ts4.writable.getWriter();
            return done(ts4.readable.cancel('rc'), 'readable cancel').then(function () { return done(w4.closed, 'writable after cancel'); });
          });
        });
        step('pipe', function () {
          var log2 = [];
          var src = new ReadableStream({ start: function (c) { c.enqueue('p1'); c.enqueue('p2'); c.close(); } });
          var dst = new WritableStream({ write: function (c) { log('dest write ' + c); }, close: function () { log('dest close'); } });
          var p = src.pipeTo(dst);
          log('piping ' + src.locked + ' ' + dst.locked);
          return done(p, 'pipeTo').then(function () {
            var src2 = new ReadableStream({ start: function (c) { c.enqueue('q'); c.close(); } });
            var dst2 = new WritableStream({ close: function () { log('should not close'); } });
            return done(src2.pipeTo(dst2, { preventClose: true }), 'preventClose').then(function () { log('dst2 locked ' + dst2.locked); });
          }).then(function () {
            var src3 = new ReadableStream({ start: function (c) { c.error(new RangeError('src error')); } });
            var dst3 = new WritableStream({ abort: function (r) { log('dst3 abort ' + err(r)); } });
            return done(src3.pipeTo(dst3), 'source errored');
          }).then(function () {
            var src4 = new ReadableStream({ cancel: function (r) { log('src4 cancel ' + err(r)); } });
            var dst4 = new WritableStream({ start: function (c) { c.error(new SyntaxError('dest error')); } });
            return done(src4.pipeTo(dst4), 'dest errored');
          }).then(function () {
            var ac = new AbortController();
            var src5 = new ReadableStream({ cancel: function (r) { log('src5 cancel ' + err(r)); } });
            var dst5 = new WritableStream({ abort: function (r) { log('dst5 abort ' + err(r)); } });
            var p5 = src5.pipeTo(dst5, { signal: ac.signal });
            ac.abort();
            return done(p5, 'signal abort');
          }).then(function () {
            var src6 = new ReadableStream();
            src6.getReader();
            return Promise.all([done(src6.pipeTo(new WritableStream()), 'locked source'), done(new ReadableStream().pipeTo(5), 'bad dest'), done(new ReadableStream().pipeTo(new WritableStream(), { signal: 5 }), 'bad signal')]);
          }).then(function () {
            var src7 = new ReadableStream({ start: function (c) { c.enqueue('x'); c.enqueue('y'); c.close(); } });
            var ts = new TransformStream({ transform: function (ch, c) { c.enqueue(ch + ch); } });
            var r = src7.pipeThrough(ts);
            log('pipeThrough ' + (r === ts.readable) + ' ' + src7.locked);
            tryit('pipeThrough bad', function () { new ReadableStream().pipeThrough({}); return 'ok'; });
            tryit('pipeThrough locked', function () { var s = new ReadableStream(); s.getReader(); s.pipeThrough(new TransformStream()); return 'ok'; });
            return readAll(r.getReader(), 'through');
          });
        });
        step('strategies', function () {
          var c = new CountQueuingStrategy({ highWaterMark: 3 });
          var b = new ByteLengthQueuingStrategy({ highWaterMark: 5 });
          tryit('count', function () { return c.highWaterMark + ' ' + c.size() + ' ' + c.size('x') + ' ' + c.size.name + ' ' + c.size.length + ' ' + (c.size === new CountQueuingStrategy({ highWaterMark: 1 }).size); });
          tryit('bytes', function () { return b.highWaterMark + ' ' + b.size({ byteLength: 7 }) + ' ' + b.size.name + ' ' + b.size.length; });
          tryit('bytes no arg', function () { return b.size(); });
          tryit('count none', function () { new CountQueuingStrategy(); return 'ok'; });
          tryit('count empty', function () { new CountQueuingStrategy({}); return 'ok'; });
          tryit('count string', function () { return new CountQueuingStrategy({ highWaterMark: '4' }).highWaterMark; });
          tryit('size own', function () { return Object.getOwnPropertyNames(c).length + ' ' + typeof Object.getOwnPropertyDescriptor(CountQueuingStrategy.prototype, 'size').get; });
        });
        step('text streams', function () {
          var es = new TextEncoderStream();
          log('encoder ' + es.encoding + ' ' + Object.prototype.toString.call(es.readable) + ' ' + Object.prototype.toString.call(es.writable));
          var w = es.writable.getWriter();
          w.write('a\uD83D');
          w.write('\uDE00b');
          w.write('');
          w.write('\uD800');
          w.close();
          return readAll(es.readable.getReader(), 'encoded').then(function () {
            var ds = new TextDecoderStream('utf-8', { fatal: false });
            log('decoder ' + ds.encoding + ' ' + ds.fatal + ' ' + ds.ignoreBOM);
            var w2 = ds.writable.getWriter();
            w2.write(new Uint8Array([0xEF, 0xBB, 0xBF, 0x68, 0xE2, 0x82]));
            w2.write(new Uint8Array([0xAC, 0x21]).buffer);
            w2.write(new Uint8Array([0xE2]));
            w2.close();
            return readAll(ds.readable.getReader(), 'decoded');
          }).then(function () {
            var ds = new TextDecoderStream('utf-8', { fatal: true });
            var w3 = ds.writable.getWriter();
            var r3 = ds.readable.getReader();
            return Promise.all([done(w3.write(new Uint8Array([0xFF])), 'fatal write'), done(r3.read(), 'fatal read')]);
          }).then(function () {
            var ds = new TextDecoderStream();
            var w4 = ds.writable.getWriter();
            return Promise.all([done(w4.write('not bytes'), 'decoder string chunk'), done(ds.readable.getReader().read(), 'decoder string read')]);
          }).then(function () {
            tryit('decoder bad label', function () { new TextDecoderStream('nope'); return 'ok'; });
            tryit('decoder label', function () { return new TextDecoderStream('latin1').encoding; });
          });
        });
        step('blob stream', function () {
          var bytes = new Uint8Array(70000);
          for (var i = 0; i < bytes.length; i++) bytes[i] = i & 255;
          var blob = new Blob([bytes]);
          var s = blob.stream();
          log('blob stream ' + Object.prototype.toString.call(s) + ' ' + (s !== blob.stream()));
          var reader = s.getReader();
          var sizes = [];
          function pump() {
            return reader.read().then(function (r) {
              if (r.done) { log('blob chunks ' + sizes.join(',') + ' total'); return; }
              sizes.push(Object.prototype.toString.call(r.value) + r.value.length);
              return pump();
            });
          }
          return pump().then(function () {
            var r2 = new Blob(['hello']).stream().getReader({ mode: 'byob' });
            return done(r2.read(new Uint8Array(3)), 'blob byob');
          }).then(function () {
            return readAll(new Blob([]).stream().getReader(), 'empty blob');
          }).then(function () {
            if (typeof Blob.prototype.textStream !== 'function') { log('no textStream'); return; }
            return readAll(new Blob(['hé']).textStream().getReader(), 'text stream');
          });
        });
        setTimeout(next, 0);
    """.trimIndent()

    private val chromium = listOf(
        """== shape""",
        """lengths ReadableStream0 ReadableStreamDefaultReader1 ReadableStreamBYOBReader1 WritableStream0 WritableStreamDefaultWriter1 TransformStream0 CountQueuingStrategy1 ByteLengthQueuingStrategy1 TextEncoderStream0 TextDecoderStream0""",
        """rs cancel,constructor,getReader,locked,pipeThrough,pipeTo,tee,values""",
        """reader cancel,closed,constructor,read,releaseLock | cancel,closed,constructor,read,releaseLock""",
        """controllers close,constructor,desiredSize,enqueue,error | byobRequest,close,constructor,desiredSize,enqueue,error | constructor,respond,respondWithNewView,view""",
        """ws abort,close,constructor,getWriter,locked | abort,close,closed,constructor,desiredSize,ready,releaseLock,write | constructor,error,signal""",
        """ts constructor,readable,writable | constructor,desiredSize,enqueue,error,terminate""",
        """strategies constructor,highWaterMark,size | constructor,highWaterMark,size""",
        """text streams constructor,encoding,readable,writable | constructor,encoding,fatal,ignoreBOM,readable,writable""",
        """async iterator true values""",
        """controller ctor threw TypeError: Failed to construct 'ReadableStreamDefaultController': Illegal constructor""",
        """reader ctor [object ReadableStreamDefaultReader]""",
        """reader ctor bad threw TypeError: Failed to construct 'ReadableStreamDefaultReader': parameter 1 is not of type 'ReadableStream'.""",
        """call threw TypeError: Failed to construct 'ReadableStream': Please use the 'new' operator, this DOM object constructor cannot be called as a function.""",
        """blob function function""",
        """== constructor errors""",
        """type x threw RangeError: Failed to construct 'ReadableStream': Invalid type is specified""",
        """start 5 threw TypeError: Failed to construct 'ReadableStream': underlyingSource.start must be a function or undefined""",
        """pull 5 threw TypeError: Failed to construct 'ReadableStream': underlyingSource.pull must be a function or undefined""",
        """hwm -1 threw RangeError: Failed to construct 'ReadableStream': A queuing strategy's highWaterMark property must be a nonnegative, non-NaN number""",
        """hwm NaN threw RangeError: Failed to construct 'ReadableStream': A queuing strategy's highWaterMark property must be a nonnegative, non-NaN number""",
        """size 5 threw TypeError: Failed to construct 'ReadableStream': A queuing strategy's size property must be a function""",
        """bytes size threw RangeError: Failed to construct 'ReadableStream': Cannot create byte stream with size() defined on the strategy""",
        """auto 0 threw TypeError: Failed to construct 'ReadableStream': autoAllocateChunkSize cannot be 0""",
        """source 5 ok""",
        """source null threw TypeError: Failed to construct 'ReadableStream': Cannot convert undefined or null to object""",
        """strategy 5 ok""",
        """start throws threw RangeError: boom""",
        """ws type threw RangeError: Failed to construct 'WritableStream': Invalid type is specified""",
        """ts readableType threw RangeError: Failed to construct 'TransformStream': Invalid readableType was specified""",
        """ts writableType threw RangeError: Failed to construct 'TransformStream': Invalid writableType was specified""",
        """== default stream""",
        """start 2 [object ReadableStreamDefaultController]""",
        """after enqueue 1""",
        """constructed false""",
        """locked true [object ReadableStreamDefaultReader]""",
        """pull 1""",
        """tick""",
        """reads made 2""",
        """enqueued 1""",
        """enqueue closed threw TypeError: Failed to execute 'enqueue' on 'ReadableStreamDefaultController': Cannot enqueue a chunk into a readable stream that is closed or has been requested to be closed""",
        """close twice threw TypeError: Failed to execute 'close' on 'ReadableStreamDefaultController': Cannot close a readable stream that has already been requested to be closed""",
        """desired closing 1""",
        """p1 fulfilled {done:false,value:"a"}""",
        """p2 fulfilled {done:false,value:"b"}""",
        """closed fulfilled""",
        """rest read {done:false,value:"c"}""",
        """rest read {done:true,value:undefined}""",
        """desired closed 0""",
        """error closed ok""",
        """== pull order""",
        """pull 1 2""",
        """pulled read {done:false,value:1}""",
        """pull 2 2""",
        """pulled read {done:false,value:2}""",
        """pull 3 2""",
        """pulled read {done:false,value:3}""",
        """pull 4 2""",
        """pulled read {done:true,value:undefined}""",
        """== pull promise""",
        """pull 1""",
        """pull 1 settles""",
        """slow read {done:false,value:"x1"}""",
        """pull 2""",
        """pull 2 settles""",
        """slow read {done:false,value:"x2"}""",
        """slow read {done:true,value:undefined}""",
        """== error""",
        """enqueue errored threw TypeError: Failed to execute 'enqueue' on 'ReadableStreamDefaultController': Cannot enqueue a chunk into an errored readable stream""",
        """close errored threw TypeError: Failed to execute 'close' on 'ReadableStreamDefaultController': Cannot close an errored readable stream""",
        """desired errored null""",
        """pending read rejected TypeError: bad""",
        """closed rejected TypeError: bad""",
        """later read rejected TypeError: bad""",
        """cancel locked rejected TypeError: Failed to execute 'cancel' on 'ReadableStream': Cannot cancel a locked stream""",
        """== cancel""",
        """underlying cancel "why"""",
        """read after cancel fulfilled {done:true,value:undefined}""",
        """closed fulfilled undefined""",
        """reader cancel fulfilled undefined""",
        """cancel throws rejected RangeError: cancel threw""",
        """cancel rejects rejected SyntaxError: cancel rejects""",
        """== locking""",
        """second reader threw TypeError: Failed to execute 'getReader' on 'ReadableStream': ReadableStreamDefaultReader constructor can only accept readable streams that are not yet locked to a reader""",
        """reader ctor locked threw TypeError: Failed to construct 'ReadableStreamDefaultReader': ReadableStreamDefaultReader constructor can only accept readable streams that are not yet locked to a reader""",
        """released false false""",
        """pending on release rejected TypeError: Releasing Default reader""",
        """old closed rejected TypeError: This readable stream reader has been released and cannot be used to monitor the stream's state""",
        """new closed rejected TypeError: This readable stream reader has been released and cannot be used to monitor the stream's state""",
        """read released rejected TypeError: Failed to execute 'read' on 'ReadableStreamDefaultReader': This readable stream reader has been released and cannot be used to read from its previous owner stream""",
        """cancel released rejected TypeError: Failed to execute 'cancel' on 'ReadableStreamDefaultReader': This readable stream reader has been released and cannot be used to cancel its previous owner stream""",
        """release twice ok""",
        """mode x threw TypeError: Failed to execute 'getReader' on 'ReadableStream': Failed to read the 'mode' property from 'ReadableStreamGetReaderOptions': The provided value 'x' is not a valid enum value of type ReadableStreamReaderMode.""",
        """byob on default threw TypeError: Failed to execute 'getReader' on 'ReadableStream': Cannot use a BYOB reader with a non-byte stream""",
        """options 5 threw TypeError: Failed to execute 'getReader' on 'ReadableStream': The provided value is not of type 'ReadableStreamGetReaderOptions'.""",
        """getter no this threw TypeError: Illegal invocation""",
        """cancel no this rejected TypeError: Failed to execute 'cancel' on 'ReadableStream': Illegal invocation""",
        """== tee""",
        """tee 2 true true""",
        """b0 read {done:false,value:"t1"}""",
        """b1 read {done:false,value:"t1"}""",
        """b0 read {done:false,value:{o:1}}""",
        """b1 read {done:false,value:{o:1}}""",
        """b0 read {done:true,value:undefined}""",
        """b1 read {done:true,value:undefined}""",
        """composite ["r0","r1"]""",
        """cancel b0 fulfilled undefined""",
        """cancel b1 fulfilled undefined""",
        """b0 closed rejected Error: tee error""",
        """b1 closed rejected Error: tee error""",
        """== async iteration""",
        """iterator [object ReadableStream AsyncIterator] next,return function true""",
        """next {done:false,value:1}""",
        """iter cancel "stop"""",
        """return {done:true,value:"stop"} false""",
        """next after return {done:true,value:undefined}""",
        """it2 {done:false,value:"k"}""",
        """it2 return {done:true,value:undefined} false""",
        """errored next rejected RangeError: iter error""",
        """== byte stream byob""",
        """byte start [object ReadableByteStreamController] 0 null""",
        """byob reader [object ReadableStreamBYOBReader]""",
        """request [object ReadableStreamBYOBRequest] Uint8Array[0,0,0,0]@0/4""",
        """responded null""",
        """byob read fulfilled {done:false,value:Uint8Array[7,8]@0/4}""",
        """byob from queue fulfilled {done:false,value:Uint8Array[1,2,3,4]@0/4}""",
        """byob u16 rest fulfilled {done:false,value:Uint16Array[5,6]@0/4}""",
        """byob min fulfilled {done:false,value:Uint8Array[9,10,11]@0/3}""",
        """read empty view returned""",
        """empty view rejected TypeError: Failed to execute 'read' on 'ReadableStreamBYOBReader': This readable stream reader cannot be used to read as the view has byte length equal to 0""",
        """zero view rejected TypeError: Failed to execute 'read' on 'ReadableStreamBYOBReader': This readable stream reader cannot be used to read as the view has byte length equal to 0""",
        """read 5 rejected TypeError: Failed to execute 'read' on 'ReadableStreamBYOBReader': parameter 1 is not of type 'ArrayBufferView'.""",
        """min too big rejected RangeError: Failed to execute 'read' on 'ReadableStreamBYOBReader': min cannot be larger than view's length""",
        """byob after close fulfilled {done:false,value:Uint8Array[12]@0/2}""",
        """byob closed fulfilled undefined""",
        """== byte stream default reader""",
        """auto pull 1 Uint8Array[0,0,0]@0/3""",
        """auto read {done:false,value:Uint8Array[1]@0/3}""",
        """auto pull 2 Uint8Array[0,0,0]@0/3""",
        """auto read {done:false,value:Uint8Array[2]@0/3}""",
        """auto pull 3 Uint8Array[0,0,0]@0/3""",
        """auto read {done:true,value:undefined}""",
        """enqueue empty threw TypeError: Failed to execute 'enqueue' on 'ReadableByteStreamController': chunk is empty""",
        """enqueue not view threw TypeError: Failed to execute 'enqueue' on 'ReadableByteStreamController': parameter 1 is not of type 'ArrayBufferView'.""",
        """respond no request null""",
        """default on bytes fulfilled {done:false,value:Uint8Array[5,6]@0/2}""",
        """bt0 read {done:false,value:Uint8Array[1,2,3]@0/3}""",
        """bt1 read {done:false,value:Uint8Array[1,2,3]@0/3}""",
        """bt0 read {done:true,value:undefined}""",
        """bt1 read {done:true,value:undefined}""",
        """view detached 0 true""",
        """req view Uint8Array[0,0]@1/4""",
        """respond too big threw RangeError: Failed to execute 'respond' on 'ReadableStreamBYOBRequest': available read buffer is too small for specified number of bytes""",
        """new view fulfilled {done:false,value:Uint8Array[42]@1/4}""",
        """== writable""",
        """ws start [object WritableStreamDefaultController] [object AbortSignal]""",
        """writer [object WritableStreamDefaultWriter] 2 true""",
        """after w1 1""",
        """after w2 0""",
        """after w3 -1""",
        """write after close returned""",
        """sink write "w1" true""",
        """ready 1""",
        """ready 2 -1""",
        """late write rejected TypeError: Cannot write to a closing writable stream""",
        """sink write "w2" true""",
        """w1 fulfilled undefined""",
        """sink write "w3" true""",
        """w2 fulfilled undefined""",
        """sink close 0""",
        """w3 fulfilled undefined""",
        """close fulfilled undefined""",
        """closed fulfilled undefined""",
        """desired closed 0""",
        """close twice rejected TypeError: Cannot close a CLOSED writable stream""",
        """ws2 sink abort "reason a"""",
        """ready after abort rejected value reason a""",
        """write after abort rejected value reason a""",
        """write before abort rejected value reason a""",
        """abort fulfilled undefined""",
        """closed after abort rejected value reason a""",
        """write threw rejected RangeError: sink write threw""",
        """write errored rejected RangeError: sink write threw""",
        """closed errored rejected RangeError: sink write threw""",
        """second writer threw TypeError: Failed to execute 'getWriter' on 'WritableStream': Cannot create writer when WritableStream is locked""",
        """released closed rejected TypeError: This writable stream writer has been released and cannot be used to monitor the stream's state""",
        """released ready rejected TypeError: This writable stream writer has been released and cannot be used to monitor the stream's state""",
        """released write rejected TypeError: Failed to execute 'write' on 'WritableStreamDefaultWriter': This writable stream writer has been released and cannot be written to""",
        """released desired threw TypeError: Failed to read the 'desiredSize' property from 'WritableStreamDefaultWriter': This writable stream writer has been released and cannot be used to get the desiredSize""",
        """abort locked rejected TypeError: Failed to execute 'abort' on 'WritableStream': Cannot abort a locked stream""",
        """controller error closed rejected SyntaxError: ctl error""",
        """== transform""",
        """ts start [object TransformStreamDefaultController] 0""",
        """transform "ab"""",
        """ts read {done:false,value:"AB"}""",
        """ts read {done:false,value:"!"}""",
        """transform "cd"""",
        """ts read {done:false,value:"CD"}""",
        """ts read {done:false,value:"!"}""",
        """flush""",
        """ts read {done:false,value:"end"}""",
        """ts read {done:true,value:undefined}""",
        """identity desired 1""",
        """identity after write 0""",
        """identity read fulfilled {done:false,value:"same"}""",
        """identity write fulfilled undefined""",
        """terminated""",
        """enqueue terminated threw TypeError: Failed to execute 'enqueue' on 'TransformStreamDefaultController': Cannot enqueue a chunk into a readable stream that is closed or has been requested to be closed""",
        """read terminate fulfilled {done:true,value:undefined}""",
        """write terminate fulfilled undefined""",
        """writable after terminate rejected TypeError: The transform stream has been terminated""",
        """read error rejected TypeError: transform threw""",
        """write error rejected TypeError: transform threw""",
        """readable cancel fulfilled undefined""",
        """writable after cancel rejected value rc""",
        """== pipe""",
        """piping true true""",
        """dest write p1""",
        """dest write p2""",
        """dest close""",
        """pipeTo fulfilled undefined""",
        """preventClose fulfilled undefined""",
        """dst2 locked false""",
        """dst3 abort RangeError: src error""",
        """source errored rejected RangeError: src error""",
        """src4 cancel SyntaxError: dest error""",
        """dest errored rejected SyntaxError: dest error""",
        """dst5 abort AbortError: signal is aborted without reason""",
        """src5 cancel AbortError: signal is aborted without reason""",
        """signal abort rejected AbortError: signal is aborted without reason""",
        """locked source rejected TypeError: Failed to execute 'pipeTo' on 'ReadableStream': Cannot pipe a locked stream""",
        """bad dest rejected TypeError: Failed to execute 'pipeTo' on 'ReadableStream': parameter 1 is not of type 'WritableStream'.""",
        """bad signal rejected TypeError: Failed to execute 'pipeTo' on 'ReadableStream': Failed to read the 'signal' property from 'StreamPipeOptions': Failed to convert value to 'AbortSignal'.""",
        """pipeThrough true true""",
        """pipeThrough bad threw TypeError: Failed to execute 'pipeThrough' on 'ReadableStream': Failed to read the 'readable' property from 'ReadableWritablePair': Required member is undefined.""",
        """pipeThrough locked threw TypeError: Failed to execute 'pipeThrough' on 'ReadableStream': Cannot pipe a locked stream""",
        """through read {done:false,value:"xx"}""",
        """through read {done:false,value:"yy"}""",
        """through read {done:true,value:undefined}""",
        """== strategies""",
        """count 3 1 1 size 0 true""",
        """bytes 5 7 size 1""",
        """bytes no arg threw TypeError: Cannot convert undefined or null to object""",
        """count none threw TypeError: Failed to construct 'CountQueuingStrategy': 1 argument required, but only 0 present.""",
        """count empty threw TypeError: Failed to construct 'CountQueuingStrategy': Failed to read the 'highWaterMark' property from 'QueuingStrategyInit': Required member is undefined.""",
        """count string 4""",
        """size own 0 function""",
        """== text streams""",
        """encoder utf-8 [object ReadableStream] [object WritableStream]""",
        """encoded read {done:false,value:Uint8Array[97]@0/1}""",
        """encoded read {done:false,value:Uint8Array[240,159,152,128,98]@0/5}""",
        """encoded read {done:false,value:Uint8Array[239,191,189]@0/3}""",
        """encoded read {done:true,value:undefined}""",
        """decoder utf-8 false false""",
        """decoded read {done:false,value:"h"}""",
        """decoded read {done:false,value:"€!"}""",
        """decoded read {done:false,value:"�"}""",
        """decoded read {done:true,value:undefined}""",
        """fatal read rejected TypeError: The encoded data is not valid.""",
        """fatal write rejected TypeError: The encoded data is not valid.""",
        """decoder string read rejected TypeError: The provided value is not of type '(ArrayBuffer or ArrayBufferView)'.""",
        """decoder string chunk rejected TypeError: The provided value is not of type '(ArrayBuffer or ArrayBufferView)'.""",
        """decoder bad label threw RangeError: Failed to construct 'TextDecoderStream': The encoding label provided ('nope') is invalid.""",
        """decoder label windows-1252""",
        """== blob stream""",
        """blob stream [object ReadableStream] true""",
        """blob chunks [object Uint8Array]65536,[object Uint8Array]4464 total""",
        """blob byob fulfilled {done:false,value:Uint8Array[104,101,108]@0/3}""",
        """empty blob read {done:true,value:undefined}""",
        """text stream read {done:false,value:"hé"}""",
        """text stream read {done:true,value:undefined}""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("S536") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_streams_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_streams_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
