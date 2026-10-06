package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * structuredClone, window.postMessage, MessageChannel, MessagePort and MessageEvent (#534). Each
 * expected line is what headless Chromium logs for the same chapter. The port steps run 20 ms
 * apart, because Chromium does not keep one order for messages on different ports.
 */
class MessagingTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function log(s) { out.push(s); }
        function tryit(name, f) { try { log(name + ' ' + f()); } catch (e) { log(name + ' threw ' + e.name + ': ' + e.message); } }
        function show(v) {
          if (v === null || typeof v !== 'object') return typeof v === 'bigint' ? v + 'n' : typeof v === 'string' ? JSON.stringify(v) : String(v);
          return Object.prototype.toString.call(v);
        }
        tryit('prims', function () { return [undefined, null, true, 1.5, -0, NaN, 10n, 'x'].map(function (v) { var c = structuredClone(v); return show(c) + (Object.is(c, -0) ? '(-0)' : ''); }).join(' '); });
        tryit('wrappers', function () { return [new Boolean(false), new Number(2), new String('s'), Object(3n)].map(function (v) { var c = structuredClone(v); return show(c) + ':' + c.valueOf() + ':' + (c !== v); }).join(' '); });
        tryit('date', function () { var d = new Date(5); var c = structuredClone(d); return show(c) + ' ' + c.getTime() + ' ' + (c !== d); });
        tryit('regexp', function () { var r = /a+/gi; r.lastIndex = 3; r.x = 1; var c = structuredClone(r); return c.source + ' ' + c.flags + ' ' + c.lastIndex + ' ' + c.x; });
        tryit('object', function () { var o = { a: 1, b: { c: [1, 2, , 4] } }; Object.defineProperty(o, 'hidden', { value: 1, enumerable: false }); o[Symbol('s')] = 1; var c = structuredClone(o); return JSON.stringify(c) + ' ' + ('hidden' in c) + ' ' + Object.getOwnPropertySymbols(c).length + ' ' + (2 in c.b.c) + ' ' + c.b.c.length; });
        tryit('cycle', function () { var o = { n: 1 }; o.self = o; var a = [o, o]; var c = structuredClone(a); return (c[0] === c[1]) + ' ' + (c[0].self === c[0]) + ' ' + (c[0] !== o); });
        tryit('proto', function () { function C() { this.a = 1; } C.prototype.b = 2; var c = structuredClone(new C()); return show(c) + ' ' + c.a + ' ' + c.b + ' ' + (Object.getPrototypeOf(c) === Object.prototype); });
        tryit('getter', function () { var n = 0; var o = { get g() { n++; return 7; } }; var c = structuredClone(o); return c.g + ' ' + n + ' ' + JSON.stringify(Object.getOwnPropertyDescriptor(c, 'g')); });
        tryit('array extra', function () { var a = [1, 2]; a.k = 'v'; a.length = 4; var c = structuredClone(a); return c.length + ' ' + c.k + ' ' + Object.keys(c).join(','); });
        tryit('map set', function () { var k = {}; var m = new Map([[k, 'v'], ['s', k]]); var s = new Set([k, 1]); var c = structuredClone([m, s]); var ck = Array.from(c[0].keys())[0]; return c[0].size + ' ' + (c[0].get('s') === ck) + ' ' + (Array.from(c[1])[0] === ck) + ' ' + c[1].has(1); });
        tryit('errors', function () { return [new Error('m'), new TypeError('t'), new RangeError('r'), new EvalError('e'), new URIError('u'), new ReferenceError('f'), new SyntaxError('s')].map(function (e) { var c = structuredClone(e); return show(c) + ':' + c.name + ':' + c.message + ':' + (c instanceof e.constructor); }).join(' '); });
        tryit('error custom', function () { var e = new TypeError('x'); e.name = 'Custom'; e.extra = 1; var c = structuredClone(e); return c.name + ' ' + (c instanceof TypeError) + ' ' + c.extra + ' ' + ('message' in c) + ' ' + (typeof c.stack); });
        tryit('error nomsg', function () { var c = structuredClone(new Error()); return Object.prototype.hasOwnProperty.call(c, 'message') + ' ' + JSON.stringify(c.message); });
        tryit('error cause', function () { var c = structuredClone(new Error('a', { cause: 5 })); return c.cause + ' ' + Object.prototype.hasOwnProperty.call(c, 'cause'); });
        tryit('error subclass', function () { class E2 extends RangeError {} var c = structuredClone(new E2('q')); return show(c) + ' ' + c.name + ' ' + (c instanceof RangeError) + ' ' + (c instanceof E2); });
        tryit('buffer', function () { var b = new Uint8Array([1, 2, 3]).buffer; var c = structuredClone(b); return show(c) + ' ' + c.byteLength + ' ' + (c !== b) + ' ' + b.byteLength + ' ' + new Uint8Array(c)[2]; });
        tryit('views', function () { var b = new ArrayBuffer(8); var u = new Uint8Array(b, 2, 4); var d = new DataView(b, 1, 3); u[0] = 9; var c = structuredClone([u, d, b]); return show(c[0]) + ' ' + c[0].byteOffset + ' ' + c[0].length + ' ' + (c[0].buffer === c[2]) + ' ' + (c[1].buffer === c[2]) + ' ' + c[1].byteOffset + ' ' + c[1].byteLength + ' ' + new Uint8Array(c[2])[2]; });
        tryit('typed kinds', function () { return [Int8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, Float32Array, Float64Array, BigInt64Array, BigUint64Array].map(function (T) { return show(structuredClone(new T(2))); }).join(' '); });
        tryit('resizable', function () { var b = new ArrayBuffer(2, { maxByteLength: 8 }); var c = structuredClone(b); return c.resizable + ' ' + c.maxByteLength + ' ' + c.byteLength; });
        tryit('transfer', function () { var b = new Uint8Array([7, 8]).buffer; var c = structuredClone({ b: b }, { transfer: [b] }); return b.byteLength + ' ' + b.detached + ' ' + c.b.byteLength + ' ' + new Uint8Array(c.b)[1]; });
        tryit('transfer view', function () { var b = new ArrayBuffer(4); var u = new Uint16Array(b); var c = structuredClone(u, { transfer: [b] }); return b.byteLength + ' ' + c.length + ' ' + c.buffer.byteLength; });
        tryit('transfer dup', function () { var b = new ArrayBuffer(1); structuredClone(1, { transfer: [b, b] }); return 'ok'; });
        tryit('transfer detached', function () { var b = new ArrayBuffer(1); structuredClone(1, { transfer: [b] }); structuredClone(1, { transfer: [b] }); return 'ok'; });
        tryit('clone detached', function () { var b = new ArrayBuffer(1); structuredClone(1, { transfer: [b] }); structuredClone(b); return 'ok'; });
        tryit('transfer notbuf', function () { structuredClone(1, { transfer: [{}] }); return 'ok'; });
        tryit('transfer notiter', function () { structuredClone(1, { transfer: 5 }); return 'ok'; });
        tryit('function', function () { structuredClone(function f() {}); return 'ok'; });
        tryit('arrow', function () { structuredClone({ a: () => 1 }); return 'ok'; });
        tryit('symbol', function () { structuredClone(Symbol('q')); return 'ok'; });
        tryit('node', function () { structuredClone(document.getElementById('p')); return 'ok'; });
        tryit('window', function () { structuredClone(window); return 'ok'; });
        tryit('promise', function () { structuredClone(Promise.resolve()); return 'ok'; });
        tryit('weakmap', function () { structuredClone(new WeakMap()); return 'ok'; });
        tryit('no args', function () { structuredClone(); return 'ok'; });
        tryit('blob', function () { var b = new Blob(['ab'], { type: 'x/y' }); var c = structuredClone(b); return show(c) + ' ' + c.size + ' ' + c.type + ' ' + (c !== b); });
        tryit('file', function () { var f = new File(['q'], 'n.txt', { type: 't/u', lastModified: 42 }); var c = structuredClone(f); return show(c) + ' ' + c.name + ' ' + c.lastModified + ' ' + c.type; });
        tryit('domexception', function () { var c = structuredClone(new DOMException('m', 'NotFoundError')); return show(c) + ' ' + c.name + ' ' + c.message + ' ' + c.code; });
        tryit('getter throws', function () { structuredClone({ get x() { throw new RangeError('inner'); } }); return 'ok'; });
        tryit('length', function () { return structuredClone.length + ' ' + MessageChannel.length + ' ' + MessageEvent.length + ' ' + typeof MessagePort + ' ' + window.postMessage.length; });
        tryit('mp new', function () { new MessagePort(); return 'ok'; });
        tryit('me', function () { var e = new MessageEvent('message', { data: 5, origin: 'o', lastEventId: 'l', source: window, ports: [] }); return e.data + ' ' + e.origin + ' ' + e.lastEventId + ' ' + (e.source === window) + ' ' + Array.isArray(e.ports) + ' ' + Object.isFrozen(e.ports) + ' ' + e.bubbles; });
        tryit('me default', function () { var e = new MessageEvent('x'); return e.data + ' ' + JSON.stringify(e.origin) + ' ' + e.source + ' ' + e.ports.length; });
        tryit('me bad source', function () { new MessageEvent('x', { source: 5 }); return 'ok'; });
        tryit('me initMessageEvent', function () { var e = new MessageEvent('x'); e.initMessageEvent('y', true, false, 9, 'oo', 'id', null, []); return e.type + ' ' + e.bubbles + ' ' + e.data + ' ' + e.origin; });

        tryit('symbol wrapper', function () { structuredClone(Object(Symbol('w'))); return 'ok'; });
        tryit('generator', function () { structuredClone((function* () {})()); return 'ok'; });
        tryit('arguments', function () { structuredClone((function () { return arguments; })(1)); return 'ok'; });
        tryit('weakset', function () { structuredClone(new WeakSet()); return 'ok'; });
        tryit('weakref', function () { structuredClone(new WeakRef({})); return 'ok'; });
        tryit('url', function () { structuredClone(new URL('https://a.b/')); return 'ok'; });
        tryit('event', function () { structuredClone(new Event('x')); return 'ok'; });
        tryit('channel', function () { structuredClone(new MessageChannel()); return 'ok'; });
        tryit('port', function () { structuredClone(new MessageChannel().port1); return 'ok'; });
        tryit('port transferred', function () { var c = new MessageChannel(); var r = structuredClone({ p: c.port1 }, { transfer: [c.port1] }); return show(r.p) + ' ' + (r.p !== c.port1); });
        tryit('port twice', function () { var c = new MessageChannel(); structuredClone(1, { transfer: [c.port1] }); structuredClone(1, { transfer: [c.port1] }); return 'ok'; });
        tryit('closed port', function () { var c = new MessageChannel(); c.port1.close(); structuredClone(1, { transfer: [c.port1] }); return 'ok'; });
        tryit('options 5', function () { structuredClone(1, 5); return 'ok'; });
        tryit('options null', function () { return structuredClone(1, null); });
        tryit('transfer kept on throw', function () { var b = new ArrayBuffer(2); try { structuredClone({ b: b, f: function () {} }, { transfer: [b] }); } catch (e) {} return b.byteLength; });
        tryit('nested', function () { var o = { a: { b: { c: { d: [new Date(0), /x/y, new Set([1])] } } } }; var c = structuredClone(o); return show(c.a.b.c.d[0]) + ' ' + c.a.b.c.d[1].flags + ' ' + c.a.b.c.d[2].size; });
        tryit('dunder proto', function () { var o = JSON.parse('{"__proto__": {"x": 1}}'); var c = structuredClone(o); return Object.getPrototypeOf(c) === Object.prototype ? 'own ' + JSON.stringify(Object.getOwnPropertyDescriptor(c, '__proto__').value) : 'proto'; });
        tryit('call channel', function () { MessageChannel(); return 'ok'; });
        tryit('ports', function () { var c = new MessageChannel(); return show(c) + ' ' + show(c.port1) + ' ' + (c.port1 instanceof EventTarget) + ' ' + (c.port1 !== c.port2) + ' ' + (c.port1 === c.port1) + ' ' + ('onclose' in c.port1) + ' ' + ('onmessageerror' in c.port1); });
        tryit('me ports bad', function () { new MessageEvent('x', { ports: [1] }); return 'ok'; });
        tryit('me ports null', function () { new MessageEvent('x', { ports: null }); return 'ok'; });
        tryit('me port source', function () { var c = new MessageChannel(); var e = new MessageEvent('x', { source: c.port1, ports: [c.port2] }); return (e.source === c.port1) + ' ' + (e.ports[0] === c.port2); });
        tryit('post no args', function () { window.postMessage(); return 'ok'; });
        tryit('post bad transfer', function () { window.postMessage(1, '*', [{}]); return 'ok'; });
        tryit('port post no args', function () { new MessageChannel().port1.postMessage(); return 'ok'; });
        tryit('port post prim', function () { new MessageChannel().port1.postMessage(1, 5); return 'ok'; });
        tryit('port post self', function () { var c = new MessageChannel(); c.port1.postMessage(1, [c.port1]); return 'ok'; });
        tryit('port post dup', function () { var c = new MessageChannel(), d = new MessageChannel(); c.port1.postMessage(1, [d.port1, d.port1]); return 'ok'; });
        tryit('port post fn', function () { new MessageChannel().port1.postMessage(function () {}); return 'ok'; });

        window.addEventListener('message', function (e) {
          log('win message ' + show(e.data) + ' ' + JSON.stringify(e.data) + ' origin=' + (e.origin === location.origin) + ' ' + (e.source === window) + ' ' + e.ports.length + ' ' + e.isTrusted + ' ' + e.bubbles + ' ' + e.cancelable + ' ' + (e instanceof MessageEvent) + ' ' + JSON.stringify(e.lastEventId) + ' ' + (e.target === window) + ' ' + e.eventPhase);
        });
        var obj = { k: [1] };
        window.postMessage(obj, '*');
        obj.k.push(2);
        window.postMessage('slash', '/');
        window.postMessage('own', location.origin);
        window.postMessage('other', 'https://example.com');
        tryit('bad origin', function () { window.postMessage('bad', 'not a url'); return 'ok'; });
        window.postMessage('opts', { targetOrigin: '*' });
        window.postMessage('opts default', {});
        window.postMessage('opts null', null);
        tryit('no target', function () { window.postMessage('x'); return 'ok'; });
        tryit('post fn', function () { window.postMessage(function () {}, '*'); return 'ok'; });
        var buf = new ArrayBuffer(3);
        window.postMessage(buf, '*', [buf]);
        log('after post ' + buf.byteLength);
        var buf2 = new ArrayBuffer(5);
        window.postMessage({ b: buf2 }, { transfer: [buf2] });
        log('after opts post ' + buf2.byteLength);
        setTimeout(function () { log('timeout 0'); }, 0);
        Promise.resolve().then(function () { log('microtask'); });
        log('sync end');

        var steps = [];
        function step(f) { steps.push(f); }
        function next() { var f = steps.shift(); if (!f) { log('done'); console.log('M534\n' + out.join('\n')); return; } f(); setTimeout(next, 20); }
        setTimeout(next, 20);
        var A = new MessageChannel();
        step(function () {
          log('step queued');
          A.port2.addEventListener('message', function (e) { log('A2 listener ' + JSON.stringify(e.data) + ' ' + JSON.stringify(e.origin) + ' ' + e.source + ' ' + e.isTrusted + ' ' + (e.target === A.port2) + ' ' + e.bubbles + ' ' + e.ports.length + ' ' + (e instanceof MessageEvent)); });
          A.port1.postMessage('one');
          A.port1.postMessage({ two: [2] });
        });
        step(function () { log('step start'); A.port2.start(); });
        step(function () { log('step after start'); A.port1.postMessage('three'); log('posted three'); });
        step(function () {
          log('step onmessage');
          A.port1.onmessage = function (e) { log('A1 onmessage ' + JSON.stringify(e.data) + ' ' + (e.currentTarget === A.port1)); };
          A.port2.postMessage('back');
          log('handler ' + (typeof A.port1.onmessage));
        });
        var B = new MessageChannel();
        step(function () {
          log('step null handler');
          B.port2.onmessage = function () {};
          B.port2.onmessage = null;
          B.port2.addEventListener('message', function (e) { log('B2 got ' + e.data); });
          B.port1.postMessage('started anyway');
        });
        var C = new MessageChannel(), D = new MessageChannel(), moved = null;
        step(function () {
          log('step transfer');
          C.port2.onmessage = function (e) { log('C2 got ' + JSON.stringify(e.data) + ' ports=' + e.ports.length + ' ' + (e.ports[0] instanceof MessagePort) + ' ' + (e.ports[0] !== D.port1) + ' ' + Object.isFrozen(e.ports)); moved = e.ports[0]; };
          D.port2.postMessage('held for the moved port');
          C.port1.postMessage('with port', [D.port1]);
          tryit('post on moved', function () { D.port1.postMessage('stale'); return 'ok'; });
        });
        step(function () { log('step moved'); moved.onmessage = function (e) { log('moved got ' + e.data); }; });
        step(function () { log('step moved again'); D.port2.postMessage('to moved'); moved.postMessage('from moved'); D.port2.onmessage = function (e) { log('D2 got ' + e.data); }; });
        var E = new MessageChannel();
        step(function () {
          log('step close');
          E.port2.onmessage = function (e) { log('E2 got ' + e.data); };
          E.port1.onmessage = function (e) { log('E1 got ' + e.data); };
          E.port1.postMessage('before close');
          E.port1.close();
          E.port1.postMessage('after close');
          E.port2.postMessage('to closed');
        });
        var F = new MessageChannel();
        step(function () {
          log('step close receiver');
          F.port2.onmessage = function (e) { log('F2 got ' + e.data); };
          F.port1.postMessage('queued then closed');
          F.port2.close();
        });
        var G = new MessageChannel();
        step(function () {
          log('step buffer');
          var b = new Uint8Array([4, 5, 6]).buffer;
          G.port2.onmessage = function (e) { log('G2 got ' + show(e.data) + ' ' + e.data.byteLength + ' ' + new Uint8Array(e.data)[2]); };
          G.port1.postMessage(b, { transfer: [b] });
          log('sent ' + b.byteLength);
        });
        var H = new MessageChannel();
        step(function () {
          log('step listener order');
          H.port2.onmessage = function (e) { log('H2 handler ' + e.data); Promise.resolve().then(function () { log('H2 job ' + e.data); }); };
          H.port2.addEventListener('message', function (e) { log('H2 listener ' + e.data); });
          H.port1.postMessage('a');
          H.port1.postMessage('b');
        });
    """.trimIndent()

    private val chromium = listOf(
        """prims undefined null true 1.5 0(-0) NaN 10n "x"""",
        """wrappers [object Boolean]:false:true [object Number]:2:true [object String]:s:true [object BigInt]:3:true""",
        """date [object Date] 5 true""",
        """regexp a+ gi 0 undefined""",
        """object {"a":1,"b":{"c":[1,2,null,4]}} false 0 false 4""",
        """cycle true true true""",
        """proto [object Object] 1 undefined true""",
        """getter 7 1 {"value":7,"writable":true,"enumerable":true,"configurable":true}""",
        """array extra 4 v 0,1,k""",
        """map set 2 true true true""",
        """errors [object Error]:Error:m:true [object Error]:TypeError:t:true [object Error]:RangeError:r:true [object Error]:EvalError:e:true [object Error]:URIError:u:true [object Error]:ReferenceError:f:true [object Error]:SyntaxError:s:true""",
        """error custom Error false undefined true string""",
        """error nomsg false """"",
        """error cause 5 true""",
        """error subclass [object Error] RangeError true false""",
        """buffer [object ArrayBuffer] 3 true 3 3""",
        """views [object Uint8Array] 2 4 true true 1 3 9""",
        """typed kinds [object Int8Array] [object Uint8ClampedArray] [object Int16Array] [object Uint16Array] [object Int32Array] [object Uint32Array] [object Float32Array] [object Float64Array] [object BigInt64Array] [object BigUint64Array]""",
        """resizable true 8 2""",
        """transfer 0 true 2 8""",
        """transfer view 0 2 4""",
        """transfer dup threw DataCloneError: Failed to execute 'structuredClone' on 'Window': ArrayBuffer at index 1 is a duplicate of an earlier ArrayBuffer.""",
        """transfer detached threw DataCloneError: Failed to execute 'structuredClone' on 'Window': ArrayBuffer at index 0 is already detached.""",
        """clone detached threw DataCloneError: Failed to execute 'structuredClone' on 'Window': An ArrayBuffer is detached and could not be cloned.""",
        """transfer notbuf threw DataCloneError: Failed to execute 'structuredClone' on 'Window': Value at index 0 does not have a transferable type.""",
        """transfer notiter threw TypeError: Failed to execute 'structuredClone' on 'Window': Failed to read the 'transfer' property from 'StructuredSerializeOptions': The provided value cannot be converted to a sequence.""",
        """function threw DataCloneError: Failed to execute 'structuredClone' on 'Window': function f() {} could not be cloned.""",
        """arrow threw DataCloneError: Failed to execute 'structuredClone' on 'Window': () => 1 could not be cloned.""",
        """symbol threw DataCloneError: Failed to execute 'structuredClone' on 'Window': Symbol(q) could not be cloned.""",
        """node threw DataCloneError: Failed to execute 'structuredClone' on 'Window': HTMLParagraphElement object could not be cloned.""",
        """window threw DataCloneError: Failed to execute 'structuredClone' on 'Window': #<Window> could not be cloned.""",
        """promise threw DataCloneError: Failed to execute 'structuredClone' on 'Window': #<Promise> could not be cloned.""",
        """weakmap threw DataCloneError: Failed to execute 'structuredClone' on 'Window': #<WeakMap> could not be cloned.""",
        """no args threw TypeError: Failed to execute 'structuredClone' on 'Window': 1 argument required, but only 0 present.""",
        """blob [object Blob] 2 x/y true""",
        """file [object File] n.txt 42 t/u""",
        """domexception [object DOMException] NotFoundError m 8""",
        """getter throws threw RangeError: inner""",
        """length 1 0 1 function 1""",
        """mp new threw TypeError: Failed to construct 'MessagePort': Illegal constructor""",
        """me 5 o l true true true false""",
        """me default null "" null 0""",
        """me bad source threw TypeError: Failed to construct 'MessageEvent': Failed to read the 'source' property from 'MessageEventInit': Failed to convert value to 'EventTarget'.""",
        """me initMessageEvent y true 9 oo""",
        """symbol wrapper threw DataCloneError: Failed to execute 'structuredClone' on 'Window': [object Symbol] could not be cloned.""",
        """generator threw DataCloneError: Failed to execute 'structuredClone' on 'Window': [object Generator] could not be cloned.""",
        """arguments threw DataCloneError: Failed to execute 'structuredClone' on 'Window': #<Object> could not be cloned.""",
        """weakset threw DataCloneError: Failed to execute 'structuredClone' on 'Window': #<WeakSet> could not be cloned.""",
        """weakref threw DataCloneError: Failed to execute 'structuredClone' on 'Window': #<WeakRef> could not be cloned.""",
        """url threw DataCloneError: Failed to execute 'structuredClone' on 'Window': URL object could not be cloned.""",
        """event threw DataCloneError: Failed to execute 'structuredClone' on 'Window': Event object could not be cloned.""",
        """channel threw DataCloneError: Failed to execute 'structuredClone' on 'Window': MessageChannel object could not be cloned.""",
        """port threw DataCloneError: Failed to execute 'structuredClone' on 'Window': A MessagePort could not be cloned because it was not transferred.""",
        """port transferred [object MessagePort] true""",
        """port twice threw DataCloneError: Failed to execute 'structuredClone' on 'Window': Port at index 0 is already neutered.""",
        """closed port ok""",
        """options 5 threw TypeError: Failed to execute 'structuredClone' on 'Window': The provided value is not of type 'StructuredSerializeOptions'.""",
        """options null 1""",
        """transfer kept on throw 2""",
        """nested [object Date] y 1""",
        """dunder proto own {"x":1}""",
        """call channel threw TypeError: Failed to construct 'MessageChannel': Please use the 'new' operator, this DOM object constructor cannot be called as a function.""",
        """ports [object MessageChannel] [object MessagePort] true true true true true""",
        """me ports bad threw TypeError: Failed to construct 'MessageEvent': Failed to read the 'ports' property from 'MessageEventInit': Failed to convert value to 'MessagePort'.""",
        """me ports null threw TypeError: Failed to construct 'MessageEvent': Failed to read the 'ports' property from 'MessageEventInit': The provided value cannot be converted to a sequence.""",
        """me port source true true""",
        """post no args threw TypeError: Failed to execute 'postMessage' on 'Window': 1 argument required, but only 0 present.""",
        """post bad transfer threw DataCloneError: Failed to execute 'postMessage' on 'Window': Value at index 0 does not have a transferable type.""",
        """port post no args threw TypeError: Failed to execute 'postMessage' on 'MessagePort': 1 argument required, but only 0 present.""",
        """port post prim threw TypeError: Failed to execute 'postMessage' on 'MessagePort': Overload resolution failed.""",
        """port post self threw DataCloneError: Failed to execute 'postMessage' on 'MessagePort': Port at index 0 contains the source port.""",
        """port post dup threw DataCloneError: Failed to execute 'postMessage' on 'MessagePort': Message port at index 1 is a duplicate of an earlier port.""",
        """port post fn threw DataCloneError: Failed to execute 'postMessage' on 'MessagePort': function () {} could not be cloned.""",
        """bad origin threw SyntaxError: Failed to execute 'postMessage' on 'Window': Invalid target origin 'not a url' in a call to 'postMessage'.""",
        """no target ok""",
        """post fn threw DataCloneError: Failed to execute 'postMessage' on 'Window': function () {} could not be cloned.""",
        """after post 0""",
        """after opts post 0""",
        """sync end""",
        """microtask""",
        """win message [object Object] {"k":[1]} origin=true true 0 true false false true "" true 2""",
        """win message "slash" "slash" origin=true true 0 true false false true "" true 2""",
        """win message "own" "own" origin=true true 0 true false false true "" true 2""",
        """win message "opts" "opts" origin=true true 0 true false false true "" true 2""",
        """win message "opts default" "opts default" origin=true true 0 true false false true "" true 2""",
        """win message "opts null" "opts null" origin=true true 0 true false false true "" true 2""",
        """win message "x" "x" origin=true true 0 true false false true "" true 2""",
        """win message [object ArrayBuffer] {} origin=true true 0 true false false true "" true 2""",
        """win message [object Object] {"b":{}} origin=true true 0 true false false true "" true 2""",
        """timeout 0""",
        """step queued""",
        """step start""",
        """A2 listener "one" "" null true true false 0 true""",
        """A2 listener {"two":[2]} "" null true true false 0 true""",
        """step after start""",
        """posted three""",
        """A2 listener "three" "" null true true false 0 true""",
        """step onmessage""",
        """handler function""",
        """A1 onmessage "back" true""",
        """step null handler""",
        """B2 got started anyway""",
        """step transfer""",
        """post on moved ok""",
        """C2 got "with port" ports=1 true true true""",
        """step moved""",
        """moved got held for the moved port""",
        """step moved again""",
        """moved got to moved""",
        """D2 got from moved""",
        """step close""",
        """E2 got before close""",
        """step close receiver""",
        """step buffer""",
        """sent 0""",
        """G2 got [object ArrayBuffer] 3 6""",
        """step listener order""",
        """H2 handler a""",
        """H2 job a""",
        """H2 listener a""",
        """H2 handler b""",
        """H2 job b""",
        """H2 listener b""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.isEmpty() && now < 2_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }.drop(1)
    }

    @Test
    fun an_xhtml_chapter_clones_and_posts_messages_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = false))
    }

    @Test
    fun an_html_chapter_clones_and_posts_messages_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = true))
    }
}
