package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] for structured cloning and messaging (#534): `structuredClone`,
 * `window.postMessage`, `MessageChannel`, `MessagePort` and `MessageEvent`, of HTML, 2.7 and 9.4.
 */
internal const val DOM_PRELUDE_MESSAGING: String = """/* ---- structured cloning and messaging, of HTML, 2.7, 9.4 and 9.5 (#534) ----
   A chapter is one realm, so a clone is made at once, where HTML serializes a value and then
   deserializes it. A transferred buffer is copied into its clone and then detached, so a clone
   that throws halfway detaches nothing. Each message arrives in a task of its own. */

function cloneError(what, message) { return new DOMException(what === null ? message : what + ': ' + message, 'DataCloneError'); }
/* Whether [check], a built-in that throws for a value without its internal slots, takes [v]. */
function branded(check, v) {
  if (!check) return false;
  try { check(v); return true; } catch (e) { return false; }
}
/* The interface of a platform object, by the first interface prototype on its chain, or null. */
var interfaceNames = null;
function interfaceOf(v) {
  if (interfaceNames === null) {
    interfaceNames = new Map();
    for (var i = 0; i < INTERFACES.length; i++) MapSet(interfaceNames, INTERFACES[i].ctor.prototype, INTERFACES[i].name);
  }
  for (var p = ObjectGetPrototypeOf(v), n = 0; p !== null && n < 64; p = ObjectGetPrototypeOf(p), n++) {
    if (MapHas(interfaceNames, p)) return MapGet(interfaceNames, p);
  }
  return null;
}
/* An object whose internal slots no clone can carry, as V8 names it in its message, or null. */
function unclonable(v) {
  var tag = ObjectToString(v);
  if (branded(SymbolValueOf, v)) return '[object Symbol]';
  if (tag === '[object Generator]' || tag === '[object AsyncGenerator]') return tag;
  if (tag === '[object Arguments]') return '#<Object>';
  if (tag === '[object Promise]') return '#<Promise>';
  if (WeakSetHas && branded(function (x) { WeakSetHas(x, global); }, v)) return '#<WeakSet>';
  if (branded(function (x) { WeakMapHas(x, global); }, v)) return '#<WeakMap>';
  if (branded(WeakRefDeref, v)) return '#<WeakRef>';
  if (RegistryUnregister && branded(function (x) { RegistryUnregister(x, global); }, v)) return '#<FinalizationRegistry>';
  return null;
}
function isErrorObject(v) { return ErrorIsError ? ErrorIsError(v) : ObjectToString(v) === '[object Error]'; }
function regExpFlags(v) {
  var flags = '';
  for (var i = 0; i < REGEXP_FLAGS.length; i++) {
    var has = REGEXP_FLAGS[i][0];
    if (has(v)) flags += REGEXP_FLAGS[i][1];
  }
  return flags;
}
function copyBuffer(v, what) {
  if (ArrayBufferDetached && ArrayBufferDetached(v)) throw cloneError(what, 'An ArrayBuffer is detached and could not be cloned.');
  var length = ArrayBufferByteLength(v);
  var out = ArrayBufferResizable && ArrayBufferResizable(v)
    ? new ArrayBufferCtor(length, { __proto__: null, maxByteLength: ArrayBufferMaxByteLength(v) })
    : new ArrayBufferCtor(length);
  TypedArraySet(new Uint8Array(out), new Uint8Array(v));
  return out;
}
/* Defines [key] on [out] as an own data property, as CreateDataProperty does, so __proto__ is a key like any other. */
function putData(out, key, value) {
  ObjectDefineProperty(out, key, { __proto__: null, value: value, writable: true, enumerable: true, configurable: true });
}
function putHidden(out, key, value) {
  ObjectDefineProperty(out, key, { __proto__: null, value: value, writable: true, enumerable: false, configurable: true });
}
/* The clone of [v] (HTML, 2.7.3 and 2.7.6), with [memory] mapping each object cloned so far to its clone. */
function cloneValue(v, memory, what) {
  if (typeof v === 'symbol') throw cloneError(what, SymbolToString(v) + ' could not be cloned.');
  if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return v;
  if (MapHas(memory, v)) return MapGet(memory, v);
  if (typeof v === 'function') throw cloneError(what, FunctionToString(v) + ' could not be cloned.');
  if (v === global) throw cloneError(what, '#<Window> could not be cloned.');
  var out, data;
  if (WeakMapHas(messagePorts, v)) throw cloneError(what, 'A MessagePort could not be cloned because it was not transferred.');
  var kind = streamKind(v);
  if (kind !== null) throw cloneError(what, 'A ' + kind + ' could not be cloned because it was not transferred.');
  if ((data = WeakMapGet(blobs, v)) !== undefined) {
    var file = WeakMapGet(files, v);
    out = ObjectCreate(file === undefined ? Blob.prototype : File.prototype);
    WeakMapSet(blobs, out, data);
    if (file !== undefined) WeakMapSet(files, out, file);
    MapSet(memory, v, out);
    return out;
  }
  if ((data = WeakMapGet(exceptions, v)) !== undefined) {
    out = new DOMException(data.message, data.name);
    MapSet(memory, v, out);
    return out;
  }
  if ((data = WeakMapGet(imageDatas, v)) !== undefined) {
    // An ImageData is serializable (HTML, 4.12.5.1.16): its pixels clone as a typed array would.
    out = ObjectCreate(ImageData.prototype);
    MapSet(memory, v, out);
    WeakMapSet(imageDatas, out, { __proto__: null, width: data.width, height: data.height, data: cloneValue(data.data, memory, what),
      colorSpace: data.colorSpace, pixelFormat: data.pixelFormat });
    return out;
  }
  var platform = interfaceOf(v);
  if (platform !== null) throw cloneError(what, platform + ' object could not be cloned.');
  if (branded(BooleanValueOf, v)) out = ObjectCtor(BooleanValueOf(v));
  else if (branded(NumberValueOf, v)) out = ObjectCtor(NumberValueOf(v));
  else if (branded(BigIntValueOf, v)) out = ObjectCtor(BigIntValueOf(v));
  else if (branded(StringValueOf, v)) out = ObjectCtor(StringValueOf(v));
  else if (branded(DateGetTime, v)) out = new DateCtor(DateGetTime(v));
  else if (v !== RegExpPrototype && branded(RegExpSource, v)) out = new RegExpCtor(RegExpSource(v), regExpFlags(v));
  else if (isBuffer(v, ArrayBufferByteLength)) out = copyBuffer(v, what);
  else if (isBuffer(v, SharedArrayBufferByteLength)) throw cloneError(what, '#<SharedArrayBuffer> could not be cloned.');
  else if (ArrayBufferIsView(v)) {
    var tag = TypedArrayTag(v);
    if (tag === undefined) {
      out = new DataViewCtor(cloneValue(DataViewBuffer(v), memory, what), DataViewByteOffset(v), DataViewByteLength(v));
    } else {
      var Typed = TYPED_ARRAYS[tag];
      out = new Typed(cloneValue(TypedArrayBuffer(v), memory, what), TypedArrayByteOffset(v), TypedArrayLength(v));
    }
  } else if (branded(MapSize, v)) {
    out = new Map();
    MapSet(memory, v, out);
    var pairs = [];
    for (var it = MapEntries(v), s; !(s = MapIteratorNext(it)).done;) ArrayPush(pairs, s.value);
    for (var i = 0; i < pairs.length; i++) MapSet(out, cloneValue(pairs[i][0], memory, what), cloneValue(pairs[i][1], memory, what));
    return out;
  } else if (branded(SetSize, v)) {
    out = new SetCtor();
    MapSet(memory, v, out);
    var items = [];
    for (var sit = SetValues(v), t; !(t = SetIteratorNext(sit)).done;) ArrayPush(items, t.value);
    for (var j = 0; j < items.length; j++) SetAdd(out, cloneValue(items[j], memory, what));
    return out;
  } else if (isErrorObject(v)) {
    return cloneErrorValue(v, memory, what);
  } else {
    var bad = unclonable(v);
    if (bad !== null) throw cloneError(what, bad + ' could not be cloned.');
    if (ArrayIsArray(v)) { out = []; out.length = v.length; } else out = {};
    MapSet(memory, v, out);
    var keys = ObjectKeys(v);
    for (var k = 0; k < keys.length; k++) {
      // A property that a getter before it deleted is skipped, as HTML checks each key again.
      if (ObjectHasOwn(v, keys[k])) putData(out, keys[k], cloneValue(v[keys[k]], memory, what));
    }
    return out;
  }
  MapSet(memory, v, out);
  return out;
}
/* An Error's clone: its type when its name is one of the seven, its message, and, as Chromium
   keeps them, its stack and its cause. */
function cloneErrorValue(v, memory, what) {
  var name = v.name;
  var Type = typeof name === 'string' && ERROR_TYPES[name] ? ERROR_TYPES[name] : Error;
  var m = ObjectGetOwnPropertyDescriptor(v, 'message');
  var message = m !== undefined && ObjectHasOwn(m, 'value') ? String(m.value) : undefined;
  var out = new Type();
  MapSet(memory, v, out);
  if (message !== undefined) putHidden(out, 'message', message);
  var stack = ObjectGetOwnPropertyDescriptor(v, 'stack');
  if (stack !== undefined && typeof stack.value === 'string') putHidden(out, 'stack', stack.value);
  var cause = ObjectGetOwnPropertyDescriptor(v, 'cause');
  if (cause !== undefined && ObjectHasOwn(cause, 'value')) putHidden(out, 'cause', cloneValue(cause.value, memory, what));
  return out;
}
/* The clone of [value] with the objects of [transfer] moved into it (HTML, 2.7.4): its value and
   the new ports. [source] is the port that posts it, which cannot move with its own message. As
   Chromium does, a locked stream fails the clone after the value cloned and before a buffer detaches. */
function cloneWithTransfer(value, transfer, what, source) {
  var memory = new Map(), buffers = [], movedPorts = [], newPorts = [], streams = [], shells = [], kind;
  for (var i = 0; i < transfer.length; i++) {
    var t = transfer[i];
    if ((kind = streamKind(t)) !== null) {
      if (ArrayIndexOf(streams, t) >= 0) throw cloneError(what, kind + ' at index ' + i + ' is a duplicate of an earlier ' + kind + '.');
      ArrayPush(streams, t);
    } else if (WeakMapHas(messagePorts, t)) {
      if (t === source) throw cloneError(what, 'Port at index ' + i + ' contains the source port.');
      if (ArrayIndexOf(movedPorts, t) >= 0) throw cloneError(what, 'Message port at index ' + i + ' is a duplicate of an earlier port.');
      if (portOf(t).neutered) throw cloneError(what, 'Port at index ' + i + ' is already neutered.');
      ArrayPush(movedPorts, t);
    } else if (isBuffer(t, ArrayBufferByteLength)) {
      if (ArrayIndexOf(buffers, t) >= 0) throw cloneError(what, 'ArrayBuffer at index ' + i + ' is a duplicate of an earlier ArrayBuffer.');
      if (ArrayBufferDetached && ArrayBufferDetached(t)) throw cloneError(what, 'ArrayBuffer at index ' + i + ' is already detached.');
      ArrayPush(buffers, t);
    } else {
      throw cloneError(what, 'Value at index ' + i + ' does not have a transferable type.');
    }
  }
  for (var p = 0; p < movedPorts.length; p++) {
    var made = makePort();
    ArrayPush(newPorts, made);
    MapSet(memory, movedPorts[p], made);
  }
  for (var n = 0; n < streams.length; n++) {
    var shell = streamShell(streams[n]);
    ArrayPush(shells, shell);
    MapSet(memory, streams[n], shell);
  }
  var out = cloneValue(value, memory, what);
  for (var l = 0; l < streams.length; l++) {
    if (streamTransferLocked(streams[l])) throw cloneError(what, 'A ' + streamKind(streams[l]) + ' could not be cloned because it was locked');
  }
  for (var m = 0; m < streams.length; m++) moveStream(streams[m], shells[m]);
  for (var b = 0; b < buffers.length; b++) if (ArrayBufferTransfer) ArrayBufferTransfer(buffers[b]);
  for (var q = 0; q < movedPorts.length; q++) movePort(movedPorts[q], newPorts[q]);
  return { __proto__: null, value: out, ports: newPorts };
}
/* The transfer list of a postMessage or a structuredClone whose second argument is [v]: a
   sequence of objects, or a dictionary with a transfer member, as the overloads say. */
function transferOf(v, what, sequenceAllowed) {
  var member = what + ": Failed to read the 'transfer' property from 'StructuredSerializeOptions'";
  if (v === undefined || v === null) return [];
  if (typeof v !== 'object' && typeof v !== 'function') {
    if (sequenceAllowed) throw new TypeError(what + ': Overload resolution failed.');
    throw new TypeError(what + ": The provided value is not of type 'StructuredSerializeOptions'.");
  }
  var method = sequenceAllowed ? v[SymbolIterator] : undefined;
  if (method !== undefined) return idlSequence(v, transferable, what, method);
  var list = v.transfer;
  return list === undefined ? [] : idlSequence(list, transferable, member);
}
function transferable(x) { return x; }

function structuredClone(value) {
  var what = "Failed to execute 'structuredClone' on 'Window'";
  needArgs(arguments, 1, what);
  return cloneWithTransfer(value, transferOf(arguments[1], what, false), what, null).value;
}

/* MessagePort, of HTML, 9.4.4. Its state lives in a weak map: the port it is entangled with, the
   messages it holds until it starts, and whether it moved away in a transfer. */
var messagePorts = new WeakMap();
function portOf(p) {
  var s = WeakMapGet(messagePorts, p);
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function makePort() {
  var p = ObjectCreate(MessagePort.prototype);
  WeakMapSet(messagePorts, p, { __proto__: null, other: null, queue: [], enabled: false, neutered: false, handler: null });
  return p;
}
function MessagePort() { illegal('MessagePort'); }
MessagePort.prototype = ObjectCreate(EventTarget.prototype);
defineInterface(MessagePort, 'MessagePort', EventTarget, 0);
/* [from] moves into [to]: [to] takes its partner and the messages it held, and [from] is left with neither. */
function movePort(from, to) {
  var a = portOf(from), b = portOf(to);
  b.other = a.other;
  if (a.other) portOf(a.other).other = to;
  b.queue = a.queue;
  a.queue = [];
  a.other = null;
  a.neutered = true;
  dropTasks(from);
}
/* The task that hands the next message [port] holds to its listeners. */
function queuePortTask(port) {
  var task = function* () {
    var s = portOf(port);
    if (!s.queue.length) return;
    var m = ArrayShift(s.queue);
    // A port of a moved stream hands its messages to the stream, not to listeners.
    if (s.handler !== null) { var handle = s.handler; handle(m.value); return; }
    var e = new MessageEvent('message', { __proto__: null, data: m.value, ports: m.ports });
    e.isTrusted = true;
    for (var g = dispatchSteps(port, e); !GeneratorNext(g).done;) yield;
  };
  task.owner = port;
  queueTask(task);
}
function startPort(port) {
  var s = portOf(port);
  if (s.enabled) return;
  s.enabled = true;
  for (var i = 0; i < s.queue.length; i++) queuePortTask(port);
}
MessagePort.prototype.postMessage = function (message) {
  var what = "Failed to execute 'postMessage' on 'MessagePort'", s = portOf(this);
  needArgs(arguments, 1, what);
  postToPort(this, cloneWithTransfer(message, transferOf(arguments[1], what, true), what, this));
};
/* Hands [r], a clone, to the partner of [port], if it has one. */
function postToPort(port, r) {
  var s = portOf(port);
  if (!s.other) return;
  var target = portOf(s.other);
  ArrayPush(target.queue, r);
  if (target.enabled) queuePortTask(s.other);
}
MessagePort.prototype.start = function () { startPort(this); };
MessagePort.prototype.close = function () { closePort(this); };
function closePort(port) {
  var s = portOf(port);
  if (s.other) portOf(s.other).other = null;
  s.other = null;
  s.queue = [];
  dropTasks(port);
}
// The first time a script sets onmessage, the port starts (HTML, 9.4.4).
def(MessagePort.prototype, 'onmessage', function () { portOf(this); return handlerValue(this, 'message'); }, function (v) {
  portOf(this);
  setHandler(this, 'message', v !== null && (typeof v === 'object' || typeof v === 'function') ? v : null, null);
  startPort(this);
});
defineHandlers(MessagePort.prototype, ['messageerror', 'close']);

/* MessageChannel, of HTML, 9.4.3: two ports entangled with each other. */
var channels = new WeakMap();
function channelOf(c) {
  var s = WeakMapGet(channels, c);
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function MessageChannel() {
  needNew(this, MessageChannel, 'MessageChannel', function (c) { return WeakMapHas(channels, c); });
  var a = makePort(), b = makePort();
  portOf(a).other = b;
  portOf(b).other = a;
  WeakMapSet(channels, this, { __proto__: null, port1: a, port2: b });
}
defineInterface(MessageChannel, 'MessageChannel', null, 0);
def(MessageChannel.prototype, 'port1', function () { return channelOf(this).port1; });
def(MessageChannel.prototype, 'port2', function () { return channelOf(this).port2; });

/* MessageEvent, of HTML, 9.3.1. Its dictionary's members are read in their lexicographic order. */
var MessageEvent = subEvent(Event, 'MessageEvent', function (init) {
  var what = "Failed to construct 'MessageEvent'";
  this.data = init.data === undefined ? null : init.data;
  this.lastEventId = init.lastEventId === undefined ? '' : domString(init.lastEventId);
  this.origin = init.origin === undefined ? '' : usv(init.origin);
  var list = init.ports === undefined ? [] : idlSequence(init.ports, function (p) {
    if (!WeakMapHas(messagePorts, p)) throw new TypeError(what + ": Failed to read the 'ports' property from 'MessageEventInit': Failed to convert value to 'MessagePort'.");
    return p;
  }, what + ": Failed to read the 'ports' property from 'MessageEventInit'");
  this.ports = ObjectFreeze(list);
  var source = init.source;
  if (source === undefined || source === null) source = null;
  else if (source !== global && !WeakMapHas(messagePorts, source)) {
    if (!isA(source, EventTarget)) throw new TypeError(what + ": Failed to read the 'source' property from 'MessageEventInit': Failed to convert value to 'EventTarget'.");
    throw new TypeError(what + ": The optional 'source' property is neither a Window nor MessagePort.");
  }
  this.source = source;
}, 1);
MessageEvent.prototype.initMessageEvent = function (type, bubbles, cancelable, data, origin, lastEventId, source, ports) {
  needArgs(arguments, 1, "Failed to execute 'initMessageEvent' on 'MessageEvent'");
  initEventOf(this, type, bubbles, cancelable);
  this.data = data === undefined ? null : data;
  this.origin = origin === undefined ? '' : usv(origin);
  this.lastEventId = lastEventId === undefined ? '' : domString(lastEventId);
  this.source = source == null ? null : source;
  this.ports = ObjectFreeze(ports == null ? [] : idlSequence(ports, transferable, "Failed to execute 'initMessageEvent' on 'MessageEvent'"));
};

/* window.postMessage (HTML, 9.3.3): the message goes to the chapter's own window, in a task, when
   the target origin is the book's. A target origin of / is the book's, and * is any. */
function postMessage(message) {
  var what = "Failed to execute 'postMessage' on 'Window'";
  needArgs(arguments, 1, what);
  var second = arguments[1], target = '/', transfer;
  if (second === undefined || second === null || typeof second === 'object' || typeof second === 'function') {
    var options = idlDictionary(second, what, 'WindowPostMessageOptions');
    var to = options.targetOrigin;
    if (to !== undefined) target = usv(to);
    var list = options.transfer;
    transfer = list === undefined ? [] : idlSequence(list, transferable, what + ": Failed to read the 'transfer' property from 'WindowPostMessageOptions'");
  } else {
    target = usv(second);
    transfer = arguments[2] === undefined ? [] : idlSequence(arguments[2], transferable, what);
  }
  var wanted = null;
  if (target === '/') wanted = origin;
  else if (target !== '*') {
    var parts = parseUrl(target);
    if (parts == null) throw new DOMException(what + ": Invalid target origin '" + target + "' in a call to 'postMessage'.", 'SyntaxError');
    wanted = parts[1];
  }
  var r = cloneWithTransfer(message, transfer, what, null);
  queueTask(function* () {
    if (wanted !== null && wanted !== origin) return;
    var e = new MessageEvent('message', { __proto__: null, data: r.value, origin: origin, source: global, ports: r.ports });
    e.isTrusted = true;
    for (var g = dispatchSteps(global, e); !GeneratorNext(g).done;) yield;
  });
}
"""
