package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] for `AbortController` and `AbortSignal`, of the DOM Standard, 3.1 and 3.2 (#607).
 */
internal const val DOM_PRELUDE_ABORT: String = """/* ---- AbortController and AbortSignal, of the DOM Standard, 3.1 and 3.2 (#607) ----
   A signal's state lives in a weak map: whether it is aborted, its reason, the algorithms an
   abort runs, and for a signal that AbortSignal.any made, the signals it follows. */

var abortSignals = new WeakMap();
function signalState(s) {
  var st = WeakMapGet(abortSignals, s);
  if (st === undefined) throw new TypeError('Illegal invocation');
  return st;
}
function isSignal(v) { return v !== null && typeof v === 'object' && WeakMapHas(abortSignals, v); }
function makeSignal() {
  var s = ObjectCreate(AbortSignal.prototype);
  WeakMapSet(abortSignals, s, { __proto__: null, aborted: false, reason: undefined, algorithms: [], sources: [], dependents: [], dependent: false });
  return s;
}
function abortReason(reason) {
  return reason === undefined ? new DOMException('signal is aborted without reason', 'AbortError') : reason;
}
/* Adds [algorithm] to what an abort of [signal] runs, unless it is aborted already; answers the
   entry, which removeAbortAlgorithm takes back. */
function addAbortAlgorithm(signal, algorithm) {
  var st = signalState(signal);
  if (st.aborted) return null;
  var entry = { __proto__: null, run: algorithm };
  ArrayPush(st.algorithms, entry);
  return entry;
}
function removeAbortAlgorithm(signal, entry) {
  var list = signalState(signal).algorithms, at = ArrayIndexOf(list, entry);
  if (at >= 0) listRemoveAt(list, at);
}
/* Signal abort (DOM, 3.2): the signal and each signal that follows it take the reason first,
   and then each runs its algorithms and fires abort, a callback at a time. */
function* signalAbortSteps(signal, reason) {
  var st = signalState(signal);
  if (st.aborted) return;
  st.aborted = true;
  st.reason = abortReason(reason);
  var follow = [];
  for (var i = 0; i < st.dependents.length; i++) {
    var d = signalState(st.dependents[i]);
    if (d.aborted) continue;
    d.aborted = true;
    d.reason = st.reason;
    ArrayPush(follow, st.dependents[i]);
  }
  for (var g = abortStepsOf(signal); !GeneratorNext(g).done;) yield;
  for (var j = 0; j < follow.length; j++) for (var h = abortStepsOf(follow[j]); !GeneratorNext(h).done;) yield;
}
function* abortStepsOf(signal) {
  var st = signalState(signal), algorithms = st.algorithms;
  st.algorithms = [];
  for (var i = 0; i < algorithms.length; i++) {
    var run = algorithms[i].run;
    run();
  }
  var e = new Event('abort');
  e.isTrusted = true;
  for (var g = dispatchSteps(signal, e); !GeneratorNext(g).done;) yield;
}
function signalAbort(signal, reason) { drain(signalAbortSteps(signal, reason)); }

function AbortSignal() { illegal('AbortSignal'); }
AbortSignal.prototype = ObjectCreate(EventTarget.prototype);
defineInterface(AbortSignal, 'AbortSignal', EventTarget, 0);
def(AbortSignal.prototype, 'aborted', function () { return signalState(this).aborted; });
def(AbortSignal.prototype, 'reason', function () { return signalState(this).reason; });
AbortSignal.prototype.throwIfAborted = function () {
  var st = signalState(this);
  if (st.aborted) throw st.reason;
};
defineHandlers(AbortSignal.prototype, ['abort']);
AbortSignal.abort = function abort() {
  var s = makeSignal(), st = signalState(s);
  st.aborted = true;
  st.reason = abortReason(arguments[0]);
  return s;
};
AbortSignal.timeout = function timeout(milliseconds) {
  var what = "Failed to execute 'timeout' on 'AbortSignal'";
  needArgs(arguments, 1, what);
  var ms = Number(milliseconds);
  if (ms !== ms || ms === 1 / 0 || ms === -1 / 0) throw new TypeError(what + ': Value is not a finite number.');
  ms = ms < 0 ? MathCeil(ms) : MathFloor(ms);
  if (ms < 0 || ms > 9007199254740991) throw new TypeError(what + ": Value is outside the 'unsigned long long' value range.");
  var s = makeSignal();
  scheduleSteps(function () { return signalAbortSteps(s, new DOMException('signal timed out', 'TimeoutError')); }, ms);
  return s;
};
AbortSignal.any = function any(signals) {
  var what = "Failed to execute 'any' on 'AbortSignal'";
  needArgs(arguments, 1, what);
  var list = idlSequence(signals, function (v) {
    if (!isSignal(v)) throw new TypeError(what + ": Failed to convert value to 'AbortSignal'.");
    return v;
  }, what);
  return dependentSignal(list);
};
/* A signal that follows each of [list], or the sources of one that follows others itself (DOM, 3.2.1). */
function dependentSignal(list) {
  var result = makeSignal(), rs = signalState(result);
  for (var i = 0; i < list.length; i++) {
    var st = signalState(list[i]);
    if (st.aborted) { rs.aborted = true; rs.reason = st.reason; return result; }
  }
  rs.dependent = true;
  for (var j = 0; j < list.length; j++) {
    var source = signalState(list[j]);
    if (!source.dependent) follow(result, list[j]);
    else for (var k = 0; k < source.sources.length; k++) follow(result, source.sources[k]);
  }
  return result;
}
function follow(dependent, source) {
  var d = signalState(dependent), s = signalState(source);
  if (ArrayIndexOf(d.sources, source) >= 0) return;
  ArrayPush(d.sources, source);
  ArrayPush(s.dependents, dependent);
}

var abortControllers = new WeakMap();
function AbortController() {
  needNew(this, AbortController, 'AbortController', function (c) { return WeakMapHas(abortControllers, c); });
  WeakMapSet(abortControllers, this, makeSignal());
}
defineInterface(AbortController, 'AbortController', null, 0);
def(AbortController.prototype, 'signal', function () {
  var s = WeakMapGet(abortControllers, this);
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
});
AbortController.prototype.abort = function () {
  var s = WeakMapGet(abortControllers, this);
  if (s === undefined) throw new TypeError('Illegal invocation');
  signalAbort(s, arguments[0]);
};
"""
