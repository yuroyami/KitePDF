// Three small kernels in the shapes DoomPDF's script is made of. Each returns a
// checksum so an engine cannot skip work. `runKernel(name)` times one of them.
// Plain ES5 on purpose, so KiteJS and V8 run the same text.
var __kernels = {
  // asm.js style: integer math and typed-array traffic, like Doom's renderer.
  asm: function () {
    var buffer = new ArrayBuffer(1 << 20);
    var mod = (function (global, env, buffer) {
      "use asm";
      var H32 = new global.Int32Array(buffer);
      var HU8 = new global.Uint8Array(buffer);
      var imul = global.Math.imul;
      function fill(n) {
        n = n | 0;
        var i = 0, x = 0, acc = 0;
        for (i = 0; (i | 0) < (n | 0); i = (i + 1) | 0) {
          x = imul(x, 1103515245) + 12345 | 0;
          H32[(i & 65535) << 2 >> 2] = x;
          HU8[(x >>> 12) & 1048575] = (x >>> 24) & 255;
          acc = (acc + (HU8[(i * 7) & 1048575] | 0)) | 0;
        }
        return acc | 0;
      }
      return { fill: fill };
    })({ Int32Array: Int32Array, Uint8Array: Uint8Array, Math: Math }, {}, buffer);
    return mod.fill(3000000);
  },
  // Plain JavaScript over strings and arrays, like the WAD decoder.
  strings: function () {
    var abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".split("");
    var src = "";
    for (var k = 0; k < 2000; k++) src += "TWFuIGlzIGRpc3Rpbmd1aXNoZWQsIG5vdCBvbmx5IGJ5IGhpcyByZWFz";
    var result = [];
    for (var i = 0; i < src.length / 4; i++) {
      var chunk = src.slice(4 * i, 4 * i + 4).split("");
      var bin = chunk.map(function (x) { return abc.indexOf(x).toString(2).padStart(6, 0); }).join("");
      var bytes = bin.match(/.{1,8}/g).map(function (x) { return +("0b" + x); });
      Array.prototype.push.apply(result, bytes.slice(0, 3));
    }
    var sum = 0;
    for (var j = 0; j < result.length; j++) sum = (sum + (result[j] | 0)) | 0;
    return sum;
  },
  // Property access and calls through small objects, like the field updates.
  objects: function () {
    var fields = {};
    function getField(name) {
      var f = fields[name];
      if (!f) { f = { name: name, value: "" }; fields[name] = f; }
      return f;
    }
    var row = new Array(320);
    var total = 0;
    for (var frame = 0; frame < 20; frame++) {
      for (var y = 0; y < 200; y++) {
        for (var x = 0; x < 320; x++) row[x] = ((x + y + frame) & 7) > 3 ? "#" : "_";
        var s = row.join("");
        var f = getField("field_" + y);
        if (f.value !== s) { f.value = s; total++; }
      }
    }
    return total;
  }
};
function runKernel(name) {
  var t0 = Date.now();
  var checksum = __kernels[name]();
  return name + ":" + (Date.now() - t0) + "ms:" + checksum;
}
