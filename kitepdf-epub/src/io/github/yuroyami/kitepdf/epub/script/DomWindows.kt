package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] for the other windows of a chapter (#613): the window of a frame as
 * the chapter sees it through `contentWindow`, the chapter's window as a frame sees it through
 * `parent`, and the messages and ports that pass between them.
 */
internal const val DOM_PRELUDE_WINDOWS: String = """/* ---- other windows: frames, and the window around a frame (#613) ----
   Each window has an engine of its own, so one window sees another as a browser sees a window of
   another origin (HTML, 7.2.3): a WindowProxy whose postMessage, close, focus, blur and a few
   other members work, and whose other members throw a SecurityError. A message is cloned here,
   written as text, carried by the host and read back in the other window. A port that moves to
   another window stays entangled with its partner through a route that the host keeps. */
/* This window, the window around it, and the chapter's window, by the host's ids. */
var WINDOW_IDS = K.windows();
var windowProxies = new Map(), proxyWindows = new WeakMap();
var CROSS_ORIGIN = nameSet(['window', 'self', 'location', 'close', 'closed', 'focus', 'blur', 'frames', 'length', 'top', 'opener', 'parent', 'postMessage']);
function crossOriginError(verb, key) {
  return new DOMException('Failed to ' + verb + " a named property '" + String(key) + "' " + (verb === 'read' ? 'from' : 'on') +
    " 'Window': Blocked a frame with origin \"" + origin + '" from accessing a cross-origin frame.', 'SecurityError');
}
/* The WindowProxy of the window [id]: the global itself for this window, null for none. */
function windowProxy(id) {
  if (id === null || id === undefined) return null;
  if (id === WINDOW_IDS[0]) return global;
  var known = MapGet(windowProxies, id);
  if (known !== undefined) return known;
  var proxy;
  var location = ObjectCreate(null);
  location.replace = function replace() {};
  var members = { __proto__: null,
    postMessage: function postMessage(message) { postToWindow(id, arguments, "Failed to execute 'postMessage' on 'Window'"); },
    close: function close() {}, focus: function focus() {}, blur: function blur() {} };
  function read(key) {
    if (key === 'window' || key === 'self' || key === 'frames') return proxy;
    if (key === 'closed') return false;
    if (key === 'length') return K.windowLength(id);
    if (key === 'top') return windowProxy(WINDOW_IDS[2]);
    if (key === 'parent') return windowProxy(K.windowParent(id));
    if (key === 'opener') return null;
    if (key === 'location') return location;
    return members[key];
  }
  var handler = { __proto__: null,
    get: function (t, key) {
      if (typeof key === 'string' && CROSS_ORIGIN[key]) return read(key);
      // What the cross-origin fallback answers, so that a promise or an instanceof can look (HTML, 7.2.3.3).
      if (key === 'then' || key === SymbolToStringTag || key === SymbolHasInstance || key === SymbolIsConcatSpreadable) return undefined;
      throw crossOriginError('read', key);
    },
    set: function (t, key) {
      // A window of another origin takes a new location and nothing else; a frame here keeps its document.
      if (key === 'location') return true;
      throw crossOriginError('set', key);
    },
    has: function (t, key) {
      if (typeof key === 'string' && CROSS_ORIGIN[key]) return true;
      throw crossOriginError('read', key);
    },
    getOwnPropertyDescriptor: function (t, key) {
      if (typeof key === 'string' && CROSS_ORIGIN[key]) return { __proto__: null, value: read(key), writable: false, enumerable: false, configurable: true };
      if (key === 'then' || key === SymbolToStringTag || key === SymbolHasInstance || key === SymbolIsConcatSpreadable) return undefined;
      throw crossOriginError('read', key);
    },
    ownKeys: function () { return ObjectKeys(CROSS_ORIGIN); },
    defineProperty: function (t, key) { throw crossOriginError('set', key); },
    deleteProperty: function (t, key) { throw crossOriginError('set', key); },
    getPrototypeOf: function () { return null; },
    setPrototypeOf: function (t, v) { return v === null; },
    isExtensible: function () { return true; },
    preventExtensions: function () { return false; }
  };
  proxy = new Proxy(ObjectCreate(null), handler);
  MapSet(windowProxies, id, proxy);
  WeakMapSet(proxyWindows, proxy, id);
  return proxy;
}
function isWindowProxy(v) { return v === global || WeakMapHas(proxyWindows, v); }

/* A stream stays in its window: it moves to another only through a port, which this does not do. */
function refuseStreams(transfer, what) {
  for (var i = 0; i < transfer.length; i++) {
    var kind = streamKind(transfer[i]);
    if (kind !== null) throw cloneError(what, 'A ' + kind + ' could not be transferred to another window.');
  }
}
/* postMessage of another window's proxy: the clone goes to the host, which hands it to that window. */
function postToWindow(id, args, what) {
  var a = postArgs(args, what);
  refuseStreams(a.transfer, what);
  var r = cloneWithTransfer(args[0], a.transfer, what, null);
  // Every window of the book has the book's origin, so another target origin takes nothing.
  if (a.wanted !== null && a.wanted !== origin) {
    for (var i = 0; i < r.ports.length; i++) closePort(r.ports[i]);
    return;
  }
  K.post(id, messageText(r.value, r.ports, id));
}

/* ---- ports between windows ----
   A port whose partner is in another window has an end of a route instead of a partner. The host
   keeps which window holds each end; the two ends of a route are n and n ^ 1. What a port posts
   goes to the host, which hands it to the window that holds the other end. */
var routePorts = new Map(), routeHeld = new Map();
/* A message as the host carries it: the clone, and the ports it moves to [dest]. */
function messageText(value, ports, dest) { return JSONStringify([encodeValue(value), exportPorts(ports, dest)]); }
function messageOf(text) {
  var parsed = JSONParse(text);
  return { __proto__: null, value: decodeValue(parsed[0]), ports: importPorts(parsed[1]) };
}
/* The ends of [ports], which go to [dest]: a window's id, or minus an end for the window that
   holds it. Each end goes with the messages that reached its port and did not run, in order. */
function exportPorts(ports, dest) {
  var out = [];
  for (var i = 0; i < ports.length; i++) {
    var q = ports[i], s = portOf(q), end = s.route;
    if (end) {
      K.portMove(end, dest);
      MapDelete(routePorts, end);
    } else {
      end = K.portRoute(dest);
      var partner = s.other;
      if (partner) {
        var ps = portOf(partner);
        ps.other = null;
        ps.route = end ^ 1;
        MapSet(routePorts, end ^ 1, partner);
      }
    }
    var held = [];
    for (var j = 0; j < s.queue.length; j++) ArrayPush(held, messageText(s.queue[j].value, s.queue[j].ports, dest));
    s.route = 0;
    s.other = null;
    s.queue = [];
    s.neutered = true;
    dropTasks(q);
    ArrayPush(out, [end, held]);
  }
  return out;
}
/* The ports of [ends], made in this window, each with the messages it carried and then those that
   reached its end before it. A port whose partner is here is entangled with it again, and the host
   forgets their route. */
function importPorts(ends) {
  var out = [];
  for (var i = 0; i < ends.length; i++) {
    var end = ends[i][0], carried = ends[i][1], p = makePort(), s = portOf(p);
    for (var j = 0; j < carried.length; j++) ArrayPush(s.queue, messageOf(carried[j]));
    var held = MapGet(routeHeld, end);
    if (held !== undefined) {
      for (var k = 0; k < held.length; k++) ArrayPush(s.queue, held[k]);
      MapDelete(routeHeld, end);
    }
    var here = MapGet(routePorts, end ^ 1);
    if (here !== undefined) {
      var hs = portOf(here);
      MapDelete(routePorts, end ^ 1);
      K.portClose(end);
      hs.route = 0;
      hs.other = p;
      s.other = here;
    } else {
      s.route = end;
      MapSet(routePorts, end, p);
    }
    ArrayPush(out, p);
  }
  return out;
}
/* A message that the host carries from another window: to the end of a route, or to this window. */
hostEntry('__kite_deliver', function () {
  var d = K.delivery();
  if (d === null) return;
  if (d[0]) portMessage(d[0], d[2]); else windowMessage(d[1], d[2]);
});
function portMessage(end, text) {
  var m = messageOf(text), p = MapGet(routePorts, end);
  if (p === undefined) {
    // The port is on its way here in a message that has not arrived yet.
    var held = MapGet(routeHeld, end);
    if (held === undefined) MapSet(routeHeld, end, held = []);
    ArrayPush(held, m);
    return;
  }
  var s = portOf(p);
  ArrayPush(s.queue, m);
  if (s.enabled) queuePortTask(p);
}
function windowMessage(source, text) {
  var m = messageOf(text);
  queueTask(function* () {
    var e = new MessageEvent('message', { __proto__: null, data: m.value, origin: origin, source: windowProxy(source), ports: m.ports });
    e.isTrusted = true;
    for (var g = dispatchSteps(global, e); !GeneratorNext(g).done;) yield;
  });
}

/* ---- a clone as text ----
   A value that cloneValue made, written as JSON: each object once, in a list of records that the
   value and the records refer to by index, so a cycle and a shared object come back as they were.
   Bytes are strings with a code unit for each byte. */
function numberText(x) {
  if (x !== x) return 'NaN';
  if (x === 0 && 1 / x < 0) return '-0';
  return String(x);
}
function encodeValue(value) {
  var memory = new Map(), records = [];
  function props(x) {
    var out = [], keys = ObjectKeys(x);
    for (var i = 0; i < keys.length; i++) ArrayPush(out, [keys[i], enc(x[keys[i]])]);
    return out;
  }
  function record(x) {
    var data, file, tag;
    if ((data = WeakMapGet(blobs, x)) !== undefined) {
      file = WeakMapGet(files, x);
      return ['L', byteString(data.bytes), data.type, file === undefined ? null : file.name, file === undefined ? 0 : file.lastModified];
    }
    if ((data = WeakMapGet(exceptions, x)) !== undefined) return ['X', data.name, data.message];
    if ((data = WeakMapGet(imageDatas, x)) !== undefined) return ['I', data.width, data.height, enc(data.data), data.colorSpace, data.pixelFormat];
    if (branded(BooleanValueOf, x)) return ['B', BooleanValueOf(x)];
    if (branded(NumberValueOf, x)) return ['N', numberText(NumberValueOf(x))];
    if (branded(BigIntValueOf, x)) return ['G', String(BigIntValueOf(x))];
    if (branded(StringValueOf, x)) return ['S', StringValueOf(x)];
    if (branded(DateGetTime, x)) return ['D', numberText(DateGetTime(x))];
    if (x !== RegExpPrototype && branded(RegExpSource, x)) return ['R', RegExpSource(x), regExpFlags(x)];
    if (isBuffer(x, ArrayBufferByteLength)) {
      return ['A', byteString(new Uint8Array(x)), ArrayBufferResizable && ArrayBufferResizable(x) ? ArrayBufferMaxByteLength(x) : -1];
    }
    if (ArrayBufferIsView(x)) {
      tag = TypedArrayTag(x);
      if (tag === undefined) return ['W', enc(DataViewBuffer(x)), DataViewByteOffset(x), DataViewByteLength(x)];
      return ['V', tag, enc(TypedArrayBuffer(x)), TypedArrayByteOffset(x), TypedArrayLength(x)];
    }
    if (branded(MapSize, x)) {
      var pairs = [];
      for (var it = MapEntries(x), s; !(s = MapIteratorNext(it)).done;) ArrayPush(pairs, s.value);
      var outPairs = [];
      for (var i = 0; i < pairs.length; i++) ArrayPush(outPairs, [enc(pairs[i][0]), enc(pairs[i][1])]);
      return ['M', outPairs];
    }
    if (branded(SetSize, x)) {
      var items = [];
      for (var sit = SetValues(x), t; !(t = SetIteratorNext(sit)).done;) ArrayPush(items, t.value);
      var outItems = [];
      for (var j = 0; j < items.length; j++) ArrayPush(outItems, enc(items[j]));
      return ['T', outItems];
    }
    if (isErrorObject(x)) {
      var m = ObjectGetOwnPropertyDescriptor(x, 'message'), st = ObjectGetOwnPropertyDescriptor(x, 'stack'), c = ObjectGetOwnPropertyDescriptor(x, 'cause');
      return ['E', x.name, m !== undefined && ObjectHasOwn(m, 'value') ? m.value : null, st !== undefined && typeof st.value === 'string' ? st.value : null,
        c !== undefined && ObjectHasOwn(c, 'value') ? [enc(c.value)] : null];
    }
    if (ArrayIsArray(x)) return ['Y', x.length, props(x)];
    return ['O', props(x)];
  }
  function enc(x) {
    if (x === undefined) return ['u'];
    if (x === null || typeof x === 'boolean' || typeof x === 'string') return x;
    if (typeof x === 'number') return ['n', numberText(x)];
    if (typeof x === 'bigint') return ['g', String(x)];
    var known = MapGet(memory, x);
    if (known !== undefined) return ['r', known];
    var index = records.length;
    ArrayPush(records, null);
    MapSet(memory, x, index);
    records[index] = record(x);
    return ['r', index];
  }
  var root = enc(value);
  return JSONStringify([root, records]);
}
function decodeValue(text) {
  var parsed = JSONParse(text), records = parsed[1], made = new Map();
  function fill(out, list) { for (var i = 0; i < list.length; i++) putData(out, list[i][0], dec(list[i][1])); }
  function build(i) {
    var r = records[i], tag = r[0], out, k;
    if (tag === 'O') { out = {}; MapSet(made, i, out); fill(out, r[1]); return out; }
    if (tag === 'Y') { out = []; out.length = r[1]; MapSet(made, i, out); fill(out, r[2]); return out; }
    if (tag === 'M') {
      out = new Map();
      MapSet(made, i, out);
      for (k = 0; k < r[1].length; k++) MapSet(out, dec(r[1][k][0]), dec(r[1][k][1]));
      return out;
    }
    if (tag === 'T') {
      out = new SetCtor();
      MapSet(made, i, out);
      for (k = 0; k < r[1].length; k++) SetAdd(out, dec(r[1][k]));
      return out;
    }
    if (tag === 'A') {
      var bytes = bytesOf(r[1]);
      out = r[2] >= 0 ? new ArrayBufferCtor(bytes.length, { __proto__: null, maxByteLength: r[2] }) : new ArrayBufferCtor(bytes.length);
      TypedArraySet(new Uint8Array(out), bytes);
    } else if (tag === 'V') {
      var Typed = TYPED_ARRAYS[r[1]];
      out = new Typed(dec(r[2]), r[3], r[4]);
    } else if (tag === 'W') out = new DataViewCtor(dec(r[1]), r[2], r[3]);
    else if (tag === 'B') out = ObjectCtor(r[1]);
    else if (tag === 'N') out = ObjectCtor(Number(r[1]));
    else if (tag === 'G') out = ObjectCtor(BigIntCtor(r[1]));
    else if (tag === 'S') out = ObjectCtor(r[1]);
    else if (tag === 'D') out = new DateCtor(Number(r[1]));
    else if (tag === 'R') out = new RegExpCtor(r[1], r[2]);
    else if (tag === 'X') out = new DOMException(r[2], r[1]);
    else if (tag === 'E') {
      var Type = typeof r[1] === 'string' && ERROR_TYPES[r[1]] ? ERROR_TYPES[r[1]] : Error;
      out = new Type();
      MapSet(made, i, out);
      if (r[2] !== null) putHidden(out, 'message', r[2]);
      if (r[3] !== null) putHidden(out, 'stack', r[3]);
      if (r[4] !== null) putHidden(out, 'cause', dec(r[4][0]));
      return out;
    } else if (tag === 'L') {
      out = ObjectCreate(r[3] === null ? Blob.prototype : File.prototype);
      WeakMapSet(blobs, out, { bytes: bytesOf(r[1]), type: r[2] });
      if (r[3] !== null) WeakMapSet(files, out, { name: r[3], lastModified: r[4] });
    } else if (tag === 'I') {
      out = ObjectCreate(ImageData.prototype);
      MapSet(made, i, out);
      WeakMapSet(imageDatas, out, { __proto__: null, width: r[1], height: r[2], data: dec(r[3]), colorSpace: r[4], pixelFormat: r[5] });
      return out;
    }
    MapSet(made, i, out);
    return out;
  }
  function dec(x) {
    if (x === null || typeof x !== 'object') return x;
    var tag = x[0];
    if (tag === 'u') return undefined;
    if (tag === 'n') return Number(x[1]);
    if (tag === 'g') return BigIntCtor(x[1]);
    var known = MapGet(made, x[1]);
    return known !== undefined ? known : build(x[1]);
  }
  return dec(parsed[0]);
}

/* ---- frames as elements ----
   The window of an iframe, or of an object that shows a document, and the load event each element
   gets once its document has loaded. */
var frameLoaded = new WeakMap();
function frameWindow(el) {
  var id = idOf(el);
  return K.connected(id) ? windowProxy(K.frameWindow(id)) : null;
}
/* The iframe and object elements of the document that have a document and have not had their load event. */
function* frameLoadSteps() {
  var ids = K.frames(rootId);
  for (var i = 0; i < ids.length; i++) {
    var el = wrap(ids[i]);
    if (WeakMapHas(frameLoaded, el)) continue;
    WeakMapSet(frameLoaded, el, true);
    var e = new Event('load');
    e.isTrusted = true;
    for (var g = dispatchSteps(el, e); !GeneratorNext(g).done;) yield;
  }
}
hostEntry('__kite_frames_loaded', function () { return begin(frameLoadSteps()); });
"""
