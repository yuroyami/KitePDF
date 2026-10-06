package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] for `DOMPoint`, `DOMQuad` and `DOMMatrix`, of Geometry Interfaces
 * Module Level 1 (#609). `DOMRect` lives with the nodes, which answer it for a box.
 */
internal const val DOM_PRELUDE_GEOMETRY: String = """/* ---- DOMPoint, DOMQuad and DOMMatrix, of Geometry Interfaces 1 (#609) ----
   A matrix keeps its 16 entries in the order m11, m12, ... m44, so entry (column - 1) * 4 + (row - 1)
   is m<column><row>, and a point maps to m11 x + m21 y + m31 z + m41 w and so on. */

/* An unrestricted double, as Web IDL converts one, with Chromium's message for a symbol. */
function geometryNumber(v, what) {
  if (typeof v === 'symbol') throw new TypeError(what + 'Cannot convert a Symbol value to a number');
  if (typeof v === 'bigint') throw new TypeError(what + 'Cannot convert a BigInt value to a number');
  return +v;
}
function argNumber(v, fallback) { return v === undefined ? fallback : geometryNumber(v, ''); }
/* The dictionary [v] as Web IDL reads one: undefined and null are empty, any other primitive throws. */
function geometryDictionary(v, what, type) {
  if (v === undefined || v === null) return null;
  if (typeof v !== 'object' && typeof v !== 'function') throw new TypeError(what + ": The provided value is not of type '" + type + "'.");
  return v;
}
/* A member of a dictionary that a script gave, as a number, or undefined when it is not there. */
function memberNumber(dict, name, what, type) {
  if (dict === null) return undefined;
  var v = dict[name];
  return v === undefined ? undefined : geometryNumber(v, what + ": Failed to read the '" + name + "' property from '" + type + "': ");
}

/* ---- DOMPointReadOnly and DOMPoint ---- */

var points = new WeakMap();
function pointOf(p) {
  var s = p !== null && typeof p === 'object' ? WeakMapGet(points, p) : undefined;
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function newPoint(ctor, x, y, z, w) {
  var p = ObjectCreate(ctor.prototype);
  WeakMapSet(points, p, { __proto__: null, x: x, y: y, z: z, w: w });
  return p;
}
function initPoint(self, ctor, name, args) {
  needNew(self, ctor, name, function (p) { return WeakMapHas(points, p); });
  WeakMapSet(points, self, { __proto__: null, x: argNumber(args[0], 0), y: argNumber(args[1], 0), z: argNumber(args[2], 0), w: argNumber(args[3], 1) });
}
function DOMPointReadOnly() { initPoint(this, DOMPointReadOnly, 'DOMPointReadOnly', arguments); }
function DOMPoint() { initPoint(this, DOMPoint, 'DOMPoint', arguments); }
DOMPoint.prototype = ObjectCreate(DOMPointReadOnly.prototype);
ObjectSetPrototypeOf(DOMPoint, DOMPointReadOnly);
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) {
    def(DOMPointReadOnly.prototype, n, function () { return pointOf(this)[n]; });
    def(DOMPoint.prototype, n, function () { return pointOf(this)[n]; }, function (v) { pointOf(this)[n] = geometryNumber(v, ''); });
  })(names[i]);
})(['x', 'y', 'z', 'w']);
/* The point of a DOMPointInit, whose members Web IDL reads in the order w, x, y, z. */
function pointFromInit(ctor, other, what) {
  var d = geometryDictionary(other, what, 'DOMPointInit');
  var w = memberNumber(d, 'w', what, 'DOMPointInit'), x = memberNumber(d, 'x', what, 'DOMPointInit');
  var y = memberNumber(d, 'y', what, 'DOMPointInit'), z = memberNumber(d, 'z', what, 'DOMPointInit');
  return newPoint(ctor, x === undefined ? 0 : x, y === undefined ? 0 : y, z === undefined ? 0 : z, w === undefined ? 1 : w);
}
DOMPointReadOnly.fromPoint = function fromPoint(other) { return pointFromInit(DOMPointReadOnly, other, "Failed to execute 'fromPoint' on 'DOMPointReadOnly'"); };
DOMPoint.fromPoint = function fromPoint(other) { return pointFromInit(DOMPoint, other, "Failed to execute 'fromPoint' on 'DOMPoint'"); };
DOMPointReadOnly.prototype.matrixTransform = function matrixTransform(matrix) {
  var p = pointOf(this);
  var m = matrixFromInit(matrix, "Failed to execute 'matrixTransform' on 'DOMPointReadOnly'").m;
  return transformedPoint(m, p.x, p.y, p.z, p.w);
};
function transformedPoint(m, x, y, z, w) {
  return newPoint(DOMPoint,
    m[0] * x + m[4] * y + m[8] * z + m[12] * w,
    m[1] * x + m[5] * y + m[9] * z + m[13] * w,
    m[2] * x + m[6] * y + m[10] * z + m[14] * w,
    m[3] * x + m[7] * y + m[11] * z + m[15] * w);
}
DOMPointReadOnly.prototype.toJSON = function toJSON() {
  var p = pointOf(this);
  return { x: p.x, y: p.y, z: p.z, w: p.w };
};
defineInterface(DOMPointReadOnly, 'DOMPointReadOnly', null, 0);
defineInterface(DOMPoint, 'DOMPoint', DOMPointReadOnly, 0);

/* ---- DOMQuad ---- */

var quads = new WeakMap();
function quadOf(q) {
  var s = q !== null && typeof q === 'object' ? WeakMapGet(quads, q) : undefined;
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function newQuad(p1, p2, p3, p4) {
  var q = ObjectCreate(DOMQuad.prototype);
  WeakMapSet(quads, q, { __proto__: null, p1: p1, p2: p2, p3: p3, p4: p4 });
  return q;
}
function DOMQuad() {
  needNew(this, DOMQuad, 'DOMQuad', function (q) { return WeakMapHas(quads, q); });
  var what = "Failed to construct 'DOMQuad'";
  WeakMapSet(quads, this, { __proto__: null, p1: pointFromInit(DOMPoint, arguments[0], what), p2: pointFromInit(DOMPoint, arguments[1], what),
    p3: pointFromInit(DOMPoint, arguments[2], what), p4: pointFromInit(DOMPoint, arguments[3], what) });
}
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) {
    def(DOMQuad.prototype, n, function () { return quadOf(this)[n]; });
  })(names[i]);
})(['p1', 'p2', 'p3', 'p4']);
DOMQuad.fromRect = function fromRect(other) {
  var what = "Failed to execute 'fromRect' on 'DOMQuad'", d = geometryDictionary(other, what, 'DOMRectInit');
  var h = memberNumber(d, 'height', what, 'DOMRectInit'), w = memberNumber(d, 'width', what, 'DOMRectInit');
  var x = memberNumber(d, 'x', what, 'DOMRectInit'), y = memberNumber(d, 'y', what, 'DOMRectInit');
  if (h === undefined) h = 0;
  if (w === undefined) w = 0;
  if (x === undefined) x = 0;
  if (y === undefined) y = 0;
  return newQuad(newPoint(DOMPoint, x, y, 0, 1), newPoint(DOMPoint, x + w, y, 0, 1), newPoint(DOMPoint, x + w, y + h, 0, 1), newPoint(DOMPoint, x, y + h, 0, 1));
};
DOMQuad.fromQuad = function fromQuad(other) {
  var what = "Failed to execute 'fromQuad' on 'DOMQuad'", d = geometryDictionary(other, what, 'DOMQuadInit');
  var pts = [], names = ['p1', 'p2', 'p3', 'p4'];
  for (var i = 0; i < 4; i++) ArrayPush(pts, pointFromInit(DOMPoint, d === null ? undefined : d[names[i]], what));
  return newQuad(pts[0], pts[1], pts[2], pts[3]);
};
DOMQuad.prototype.getBounds = function getBounds() {
  var q = quadOf(this), xs = [], ys = [], names = ['p1', 'p2', 'p3', 'p4'];
  for (var i = 0; i < 4; i++) { var p = pointOf(q[names[i]]); ArrayPush(xs, p.x); ArrayPush(ys, p.y); }
  var left = MathMin(xs[0], xs[1], xs[2], xs[3]), top = MathMin(ys[0], ys[1], ys[2], ys[3]);
  var right = MathMax(xs[0], xs[1], xs[2], xs[3]), bottom = MathMax(ys[0], ys[1], ys[2], ys[3]);
  return newRect(DOMRect, left, top, right - left, bottom - top);
};
DOMQuad.prototype.toJSON = function toJSON() {
  var q = quadOf(this);
  return { p1: q.p1, p2: q.p2, p3: q.p3, p4: q.p4 };
};
defineInterface(DOMQuad, 'DOMQuad', null, 0);

/* ---- DOMMatrixReadOnly and DOMMatrix ---- */

var matrices = new WeakMap();
var IDENTITY_MATRIX = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
var MATRIX_NAMES = ['m11', 'm12', 'm13', 'm14', 'm21', 'm22', 'm23', 'm24', 'm31', 'm32', 'm33', 'm34', 'm41', 'm42', 'm43', 'm44'];
function matrixOf(m) {
  var s = m !== null && typeof m === 'object' ? WeakMapGet(matrices, m) : undefined;
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function newMatrix(ctor, m, is2D) {
  var o = ObjectCreate(ctor.prototype);
  WeakMapSet(matrices, o, { __proto__: null, m: m, is2D: is2D });
  return o;
}
function copyEntries(m) { var out = []; for (var i = 0; i < 16; i++) ArrayPush(out, m[i]); return out; }
function matrix2d(a, b, c, d, e, f) { return [a, b, 0, 0, c, d, 0, 0, 0, 0, 1, 0, e, f, 0, 1]; }
/* [a] times [b]: [b] applies first. */
function matrixMultiply(a, b) {
  var out = [];
  for (var col = 0; col < 4; col++) for (var row = 0; row < 4; row++) {
    var sum = 0;
    for (var k = 0; k < 4; k++) sum += a[k * 4 + row] * b[col * 4 + k];
    out[col * 4 + row] = sum;
  }
  return out;
}
function notZero(v) { return v !== 0; }
/* The sine and cosine of [degrees], exact at each multiple of 45 degrees as in Chromium. */
function sinCosDegrees(degrees) {
  if (degrees % 45 === 0) {
    var r = MathSqrt(0.5), eighth = ((degrees / 45) % 8 + 8) % 8;
    var table = [[0, 1], [r, r], [1, 0], [r, -r], [0, -1], [-r, -r], [-1, 0], [-r, r]];
    return table[eighth];
  }
  var rad = degrees * MathPI / 180;
  return [MathSin(rad), MathCos(rad)];
}
/* A rotation by [degrees] about ([x], [y], [z]), as rotate3d() defines it. */
function rotationMatrix(x, y, z, degrees) {
  var length = MathSqrt(x * x + y * y + z * z), out = copyEntries(IDENTITY_MATRIX);
  if (length === 0 || length !== length) return out;
  var nx = x / length, ny = y / length, nz = z / length, sc;
  if (nx === 1 && ny === 0 && nz === 0) { sc = sinCosDegrees(degrees); out[5] = sc[1]; out[6] = sc[0]; out[9] = -sc[0]; out[10] = sc[1]; return out; }
  if (nx === 0 && ny === 1 && nz === 0) { sc = sinCosDegrees(degrees); out[0] = sc[1]; out[2] = -sc[0]; out[8] = sc[0]; out[10] = sc[1]; return out; }
  if (nx === 0 && ny === 0 && nz === 1) { sc = sinCosDegrees(degrees); out[0] = sc[1]; out[1] = sc[0]; out[4] = -sc[0]; out[5] = sc[1]; return out; }
  var r = degrees * MathPI / 180, s = MathSin(r), c = MathCos(r), k = 1 - c;
  out[0] = nx * nx * k + c; out[1] = nx * ny * k + nz * s; out[2] = nz * nx * k - ny * s;
  out[4] = nx * ny * k - nz * s; out[5] = ny * ny * k + c; out[6] = ny * nz * k + nx * s;
  out[8] = nz * nx * k + ny * s; out[9] = ny * nz * k - nx * s; out[10] = nz * nz * k + c;
  return out;
}
/* The entries and the 2D flag of a CSS transform list, or the SyntaxError Chromium throws for it. */
function parseTransformList(text, what) {
  var r = K.matrixParse(text);
  if (r === null || r === undefined) throw new DOMException(what + ": Failed to parse '" + text + "'.", 'SyntaxError');
  if (r === 'relative') throw new DOMException(what + ': Values must be resolvable at parse time', 'SyntaxError');
  var m = [];
  for (var i = 0; i < 16; i++) ArrayPush(m, r[i]);
  return { __proto__: null, m: m, is2D: r[16] === 1 };
}
var SEQUENCE_LENGTH = 'The sequence must contain 6 elements for a 2D matrix or 16 elements for a 3D matrix.';
function entriesFromList(list, what) {
  if (list.length === 6) return { __proto__: null, m: matrix2d(list[0], list[1], list[2], list[3], list[4], list[5]), is2D: true };
  if (list.length === 16) return { __proto__: null, m: copyEntries(list), is2D: false };
  throw new TypeError(what + ': ' + SEQUENCE_LENGTH);
}
function initMatrix(self, ctor, name, init) {
  needNew(self, ctor, name, function (m) { return WeakMapHas(matrices, m); });
  var what = "Failed to construct '" + name + "'", r;
  if (init === undefined) r = { __proto__: null, m: copyEntries(IDENTITY_MATRIX), is2D: true };
  else if (init !== null && (typeof init === 'object' || typeof init === 'function') && init[SymbolIterator] !== undefined) {
    r = entriesFromList(idlSequence(init, function (v) { return geometryNumber(v, what + ': '); }, what), what);
  } else {
    if (typeof init === 'symbol') throw new TypeError(what + ': Cannot convert a Symbol value to a string');
    r = parseTransformList('' + init, what);
  }
  WeakMapSet(matrices, self, r);
}
/* The matrix of a DOMMatrixInit, checked and filled in as Geometry Interfaces 1, 6.1 says. */
function matrixFromInit(other, what) {
  var d = geometryDictionary(other, what, 'DOMMatrixInit'), T = 'DOMMatrixInit';
  var a = memberNumber(d, 'a', what, T), b = memberNumber(d, 'b', what, T), c = memberNumber(d, 'c', what, T);
  var dd = memberNumber(d, 'd', what, T), e = memberNumber(d, 'e', what, T), f = memberNumber(d, 'f', what, T);
  var m11 = memberNumber(d, 'm11', what, T), m12 = memberNumber(d, 'm12', what, T), m21 = memberNumber(d, 'm21', what, T);
  var m22 = memberNumber(d, 'm22', what, T), m41 = memberNumber(d, 'm41', what, T), m42 = memberNumber(d, 'm42', what, T);
  var is2D = d === null || d.is2D === undefined ? undefined : !!d.is2D;
  var m13 = memberNumber(d, 'm13', what, T), m14 = memberNumber(d, 'm14', what, T), m23 = memberNumber(d, 'm23', what, T);
  var m24 = memberNumber(d, 'm24', what, T), m31 = memberNumber(d, 'm31', what, T), m32 = memberNumber(d, 'm32', what, T);
  var m33 = memberNumber(d, 'm33', what, T), m34 = memberNumber(d, 'm34', what, T), m43 = memberNumber(d, 'm43', what, T);
  var m44 = memberNumber(d, 'm44', what, T);
  function clash(x, y) { return x !== undefined && y !== undefined && !(x === y || (x !== x && y !== y)); }
  if (clash(a, m11) || clash(b, m12) || clash(c, m21) || clash(dd, m22) || clash(e, m41) || clash(f, m42)) {
    throw new TypeError(what + ': Property mismatch on matrix initialization.');
  }
  if (m11 === undefined) m11 = a === undefined ? 1 : a;
  if (m12 === undefined) m12 = b === undefined ? 0 : b;
  if (m21 === undefined) m21 = c === undefined ? 0 : c;
  if (m22 === undefined) m22 = dd === undefined ? 1 : dd;
  if (m41 === undefined) m41 = e === undefined ? 0 : e;
  if (m42 === undefined) m42 = f === undefined ? 0 : f;
  if (m13 === undefined) m13 = 0;
  if (m14 === undefined) m14 = 0;
  if (m23 === undefined) m23 = 0;
  if (m24 === undefined) m24 = 0;
  if (m31 === undefined) m31 = 0;
  if (m32 === undefined) m32 = 0;
  if (m33 === undefined) m33 = 1;
  if (m34 === undefined) m34 = 0;
  if (m43 === undefined) m43 = 0;
  if (m44 === undefined) m44 = 1;
  var threeD = notZero(m13) || notZero(m14) || notZero(m23) || notZero(m24) || notZero(m31) || notZero(m32) ||
    notZero(m34) || notZero(m43) || m33 !== 1 || m44 !== 1;
  if (is2D === true && threeD) throw new TypeError(what + ': The is2D member is set to true but the input matrix is a 3d matrix.');
  if (is2D === undefined) is2D = !threeD;
  return { __proto__: null, m: [m11, m12, m13, m14, m21, m22, m23, m24, m31, m32, m33, m34, m41, m42, m43, m44], is2D: is2D };
}
function fromTypedArray(ctor, array, tag, what) {
  if (TypedArrayTag(array) !== tag) throw new TypeError(what + ": parameter 1 is not of type '" + tag + "'.");
  var list = [];
  for (var i = 0; i < TypedArrayLength(array); i++) ArrayPush(list, array[i]);
  var r = entriesFromList(list, what);
  return newMatrix(ctor, r.m, r.is2D);
}
function matrixStatics(ctor, name) {
  ctor.fromMatrix = function fromMatrix(other) {
    var r = matrixFromInit(other, "Failed to execute 'fromMatrix' on '" + name + "'");
    return newMatrix(ctor, r.m, r.is2D);
  };
  ctor.fromFloat32Array = function fromFloat32Array(array32) {
    return fromTypedArray(ctor, array32, 'Float32Array', "Failed to execute 'fromFloat32Array' on '" + name + "'");
  };
  ctor.fromFloat64Array = function fromFloat64Array(array64) {
    return fromTypedArray(ctor, array64, 'Float64Array', "Failed to execute 'fromFloat64Array' on '" + name + "'");
  };
}
function DOMMatrixReadOnly() { initMatrix(this, DOMMatrixReadOnly, 'DOMMatrixReadOnly', arguments[0]); }
function DOMMatrix() { initMatrix(this, DOMMatrix, 'DOMMatrix', arguments[0]); }
DOMMatrix.prototype = ObjectCreate(DOMMatrixReadOnly.prototype);
ObjectSetPrototypeOf(DOMMatrix, DOMMatrixReadOnly);
matrixStatics(DOMMatrixReadOnly, 'DOMMatrixReadOnly');
matrixStatics(DOMMatrix, 'DOMMatrix');
(function () {
  var ALIASES = [['a', 0], ['b', 1], ['c', 4], ['d', 5], ['e', 12], ['f', 13]];
  function entry(name, at, flat) {
    def(DOMMatrixReadOnly.prototype, name, function () { return matrixOf(this).m[at]; });
    def(DOMMatrix.prototype, name, function () { return matrixOf(this).m[at]; }, function (v) {
      var s = matrixOf(this), n = geometryNumber(v, '');
      s.m[at] = n;
      if (flat !== undefined && n !== flat) s.is2D = false;
    });
  }
  for (var i = 0; i < ALIASES.length; i++) entry(ALIASES[i][0], ALIASES[i][1], undefined);
  // An entry that a 2D matrix holds at 0, or at 1 on the diagonal, makes it 3D when set to anything else.
  var FLAT = [undefined, undefined, 0, 0, undefined, undefined, 0, 0, 0, 0, 1, 0, undefined, undefined, 0, 1];
  for (var j = 0; j < 16; j++) entry(MATRIX_NAMES[j], j, FLAT[j]);
})();
def(DOMMatrixReadOnly.prototype, 'is2D', function () { return matrixOf(this).is2D; });
def(DOMMatrixReadOnly.prototype, 'isIdentity', function () {
  var m = matrixOf(this).m;
  for (var i = 0; i < 16; i++) if (m[i] !== IDENTITY_MATRIX[i]) return false;
  return true;
});

/* The steps of the Self methods, on a matrix's state [s]. */
function postMultiply(s, b) { s.m = matrixMultiply(s.m, b); }
function translateState(s, tx, ty, tz) {
  var t = copyEntries(IDENTITY_MATRIX);
  t[12] = tx; t[13] = ty; t[14] = tz;
  postMultiply(s, t);
  if (notZero(tz)) s.is2D = false;
}
function scaleState(s, sx, sy, sz, ox, oy, oz) {
  translateState(s, ox, oy, oz);
  var t = copyEntries(IDENTITY_MATRIX);
  t[0] = sx; t[5] = sy; t[10] = sz;
  postMultiply(s, t);
  translateState(s, -ox, -oy, -oz);
  if (sz !== 1 || notZero(oz)) s.is2D = false;
}
function rotateState(s, rotX, rotY, rotZ) {
  postMultiply(s, rotationMatrix(0, 0, 1, rotZ));
  postMultiply(s, rotationMatrix(0, 1, 0, rotY));
  postMultiply(s, rotationMatrix(1, 0, 0, rotX));
  if (notZero(rotX) || notZero(rotY)) s.is2D = false;
}
function skewState(s, ax, ay) {
  var t = copyEntries(IDENTITY_MATRIX);
  t[4] = MathTan(ax * MathPI / 180);
  t[1] = MathTan(ay * MathPI / 180);
  postMultiply(s, t);
}
/* The inverse of [m], or null when it has none. */
function invertEntries(m) {
  var inv = [];
  inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
  inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
  inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
  inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
  inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
  inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
  inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
  inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
  inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
  inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
  inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
  inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
  inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
  inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
  inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
  inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
  var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
  if (det === 0 || det !== det || det === 1 / 0 || det === -1 / 0) return null;
  for (var i = 0; i < 16; i++) inv[i] = inv[i] / det;
  return inv;
}
function invertState(s) {
  // A 2D matrix inverts as one, so its zero entries stay exact.
  var m = s.m, inv;
  if (s.is2D) {
    var det = m[0] * m[5] - m[1] * m[4];
    inv = det === 0 || det !== det || det === 1 / 0 || det === -1 / 0 ? null :
      matrix2d(m[5] / det, -m[1] / det, -m[4] / det, m[0] / det, (m[4] * m[13] - m[5] * m[12]) / det, (m[1] * m[12] - m[0] * m[13]) / det);
  } else {
    inv = invertEntries(m);
  }
  if (inv === null) {
    var nan = [];
    for (var i = 0; i < 16; i++) ArrayPush(nan, 0 / 0);
    s.m = nan;
    s.is2D = false;
  } else {
    s.m = inv;
  }
}
function rotateArgs(args) {
  var rotX = argNumber(args[0], 0), rotY = args[1], rotZ = args[2];
  if (rotY === undefined && rotZ === undefined) return [0, 0, rotX];
  return [rotX, argNumber(rotY, 0), argNumber(rotZ, 0)];
}
function vectorAngle(x, y) { return x === 0 && y === 0 ? 0 : MathAtan2(y, x) * (180 / MathPI); }

/* The steps every method shares: [name]Self changes the matrix, and [name] changes a copy. */
var MATRIX_STEPS = {
  __proto__: null,
  translate: function (s, a) { translateState(s, argNumber(a[0], 0), argNumber(a[1], 0), argNumber(a[2], 0)); },
  scale: function (s, a) {
    var sx = argNumber(a[0], 1);
    scaleState(s, sx, a[1] === undefined ? sx : argNumber(a[1], 1), argNumber(a[2], 1), argNumber(a[3], 0), argNumber(a[4], 0), argNumber(a[5], 0));
  },
  scale3d: function (s, a) {
    var k = argNumber(a[0], 1);
    scaleState(s, k, k, k, argNumber(a[1], 0), argNumber(a[2], 0), argNumber(a[3], 0));
    if (k !== 1) s.is2D = false;
  },
  rotate: function (s, a) { var r = rotateArgs(a); rotateState(s, r[0], r[1], r[2]); },
  rotateFromVector: function (s, a) { postMultiply(s, rotationMatrix(0, 0, 1, vectorAngle(argNumber(a[0], 0), argNumber(a[1], 0)))); },
  rotateAxisAngle: function (s, a) {
    var x = argNumber(a[0], 0), y = argNumber(a[1], 0), z = argNumber(a[2], 0);
    postMultiply(s, rotationMatrix(x, y, z, argNumber(a[3], 0)));
    if (notZero(x) || notZero(y)) s.is2D = false;
  },
  skewX: function (s, a) { skewState(s, argNumber(a[0], 0), 0); },
  skewY: function (s, a) { skewState(s, 0, argNumber(a[0], 0)); }
};
(function () {
  var names = ['translate', 'scale', 'scale3d', 'rotate', 'rotateFromVector', 'rotateAxisAngle', 'skewX', 'skewY'];
  function method(name) {
    var step = MATRIX_STEPS[name];
    DOMMatrixReadOnly.prototype[name] = function () {
      var s = matrixOf(this), copy = { __proto__: null, m: copyEntries(s.m), is2D: s.is2D };
      step(copy, arguments);
      return newMatrix(DOMMatrix, copy.m, copy.is2D);
    };
    DOMMatrix.prototype[name + 'Self'] = function () {
      step(matrixOf(this), arguments);
      return this;
    };
  }
  for (var i = 0; i < names.length; i++) method(names[i]);
})();
DOMMatrixReadOnly.prototype.scaleNonUniform = function scaleNonUniform() {
  var s = matrixOf(this), copy = { __proto__: null, m: copyEntries(s.m), is2D: s.is2D };
  scaleState(copy, argNumber(arguments[0], 1), argNumber(arguments[1], 1), 1, 0, 0, 0);
  return newMatrix(DOMMatrix, copy.m, copy.is2D);
};
DOMMatrixReadOnly.prototype.multiply = function multiply(other) {
  var s = matrixOf(this), o = matrixFromInit(other, "Failed to execute 'multiply' on 'DOMMatrixReadOnly'");
  return newMatrix(DOMMatrix, matrixMultiply(s.m, o.m), s.is2D && o.is2D);
};
DOMMatrixReadOnly.prototype.flipX = function flipX() {
  var s = matrixOf(this), t = copyEntries(IDENTITY_MATRIX);
  t[0] = -1;
  return newMatrix(DOMMatrix, matrixMultiply(s.m, t), s.is2D);
};
DOMMatrixReadOnly.prototype.flipY = function flipY() {
  var s = matrixOf(this), t = copyEntries(IDENTITY_MATRIX);
  t[5] = -1;
  return newMatrix(DOMMatrix, matrixMultiply(s.m, t), s.is2D);
};
DOMMatrixReadOnly.prototype.inverse = function inverse() {
  var s = matrixOf(this), copy = { __proto__: null, m: copyEntries(s.m), is2D: s.is2D };
  invertState(copy);
  return newMatrix(DOMMatrix, copy.m, copy.is2D);
};
DOMMatrixReadOnly.prototype.transformPoint = function transformPoint(point) {
  var s = matrixOf(this), p = pointOf(pointFromInit(DOMPoint, point, "Failed to execute 'transformPoint' on 'DOMMatrixReadOnly'"));
  return transformedPoint(s.m, p.x, p.y, p.z, p.w);
};
DOMMatrixReadOnly.prototype.toFloat32Array = function toFloat32Array() {
  var m = matrixOf(this).m, F32 = TYPED_ARRAYS.Float32Array, out = new F32(16);
  for (var i = 0; i < 16; i++) out[i] = m[i];
  return out;
};
DOMMatrixReadOnly.prototype.toFloat64Array = function toFloat64Array() {
  var m = matrixOf(this).m, F64 = TYPED_ARRAYS.Float64Array, out = new F64(16);
  for (var i = 0; i < 16; i++) out[i] = m[i];
  return out;
};
DOMMatrixReadOnly.prototype.toJSON = function toJSON() {
  var s = matrixOf(this), m = s.m, out = { a: m[0], b: m[1], c: m[4], d: m[5], e: m[12], f: m[13] };
  for (var i = 0; i < 16; i++) putData(out, MATRIX_NAMES[i], m[i]);
  out.is2D = s.is2D;
  out.isIdentity = this.isIdentity;
  return out;
};
DOMMatrixReadOnly.prototype.toString = function toString() {
  var s = matrixOf(this), m = s.m;
  for (var i = 0; i < 16; i++) {
    if (m[i] !== m[i] || m[i] === 1 / 0 || m[i] === -1 / 0) {
      throw new DOMException("Failed to execute 'toString' on 'DOMMatrixReadOnly': DOMMatrix cannot be serialized with NaN or Infinity values.", 'InvalidStateError');
    }
  }
  if (s.is2D) return 'matrix(' + ArrayJoin([m[0], m[1], m[4], m[5], m[12], m[13]], ', ') + ')';
  return 'matrix3d(' + ArrayJoin(m, ', ') + ')';
};
DOMMatrix.prototype.multiplySelf = function multiplySelf(other) {
  var s = matrixOf(this), o = matrixFromInit(other, "Failed to execute 'multiplySelf' on 'DOMMatrix'");
  s.m = matrixMultiply(s.m, o.m);
  if (!o.is2D) s.is2D = false;
  return this;
};
DOMMatrix.prototype.preMultiplySelf = function preMultiplySelf(other) {
  var s = matrixOf(this), o = matrixFromInit(other, "Failed to execute 'preMultiplySelf' on 'DOMMatrix'");
  s.m = matrixMultiply(o.m, s.m);
  if (!o.is2D) s.is2D = false;
  return this;
};
DOMMatrix.prototype.invertSelf = function invertSelf() {
  invertState(matrixOf(this));
  return this;
};
DOMMatrix.prototype.setMatrixValue = function setMatrixValue(transformList) {
  var s = matrixOf(this), what = "Failed to execute 'setMatrixValue' on 'DOMMatrix'";
  needArgs(arguments, 1, what);
  if (typeof transformList === 'symbol') throw new TypeError(what + ': Cannot convert a Symbol value to a string');
  var r = parseTransformList('' + transformList, what);
  s.m = r.m;
  s.is2D = r.is2D;
  return this;
};
defineInterface(DOMMatrixReadOnly, 'DOMMatrixReadOnly', null, 0);
defineInterface(DOMMatrix, 'DOMMatrix', DOMMatrixReadOnly, 0);
"""
