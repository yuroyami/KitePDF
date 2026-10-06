package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] for the Streams Standard (#536): readable, writable and transform
 * streams, their readers, writers and controllers, the queuing strategies, `TextEncoderStream`,
 * `TextDecoderStream`, and `Blob.stream()` with `Blob.textStream()`.
 */
internal const val DOM_PRELUDE_STREAMS: String = """/* ---- the Streams Standard (#536) ----
   Each stream, reader, writer and controller keeps its slots in a record of a weak map, and the
   algorithms of the standard work on the records, step for step, so each promise settles in the
   microtask the standard gives it. A record's self is the object a script holds. */

/* ---- promises, as the standard's "a new promise", "upon fulfillment" and "transforming" ---- */

function newDeferred() {
  var d = { __proto__: null, promise: null, resolve: null, reject: null, done: false };
  d.promise = new Promise(function (resolve, reject) { d.resolve = resolve; d.reject = reject; });
  return d;
}
function resolveDeferred(d, v) { if (d.done) return; d.done = true; var f = d.resolve; f(v); }
function rejectDeferred(d, e) { if (d.done) return; d.done = true; var f = d.reject; f(e); }
function resolvedWith(v) { return ReflectApply(PromiseResolve, Promise, [v]); }
function streamNoop() {}
function markHandled(p) { PromiseThen(p, undefined, streamNoop); }
function uponPromise(p, onFulfilled, onRejected) { markHandled(PromiseThen(p, onFulfilled, onRejected)); }
function transformPromise(p, onFulfilled, onRejected) { return PromiseThen(p, onFulfilled, onRejected); }
/* A promise rejected with [e], marked handled as the standard's own rejected promises are. */
function rejectedHandled(e) { var p = rejected(e); markHandled(p); return p; }
/* A callback of a dictionary that a script gave, called with its dictionary as this. A callback
   whose IDL type returns a promise answers one, rejected with what it throws. */
function invokeSync(fn, self, args) { return ReflectApply(fn, self, args); }
function invokePromise(fn, self, args) {
  try { return resolvedWith(ReflectApply(fn, self, args)); } catch (e) { return rejected(e); }
}
function iterResult(value, done) { return { done: done, value: value }; }
function call1(f, a) { return f(a); }
function call0(f) { return f(); }
/* A dictionary argument as Chromium reads it: undefined is empty, null throws, and any other value is an object. */
function streamDictionary(v, what) {
  if (v === undefined) return { __proto__: null };
  if (v === null) throw new TypeError(what + ': Cannot convert undefined or null to object');
  return ObjectCtor(v);
}
function callbackMember(dict, name, owner, what) {
  var f = dict[name];
  if (f === undefined) return undefined;
  if (typeof f !== 'function') throw new TypeError(what + ': ' + owner + '.' + name + ' must be a function or undefined');
  return f;
}

/* ---- queues with sizes, of 8.1 ---- */

function resetQueue(c) { c.queue = []; c.queueTotalSize = 0; }
function dequeueValue(c) {
  var pair = ArrayShift(c.queue);
  c.queueTotalSize -= pair.size;
  if (c.queueTotalSize < 0) c.queueTotalSize = 0;
  return pair.value;
}
function enqueueValueWithSize(c, value, size) {
  size = Number(size);
  if (size !== size || size < 0 || size === 1 / 0) {
    throw new RangeError("The return value of a queuing strategy's size function must be a finite, non-NaN, non-negative number");
  }
  ArrayPush(c.queue, { __proto__: null, value: value, size: size });
  c.queueTotalSize += size;
}

/* ---- queuing strategies, of 7 ---- */

/* A QueuingStrategy dictionary: its highWaterMark as an unrestricted double, then its size callback. */
function queuingStrategy(v, what) {
  var dict = streamDictionary(v, what);
  var hwm = dict.highWaterMark;
  if (hwm !== undefined) hwm = Number(hwm);
  var size = dict.size;
  if (size !== undefined && typeof size !== 'function') throw new TypeError(what + ": A queuing strategy's size property must be a function");
  return { __proto__: null, highWaterMark: hwm, size: size };
}
function strategyHighWaterMark(strategy, fallback, what) {
  var hwm = strategy.highWaterMark;
  if (hwm === undefined) return fallback;
  if (hwm !== hwm || hwm < 0) throw new RangeError(what + ": A queuing strategy's highWaterMark property must be a nonnegative, non-NaN number");
  return hwm;
}
function strategySize(strategy) {
  var size = strategy.size;
  if (size === undefined) return function () { return 1; };
  return function (chunk) { return ReflectApply(size, undefined, [chunk]); };
}
var strategies = new WeakMap();
function strategyInit(self, ctor, name, args) {
  var what = "Failed to construct '" + name + "'";
  needNew(self, ctor, name, function (s) { return WeakMapHas(strategies, s); });
  needArgs(args, 1, what);
  var init = idlDictionary(args[0], what, 'QueuingStrategyInit');
  var hwm = init.highWaterMark;
  if (hwm === undefined) throw new TypeError(what + ": Failed to read the 'highWaterMark' property from 'QueuingStrategyInit': Required member is undefined.");
  WeakMapSet(strategies, self, Number(hwm));
}
function strategyMark(s) {
  var hwm = WeakMapGet(strategies, s);
  if (hwm === undefined) throw new TypeError('Illegal invocation');
  return hwm;
}
// The size function is one for every strategy of a kind, and reads no slot of its this.
var countSize = (function () { var size = function size() { return 1; }; return size; })();
var byteLengthSize = (function () {
  var size = function size(chunk) {
    if (chunk === undefined || chunk === null) throw new TypeError('Cannot convert undefined or null to object');
    return chunk.byteLength;
  };
  return size;
})();
function CountQueuingStrategy(init) { strategyInit(this, CountQueuingStrategy, 'CountQueuingStrategy', arguments); }
defineInterface(CountQueuingStrategy, 'CountQueuingStrategy', null, 1);
def(CountQueuingStrategy.prototype, 'highWaterMark', function () { return strategyMark(this); });
def(CountQueuingStrategy.prototype, 'size', function () { strategyMark(this); return countSize; });
function ByteLengthQueuingStrategy(init) { strategyInit(this, ByteLengthQueuingStrategy, 'ByteLengthQueuingStrategy', arguments); }
defineInterface(ByteLengthQueuingStrategy, 'ByteLengthQueuingStrategy', null, 1);
def(ByteLengthQueuingStrategy.prototype, 'highWaterMark', function () { return strategyMark(this); });
def(ByteLengthQueuingStrategy.prototype, 'size', function () { strategyMark(this); return byteLengthSize; });

/* ---- buffers, for byte streams ---- */

var VIEW_SIZES = { __proto__: null, Int8Array: 1, Uint8Array: 1, Uint8ClampedArray: 1, Int16Array: 2, Uint16Array: 2,
  Float16Array: 2, Int32Array: 4, Uint32Array: 4, Float32Array: 4, Float64Array: 8, BigInt64Array: 8, BigUint64Array: 8 };
function viewParts(view) {
  var tag = TypedArrayTag(view);
  if (tag === undefined) {
    return { __proto__: null, buffer: DataViewBuffer(view), byteOffset: DataViewByteOffset(view), byteLength: DataViewByteLength(view), elementSize: 1, ctor: null };
  }
  return { __proto__: null, buffer: TypedArrayBuffer(view), byteOffset: TypedArrayByteOffset(view), byteLength: TypedArrayByteLength(view),
    elementSize: VIEW_SIZES[tag], ctor: tag };
}
function makeView(ctor, buffer, byteOffset, length) {
  if (ctor === null) return new DataViewCtor(buffer, byteOffset, length);
  var Typed = TYPED_ARRAYS[ctor];
  return new Typed(buffer, byteOffset, length);
}
function isDetached(buffer) { return ArrayBufferDetached ? ArrayBufferDetached(buffer) : false; }
function transferBuffer(buffer) { return ArrayBufferTransfer(buffer); }
function copyBytes(to, toOffset, from, fromOffset, count) {
  TypedArraySet(new Uint8Array(to, toOffset, count), new Uint8Array(from, fromOffset, count));
}
function cloneBufferPart(buffer, offset, length) {
  var out = new ArrayBufferCtor(length);
  copyBytes(out, 0, buffer, offset, length);
  return out;
}

/* ---- ReadableStream, of 4.2 ---- */

var readableStreams = new WeakMap(), streamReaders = new WeakMap(), streamControllers = new WeakMap(), byobRequests = new WeakMap();
function streamOf(s, what) {
  var r = WeakMapGet(readableStreams, s);
  if (r === undefined) throw new TypeError(what ? what + ': Illegal invocation' : 'Illegal invocation');
  return r;
}
function newReadableRecord(self) {
  var r = { __proto__: null, self: self, state: 'readable', reader: undefined, storedError: undefined, disturbed: false, controller: undefined };
  WeakMapSet(readableStreams, self, r);
  return r;
}
/* A stream the standard makes for itself, as CreateReadableStream does. */
function createReadableStream(start, pull, cancel, hwm, size) {
  var r = newReadableRecord(ObjectCreate(ReadableStream.prototype));
  setUpDefaultController(r, newDefaultController(), start, pull, cancel, hwm === undefined ? 1 : hwm, size || function () { return 1; });
  return r;
}
function createByteStream(start, pull, cancel) {
  var r = newReadableRecord(ObjectCreate(ReadableStream.prototype));
  setUpByteController(r, newByteController(), start, pull, cancel, 0, undefined);
  return r;
}
function isLocked(r) { return r.reader !== undefined; }

function ReadableStream() {
  var what = "Failed to construct 'ReadableStream'";
  needNew(this, ReadableStream, 'ReadableStream', function (s) { return WeakMapHas(readableStreams, s); });
  var source = arguments[0];
  var strategy = queuingStrategy(arguments[1], what);
  var dict = streamDictionary(source, what);
  var auto = dict.autoAllocateChunkSize;
  var cancel = callbackMember(dict, 'cancel', 'underlyingSource', what);
  var pull = callbackMember(dict, 'pull', 'underlyingSource', what);
  var start = callbackMember(dict, 'start', 'underlyingSource', what);
  var type = dict.type;
  if (type !== undefined) {
    type = String(type);
    if (type !== 'bytes') throw new RangeError(what + ': Invalid type is specified');
  }
  var r = newReadableRecord(this);
  if (type === 'bytes') {
    if (strategy.size !== undefined) throw new RangeError(what + ': Cannot create byte stream with size() defined on the strategy');
    var hwm = strategyHighWaterMark(strategy, 0, what);
    if (auto !== undefined) {
      auto = enforceLong(auto, what, 'autoAllocateChunkSize');
      if (auto === 0) throw new TypeError(what + ': autoAllocateChunkSize cannot be 0');
    }
    var bc = newByteController();
    setUpByteController(r, bc,
      start ? function () { return invokeSync(start, source, [bc.self]); } : function () { return undefined; },
      pull ? function () { return invokePromise(pull, source, [bc.self]); } : function () { return resolvedWith(undefined); },
      cancel ? function (reason) { return invokePromise(cancel, source, [reason]); } : function () { return resolvedWith(undefined); },
      hwm, auto);
  } else {
    var size = strategySize(strategy);
    var mark = strategyHighWaterMark(strategy, 1, what);
    var dc = newDefaultController();
    setUpDefaultController(r, dc,
      start ? function () { return invokeSync(start, source, [dc.self]); } : function () { return undefined; },
      pull ? function () { return invokePromise(pull, source, [dc.self]); } : function () { return resolvedWith(undefined); },
      cancel ? function (reason) { return invokePromise(cancel, source, [reason]); } : function () { return resolvedWith(undefined); },
      mark, size);
  }
}
defineInterface(ReadableStream, 'ReadableStream', null, 0);
/* An [EnforceRange] unsigned long long. */
function enforceLong(v, what, member) {
  var n = Number(v);
  if (n !== n || n === 1 / 0 || n === -1 / 0) throw new TypeError(what + ": Failed to read the '" + member + "' property: Value is not a finite number.");
  n = n < 0 ? MathCeil(n) : MathFloor(n);
  if (n < 0 || n > 9007199254740991) throw new TypeError(what + ": Failed to read the '" + member + "' property: Value is outside the 'unsigned long long' value range.");
  return n;
}
def(ReadableStream.prototype, 'locked', function () { return isLocked(streamOf(this)); });
ReadableStream.prototype.cancel = function (reason) {
  var what = "Failed to execute 'cancel' on 'ReadableStream'", r = WeakMapGet(readableStreams, this);
  if (r === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  if (isLocked(r)) return rejected(new TypeError(what + ': Cannot cancel a locked stream'));
  return readableCancel(r, reason);
};
ReadableStream.prototype.getReader = function () {
  var what = "Failed to execute 'getReader' on 'ReadableStream'", r = streamOf(this);
  var options = idlDictionary(arguments[0], what, 'ReadableStreamGetReaderOptions');
  var mode = options.mode;
  if (mode !== undefined) {
    mode = String(mode);
    if (mode !== 'byob') throw new TypeError(what + ": Failed to read the 'mode' property from 'ReadableStreamGetReaderOptions': The provided value '" + mode + "' is not a valid enum value of type ReadableStreamReaderMode.");
    if (!r.controller.isByte) throw new TypeError(what + ': Cannot use a BYOB reader with a non-byte stream');
    return acquireReader(r, true, what).self;
  }
  return acquireReader(r, false, what).self;
};
ReadableStream.prototype.pipeThrough = function (transform) {
  var what = "Failed to execute 'pipeThrough' on 'ReadableStream'", r = streamOf(this);
  needArgs(arguments, 1, what);
  var pair = idlDictionary(transform, what, 'ReadableWritablePair');
  var readable = pair.readable;
  if (readable === undefined) throw new TypeError(what + ": Failed to read the 'readable' property from 'ReadableWritablePair': Required member is undefined.");
  var rr = WeakMapGet(readableStreams, readable);
  if (rr === undefined) throw new TypeError(what + ": Failed to read the 'readable' property from 'ReadableWritablePair': Failed to convert value to 'ReadableStream'.");
  var writable = pair.writable;
  if (writable === undefined) throw new TypeError(what + ": Failed to read the 'writable' property from 'ReadableWritablePair': Required member is undefined.");
  var w = WeakMapGet(writableStreams, writable);
  if (w === undefined) throw new TypeError(what + ": Failed to read the 'writable' property from 'ReadableWritablePair': Failed to convert value to 'WritableStream'.");
  var options = pipeOptions(arguments[1], what);
  if (isLocked(r)) throw new TypeError(what + ': Cannot pipe a locked stream');
  if (isWritableLocked(w)) throw new TypeError(what + ': Cannot pipe to a locked stream');
  markHandled(pipeTo(r, w, options.preventClose, options.preventAbort, options.preventCancel, options.signal));
  return readable;
};
ReadableStream.prototype.pipeTo = function (destination) {
  var what = "Failed to execute 'pipeTo' on 'ReadableStream'", r = WeakMapGet(readableStreams, this);
  if (r === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  var w = WeakMapGet(writableStreams, destination), options;
  if (arguments.length < 1) return rejected(new TypeError(what + ': 1 argument required, but only 0 present.'));
  if (w === undefined) return rejected(new TypeError(what + ": parameter 1 is not of type 'WritableStream'."));
  try { options = pipeOptions(arguments[1], what); } catch (e) { return rejected(e); }
  if (isLocked(r)) return rejected(new TypeError(what + ': Cannot pipe a locked stream'));
  if (isWritableLocked(w)) return rejected(new TypeError(what + ': Cannot pipe to a locked stream'));
  return pipeTo(r, w, options.preventClose, options.preventAbort, options.preventCancel, options.signal);
};
/* A StreamPipeOptions dictionary, its members read in their lexicographic order. */
function pipeOptions(v, what) {
  var options = idlDictionary(v, what, 'StreamPipeOptions');
  var preventAbort = !!options.preventAbort, preventCancel = !!options.preventCancel, preventClose = !!options.preventClose;
  var signal = options.signal;
  if (signal !== undefined && !isSignal(signal)) {
    throw new TypeError(what + ": Failed to read the 'signal' property from 'StreamPipeOptions': Failed to convert value to 'AbortSignal'.");
  }
  return { __proto__: null, preventAbort: preventAbort, preventCancel: preventCancel, preventClose: preventClose, signal: signal };
}
ReadableStream.prototype.tee = function () {
  var r = streamOf(this);
  var branches = r.controller.isByte ? byteTee(r) : defaultTee(r, false);
  return [branches[0].self, branches[1].self];
};
ReadableStream.prototype.values = function values() {
  var what = "Failed to execute 'values' on 'ReadableStream'", r = streamOf(this);
  var options = idlDictionary(arguments[0], what, 'ReadableStreamIteratorOptions');
  var preventCancel = !!options.preventCancel;
  var reader = acquireReader(r, false, what);
  var it = ObjectCreate(StreamIteratorPrototype);
  WeakMapSet(streamIterators, it, { __proto__: null, reader: reader, preventCancel: preventCancel, ongoing: undefined, finished: false });
  return it;
};
if (SymbolAsyncIterator) {
  ObjectDefineProperty(ReadableStream.prototype, SymbolAsyncIterator, { __proto__: null, value: ReadableStream.prototype.values, writable: true, enumerable: false, configurable: true });
}
/* ReadableStream.from (4.2.4), which takes an async iterable or an iterable. */
ReadableStream.from = function from(asyncIterable) {
  var what = "Failed to execute 'from' on 'ReadableStream'";
  needArgs(arguments, 1, what);
  return readableFromIterable(asyncIterable, what).self;
};

/* ---- the async iterator of a ReadableStream, of Web IDL, 3.7.10.2 ---- */

var streamIterators = new WeakMap();
var END_OF_ITERATION = { __proto__: null };
var StreamIteratorPrototype = ObjectCreate(AsyncIteratorPrototype);
function iteratorOf(it) { return WeakMapGet(streamIterators, it); }
StreamIteratorPrototype.next = function next() {
  var st = iteratorOf(this);
  if (st === undefined) return rejected(new TypeError("Failed to execute 'next' on 'ReadableStream AsyncIterator': Illegal invocation"));
  var nextSteps = function () {
    if (st.finished) return resolvedWith(iterResult(undefined, true));
    var nextPromise = iteratorNextResult(st);
    return transformPromise(nextPromise, function (next) {
      st.ongoing = undefined;
      if (next === END_OF_ITERATION) { st.finished = true; return iterResult(undefined, true); }
      return iterResult(next, false);
    }, function (reason) {
      st.ongoing = undefined;
      st.finished = true;
      throw reason;
    });
  };
  st.ongoing = st.ongoing ? transformPromise(st.ongoing, nextSteps, nextSteps) : nextSteps();
  return st.ongoing;
};
StreamIteratorPrototype['return'] = function (value) {
  var st = iteratorOf(this);
  if (st === undefined) return rejected(new TypeError("Failed to execute 'return' on 'ReadableStream AsyncIterator': Illegal invocation"));
  var returnSteps = function () {
    if (st.finished) return resolvedWith(iterResult(value, true));
    st.finished = true;
    return iteratorReturn(st, value);
  };
  st.ongoing = st.ongoing ? transformPromise(st.ongoing, returnSteps, returnSteps) : returnSteps();
  return transformPromise(st.ongoing, function () { return iterResult(value, true); });
};
if (SymbolToStringTag) ObjectDefineProperty(StreamIteratorPrototype, SymbolToStringTag, { __proto__: null, value: 'ReadableStream AsyncIterator', configurable: true });
function iteratorNextResult(st) {
  var reader = st.reader, d = newDeferred();
  defaultReaderRead(reader, {
    __proto__: null,
    chunk: function (chunk) { resolveDeferred(d, chunk); },
    close: function () { defaultReaderRelease(reader); resolveDeferred(d, END_OF_ITERATION); },
    error: function (e) { defaultReaderRelease(reader); rejectDeferred(d, e); },
  });
  return d.promise;
}
function iteratorReturn(st, value) {
  var reader = st.reader;
  if (!st.preventCancel) {
    var result = readerGenericCancel(reader, value);
    defaultReaderRelease(reader);
    return result;
  }
  defaultReaderRelease(reader);
  return resolvedWith(undefined);
}

/* ---- readers, of 4.3 and 4.4 ---- */

function streamReaderOf(self, byob) {
  var rd = WeakMapGet(streamReaders, self);
  if (rd === undefined || rd.byob !== byob) throw new TypeError('Illegal invocation');
  return rd;
}
function newReaderRecord(self, byob) {
  var rd = { __proto__: null, self: self, stream: undefined, closed: null, requests: [], byob: byob };
  WeakMapSet(streamReaders, self, rd);
  return rd;
}
function acquireReader(r, byob, what) {
  var rd = newReaderRecord(ObjectCreate(byob ? ReadableStreamBYOBReader.prototype : ReadableStreamDefaultReader.prototype), byob);
  setUpReader(rd, r, what);
  return rd;
}
function setUpReader(rd, r, what) {
  if (isLocked(r)) {
    throw new TypeError(what + ': ' + (rd.byob ? 'ReadableStreamBYOBReader' : 'ReadableStreamDefaultReader') +
      ' constructor can only accept readable streams that are not yet locked to a reader');
  }
  if (rd.byob && !r.controller.isByte) throw new TypeError(what + ': Cannot use a BYOB reader with a non-byte stream');
  readerGenericInitialize(rd, r);
  rd.requests = [];
}
function readerGenericInitialize(rd, r) {
  rd.stream = r;
  r.reader = rd;
  rd.closed = newDeferred();
  if (r.state === 'closed') resolveDeferred(rd.closed, undefined);
  else if (r.state === 'errored') { rejectDeferred(rd.closed, r.storedError); markHandled(rd.closed.promise); }
}
function readerGenericCancel(rd, reason) { return readableCancel(rd.stream, reason); }
function readerGenericRelease(rd) {
  var r = rd.stream;
  var e = new TypeError("This readable stream reader has been released and cannot be used to monitor the stream's state");
  if (r.state === 'readable') rejectDeferred(rd.closed, e);
  else { rd.closed = newDeferred(); rejectDeferred(rd.closed, e); }
  markHandled(rd.closed.promise);
  if (r.controller.isByte && r.controller.pendingPullIntos.length) {
    var first = r.controller.pendingPullIntos[0];
    first.readerType = 'none';
    r.controller.pendingPullIntos = [first];
  }
  r.reader = undefined;
  rd.stream = undefined;
}
function defaultReaderRelease(rd) {
  readerGenericRelease(rd);
  errorReadRequests(rd, new TypeError('Releasing Default reader'));
}
function byobReaderRelease(rd) {
  readerGenericRelease(rd);
  errorReadRequests(rd, new TypeError('Releasing BYOB reader'));
}
function errorReadRequests(rd, e) {
  var requests = rd.requests;
  rd.requests = [];
  for (var i = 0; i < requests.length; i++) call1(requests[i].error, e);
}
function defaultReaderRead(rd, request) {
  var r = rd.stream;
  r.disturbed = true;
  if (r.state === 'closed') call0(request.close);
  else if (r.state === 'errored') call1(request.error, r.storedError);
  else pullSteps(r.controller, request);
}
function readRequestFor(d) {
  return {
    __proto__: null,
    chunk: function (chunk) { resolveDeferred(d, iterResult(chunk, false)); },
    close: function () { resolveDeferred(d, iterResult(undefined, true)); },
    error: function (e) { rejectDeferred(d, e); },
  };
}
function readerConstructor(self, ctor, name, byob, args) {
  var what = "Failed to construct '" + name + "'";
  needNew(self, ctor, name, function (s) { return WeakMapHas(streamReaders, s); });
  needArgs(args, 1, what);
  var r = WeakMapGet(readableStreams, args[0]);
  if (r === undefined) throw new TypeError(what + ": parameter 1 is not of type 'ReadableStream'.");
  setUpReader(newReaderRecord(self, byob), r, what);
}
function readerClosed(self, byob) { return streamReaderOf(self, byob).closed.promise; }
function readerCancel(self, byob, reason, name) {
  var rd = WeakMapGet(streamReaders, self);
  var what = "Failed to execute 'cancel' on '" + name + "'";
  if (rd === undefined || rd.byob !== byob) return rejected(new TypeError(what + ': Illegal invocation'));
  if (rd.stream === undefined) return rejected(new TypeError(what + ': This readable stream reader has been released and cannot be used to cancel its previous owner stream'));
  return readerGenericCancel(rd, reason);
}

function ReadableStreamDefaultReader(stream) { readerConstructor(this, ReadableStreamDefaultReader, 'ReadableStreamDefaultReader', false, arguments); }
defineInterface(ReadableStreamDefaultReader, 'ReadableStreamDefaultReader', null, 1);
def(ReadableStreamDefaultReader.prototype, 'closed', function () { return readerClosed(this, false); });
ReadableStreamDefaultReader.prototype.cancel = function (reason) { return readerCancel(this, false, reason, 'ReadableStreamDefaultReader'); };
ReadableStreamDefaultReader.prototype.read = function () {
  var what = "Failed to execute 'read' on 'ReadableStreamDefaultReader'", rd = WeakMapGet(streamReaders, this);
  if (rd === undefined || rd.byob) return rejected(new TypeError(what + ': Illegal invocation'));
  if (rd.stream === undefined) return rejected(new TypeError(what + ': This readable stream reader has been released and cannot be used to read from its previous owner stream'));
  var d = newDeferred();
  defaultReaderRead(rd, readRequestFor(d));
  return d.promise;
};
ReadableStreamDefaultReader.prototype.releaseLock = function () {
  var rd = streamReaderOf(this, false);
  if (rd.stream === undefined) return;
  defaultReaderRelease(rd);
};

function ReadableStreamBYOBReader(stream) { readerConstructor(this, ReadableStreamBYOBReader, 'ReadableStreamBYOBReader', true, arguments); }
defineInterface(ReadableStreamBYOBReader, 'ReadableStreamBYOBReader', null, 1);
def(ReadableStreamBYOBReader.prototype, 'closed', function () { return readerClosed(this, true); });
ReadableStreamBYOBReader.prototype.cancel = function (reason) { return readerCancel(this, true, reason, 'ReadableStreamBYOBReader'); };
ReadableStreamBYOBReader.prototype.read = function (view) {
  var what = "Failed to execute 'read' on 'ReadableStreamBYOBReader'", rd = WeakMapGet(streamReaders, this);
  if (rd === undefined || !rd.byob) return rejected(new TypeError(what + ': Illegal invocation'));
  if (arguments.length < 1) return rejected(new TypeError(what + ': 1 argument required, but only 0 present.'));
  if (!ArrayBufferIsView(view)) return rejected(new TypeError(what + ": parameter 1 is not of type 'ArrayBufferView'."));
  var options;
  try { options = idlDictionary(arguments[1], what, 'ReadableStreamBYOBReaderReadOptions'); } catch (e) { return rejected(e); }
  var min = options.min, parts = viewParts(view);
  if (min === undefined) min = 1;
  else {
    try { min = enforceLong(min, what, 'min'); } catch (e) { return rejected(e); }
  }
  if (parts.byteLength === 0) return rejected(new TypeError(what + ': This readable stream reader cannot be used to read as the view has byte length equal to 0'));
  if (ArrayBufferByteLength(parts.buffer) === 0) return rejected(new TypeError(what + ": This readable stream reader cannot be used to read as the view's buffer has byte length equal to 0"));
  if (isDetached(parts.buffer)) return rejected(new TypeError(what + ": This readable stream reader cannot be used to read as the view's buffer has been detached"));
  if (min === 0) return rejected(new TypeError(what + ': min must be greater than 0'));
  var count = parts.ctor === null ? parts.byteLength : TypedArrayLength(view);
  if (min > count) return rejected(new RangeError(what + ": min cannot be larger than view's length"));
  if (rd.stream === undefined) return rejected(new TypeError(what + ': This readable stream reader has been released and cannot be used to read from its previous owner stream'));
  var d = newDeferred();
  byobReaderRead(rd, view, min, {
    __proto__: null,
    chunk: function (chunk) { resolveDeferred(d, iterResult(chunk, false)); },
    close: function (chunk) { resolveDeferred(d, iterResult(chunk, true)); },
    error: function (e) { rejectDeferred(d, e); },
  });
  return d.promise;
};
ReadableStreamBYOBReader.prototype.releaseLock = function () {
  var rd = streamReaderOf(this, true);
  if (rd.stream === undefined) return;
  byobReaderRelease(rd);
};
function byobReaderRead(rd, view, min, request) {
  var r = rd.stream;
  r.disturbed = true;
  if (r.state === 'errored') call1(request.error, r.storedError);
  else byteControllerPullInto(r.controller, view, min, request);
}

/* ---- the stream's own algorithms, of 4.9 ---- */

function readableCancel(r, reason) {
  r.disturbed = true;
  if (r.state === 'closed') return resolvedWith(undefined);
  if (r.state === 'errored') return rejected(r.storedError);
  readableClose(r);
  var rd = r.reader;
  if (rd !== undefined && rd.byob) {
    var requests = rd.requests;
    rd.requests = [];
    for (var i = 0; i < requests.length; i++) call1(requests[i].close, undefined);
  }
  var sourceCancel = cancelSteps(r.controller, reason);
  return transformPromise(sourceCancel, function () { return undefined; });
}
function readableClose(r) {
  r.state = 'closed';
  var rd = r.reader;
  if (rd === undefined) return;
  resolveDeferred(rd.closed, undefined);
  if (!rd.byob) {
    var requests = rd.requests;
    rd.requests = [];
    for (var i = 0; i < requests.length; i++) call0(requests[i].close);
  }
}
function readableError(r, e) {
  r.state = 'errored';
  r.storedError = e;
  var rd = r.reader;
  if (rd === undefined) return;
  rejectDeferred(rd.closed, e);
  markHandled(rd.closed.promise);
  errorReadRequests(rd, e);
}
function fulfillReadRequest(r, chunk, done) {
  var request = ArrayShift(r.reader.requests);
  if (done) call0(request.close); else call1(request.chunk, chunk);
}
function fulfillReadIntoRequest(r, chunk, done) {
  var request = ArrayShift(r.reader.requests);
  if (done) call1(request.close, chunk); else call1(request.chunk, chunk);
}
function numReadRequests(r) { return r.reader.requests.length; }
function hasDefaultReader(r) { return r.reader !== undefined && !r.reader.byob; }
function hasByobReader(r) { return r.reader !== undefined && r.reader.byob; }

/* ---- ReadableStreamDefaultController, of 4.6 ---- */

function newDefaultController() {
  var c = { __proto__: null, self: ObjectCreate(ReadableStreamDefaultController.prototype), isByte: false, stream: undefined, queue: [],
    queueTotalSize: 0, started: false, closeRequested: false, pullAgain: false, pulling: false, sizeAlgorithm: undefined, hwm: 0,
    pullAlgorithm: undefined, cancelAlgorithm: undefined };
  WeakMapSet(streamControllers, c.self, c);
  return c;
}
function setUpDefaultController(r, c, start, pull, cancel, hwm, size) {
  c.stream = r;
  resetQueue(c);
  c.started = c.closeRequested = c.pullAgain = c.pulling = false;
  c.sizeAlgorithm = size;
  c.hwm = hwm;
  c.pullAlgorithm = pull;
  c.cancelAlgorithm = cancel;
  r.controller = c;
  var startResult = call0(start);
  uponPromise(resolvedWith(startResult), function () {
    c.started = true;
    defaultCallPullIfNeeded(c);
  }, function (e) { defaultControllerError(c, e); });
}
function defaultControllerOf(self, what) {
  var c = WeakMapGet(streamControllers, self);
  if (c === undefined || c.isByte) throw new TypeError(what ? what + ': Illegal invocation' : 'Illegal invocation');
  return c;
}
function defaultCallPullIfNeeded(c) {
  if (!defaultShouldCallPull(c)) return;
  if (c.pulling) { c.pullAgain = true; return; }
  c.pulling = true;
  uponPromise(call0(c.pullAlgorithm), function () {
    c.pulling = false;
    if (c.pullAgain) { c.pullAgain = false; defaultCallPullIfNeeded(c); }
  }, function (e) { defaultControllerError(c, e); });
}
function defaultShouldCallPull(c) {
  var r = c.stream;
  if (!defaultCanCloseOrEnqueue(c) || !c.started) return false;
  if (isLocked(r) && numReadRequests(r) > 0) return true;
  return defaultDesiredSize(c) > 0;
}
function defaultClearAlgorithms(c) { c.pullAlgorithm = undefined; c.cancelAlgorithm = undefined; c.sizeAlgorithm = undefined; }
function defaultControllerClose(c) {
  if (!defaultCanCloseOrEnqueue(c)) return;
  c.closeRequested = true;
  if (!c.queue.length) { defaultClearAlgorithms(c); readableClose(c.stream); }
}
function defaultControllerEnqueue(c, chunk) {
  if (!defaultCanCloseOrEnqueue(c)) return;
  var r = c.stream;
  if (isLocked(r) && numReadRequests(r) > 0) fulfillReadRequest(r, chunk, false);
  else {
    var size;
    try { size = call1(c.sizeAlgorithm, chunk); } catch (e) { defaultControllerError(c, e); throw e; }
    try { enqueueValueWithSize(c, chunk, size); } catch (e) { defaultControllerError(c, e); throw e; }
  }
  defaultCallPullIfNeeded(c);
}
function defaultControllerError(c, e) {
  if (c.stream.state !== 'readable') return;
  resetQueue(c);
  defaultClearAlgorithms(c);
  readableError(c.stream, e);
}
function defaultDesiredSize(c) {
  var state = c.stream.state;
  if (state === 'errored') return null;
  if (state === 'closed') return 0;
  return c.hwm - c.queueTotalSize;
}
function defaultHasBackpressure(c) { return !defaultShouldCallPull(c); }
function defaultCanCloseOrEnqueue(c) { return !c.closeRequested && c.stream.state === 'readable'; }
/* The checks of enqueue and close, with Chromium's words for each. */
function defaultCheckEnqueue(c, what) {
  if (c.closeRequested || c.stream.state === 'closed') throw new TypeError(what + ': Cannot enqueue a chunk into a readable stream that is closed or has been requested to be closed');
  if (c.stream.state === 'errored') throw new TypeError(what + ': Cannot enqueue a chunk into an errored readable stream');
}
function pullSteps(c, request) {
  if (c.isByte) { bytePullSteps(c, request); return; }
  var r = c.stream;
  if (c.queue.length) {
    var chunk = dequeueValue(c);
    if (c.closeRequested && !c.queue.length) { defaultClearAlgorithms(c); readableClose(r); }
    else defaultCallPullIfNeeded(c);
    call1(request.chunk, chunk);
  } else {
    ArrayPush(r.reader.requests, request);
    defaultCallPullIfNeeded(c);
  }
}
function cancelSteps(c, reason) {
  if (c.isByte) {
    byteClearPendingPullIntos(c);
    resetQueue(c);
    var byteResult = call1(c.cancelAlgorithm, reason);
    byteClearAlgorithms(c);
    return byteResult;
  }
  resetQueue(c);
  var result = call1(c.cancelAlgorithm, reason);
  defaultClearAlgorithms(c);
  return result;
}

function ReadableStreamDefaultController() { illegal('ReadableStreamDefaultController'); }
defineInterface(ReadableStreamDefaultController, 'ReadableStreamDefaultController', null, 0);
def(ReadableStreamDefaultController.prototype, 'desiredSize', function () { return defaultDesiredSize(defaultControllerOf(this)); });
ReadableStreamDefaultController.prototype.close = function () {
  var what = "Failed to execute 'close' on 'ReadableStreamDefaultController'", c = defaultControllerOf(this, what);
  if (c.stream.state === 'errored') throw new TypeError(what + ': Cannot close an errored readable stream');
  if (!defaultCanCloseOrEnqueue(c)) throw new TypeError(what + ': Cannot close a readable stream that has already been requested to be closed');
  defaultControllerClose(c);
};
ReadableStreamDefaultController.prototype.enqueue = function (chunk) {
  var what = "Failed to execute 'enqueue' on 'ReadableStreamDefaultController'", c = defaultControllerOf(this, what);
  defaultCheckEnqueue(c, what);
  defaultControllerEnqueue(c, chunk);
};
ReadableStreamDefaultController.prototype.error = function (e) { defaultControllerError(defaultControllerOf(this), e); };

/* ---- ReadableByteStreamController and ReadableStreamBYOBRequest, of 4.7 and 4.8 ---- */

function newByteController() {
  var c = { __proto__: null, self: ObjectCreate(ReadableByteStreamController.prototype), isByte: true, stream: undefined, queue: [],
    queueTotalSize: 0, started: false, closeRequested: false, pullAgain: false, pulling: false, hwm: 0, pullAlgorithm: undefined,
    cancelAlgorithm: undefined, autoAllocateChunkSize: undefined, byobRequest: null, pendingPullIntos: [] };
  WeakMapSet(streamControllers, c.self, c);
  return c;
}
function byteControllerOf(self, what) {
  var c = WeakMapGet(streamControllers, self);
  if (c === undefined || !c.isByte) throw new TypeError(what ? what + ': Illegal invocation' : 'Illegal invocation');
  return c;
}
function setUpByteController(r, c, start, pull, cancel, hwm, auto) {
  c.stream = r;
  c.pullAgain = c.pulling = false;
  c.byobRequest = null;
  resetQueue(c);
  c.closeRequested = c.started = false;
  c.hwm = hwm;
  c.pullAlgorithm = pull;
  c.cancelAlgorithm = cancel;
  c.autoAllocateChunkSize = auto;
  c.pendingPullIntos = [];
  r.controller = c;
  var startResult = call0(start);
  uponPromise(resolvedWith(startResult), function () {
    c.started = true;
    byteCallPullIfNeeded(c);
  }, function (e) { byteControllerError(c, e); });
}
function byteCallPullIfNeeded(c) {
  if (!byteShouldCallPull(c)) return;
  if (c.pulling) { c.pullAgain = true; return; }
  c.pulling = true;
  uponPromise(call0(c.pullAlgorithm), function () {
    c.pulling = false;
    if (c.pullAgain) { c.pullAgain = false; byteCallPullIfNeeded(c); }
  }, function (e) { byteControllerError(c, e); });
}
function byteShouldCallPull(c) {
  var r = c.stream;
  if (r.state !== 'readable' || c.closeRequested || !c.started) return false;
  if (hasDefaultReader(r) && numReadRequests(r) > 0) return true;
  if (hasByobReader(r) && numReadRequests(r) > 0) return true;
  return byteDesiredSize(c) > 0;
}
function byteDesiredSize(c) {
  var state = c.stream.state;
  if (state === 'errored') return null;
  if (state === 'closed') return 0;
  return c.hwm - c.queueTotalSize;
}
function byteClearAlgorithms(c) { c.pullAlgorithm = undefined; c.cancelAlgorithm = undefined; }
function byteClearPendingPullIntos(c) { byteInvalidateRequest(c); c.pendingPullIntos = []; }
function byteInvalidateRequest(c) {
  if (c.byobRequest === null) return;
  var req = WeakMapGet(byobRequests, c.byobRequest);
  req.controller = undefined;
  req.view = null;
  c.byobRequest = null;
}
function byteControllerClose(c, what) {
  var r = c.stream;
  if (c.closeRequested || r.state !== 'readable') return;
  if (c.queueTotalSize > 0) { c.closeRequested = true; return; }
  if (c.pendingPullIntos.length) {
    var first = c.pendingPullIntos[0];
    if (first.bytesFilled % first.elementSize !== 0) {
      var e = new TypeError((what ? what + ': ' : '') + 'Insufficient bytes to fill elements in the given buffer');
      byteControllerError(c, e);
      throw e;
    }
  }
  byteClearAlgorithms(c);
  readableClose(r);
}
function byteControllerError(c, e) {
  var r = c.stream;
  if (r.state !== 'readable') return;
  byteClearPendingPullIntos(c);
  resetQueue(c);
  byteClearAlgorithms(c);
  readableError(r, e);
}
function byteControllerEnqueue(c, chunk) {
  var r = c.stream;
  if (c.closeRequested || r.state !== 'readable') return;
  var parts = viewParts(chunk);
  var buffer = parts.buffer, byteOffset = parts.byteOffset, byteLength = parts.byteLength;
  var transferred = transferBuffer(buffer);
  if (c.pendingPullIntos.length) {
    var first = c.pendingPullIntos[0];
    if (isDetached(first.buffer)) throw new TypeError("The BYOB request's buffer has been detached and so cannot be filled with an enqueued chunk");
    byteInvalidateRequest(c);
    first.buffer = transferBuffer(first.buffer);
    if (first.readerType === 'none') byteEnqueueDetachedPullIntoToQueue(c, first);
  }
  if (hasDefaultReader(r)) {
    byteProcessReadRequestsUsingQueue(c);
    if (numReadRequests(r) === 0) byteEnqueueChunkToQueue(c, transferred, byteOffset, byteLength);
    else {
      if (c.pendingPullIntos.length) byteShiftPendingPullInto(c);
      fulfillReadRequest(r, new Uint8Array(transferred, byteOffset, byteLength), false);
    }
  } else if (hasByobReader(r)) {
    byteEnqueueChunkToQueue(c, transferred, byteOffset, byteLength);
    var filled = byteProcessPullIntosUsingQueue(c);
    for (var i = 0; i < filled.length; i++) byteCommitPullInto(r, filled[i]);
  } else {
    byteEnqueueChunkToQueue(c, transferred, byteOffset, byteLength);
  }
  byteCallPullIfNeeded(c);
}
function byteEnqueueChunkToQueue(c, buffer, byteOffset, byteLength) {
  ArrayPush(c.queue, { __proto__: null, buffer: buffer, byteOffset: byteOffset, byteLength: byteLength });
  c.queueTotalSize += byteLength;
}
function byteEnqueueClonedChunkToQueue(c, buffer, byteOffset, byteLength) {
  var clone;
  try { clone = cloneBufferPart(buffer, byteOffset, byteLength); } catch (e) { byteControllerError(c, e); throw e; }
  byteEnqueueChunkToQueue(c, clone, 0, byteLength);
}
function byteEnqueueDetachedPullIntoToQueue(c, p) {
  if (p.bytesFilled > 0) byteEnqueueClonedChunkToQueue(c, p.buffer, p.byteOffset, p.bytesFilled);
  byteShiftPendingPullInto(c);
}
function byteFillPullIntoFromQueue(c, p) {
  var maxBytesToCopy = MathMin(c.queueTotalSize, p.byteLength - p.bytesFilled);
  var maxBytesFilled = p.bytesFilled + maxBytesToCopy;
  var totalBytesToCopyRemaining = maxBytesToCopy, ready = false;
  var remainderBytes = maxBytesFilled % p.elementSize;
  var maxAlignedBytes = maxBytesFilled - remainderBytes;
  if (maxAlignedBytes >= p.minimumFill) {
    totalBytesToCopyRemaining = maxAlignedBytes - p.bytesFilled;
    ready = true;
  }
  var queue = c.queue;
  while (totalBytesToCopyRemaining > 0) {
    var head = queue[0];
    var bytesToCopy = MathMin(totalBytesToCopyRemaining, head.byteLength);
    var destStart = p.byteOffset + p.bytesFilled;
    copyBytes(p.buffer, destStart, head.buffer, head.byteOffset, bytesToCopy);
    if (head.byteLength === bytesToCopy) ArrayShift(queue);
    else { head.byteOffset += bytesToCopy; head.byteLength -= bytesToCopy; }
    c.queueTotalSize -= bytesToCopy;
    byteFillHeadPullInto(c, bytesToCopy, p);
    totalBytesToCopyRemaining -= bytesToCopy;
  }
  return ready;
}
function byteFillHeadPullInto(c, size, p) { p.bytesFilled += size; }
function byteFillReadRequestFromQueue(c, request) {
  var entry = ArrayShift(c.queue);
  c.queueTotalSize -= entry.byteLength;
  byteHandleQueueDrain(c);
  call1(request.chunk, new Uint8Array(entry.buffer, entry.byteOffset, entry.byteLength));
}
function byteHandleQueueDrain(c) {
  if (c.queueTotalSize === 0 && c.closeRequested) { byteClearAlgorithms(c); readableClose(c.stream); }
  else byteCallPullIfNeeded(c);
}
function byteProcessPullIntosUsingQueue(c) {
  var filled = [];
  while (c.pendingPullIntos.length) {
    if (c.queueTotalSize === 0) break;
    var p = c.pendingPullIntos[0];
    if (byteFillPullIntoFromQueue(c, p)) {
      byteShiftPendingPullInto(c);
      ArrayPush(filled, p);
    }
  }
  return filled;
}
function byteProcessReadRequestsUsingQueue(c) {
  var rd = c.stream.reader;
  while (rd.requests.length) {
    if (c.queueTotalSize === 0) return;
    byteFillReadRequestFromQueue(c, ArrayShift(rd.requests));
  }
}
function byteConvertPullInto(p) {
  var buffer = transferBuffer(p.buffer);
  return makeView(p.viewConstructor, buffer, p.byteOffset, p.viewConstructor === null ? p.bytesFilled : p.bytesFilled / p.elementSize);
}
function byteCommitPullInto(r, p) {
  var done = false;
  if (r.state === 'closed') done = true;
  var view = byteConvertPullInto(p);
  if (p.readerType === 'default') fulfillReadRequest(r, view, done);
  else fulfillReadIntoRequest(r, view, done);
}
function byteShiftPendingPullInto(c) { return ArrayShift(c.pendingPullIntos); }
function bytePullSteps(c, request) {
  var r = c.stream;
  if (c.queueTotalSize > 0) { byteFillReadRequestFromQueue(c, request); return; }
  var auto = c.autoAllocateChunkSize;
  if (auto !== undefined) {
    var buffer;
    try { buffer = new ArrayBufferCtor(auto); } catch (e) { call1(request.error, e); return; }
    ArrayPush(c.pendingPullIntos, { __proto__: null, buffer: buffer, bufferByteLength: auto, byteOffset: 0, byteLength: auto, bytesFilled: 0,
      minimumFill: 1, elementSize: 1, viewConstructor: 'Uint8Array', readerType: 'default' });
  }
  ArrayPush(r.reader.requests, request);
  byteCallPullIfNeeded(c);
}
function byteControllerPullInto(c, view, min, request) {
  var r = c.stream, parts = viewParts(view);
  var elementSize = parts.elementSize, ctor = parts.ctor;
  var minimumFill = min * elementSize;
  var byteOffset = parts.byteOffset, byteLength = parts.byteLength, buffer;
  try { buffer = transferBuffer(parts.buffer); } catch (e) { call1(request.error, e); return; }
  var p = { __proto__: null, buffer: buffer, bufferByteLength: ArrayBufferByteLength(buffer), byteOffset: byteOffset, byteLength: byteLength,
    bytesFilled: 0, minimumFill: minimumFill, elementSize: elementSize, viewConstructor: ctor, readerType: 'byob' };
  if (c.pendingPullIntos.length) {
    ArrayPush(c.pendingPullIntos, p);
    ArrayPush(r.reader.requests, request);
    return;
  }
  if (r.state === 'closed') {
    call1(request.close, makeView(ctor, p.buffer, p.byteOffset, 0));
    return;
  }
  if (c.queueTotalSize > 0) {
    if (byteFillPullIntoFromQueue(c, p)) {
      var filledView = byteConvertPullInto(p);
      byteHandleQueueDrain(c);
      call1(request.chunk, filledView);
      return;
    }
    if (c.closeRequested) {
      var e = new TypeError('Insufficient bytes to fill elements in the given buffer');
      byteControllerError(c, e);
      call1(request.error, e);
      return;
    }
  }
  ArrayPush(c.pendingPullIntos, p);
  ArrayPush(r.reader.requests, request);
  byteCallPullIfNeeded(c);
}
function byteRespond(c, bytesWritten, what) {
  var first = c.pendingPullIntos[0], state = c.stream.state;
  if (state === 'closed') {
    if (bytesWritten !== 0) throw new TypeError(what + ': bytesWritten must be 0 when calling respond() on a closed stream');
  } else {
    if (bytesWritten === 0) throw new TypeError(what + ': bytesWritten must be greater than 0 when calling respond() on a readable stream');
    if (first.bytesFilled + bytesWritten > first.byteLength) throw new RangeError(what + ': available read buffer is too small for specified number of bytes');
  }
  first.buffer = transferBuffer(first.buffer);
  byteRespondInternal(c, bytesWritten);
}
function byteRespondInClosedState(c, first) {
  if (first.readerType === 'none') byteShiftPendingPullInto(c);
  var r = c.stream;
  if (hasByobReader(r)) {
    var filled = [];
    while (filled.length < numReadRequests(r)) ArrayPush(filled, byteShiftPendingPullInto(c));
    for (var i = 0; i < filled.length; i++) byteCommitPullInto(r, filled[i]);
  }
}
function byteRespondInReadableState(c, bytesWritten, p) {
  byteFillHeadPullInto(c, bytesWritten, p);
  if (p.readerType === 'none') {
    byteEnqueueDetachedPullIntoToQueue(c, p);
    var filled = byteProcessPullIntosUsingQueue(c);
    for (var i = 0; i < filled.length; i++) byteCommitPullInto(c.stream, filled[i]);
    return;
  }
  if (p.bytesFilled < p.minimumFill) return;
  byteShiftPendingPullInto(c);
  var remainderSize = p.bytesFilled % p.elementSize;
  if (remainderSize > 0) {
    var end = p.byteOffset + p.bytesFilled;
    byteEnqueueClonedChunkToQueue(c, p.buffer, end - remainderSize, remainderSize);
  }
  p.bytesFilled -= remainderSize;
  var more = byteProcessPullIntosUsingQueue(c);
  byteCommitPullInto(c.stream, p);
  for (var j = 0; j < more.length; j++) byteCommitPullInto(c.stream, more[j]);
}
function byteRespondInternal(c, bytesWritten) {
  var first = c.pendingPullIntos[0];
  byteInvalidateRequest(c);
  if (c.stream.state === 'closed') byteRespondInClosedState(c, first);
  else byteRespondInReadableState(c, bytesWritten, first);
  byteCallPullIfNeeded(c);
}
function byteRespondWithNewView(c, view, what) {
  var first = c.pendingPullIntos[0], state = c.stream.state, parts = viewParts(view);
  if (state === 'closed') {
    if (parts.byteLength !== 0) throw new TypeError(what + ": The view's length must be 0 when calling respondWithNewView() on a closed stream");
  } else if (parts.byteLength === 0) {
    throw new TypeError(what + ": The view's length must be greater than 0 when calling respondWithNewView() on a readable stream");
  }
  if (first.byteOffset + first.bytesFilled !== parts.byteOffset) throw new RangeError(what + ': The region specified by view does not match byobRequest');
  if (first.bufferByteLength !== ArrayBufferByteLength(parts.buffer)) throw new RangeError(what + ': The buffer of view has different capacity than byobRequest');
  if (first.bytesFilled + parts.byteLength > first.byteLength) throw new RangeError(what + ': The region specified by view is larger than byobRequest');
  var viewByteLength = parts.byteLength;
  first.buffer = transferBuffer(parts.buffer);
  byteRespondInternal(c, viewByteLength);
}
function byteGetRequest(c) {
  if (c.byobRequest === null && c.pendingPullIntos.length) {
    var first = c.pendingPullIntos[0];
    var view = new Uint8Array(first.buffer, first.byteOffset + first.bytesFilled, first.byteLength - first.bytesFilled);
    var req = ObjectCreate(ReadableStreamBYOBRequest.prototype);
    WeakMapSet(byobRequests, req, { __proto__: null, controller: c, view: view });
    c.byobRequest = req;
  }
  return c.byobRequest;
}

function ReadableByteStreamController() { illegal('ReadableByteStreamController'); }
defineInterface(ReadableByteStreamController, 'ReadableByteStreamController', null, 0);
def(ReadableByteStreamController.prototype, 'byobRequest', function () { return byteGetRequest(byteControllerOf(this)); });
def(ReadableByteStreamController.prototype, 'desiredSize', function () { return byteDesiredSize(byteControllerOf(this)); });
ReadableByteStreamController.prototype.close = function () {
  var what = "Failed to execute 'close' on 'ReadableByteStreamController'", c = byteControllerOf(this, what);
  if (c.closeRequested) throw new TypeError(what + ': Cannot close a readable stream that has already been requested to be closed');
  if (c.stream.state !== 'readable') throw new TypeError(what + ': Cannot close a readable stream that is not readable');
  byteControllerClose(c, what);
};
ReadableByteStreamController.prototype.enqueue = function (chunk) {
  var what = "Failed to execute 'enqueue' on 'ReadableByteStreamController'", c = byteControllerOf(this, what);
  needArgs(arguments, 1, what);
  if (!ArrayBufferIsView(chunk)) throw new TypeError(what + ": parameter 1 is not of type 'ArrayBufferView'.");
  var parts = viewParts(chunk);
  if (parts.byteLength === 0) throw new TypeError(what + ': chunk is empty');
  if (ArrayBufferByteLength(parts.buffer) === 0) throw new TypeError(what + ": chunk's buffer is empty");
  if (c.closeRequested) throw new TypeError(what + ': Cannot enqueue a chunk into a readable stream that is closed or has been requested to be closed');
  if (c.stream.state !== 'readable') throw new TypeError(what + ': Cannot enqueue a chunk into a readable stream that is not readable');
  if (isDetached(parts.buffer)) throw new TypeError(what + ": chunk's buffer is detached");
  byteControllerEnqueue(c, chunk);
};
ReadableByteStreamController.prototype.error = function (e) { byteControllerError(byteControllerOf(this), e); };

function byobRequestOf(self) {
  var req = WeakMapGet(byobRequests, self);
  if (req === undefined) throw new TypeError('Illegal invocation');
  return req;
}
function ReadableStreamBYOBRequest() { illegal('ReadableStreamBYOBRequest'); }
defineInterface(ReadableStreamBYOBRequest, 'ReadableStreamBYOBRequest', null, 0);
def(ReadableStreamBYOBRequest.prototype, 'view', function () { return byobRequestOf(this).view; });
ReadableStreamBYOBRequest.prototype.respond = function (bytesWritten) {
  var what = "Failed to execute 'respond' on 'ReadableStreamBYOBRequest'", req = byobRequestOf(this);
  needArgs(arguments, 1, what);
  var n = enforceLong(bytesWritten, what, 'bytesWritten');
  if (req.controller === undefined) throw new TypeError(what + ': This BYOB request has been invalidated');
  if (isDetached(TypedArrayBuffer(req.view))) throw new TypeError(what + ": The BYOB request's buffer has been detached and so cannot be used as a response");
  byteRespond(req.controller, n, what);
};
ReadableStreamBYOBRequest.prototype.respondWithNewView = function (view) {
  var what = "Failed to execute 'respondWithNewView' on 'ReadableStreamBYOBRequest'", req = byobRequestOf(this);
  needArgs(arguments, 1, what);
  if (!ArrayBufferIsView(view)) throw new TypeError(what + ": parameter 1 is not of type 'ArrayBufferView'.");
  if (req.controller === undefined) throw new TypeError(what + ': This BYOB request has been invalidated');
  if (isDetached(viewParts(view).buffer)) throw new TypeError(what + ": The given view's buffer has been detached and so cannot be used as a response");
  byteRespondWithNewView(req.controller, view, what);
};

/* ---- tee, of 4.9.1 ---- */

function defaultTee(r, cloneForBranch2) {
  var reader = acquireReader(r, false, '');
  var reading = false, readAgain = false, canceled1 = false, canceled2 = false, reason1, reason2, branch1, branch2;
  var cancelPromise = newDeferred();
  function pullAlgorithm() {
    if (reading) { readAgain = true; return resolvedWith(undefined); }
    reading = true;
    defaultReaderRead(reader, {
      __proto__: null,
      chunk: function (chunk) {
        microtask(function () {
          readAgain = false;
          var chunk1 = chunk, chunk2 = chunk;
          if (!canceled1) defaultControllerEnqueue(branch1.controller, chunk1);
          if (!canceled2) defaultControllerEnqueue(branch2.controller, chunk2);
          reading = false;
          if (readAgain) pullAlgorithm();
        });
      },
      close: function () {
        reading = false;
        if (!canceled1) defaultControllerClose(branch1.controller);
        if (!canceled2) defaultControllerClose(branch2.controller);
        if (!canceled1 || !canceled2) resolveDeferred(cancelPromise, undefined);
      },
      error: function () { reading = false; },
    });
    return resolvedWith(undefined);
  }
  function cancel1(reason) {
    canceled1 = true;
    reason1 = reason;
    if (canceled2) resolveDeferred(cancelPromise, readableCancel(r, [reason1, reason2]));
    return cancelPromise.promise;
  }
  function cancel2(reason) {
    canceled2 = true;
    reason2 = reason;
    if (canceled1) resolveDeferred(cancelPromise, readableCancel(r, [reason1, reason2]));
    return cancelPromise.promise;
  }
  function start() {}
  branch1 = createReadableStream(start, pullAlgorithm, cancel1);
  branch2 = createReadableStream(start, pullAlgorithm, cancel2);
  uponPromise(reader.closed.promise, undefined, function (e) {
    defaultControllerError(branch1.controller, e);
    defaultControllerError(branch2.controller, e);
    if (!canceled1 || !canceled2) resolveDeferred(cancelPromise, undefined);
  });
  return [branch1, branch2];
}
/* The tee of a byte stream (ReadableByteStreamTee), whose second branch takes a copy of each chunk. */
function byteTee(r) {
  var reader = acquireReader(r, false, '');
  var reading = false, readAgainForBranch1 = false, readAgainForBranch2 = false, canceled1 = false, canceled2 = false;
  var reason1, reason2, branch1, branch2;
  var cancelPromise = newDeferred();
  function forwardReaderError(thisReader) {
    uponPromise(thisReader.closed.promise, undefined, function (e) {
      if (thisReader !== reader) return;
      byteControllerError(branch1.controller, e);
      byteControllerError(branch2.controller, e);
      if (!canceled1 || !canceled2) resolveDeferred(cancelPromise, undefined);
    });
  }
  function pullWithDefaultReader() {
    if (reader.byob) {
      byobReaderRelease(reader);
      reader = acquireReader(r, false, '');
      forwardReaderError(reader);
    }
    defaultReaderRead(reader, {
      __proto__: null,
      chunk: function (chunk) {
        microtask(function () {
          readAgainForBranch1 = false;
          readAgainForBranch2 = false;
          var chunk1 = chunk, chunk2 = chunk;
          if (!canceled1 && !canceled2) {
            try { chunk2 = cloneAsUint8Array(chunk); } catch (e) {
              byteControllerError(branch1.controller, e);
              byteControllerError(branch2.controller, e);
              resolveDeferred(cancelPromise, readableCancel(r, e));
              return;
            }
          }
          if (!canceled1) byteControllerEnqueue(branch1.controller, chunk1);
          if (!canceled2) byteControllerEnqueue(branch2.controller, chunk2);
          reading = false;
          if (readAgainForBranch1) pull1Algorithm();
          else if (readAgainForBranch2) pull2Algorithm();
        });
      },
      close: function () {
        reading = false;
        if (!canceled1) byteControllerClose(branch1.controller);
        if (!canceled2) byteControllerClose(branch2.controller);
        if (branch1.controller.pendingPullIntos.length) byteRespond(branch1.controller, 0, '');
        if (branch2.controller.pendingPullIntos.length) byteRespond(branch2.controller, 0, '');
        if (!canceled1 || !canceled2) resolveDeferred(cancelPromise, undefined);
      },
      error: function () { reading = false; },
    });
  }
  function pullWithByobReader(view, forBranch2) {
    if (!reader.byob) {
      defaultReaderRelease(reader);
      reader = acquireReader(r, true, '');
      forwardReaderError(reader);
    }
    var byobBranch = forBranch2 ? branch2 : branch1, otherBranch = forBranch2 ? branch1 : branch2;
    byobReaderRead(reader, view, 1, {
      __proto__: null,
      chunk: function (chunk) {
        microtask(function () {
          readAgainForBranch1 = false;
          readAgainForBranch2 = false;
          var byobCanceled = forBranch2 ? canceled2 : canceled1, otherCanceled = forBranch2 ? canceled1 : canceled2;
          if (!otherCanceled) {
            var clonedChunk;
            try { clonedChunk = cloneAsUint8Array(chunk); } catch (e) {
              byteControllerError(byobBranch.controller, e);
              byteControllerError(otherBranch.controller, e);
              resolveDeferred(cancelPromise, readableCancel(r, e));
              return;
            }
            if (!byobCanceled) byteRespondWithNewView(byobBranch.controller, chunk, '');
            byteControllerEnqueue(otherBranch.controller, clonedChunk);
          } else if (!byobCanceled) {
            byteRespondWithNewView(byobBranch.controller, chunk, '');
          }
          reading = false;
          if (readAgainForBranch1) pull1Algorithm();
          else if (readAgainForBranch2) pull2Algorithm();
        });
      },
      close: function (chunk) {
        reading = false;
        var byobCanceled = forBranch2 ? canceled2 : canceled1, otherCanceled = forBranch2 ? canceled1 : canceled2;
        if (!byobCanceled) byteControllerClose(byobBranch.controller);
        if (!otherCanceled) byteControllerClose(otherBranch.controller);
        if (chunk !== undefined) {
          if (!byobCanceled) byteRespondWithNewView(byobBranch.controller, chunk, '');
          if (!otherCanceled && otherBranch.controller.pendingPullIntos.length) byteRespond(otherBranch.controller, 0, '');
        }
        if (!byobCanceled || !otherCanceled) resolveDeferred(cancelPromise, undefined);
      },
      error: function () { reading = false; },
    });
  }
  function pull1Algorithm() {
    if (reading) { readAgainForBranch1 = true; return resolvedWith(undefined); }
    reading = true;
    var request = byteGetRequest(branch1.controller);
    if (request === null) pullWithDefaultReader();
    else pullWithByobReader(byobRequestOf(request).view, false);
    return resolvedWith(undefined);
  }
  function pull2Algorithm() {
    if (reading) { readAgainForBranch2 = true; return resolvedWith(undefined); }
    reading = true;
    var request = byteGetRequest(branch2.controller);
    if (request === null) pullWithDefaultReader();
    else pullWithByobReader(byobRequestOf(request).view, true);
    return resolvedWith(undefined);
  }
  function cancel1(reason) {
    canceled1 = true;
    reason1 = reason;
    if (canceled2) resolveDeferred(cancelPromise, readableCancel(r, [reason1, reason2]));
    return cancelPromise.promise;
  }
  function cancel2(reason) {
    canceled2 = true;
    reason2 = reason;
    if (canceled1) resolveDeferred(cancelPromise, readableCancel(r, [reason1, reason2]));
    return cancelPromise.promise;
  }
  function start() {}
  branch1 = createByteStream(start, pull1Algorithm, cancel1);
  branch2 = createByteStream(start, pull2Algorithm, cancel2);
  forwardReaderError(reader);
  return [branch1, branch2];
}
function cloneAsUint8Array(view) {
  var parts = viewParts(view);
  return new Uint8Array(cloneBufferPart(parts.buffer, parts.byteOffset, parts.byteLength));
}

/* ---- ReadableStream.from, of 4.9.2 ---- */

function readableFromIterable(iterable, what) {
  var iteratorRecord = asyncIteratorOf(iterable, what), stream;
  function pull() {
    var nextResult;
    try { nextResult = ReflectApply(iteratorRecord.next, iteratorRecord.iterator, []); } catch (e) { return rejected(e); }
    return transformPromise(resolvedWith(nextResult), function (result) {
      if (result === null || (typeof result !== 'object' && typeof result !== 'function')) throw new TypeError('The promise returned by the iterator.next() method must fulfill with an object');
      if (result.done) defaultControllerClose(stream.controller);
      else defaultControllerEnqueue(stream.controller, result.value);
    });
  }
  function cancel(reason) {
    var iterator = iteratorRecord.iterator, returnMethod;
    try { returnMethod = iterator['return']; } catch (e) { return rejected(e); }
    if (returnMethod === undefined || returnMethod === null) return resolvedWith(undefined);
    var returnResult;
    try { returnResult = ReflectApply(returnMethod, iterator, [reason]); } catch (e) { return rejected(e); }
    return transformPromise(resolvedWith(returnResult), function (result) {
      if (result === null || (typeof result !== 'object' && typeof result !== 'function')) throw new TypeError('The promise returned by the iterator.return() method must fulfill with an object');
      return undefined;
    });
  }
  stream = createReadableStream(function () {}, pull, cancel, 0);
  return stream;
}
/* GetIterator(obj, async): the async iterator of [obj], or its sync iterator made async. */
function asyncIteratorOf(obj, what) {
  var method = obj === null || obj === undefined ? undefined : (SymbolAsyncIterator ? obj[SymbolAsyncIterator] : undefined);
  if (method === undefined || method === null) {
    var syncMethod = obj === null || obj === undefined ? undefined : obj[SymbolIterator];
    if (typeof syncMethod !== 'function') throw new TypeError(what + ': The provided value is not iterable');
    var syncIterator = ReflectApply(syncMethod, obj, []);
    if (syncIterator === null || (typeof syncIterator !== 'object' && typeof syncIterator !== 'function')) throw new TypeError(what + ': The iterator is not an object');
    var syncNext = syncIterator.next;
    // CreateAsyncFromSyncIterator: each step's value is awaited, and return goes through.
    var asyncIterator = { __proto__: null };
    asyncIterator.next = function () {
      var result = ReflectApply(syncNext, syncIterator, []);
      if (result === null || (typeof result !== 'object' && typeof result !== 'function')) return rejected(new TypeError('Iterator result ' + result + ' is not an object'));
      var done = !!result.done;
      return transformPromise(resolvedWith(result.value), function (v) { return iterResult(v, done); });
    };
    asyncIterator['return'] = function (value) {
      var ret = syncIterator['return'];
      if (ret === undefined || ret === null) return resolvedWith(iterResult(value, true));
      var result = ReflectApply(ret, syncIterator, [value]);
      return transformPromise(resolvedWith(result.value), function (v) { return iterResult(v, !!result.done); });
    };
    return { __proto__: null, iterator: asyncIterator, next: asyncIterator.next };
  }
  if (typeof method !== 'function') throw new TypeError(what + ': The provided value is not iterable');
  var iterator = ReflectApply(method, obj, []);
  if (iterator === null || (typeof iterator !== 'object' && typeof iterator !== 'function')) throw new TypeError(what + ': The iterator is not an object');
  return { __proto__: null, iterator: iterator, next: iterator.next };
}
"""

/**
 * The second part of the Streams Standard (#536): writable and transform streams, piping, the text
 * streams of the Encoding Standard and the streams of a blob.
 */
internal const val DOM_PRELUDE_STREAMS_WRITABLE: String = """/* ---- WritableStream, of 5.2 ---- */

var writableStreams = new WeakMap(), streamWriters = new WeakMap(), writableControllers = new WeakMap();
function writableOf(s, what) {
  var w = WeakMapGet(writableStreams, s);
  if (w === undefined) throw new TypeError(what ? what + ': Illegal invocation' : 'Illegal invocation');
  return w;
}
function newWritableRecord(self) {
  var w = { __proto__: null, self: self, state: 'writable', storedError: undefined, writer: undefined, controller: undefined,
    inFlightWriteRequest: undefined, closeRequest: undefined, inFlightCloseRequest: undefined, pendingAbortRequest: undefined,
    writeRequests: [], backpressure: false };
  WeakMapSet(writableStreams, self, w);
  return w;
}
function isWritableLocked(w) { return w.writer !== undefined; }
function createWritableStream(start, write, close, abort, hwm, size) {
  var w = newWritableRecord(ObjectCreate(WritableStream.prototype));
  setUpWritableController(w, newWritableController(), start, write, close, abort, hwm, size);
  return w;
}
function WritableStream() {
  var what = "Failed to construct 'WritableStream'";
  needNew(this, WritableStream, 'WritableStream', function (s) { return WeakMapHas(writableStreams, s); });
  var sink = arguments[0];
  var strategy = queuingStrategy(arguments[1], what);
  var dict = streamDictionary(sink, what);
  var abort = callbackMember(dict, 'abort', 'underlyingSink', what);
  var close = callbackMember(dict, 'close', 'underlyingSink', what);
  var start = callbackMember(dict, 'start', 'underlyingSink', what);
  var type = dict.type;
  var write = callbackMember(dict, 'write', 'underlyingSink', what);
  if (type !== undefined) throw new RangeError(what + ': Invalid type is specified');
  var size = strategySize(strategy);
  var hwm = strategyHighWaterMark(strategy, 1, what);
  var w = newWritableRecord(this);
  var c = newWritableController();
  setUpWritableController(w, c,
    start ? function () { return invokeSync(start, sink, [c.self]); } : function () { return undefined; },
    write ? function (chunk) { return invokePromise(write, sink, [chunk, c.self]); } : function () { return resolvedWith(undefined); },
    close ? function () { return invokePromise(close, sink, []); } : function () { return resolvedWith(undefined); },
    abort ? function (reason) { return invokePromise(abort, sink, [reason]); } : function () { return resolvedWith(undefined); },
    hwm, size);
}
defineInterface(WritableStream, 'WritableStream', null, 0);
def(WritableStream.prototype, 'locked', function () { return isWritableLocked(writableOf(this)); });
WritableStream.prototype.abort = function (reason) {
  var what = "Failed to execute 'abort' on 'WritableStream'", w = WeakMapGet(writableStreams, this);
  if (w === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  if (isWritableLocked(w)) return rejected(new TypeError(what + ': Cannot abort a locked stream'));
  return writableAbort(w, reason);
};
WritableStream.prototype.close = function () {
  var what = "Failed to execute 'close' on 'WritableStream'", w = WeakMapGet(writableStreams, this);
  if (w === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  if (isWritableLocked(w)) return rejected(new TypeError(what + ': Cannot close a locked stream'));
  if (closeQueuedOrInFlight(w)) return rejected(new TypeError(what + ': Cannot close a writable stream that has already been requested to be closed'));
  return writableClose(w);
};
WritableStream.prototype.getWriter = function () {
  var what = "Failed to execute 'getWriter' on 'WritableStream'", w = writableOf(this);
  return acquireWriter(w, what).self;
};

function writableAbort(w, reason) {
  if (w.state === 'closed' || w.state === 'errored') return resolvedWith(undefined);
  signalAbort(w.controller.signal, reason);
  var state = w.state;
  if (state === 'closed' || state === 'errored') return resolvedWith(undefined);
  if (w.pendingAbortRequest !== undefined) return w.pendingAbortRequest.deferred.promise;
  var wasAlreadyErroring = false;
  if (state === 'erroring') { wasAlreadyErroring = true; reason = undefined; }
  var d = newDeferred();
  w.pendingAbortRequest = { __proto__: null, deferred: d, reason: reason, wasAlreadyErroring: wasAlreadyErroring };
  if (!wasAlreadyErroring) writableStartErroring(w, reason);
  return d.promise;
}
function writableClose(w) {
  var state = w.state;
  if (state === 'closed' || state === 'errored') return rejected(new TypeError('Cannot close a ' + StringToUpperCase(state) + ' writable stream'));
  var d = newDeferred();
  w.closeRequest = d;
  var writer = w.writer;
  if (writer !== undefined && w.backpressure && state === 'writable') resolveDeferred(writer.ready, undefined);
  writableControllerClose(w.controller);
  return d.promise;
}
function writableAddWriteRequest(w) {
  var d = newDeferred();
  ArrayPush(w.writeRequests, d);
  return d.promise;
}
function writableDealWithRejection(w, error) {
  if (w.state === 'writable') { writableStartErroring(w, error); return; }
  writableFinishErroring(w);
}
function writableFinishErroring(w) {
  w.state = 'errored';
  writableErrorSteps(w.controller);
  var storedError = w.storedError, requests = w.writeRequests;
  for (var i = 0; i < requests.length; i++) rejectDeferred(requests[i], storedError);
  w.writeRequests = [];
  if (w.pendingAbortRequest === undefined) { writableRejectCloseAndClosedIfNeeded(w); return; }
  var abortRequest = w.pendingAbortRequest;
  w.pendingAbortRequest = undefined;
  if (abortRequest.wasAlreadyErroring) {
    rejectDeferred(abortRequest.deferred, storedError);
    writableRejectCloseAndClosedIfNeeded(w);
    return;
  }
  var promise = writableAbortSteps(w.controller, abortRequest.reason);
  uponPromise(promise, function () {
    resolveDeferred(abortRequest.deferred, undefined);
    writableRejectCloseAndClosedIfNeeded(w);
  }, function (reason) {
    rejectDeferred(abortRequest.deferred, reason);
    writableRejectCloseAndClosedIfNeeded(w);
  });
}
function writableFinishInFlightWrite(w) { resolveDeferred(w.inFlightWriteRequest, undefined); w.inFlightWriteRequest = undefined; }
function writableFinishInFlightWriteWithError(w, error) {
  rejectDeferred(w.inFlightWriteRequest, error);
  w.inFlightWriteRequest = undefined;
  writableDealWithRejection(w, error);
}
function writableFinishInFlightClose(w) {
  resolveDeferred(w.inFlightCloseRequest, undefined);
  w.inFlightCloseRequest = undefined;
  var state = w.state;
  if (state === 'erroring') {
    w.storedError = undefined;
    if (w.pendingAbortRequest !== undefined) {
      resolveDeferred(w.pendingAbortRequest.deferred, undefined);
      w.pendingAbortRequest = undefined;
    }
  }
  w.state = 'closed';
  var writer = w.writer;
  if (writer !== undefined) resolveDeferred(writer.closed, undefined);
}
function writableFinishInFlightCloseWithError(w, error) {
  rejectDeferred(w.inFlightCloseRequest, error);
  w.inFlightCloseRequest = undefined;
  if (w.pendingAbortRequest !== undefined) {
    rejectDeferred(w.pendingAbortRequest.deferred, error);
    w.pendingAbortRequest = undefined;
  }
  writableDealWithRejection(w, error);
}
function closeQueuedOrInFlight(w) { return w.closeRequest !== undefined || w.inFlightCloseRequest !== undefined; }
function hasOperationMarkedInFlight(w) { return w.inFlightWriteRequest !== undefined || w.inFlightCloseRequest !== undefined; }
function writableMarkCloseRequestInFlight(w) { w.inFlightCloseRequest = w.closeRequest; w.closeRequest = undefined; }
function writableMarkFirstWriteRequestInFlight(w) { w.inFlightWriteRequest = ArrayShift(w.writeRequests); }
function writableRejectCloseAndClosedIfNeeded(w) {
  if (w.closeRequest !== undefined) {
    rejectDeferred(w.closeRequest, w.storedError);
    w.closeRequest = undefined;
  }
  var writer = w.writer;
  if (writer !== undefined) {
    rejectDeferred(writer.closed, w.storedError);
    markHandled(writer.closed.promise);
  }
}
function writableStartErroring(w, reason) {
  var c = w.controller;
  w.state = 'erroring';
  w.storedError = reason;
  var writer = w.writer;
  if (writer !== undefined) writerEnsureReadyPromiseRejected(writer, reason);
  if (!hasOperationMarkedInFlight(w) && c.started) writableFinishErroring(w);
}
function writableUpdateBackpressure(w, backpressure) {
  var writer = w.writer;
  if (writer !== undefined && backpressure !== w.backpressure) {
    if (backpressure) writer.ready = newDeferred();
    else resolveDeferred(writer.ready, undefined);
  }
  w.backpressure = backpressure;
}

/* ---- WritableStreamDefaultWriter, of 5.3 ---- */

function newWriterRecord(self) {
  var wr = { __proto__: null, self: self, stream: undefined, ready: null, closed: null };
  WeakMapSet(streamWriters, self, wr);
  return wr;
}
function acquireWriter(w, what) {
  var wr = newWriterRecord(ObjectCreate(WritableStreamDefaultWriter.prototype));
  setUpWriter(wr, w, what);
  return wr;
}
function setUpWriter(wr, w, what) {
  if (isWritableLocked(w)) throw new TypeError(what + ': Cannot create writer when WritableStream is locked');
  wr.stream = w;
  w.writer = wr;
  var state = w.state;
  wr.ready = newDeferred();
  wr.closed = newDeferred();
  if (state === 'writable') {
    if (closeQueuedOrInFlight(w) || !w.backpressure) resolveDeferred(wr.ready, undefined);
  } else if (state === 'erroring') {
    rejectDeferred(wr.ready, w.storedError);
    markHandled(wr.ready.promise);
  } else if (state === 'closed') {
    resolveDeferred(wr.ready, undefined);
    resolveDeferred(wr.closed, undefined);
  } else {
    rejectDeferred(wr.ready, w.storedError);
    markHandled(wr.ready.promise);
    rejectDeferred(wr.closed, w.storedError);
    markHandled(wr.closed.promise);
  }
}
function writerOf(self, what) {
  var wr = WeakMapGet(streamWriters, self);
  if (wr === undefined) throw new TypeError(what ? what + ': Illegal invocation' : 'Illegal invocation');
  return wr;
}
function writerEnsureReadyPromiseRejected(wr, error) { settleRejected(wr, 'ready', error); }
/* Rejects the promise [key] of a writer when it is pending, or puts a rejected one in its place. */
function settleRejected(wr, key, error) {
  var d = wr[key];
  if (d.done) { d = newDeferred(); wr[key] = d; }
  rejectDeferred(d, error);
  markHandled(d.promise);
}
function writerCloseWithErrorPropagation(wr) {
  var w = wr.stream, state = w.state;
  if (closeQueuedOrInFlight(w) || state === 'closed') return resolvedWith(undefined);
  if (state === 'errored') return rejected(w.storedError);
  return writableClose(w);
}
function writerGetDesiredSize(wr) {
  var w = wr.stream, state = w.state;
  if (state === 'errored' || state === 'erroring') return null;
  if (state === 'closed') return 0;
  return writableDesiredSize(w.controller);
}
function writerRelease(wr) {
  var w = wr.stream;
  var e = new TypeError("This writable stream writer has been released and cannot be used to monitor the stream's state");
  writerEnsureReadyPromiseRejected(wr, e);
  settleRejected(wr, 'closed', e);
  w.writer = undefined;
  wr.stream = undefined;
}
function writerWrite(wr, chunk) {
  var w = wr.stream, c = w.controller;
  var chunkSize = writableGetChunkSize(c, chunk);
  if (w !== wr.stream) return rejected(new TypeError('This writable stream writer has been released and cannot be written to'));
  var state = w.state;
  if (state === 'errored') return rejected(w.storedError);
  if (closeQueuedOrInFlight(w) || state === 'closed') return rejected(new TypeError(state === 'closed' ? 'Cannot write to a closed writable stream' : 'Cannot write to a closing writable stream'));
  if (state === 'erroring') return rejected(w.storedError);
  var promise = writableAddWriteRequest(w);
  writableControllerWrite(c, chunk, chunkSize);
  return promise;
}

function WritableStreamDefaultWriter(stream) {
  var what = "Failed to construct 'WritableStreamDefaultWriter'";
  needNew(this, WritableStreamDefaultWriter, 'WritableStreamDefaultWriter', function (s) { return WeakMapHas(streamWriters, s); });
  needArgs(arguments, 1, what);
  var w = WeakMapGet(writableStreams, stream);
  if (w === undefined) throw new TypeError(what + ": parameter 1 is not of type 'WritableStream'.");
  setUpWriter(newWriterRecord(this), w, what);
}
defineInterface(WritableStreamDefaultWriter, 'WritableStreamDefaultWriter', null, 1);
def(WritableStreamDefaultWriter.prototype, 'closed', function () { return writerOf(this).closed.promise; });
def(WritableStreamDefaultWriter.prototype, 'desiredSize', function () {
  var wr = writerOf(this);
  if (wr.stream === undefined) throw new TypeError("Failed to read the 'desiredSize' property from 'WritableStreamDefaultWriter': This writable stream writer has been released and cannot be used to get the desiredSize");
  return writerGetDesiredSize(wr);
});
def(WritableStreamDefaultWriter.prototype, 'ready', function () { return writerOf(this).ready.promise; });
WritableStreamDefaultWriter.prototype.abort = function (reason) {
  var what = "Failed to execute 'abort' on 'WritableStreamDefaultWriter'", wr = WeakMapGet(streamWriters, this);
  if (wr === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  if (wr.stream === undefined) return rejected(new TypeError(what + ': This writable stream writer has been released and cannot be used to abort its previous owner stream'));
  return writableAbort(wr.stream, reason);
};
WritableStreamDefaultWriter.prototype.close = function () {
  var what = "Failed to execute 'close' on 'WritableStreamDefaultWriter'", wr = WeakMapGet(streamWriters, this);
  if (wr === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  var w = wr.stream;
  if (w === undefined) return rejected(new TypeError(what + ': This writable stream writer has been released and cannot be used to close its previous owner stream'));
  if (closeQueuedOrInFlight(w)) return rejected(new TypeError(what + ': Cannot close a writable stream that has already been requested to be closed'));
  return writableClose(w);
};
WritableStreamDefaultWriter.prototype.releaseLock = function () {
  var wr = writerOf(this);
  if (wr.stream === undefined) return;
  writerRelease(wr);
};
WritableStreamDefaultWriter.prototype.write = function (chunk) {
  var what = "Failed to execute 'write' on 'WritableStreamDefaultWriter'", wr = WeakMapGet(streamWriters, this);
  if (wr === undefined) return rejected(new TypeError(what + ': Illegal invocation'));
  if (wr.stream === undefined) return rejected(new TypeError(what + ': This writable stream writer has been released and cannot be written to'));
  return writerWrite(wr, chunk);
};

/* ---- WritableStreamDefaultController, of 5.4 ---- */

var CLOSE_SENTINEL = { __proto__: null };
function newWritableController() {
  var c = { __proto__: null, self: ObjectCreate(WritableStreamDefaultController.prototype), stream: undefined, queue: [], queueTotalSize: 0,
    signal: makeSignal(), started: false, strategySizeAlgorithm: undefined, hwm: 1, writeAlgorithm: undefined, closeAlgorithm: undefined,
    abortAlgorithm: undefined };
  WeakMapSet(writableControllers, c.self, c);
  return c;
}
function writableControllerOf(self) {
  var c = WeakMapGet(writableControllers, self);
  if (c === undefined) throw new TypeError('Illegal invocation');
  return c;
}
function setUpWritableController(w, c, start, write, close, abort, hwm, size) {
  c.stream = w;
  w.controller = c;
  resetQueue(c);
  c.started = false;
  c.strategySizeAlgorithm = size;
  c.hwm = hwm;
  c.writeAlgorithm = write;
  c.closeAlgorithm = close;
  c.abortAlgorithm = abort;
  writableUpdateBackpressure(w, writableGetBackpressure(c));
  var startResult = call0(start);
  uponPromise(resolvedWith(startResult), function () {
    c.started = true;
    writableAdvanceQueueIfNeeded(c);
  }, function (r) {
    c.started = true;
    writableDealWithRejection(w, r);
  });
}
function writableAbortSteps(c, reason) {
  var result = call1(c.abortAlgorithm, reason);
  writableClearAlgorithms(c);
  return result;
}
function writableErrorSteps(c) { resetQueue(c); }
function writableAdvanceQueueIfNeeded(c) {
  var w = c.stream;
  if (!c.started || w.inFlightWriteRequest !== undefined) return;
  if (w.state === 'erroring') { writableFinishErroring(w); return; }
  if (!c.queue.length) return;
  var value = c.queue[0].value;
  if (value === CLOSE_SENTINEL) writableProcessClose(c);
  else writableProcessWrite(c, value);
}
function writableClearAlgorithms(c) {
  c.writeAlgorithm = undefined;
  c.closeAlgorithm = undefined;
  c.abortAlgorithm = undefined;
  c.strategySizeAlgorithm = undefined;
}
function writableControllerClose(c) {
  enqueueValueWithSize(c, CLOSE_SENTINEL, 0);
  writableAdvanceQueueIfNeeded(c);
}
function writableControllerError(c, error) {
  var w = c.stream;
  writableClearAlgorithms(c);
  writableStartErroring(w, error);
}
function writableErrorIfNeeded(c, error) { if (c.stream.state === 'writable') writableControllerError(c, error); }
function writableGetBackpressure(c) { return writableDesiredSize(c) <= 0; }
function writableGetChunkSize(c, chunk) {
  if (c.strategySizeAlgorithm === undefined) return 1;
  try { return call1(c.strategySizeAlgorithm, chunk); } catch (e) { writableErrorIfNeeded(c, e); return 1; }
}
function writableDesiredSize(c) { return c.hwm - c.queueTotalSize; }
function writableProcessClose(c) {
  var w = c.stream;
  writableMarkCloseRequestInFlight(w);
  dequeueValue(c);
  var sinkClosePromise = call0(c.closeAlgorithm);
  writableClearAlgorithms(c);
  uponPromise(sinkClosePromise, function () { writableFinishInFlightClose(w); }, function (reason) { writableFinishInFlightCloseWithError(w, reason); });
}
function writableProcessWrite(c, chunk) {
  var w = c.stream;
  writableMarkFirstWriteRequestInFlight(w);
  var sinkWritePromise = call1(c.writeAlgorithm, chunk);
  uponPromise(sinkWritePromise, function () {
    writableFinishInFlightWrite(w);
    var state = w.state;
    dequeueValue(c);
    if (!closeQueuedOrInFlight(w) && state === 'writable') writableUpdateBackpressure(w, writableGetBackpressure(c));
    writableAdvanceQueueIfNeeded(c);
  }, function (reason) {
    if (w.state === 'writable') writableClearAlgorithms(c);
    writableFinishInFlightWriteWithError(w, reason);
  });
}
function writableControllerWrite(c, chunk, chunkSize) {
  try { enqueueValueWithSize(c, chunk, chunkSize); } catch (e) { writableErrorIfNeeded(c, e); return; }
  var w = c.stream;
  if (!closeQueuedOrInFlight(w) && w.state === 'writable') writableUpdateBackpressure(w, writableGetBackpressure(c));
  writableAdvanceQueueIfNeeded(c);
}

function WritableStreamDefaultController() { illegal('WritableStreamDefaultController'); }
defineInterface(WritableStreamDefaultController, 'WritableStreamDefaultController', null, 0);
def(WritableStreamDefaultController.prototype, 'signal', function () { return writableControllerOf(this).signal; });
WritableStreamDefaultController.prototype.error = function (e) {
  var c = writableControllerOf(this);
  if (c.stream.state !== 'writable') return;
  writableControllerError(c, e);
};

/* ---- TransformStream, of 6.2 ---- */

var transformStreams = new WeakMap(), transformControllers = new WeakMap();
function transformOf(s) {
  var t = WeakMapGet(transformStreams, s);
  if (t === undefined) throw new TypeError('Illegal invocation');
  return t;
}
function TransformStream() {
  var what = "Failed to construct 'TransformStream'";
  needNew(this, TransformStream, 'TransformStream', function (s) { return WeakMapHas(transformStreams, s); });
  var transformer = arguments[0];
  var writableStrategy = queuingStrategy(arguments[1], what);
  var readableStrategy = queuingStrategy(arguments[2], what);
  var dict = streamDictionary(transformer, what);
  var cancel = callbackMember(dict, 'cancel', 'transformer', what);
  var flush = callbackMember(dict, 'flush', 'transformer', what);
  var readableType = dict.readableType;
  var start = callbackMember(dict, 'start', 'transformer', what);
  var transform = callbackMember(dict, 'transform', 'transformer', what);
  var writableType = dict.writableType;
  if (readableType !== undefined) throw new RangeError(what + ': Invalid readableType was specified');
  if (writableType !== undefined) throw new RangeError(what + ': Invalid writableType was specified');
  var readableHwm = strategyHighWaterMark(readableStrategy, 0, what);
  var readableSize = strategySize(readableStrategy);
  var writableHwm = strategyHighWaterMark(writableStrategy, 1, what);
  var writableSize = strategySize(writableStrategy);
  var t = { __proto__: null, self: this, readable: undefined, writable: undefined, backpressure: undefined, backpressureChange: undefined, controller: undefined };
  WeakMapSet(transformStreams, this, t);
  var startPromise = newDeferred();
  initializeTransformStream(t, startPromise.promise, writableHwm, writableSize, readableHwm, readableSize);
  var c = newTransformController();
  setUpTransformController(t, c,
    transform ? function (chunk) { return invokePromise(transform, transformer, [chunk, c.self]); } : null,
    flush ? function () { return invokePromise(flush, transformer, [c.self]); } : function () { return resolvedWith(undefined); },
    cancel ? function (reason) { return invokePromise(cancel, transformer, [reason]); } : function () { return resolvedWith(undefined); });
  if (start) resolveDeferred(startPromise, invokeSync(start, transformer, [c.self]));
  else resolveDeferred(startPromise, undefined);
}
defineInterface(TransformStream, 'TransformStream', null, 0);
def(TransformStream.prototype, 'readable', function () { return transformOf(this).readable.self; });
def(TransformStream.prototype, 'writable', function () { return transformOf(this).writable.self; });

function initializeTransformStream(t, startPromise, writableHwm, writableSize, readableHwm, readableSize) {
  function start() { return startPromise; }
  t.writable = createWritableStream(start,
    function (chunk) { return transformSinkWrite(t, chunk); },
    function () { return transformSinkClose(t); },
    function (reason) { return transformSinkAbort(t, reason); },
    writableHwm, writableSize);
  t.readable = createReadableStream(start,
    function () { return transformSourcePull(t); },
    function (reason) { return transformSourceCancel(t, reason); },
    readableHwm, readableSize);
  t.backpressure = undefined;
  t.backpressureChange = undefined;
  transformSetBackpressure(t, true);
  t.controller = undefined;
}
function transformError(t, e) {
  defaultControllerError(t.readable.controller, e);
  transformErrorWritableAndUnblockWrite(t, e);
}
function transformErrorWritableAndUnblockWrite(t, e) {
  transformClearAlgorithms(t.controller);
  writableErrorIfNeeded(t.writable.controller, e);
  transformUnblockWrite(t);
}
function transformUnblockWrite(t) { if (t.backpressure) transformSetBackpressure(t, false); }
function transformSetBackpressure(t, backpressure) {
  if (t.backpressureChange !== undefined) resolveDeferred(t.backpressureChange, undefined);
  t.backpressureChange = newDeferred();
  t.backpressure = backpressure;
}

function newTransformController() {
  var c = { __proto__: null, self: ObjectCreate(TransformStreamDefaultController.prototype), stream: undefined, finishPromise: undefined,
    transformAlgorithm: undefined, flushAlgorithm: undefined, cancelAlgorithm: undefined };
  WeakMapSet(transformControllers, c.self, c);
  return c;
}
function transformControllerOf(self, what) {
  var c = WeakMapGet(transformControllers, self);
  if (c === undefined) throw new TypeError(what ? what + ': Illegal invocation' : 'Illegal invocation');
  return c;
}
function setUpTransformController(t, c, transform, flush, cancel) {
  c.stream = t;
  t.controller = c;
  c.transformAlgorithm = transform || function (chunk) {
    try { transformControllerEnqueue(c, chunk); } catch (e) { return rejected(e); }
    return resolvedWith(undefined);
  };
  c.flushAlgorithm = flush;
  c.cancelAlgorithm = cancel;
}
function transformClearAlgorithms(c) { c.transformAlgorithm = undefined; c.flushAlgorithm = undefined; c.cancelAlgorithm = undefined; }
function transformControllerEnqueue(c, chunk, what) {
  var t = c.stream, rc = t.readable.controller;
  if (!defaultCanCloseOrEnqueue(rc)) {
    throw new TypeError((what ? what + ': ' : '') + 'Cannot enqueue a chunk into a readable stream that is closed or has been requested to be closed');
  }
  try { defaultControllerEnqueue(rc, chunk); } catch (e) {
    transformErrorWritableAndUnblockWrite(t, e);
    throw t.readable.storedError;
  }
  var backpressure = defaultHasBackpressure(rc);
  if (backpressure !== t.backpressure) transformSetBackpressure(t, true);
}
function transformControllerError(c, e) { transformError(c.stream, e); }
function transformPerformTransform(c, chunk) {
  var p = call1(c.transformAlgorithm, chunk);
  return transformPromise(p, undefined, function (r) { transformError(c.stream, r); throw r; });
}
function transformControllerTerminate(c) {
  var t = c.stream;
  defaultControllerClose(t.readable.controller);
  transformErrorWritableAndUnblockWrite(t, new TypeError('The transform stream has been terminated'));
}
function transformSinkWrite(t, chunk) {
  var c = t.controller;
  if (t.backpressure) {
    return transformPromise(t.backpressureChange.promise, function () {
      var w = t.writable;
      if (w.state === 'erroring') throw w.storedError;
      return transformPerformTransform(c, chunk);
    });
  }
  return transformPerformTransform(c, chunk);
}
function transformSinkAbort(t, reason) {
  var c = t.controller;
  if (c.finishPromise !== undefined) return c.finishPromise.promise;
  var readable = t.readable;
  c.finishPromise = newDeferred();
  var cancelPromise = call1(c.cancelAlgorithm, reason);
  transformClearAlgorithms(c);
  uponPromise(cancelPromise, function () {
    if (readable.state === 'errored') rejectDeferred(c.finishPromise, readable.storedError);
    else { defaultControllerError(readable.controller, reason); resolveDeferred(c.finishPromise, undefined); }
  }, function (r) {
    defaultControllerError(readable.controller, r);
    rejectDeferred(c.finishPromise, r);
  });
  return c.finishPromise.promise;
}
function transformSinkClose(t) {
  var c = t.controller;
  if (c.finishPromise !== undefined) return c.finishPromise.promise;
  var readable = t.readable;
  c.finishPromise = newDeferred();
  var flushPromise = call0(c.flushAlgorithm);
  transformClearAlgorithms(c);
  uponPromise(flushPromise, function () {
    if (readable.state === 'errored') rejectDeferred(c.finishPromise, readable.storedError);
    else { defaultControllerClose(readable.controller); resolveDeferred(c.finishPromise, undefined); }
  }, function (r) {
    defaultControllerError(readable.controller, r);
    rejectDeferred(c.finishPromise, r);
  });
  return c.finishPromise.promise;
}
function transformSourcePull(t) {
  transformSetBackpressure(t, false);
  return t.backpressureChange.promise;
}
function transformSourceCancel(t, reason) {
  var c = t.controller;
  if (c.finishPromise !== undefined) return c.finishPromise.promise;
  var writable = t.writable;
  c.finishPromise = newDeferred();
  var cancelPromise = call1(c.cancelAlgorithm, reason);
  transformClearAlgorithms(c);
  uponPromise(cancelPromise, function () {
    if (writable.state === 'errored') rejectDeferred(c.finishPromise, writable.storedError);
    else {
      writableErrorIfNeeded(writable.controller, reason);
      transformUnblockWrite(t);
      resolveDeferred(c.finishPromise, undefined);
    }
  }, function (r) {
    writableErrorIfNeeded(writable.controller, r);
    transformUnblockWrite(t);
    rejectDeferred(c.finishPromise, r);
  });
  return c.finishPromise.promise;
}

function TransformStreamDefaultController() { illegal('TransformStreamDefaultController'); }
defineInterface(TransformStreamDefaultController, 'TransformStreamDefaultController', null, 0);
def(TransformStreamDefaultController.prototype, 'desiredSize', function () {
  return defaultDesiredSize(transformControllerOf(this).stream.readable.controller);
});
TransformStreamDefaultController.prototype.enqueue = function (chunk) {
  var what = "Failed to execute 'enqueue' on 'TransformStreamDefaultController'";
  transformControllerEnqueue(transformControllerOf(this, what), chunk, what);
};
TransformStreamDefaultController.prototype.error = function (reason) { transformControllerError(transformControllerOf(this), reason); };
TransformStreamDefaultController.prototype.terminate = function () { transformControllerTerminate(transformControllerOf(this)); };

/* ---- piping, of 4.9.1 (ReadableStreamPipeTo) ---- */

function pipeTo(source, dest, preventClose, preventAbort, preventCancel, signal) {
  var reader = acquireReader(source, false, '');
  var writer = acquireWriter(dest, '');
  source.disturbed = true;
  var shuttingDown = false, currentWrite = resolvedWith(undefined), abortEntry = null;
  var done = newDeferred();
  if (signal !== undefined) {
    var abortAlgorithm = function () {
      var error = signalState(signal).reason, actions = [];
      if (!preventAbort) ArrayPush(actions, function () { return dest.state === 'writable' ? writableAbort(dest, error) : resolvedWith(undefined); });
      if (!preventCancel) ArrayPush(actions, function () { return source.state === 'readable' ? readableCancel(source, error) : resolvedWith(undefined); });
      shutdownWithAction(function () { return waitForAll(actions); }, true, error);
    };
    if (signalState(signal).aborted) { abortAlgorithm(); return done.promise; }
    abortEntry = addAbortAlgorithm(signal, abortAlgorithm);
  }
  function pipeLoop() {
    var loop = newDeferred();
    function next(finished) {
      if (finished) resolveDeferred(loop, undefined);
      else PromiseThen(pipeStep(), next, function (e) { rejectDeferred(loop, e); });
    }
    next(false);
    return loop.promise;
  }
  function pipeStep() {
    if (shuttingDown) return resolvedWith(true);
    return PromiseThen(writer.ready.promise, function () {
      var read = newDeferred();
      defaultReaderRead(reader, {
        __proto__: null,
        chunk: function (chunk) {
          currentWrite = transformPromise(writerWrite(writer, chunk), undefined, streamNoop);
          resolveDeferred(read, false);
        },
        close: function () { resolveDeferred(read, true); },
        error: function (e) { rejectDeferred(read, e); },
      });
      return read.promise;
    });
  }
  function isOrBecomesErrored(stream, promise, action) {
    if (stream.state === 'errored') action(stream.storedError);
    else uponPromise(promise, undefined, action);
  }
  function isOrBecomesClosed(stream, promise, action) {
    if (stream.state === 'closed') action();
    else uponPromise(promise, action);
  }
  // Errors must be propagated forward.
  isOrBecomesErrored(source, reader.closed.promise, function (storedError) {
    if (!preventAbort) shutdownWithAction(function () { return writableAbort(dest, storedError); }, true, storedError);
    else shutdown(true, storedError);
  });
  // Errors must be propagated backward.
  isOrBecomesErrored(dest, writer.closed.promise, function (storedError) {
    if (!preventCancel) shutdownWithAction(function () { return readableCancel(source, storedError); }, true, storedError);
    else shutdown(true, storedError);
  });
  // Closing must be propagated forward.
  isOrBecomesClosed(source, reader.closed.promise, function () {
    if (!preventClose) shutdownWithAction(function () { return writerCloseWithErrorPropagation(writer); }, false, undefined);
    else shutdown(false, undefined);
  });
  // Closing must be propagated backward.
  if (closeQueuedOrInFlight(dest) || dest.state === 'closed') {
    var destClosed = new TypeError('the destination writable stream closed before all data could be piped to it');
    if (!preventCancel) shutdownWithAction(function () { return readableCancel(source, destClosed); }, true, destClosed);
    else shutdown(true, destClosed);
  }
  markHandled(pipeLoop());
  function waitForWritesToFinish() {
    var oldCurrentWrite = currentWrite;
    return PromiseThen(currentWrite, function () { return oldCurrentWrite !== currentWrite ? waitForWritesToFinish() : undefined; });
  }
  function shutdownWithAction(action, originalIsError, originalError) {
    if (shuttingDown) return;
    shuttingDown = true;
    function doTheRest() {
      uponPromise(call0(action), function () { finalize(originalIsError, originalError); }, function (newError) { finalize(true, newError); });
    }
    if (dest.state === 'writable' && !closeQueuedOrInFlight(dest)) uponPromise(waitForWritesToFinish(), doTheRest);
    else doTheRest();
  }
  function shutdown(isError, error) {
    if (shuttingDown) return;
    shuttingDown = true;
    if (dest.state === 'writable' && !closeQueuedOrInFlight(dest)) uponPromise(waitForWritesToFinish(), function () { finalize(isError, error); });
    else finalize(isError, error);
  }
  function finalize(isError, error) {
    writerRelease(writer);
    defaultReaderRelease(reader);
    if (signal !== undefined && abortEntry !== null) removeAbortAlgorithm(signal, abortEntry);
    if (isError) rejectDeferred(done, error); else resolveDeferred(done, undefined);
  }
  return done.promise;
}
/* What Promise.all does with the promises that [actions] answer, called in order. */
function waitForAll(actions) {
  var promises = [];
  for (var i = 0; i < actions.length; i++) ArrayPush(promises, call0(actions[i]));
  var d = newDeferred(), remaining = promises.length;
  if (remaining === 0) { resolveDeferred(d, undefined); return d.promise; }
  for (var j = 0; j < promises.length; j++) {
    uponPromise(promises[j], function () { if (--remaining === 0) resolveDeferred(d, undefined); }, function (e) { rejectDeferred(d, e); });
  }
  return d.promise;
}

/* ---- TextEncoderStream and TextDecoderStream, of the Encoding Standard, 7 ---- */

var textStreams = new WeakMap();
function textStreamOf(s, decoder) {
  var st = WeakMapGet(textStreams, s);
  if (st === undefined || st.decoder !== decoder) throw new TypeError('Illegal invocation');
  return st;
}
function TextEncoderStream() {
  needNew(this, TextEncoderStream, 'TextEncoderStream', function (s) { return WeakMapHas(textStreams, s); });
  var st = { __proto__: null, decoder: false, pending: null, transform: null };
  st.transform = textTransform(function (chunk, c) {
    var input = usvKeepingLead(chunk, st);
    if (input !== '') transformControllerEnqueue(c, bytesOf(K.encode(input)));
  }, function (c) {
    if (st.pending !== null) { st.pending = null; transformControllerEnqueue(c, bytesOf(K.encode('�'))); }
  });
  WeakMapSet(textStreams, this, st);
}
/* A chunk as a USVString, with the lead surrogate that ended the chunk before it first, and a lead
   surrogate that ends this one held back for the next (Encoding Standard, 7.4.3). */
function usvKeepingLead(chunk, st) {
  var s = String(chunk);
  if (st.pending !== null) { s = st.pending + s; st.pending = null; }
  if (s.length) {
    var last = StringCharCodeAt(s, s.length - 1);
    if (last >= 0xD800 && last <= 0xDBFF) { st.pending = StringCharAt(s, s.length - 1); s = StringSubstring(s, 0, s.length - 1); }
  }
  return usv(s);
}
defineInterface(TextEncoderStream, 'TextEncoderStream', null, 0);
def(TextEncoderStream.prototype, 'encoding', function () { textStreamOf(this, false); return 'utf-8'; });
def(TextEncoderStream.prototype, 'readable', function () { return textStreamOf(this, false).transform.readable.self; });
def(TextEncoderStream.prototype, 'writable', function () { return textStreamOf(this, false).transform.writable.self; });

function TextDecoderStream() {
  var what = "Failed to construct 'TextDecoderStream'";
  needNew(this, TextDecoderStream, 'TextDecoderStream', function (s) { return WeakMapHas(textStreams, s); });
  var label = arguments.length > 0 && arguments[0] !== undefined ? domString(arguments[0]) : 'utf-8';
  var options = idlDictionary(arguments[1], what, 'TextDecoderOptions');
  var fatal = !!options.fatal;
  var ignoreBOM = !!options.ignoreBOM;
  var encoding = K.encoding(label);
  if (encoding == null || encoding === 'replacement') throw new RangeError(what + ": The encoding label provided ('" + label + "') is invalid.");
  var st = { __proto__: null, decoder: true, encoding: encoding, fatal: fatal, ignoreBOM: ignoreBOM, state: null, transform: null };
  function decode(bytes, flush) {
    var result = K.decode(st.encoding, st.fatal, st.ignoreBOM, st.state, bytes, flush);
    st.state = listSlice(result, 1);
    if (result[0] == null) throw new TypeError('The encoded data is not valid.');
    return result[0];
  }
  st.transform = textTransform(function (chunk, c) {
    var view = bufferView(chunk);
    if (view === null) throw new TypeError("The provided value is not of type '(ArrayBuffer or ArrayBufferView)'.");
    var text = decode(byteString(view), false);
    if (text !== '') transformControllerEnqueue(c, text);
  }, function (c) {
    var text = decode('', true);
    if (text !== '') transformControllerEnqueue(c, text);
  });
  WeakMapSet(textStreams, this, st);
}
defineInterface(TextDecoderStream, 'TextDecoderStream', null, 0);
def(TextDecoderStream.prototype, 'encoding', function () { return StringToLowerCase(textStreamOf(this, true).encoding); });
def(TextDecoderStream.prototype, 'fatal', function () { return textStreamOf(this, true).fatal; });
def(TextDecoderStream.prototype, 'ignoreBOM', function () { return textStreamOf(this, true).ignoreBOM; });
def(TextDecoderStream.prototype, 'readable', function () { return textStreamOf(this, true).transform.readable.self; });
def(TextDecoderStream.prototype, 'writable', function () { return textStreamOf(this, true).transform.writable.self; });
/* A transform stream of the reading system, whose steps [transform] and [flush] throw to error it. */
function textTransform(transform, flush) {
  var t = { __proto__: null, self: null, readable: undefined, writable: undefined, backpressure: undefined, backpressureChange: undefined, controller: undefined };
  initializeTransformStream(t, resolvedWith(undefined), 1, function () { return 1; }, 0, function () { return 1; });
  var c = newTransformController();
  setUpTransformController(t, c, function (chunk) {
    try { transform(chunk, c); } catch (e) { return rejected(e); }
    return resolvedWith(undefined);
  }, function () {
    try { flush(c); } catch (e) { return rejected(e); }
    return resolvedWith(undefined);
  }, function () { return resolvedWith(undefined); });
  return t;
}

/* ---- Blob.stream() and Blob.textStream(), of the File API, 3.3.5 ---- */

var BLOB_CHUNK = 65536;
Blob.prototype.stream = function stream() { return blobStream(blobOf(this)).self; };
/* The stream of a blob's bytes (File API, 3.3.5): a byte stream that reads a chunk of 64 KiB in a task for each pull. */
function blobStream(data) {
  var bytes = data.bytes, at = 0, total = TypedArrayLength(bytes);
  var r = createByteStream(function () {}, function () {
    var c = r.controller;
    return new Promise(function (resolve) {
      queueTask(function* () {
        if (at >= total) {
          byteControllerClose(c, '');
          if (c.pendingPullIntos.length) byteRespond(c, 0, '');
        } else {
          var end = MathMin(total, at + BLOB_CHUNK);
          var chunk = bytesCopy(bytes, at, end);
          at = end;
          byteControllerEnqueue(c, chunk);
        }
        resolve(undefined);
      });
    });
  }, function () { at = total; return resolvedWith(undefined); });
  return r;
}
/* The blob's stream piped through a UTF-8 TextDecoderStream. */
Blob.prototype.textStream = function textStream() {
  var r = blobStream(blobOf(this));
  var t = textStreamOf(new TextDecoderStream(), true).transform;
  markHandled(pipeTo(r, t.writable, false, false, false, undefined));
  return t.readable.self;
};
"""

/**
 * The part of [DOM_PRELUDE] that moves a stream through `postMessage` or `structuredClone`, as the
 * Streams Standard's transfer steps and cross-realm transforms do (#608).
 */
internal const val DOM_PRELUDE_STREAMS_TRANSFER: String = """/* ---- transferring a stream (#608) ----
   A moved stream leaves a stream behind it that pipes into a hidden message port. The new stream
   reads from or writes to the other end of that port, as the standard's cross-realm transforms do. */

/* The interface name of [v] when it is a stream, else null. */
function streamKind(v) {
  if (v === null || typeof v !== 'object') return null;
  if (WeakMapHas(readableStreams, v)) return 'ReadableStream';
  if (WeakMapHas(writableStreams, v)) return 'WritableStream';
  if (WeakMapHas(transformStreams, v)) return 'TransformStream';
  return null;
}
function streamTransferLocked(v) {
  var kind = streamKind(v);
  if (kind === 'ReadableStream') return isLocked(streamOf(v));
  if (kind === 'WritableStream') return isWritableLocked(writableOf(v));
  var t = transformOf(v);
  return isLocked(t.readable) || isWritableLocked(t.writable);
}
/* The object that a moved [v] becomes, which moveStream makes a stream once the clone succeeded. */
function streamShell(v) {
  var kind = streamKind(v);
  return ObjectCreate(kind === 'ReadableStream' ? ReadableStream.prototype : kind === 'WritableStream' ? WritableStream.prototype : TransformStream.prototype);
}
function moveStream(v, shell) {
  var kind = streamKind(v);
  if (kind === 'ReadableStream') {
    setUpCrossRealmReadable(newReadableRecord(shell), transferReadable(streamOf(v)));
  } else if (kind === 'WritableStream') {
    setUpCrossRealmWritable(newWritableRecord(shell), transferWritable(writableOf(v)));
  } else {
    var t = transformOf(v);
    var rp = transferReadable(t.readable), wp = transferWritable(t.writable);
    var r = newReadableRecord(ObjectCreate(ReadableStream.prototype)), w = newWritableRecord(ObjectCreate(WritableStream.prototype));
    setUpCrossRealmReadable(r, rp);
    setUpCrossRealmWritable(w, wp);
    WeakMapSet(transformStreams, shell, { __proto__: null, self: shell, readable: r, writable: w, backpressure: undefined, backpressureChange: undefined, controller: undefined });
  }
}
function portPair() {
  var a = makePort(), b = makePort();
  portOf(a).other = b;
  portOf(b).other = a;
  return [a, b];
}
/* The transfer steps of a readable stream: it pipes into a writable stream over one port, and
   answers the other port, which the new stream reads from. */
function transferReadable(r) {
  var ports = portPair(), w = newWritableRecord(ObjectCreate(WritableStream.prototype));
  setUpCrossRealmWritable(w, ports[0]);
  markHandled(pipeTo(r, w, false, false, false, undefined));
  return ports[1];
}
function transferWritable(w) {
  var ports = portPair(), r = newReadableRecord(ObjectCreate(ReadableStream.prototype));
  setUpCrossRealmReadable(r, ports[0]);
  markHandled(pipeTo(r, w, false, false, false, undefined));
  return ports[1];
}
/* PackAndPostMessage: sends {type, value} to the partner of [port]; throws what the clone throws. */
function packAndPost(port, type, value) {
  var message = ObjectCreate(null);
  putData(message, 'type', type);
  putData(message, 'value', value);
  postToPort(port, cloneWithTransfer(message, [], null, port));
}
/* PackAndPostMessageHandlingError: answers a record of the error a failed send sent on, or null. */
function packAndPostHandlingError(port, type, value) {
  try {
    packAndPost(port, type, value);
  } catch (e) {
    crossRealmSendError(port, e);
    return { __proto__: null, error: e };
  }
  return null;
}
function crossRealmSendError(port, error) {
  try { packAndPost(port, 'error', error); } catch (e) {}
}
/* SetUpCrossRealmTransformReadable: [r] reads what the stream at the other end of [port] sends. */
function setUpCrossRealmReadable(r, port) {
  var c = newDefaultController();
  portOf(port).handler = function (data) {
    var type = data.type, value = data.value;
    if (type === 'chunk') defaultControllerEnqueue(c, value);
    else if (type === 'close') { defaultControllerClose(c); closePort(port); }
    else if (type === 'error') { defaultControllerError(c, value); closePort(port); }
  };
  startPort(port);
  setUpDefaultController(r, c, streamNoop, function () {
    packAndPost(port, 'pull', undefined);
    return resolvedWith(undefined);
  }, function (reason) {
    var failed = packAndPostHandlingError(port, 'error', reason);
    closePort(port);
    return failed === null ? resolvedWith(undefined) : rejected(failed.error);
  }, 0, function () { return 1; });
}
/* SetUpCrossRealmTransformWritable: [w] sends each chunk to the stream at the other end of
   [port], one at a time, once that stream asked for one. */
function setUpCrossRealmWritable(w, port) {
  var c = newWritableController(), backpressure = newDeferred();
  function release() {
    if (backpressure === undefined) return;
    resolveDeferred(backpressure, undefined);
    backpressure = undefined;
  }
  portOf(port).handler = function (data) {
    var type = data.type, value = data.value;
    if (type === 'pull') release();
    else if (type === 'error') { writableErrorIfNeeded(c, value); release(); }
  };
  startPort(port);
  setUpWritableController(w, c, streamNoop, function (chunk) {
    if (backpressure === undefined) { backpressure = newDeferred(); resolveDeferred(backpressure, undefined); }
    return transformPromise(backpressure.promise, function () {
      backpressure = newDeferred();
      var failed = packAndPostHandlingError(port, 'chunk', chunk);
      if (failed !== null) {
        closePort(port);
        return rejected(failed.error);
      }
      return resolvedWith(undefined);
    });
  }, function () {
    packAndPost(port, 'close', undefined);
    closePort(port);
    return resolvedWith(undefined);
  }, function (reason) {
    var failed = packAndPostHandlingError(port, 'error', reason);
    closePort(port);
    return failed === null ? resolvedWith(undefined) : rejected(failed.error);
  }, 1, function () { return 1; });
}
"""
