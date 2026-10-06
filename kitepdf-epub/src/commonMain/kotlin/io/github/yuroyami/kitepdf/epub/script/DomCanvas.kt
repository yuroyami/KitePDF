package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] for a canvas's 2D context, `Path2D`, `CanvasGradient`, `CanvasPattern`
 * and `TextMetrics`, of HTML, 4.12.5.1 (#501). [CanvasHost] keeps the state and draws; this side
 * converts arguments as Web IDL does and throws what Chromium throws.
 */
internal const val DOM_PRELUDE_CANVAS: String = """/* ---- the 2D context of a canvas, of HTML 4.12.5.1 (#501) ----
   The host keeps each context's state and path, and each Path2D, gradient and pattern by a
   number. This side keeps the objects a script gets back, such as a gradient it set as a style. */

var contexts = new WeakMap(), canvasContexts = new Map(), styleObjects = new Map();
var gradientHandles = new WeakMap(), patternHandles = new WeakMap(), pathHandles = new WeakMap(), metricsData = new WeakMap();
var pathRegistry = FinalizationRegistryCtor ? new FinalizationRegistryCtor(function (h) { K.cv(-1, 'freePath', h); }) : null;

function contextOf(self) {
  var c = self !== null && typeof self === 'object' ? WeakMapGet(contexts, self) : undefined;
  if (c === undefined) throw new TypeError('Illegal invocation');
  return c;
}
function canvasWhat(name) { return "Failed to execute '" + name + "' on 'CanvasRenderingContext2D'"; }
function canvasNumber(v, what) { return geometryNumber(v, what + ': '); }
/* Throws what the host answered when it is an error, and answers it otherwise. */
function canvasAnswer(r, what) {
  if (r !== null && typeof r === 'object' && r[0] === '\u0000error') throw new DOMException(what + ': ' + r[2], r[1]);
  return r;
}
function fillRule(v, what) {
  var s = v === undefined ? 'nonzero' : domString(v);
  if (s !== 'nonzero' && s !== 'evenodd') throw new TypeError(what + ": The provided value '" + s + "' is not a valid enum value of type CanvasFillRule.");
  return s;
}
function pathHandle(v) { return v !== null && typeof v === 'object' ? WeakMapGet(pathHandles, v) : undefined; }
/* The host's number of an image a canvas draws, after the type checks of Web IDL. */
function imageSource(v, what) {
  if (isNode(v) && (isA(v, HTMLImageElement) || isA(v, HTMLCanvasElement) || isA(v, HTMLVideoElement) || isA(v, SVG_TYPES.image))) return v.__id;
  throw new TypeError(what + ": The provided value is not of type '(CSSImageValue or HTMLCanvasElement or HTMLImageElement or " +
    "HTMLVideoElement or ImageBitmap or OffscreenCanvas or SVGImageElement or VideoFrame)'.");
}
/* The 2D part of a DOMMatrixInit, as setTransform and addPath read one. */
function matrix2DOf(v, what) {
  var m = matrixFromInit(v, what).m;
  return [m[0], m[1], m[4], m[5], m[12], m[13]];
}

function CanvasRenderingContext2D() { illegal('CanvasRenderingContext2D'); }
var C2D = CanvasRenderingContext2D.prototype;
def(C2D, 'canvas', function () { return wrap(contextOf(this).canvas); });

/* The attributes the host keeps, by how Web IDL converts what a script sets. */
(function (numbers, strings) {
  for (var i = 0; i < numbers.length; i++) (function (n) {
    def(C2D, n, function () { return K.cv(contextOf(this).canvas, 'get', n); }, function (v) {
      var c = contextOf(this);
      K.cv(c.canvas, 'set', n, geometryNumber(v, "Failed to set the '" + n + "' property on 'CanvasRenderingContext2D': "));
    });
  })(numbers[i]);
  for (var j = 0; j < strings.length; j++) (function (n) {
    def(C2D, n, function () { return K.cv(contextOf(this).canvas, 'get', n); }, function (v) {
      var c = contextOf(this);
      K.cv(c.canvas, 'set', n, domString(v));
    });
  })(strings[j]);
})(['lineWidth', 'miterLimit', 'lineDashOffset', 'globalAlpha', 'shadowBlur', 'shadowOffsetX', 'shadowOffsetY'],
  ['lineCap', 'lineJoin', 'globalCompositeOperation', 'shadowColor', 'font', 'textAlign', 'textBaseline', 'direction', 'filter',
    'imageSmoothingQuality', 'letterSpacing', 'wordSpacing', 'fontKerning', 'fontStretch', 'fontVariantCaps', 'textRendering', 'lang']);
def(C2D, 'imageSmoothingEnabled', function () { return K.cv(contextOf(this).canvas, 'get', 'imageSmoothingEnabled'); },
  function (v) { K.cv(contextOf(this).canvas, 'set', 'imageSmoothingEnabled', !!v); });
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n, which) {
    def(C2D, n, function () {
      var r = K.cv(contextOf(this).canvas, 'getStyle', which);
      return typeof r === 'string' ? r : MapGet(styleObjects, r[1]);
    }, function (v) {
      var c = contextOf(this), h;
      if (v !== null && typeof v === 'object' && (h = WeakMapGet(gradientHandles, v)) !== undefined) {
        MapSet(styleObjects, h, v);
        K.cv(c.canvas, 'setStyle', which, 'gradient', h);
      } else if (v !== null && typeof v === 'object' && (h = WeakMapGet(patternHandles, v)) !== undefined) {
        MapSet(styleObjects, h, v);
        K.cv(c.canvas, 'setStyle', which, 'pattern', h);
      } else {
        K.cv(c.canvas, 'setStyle', which, 'color', domString(v));
      }
    });
  })(names[i], names[i] === 'fillStyle' ? 'fill' : 'stroke');
})(['fillStyle', 'strokeStyle']);

C2D.save = function save() { K.cv(contextOf(this).canvas, 'save'); };
C2D.restore = function restore() { K.cv(contextOf(this).canvas, 'restore'); };
C2D.reset = function reset() { K.cv(contextOf(this).canvas, 'reset'); };
C2D.isContextLost = function isContextLost() { contextOf(this); return false; };
C2D.getContextAttributes = function getContextAttributes() {
  var o = contextOf(this).options;
  return { alpha: o.alpha, colorSpace: o.colorSpace, colorType: o.colorType, desynchronized: o.desynchronized,
    toneMapping: { mode: 'standard' }, willReadFrequently: o.willReadFrequently };
};

/* ---- transforms ---- */

C2D.scale = function scale(x, y) {
  var c = contextOf(this), what = canvasWhat('scale');
  needArgs(arguments, 2, what);
  K.cv(c.canvas, 'scale', canvasNumber(x, what), canvasNumber(y, what));
};
C2D.rotate = function rotate(angle) {
  var c = contextOf(this), what = canvasWhat('rotate');
  needArgs(arguments, 1, what);
  K.cv(c.canvas, 'rotate', canvasNumber(angle, what));
};
C2D.translate = function translate(x, y) {
  var c = contextOf(this), what = canvasWhat('translate');
  needArgs(arguments, 2, what);
  K.cv(c.canvas, 'translate', canvasNumber(x, what), canvasNumber(y, what));
};
C2D.transform = function transform(a, b, c, d, e, f) {
  var ctx = contextOf(this), what = canvasWhat('transform');
  needArgs(arguments, 6, what);
  K.cv(ctx.canvas, 'transform', canvasNumber(a, what), canvasNumber(b, what), canvasNumber(c, what), canvasNumber(d, what),
    canvasNumber(e, what), canvasNumber(f, what));
};
C2D.setTransform = function setTransform(a, b, c, d, e, f) {
  var ctx = contextOf(this), what = canvasWhat('setTransform');
  if (arguments.length >= 6) {
    K.cv(ctx.canvas, 'setTransform', canvasNumber(a, what), canvasNumber(b, what), canvasNumber(c, what), canvasNumber(d, what),
      canvasNumber(e, what), canvasNumber(f, what));
    return;
  }
  var m = matrix2DOf(a, what);
  K.cv(ctx.canvas, 'setTransform', m[0], m[1], m[2], m[3], m[4], m[5]);
};
C2D.resetTransform = function resetTransform() { K.cv(contextOf(this).canvas, 'resetTransform'); };
C2D.getTransform = function getTransform() {
  var t = K.cv(contextOf(this).canvas, 'getTransform');
  return newMatrix(DOMMatrix, matrix2d(t[0], t[1], t[2], t[3], t[4], t[5]), true);
};

/* ---- line dashes ---- */

C2D.setLineDash = function setLineDash(segments) {
  var c = contextOf(this), what = canvasWhat('setLineDash');
  needArgs(arguments, 1, what);
  var list = idlSequence(segments, function (v) { return canvasNumber(v, what); }, what);
  K.cv(c.canvas, 'setDash', list);
};
C2D.getLineDash = function getLineDash() {
  var d = K.cv(contextOf(this).canvas, 'getDash'), out = [];
  for (var i = 0; i < d.length; i++) ArrayPush(out, d[i]);
  return out;
};

/* ---- paths, on the context and on a Path2D ----
   [host] calls the host for the target with an operation and its numbers. */

function pathMethods(proto, iface, host) {
  function what(name) { return "Failed to execute '" + name + "' on '" + iface + "'"; }
  function numbers(self, name, args, count) {
    var w = what(name);
    needArgs(args, count, w);
    var out = [];
    for (var i = 0; i < count; i++) ArrayPush(out, canvasNumber(args[i], w));
    return out;
  }
  function finite(list) { for (var i = 0; i < list.length; i++) if (!isFinite(list[i])) return false; return true; }
  proto.closePath = function closePath() { host(this, 'closePath', []); };
  proto.moveTo = function moveTo(x, y) { host(this, 'moveTo', numbers(this, 'moveTo', arguments, 2)); };
  proto.lineTo = function lineTo(x, y) { host(this, 'lineTo', numbers(this, 'lineTo', arguments, 2)); };
  proto.quadraticCurveTo = function quadraticCurveTo(cpx, cpy, x, y) {
    host(this, 'quadraticCurveTo', numbers(this, 'quadraticCurveTo', arguments, 4));
  };
  proto.bezierCurveTo = function bezierCurveTo(cp1x, cp1y, cp2x, cp2y, x, y) {
    host(this, 'bezierCurveTo', numbers(this, 'bezierCurveTo', arguments, 6));
  };
  proto.arcTo = function arcTo(x1, y1, x2, y2, radius) {
    var n = numbers(this, 'arcTo', arguments, 5);
    if (finite(n) && n[4] < 0) throw new DOMException(what('arcTo') + ': The radius provided (' + n[4] + ') is negative.', 'IndexSizeError');
    host(this, 'arcTo', n);
  };
  proto.rect = function rect(x, y, w, h) { host(this, 'rect', numbers(this, 'rect', arguments, 4)); };
  proto.roundRect = function roundRect(x, y, w, h, radii) {
    var w0 = what('roundRect'), n = numbers(this, 'roundRect', arguments, 4);
    var list = radiiOf(radii, w0);
    if (!finite(n)) return;
    if (list.length < 1 || list.length > 4) {
      throw new RangeError(w0 + ': ' + list.length + ' radii provided. Between one and four radii are necessary.');
    }
    var flat = [];
    for (var i = 0; i < list.length; i++) {
      var r = list[i];
      if (!isFinite(r[0]) || !isFinite(r[1])) return;
      if (r[2]) {
        if (r[0] < 0) throw new RangeError(w0 + ': X-radius value ' + r[0] + ' is negative.');
        if (r[1] < 0) throw new RangeError(w0 + ': Y-radius value ' + r[1] + ' is negative.');
      } else if (r[0] < 0) {
        throw new RangeError(w0 + ': Radius value ' + r[0] + ' is negative.');
      }
      ArrayPush(flat, r[0]);
      ArrayPush(flat, r[1]);
    }
    ArrayPush(n, flat);
    host(this, 'roundRect', n);
  };
  proto.arc = function arc(x, y, radius, startAngle, endAngle, counterclockwise) {
    var n = numbers(this, 'arc', arguments, 5);
    if (finite(n) && n[2] < 0) throw new DOMException(what('arc') + ': The radius provided (' + n[2] + ') is negative.', 'IndexSizeError');
    ArrayPush(n, !!counterclockwise);
    host(this, 'arc', n);
  };
  proto.ellipse = function ellipse(x, y, radiusX, radiusY, rotation, startAngle, endAngle, counterclockwise) {
    var w0 = what('ellipse'), n = numbers(this, 'ellipse', arguments, 7);
    if (finite(n) && n[2] < 0) throw new DOMException(w0 + ': The major-axis radius provided (' + n[2] + ') is negative.', 'IndexSizeError');
    if (finite(n) && n[3] < 0) throw new DOMException(w0 + ': The minor-axis radius provided (' + n[3] + ') is negative.', 'IndexSizeError');
    ArrayPush(n, !!counterclockwise);
    host(this, 'ellipse', n);
  };
}
/* The radii of roundRect: each a number, or a point whose x and y are the two radii of a corner. */
function radiiOf(radii, what) {
  if (radii === undefined) return [[0, 0, false]];
  function one(v) {
    if (v !== null && typeof v === 'object') {
      var d = geometryDictionary(v, what, 'DOMPointInit');
      var x = memberNumber(d, 'x', what, 'DOMPointInit'), y = memberNumber(d, 'y', what, 'DOMPointInit');
      return [x === undefined ? 0 : x, y === undefined ? 0 : y, true];
    }
    var n = canvasNumber(v, what);
    return [n, n, false];
  }
  if (radii !== null && typeof radii === 'object' && typeof radii[SymbolIterator] === 'function') return idlSequence(radii, one, what);
  return [one(radii)];
}
pathMethods(C2D, 'CanvasRenderingContext2D', function (self, op, n) {
  var c = contextOf(self);
  K.cv(c.canvas, op, n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7]);
});
C2D.beginPath = function beginPath() { K.cv(contextOf(this).canvas, 'beginPath'); };

/* fill, clip and the hit tests take a Path2D first or not at all. */
C2D.fill = function fill(a, b) {
  var c = contextOf(this), what = canvasWhat('fill'), h = pathHandle(a);
  if (h !== undefined) K.cv(c.canvas, 'fill', h, fillRule(b, what));
  else K.cv(c.canvas, 'fill', -1, fillRule(a, what));
};
C2D.clip = function clip(a, b) {
  var c = contextOf(this), what = canvasWhat('clip'), h = pathHandle(a);
  if (h !== undefined) K.cv(c.canvas, 'clip', h, fillRule(b, what));
  else K.cv(c.canvas, 'clip', -1, fillRule(a, what));
};
C2D.stroke = function stroke(path) {
  var c = contextOf(this), h = -1;
  if (arguments.length > 0) {
    h = pathHandle(path);
    if (h === undefined) throw new TypeError(canvasWhat('stroke') + ": parameter 1 is not of type 'Path2D'.");
  }
  K.cv(c.canvas, 'stroke', h);
};
C2D.isPointInPath = function isPointInPath(a, b, c, d) {
  var ctx = contextOf(this), what = canvasWhat('isPointInPath'), h = pathHandle(a);
  if (h !== undefined) {
    needArgs(arguments, 3, what);
    return K.cv(ctx.canvas, 'isPointInPath', h, canvasNumber(b, what), canvasNumber(c, what), fillRule(d, what));
  }
  needArgs(arguments, 2, what);
  return K.cv(ctx.canvas, 'isPointInPath', -1, canvasNumber(a, what), canvasNumber(b, what), fillRule(c, what));
};
C2D.isPointInStroke = function isPointInStroke(a, b, c) {
  var ctx = contextOf(this), what = canvasWhat('isPointInStroke'), h = pathHandle(a);
  if (h !== undefined) {
    needArgs(arguments, 3, what);
    return K.cv(ctx.canvas, 'isPointInStroke', h, canvasNumber(b, what), canvasNumber(c, what));
  }
  needArgs(arguments, 2, what);
  return K.cv(ctx.canvas, 'isPointInStroke', -1, canvasNumber(a, what), canvasNumber(b, what));
};
C2D.drawFocusIfNeeded = function drawFocusIfNeeded(a, b) {
  contextOf(this);
  var el = pathHandle(a) !== undefined ? b : a;
  if (!isNode(el) || !isA(el, Element)) throw new TypeError(canvasWhat('drawFocusIfNeeded') + ": parameter 1 is not of type 'Element'.");
};

/* ---- rectangles, text and images ---- */

(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) {
    C2D[n] = function (x, y, w, h) {
      var c = contextOf(this), what = canvasWhat(n);
      needArgs(arguments, 4, what);
      K.cv(c.canvas, n, canvasNumber(x, what), canvasNumber(y, what), canvasNumber(w, what), canvasNumber(h, what));
    };
    ObjectDefineProperty(C2D[n], 'name', { __proto__: null, value: n, configurable: true });
  })(names[i]);
})(['fillRect', 'strokeRect', 'clearRect']);
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) {
    C2D[n] = function (text, x, y, maxWidth) {
      var c = contextOf(this), what = canvasWhat(n);
      needArgs(arguments, 3, what);
      var t = domString(text), px = canvasNumber(x, what), py = canvasNumber(y, what);
      K.cv(c.canvas, n, t, px, py, maxWidth === undefined ? null : canvasNumber(maxWidth, what));
    };
    ObjectDefineProperty(C2D[n], 'name', { __proto__: null, value: n, configurable: true });
    ObjectDefineProperty(C2D[n], 'length', { __proto__: null, value: 3, configurable: true });
  })(names[i]);
})(['fillText', 'strokeText']);
C2D.measureText = function measureText(text) {
  var c = contextOf(this), what = canvasWhat('measureText');
  needArgs(arguments, 1, what);
  var m = K.cv(c.canvas, 'measureText', domString(text)), o = ObjectCreate(TextMetrics.prototype);
  WeakMapSet(metricsData, o, m);
  return o;
};
C2D.drawImage = function drawImage(image) {
  var c = contextOf(this), what = canvasWhat('drawImage'), n = arguments.length;
  needArgs(arguments, 3, what);
  if (n !== 3 && n !== 5 && n < 9) throw new TypeError(what + ': Overload resolution failed.');
  var id = imageSource(image, what), count = n >= 9 ? 8 : n - 1, v = [];
  for (var i = 1; i <= count; i++) ArrayPush(v, canvasNumber(arguments[i], what));
  canvasAnswer(K.cv(c.canvas, 'drawImage', id, count, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]), what);
};

/* ---- ImageData and the pixels of a canvas, of HTML 4.12.5.1.16 (#610) ----
   Pixels cross to the host as a string with a code unit for each byte, RGBA in rows, and
   float16 pixels as a list of numbers. The host keeps each canvas's pixels premultiplied, as
   Chromium does, and converts between sRGB and Display P3. */

var imageDatas = new WeakMap(), Uint8ClampedArrayCtor = TYPED_ARRAYS.Uint8ClampedArray, Float16ArrayCtor = TYPED_ARRAYS.Float16Array || null;
/* The most bytes an ImageData holds, as a typed array's length is a 32-bit int in Chromium. */
var IMAGE_DATA_LIMIT = 2147483647;
function imageDataOf(v) {
  var d = v !== null && typeof v === 'object' ? WeakMapGet(imageDatas, v) : undefined;
  if (d === undefined) throw new TypeError('Illegal invocation');
  return d;
}
/* An [EnforceRange] long, as getImageData and putImageData take their numbers. */
function enforcedLong(v, what) {
  var x = +v;
  if (x !== x) throw new TypeError(what + ": Value is not of type 'long'.");
  if (x === Infinity || x === -Infinity) throw new TypeError(what + ": Value is infinite and not of type 'long'.");
  x = x < 0 ? MathCeil(x) : MathFloor(x);
  if (x < -2147483648 || x > 2147483647) throw new TypeError(what + ": Value is outside the 'long' value range.");
  return x + 0;
}
/* An ImageDataSettings dictionary, with [colorSpace] when it names none. */
function imageSettings(v, what, colorSpace) {
  var o = geometryDictionary(v, what, 'ImageDataSettings'), cs = colorSpace, pf = 'rgba-unorm8', s;
  if (o !== null && (s = o.colorSpace) !== undefined) {
    cs = domString(s);
    if (cs !== 'srgb' && cs !== 'display-p3') {
      throw new TypeError(what + ": Failed to read the 'colorSpace' property from 'ImageDataSettings': The provided value '" + cs + "' is not a valid enum value of type PredefinedColorSpace.");
    }
  }
  if (o !== null && (s = o.pixelFormat) !== undefined) {
    pf = domString(s);
    if (pf !== 'rgba-unorm8' && (pf !== 'rgba-float16' || Float16ArrayCtor === null)) {
      throw new TypeError(what + ": Failed to read the 'pixelFormat' property from 'ImageDataSettings': The provided value '" + pf + "' is not a valid enum value of type ImageDataPixelFormat.");
    }
  }
  return { __proto__: null, colorSpace: cs, pixelFormat: pf };
}
function newImageData(self, w, h, data, settings) {
  var o = self || ObjectCreate(ImageData.prototype);
  if (data === null) data = settings.pixelFormat === 'rgba-float16' ? new Float16ArrayCtor(w * h * 4) : new Uint8ClampedArrayCtor(w * h * 4);
  WeakMapSet(imageDatas, o, { __proto__: null, width: w, height: h, data: data, colorSpace: settings.colorSpace, pixelFormat: settings.pixelFormat });
  return o;
}
function ImageData(a, b) {
  var what = "Failed to construct 'ImageData'";
  needNew(this, ImageData, 'ImageData', function (p) { return WeakMapHas(imageDatas, p); });
  needArgs(arguments, 2, what);
  // A view of another type picks the overload of a width, which it converts to 0.
  var tag = ArrayBufferIsView(a) ? TypedArrayTag(a) : undefined, w, h, settings;
  if (tag === 'Uint8ClampedArray' || tag === 'Float16Array' || tag === 'Float32Array') {
    var n = TypedArrayLength(a);
    settings = imageSettings(arguments[3], what, 'srgb');
    var wanted = settings.pixelFormat === 'rgba-float16' ? 'Float16Array' : 'Uint8ClampedArray';
    if (tag !== wanted) {
      var format = tag === 'Uint8ClampedArray' ? 'rgba-unorm8' : tag === 'Float16Array' ? 'rgba-float16' : 'rgba-float32';
      throw new DOMException(what + ': ' + tag + ' must use ' + format + ' pixelFormat.', 'InvalidStateError');
    }
    if (n === 0) throw new DOMException(what + ': The input data has zero elements.', 'InvalidStateError');
    if (n % 4 !== 0) throw new DOMException(what + ': The input data length is not a multiple of 4.', 'InvalidStateError');
    w = unsignedLong(b);
    if (w === 0) throw new DOMException(what + ': The source width is zero or not a number.', 'IndexSizeError');
    if ((n / 4) % w !== 0) throw new DOMException(what + ': The input data length is not a multiple of (4 * width).', 'IndexSizeError');
    h = n / 4 / w;
    if (arguments.length > 2) {
      var given = unsignedLong(arguments[2]);
      if (given === 0) throw new DOMException(what + ': The source height is zero or not a number.', 'IndexSizeError');
      if (given !== h) throw new DOMException(what + ': The input data length is not equal to (4 * width * height).', 'IndexSizeError');
    }
    newImageData(this, w, h, a, settings);
    return;
  }
  w = unsignedLong(a);
  h = unsignedLong(b);
  if (w === 0) throw new DOMException(what + ': The source width is zero or not a number.', 'IndexSizeError');
  if (h === 0) throw new DOMException(what + ': The source height is zero or not a number.', 'IndexSizeError');
  if (w * h * 4 > IMAGE_DATA_LIMIT) throw new DOMException(what + ': The requested image size exceeds the supported range.', 'IndexSizeError');
  newImageData(this, w, h, null, imageSettings(arguments[2], what, 'srgb'));
}
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) {
    def(ImageData.prototype, n, function () { return imageDataOf(this)[n]; });
  })(names[i]);
})(['width', 'height', 'data', 'colorSpace', 'pixelFormat']);
defineInterface(ImageData, 'ImageData', null, 2);

/* A width and a height as createImageData and getImageData take them: 0 throws, and a negative
   size counts back from its corner. [zero] is the message for a size of 0. */
function imageDataSize(w, h, what, zero) {
  if (w === 0) throw new DOMException(what + ': The source width is ' + zero + '.', 'IndexSizeError');
  if (h === 0) throw new DOMException(what + ': The source height is ' + zero + '.', 'IndexSizeError');
  w = w < 0 ? -w : w;
  h = h < 0 ? -h : h;
  if (w * h * 4 > IMAGE_DATA_LIMIT) throw new RangeError(what + ': Out of memory at ImageData creation');
  return [w, h];
}
C2D.createImageData = function createImageData(a, b, c) {
  var ctx = contextOf(this), what = canvasWhat('createImageData');
  needArgs(arguments, 1, what);
  if (arguments.length === 1) {
    var d = a !== null && typeof a === 'object' ? WeakMapGet(imageDatas, a) : undefined;
    if (d === undefined) throw new TypeError(what + ": parameter 1 is not of type 'ImageData'.");
    return newImageData(null, d.width, d.height, null, d);
  }
  var size = imageDataSize(enforcedLong(a, what), enforcedLong(b, what), what, 'zero or not a number');
  return newImageData(null, size[0], size[1], null, imageSettings(c, what, ctx.options.colorSpace));
};
C2D.getImageData = function getImageData(sx, sy, sw, sh, settings) {
  var ctx = contextOf(this), what = canvasWhat('getImageData');
  needArgs(arguments, 4, what);
  var x = enforcedLong(sx, what), y = enforcedLong(sy, what), w = enforcedLong(sw, what), h = enforcedLong(sh, what);
  var s = imageSettings(settings, what, ctx.options.colorSpace), size = imageDataSize(w, h, what, '0');
  if (w < 0) x += w;
  if (h < 0) y += h;
  var float = s.pixelFormat === 'rgba-float16', p3 = s.colorSpace === 'display-p3';
  var pixels = K.cv(ctx.canvas, 'getImageData', x, y, size[0], size[1], float, p3);
  var data;
  if (float) {
    data = new Float16ArrayCtor(pixels.length);
    for (var i = 0; i < pixels.length; i++) data[i] = pixels[i];
  } else {
    data = new Uint8ClampedArrayCtor(pixels.length);
    for (var j = 0; j < pixels.length; j++) data[j] = StringCharCodeAt(pixels, j);
  }
  return newImageData(null, size[0], size[1], data, s);
};
C2D.putImageData = function putImageData(imagedata, dx, dy, dirtyX, dirtyY, dirtyWidth, dirtyHeight) {
  var ctx = contextOf(this), what = canvasWhat('putImageData'), n = arguments.length;
  needArgs(arguments, 3, what);
  if (n !== 3 && n < 7) throw new TypeError(what + ': Overload resolution failed.');
  var d = imagedata !== null && typeof imagedata === 'object' ? WeakMapGet(imageDatas, imagedata) : undefined;
  if (d === undefined) throw new TypeError(what + ": parameter 1 is not of type 'ImageData'.");
  var x = enforcedLong(dx, what), y = enforcedLong(dy, what);
  var rx = 0, ry = 0, rw = d.width, rh = d.height;
  if (n >= 7) {
    rx = enforcedLong(dirtyX, what); ry = enforcedLong(dirtyY, what); rw = enforcedLong(dirtyWidth, what); rh = enforcedLong(dirtyHeight, what);
  }
  if (ArrayBufferDetached && ArrayBufferDetached(TypedArrayBuffer(d.data))) {
    throw new DOMException(what + ': The source data has been detached.', 'InvalidStateError');
  }
  if (rw < 0) { rx += rw; rw = -rw; }
  if (rh < 0) { ry += rh; rh = -rh; }
  if (rx < 0) { rw += rx; rx = 0; }
  if (ry < 0) { rh += ry; ry = 0; }
  if (rx + rw > d.width) rw = d.width - rx;
  if (ry + rh > d.height) rh = d.height - ry;
  if (rw <= 0 || rh <= 0) return;
  var float = d.pixelFormat === 'rgba-float16', data = d.data, out, row, k;
  if (float) {
    out = [];
    for (row = 0; row < rh; row++) {
      for (k = ((ry + row) * d.width + rx) * 4; k < ((ry + row) * d.width + rx + rw) * 4; k++) ArrayPush(out, data[k]);
    }
  } else {
    out = '';
    for (row = 0; row < rh; row++) out += byteString(bytesView(data, ((ry + row) * d.width + rx) * 4, ((ry + row) * d.width + rx + rw) * 4));
  }
  K.cv(ctx.canvas, 'putImageData', out, x + rx, y + ry, rw, rh, d.colorSpace === 'display-p3');
};

/* toDataURL and toBlob of a canvas: a PNG, or a JPEG when asked; any other type gives a PNG. */
function canvasId(self) {
  if (!isNode(self) || !isA(self, HTMLCanvasElement)) throw new TypeError('Illegal invocation');
  return idOf(self);
}
function canvasDataUrl(canvas, type, quality) {
  var id = canvasId(canvas);
  return K.cv(id, 'toDataURL', type === undefined ? 'image/png' : domString(type), typeof quality === 'number' ? quality : null);
}
function canvasBlob(canvas, callback, type, quality) {
  var id = canvasId(canvas), what = "Failed to execute 'toBlob' on 'HTMLCanvasElement'";
  if (typeof callback !== 'function') throw new TypeError(what + ": parameter 1 is not of type 'Function'.");
  var file = K.cv(id, 'encode', type === undefined ? 'image/png' : domString(type), typeof quality === 'number' ? quality : null);
  queueTask(function* () {
    var blob = null;
    if (file != null) {
      blob = ObjectCreate(Blob.prototype);
      WeakMapSet(blobs, blob, { bytes: bytesOf(file[1]), type: file[0] });
    }
    callback(blob);
  });
}

/* ---- gradients and patterns ---- */

function CanvasGradient() { illegal('CanvasGradient'); }
CanvasGradient.prototype.addColorStop = function addColorStop(offset, color) {
  var what = "Failed to execute 'addColorStop' on 'CanvasGradient'";
  var h = this !== null && typeof this === 'object' ? WeakMapGet(gradientHandles, this) : undefined;
  if (h === undefined) throw new TypeError('Illegal invocation');
  needArgs(arguments, 2, what);
  var o = webIdlDouble(offset, what), s = domString(color);
  if (o < 0 || o > 1) throw new DOMException(what + ': The provided value (' + o + ') is outside the range (0.0, 1.0).', 'IndexSizeError');
  if (!K.cv(-1, 'stop', h, o, s)) throw new DOMException(what + ": The value provided ('" + s + "') could not be parsed as a color.", 'SyntaxError');
};
function newGradient(kind, list) {
  var g = ObjectCreate(CanvasGradient.prototype);
  WeakMapSet(gradientHandles, g, K.cv(-1, 'gradient', kind, list[0], list[1], list[2], list[3], list[4], list[5]));
  return g;
}
C2D.createLinearGradient = function createLinearGradient(x0, y0, x1, y1) {
  contextOf(this);
  var what = canvasWhat('createLinearGradient');
  needArgs(arguments, 4, what);
  return newGradient('linear', [webIdlDouble(x0, what), webIdlDouble(y0, what), webIdlDouble(x1, what), webIdlDouble(y1, what)]);
};
C2D.createRadialGradient = function createRadialGradient(x0, y0, r0, x1, y1, r1) {
  contextOf(this);
  var what = canvasWhat('createRadialGradient');
  needArgs(arguments, 6, what);
  var v = [webIdlDouble(x0, what), webIdlDouble(y0, what), webIdlDouble(r0, what), webIdlDouble(x1, what), webIdlDouble(y1, what), webIdlDouble(r1, what)];
  if (v[2] < 0) throw new DOMException(what + ': The r0 provided is less than 0.', 'IndexSizeError');
  if (v[5] < 0) throw new DOMException(what + ': The r1 provided is less than 0.', 'IndexSizeError');
  return newGradient('radial', v);
};
C2D.createConicGradient = function createConicGradient(startAngle, x, y) {
  contextOf(this);
  var what = canvasWhat('createConicGradient');
  needArgs(arguments, 3, what);
  return newGradient('conic', [webIdlDouble(startAngle, what), webIdlDouble(x, what), webIdlDouble(y, what)]);
};

function CanvasPattern() { illegal('CanvasPattern'); }
CanvasPattern.prototype.setTransform = function setTransform(transform) {
  var h = this !== null && typeof this === 'object' ? WeakMapGet(patternHandles, this) : undefined;
  if (h === undefined) throw new TypeError('Illegal invocation');
  var m = matrix2DOf(transform, "Failed to execute 'setTransform' on 'CanvasPattern'");
  K.cv(-1, 'patternTransform', h, m[0], m[1], m[2], m[3], m[4], m[5]);
};
C2D.createPattern = function createPattern(image, repetition) {
  var c = contextOf(this), what = canvasWhat('createPattern');
  needArgs(arguments, 2, what);
  var id = imageSource(image, what), r = repetition === null ? '' : domString(repetition);
  if (r !== '' && r !== 'repeat' && r !== 'repeat-x' && r !== 'repeat-y' && r !== 'no-repeat') {
    throw new DOMException(what + ": The provided type ('" + r + "') is not one of 'repeat', 'no-repeat', 'repeat-x', or 'repeat-y'.", 'SyntaxError');
  }
  var h = canvasAnswer(K.cv(c.canvas, 'pattern', id, r), what);
  if (h == null) return null;
  var p = ObjectCreate(CanvasPattern.prototype);
  WeakMapSet(patternHandles, p, h);
  return p;
};

/* ---- TextMetrics ---- */

function TextMetrics() { illegal('TextMetrics'); }
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n, at) {
    if (n === 'emHeightAscent' || n === 'emHeightDescent') return;
    def(TextMetrics.prototype, n, function () {
      var m = this !== null && typeof this === 'object' ? WeakMapGet(metricsData, this) : undefined;
      if (m === undefined) throw new TypeError('Illegal invocation');
      return m[at];
    });
  })(names[i], i);
})(['width', 'actualBoundingBoxLeft', 'actualBoundingBoxRight', 'fontBoundingBoxAscent', 'fontBoundingBoxDescent',
  'actualBoundingBoxAscent', 'actualBoundingBoxDescent', 'emHeightAscent', 'emHeightDescent',
  'hangingBaseline', 'alphabeticBaseline', 'ideographicBaseline']);

/* ---- Path2D ---- */

function Path2D(path) {
  needNew(this, Path2D, 'Path2D', function (p) { return WeakMapHas(pathHandles, p); });
  var from = pathHandle(path), h;
  if (from !== undefined) h = K.cv(-1, 'newPath', from);
  else if (path === undefined) h = K.cv(-1, 'newPath');
  else h = K.cv(-1, 'newPath', domString(path));
  WeakMapSet(pathHandles, this, h);
  if (pathRegistry) RegistryRegister(pathRegistry, this, h);
}
pathMethods(Path2D.prototype, 'Path2D', function (self, op, n) {
  var h = pathHandle(self);
  if (h === undefined) throw new TypeError('Illegal invocation');
  K.cv(-1, op, h, n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7]);
});
Path2D.prototype.addPath = function addPath(path, transform) {
  var h = pathHandle(this), what = "Failed to execute 'addPath' on 'Path2D'";
  if (h === undefined) throw new TypeError('Illegal invocation');
  needArgs(arguments, 1, what);
  var from = pathHandle(path);
  if (from === undefined) throw new TypeError(what + ": parameter 1 is not of type 'Path2D'.");
  var m = matrix2DOf(transform, what);
  K.cv(-1, 'addPath', h, from, m[0], m[1], m[2], m[3], m[4], m[5]);
};

defineInterface(CanvasRenderingContext2D, 'CanvasRenderingContext2D', null, 0);
defineInterface(CanvasGradient, 'CanvasGradient', null, 0);
defineInterface(CanvasPattern, 'CanvasPattern', null, 0);
defineInterface(TextMetrics, 'TextMetrics', null, 0);
defineInterface(Path2D, 'Path2D', null, 0);

/* getContext: one 2D context a canvas keeps for good, and null for any other kind. */
function canvasContext(canvas, kind, options) {
  var id = idOf(canvas), what = "Failed to execute 'getContext' on 'HTMLCanvasElement'";
  var existing = MapGet(canvasContexts, id);
  if (kind !== '2d') return null;
  if (existing) return existing;
  var o = geometryDictionary(options, what, 'CanvasRenderingContext2DSettings');
  var settings = { __proto__: null, alpha: o === null || o.alpha === undefined ? true : !!o.alpha,
    colorSpace: o === null || o.colorSpace === undefined ? 'srgb' : domString(o.colorSpace), colorType: 'unorm8',
    desynchronized: o === null ? false : !!o.desynchronized, willReadFrequently: o === null ? false : !!o.willReadFrequently };
  K.cv(id, 'open');
  var ctx = ObjectCreate(C2D);
  WeakMapSet(contexts, ctx, { __proto__: null, canvas: id, options: settings });
  MapSet(canvasContexts, id, ctx);
  return ctx;
}
"""
