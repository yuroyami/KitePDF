/* Just enough of testharness.js for the tests of this folder, and the same rules: test runs its
   function at once, async_test waits for done(), the promise tests run one after the other, and
   a step that throws fails its test. A test logs its name when it completes, with its error when
   it fails, and once the page has loaded and every test has completed, the harness logs the
   count; a test still running ten seconds after the page loaded times out. Not a file of
   web-platform-tests. */
var harness = { passed: 0, failed: 0, tests: [], loaded: false, finished: false, promiseTests: null, timer: 0 };
var STARTED = 1, HAS_RESULT = 2, CLEANING = 3, COMPLETE = 4;
function describe(e) { return e && e.message !== undefined ? e.name + ': ' + e.message : String(e); }
function Test(name) {
  this.name = name === undefined ? 'Untitled' : String(name);
  this.phase = 0;
  this.failure = null;
  this.cleanups = [];
  this.whenDone = [];
  harness.tests.push(this);
}
Test.prototype.step = function (fn, self) {
  if (this.phase > STARTED) return undefined;
  this.phase = STARTED;
  try {
    return fn.apply(arguments.length === 1 ? this : self, Array.prototype.slice.call(arguments, 2));
  } catch (e) {
    if (this.phase >= HAS_RESULT) return undefined;
    this.failure = describe(e);
    this.phase = HAS_RESULT;
    this.done();
    return undefined;
  }
};
Test.prototype.step_func = function (fn, self) {
  var t = this, bound = arguments.length === 1 ? t : self;
  return function () { return t.step.apply(t, [fn, bound].concat(Array.prototype.slice.call(arguments))); };
};
Test.prototype.step_func_done = function (fn, self) {
  var t = this, bound = arguments.length === 1 ? t : self;
  return function () {
    if (fn) t.step.apply(t, [fn, bound].concat(Array.prototype.slice.call(arguments)));
    t.done();
  };
};
Test.prototype.unreached_func = function (description) {
  return this.step_func(function () { assert_unreached(description); });
};
Test.prototype.step_timeout = function (fn, timeout) {
  var t = this, args = Array.prototype.slice.call(arguments, 2);
  return setTimeout(this.step_func(function () { return fn.apply(t, args); }), timeout);
};
Test.prototype.add_cleanup = function (fn) { this.cleanups.push(fn); };
Test.prototype.done = function () {
  if (this.phase >= CLEANING) return;
  this.phase = CLEANING;
  for (var i = 0; i < this.cleanups.length; i++) {
    try { this.cleanups[i](); } catch (e) { if (this.failure === null) this.failure = 'a cleanup threw ' + describe(e); }
  }
  this.phase = COMPLETE;
  if (this.failure === null) harnessPass(this.name); else harnessFail(this.name, this.failure);
  var callbacks = this.whenDone;
  this.whenDone = [];
  for (var j = 0; j < callbacks.length; j++) callbacks[j]();
  harnessCheck();
};
function harnessPass(name) {
  harness.passed++;
  console.log('PASS ' + name);
}
function harnessFail(name, failure) {
  harness.failed++;
  console.log('FAIL ' + name + ' :: ' + failure);
}
/* Logs the count once the page has loaded and every test has completed. */
function harnessCheck() {
  if (!harness.loaded || harness.finished) return;
  for (var i = 0; i < harness.tests.length; i++) if (harness.tests[i].phase !== COMPLETE) return;
  harness.finished = true;
  clearTimeout(harness.timer);
  console.log('DONE ' + harness.passed + ' ' + harness.failed);
}
addEventListener('load', function () {
  harness.loaded = true;
  harness.timer = setTimeout(function () {
    for (var i = 0; i < harness.tests.length; i++) {
      var t = harness.tests[i];
      if (t.phase < HAS_RESULT) { t.failure = 'Test timed out'; t.phase = HAS_RESULT; t.done(); }
    }
  }, 10000);
  harnessCheck();
});
function test(fn, name) {
  var t = new Test(name);
  t.step(fn, t, t);
  if (t.phase === STARTED) t.done();
}
function async_test(fn, name) {
  if (typeof fn !== 'function') { name = fn; fn = null; }
  var t = new Test(name);
  if (fn) t.step(fn, t, t);
  return t;
}
function promise_test(fn, name) {
  var t = new Test(name);
  if (!harness.promiseTests) harness.promiseTests = Promise.resolve();
  harness.promiseTests = harness.promiseTests.then(function () {
    return new Promise(function (resolve) {
      var promise = t.step(fn, t, t);
      t.step(function () { check(!!promise && typeof promise.then === 'function', null, 'the test body returned no thenable'); });
      if (t.phase === COMPLETE) resolve(); else t.whenDone.push(resolve);
      Promise.resolve(promise).catch(t.step_func(function (e) { throw e; })).then(function () { t.done(); });
    });
  });
}
function promise_rejects_js(t, constructor, promise, description) {
  return promise.then(t.unreached_func('Should have rejected: ' + description), function (e) {
    assert_throws_js(constructor, function () { throw e; }, description);
  });
}
function promise_rejects_dom(t, name, promise, description) {
  return promise.then(t.unreached_func('Should have rejected: ' + description), function (e) {
    assert_throws_dom(name, function () { throw e; }, description);
  });
}
function promise_rejects_exactly(t, exception, promise, description) {
  return promise.then(t.unreached_func('Should have rejected: ' + description), function (e) {
    assert_throws_exactly(exception, function () { throw e; }, description);
  });
}
/* The EventWatcher of testharness.js: a promise for each event, or run of events, in order, and a
   failure for any watched event that comes when none is awaited or another is. */
function EventWatcher(t, target, types) {
  if (typeof types === 'string') types = [types];
  var waitingFor = null;
  var recorded = null;
  var handler = t.step_func(function (event) {
    assert_true(!!waitingFor, 'Not expecting event, but got ' + event.type + ' event');
    assert_equals(event.type, waitingFor.types[0], 'Expected ' + waitingFor.types[0] + ' event, but got ' + event.type + ' event instead');
    if (recorded) recorded.push(event);
    if (waitingFor.types.length > 1) { waitingFor.types.shift(); return; }
    var resolve = waitingFor.resolve;
    var result = recorded || event;
    waitingFor = null;
    recorded = null;
    resolve(result);
  });
  for (var i = 0; i < types.length; i++) target.addEventListener(types[i], handler, false);
  this.wait_for = function (expected, options) {
    if (waitingFor) return Promise.reject('Already waiting for an event or events');
    if (typeof expected === 'string') expected = [expected];
    if (options && options.record === 'all') recorded = [];
    return new Promise(function (resolve, reject) { waitingFor = { types: expected.slice(), resolve: resolve, reject: reject }; });
  };
  this.stop_watching = function () {
    for (var i = 0; i < types.length; i++) target.removeEventListener(types[i], handler, false);
  };
  t.add_cleanup(this.stop_watching);
}
function subsetTestByKey(key, testFunction, fn, name) { return testFunction(fn, name); }
function setup(fn) { if (typeof fn === 'function') fn(); }
function generate_tests(fn, cases) {
  cases.forEach(function (c) { test(function () { fn.apply(null, c.slice(1)); }, c[0]); });
}
function fetch_json(path) { return fetch(path).then(function (response) { return response.json(); }); }
function fetch(path) {
  var data = harnessData[path];
  if (data === undefined) return Promise.reject(new TypeError('no ' + path));
  return Promise.resolve({ json: function () { return Promise.resolve(data); } });
}
function AssertionError(message) { this.message = message; }
AssertionError.prototype = Object.create(Error.prototype);
AssertionError.prototype.name = 'AssertionError';
function show(v) {
  if (typeof v === 'string') return JSON.stringify(v);
  try { return String(v); } catch (e) { return typeof v; }
}
/* A string quoted, its control characters escaped, as testharness.js names a test after one. */
function format_value(v) {
  if (typeof v !== 'string') return show(v);
  return '"' + v.replace(/["\\\u0000-\u001f\u007f-\u009f\u2028\u2029]/g, function (c) {
    return c === '"' || c === '\\' ? '\\' + c : '\\u' + ('000' + c.charCodeAt(0).toString(16)).slice(-4);
  }) + '"';
}
function same(a, b) { return a === b ? a !== 0 || 1 / a === 1 / b : a !== a && b !== b; }
function check(ok, description, what) { if (!ok) throw new AssertionError((description ? description + ': ' : '') + what); }
function assert_equals(actual, expected, description) { check(same(actual, expected), description, 'expected ' + show(expected) + ' but got ' + show(actual)); }
function assert_not_equals(actual, expected, description) { check(!same(actual, expected), description, 'got ' + show(actual)); }
function assert_true(actual, description) { check(actual === true, description, 'expected true but got ' + show(actual)); }
function assert_false(actual, description) { check(actual === false, description, 'expected false but got ' + show(actual)); }
function assert_greater_than(actual, expected, description) {
  check(typeof actual === typeof expected && actual > expected, description, 'expected a number greater than ' + show(expected) + ' but got ' + show(actual));
}
function assert_in_array(actual, expected, description) {
  check(expected.indexOf(actual) !== -1, description, 'value ' + show(actual) + ' not in array ' + show(expected));
}
function assert_class_string(object, name, description) {
  var actual = Object.prototype.toString.call(object);
  check(actual === '[object ' + name + ']', description, 'expected [object ' + name + '] but got ' + actual);
}
function assert_array_equals(actual, expected, description) {
  check(actual != null && actual.length === expected.length, description, 'lengths differ: ' + show(actual) + ' and ' + show(expected));
  for (var i = 0; i < expected.length; i++) check(same(actual[i], expected[i]), description, 'item ' + i + ' is ' + show(actual[i]) + ', not ' + show(expected[i]));
}
function assert_throws_js(constructor, fn, description) {
  try { fn(); } catch (e) {
    check(e !== null && typeof e === 'object' && e.constructor === constructor && e.name === constructor.name, description, 'threw ' + show(e) + ', not a ' + constructor.name);
    return;
  }
  check(false, description, 'did not throw');
}
function assert_throws_dom(name, fn, description) {
  try { fn(); } catch (e) {
    check(e instanceof DOMException && e.name === name, description, 'threw ' + show(e) + ', not a ' + name);
    return;
  }
  check(false, description, 'did not throw');
}
function assert_throws_exactly(exception, fn, description) {
  try { fn(); } catch (e) {
    check(!(e instanceof AssertionError), description, 'threw ' + show(e));
    check(same(e, exception), description, 'threw ' + show(e) + ' but we expected it to throw ' + show(exception));
    return;
  }
  check(false, description, 'did not throw');
}
function assert_own_property(object, name, description) {
  check(object != null && Object.prototype.hasOwnProperty.call(object, name), description, 'expected an own property ' + show(name));
}
function assert_unreached(description) { check(false, description, 'reached unreachable code'); }
