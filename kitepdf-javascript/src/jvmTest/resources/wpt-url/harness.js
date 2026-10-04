/* Just enough of testharness.js for the URL tests of web-platform-tests: each test runs at once,
   a failing one logs its name and error, and the last script logs the count once every
   promise_test has settled. Not a file of web-platform-tests. */
var harness = { passed: 0, failed: 0, pending: [] };
function harnessFail(name, e) {
  harness.failed++;
  console.log('FAIL ' + name + ' :: ' + (e && e.message !== undefined ? e.name + ': ' + e.message : String(e)));
}
function test(fn, name) {
  try { fn(); harness.passed++; } catch (e) { harnessFail(name, e); }
}
function promise_test(fn, name) {
  harness.pending.push(Promise.resolve().then(fn).then(function () {}, function (e) { harnessFail(name, e); }));
}
function subsetTestByKey(key, testFunction, fn, name) { return testFunction(fn, name); }
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
function same(a, b) { return a === b ? a !== 0 || 1 / a === 1 / b : a !== a && b !== b; }
function check(ok, description, what) { if (!ok) throw new AssertionError((description ? description + ': ' : '') + what); }
function assert_equals(actual, expected, description) { check(same(actual, expected), description, 'expected ' + show(expected) + ' but got ' + show(actual)); }
function assert_not_equals(actual, expected, description) { check(!same(actual, expected), description, 'got ' + show(actual)); }
function assert_true(actual, description) { check(actual === true, description, 'expected true but got ' + show(actual)); }
function assert_false(actual, description) { check(actual === false, description, 'expected false but got ' + show(actual)); }
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
function assert_unreached(description) { check(false, description, 'reached unreachable code'); }
