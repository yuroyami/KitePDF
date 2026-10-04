/* Stands in for common/sab.js of web-platform-tests, which makes a SharedArrayBuffer through
   WebAssembly.Memory, since a page that is not cross-origin isolated has no SharedArrayBuffer
   constructor. A book's scripts have no WebAssembly, and nothing hides the engine's constructor
   from them, so this makes the buffer with it. Not a file of web-platform-tests. */
function createBuffer(type, length, opts) {
  if (type === 'ArrayBuffer') return new ArrayBuffer(length, opts);
  if (type === 'SharedArrayBuffer') return new SharedArrayBuffer(length, opts);
  throw new Error('type has to be ArrayBuffer or SharedArrayBuffer');
}
