/* Stands in for FileAPI/support/Blob.js of web-platform-tests, which writes its promise tests as
   async functions: the same checks, with then in place of await, so that the many tests of the
   files that load it run on an engine without async functions (kitejs#12). Not a file of
   web-platform-tests. */
function test_blob(fn, expectations) {
  var expected = expectations.expected, type = expectations.type, desc = expectations.desc;
  promise_test(function () {
    var blob = fn();
    assert_true(blob instanceof Blob);
    assert_false(blob instanceof File);
    assert_equals(blob.type, type);
    assert_equals(blob.size, expected.length);
    return blob.text().then(function (text) { assert_equals(text, expected); });
  }, desc);
}
function test_blob_binary(fn, expectations) {
  var expected = expectations.expected, type = expectations.type, desc = expectations.desc;
  promise_test(function () {
    var blob = fn();
    assert_true(blob instanceof Blob);
    assert_false(blob instanceof File);
    assert_equals(blob.type, type);
    assert_equals(blob.size, expected.length);
    return blob.arrayBuffer().then(function (ab) {
      assert_true(ab instanceof ArrayBuffer, 'Result should be an ArrayBuffer');
      assert_array_equals(new Uint8Array(ab), expected);
    });
  }, desc);
}
function assert_equals_typed_array(array1, array2) {
  var views = [array1, array2].map(function (array) {
    assert_true(array.buffer instanceof ArrayBuffer, 'Expect input ArrayBuffers to contain field `buffer`');
    return new DataView(array.buffer, array.byteOffset, array.byteLength);
  });
  assert_equals(views[0].byteLength, views[1].byteLength, 'Expect both arrays to be of the same byte length');
  for (var i = 0; i < views[0].byteLength; ++i) {
    assert_equals(views[0].getUint8(i), views[1].getUint8(i), 'Expect byte at buffer position ' + i + ' to be equal');
  }
}
