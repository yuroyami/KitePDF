package io.github.yuroyami.kitepdf.epub.script

/**
 * The DOM a chapter's scripts see (#41), written in JavaScript over the host functions that
 * [io.github.yuroyami.kitepdf.epub.EpubScriptSession] defines under `__kite`: each node is a
 * number there, and an object here. It runs before the chapter's own scripts, in the global
 * scope that is their `window`.
 *
 * Written in the JavaScript every engine of the library runs: functions, prototypes and
 * generators, no `class`, no `async`. The source is kept in parts joined at run time, since a
 * JVM class file holds no string constant over 64 KB.
 */
internal val DOM_PRELUDE: String = buildString {
    append(DOM_PRELUDE_HEAD)
    append(DOM_PRELUDE_NODES)
    append(DOM_PRELUDE_COLLECTIONS)
    append(DOM_PRELUDE_URL)
    append(DOM_PRELUDE_ENCODING)
    append(DOM_PRELUDE_FILE)
    append(DOM_PRELUDE_REFLECTION)
    append(DOM_PRELUDE_ELEMENTS)
    append(DOM_PRELUDE_FORMS)
    append(DOM_PRELUDE_TAIL)
}

/**
 * The first part of [DOM_PRELUDE]: the built-ins it takes, its helpers, the interface objects of Web IDL, events and event handlers.
 */
private const val DOM_PRELUDE_HEAD: String = """(function (global) {
/* ---- the built-ins the DOM calls (#540) ----
   A browser's DOM is native code, so a page that patches a built-in changes its own code and not
   the DOM. This DOM is JavaScript in the book's realm, so it takes the built-ins it calls here,
   before any script of the book runs, and calls a method uncurried: ArrayPush(list, x) where a
   script writes list.push(x). The global constructors are shadowed by the values they have now.
   A regular expression keeps its own exec and flags, so the methods that run it find no patch;
   the steps of a generator run through GeneratorNext, not yield*; and an object whose missing
   keys matter, a descriptor, a proxy's handler or a table, has no prototype. The methods that
   build an array through its constructor's species, as slice, filter, map, splice and concat
   do, are not called at all. Below this part the DOM calls a method only of the host's table K,
   and DomPreludeSourceTest reads the source to keep it so. What the engine calls by itself stays
   open to a book: KiteJS takes the prototype of a literal from the global binding of its
   constructor (kitejs#77) and reads a string `this` back through String.prototype.toString
   (kitejs#78). */
var String = global.String, Number = global.Number, Error = global.Error, TypeError = global.TypeError,
  RangeError = global.RangeError, Map = global.Map, WeakMap = global.WeakMap, Proxy = global.Proxy,
  Uint8Array = global.Uint8Array, Promise = global.Promise, Function = global.Function,
  parseInt = global.parseInt, isNaN = global.isNaN, isFinite = global.isFinite;
var uncurry = Function.prototype.bind.bind(Function.prototype.call);
function getter(proto, name) { var d = Object.getOwnPropertyDescriptor(proto, name); return d && d.get ? uncurry(d.get) : null; }
var ReflectApply = Reflect.apply, ReflectOwnKeys = Reflect.ownKeys, ReflectSet = Reflect.set,
  ReflectHas = Reflect.has, ReflectDefineProperty = Reflect.defineProperty, ReflectDeleteProperty = Reflect.deleteProperty;
var ObjectCreate = Object.create, ObjectDefineProperty = Object.defineProperty, ObjectFreeze = Object.freeze,
  ObjectGetOwnPropertyDescriptor = Object.getOwnPropertyDescriptor, ObjectGetPrototypeOf = Object.getPrototypeOf,
  ObjectKeys = Object.keys, ObjectSetPrototypeOf = Object.setPrototypeOf, ObjectHasOwn = uncurry(Object.prototype.hasOwnProperty);
var MathCeil = Math.ceil, MathFloor = Math.floor, MathMax = Math.max, MathMin = Math.min, MathRound = Math.round, MathPow = Math.pow;
var ArrayIndexOf = uncurry(Array.prototype.indexOf), ArrayJoin = uncurry(Array.prototype.join),
  ArrayPush = uncurry(Array.prototype.push), ArrayShift = uncurry(Array.prototype.shift), ArraySort = uncurry(Array.prototype.sort);
/* The iteration methods of Array.prototype, which Web IDL makes the methods of an iterable list such as a NodeList. */
var ArrayValues = Array.prototype[Symbol.iterator], ArrayEntries = Array.prototype.entries, ArrayKeys = Array.prototype.keys,
  ArrayForEach = Array.prototype.forEach;
var StringCharAt = uncurry(String.prototype.charAt), StringCharCodeAt = uncurry(String.prototype.charCodeAt),
  StringFromCharCode = String.fromCharCode, StringIndexOf = uncurry(String.prototype.indexOf),
  StringSubstring = uncurry(String.prototype.substring), StringToLowerCase = uncurry(String.prototype.toLowerCase),
  StringToUpperCase = uncurry(String.prototype.toUpperCase), StringTrim = uncurry(String.prototype.trim);
var RegExpExec = RegExp.prototype.exec, RegExpReplace = uncurry(RegExp.prototype[Symbol.replace]),
  RegExpTest = uncurry(RegExp.prototype.test);
var MapGet = uncurry(Map.prototype.get), MapHas = uncurry(Map.prototype.has), MapSet = uncurry(Map.prototype.set);
var WeakMapGet = uncurry(WeakMap.prototype.get), WeakMapHas = uncurry(WeakMap.prototype.has), WeakMapSet = uncurry(WeakMap.prototype.set);
var TypedArrayPrototype = Object.getPrototypeOf(Uint8Array.prototype);
var TypedArrayBuffer = getter(TypedArrayPrototype, 'buffer'), TypedArrayByteLength = getter(TypedArrayPrototype, 'byteLength'),
  TypedArrayByteOffset = getter(TypedArrayPrototype, 'byteOffset'), TypedArrayLength = getter(TypedArrayPrototype, 'length'),
  TypedArrayTag = getter(TypedArrayPrototype, Symbol.toStringTag), TypedArraySet = uncurry(TypedArrayPrototype.set);
var DataViewBuffer = getter(DataView.prototype, 'buffer'), DataViewByteLength = getter(DataView.prototype, 'byteLength'),
  DataViewByteOffset = getter(DataView.prototype, 'byteOffset');
var ArrayBufferByteLength = getter(ArrayBuffer.prototype, 'byteLength'), ArrayBufferIsView = ArrayBuffer.isView,
  SharedArrayBufferByteLength = typeof SharedArrayBuffer === 'function' ? getter(SharedArrayBuffer.prototype, 'byteLength') : null;
var GeneratorNext = uncurry(Object.getPrototypeOf(Object.getPrototypeOf((function* () {})())).next);
var IteratorPrototype = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
var FunctionHasInstance = uncurry(Function.prototype[Symbol.hasInstance]);
var JSONStringify = JSON.stringify, DateNow = Date.now;
var ErrorCaptureStackTrace = typeof Error.captureStackTrace === 'function' ? Error.captureStackTrace : null;
var SymbolIterator = Symbol.iterator, SymbolToStringTag = typeof Symbol.toStringTag === 'symbol' ? Symbol.toStringTag : null;
/* Whether [v] is an instance of [ctor] by its prototype chain, whatever Symbol.hasInstance of ctor says. */
function isA(v, ctor) { return FunctionHasInstance(ctor, v); }
/* A regular expression that runs on the built-ins taken above, whatever a script does to RegExp.prototype. */
function hardened(re) {
  ObjectDefineProperty(re, 'exec', { __proto__: null, value: RegExpExec });
  ObjectDefineProperty(re, 'flags', { __proto__: null, value: re.flags });
  return re;
}
/* The regular expressions of the DOM. */
var RE_UPPER = hardened(/[A-Z]/g), RE_VENDOR = hardened(/^(webkit|moz|ms|o)-/), RE_VENDOR_ANY = hardened(/^-(webkit|moz|ms|o|epub)-/),
  RE_BARE_FRACTION = hardened(/(^|[\s,(\/+*-])\.(\d)/g), RE_DASHED = hardened(/-([a-z])/g), RE_ERROR_CODE = hardened(/(\w+) (\d+)/g),
  RE_SPACE = hardened(/\s/), RE_IMPORTANT = hardened(/!\s*important$/i), RE_DIGITS = hardened(/^\d+$/),
  RE_PREFIX = hardened(/^.*:/), RE_WHITESPACE_RUN = hardened(/\s+/g), RE_QUOTE = hardened(/"/g), RE_EPUB_SCHEME = hardened(/^epub:\/\//),
  RE_FRAGMENT = hardened(/#.*/), RE_CRLF = hardened(/\r\n?/g), RE_PRINTABLE = hardened(/^[\x20-\x7E]*$/), RE_CAPITAL = hardened(/^[A-Z]/),
  RE_TYPE_NAME = hardened(/^[A-Za-z][A-Za-z0-9-]*$/), RE_ATTRIBUTE_NAME = hardened(/^[^\0\t\n\f\r \/>=]+$/),
  RE_CUSTOM_ELEMENT = hardened(/^[a-z](?:[-.0-9_a-z\u00B7\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u037D\u037F-\u1FFF\u200C\u200D\u203F\u2040\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD]|[\uD800-\uDB7F][\uDC00-\uDFFF])*$/),
  RE_ELEMENT_NAME = hardened(/^(?:[A-Za-z][^\0\t\n\f\r />]*|[:_\u0080-\uFFFF][-.0-9:A-Z_a-z\u0080-\uFFFF]*)$/);
/* The host's functions, in a table of the DOM's own, and the global that held them gone, so a
   script neither replaces nor calls one. */
var K = (function (host) {
  var table = ObjectCreate(null), names = ReflectOwnKeys(host);
  for (var i = 0; i < names.length; i++) table[names[i]] = host[names[i]];
  return ObjectFreeze(table);
})(global.__kite);
delete global.__kite;
/* ---- end of the built-ins ---- */

/* The parts of a list, from [from] on, in a new array, which no species constructor makes. */
function listSlice(list, from) {
  var out = [];
  for (var i = from || 0; i < list.length; i++) ArrayPush(out, list[i]);
  return out;
}
function listFilter(list, keep) {
  var out = [];
  for (var i = 0; i < list.length; i++) if (keep(list[i], i)) ArrayPush(out, list[i]);
  return out;
}
function listMap(list, f) {
  var out = [];
  for (var i = 0; i < list.length; i++) ArrayPush(out, f(list[i], i));
  return out;
}
function listRemoveAt(list, at) {
  for (var i = at + 1; i < list.length; i++) list[i - 1] = list[i];
  list.length--;
}
/* The parts of a string between the occurrences of [separator]. */
function splitOn(s, separator) {
  var out = [], from = 0;
  for (var at = StringIndexOf(s, separator); at >= 0; at = StringIndexOf(s, separator, from)) {
    ArrayPush(out, StringSubstring(s, from, at));
    from = at + separator.length;
  }
  ArrayPush(out, StringSubstring(s, from));
  return out;
}
/* The tokens of a string split on ASCII whitespace, as the DOM's ordered set parser splits them. */
function asciiTokens(s) {
  var out = [], start = -1;
  for (var i = 0; i <= s.length; i++) {
    var c = i < s.length ? StringCharCodeAt(s, i) : 32;
    var space = c === 32 || c === 9 || c === 10 || c === 12 || c === 13;
    if (space && start >= 0) { ArrayPush(out, StringSubstring(s, start, i)); start = -1; }
    else if (!space && start < 0) start = i;
  }
  return out;
}

var wrappers = new Map();
/* The object under each form's proxy. */
var formTargets = new WeakMap();
var rootId = K.root();

function def(proto, name, get, set) {
  ObjectDefineProperty(proto, name, { __proto__: null, get: get, set: set, configurable: true, enumerable: true });
}
function hidden(obj, name, value) {
  ObjectDefineProperty(obj, name, { __proto__: null, value: value, writable: true, configurable: true, enumerable: false });
}
function report(e) {
  try { K.error(e && e.name && e.message !== undefined ? e.name + ': ' + e.message : String(e)); } catch (ignored) {}
}
function kebab(name) {
  if (name === 'cssFloat') return 'float';
  var out = RegExpReplace(RE_UPPER, String(name), function (c) { return '-' + StringToLowerCase(c); });
  if (RegExpTest(RE_VENDOR, out)) out = '-' + out;
  return out;
}
/* The properties a style declaration answers, as a browser's does: any other name is not CSS. */
var CSS_PROPERTIES = ObjectCreate(null);
(function (names) { for (var i = 0; i < names.length; i++) if (names[i]) CSS_PROPERTIES[names[i]] = true; })(splitOn('align-content align-items align-self all animation animation-delay animation-direction ' +
  'animation-duration animation-fill-mode animation-iteration-count animation-name ' +
  'animation-play-state animation-timing-function appearance aspect-ratio backface-visibility ' +
  'background background-attachment background-blend-mode background-clip background-color ' +
  'background-image background-origin background-position background-position-x ' +
  'background-position-y background-repeat background-size block-size border border-block ' +
  'border-block-color border-block-end border-block-end-color border-block-end-style ' +
  'border-block-end-width border-block-start border-block-start-color border-block-start-style ' +
  'border-block-start-width border-block-style border-block-width border-bottom border-bottom-color ' +
  'border-bottom-left-radius border-bottom-right-radius border-bottom-style border-bottom-width ' +
  'border-collapse border-color border-end-end-radius border-end-start-radius border-image ' +
  'border-image-outset border-image-repeat border-image-slice border-image-source ' +
  'border-image-width border-inline border-inline-color border-inline-end border-inline-end-color ' +
  'border-inline-end-style border-inline-end-width border-inline-start border-inline-start-color ' +
  'border-inline-start-style border-inline-start-width border-inline-style border-inline-width ' +
  'border-left border-left-color border-left-style border-left-width border-radius border-right ' +
  'border-right-color border-right-style border-right-width border-spacing border-start-end-radius ' +
  'border-start-start-radius border-style border-top border-top-color border-top-left-radius ' +
  'border-top-right-radius border-top-style border-top-width border-width bottom ' +
  'box-decoration-break box-shadow box-sizing break-after break-before break-inside caption-side ' +
  'caret-color clear clip clip-path clip-rule color color-interpolation color-interpolation-filters ' +
  'color-scheme column-count column-fill column-gap column-rule column-rule-color column-rule-style ' +
  'column-rule-width column-span column-width columns contain content content-visibility ' +
  'counter-increment counter-reset counter-set cursor cx cy d direction display dominant-baseline ' +
  'empty-cells fill fill-opacity fill-rule filter flex flex-basis flex-direction flex-flow ' +
  'flex-grow flex-shrink flex-wrap float flood-color flood-opacity font font-family ' +
  'font-feature-settings font-kerning font-size font-size-adjust font-stretch font-style ' +
  'font-synthesis font-variant font-variant-caps font-variant-east-asian font-variant-ligatures ' +
  'font-variant-numeric font-variation-settings font-weight gap grid grid-area grid-auto-columns ' +
  'grid-auto-flow grid-auto-rows grid-column grid-column-end grid-column-gap grid-column-start ' +
  'grid-gap grid-row grid-row-end grid-row-gap grid-row-start grid-template grid-template-areas ' +
  'grid-template-columns grid-template-rows hanging-punctuation height hyphens image-orientation ' +
  'image-rendering inline-size inset inset-block inset-block-end inset-block-start inset-inline ' +
  'inset-inline-end inset-inline-start isolation justify-content justify-items justify-self left ' +
  'letter-spacing lighting-color line-break line-height list-style list-style-image ' +
  'list-style-position list-style-type margin margin-block margin-block-end margin-block-start ' +
  'margin-bottom margin-inline margin-inline-end margin-inline-start margin-left margin-right ' +
  'margin-top marker marker-end marker-mid marker-start mask mask-clip mask-composite mask-image ' +
  'mask-mode mask-origin mask-position mask-repeat mask-size mask-type max-block-size max-height ' +
  'max-inline-size max-width min-block-size min-height min-inline-size min-width mix-blend-mode ' +
  'object-fit object-position opacity order orphans outline outline-color outline-offset ' +
  'outline-style outline-width overflow overflow-wrap overflow-x overflow-y padding padding-block ' +
  'padding-block-end padding-block-start padding-bottom padding-inline padding-inline-end ' +
  'padding-inline-start padding-left padding-right padding-top page-break-after page-break-before ' +
  'page-break-inside paint-order perspective perspective-origin place-content place-items ' +
  'place-self pointer-events position quotes r resize right rotate row-gap ruby-align ruby-position ' +
  'rx ry scale scroll-behavior shape-image-threshold shape-margin shape-outside shape-rendering ' +
  'stop-color stop-opacity stroke stroke-dasharray stroke-dashoffset stroke-linecap stroke-linejoin ' +
  'stroke-miterlimit stroke-opacity stroke-width tab-size table-layout text-align text-align-last ' +
  'text-anchor text-combine-upright text-decoration text-decoration-color text-decoration-line ' +
  'text-decoration-style text-decoration-thickness text-emphasis text-emphasis-color ' +
  'text-emphasis-position text-emphasis-style text-indent text-justify text-orientation ' +
  'text-overflow text-rendering text-shadow text-transform text-underline-offset ' +
  'text-underline-position top touch-action transform transform-box transform-origin ' +
  'transform-style transition transition-delay transition-duration transition-property ' +
  'transition-timing-function translate unicode-bidi user-select vector-effect vertical-align ' +
  'visibility white-space widows width will-change word-break word-spacing word-wrap writing-mode x ' +
  'y z-index zoom ', ' '));
/* The CSS property a style declaration's key names, or null for a key that names none. */
function cssProperty(key) {
  var name = StringIndexOf(key, '-') >= 0 ? StringToLowerCase(key) : kebab(key);
  var bare = RegExpReplace(RE_VENDOR_ANY, name, '');
  return CSS_PROPERTIES[bare] === true ? name : null;
}
/* A declared value as a browser gives it back: a number keeps its leading zero, so `.5` reads `0.5`. */
function cssValue(value) {
  return RegExpReplace(RE_BARE_FRACTION, String(value), '$10.$2');
}
function camel(name) {
  return RegExpReplace(RE_DASHED, String(name), function (m, c) { return StringToUpperCase(c); });
}
/* A value as a WebIDL DOMString. */
function domString(v) {
  if (typeof v === 'symbol') throw new TypeError('Cannot convert a Symbol value to a string');
  return String(v);
}
/* A string with its ASCII letters lowercased, or uppercased, and no other letter changed, as the
   DOM and HTML change case (Infra, 4.7). */
function asciiCase(s, from, to) {
  var out = '', start = 0;
  for (var i = 0; i < s.length; i++) {
    var c = StringCharCodeAt(s, i);
    if (c >= from && c <= from + 25) {
      out += StringSubstring(s, start, i) + StringFromCharCode(c - from + to);
      start = i + 1;
    }
  }
  return start ? out + StringSubstring(s, start) : s;
}
function asciiLowerCase(s) { return asciiCase(s, 65, 97); }
function asciiUpperCase(s) { return asciiCase(s, 97, 65); }
/* A value as a WebIDL unsigned long, and as a long, of Web IDL, 3.2.4: the number, truncated and
   wrapped around 2 to the 32nd. */
function unsignedLong(v) {
  var x = +v;
  if (x !== x || x === Infinity || x === -Infinity) return 0;
  x = x < 0 ? MathCeil(x) : MathFloor(x);
  x = x % 4294967296;
  return x < 0 ? x + 4294967296 : x + 0;
}
function webIdlLong(v) { var x = unsignedLong(v); return x >= 2147483648 ? x - 4294967296 : x; }
/* A value as a WebIDL double, which no infinity or NaN is. */
function webIdlDouble(v, what) {
  var x = +v;
  if (!isFinite(x)) throw new TypeError(what + ': The provided double value is non-finite.');
  return x;
}
/* Throws, as a call of [what] with fewer than [count] arguments does. */
function needArgs(args, count, what) {
  if (args.length < count) {
    throw new TypeError(what + ': ' + count + ' argument' + (count > 1 ? 's' : '') + ' required, but only ' + args.length + ' present.');
  }
}

/* ---- interface objects, of Web IDL, 3.7 (#538) ----
   An interface is a function named after it, whose prototype property, which a script cannot
   replace, holds the members of the interface and its class string, and whose own prototype is
   the interface it inherits from. Each one is a property of the global that a for-in does not
   see. An interface without a constructor throws when called. */
var INTERFACES = [];
/* Makes [ctor] the interface object [name], which inherits from [parent] when given, and whose
   length is [length], or the count of its parameters. */
function defineInterface(ctor, name, parent, length) {
  var proto = ctor.prototype;
  ObjectDefineProperty(ctor, 'name', { __proto__: null, value: name, configurable: true });
  if (length !== undefined) ObjectDefineProperty(ctor, 'length', { __proto__: null, value: length, configurable: true });
  if (parent) {
    if (ObjectGetPrototypeOf(ctor) !== parent) ObjectSetPrototypeOf(ctor, parent);
    if (ObjectGetPrototypeOf(proto) !== parent.prototype) ObjectSetPrototypeOf(proto, parent.prototype);
  }
  ObjectDefineProperty(proto, 'constructor', { __proto__: null, value: ctor, writable: true, enumerable: false, configurable: true });
  if (SymbolToStringTag) ObjectDefineProperty(proto, SymbolToStringTag, { __proto__: null, value: name, configurable: true });
  ObjectDefineProperty(ctor, 'prototype', { __proto__: null, writable: false, enumerable: false, configurable: false });
  ArrayPush(INTERFACES, { __proto__: null, name: name, ctor: ctor });
  return ctor;
}
/* Makes [proto] the prototype of the interface object [ctor], named [name]. */
function interfaceProto(ctor, proto, name) {
  ctor.prototype = proto;
  defineInterface(ctor, name);
}
/* An interface object without a constructor, whose prototype inherits from [parent]'s. */
function abstractInterface(name, parent) {
  var I = function () { illegal(name); };
  I.prototype = ObjectCreate(parent ? parent.prototype : ObjectGetPrototypeOf({}));
  return I;
}
function illegal(name) { throw new TypeError("Failed to construct '" + name + "': Illegal constructor"); }
/* Throws unless [self] is the object that new made for [ctor], as a constructor called without
   new does; [made] tells an object it made already. */
function needNew(self, ctor, name, made) {
  if (!isA(self, ctor) || self === global || made(self)) {
    throw new TypeError("Failed to construct '" + name + "': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
  }
}
/* The constants [names] of an interface, numbered from [first], on its interface object and its prototype. */
function constants(ctor, names, first) {
  for (var i = 0; i < names.length; i++) {
    if (!names[i]) continue;
    var d = { __proto__: null, value: first + i, writable: false, enumerable: true, configurable: false };
    ObjectDefineProperty(ctor, names[i], d);
    ObjectDefineProperty(ctor.prototype, names[i], d);
  }
}

/* DOMException, of Web IDL, 3.14 (#530). An instance is an Error underneath, so it has what
   the engine gives an Error, and its stack starts where the script made it, as in a browser.
   Its name and message live in a weak map that the getters of the prototype read. A call
   without new is told apart as one whose this is no fresh object of the class. */
var exceptions = new WeakMap();
var DOM_ERROR_CODES = ObjectCreate(null);
RegExpReplace(RE_ERROR_CODE, 'IndexSizeError 1 HierarchyRequestError 3 WrongDocumentError 4 InvalidCharacterError 5 ' +
  'NoModificationAllowedError 7 NotFoundError 8 NotSupportedError 9 InUseAttributeError 10 ' +
  'InvalidStateError 11 SyntaxError 12 InvalidModificationError 13 NamespaceError 14 ' +
  'InvalidAccessError 15 TypeMismatchError 17 SecurityError 18 NetworkError 19 AbortError 20 ' +
  'URLMismatchError 21 QuotaExceededError 22 TimeoutError 23 InvalidNodeTypeError 24 ' +
  'DataCloneError 25', function (m, name, code) { DOM_ERROR_CODES[name] = +code; });
function newException(self, ctor, message, name) {
  var e = new Error();
  ObjectSetPrototypeOf(e, ObjectGetPrototypeOf(self));
  if (ErrorCaptureStackTrace) ErrorCaptureStackTrace(e, ctor);
  WeakMapSet(exceptions, e, { name: name, message: message });
  return e;
}
function DOMException() {
  if (!isA(this, DOMException) || WeakMapHas(exceptions, this)) throw new TypeError("Failed to construct 'DOMException': Please use the 'new' operator.");
  var message = arguments.length > 0 && arguments[0] !== undefined ? domString(arguments[0]) : '';
  var name = arguments.length > 1 && arguments[1] !== undefined ? domString(arguments[1]) : 'Error';
  return newException(this, DOMException, message, name);
}
function exceptionOf(e) {
  var data = WeakMapGet(exceptions, e);
  if (data === undefined) throw new TypeError('Illegal invocation');
  return data;
}
interfaceProto(DOMException, ObjectCreate(Error.prototype), 'DOMException');
def(DOMException.prototype, 'name', function () { return exceptionOf(this).name; });
def(DOMException.prototype, 'message', function () { return exceptionOf(this).message; });
def(DOMException.prototype, 'code', function () { var c = DOM_ERROR_CODES[exceptionOf(this).name]; return c === undefined ? 0 : c; });
(function (names) {
  for (var i = 0; i < names.length; i++) {
    ObjectDefineProperty(DOMException, names[i], { __proto__: null, value: i + 1, enumerable: true });
    ObjectDefineProperty(DOMException.prototype, names[i], { __proto__: null, value: i + 1, enumerable: true });
  }
})(['INDEX_SIZE_ERR', 'DOMSTRING_SIZE_ERR', 'HIERARCHY_REQUEST_ERR', 'WRONG_DOCUMENT_ERR', 'INVALID_CHARACTER_ERR',
  'NO_DATA_ALLOWED_ERR', 'NO_MODIFICATION_ALLOWED_ERR', 'NOT_FOUND_ERR', 'NOT_SUPPORTED_ERR', 'INUSE_ATTRIBUTE_ERR',
  'INVALID_STATE_ERR', 'SYNTAX_ERR', 'INVALID_MODIFICATION_ERR', 'NAMESPACE_ERR', 'INVALID_ACCESS_ERR',
  'VALIDATION_ERR', 'TYPE_MISMATCH_ERR', 'SECURITY_ERR', 'NETWORK_ERR', 'ABORT_ERR', 'URL_MISMATCH_ERR',
  'QUOTA_EXCEEDED_ERR', 'TIMEOUT_ERR', 'INVALID_NODE_TYPE_ERR', 'DATA_CLONE_ERR']);

/* QuotaExceededError, the DOMException that Web IDL derives for a quota (3.14.3), whose name
   gives code 22. */
var quotas = new WeakMap();
/* A member of the options as a WebIDL double, or null when it is missing. */
function quotaMember(options, key) {
  var v = options == null ? undefined : options[key];
  if (v === undefined) return null;
  v = +v;
  if (!isFinite(v)) throw new TypeError("Failed to construct 'QuotaExceededError': The provided " + key + " is not a finite number.");
  return v;
}
function QuotaExceededError() {
  if (!isA(this, QuotaExceededError) || WeakMapHas(exceptions, this)) throw new TypeError("Failed to construct 'QuotaExceededError': Please use the 'new' operator.");
  var message = arguments.length > 0 && arguments[0] !== undefined ? domString(arguments[0]) : '';
  var options = arguments[1];
  if (options != null && typeof options !== 'object' && typeof options !== 'function') {
    throw new TypeError("Failed to construct 'QuotaExceededError': The options are not an object.");
  }
  // The dictionary is converted whole, its members in lexicographic order, before any step runs.
  var quota = quotaMember(options, 'quota');
  var requested = quotaMember(options, 'requested');
  if ((quota !== null && quota < 0) || (requested !== null && requested < 0)) {
    throw new RangeError("Failed to construct 'QuotaExceededError': A quota or a requested amount is negative.");
  }
  if (quota !== null && requested !== null && requested < quota) {
    throw new RangeError("Failed to construct 'QuotaExceededError': The requested amount is less than the quota.");
  }
  var e = newException(this, QuotaExceededError, message, 'QuotaExceededError');
  WeakMapSet(quotas, e, { quota: quota, requested: requested });
  return e;
}
function quotaOf(e) {
  var data = WeakMapGet(quotas, e);
  if (data === undefined) throw new TypeError('Illegal invocation');
  return data;
}
ObjectSetPrototypeOf(QuotaExceededError, DOMException);
interfaceProto(QuotaExceededError, ObjectCreate(DOMException.prototype), 'QuotaExceededError');
def(QuotaExceededError.prototype, 'quota', function () { return quotaOf(this).quota; });
def(QuotaExceededError.prototype, 'requested', function () { return quotaOf(this).requested; });

function check(error, what) {
  if (error != null) throw new DOMException(what + ': ' + error, error);
}

/* A node's id in the host, for a wrapper this DOM made; anything else is no node. */
function isNode(v) {
  return v !== null && typeof v === 'object' && typeof v.__id === 'number' && MapGet(wrappers, v.__id) === v;
}
function idOf(node) {
  if (!isNode(node)) throw new TypeError('parameter is not a Node');
  return node.__id;
}
/* The node of the host's [id], made the first time with the prototype of its interface. A form
   is a proxy, whose controls are its named and indexed properties. */
function wrap(id) {
  if (id == null) return null;
  var w = MapGet(wrappers, id);
  if (w) return w;
  var kind = K.kind(id), proto;
  if (kind === 1) proto = protoFor(id);
  else if (kind === 3) proto = Text.prototype;
  else if (kind === 11) proto = DocumentFragment.prototype;
  else if (id === rootId) proto = HTML_DOCUMENT ? HTMLDocument.prototype : XMLDocument.prototype;
  else { proto = MapGet(documentProtos, id); if (proto === undefined) proto = XMLDocument.prototype; }
  w = ObjectCreate(proto);
  ObjectDefineProperty(w, '__id', { __proto__: null, value: id });
  if (proto === HTMLFormElement.prototype) {
    var target = w;
    w = new Proxy(target, FORM_HANDLER);
    WeakMapSet(formTargets, w, target);
  }
  MapSet(wrappers, id, w);
  return w;
}
function listenersOf(t) {
  if (!t.__listeners) hidden(t, '__listeners', ObjectCreate(null));
  return t.__listeners;
}
/* What the DOM's own algorithms do to the tree, on ids, where a script calls the methods. */
function textNode(data) { return wrap(K.createText(String(data))); }
function insertNode(parentId, node, before, what) { check(K.insert(parentId, idOf(node), before == null ? null : idOf(before)), what); return node; }
function removeNode(parentId, node, what) { check(K.remove(parentId, idOf(node)), what); return node; }
function elementIds(id) {
  var all = K.children(id), out = [];
  if (all) for (var i = 0; i < all.length; i++) if (K.kind(all[i]) === 1) ArrayPush(out, all[i]);
  return out;
}
function queryAll(id, selectors) {
  var found = K.query(id, String(selectors), true);
  if (found == null) throw new DOMException('not a valid selector: ' + selectors, 'SyntaxError');
  return found;
}
function queryFirst(id, selectors) {
  var found = K.query(id, String(selectors), false);
  if (found == null) throw new DOMException('not a valid selector: ' + selectors, 'SyntaxError');
  return found.length ? found[0] : null;
}
function matchesId(id, selectors) {
  var r = K.matches(id, String(selectors));
  if (r == null) throw new DOMException('not a valid selector: ' + selectors, 'SyntaxError');
  return r;
}
/* The element at [id] or its nearest ancestor that matches [selectors], or null. */
function closestId(id, selectors) {
  for (var t = id; t != null && K.kind(t) === 1; t = K.parent(t)) if (matchesId(t, selectors)) return t;
  return null;
}
function attrOf(el, name) { return K.attr(idOf(el), name); }
function tagOf(el) { return K.kind(idOf(el)) === 1 ? K.tag(el.__id) : ''; }

/* ---- events ---- */

function addListener(t, type, fn, capture, once) {
  settleTarget(t);
  var all = listenersOf(t);
  var entries = all[type] || (all[type] = []);
  for (var i = 0; i < entries.length; i++) if (entries[i].fn === fn && entries[i].capture === capture) return;
  ArrayPush(entries, { __proto__: null, fn: fn, handler: null, capture: capture, once: once, removed: false });
}
function removeListener(t, type, fn, capture) {
  var entries = listenersOf(t)[type];
  if (!entries) return;
  for (var i = 0; i < entries.length; i++) {
    if (entries[i].fn === fn && entries[i].capture === capture) { removeEntry(t, type, entries[i]); return; }
  }
}
function removeEntry(t, type, entry) {
  var entries = listenersOf(t)[type], at = entries ? ArrayIndexOf(entries, entry) : -1;
  entry.removed = true;
  if (at >= 0) listRemoveAt(entries, at);
}
/* EventTarget, of the DOM Standard, 2.7, which a script may construct. */
function EventTarget() {
  needNew(this, EventTarget, 'EventTarget', function (t) { return isNode(t) || ObjectHasOwn(t, '__listeners'); });
  hidden(this, '__listeners', ObjectCreate(null));
}
EventTarget.prototype.addEventListener = function (type, fn, options) {
  if (fn == null) return;
  var capture = options === true || !!(options && typeof options === 'object' && options.capture);
  var once = !!(options && typeof options === 'object' && options.once);
  addListener(this, String(type), fn, capture, once);
};
EventTarget.prototype.removeEventListener = function (type, fn, options) {
  var capture = options === true || !!(options && typeof options === 'object' && options.capture);
  removeListener(this, String(type), fn, capture);
};
EventTarget.prototype.dispatchEvent = function (event) {
  if (!isA(event, Event)) throw new TypeError('parameter is not an Event');
  event.isTrusted = false;
  return dispatch(this, event);
};
defineInterface(EventTarget, 'EventTarget');

/* An event, of the DOM Standard, 2.2, whose constructor takes its type and then a dictionary. */
function eventMade(e) { return ObjectHasOwn(e, '__stop'); }
function Event(type, init) {
  needNew(this, Event, 'Event', eventMade);
  needArgs(arguments, 1, "Failed to construct 'Event'");
  init = init || { __proto__: null };
  this.type = String(type);
  this.bubbles = !!init.bubbles;
  this.cancelable = !!init.cancelable;
  this.composed = !!init.composed;
  this.defaultPrevented = false;
  this.target = null;
  this.currentTarget = null;
  this.eventPhase = 0;
  this.isTrusted = false;
  this.timeStamp = K.now();
  hidden(this, '__stop', false);
  hidden(this, '__stopNow', false);
}
constants(Event, ['NONE', 'CAPTURING_PHASE', 'AT_TARGET', 'BUBBLING_PHASE'], 0);
function preventDefaultOf(event) { if (event.cancelable) event.defaultPrevented = true; }
function initEventOf(event, type, bubbles, cancelable) { event.type = String(type); event.bubbles = !!bubbles; event.cancelable = !!cancelable; }
Event.prototype.preventDefault = function () { preventDefaultOf(this); };
Event.prototype.stopPropagation = function () { this.__stop = true; };
Event.prototype.stopImmediatePropagation = function () { this.__stop = true; this.__stopNow = true; };
Event.prototype.initEvent = function (type, bubbles, cancelable) { initEventOf(this, type, bubbles, cancelable); };
Event.prototype.composedPath = function () { var out = []; for (var t = this.target; t; t = parentTarget(t)) ArrayPush(out, t); return out; };
def(Event.prototype, 'srcElement', function () { return this.target; });
def(Event.prototype, 'returnValue', function () { return !this.defaultPrevented; }, function (v) { if (!v) preventDefaultOf(this); });
def(Event.prototype, 'cancelBubble', function () { return this.__stop; }, function (v) { if (v) this.__stop = true; });
defineInterface(Event, 'Event', null, 1);

/* The interface [name] of an event that inherits from [parent], whose dictionary [fill] reads. */
function subEvent(parent, name, fill) {
  var E = function (type, init) {
    needNew(this, E, name, eventMade);
    needArgs(arguments, 1, "Failed to construct '" + name + "'");
    init = init || { __proto__: null };
    ReflectApply(parent, this, [type, init]);
    ReflectApply(fill, this, [init]);
  };
  E.prototype = ObjectCreate(parent.prototype);
  return defineInterface(E, name, parent, 1);
}
var UIEvent = subEvent(Event, 'UIEvent', function (init) { this.view = init.view || null; this.detail = init.detail || 0; });
var MouseEvent = subEvent(UIEvent, 'MouseEvent', function (init) {
  this.clientX = init.clientX || 0; this.clientY = init.clientY || 0;
  this.screenX = init.screenX || 0; this.screenY = init.screenY || 0;
  this.pageX = init.pageX === undefined ? this.clientX : init.pageX; this.pageY = init.pageY === undefined ? this.clientY : init.pageY;
  this.offsetX = init.offsetX || 0; this.offsetY = init.offsetY || 0;
  this.button = init.button || 0; this.buttons = init.buttons || 0;
  this.ctrlKey = !!init.ctrlKey; this.shiftKey = !!init.shiftKey; this.altKey = !!init.altKey; this.metaKey = !!init.metaKey;
  this.relatedTarget = init.relatedTarget || null;
});
MouseEvent.prototype.getModifierState = function () { return false; };
var PointerEvent = subEvent(MouseEvent, 'PointerEvent', function (init) {
  this.pointerId = init.pointerId || 0; this.pointerType = init.pointerType || '';
  this.isPrimary = !!init.isPrimary; this.width = init.width || 1; this.height = init.height || 1; this.pressure = init.pressure || 0;
});
var KeyboardEvent = subEvent(UIEvent, 'KeyboardEvent', function (init) {
  this.key = init.key || ''; this.code = init.code || ''; this.keyCode = init.keyCode || 0; this.which = this.keyCode;
  this.ctrlKey = !!init.ctrlKey; this.shiftKey = !!init.shiftKey; this.altKey = !!init.altKey; this.metaKey = !!init.metaKey;
  this.repeat = !!init.repeat;
});
var FocusEvent = subEvent(UIEvent, 'FocusEvent', function (init) { this.relatedTarget = init.relatedTarget || null; });
var InputEvent = subEvent(UIEvent, 'InputEvent', function (init) { this.data = init.data === undefined ? null : init.data; this.inputType = init.inputType || ''; });
var CustomEvent = subEvent(Event, 'CustomEvent', function (init) { this.detail = init.detail === undefined ? null : init.detail; });
CustomEvent.prototype.initCustomEvent = function (type, bubbles, cancelable, detail) { initEventOf(this, type, bubbles, cancelable); this.detail = detail; };
var TouchEvent = subEvent(UIEvent, 'TouchEvent', function (init) {
  this.touches = init.touches || []; this.targetTouches = init.targetTouches || []; this.changedTouches = init.changedTouches || [];
  this.ctrlKey = !!init.ctrlKey; this.shiftKey = !!init.shiftKey; this.altKey = !!init.altKey; this.metaKey = !!init.metaKey;
});

function parentTarget(t) {
  if (t === global) return null;
  if (t === document) return global;
  if (!isNode(t)) return null;
  var p = K.parent(t.__id);
  return p == null ? null : wrap(p);
}
/* Dispatch, and whatever else runs a script's callbacks for the reading system, is a generator
   that yields after each callback (#535). The host runs what the reading system starts, a tap or
   a task, one callback to a call, so the promise jobs of a callback run before the next one, as
   HTML runs a microtask checkpoint once a callback it invoked returns. A script's own
   dispatchEvent or click() runs the same steps at once, as in a browser, where the jobs of its
   listeners wait until the script is done. One generator runs another's steps by a loop over
   GeneratorNext, where yield* would call the next of the generator prototype (#540). */
function* dispatchSteps(target, event) {
  event.target = target;
  var path = [];
  for (var t = target; t; t = parentTarget(t)) ArrayPush(path, t);
  event.eventPhase = 1;
  for (var i = path.length - 1; i > 0 && !event.__stop; i--) for (var a = invokeSteps(path[i], event, 1); !GeneratorNext(a).done;) yield;
  if (!event.__stop) { event.eventPhase = 2; for (var b = invokeSteps(path[0], event, 2); !GeneratorNext(b).done;) yield; }
  if (event.bubbles) {
    event.eventPhase = 3;
    for (var j = 1; j < path.length && !event.__stop; j++) for (var c = invokeSteps(path[j], event, 3); !GeneratorNext(c).done;) yield;
  }
  event.eventPhase = 0;
  event.currentTarget = null;
  return !event.defaultPrevented;
}
function* invokeSteps(t, event, phase) {
  event.currentTarget = t;
  settleTarget(t);
  var entries = listSlice(listenersOf(t)[event.type] || []);
  for (var i = 0; i < entries.length && !event.__stopNow; i++) {
    var l = entries[i];
    if (l.removed) continue;
    if (phase === 1 && !l.capture) continue;
    if (phase === 3 && l.capture) continue;
    if (l.once) removeListener(t, event.type, l.fn, l.capture);
    if (l.handler !== null) runHandler(t, event); else call(l.fn, t, event);
    yield;
  }
}
/* Runs steps through to their end at once, and answers what they return. */
function drain(steps) {
  for (;;) {
    var r = GeneratorNext(steps);
    if (r.done) return r.value;
  }
}
function dispatch(target, event) { return drain(dispatchSteps(target, event)); }
/* A listener's callback: a function, or an object whose handleEvent is read when the event comes. */
function call(fn, t, event) {
  try {
    if (typeof fn === 'function') return ReflectApply(fn, t, [event]);
    var handle = fn ? fn.handleEvent : undefined;
    if (typeof handle === 'function') return ReflectApply(handle, fn, [event]);
  } catch (e) { report(e); }
  return undefined;
}

/* ---- event handlers ----
   An event handler is an entry of its target's list of event listeners (HTML, 8.1.8.1), at the
   place it took when it was first set: setting it again keeps the place, and setting it to null
   takes it out. Its value is a function, an object, null, or RAW, the code of the content
   attribute of its element, compiled when the handler is read. A content attribute sets its
   handler when it is set, and the markup's attributes when the parser reaches their element: the
   reading system reaches the elements before each script as that script is about to run, and the
   rest before DOMContentLoaded, so a script adds its listeners ahead of the handlers of the
   markup after it (#539). An element a script made has its attributes from the start. */
var GLOBAL_HANDLERS = splitOn('abort animationcancel animationend animationiteration animationstart auxclick beforeinput ' +
  'beforematch beforetoggle blur cancel canplay canplaythrough change click close command contextlost contextmenu ' +
  'contextrestored copy cuechange cut dblclick drag dragend dragenter dragleave dragover dragstart drop durationchange ' +
  'emptied ended error focus formdata gotpointercapture input invalid keydown keypress keyup load loadeddata ' +
  'loadedmetadata loadstart lostpointercapture mousedown mouseenter mouseleave mousemove mouseout mouseover mouseup paste ' +
  'pause play playing pointercancel pointerdown pointerenter pointerleave pointermove pointerout pointerover ' +
  'pointerrawupdate pointerup progress ratechange reset resize scroll scrollend securitypolicyviolation seeked seeking ' +
  'select selectionchange selectstart slotchange stalled submit suspend timeupdate toggle touchcancel touchend touchmove ' +
  'touchstart transitioncancel transitionend transitionrun transitionstart volumechange waiting webkitanimationend ' +
  'webkitanimationiteration webkitanimationstart webkittransitionend wheel', ' ');
var WINDOW_HANDLER_NAMES = 'afterprint beforeprint beforeunload hashchange languagechange message messageerror offline ' +
  'online pagehide pagereveal pageshow pageswap popstate rejectionhandled storage unhandledrejection unload';
var WINDOW_HANDLERS = splitOn(WINDOW_HANDLER_NAMES, ' ');
/* The handlers of a body element that are its window's: the window-reflecting set and WindowEventHandlers. */
var BODY_WINDOW_HANDLERS = splitOn('blur error focus load resize scroll ' + WINDOW_HANDLER_NAMES, ' ');
function nameSet(names) {
  var out = ObjectCreate(null);
  for (var i = 0; i < names.length; i++) out[names[i]] = true;
  return out;
}
var IS_GLOBAL_HANDLER = nameSet(GLOBAL_HANDLERS), BODY_TO_WINDOW = nameSet(BODY_WINDOW_HANDLERS);
var RAW = ObjectFreeze(ObjectCreate(null));
/* The markup's elements with handler attributes, in tree order, and how many of them the parser reached. */
var markup = [], markupReached = 0, pendingIds = ObjectCreate(null), reachedIds = ObjectCreate(null);

/* The target whose handler [type] a content attribute of [el] sets: its window for a window
   handler of a body element, or null when [type] names no handler of an element. */
function handlerTarget(el, type) {
  if (tagOf(el) === 'body' && BODY_TO_WINDOW[type]) return global;
  return IS_GLOBAL_HANDLER[type] ? el : null;
}
function handlerState(t, type, make) {
  var all = t.__handlers;
  if (!all) {
    if (!make) return null;
    all = ObjectCreate(null);
    hidden(t, '__handlers', all);
  }
  if (!all[type] && make) all[type] = { __proto__: null, value: null, source: null, entry: null };
  return all[type] || null;
}
/* Sets the handler [type] of [t] to [value], RAW for the content attribute of [source]; null takes it out of the list. */
function setHandler(t, type, value, source) {
  var st = handlerState(t, type, true);
  st.value = value;
  st.source = source;
  if (value === null) {
    if (st.entry) removeEntry(t, type, st.entry);
    st.entry = null;
  } else if (!st.entry) {
    st.entry = { __proto__: null, fn: null, handler: type, capture: false, once: false, removed: false };
    var all = listenersOf(t);
    ArrayPush(all[type] || (all[type] = []), st.entry);
  }
}
/* The parser reaches [el]: each handler attribute it has sets its handler. */
function reach(el) {
  var id = el.__id;
  reachedIds[id] = true;
  pendingIds[id] = false;
  var names = K.attrNames(id);
  for (var i = 0; i < names.length; i++) handlerAttributeChanged(el, names[i], true);
}
/* Reaches [el], unless it is an element of the markup that the parser has yet to reach. A node
   that is no element has no handler attributes, and is marked reached as it is. */
function settle(el) {
  if (!el || !isNode(el) || reachedIds[el.__id] || pendingIds[el.__id]) return;
  if (K.kind(el.__id) === 1) reach(el); else reachedIds[el.__id] = true;
}
/* Settles what sets the handlers of [t]: the body element for the window. */
function settleTarget(t) { settle(t === global ? bodyOf(rootId) : t); }
/* The parser reaches the first [count] elements of the markup with handler attributes. */
function reachMarkup(count) {
  for (; markupReached < count && markupReached < markup.length; markupReached++) {
    var id = markup[markupReached];
    if (pendingIds[id] && !reachedIds[id]) reach(wrap(id));
  }
}
/* A content attribute [name] of [el] was set, or removed: a handler attribute sets its handler. */
function attributeChanged(el, name, set) {
  // Setting or removing the nonce attribute sets the element's nonce (HTML, 2.6.3), and setting
  // async ends the forced async of a script element a script made (HTML, 4.12.1).
  if (name === 'nonce') WeakMapSet(nonces, el, set ? K.attr(el.__id, 'nonce') : '');
  if (name === 'async' && set) WeakMapSet(forceAsync, el, false);
  handlerAttributeChanged(el, name, set);
}
function handlerAttributeChanged(el, name, set) {
  if (StringSubstring(name, 0, 2) !== 'on') return;
  var type = StringSubstring(name, 2), t = handlerTarget(el, type);
  if (t) setHandler(t, type, set ? RAW : null, el);
}
/* The current value of the handler [type] of [t]: RAW compiles the attribute of its element, in
   the scopes HTML gives it, which for an element's handler are the document, the element's form
   owner and the element, whose properties name variables of the code. */
function handlerValue(t, type) {
  var st = handlerState(t, type, false), source;
  if (st && st.value !== RAW) return st.value;
  if (st) source = st.source;
  else if (t === global) source = BODY_TO_WINDOW[type] ? bodyOf(rootId) : null;
  else source = isNode(t) && K.kind(t.__id) === 1 && handlerTarget(t, type) === t ? t : null;
  if (!source) return null;
  var key = 'on' + type, code = K.attr(source.__id, key);
  if (code == null) return null;
  if (!source.__compiled) hidden(source, '__compiled', ObjectCreate(null));
  var cached = source.__compiled[key];
  if (cached && cached.code === code) return cached.fn;
  var fn = null;
  try {
    if (t === global) {
      fn = type === 'error' ? new Function('event', 'source', 'lineno', 'colno', 'error', code) : new Function('event', code);
    } else {
      var scoped = new Function('__kiteDocument', '__kiteForm', '__kiteScope',
        'with (__kiteDocument) { with (__kiteForm) { with (__kiteScope) { return function (event) {\n' + code + '\n}; } } }');
      fn = ReflectApply(scoped, null, [document, formScope(formOwnerOf(source)), source]);
    }
  } catch (e) { report(e); }
  source.__compiled[key] = { __proto__: null, code: code, fn: fn };
  return fn;
}
/* The value of [key] along the prototype chain of [t], a getter run on [receiver]: OrdinaryGet of
   ECMAScript, 10.1.8.1, for a proxy's get trap. Reflect.get would do, but that of KiteJS 0.2.0
   answers a key that nothing has with an object of its own, not undefined, and runs a getter on
   [t] alone. */
function receiverGet(t, key, receiver) {
  for (var o = t; o !== null; o = ObjectGetPrototypeOf(o)) {
    var d = ObjectGetOwnPropertyDescriptor(o, key);
    if (d === undefined) continue;
    if (ObjectHasOwn(d, 'value')) return d.value;
    return d.get === undefined ? undefined : ReflectApply(d.get, receiver, []);
  }
  return undefined;
}
/* The fields of the property descriptor [desc] that a defineProperty trap is handed, in an object
   of their own: the engine makes it an ordinary object (ECMAScript, 10.5.6), so a get or a value
   that a book adds to Object.prototype would join it on its way to Reflect.defineProperty. */
function ownDescriptor(desc) {
  var d = { __proto__: null };
  if (ObjectHasOwn(desc, 'value')) d.value = desc.value;
  if (ObjectHasOwn(desc, 'writable')) d.writable = desc.writable;
  if (ObjectHasOwn(desc, 'get')) d.get = desc.get;
  if (ObjectHasOwn(desc, 'set')) d.set = desc.set;
  if (ObjectHasOwn(desc, 'enumerable')) d.enumerable = desc.enumerable;
  if (ObjectHasOwn(desc, 'configurable')) d.configurable = desc.configurable;
  return d;
}
/* Sets a property as an assignment on [receiver] does, its target being [t]. */
function receiverSet(t, key, value, receiver) {
  for (var o = t; o !== null; o = ObjectGetPrototypeOf(o)) {
    var d = ObjectGetOwnPropertyDescriptor(o, key);
    if (d === undefined) continue;
    if (ObjectHasOwn(d, 'value')) {
      if (!d.writable) return false;
      break;
    }
    if (d.set === undefined) return false;
    ReflectApply(d.set, receiver, [value]);
    return true;
  }
  return ReflectSet(t, key, value);
}
/* Whether the engine's with statement binds a name by asking the object whether it has it, as
   an object environment record does (ECMAScript, 9.1.1.2.1), and not by reading it: one that reads
   it finds every name in a form's proxy, which answers undefined for a name it lacks. */
var WITH_ASKS_HAS = (function () {
  var probe = new Proxy(ObjectCreate(null), { __proto__: null, get: function () { return 1; } });
  try { return ReflectApply(new Function('probe', 'outer', 'with (probe) { return outer; }'), null, [probe, 0]) === 0; } catch (e) { return false; }
})();
/* The scope of a handler for its element's form owner [form]: the form, whose controls are named
   properties, or its object alone, without them, on an engine whose with reads every name. */
function formScope(form) {
  if (form === null) return ObjectCreate(null);
  return WITH_ASKS_HAS ? form : WeakMapGet(formTargets, form);
}
var FORM_ASSOCIATED = nameSet(['button', 'fieldset', 'img', 'input', 'object', 'output', 'select', 'textarea']);
function formOwnerOf(el) { return FORM_ASSOCIATED[tagOf(el)] ? formOf(el) : null; }
/* Runs the handler of [t] for [event], as the listener HTML makes of a handler: a value that
   cannot be called does nothing, and an answer of false cancels the event. */
function runHandler(t, event) {
  var h = handlerValue(t, event.type), r;
  if (typeof h !== 'function') return;
  try { r = ReflectApply(h, t, [event]); } catch (e) { report(e); return; }
  if (r === false) preventDefaultOf(event);
}
/* The IDL attributes of the handlers [types] on [proto], which are the window's for [toWindow]. */
function defineHandlers(proto, types, toWindow) {
  for (var i = 0; i < types.length; i++) defineHandler(proto, types[i], toWindow);
}
function defineHandler(proto, type, toWindow) {
  def(proto, 'on' + type, function () {
    return handlerValue(toWindow ? global : this, type);
  }, function (v) {
    var t = toWindow ? global : this;
    settleTarget(t);
    // An object is kept, callable or not, and anything else is null ([LegacyTreatNonObjectAsNull]).
    setHandler(t, type, v !== null && (typeof v === 'object' || typeof v === 'function') ? v : null, null);
  });
}

"""

/**
 * The `URL` and `URLSearchParams` of [DOM_PRELUDE], of the WHATWG URL Standard (#520), over
 * [WhatwgUrl]: the host parses and serializes, and the list of a `URLSearchParams` lives here.
 */
private const val DOM_PRELUDE_URL: String = """/* ---- URL and URLSearchParams, of the URL Standard (#520) ---- */

/* A value as a WebIDL USVString: a lone surrogate becomes U+FFFD. */
function usv(v) {
  if (typeof v === 'symbol') throw new TypeError('Cannot convert a Symbol value to a string');
  var s = String(v), out = '', from = 0;
  for (var i = 0; i < s.length; i++) {
    var c = StringCharCodeAt(s, i);
    if (c < 0xD800 || c > 0xDFFF) continue;
    if (c <= 0xDBFF && i + 1 < s.length) {
      var d = StringCharCodeAt(s, i + 1);
      if (d >= 0xDC00 && d <= 0xDFFF) { i++; continue; }
    }
    out += StringSubstring(s, from, i) + '�';
    from = i + 1;
  }
  return from ? out + StringSubstring(s, from) : s;
}
/* A WebIDL sequence, from what the iterator of an object gives, each value converted as it
   comes. [method] is the iterator's method where a union has read it already, as WebIDL reads
   it once; the iterator's next is read once too, and each step's done and value as they come. */
function idlSequence(v, convert, what, method) {
  if (v === null || (typeof v !== 'object' && typeof v !== 'function')) throw new TypeError(what + ': The provided value cannot be converted to a sequence.');
  if (method === undefined) method = v[SymbolIterator];
  if (typeof method !== 'function') throw new TypeError(what + ': The object must have a callable @@iterator property.');
  var it = ReflectApply(method, v, []);
  if (it === null || (typeof it !== 'object' && typeof it !== 'function')) throw new TypeError(what + ': The iterator is not an object.');
  var next = it.next, out = [];
  for (;;) {
    var step = ReflectApply(next, it, []);
    if (step === null || (typeof step !== 'object' && typeof step !== 'function')) throw new TypeError(what + ': The iterator result is not an object.');
    if (step.done) return out;
    ArrayPush(out, convert(step.value));
  }
}
/* A string without its first character when that is [c]. */
function withoutLead(s, c) { return StringCharAt(s, 0) === c ? StringSubstring(s, 1) : s; }
/* The name-value pairs of an application/x-www-form-urlencoded string. */
function formList(text) {
  var flat = text == null || text === '' ? [] : K.formParse(text), out = [];
  for (var i = 0; i + 1 < flat.length; i += 2) ArrayPush(out, [flat[i], flat[i + 1]]);
  return out;
}

/* The URL class. Its record is the host's: __parts holds what the host answers of it, as
   href, origin, protocol, username, password, host, hostname, port, pathname, search, hash,
   then the raw query. */
function parseUrl(url, base) { return K.url(usv(url), base === undefined ? null : usv(base)); }
function initUrl(u, parts) {
  hidden(u, '__parts', parts);
  var params = ObjectCreate(SP);
  hidden(params, '__list', formList(parts[11]));
  hidden(params, '__url', u);
  hidden(u, '__params', params);
  return u;
}
function URL(url, base) {
  if (!isA(this, URL)) throw new TypeError("Failed to construct 'URL': Please use the 'new' operator.");
  needArgs(arguments, 1, "Failed to construct 'URL'");
  var parts = parseUrl(url, base);
  if (parts == null) throw new TypeError("Failed to construct 'URL': Invalid URL");
  initUrl(this, parts);
}
var UP = URL.prototype;
function urlOf(u) {
  if (u == null || typeof u !== 'object' || !u.__parts) throw new TypeError('Illegal invocation');
  return u;
}
function setUrlPart(u, name, value) {
  var v = usv(value), parts = K.urlSet(u.__parts[0], name, v);
  if (parts == null) {
    if (name === 'href') throw new TypeError("Failed to set the 'href' property on 'URL': Invalid URL");
    return;
  }
  u.__parts = parts;
  if (name === 'href') u.__params.__list = formList(parts[11]);
  if (name === 'search') u.__params.__list = v === '' ? [] : formList(withoutLead(v, '?'));
}
function urlPart(name, i) {
  def(UP, name, function () { return urlOf(this).__parts[i]; },
    name === 'origin' ? undefined : function (v) { setUrlPart(urlOf(this), name, v); });
}
(function (names) { for (var i = 0; i < names.length; i++) urlPart(names[i], i); })(
  ['href', 'origin', 'protocol', 'username', 'password', 'host', 'hostname', 'port', 'pathname', 'search', 'hash']);
def(UP, 'searchParams', function () { return urlOf(this).__params; });
UP.toJSON = function () { return urlOf(this).__parts[0]; };
UP.toString = function () { return urlOf(this).__parts[0]; };
URL.canParse = function (url, base) {
  needArgs(arguments, 1, "Failed to execute 'canParse' on 'URL'");
  return parseUrl(url, base) != null;
};
URL.parse = function (url, base) {
  needArgs(arguments, 1, "Failed to execute 'parse' on 'URL'");
  var parts = parseUrl(url, base);
  return parts == null ? null : initUrl(ObjectCreate(UP), parts);
};

/* The URLSearchParams class: a list of name-value pairs that writes itself back to the query
   of the URL it belongs to, if any. */
function paramsOf(p) {
  if (p == null || typeof p !== 'object' || !p.__list) throw new TypeError('Illegal invocation');
  return p;
}
function URLSearchParams(init) {
  var what = "Failed to construct 'URLSearchParams'";
  if (!isA(this, URLSearchParams)) throw new TypeError(what + ": Please use the 'new' operator.");
  var out = [];
  if (init !== null && (typeof init === 'object' || typeof init === 'function')) {
    var method = init[SymbolIterator];
    if (method != null) {
      /* A sequence of sequences, converted whole before the pairs are checked. */
      var pairs = idlSequence(init, function (pair) { return idlSequence(pair, usv, what); }, what, method);
      for (var p = 0; p < pairs.length; p++) {
        if (pairs[p].length !== 2) throw new TypeError(what + ': Sequence initializer must only contain pair elements');
        ArrayPush(out, pairs[p]);
      }
    } else {
      /* A WebIDL record: two keys that are one scalar value string keep the place of the
         first and the value of the last. */
      var keys = ReflectOwnKeys(init), at = new Map();
      for (var i = 0; i < keys.length; i++) {
        var d = ObjectGetOwnPropertyDescriptor(init, keys[i]);
        if (!d || !d.enumerable) continue;
        var key = usv(keys[i]), value = usv(init[keys[i]]);
        if (MapHas(at, key)) out[MapGet(at, key)][1] = value;
        else { MapSet(at, key, out.length); ArrayPush(out, [key, value]); }
      }
    }
  } else if (init !== undefined) {
    out = formList(withoutLead(usv(init), '?'));
  }
  hidden(this, '__list', out);
  hidden(this, '__url', null);
}
var SP = URLSearchParams.prototype;
function serializeParams(pairs) {
  var flat = [];
  for (var i = 0; i < pairs.length; i++) ArrayPush(flat, pairs[i][0], pairs[i][1]);
  return K.formSerialize(flat);
}
/* The update steps: the URL's query becomes the serialized list, or null when that is empty. */
function updateParams(p) {
  var u = p.__url;
  if (!u) return;
  var parts = K.urlSet(u.__parts[0], 'query', serializeParams(p.__list));
  if (parts != null) u.__parts = parts;
}
SP.append = function (name, value) {
  needArgs(arguments, 2, "Failed to execute 'append' on 'URLSearchParams'");
  var p = paramsOf(this);
  ArrayPush(p.__list, [usv(name), usv(value)]);
  updateParams(p);
};
SP['delete'] = function (name, value) {
  needArgs(arguments, 1, "Failed to execute 'delete' on 'URLSearchParams'");
  var p = paramsOf(this), n = usv(name), v = value === undefined ? undefined : usv(value);
  p.__list = listFilter(p.__list, function (e) { return !(e[0] === n && (v === undefined || e[1] === v)); });
  updateParams(p);
};
SP.get = function (name) {
  needArgs(arguments, 1, "Failed to execute 'get' on 'URLSearchParams'");
  var pairs = paramsOf(this).__list, n = usv(name);
  for (var i = 0; i < pairs.length; i++) if (pairs[i][0] === n) return pairs[i][1];
  return null;
};
SP.getAll = function (name) {
  needArgs(arguments, 1, "Failed to execute 'getAll' on 'URLSearchParams'");
  var pairs = paramsOf(this).__list, n = usv(name), out = [];
  for (var i = 0; i < pairs.length; i++) if (pairs[i][0] === n) ArrayPush(out, pairs[i][1]);
  return out;
};
SP.has = function (name, value) {
  needArgs(arguments, 1, "Failed to execute 'has' on 'URLSearchParams'");
  var pairs = paramsOf(this).__list, n = usv(name), v = value === undefined ? undefined : usv(value);
  for (var i = 0; i < pairs.length; i++) if (pairs[i][0] === n && (v === undefined || pairs[i][1] === v)) return true;
  return false;
};
SP.set = function (name, value) {
  needArgs(arguments, 2, "Failed to execute 'set' on 'URLSearchParams'");
  var p = paramsOf(this), n = usv(name), v = usv(value), found = false;
  p.__list = listFilter(p.__list, function (e) {
    if (e[0] !== n) return true;
    if (found) return false;
    found = true;
    e[1] = v;
    return true;
  });
  if (!found) ArrayPush(p.__list, [n, v]);
  updateParams(p);
};
/* A stable sort by name, in code units: an insertion sort, which keeps equal names in place. */
SP.sort = function () {
  var p = paramsOf(this), pairs = listSlice(p.__list);
  for (var i = 1; i < pairs.length; i++) {
    var e = pairs[i], j = i - 1;
    for (; j >= 0 && pairs[j][0] > e[0]; j--) pairs[j + 1] = pairs[j];
    pairs[j + 1] = e;
  }
  p.__list = pairs;
  updateParams(p);
};
SP.toString = function () { return serializeParams(paramsOf(this).__list); };
def(SP, 'size', function () { return paramsOf(this).__list.length; });
SP.forEach = function (callback, thisArg) {
  needArgs(arguments, 1, "Failed to execute 'forEach' on 'URLSearchParams'");
  if (typeof callback !== 'function') throw new TypeError("Failed to execute 'forEach' on 'URLSearchParams': The callback provided as parameter 1 is not a function.");
  var p = paramsOf(this);
  for (var i = 0; i < p.__list.length; i++) ReflectApply(callback, thisArg, [p.__list[i][1], p.__list[i][0], p]);
};
/* The iterators of a pair iterable, live over the list as WebIDL's are. */
function ParamsIterator(p, kind) { hidden(this, '__p', p); hidden(this, '__kind', kind); hidden(this, '__at', 0); }
ParamsIterator.prototype = ObjectCreate(IteratorPrototype);
hidden(ParamsIterator.prototype, 'next', function () {
  var pairs = this.__p.__list;
  if (this.__at >= pairs.length) return { value: undefined, done: true };
  var e = pairs[this.__at++];
  return { value: this.__kind === 'keys' ? e[0] : this.__kind === 'values' ? e[1] : [e[0], e[1]], done: false };
});
SP.entries = function () { return new ParamsIterator(paramsOf(this), 'entries'); };
SP.keys = function () { return new ParamsIterator(paramsOf(this), 'keys'); };
SP.values = function () { return new ParamsIterator(paramsOf(this), 'values'); };
hidden(SP, SymbolIterator, SP.entries);
if (SymbolToStringTag) {
  ObjectDefineProperty(UP, SymbolToStringTag, { __proto__: null, value: 'URL', configurable: true });
  ObjectDefineProperty(SP, SymbolToStringTag, { __proto__: null, value: 'URLSearchParams', configurable: true });
  ObjectDefineProperty(ParamsIterator.prototype, SymbolToStringTag, { __proto__: null, value: 'URLSearchParams Iterator', configurable: true });
}
defineInterface(URL, 'URL', null, 1);
defineInterface(URLSearchParams, 'URLSearchParams', null, 0);
"""

/**
 * The part of [DOM_PRELUDE] for bytes and text: TextEncoder, TextDecoder, atob and btoa.
 */
private const val DOM_PRELUDE_ENCODING: String = """/* ---- TextEncoder and TextDecoder of the Encoding Standard, atob and btoa of HTML (#532) ---- */

/* A WebIDL dictionary argument: undefined and null are empty, and anything else must be an object. */
function idlDictionary(v, what, type) {
  if (v === undefined || v === null) return { __proto__: null };
  if (typeof v !== 'object' && typeof v !== 'function') throw new TypeError(what + ": The provided value is not of type '" + type + "'.");
  return v;
}
/* True when [v] has the internal slots that the byteLength getter [length] reads, as WebIDL
   tells a buffer. */
function isBuffer(v, length) {
  if (!length || v === null || typeof v !== 'object') return false;
  try { length(v); return true; } catch (e) { return false; }
}
/* A Uint8Array over the bytes of a WebIDL AllowSharedBufferSource, or null for any other value.
   A detached buffer holds no bytes. */
function bufferView(v) {
  try {
    if (ArrayBufferIsView(v)) {
      if (TypedArrayTag(v) === undefined) return new Uint8Array(DataViewBuffer(v), DataViewByteOffset(v), DataViewByteLength(v));
      return new Uint8Array(TypedArrayBuffer(v), TypedArrayByteOffset(v), TypedArrayByteLength(v));
    }
    if (isBuffer(v, ArrayBufferByteLength) || isBuffer(v, SharedArrayBufferByteLength)) return new Uint8Array(v);
  } catch (e) {
    return new Uint8Array(0);
  }
  return null;
}
/* The bytes of [view] from [from] to [to] in a Uint8Array of their own. A typed array's slice
   would make it through the species of the view's constructor. */
function bytesCopy(view, from, to) {
  var out = new Uint8Array(to - from);
  TypedArraySet(out, bytesView(view, from, to));
  return out;
}
/* A Uint8Array over the bytes of [view] from [from] to [to], as subarray gives it without the
   species of the view's constructor. */
function bytesView(view, from, to) {
  return new Uint8Array(TypedArrayBuffer(view), TypedArrayByteOffset(view) + from, to - from);
}
/* Bytes as the host takes them: a string with a code unit for each byte. */
function byteString(view) {
  var out = '';
  for (var i = 0, n = TypedArrayLength(view); i < n; i += 8192) {
    var codes = [], end = MathMin(n, i + 8192);
    for (var j = i; j < end; j++) codes[j - i] = view[j];
    out += ReflectApply(StringFromCharCode, null, codes);
  }
  return out;
}
/* The bytes of a string from the host, a code unit for each byte. */
function bytesOf(s) {
  var out = new Uint8Array(s.length);
  for (var i = 0; i < s.length; i++) out[i] = StringCharCodeAt(s, i);
  return out;
}

/* TextDecoder. Its decoder lives in the host, which hands back the state it keeps between two
   calls of a stream; that state, the encoding and the options live in a weak map. */
var textDecoders = new WeakMap();
function textDecoderOf(d) {
  var data = WeakMapGet(textDecoders, d);
  if (data === undefined) throw new TypeError('Illegal invocation');
  return data;
}
function TextDecoder() {
  if (!isA(this, TextDecoder) || WeakMapHas(textDecoders, this)) throw new TypeError("Failed to construct 'TextDecoder': Please use the 'new' operator.");
  var label = arguments.length > 0 && arguments[0] !== undefined ? domString(arguments[0]) : 'utf-8';
  // The members of a dictionary are read in lexicographic order.
  var options = idlDictionary(arguments[1], "Failed to construct 'TextDecoder'", 'TextDecoderOptions');
  var fatal = !!options.fatal;
  var ignoreBOM = !!options.ignoreBOM;
  var encoding = K.encoding(label);
  if (encoding == null || encoding === 'replacement') {
    throw new RangeError("Failed to construct 'TextDecoder': The encoding label provided ('" + label + "') is invalid.");
  }
  WeakMapSet(textDecoders, this, { encoding: encoding, fatal: fatal, ignoreBOM: ignoreBOM, state: null, doNotFlush: false });
}
interfaceProto(TextDecoder, {}, 'TextDecoder');
def(TextDecoder.prototype, 'encoding', function () { return StringToLowerCase(textDecoderOf(this).encoding); });
def(TextDecoder.prototype, 'fatal', function () { return textDecoderOf(this).fatal; });
def(TextDecoder.prototype, 'ignoreBOM', function () { return textDecoderOf(this).ignoreBOM; });
/* The input is converted before the options and its bytes copied after them, as WebIDL and the
   method's steps order it, so a getter of the options that detaches the buffer empties it. */
TextDecoder.prototype.decode = function () {
  var d = textDecoderOf(this), input = arguments[0], view = null;
  if (input !== undefined) {
    view = bufferView(input);
    if (view === null) throw new TypeError("Failed to execute 'decode' on 'TextDecoder': The provided value is not of type '(ArrayBuffer or ArrayBufferView)'.");
  }
  var stream = !!idlDictionary(arguments[1], "Failed to execute 'decode' on 'TextDecoder'", 'TextDecodeOptions').stream;
  if (!d.doNotFlush) d.state = null;
  d.doNotFlush = stream;
  var result = K.decode(d.encoding, d.fatal, d.ignoreBOM, d.state, view === null ? '' : byteString(view), !stream);
  d.state = listSlice(result, 1);
  if (result[0] == null) throw new TypeError("Failed to execute 'decode' on 'TextDecoder': The encoded data was not valid.");
  return result[0];
};

/* TextEncoder, which encodes UTF-8 alone. */
var textEncoders = new WeakMap();
function textEncoderOf(e) {
  if (!WeakMapHas(textEncoders, e)) throw new TypeError('Illegal invocation');
}
function TextEncoder() {
  if (!isA(this, TextEncoder) || WeakMapHas(textEncoders, this)) throw new TypeError("Failed to construct 'TextEncoder': Please use the 'new' operator.");
  WeakMapSet(textEncoders, this, true);
}
interfaceProto(TextEncoder, {}, 'TextEncoder');
def(TextEncoder.prototype, 'encoding', function () { textEncoderOf(this); return 'utf-8'; });
TextEncoder.prototype.encode = function () {
  textEncoderOf(this);
  var input = arguments[0];
  return bytesOf(K.encode(input === undefined ? '' : usv(input)));
};
TextEncoder.prototype.encodeInto = function (source, destination) {
  textEncoderOf(this);
  needArgs(arguments, 2, "Failed to execute 'encodeInto' on 'TextEncoder'");
  var text = usv(source);
  if (TypedArrayTag(destination) !== 'Uint8Array') {
    throw new TypeError("Failed to execute 'encodeInto' on 'TextEncoder': parameter 2 is not of type 'Uint8Array'.");
  }
  var result = K.encodeInto(text, TypedArrayLength(destination)), bytes = result[1];
  for (var i = 0; i < bytes.length; i++) destination[i] = StringCharCodeAt(bytes, i);
  return { read: result[0], written: bytes.length };
};

/* atob and btoa, of the HTML Standard: forgiving base64 between bytes and a string of them. */
function atob(data) {
  needArgs(arguments, 1, "Failed to execute 'atob' on 'Window'");
  var out = K.atob(domString(data));
  if (out == null) throw new DOMException("Failed to execute 'atob' on 'Window': The string to be decoded is not correctly encoded.", 'InvalidCharacterError');
  return out;
}
function btoa(data) {
  needArgs(arguments, 1, "Failed to execute 'btoa' on 'Window'");
  var out = K.btoa(domString(data));
  if (out == null) throw new DOMException("Failed to execute 'btoa' on 'Window': The string to be encoded contains characters outside of the Latin1 range.", 'InvalidCharacterError');
  return out;
}
"""

/**
 * The part of [DOM_PRELUDE] for files: Blob, File, FileReader, ProgressEvent and blob URLs.
 */
private const val DOM_PRELUDE_FILE: String = """/* ---- Blob, File, FileReader and blob URLs, of the File API (#533) ---- */

/* A blob's bytes and type live in a weak map that the methods read, so no script reaches them,
   and a blob never changes; a file's name and time live in another. */
var blobs = new WeakMap();
var files = new WeakMap();
function blobOf(b, what) {
  var data = WeakMapGet(blobs, b);
  if (data === undefined) throw new TypeError(what ? what + ": parameter 1 is not of type 'Blob'." : 'Illegal invocation');
  return data;
}
/* A BlobPart, a union of a buffer or a view, a Blob and a USVString: the bytes of a blob or a
   view over those of a buffer, which the constructor copies once every argument is converted,
   or else a string. */
function blobPart(v) {
  if (v !== null && typeof v === 'object') {
    var data = WeakMapGet(blobs, v);
    if (data !== undefined) return data.bytes;
    if (isBuffer(v, ArrayBufferByteLength) || ArrayBufferIsView(v)) return bufferView(v);
  }
  return usv(v);
}
/* The members of a BlobPropertyBag, in the lexicographic order WebIDL reads them in. */
function blobOptions(v, what) {
  var bag = idlDictionary(v, what, 'BlobPropertyBag');
  var endings = bag.endings;
  endings = endings === undefined ? 'transparent' : domString(endings);
  if (endings !== 'transparent' && endings !== 'native') {
    throw new TypeError(what + ": The provided value '" + endings + "' is not a valid enum value of type EndingType.");
  }
  var type = bag.type;
  return { bag: bag, endings: endings, type: type === undefined ? '' : domString(type) };
}
/* A blob's type: printable ASCII in lower case, or else empty. */
function blobType(t) {
  return RegExpTest(RE_PRINTABLE, t) ? StringToLowerCase(t) : '';
}
/* The bytes of the parts, joined: a string as UTF-8, its line breaks made the platform's own
   for endings "native". The reading system is its own platform, a line feed on every host. */
function joinParts(parts, endings) {
  var chunks = [], total = 0;
  for (var i = 0; i < parts.length; i++) {
    var p = parts[i];
    if (typeof p === 'string') p = bytesOf(K.encode(endings === 'native' ? RegExpReplace(RE_CRLF, p, '\n') : p));
    ArrayPush(chunks, p);
    total += TypedArrayLength(p);
  }
  var out = new Uint8Array(total), at = 0;
  for (var j = 0; j < chunks.length; j++) { TypedArraySet(out, chunks[j], at); at += TypedArrayLength(chunks[j]); }
  return out;
}
/* The whole of [bytes] in an ArrayBuffer of its own. */
function bufferOf(bytes) { return TypedArrayBuffer(bytesCopy(bytes, 0, TypedArrayLength(bytes))); }
/* A WebIDL [Clamp] long long: NaN is 0, and the rest is clamped and rounds half to even. */
function clampLongLong(v) {
  var x = +v;
  if (x !== x) return 0;
  x = MathMin(MathMax(x, -9223372036854775808), 9223372036854775807);
  var f = MathFloor(x), d = x - f;
  return d > 0.5 || (d === 0.5 && f % 2 !== 0) ? f + 1 : f;
}
/* A WebIDL long long: NaN and the infinities are 0, and the rest truncates toward zero and wraps. */
function longLong(v) {
  var x = +v;
  if (x !== x || x === Infinity || x === -Infinity) return 0;
  x = (x < 0 ? MathCeil(x) : MathFloor(x)) % 18446744073709551616;
  if (x >= 9223372036854775808) x -= 18446744073709551616;
  else if (x < -9223372036854775808) x += 18446744073709551616;
  return x;
}

function Blob() {
  if (!isA(this, Blob) || WeakMapHas(blobs, this)) {
    throw new TypeError("Failed to construct 'Blob': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
  }
  var what = "Failed to construct 'Blob'";
  var parts = arguments.length > 0 && arguments[0] !== undefined ? idlSequence(arguments[0], blobPart, what) : [];
  var options = blobOptions(arguments[1], what);
  WeakMapSet(blobs, this, { bytes: joinParts(parts, options.endings), type: blobType(options.type) });
}
interfaceProto(Blob, {}, 'Blob');
def(Blob.prototype, 'size', function () { return TypedArrayLength(blobOf(this).bytes); });
def(Blob.prototype, 'type', function () { return blobOf(this).type; });
Blob.prototype.slice = function (start, end, contentType) {
  var data = blobOf(this), size = TypedArrayLength(data.bytes);
  var from = start === undefined ? 0 : clampLongLong(start);
  var to = end === undefined ? size : clampLongLong(end);
  var type = contentType === undefined ? '' : blobType(domString(contentType));
  from = from < 0 ? MathMax(size + from, 0) : MathMin(from, size);
  to = to < 0 ? MathMax(size + to, 0) : MathMin(to, size);
  var out = ObjectCreate(Blob.prototype);
  WeakMapSet(blobs, out, { bytes: bytesView(data.bytes, from, MathMax(from, to)), type: type });
  return out;
};
/* What text(), arrayBuffer() and bytes() answer: a promise that a task of the event loop
   fulfils, as a browser reads the blob's stream in parallel and queues a task with its bytes. */
function readBlob(self, packageBytes) {
  var data;
  try { data = blobOf(self); } catch (e) { return rejected(e); }
  return new Promise(function (resolve) {
    queueTask(function* () { resolve(packageBytes(data.bytes)); });
  });
}
function utf8Text(bytes) { return K.decode('UTF-8', false, false, null, byteString(bytes), true)[0]; }
Blob.prototype.text = function () { return readBlob(this, utf8Text); };
Blob.prototype.arrayBuffer = function () { return readBlob(this, bufferOf); };
Blob.prototype.bytes = function () { return readBlob(this, function (bytes) { return bytesCopy(bytes, 0, TypedArrayLength(bytes)); }); };

/* File: a Blob with a name and a time. Its options read lastModified after the members of a
   BlobPropertyBag, as WebIDL reads an inherited dictionary's members first. */
function File(fileBits, fileName) {
  if (!isA(this, File) || WeakMapHas(blobs, this)) {
    throw new TypeError("Failed to construct 'File': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
  }
  var what = "Failed to construct 'File'";
  needArgs(arguments, 2, what);
  var parts = idlSequence(fileBits, blobPart, what);
  var name = usv(fileName);
  var options = blobOptions(arguments[2], what);
  var lastModified = options.bag.lastModified;
  lastModified = lastModified === undefined ? DateNow() : longLong(lastModified);
  WeakMapSet(blobs, this, { bytes: joinParts(parts, options.endings), type: blobType(options.type) });
  WeakMapSet(files, this, { name: name, lastModified: lastModified });
}
function fileOf(f) {
  var data = WeakMapGet(files, f);
  if (data === undefined) throw new TypeError('Illegal invocation');
  return data;
}
ObjectSetPrototypeOf(File, Blob);
interfaceProto(File, ObjectCreate(Blob.prototype), 'File');
def(File.prototype, 'name', function () { return fileOf(this).name; });
def(File.prototype, 'lastModified', function () { return fileOf(this).lastModified; });
def(File.prototype, 'webkitRelativePath', function () { fileOf(this); return ''; });

/* ProgressEvent, of XMLHttpRequest, which a FileReader fires. */
function progressNumber(v) {
  if (v === undefined) return 0;
  var x = +v;
  if (x !== x || x === Infinity || x === -Infinity) throw new TypeError('The provided double value is non-finite.');
  return x;
}
var ProgressEvent = subEvent(Event, 'ProgressEvent', function (init) {
  this.lengthComputable = !!init.lengthComputable;
  this.loaded = progressNumber(init.loaded);
  this.total = progressNumber(init.total);
});

/* FileReader. Its state lives in a weak map. A read queues each of its events in a task of its
   own, as the File API reads a blob's stream in parallel: loadstart, then progress once the
   bytes are read, where there are any, then load and loadend. abort() takes the read's tasks
   that have not run off the queue. */
var readers = new WeakMap();
function readerOf(r) {
  var s = WeakMapGet(readers, r);
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function FileReader() {
  if (!isA(this, FileReader) || WeakMapHas(readers, this)) {
    throw new TypeError("Failed to construct 'FileReader': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
  }
  WeakMapSet(readers, this, { state: 0, result: null, error: null, read: 0, loaded: 0, total: 0 });
}
ObjectSetPrototypeOf(FileReader, EventTarget);
interfaceProto(FileReader, ObjectCreate(EventTarget.prototype), 'FileReader');
(function (names) {
  for (var i = 0; i < names.length; i++) {
    ObjectDefineProperty(FileReader, names[i], { __proto__: null, value: i, enumerable: true });
    ObjectDefineProperty(FileReader.prototype, names[i], { __proto__: null, value: i, enumerable: true });
  }
})(['EMPTY', 'LOADING', 'DONE']);
def(FileReader.prototype, 'readyState', function () { return readerOf(this).state; });
def(FileReader.prototype, 'result', function () { return readerOf(this).result; });
def(FileReader.prototype, 'error', function () { return readerOf(this).error; });
defineHandlers(FileReader.prototype, ['loadstart', 'progress', 'load', 'abort', 'error', 'loadend']);
function progressOf(type, s) {
  var e = new ProgressEvent(type, { __proto__: null, lengthComputable: true, loaded: s.loaded, total: s.total });
  e.isTrusted = true;
  return e;
}
/* The result of a read: the bytes as the method packages them. */
function packageData(data, kind, label) {
  var bytes = data.bytes;
  if (kind === 'buffer') return bufferOf(bytes);
  if (kind === 'binary') return byteString(bytes);
  if (kind === 'text') return K.blobText(byteString(bytes), label === undefined ? null : label, data.type);
  return 'data:' + (data.type || 'application/octet-stream') + ';base64,' + K.btoa(byteString(bytes));
}
function startRead(reader, args, kind, method) {
  var s = readerOf(reader);
  var what = "Failed to execute '" + method + "' on 'FileReader'";
  needArgs(args, 1, what);
  var data = blobOf(args[0], what);
  var label = kind === 'text' && args[1] !== undefined ? domString(args[1]) : undefined;
  if (s.state === 1) throw new DOMException(what + ': The object is already busy reading Blobs.', 'InvalidStateError');
  s.state = 1;
  s.result = null;
  s.error = null;
  s.loaded = 0;
  s.total = TypedArrayLength(data.bytes);
  var read = ++s.read;
  function task(steps) {
    var t = function* () { if (s.read === read) for (var g = steps(); !GeneratorNext(g).done;) yield; };
    t.owner = s;
    queueTask(t);
  }
  function fire(type) { return dispatchSteps(reader, progressOf(type, s)); }
  task(function* () { for (var g = fire('loadstart'); !GeneratorNext(g).done;) yield; });
  if (s.total > 0) task(function* () { s.loaded = s.total; for (var g = fire('progress'); !GeneratorNext(g).done;) yield; });
  task(function* () {
    s.loaded = s.total;
    s.state = 2;
    s.result = packageData(data, kind, label);
    for (var g = fire('load'); !GeneratorNext(g).done;) yield;
    if (s.state !== 1) for (var h = fire('loadend'); !GeneratorNext(h).done;) yield;
  });
}
FileReader.prototype.readAsArrayBuffer = function (blob) { startRead(this, arguments, 'buffer', 'readAsArrayBuffer'); };
FileReader.prototype.readAsBinaryString = function (blob) { startRead(this, arguments, 'binary', 'readAsBinaryString'); };
FileReader.prototype.readAsText = function (blob) { startRead(this, arguments, 'text', 'readAsText'); };
FileReader.prototype.readAsDataURL = function (blob) { startRead(this, arguments, 'dataurl', 'readAsDataURL'); };
FileReader.prototype.abort = function () {
  var s = readerOf(this);
  if (s.state !== 1) {
    s.result = null;
    return;
  }
  s.state = 2;
  s.result = null;
  s.read++;
  dropTasks(s);
  dispatch(this, progressOf('abort', s));
  if (s.state !== 1) dispatch(this, progressOf('loadend', s));
};

/* Blob URLs. The store is the book's, on the host, so the reader loads a blob URL that an image,
   a style sheet or a font names as it loads a file of the book. */
URL.createObjectURL = function (obj) {
  var what = "Failed to execute 'createObjectURL' on 'URL'";
  needArgs(arguments, 1, what);
  var data = blobOf(obj, what);
  return K.blobUrl(byteString(data.bytes), data.type);
};
URL.revokeObjectURL = function (url) {
  needArgs(arguments, 1, "Failed to execute 'revokeObjectURL' on 'URL'");
  K.revokeBlobUrl(usv(url));
};
"""

/**
 * The rest of [DOM_PRELUDE]: activation, the document, the window and what the host calls.
 */
private const val DOM_PRELUDE_TAIL: String = """/* ---- activation: what a click does once its listeners ran ---- */

function fireEvent(target, event) { return dispatch(target, event); }
function control(el) {
  for (var t = el.__id; t != null && K.kind(t) === 1; t = K.parent(t)) {
    var tag = K.tag(t);
    if (tag === 'input' || tag === 'button' || tag === 'select' || tag === 'textarea' || tag === 'summary') return wrap(t);
    if (tag === 'label') return wrap(t);
    if (tag === 'a' && K.attr(t, 'href') != null) return wrap(t);
  }
  return null;
}
/**
 * A click on [target], with the activation of the control it lands in: a check box or a radio
 * button changes before the listeners run and changes back when one cancels the click, then
 * reports `input` and `change`; a label clicks its control; a summary opens its details; a
 * submit button submits its form. A link opens only for a [synthetic] click, since the viewer
 * follows the links of a reader's tap itself. The steps read the controls' state and run the
 * DOM's own click, as a browser's activation behavior does, whatever a script made of the
 * properties and methods of the elements.
 */
function* activateSteps(target, click, trusted) {
  var c = control(target), tag = c ? K.tag(c.__id) : '';
  if ((tag === 'input' || tag === 'button' || tag === 'select' || tag === 'textarea') && disabledOf(c)) return false;
  var undo = null;
  if (tag === 'input' && (inputType(c) === 'checkbox' || inputType(c) === 'radio')) {
    var was = checkedOf(c), group = [];
    if (inputType(c) === 'radio') {
      group = radioGroup(c);
      setChecked(c, true);
    } else {
      setChecked(c, !was);
    }
    undo = function () { setChecked(c, was); for (var j = 0; j < group.length; j++) setChecked(group[j], true); };
  }
  click.isTrusted = trusted;
  var g = dispatchSteps(target, click), r;
  while (!(r = GeneratorNext(g)).done) yield;
  if (!r.value) { if (undo) undo(); return true; }
  if (undo) {
    for (var a = dispatchSteps(c, new InputEvent('input', { __proto__: null, bubbles: true })); !GeneratorNext(a).done;) yield;
    for (var b = dispatchSteps(c, new Event('change', { __proto__: null, bubbles: true })); !GeneratorNext(b).done;) yield;
  } else if (tag === 'label') {
    var labelled = labelControl(c);
    if (labelled && labelled !== target && !isInclusiveAncestor(labelled.__id, target.__id)) {
      for (var l = clickSteps(labelled); !GeneratorNext(l).done;) yield;
    }
  } else if (tag === 'summary') {
    var details = K.parent(c.__id);
    if (details != null && K.kind(details) === 1 && K.tag(details) === 'details' && queryFirst(details, 'summary') === c.__id) {
      if (K.attr(details, 'open') != null) K.removeAttr(details, 'open'); else K.setAttr(details, 'open', '');
      for (var t = dispatchSteps(wrap(details), new Event('toggle')); !GeneratorNext(t).done;) yield;
    }
  } else if (tag === 'button' && formOf(c) && buttonType(c) === 'submit') {
    for (var s = dispatchSteps(formOf(c), new Event('submit', { __proto__: null, bubbles: true, cancelable: true })); !GeneratorNext(s).done;) yield;
  } else if (tag === 'button' && formOf(c) && buttonType(c) === 'reset') {
    for (var e = resetSteps(formOf(c)); !GeneratorNext(e).done;) yield;
  } else if (tag === 'a' && !trusted) {
    K.navigate(K.attr(c.__id, 'href'));
  }
  return false;
}
function activate(target, click, trusted) { return drain(activateSteps(target, click, trusted)); }

/* ---- the document ---- */

/* Document, of the DOM Standard, 4.5, which a script may construct, and XMLDocument and
   HTMLDocument, which it may not. The chapter is an HTMLDocument when it was served as text/html
   and an XMLDocument when served as XHTML, as a browser makes them (#541). */
var documentProtos = new Map();
/* Records the document [id] that a script made, of the interface whose prototype is [proto]. */
function madeDocument(id, proto, html, contentType) {
  MapSet(documentProtos, id, proto);
  MapSet(madeDocuments, id, { __proto__: null, html: html, contentType: contentType });
  madeDocumentCount++;
  return wrap(id);
}
function Document() {
  needNew(this, Document, 'Document', isNode);
  return madeDocument(K.createDocument(), ObjectGetPrototypeOf(this), false, 'application/xml');
}
Document.prototype = ObjectCreate(Node.prototype);
ParentNode(Document.prototype);
defineHandlers(Document.prototype, GLOBAL_HANDLERS);
defineHandlers(Document.prototype, ['readystatechange', 'visibilitychange']);
/* The first child of [parent] that is an HTML element named [local], as an id, or null. */
function childByTag(parent, local) {
  if (parent == null) return null;
  var c = elementIds(parent);
  for (var i = 0; i < c.length; i++) if (isHtml(c[i], local)) return c[i];
  return null;
}
function documentElementId(doc) { var c = elementIds(doc); return c.length ? c[0] : null; }
/* The html element of a document, as an id: its document element when that is one. */
function htmlElementOf(doc) { var e = documentElementId(doc); return e != null && isHtml(e, 'html') ? e : null; }
function headOf(doc) { return wrap(childByTag(htmlElementOf(doc), 'head')); }
function bodyOf(doc) {
  var html = htmlElementOf(doc), c = html == null ? [] : elementIds(html);
  for (var i = 0; i < c.length; i++) if (isHtml(c[i], 'body') || isHtml(c[i], 'frameset')) return wrap(c[i]);
  return null;
}
/* The node [id] that the document [docId] made, which is that document's until a tree holds it. */
function madeBy(docId, id) {
  if (docId !== rootId) MapSet(nodeDocuments, id, docId);
  var node = wrap(id);
  // A script element that the parser did not make runs as if async until its async is set (HTML, 4.12.1).
  if (K.kind(id) === 1 && isHtml(id, 'script')) WeakMapSet(forceAsync, node, true);
  return node;
}
def(Document.prototype, 'documentElement', function () { return wrap(documentElementId(idOf(this))); });
def(Document.prototype, 'head', function () { return headOf(idOf(this)); });
def(Document.prototype, 'body', function () { return bodyOf(idOf(this)); });
def(Document.prototype, 'title', function () {
  var t = queryFirst(idOf(this), 'title');
  return t == null ? '' : StringTrim(RegExpReplace(RE_WHITESPACE_RUN, K.text(t), ' '));
}, function (v) {
  var id = idOf(this), t = queryFirst(id, 'title');
  if (t == null) {
    var h = childByTag(htmlElementOf(id), 'head');
    if (h == null) return;
    t = htmlElementId('title');
    check(K.insert(h, t, null), 'title');
  }
  K.setText(t, domString(v));
});
def(Document.prototype, 'dir', function () {
  var html = htmlElementOf(idOf(this));
  return html == null ? '' : enumState(ENUMS.dir, K.attr(html, 'dir'));
}, function (v) { var html = htmlElementOf(idOf(this)); if (html != null) setAttr(wrap(html), 'dir', domString(v)); });
/* The colors of the body element that a document reflects, of HTML, 16.3.6. */
(function (pairs) {
  for (var i = 0; i < pairs.length; i += 2) (function (prop, attr) {
    def(Document.prototype, prop, function () {
      var body = bodyOf(idOf(this)), v = body === null || !isHtml(body.__id, 'body') ? null : K.attr(body.__id, attr);
      return v == null ? '' : v;
    }, function (v) {
      var body = bodyOf(idOf(this));
      if (body !== null && isHtml(body.__id, 'body')) setAttr(body, attr, v === null ? '' : domString(v));
    });
  })(pairs[i], pairs[i + 1]);
})(['fgColor', 'text', 'linkColor', 'link', 'vlinkColor', 'vlink', 'alinkColor', 'alink', 'bgColor', 'bgcolor']);
def(Document.prototype, 'readyState', function () { return this.__ready || 'loading'; });
def(Document.prototype, 'defaultView', function () { return idOf(this) === rootId ? global : null; });
def(Document.prototype, 'location', function () { return idOf(this) === rootId ? location : null; }, function (v) { if (idOf(this) === rootId) navigate(v); });
def(Document.prototype, 'URL', function () { return idOf(this) === rootId ? K.location() : 'about:blank'; });
def(Document.prototype, 'documentURI', function () { return idOf(this) === rootId ? K.location() : 'about:blank'; });
def(Document.prototype, 'domain', function () { idOf(this); return originHost; }, function () { idOf(this); });
def(Document.prototype, 'referrer', function () { idOf(this); return ''; });
def(Document.prototype, 'lastModified', function () { idOf(this); return '01/01/1970 00:00:00'; });
function utf8() { idOf(this); return 'UTF-8'; }
def(Document.prototype, 'characterSet', utf8);
def(Document.prototype, 'charset', utf8);
def(Document.prototype, 'inputEncoding', utf8);
def(Document.prototype, 'contentType', function () { return documentContentType(idOf(this)); });
def(Document.prototype, 'compatMode', function () { idOf(this); return 'CSS1Compat'; });
def(Document.prototype, 'designMode', function () { idOf(this); return 'off'; }, function () { idOf(this); });
def(Document.prototype, 'visibilityState', function () { idOf(this); return 'visible'; });
def(Document.prototype, 'hidden', function () { idOf(this); return false; });
def(Document.prototype, 'activeElement', function () { return idOf(this) === rootId ? this.__active || bodyOf(rootId) : null; });
def(Document.prototype, 'currentScript', function () { return this.__script || null; });
def(Document.prototype, 'implementation', function () {
  var id = idOf(this);
  return cachedList(this, 'implementation', function () { var i = ObjectCreate(DOMImplementation.prototype); WeakMapSet(implementations, i, id); return i; });
});
def(Document.prototype, 'cookie', function () {
  var c = this.__cookies, out = [];
  if (c) { var keys = ObjectKeys(c); for (var i = 0; i < keys.length; i++) ArrayPush(out, keys[i] + '=' + c[keys[i]]); }
  return ArrayJoin(out, '; ');
}, function (v) {
  if (!this.__cookies) hidden(this, '__cookies', ObjectCreate(null));
  var pair = domString(v), end = StringIndexOf(pair, ';');
  if (end >= 0) pair = StringSubstring(pair, 0, end);
  var eq = StringIndexOf(pair, '=');
  if (eq > 0) this.__cookies[StringTrim(StringSubstring(pair, 0, eq))] = StringTrim(StringSubstring(pair, eq + 1));
});
/* The live collections of a document, of HTML, 3.1.3: the HTML elements [selector] matches that [keep] keeps. */
function documentCollection(prop, selector, keep) {
  def(Document.prototype, prop, function () {
    var id = idOf(this);
    return cachedList(this, prop, function () {
      return liveElements(function () { return listFilter(queryAll(id, selector), function (e) { return nameOf(e).ns === XHTML_NS && (!keep || keep(e)); }); });
    });
  });
}
documentCollection('images', 'img');
documentCollection('embeds', 'embed');
documentCollection('plugins', 'embed');
documentCollection('links', 'a[href], area[href]');
documentCollection('forms', 'form');
documentCollection('scripts', 'script');
documentCollection('anchors', 'a[name]');
def(Document.prototype, 'applets', function () { idOf(this); return cachedList(this, 'applets', function () { return liveElements(function () { return []; }); }); });
/* A CSS string of [value], for a selector that matches an attribute's value. */
function selectorString(value) { return '"' + RegExpReplace(RE_QUOTE, String(value), '\\"') + '"'; }
Document.prototype.getElementById = function (elementId) {
  var id = idOf(this), v = domString(elementId);
  return wrap(id === rootId ? K.byId(v) : queryFirst(id, '[id=' + selectorString(v) + ']'));
};
Document.prototype.getElementsByName = function (name) {
  var id = idOf(this), n = domString(name);
  return cachedList(this, 'name ' + n, function () {
    return liveNodes(function () { return listFilter(queryAll(id, '[name]'), function (e) { return K.attr(e, 'name') === n && nameOf(e).ns === XHTML_NS; }); });
  });
};
Document.prototype.createElement = function (localName) {
  var docId = idOf(this), what = "Failed to execute 'createElement' on 'Document'";
  needArgs(arguments, 1, what);
  var local = validLocalName(domString(localName), what), html = isHtmlDocument(docId);
  if (html) local = asciiLowerCase(local);
  var ns = html || documentContentType(docId) === 'application/xhtml+xml' ? XHTML_NS : null;
  return madeBy(docId, createElementId({ __proto__: null, ns: ns, prefix: null, local: local }));
};
Document.prototype.createElementNS = function (namespace, qualifiedName) {
  var docId = idOf(this), what = "Failed to execute 'createElementNS' on 'Document'";
  needArgs(arguments, 2, what);
  return madeBy(docId, createElementId(extractName(namespace, domString(qualifiedName), what)));
};
Document.prototype.createTextNode = function (data) {
  needArgs(arguments, 1, "Failed to execute 'createTextNode' on 'Document'");
  return madeBy(idOf(this), K.createText(domString(data)));
};
Document.prototype.createComment = function () { return madeBy(idOf(this), K.createText('')); };
Document.prototype.createDocumentFragment = function () { return madeBy(idOf(this), K.createFragment()); };
Document.prototype.createAttribute = function (localName) {
  var what = "Failed to execute 'createAttribute' on 'Document'", id = idOf(this), name = domString(localName);
  needArgs(arguments, 1, what);
  if (!RegExpTest(RE_ATTRIBUTE_NAME, name)) throw new DOMException(what + ": The localName provided ('" + name + "') contains an invalid character.", 'InvalidCharacterError');
  return newAttr(isHtmlDocument(id) ? asciiLowerCase(name) : name, '', null);
};
/* The interfaces createEvent makes, by the names of DOM Standard, 4.5, in lower case. */
var EVENT_INTERFACES = {
  __proto__: null, event: Event, events: Event, htmlevents: Event, svgevents: Event, uievent: UIEvent, uievents: UIEvent,
  mouseevent: MouseEvent, mouseevents: MouseEvent, keyboardevent: KeyboardEvent, focusevent: FocusEvent, customevent: CustomEvent,
  touchevent: TouchEvent
};
Document.prototype.createEvent = function (interfaceName) {
  idOf(this);
  var name = domString(interfaceName), E = EVENT_INTERFACES[asciiLowerCase(name)];
  if (E === undefined) throw new DOMException("Failed to execute 'createEvent' on 'Document': The provided event type ('" + name + "') is invalid.", 'NotSupportedError');
  return new E('');
};
Document.prototype.hasFocus = function () { return idOf(this) === rootId; };
/* What document.write() writes: after the running script, or else at the end of the body. */
function writeHtml(doc, html) {
  var at = doc.__script;
  if (at) { K.write(at.__id, html); return; }
  var body = bodyOf(idOf(doc));
  if (body !== null) check(K.insertHtml(body.__id, 'beforeend', html), 'write');
}
Document.prototype.write = function () { idOf(this); writeHtml(this, ArrayJoin(listMap(arguments, domString), '')); };
Document.prototype.writeln = function () { idOf(this); writeHtml(this, ArrayJoin(listMap(arguments, domString), '') + '\n'); };
Document.prototype.open = function () { idOf(this); return this; };
Document.prototype.close = function () { idOf(this); };
Document.prototype.elementFromPoint = function () { idOf(this); return null; };
Document.prototype.elementsFromPoint = function () { idOf(this); return []; };
Document.prototype.execCommand = function () { idOf(this); return false; };
Document.prototype.queryCommandSupported = function () { idOf(this); return false; };
Document.prototype.getSelection = function () { return idOf(this) === rootId ? selection : null; };
Document.prototype.importNode = function (node, deep) { return madeBy(idOf(this), K.clone(idOf(node), !!deep)); };
Document.prototype.adoptNode = function (node) {
  var docId = idOf(this), id = idOf(node);
  if (K.kind(id) === 9) throw new DOMException("Failed to execute 'adoptNode' on 'Document': The node provided is a document, which may not be adopted.", 'NotSupportedError');
  var p = K.parent(id);
  if (p != null) K.remove(p, id);
  return madeBy(docId, id);
};
defineInterface(Document, 'Document', Node, 0);
var XMLDocument = abstractInterface('XMLDocument', Document);
defineInterface(XMLDocument, 'XMLDocument', Document);
var HTMLDocument = abstractInterface('HTMLDocument', Document);
defineInterface(HTMLDocument, 'HTMLDocument', Document);

/* DOMImplementation, of the DOM Standard, 4.5.1. */
var implementations = new WeakMap();
var DOMImplementation = abstractInterface('DOMImplementation');
function implementationOf(i) {
  var id = i !== null && typeof i === 'object' ? WeakMapGet(implementations, i) : undefined;
  if (id === undefined) throw new TypeError('Illegal invocation');
  return id;
}
DOMImplementation.prototype.createDocumentType = function () {
  implementationOf(this);
  throw new DOMException("Failed to execute 'createDocumentType' on 'DOMImplementation': A document type is not supported.", 'NotSupportedError');
};
DOMImplementation.prototype.createDocument = function (namespace, qualifiedName) {
  implementationOf(this);
  var what = "Failed to execute 'createDocument' on 'DOMImplementation'";
  needArgs(arguments, 2, what);
  var ns = namespace == null || namespace === '' ? null : domString(namespace), q = qualifiedName === null ? '' : domString(qualifiedName);
  var name = q === '' ? null : extractName(ns, q, what);
  var doc = madeDocument(K.createDocument(), XMLDocument.prototype, false,
    ns === XHTML_NS ? 'application/xhtml+xml' : ns === SVG_NS ? 'image/svg+xml' : 'application/xml');
  if (name !== null) insertNode(doc.__id, madeBy(doc.__id, createElementId(name)), null, what);
  return doc;
};
DOMImplementation.prototype.createHTMLDocument = function (title) {
  implementationOf(this);
  var doc = madeDocument(K.createDocument(), HTMLDocument.prototype, true, 'text/html'), id = doc.__id;
  function element(parent, local) { var e = htmlElementId(local); MapSet(nodeDocuments, e, id); check(K.insert(parent, e, null), 'createHTMLDocument'); return e; }
  var html = element(id, 'html'), head = element(html, 'head');
  if (title !== undefined) K.setText(element(head, 'title'), domString(title));
  element(html, 'body');
  return doc;
};
DOMImplementation.prototype.hasFeature = function () { implementationOf(this); return true; };
defineInterface(DOMImplementation, 'DOMImplementation');

/* DocumentFragment, of the DOM Standard, 4.7, which a script may construct. */
function DocumentFragment() {
  needNew(this, DocumentFragment, 'DocumentFragment', isNode);
  return wrap(K.createFragment());
}
DocumentFragment.prototype = ObjectCreate(Node.prototype);
ParentNode(DocumentFragment.prototype);
DocumentFragment.prototype.getElementById = function (elementId) { return wrap(queryFirst(idOf(this), '[id=' + selectorString(domString(elementId)) + ']')); };
defineInterface(DocumentFragment, 'DocumentFragment', Node, 0);

var document = wrap(rootId);

/* ---- the window ---- */

/* Location, of HTML, 7.10.5, whose members are the instance's own ([LegacyUnforgeable]). */
var Location = abstractInterface('Location');
defineInterface(Location, 'Location');
var location = ObjectCreate(Location.prototype);
function navigate(v) { K.navigate(usv(v)); }
def(location, 'href', function () { return K.location(); }, navigate);
def(location, 'protocol', function () { return 'epub:'; });
/* The book's origin, the same in each of its chapters (#500). */
var origin = K.origin();
var originHost = RegExpReplace(RE_EPUB_SCHEME, origin, '');
def(location, 'host', function () { return originHost; });
def(location, 'hostname', function () { return originHost; });
def(location, 'port', function () { return ''; });
def(location, 'origin', function () { return origin; });
def(location, 'pathname', function () { return RegExpReplace(RE_FRAGMENT, StringSubstring(K.location(), origin.length), ''); });
def(location, 'search', function () { return ''; });
var locationHash = '';
def(location, 'hash', function () { return locationHash; }, function (v) {
  v = domString(v);
  locationHash = v && StringCharAt(v, 0) !== '#' ? '#' + v : v;
  navigate(locationHash);
});
hidden(location, 'assign', function (url) { navigate(url); });
hidden(location, 'replace', function (url) { navigate(url); });
hidden(location, 'reload', function () {});
hidden(location, 'toString', function () { return K.location(); });

/* Storage, of HTML, 12.2.1: a legacy platform object of Web IDL, 3.9, whose keys are its named
   properties, which an assignment sets whatever the object inherits. */
var Storage = abstractInterface('Storage');
var storageKinds = new WeakMap();
function storageKind(s) {
  var k = s !== null && typeof s === 'object' ? WeakMapGet(storageKinds, s) : undefined;
  if (k === undefined) throw new TypeError('Illegal invocation');
  return k;
}
function storageGet(kind, key) { var v = K.storage(kind, 'get', key, null); return v == null ? null : v; }
function storageSet(kind, key, value) { K.storage(kind, 'set', key, value); }
function storageRemove(kind, key) { K.storage(kind, 'remove', key, null); }
function storageLength(kind) { return +K.storage(kind, 'length', null, null); }
function storageKey(kind, i) { var v = K.storage(kind, 'key', String(i), null); return v == null ? null : v; }
function storageKeys(kind) { var out = [], n = storageLength(kind); for (var i = 0; i < n; i++) ArrayPush(out, storageKey(kind, i)); return out; }
Storage.prototype.getItem = function (key) { var k = storageKind(this); needArgs(arguments, 1, "Failed to execute 'getItem' on 'Storage'"); return storageGet(k, domString(key)); };
Storage.prototype.setItem = function (key, value) { var k = storageKind(this); needArgs(arguments, 2, "Failed to execute 'setItem' on 'Storage'"); storageSet(k, domString(key), domString(value)); };
Storage.prototype.removeItem = function (key) { var k = storageKind(this); needArgs(arguments, 1, "Failed to execute 'removeItem' on 'Storage'"); storageRemove(k, domString(key)); };
Storage.prototype.clear = function () { K.storage(storageKind(this), 'clear', null, null); };
Storage.prototype.key = function (index) { var k = storageKind(this); needArgs(arguments, 1, "Failed to execute 'key' on 'Storage'"); return storageKey(k, unsignedLong(index)); };
def(Storage.prototype, 'length', function () { return storageLength(storageKind(this)); });
defineInterface(Storage, 'Storage');
/* The value of the named property [key] of a storage, or undefined where the object has a property of that name. */
function storageNamed(t, key) { return typeof key === 'string' && !ReflectHas(t, key) ? storageGet(WeakMapGet(storageKinds, t), key) : null; }
var STORAGE_HANDLER = {
  __proto__: null,
  get: function (t, key, receiver) { var v = storageNamed(t, key); return v !== null ? v : receiverGet(t, key, receiver); },
  set: function (t, key, value) {
    if (typeof key !== 'string') return ReflectSet(t, key, value);
    storageSet(WeakMapGet(storageKinds, t), key, domString(value));
    return true;
  },
  has: function (t, key) { return storageNamed(t, key) !== null || ReflectHas(t, key); },
  ownKeys: function (t) {
    var out = storageKeys(WeakMapGet(storageKinds, t)), own = ReflectOwnKeys(t);
    for (var i = 0; i < own.length; i++) ArrayPush(out, own[i]);
    return out;
  },
  getOwnPropertyDescriptor: function (t, key) {
    if (typeof key === 'string' && !ObjectHasOwn(t, key)) {
      var v = storageGet(WeakMapGet(storageKinds, t), key);
      if (v !== null) return { __proto__: null, value: v, writable: true, enumerable: true, configurable: true };
    }
    return ObjectGetOwnPropertyDescriptor(t, key);
  },
  defineProperty: function (t, key, desc) {
    var d = ownDescriptor(desc);
    if (typeof key !== 'string') return ReflectDefineProperty(t, key, d);
    if (ObjectHasOwn(d, 'get') || ObjectHasOwn(d, 'set')) return false;
    storageSet(WeakMapGet(storageKinds, t), key, domString(d.value));
    return true;
  },
  deleteProperty: function (t, key) {
    if (storageNamed(t, key) === null) return ReflectDeleteProperty(t, key);
    storageRemove(WeakMapGet(storageKinds, t), key);
    return true;
  },
  preventExtensions: function () { return false; }
};
function storage(kind) {
  var t = ObjectCreate(Storage.prototype), p = new Proxy(t, STORAGE_HANDLER);
  WeakMapSet(storageKinds, t, kind);
  WeakMapSet(storageKinds, p, kind);
  return p;
}

/* The event loop (#535). A task is a function that makes the steps it runs, such as a timer's
   callback or the events of a FileReader. A pump queues the timers that fell due, then runs the
   tasks queued by then and the animation frames after them, and the host takes each callback in
   a call of its own. A task queued meanwhile waits for the next pump. */
var timers = [];
var frames = [];
var tasks = [];
var nextTimer = 1;
/* The animation frames being run, whose callbacks a callback before them can still cancel. */
var running = [];
/* The pump's time, which a frame callback is given and from which an interval counts. */
var pumpNow = 0;
function timersChanged() { K.timers(timers.length + frames.length + tasks.length); }
function queueTask(task) {
  ArrayPush(tasks, task);
  timersChanged();
}
/* Takes the tasks of [owner] that have not run off the queue, as abort() of a FileReader does. */
function dropTasks(owner) {
  for (var i = tasks.length - 1; i >= 0; i--) if (tasks[i].owner === owner) listRemoveAt(tasks, i);
  timersChanged();
}
function schedule(fn, ms, args, repeat) {
  if (typeof fn !== 'function') { var code = String(fn); fn = new Function(code); }
  ms = Number(ms) || 0;
  if (ms < 0) ms = 0;
  var t = { id: nextTimer++, due: K.now() + ms, fn: fn, args: args, every: repeat ? MathMax(ms, 1) : 0, queued: false };
  ArrayPush(timers, t);
  timersChanged();
  return t.id;
}
function clearTimer(id) {
  for (var i = 0; i < timers.length; i++) if (timers[i].id === id) { listRemoveAt(timers, i); timersChanged(); return; }
}
/* Calls a callback of a timer or a frame, and reports what it throws. */
function callBack(fn, self, args) {
  try { ReflectApply(fn, self, args); } catch (e) { report(e); }
}
/* The task of a timer that fell due. A timer cleared since does not run, as HTML checks the map
   of active timers when the task runs. */
function timerTask(t) {
  return function* () {
    t.queued = false;
    var at = ArrayIndexOf(timers, t);
    if (at < 0) return;
    if (t.every) t.due = pumpNow + t.every; else listRemoveAt(timers, at);
    callBack(t.fn, global, t.args);
    yield;
  };
}
function* frameSteps() {
  running = frames;
  frames = [];
  for (var i = 0; i < running.length; i++) {
    if (running[i].cancelled) continue;
    callBack(running[i].fn, global, [pumpNow]);
    yield;
  }
  running = [];
}
function requestFrame(fn) {
  var id = nextTimer++;
  ArrayPush(frames, { id: id, fn: fn, cancelled: false });
  timersChanged();
  return id;
}
function cancelFrame(id) {
  for (var i = 0; i < running.length; i++) if (running[i].id === id) running[i].cancelled = true;
  for (var j = 0; j < frames.length; j++) if (frames[j].id === id) { listRemoveAt(frames, j); timersChanged(); return; }
}
/* Queues [fn] as a microtask. A promise resolved with a thenable calls the thenable's then in a
   job of its own, so no then or species of Promise.prototype, which a script can replace, takes
   part. */
function microtask(fn) {
  new Promise(function (resolve) { resolve({ __proto__: null, then: function () { try { fn(); } catch (e) { report(e); } } }); });
}

/* What the host is running: the steps of a tap, of the load events or of the task at hand. Each
   call of step() runs them to their next callback and answers MORE, or, once they are done,
   what they returned. During a pump, the tasks it counted run one after the other, then the
   frames. */
var MORE = 'more';
var current = null;
var budget = 0;
var framesDue = false;
var outcome;
function step() {
  for (;;) {
    if (!current) {
      if (budget > 0 && tasks.length) {
        budget--;
        current = ArrayShift(tasks)();
      } else if (framesDue) {
        budget = 0;
        framesDue = false;
        current = frameSteps();
      } else {
        budget = 0;
        timersChanged();
        return outcome;
      }
    }
    var r;
    try { r = GeneratorNext(current); } catch (e) { report(e); r = { done: true, value: undefined }; }
    if (!r.done) return MORE;
    current = null;
    outcome = r.value;
  }
}
function begin(steps) {
  current = steps;
  budget = 0;
  framesDue = false;
  outcome = undefined;
  return step();
}

/* What the console prints of its arguments from [from] on. */
function consoleText(args, from) {
  var parts = [];
  for (var i = from; i < args.length; i++) {
    var a = args[i];
    try { ArrayPush(parts, typeof a === 'string' ? a : isA(a, Error) ? String(a) : JSONStringify(a)); } catch (e) { ArrayPush(parts, String(a)); }
  }
  return ArrayJoin(parts, ' ');
}
var console = {};
function consoleLevel(level) { console[level] = function () { K.console(level, consoleText(arguments, 0)); }; }
(function (levels) { for (var i = 0; i < levels.length; i++) consoleLevel(levels[i]); })(['log', 'info', 'warn', 'error', 'debug', 'trace']);
console.dir = console.log;
console.table = console.log;
console.assert = function (ok) {
  if (!ok) K.console('error', 'Assertion failed:' + (arguments.length > 1 ? ' ' + consoleText(arguments, 1) : ''));
};
console.group = console.groupCollapsed = console.groupEnd = console.time = console.timeEnd = console.count = function () {};

/*
 * What a book's scripts ask of the reading system (EPUB Reading Systems 3.3, appendix B). A
 * chapter's scripts change its tree and its styles, which is laid out again, and a tap reaches
 * them as mouse events; no touch or key event reaches them. A feature this does not know
 * answers undefined. `name` and `version` are deprecated, and there is no version to give.
 */
var features = { __proto__: null, 'dom-manipulation': true, 'layout-changes': true, 'spine-scripting': true,
  'mouse-events': true, 'touch-events': false, 'keyboard-events': false };
var readingSystem = {
  name: 'KitePDF', version: '',
  hasFeature: function (feature) { return features[String(feature)]; }
};
ObjectFreeze(readingSystem);
/* The interface of an object of the window, whose members [members] defines on its prototype,
   and the one object of it that the window holds. */
function windowObject(name, parent, members) {
  var I = abstractInterface(name, parent);
  members(I.prototype, I);
  defineInterface(I, name, parent);
  var o = ObjectCreate(I.prototype);
  if (parent === EventTarget) hidden(o, '__listeners', ObjectCreate(null));
  return o;
}
/* A getter of a fixed value on [p], which checks that its this is an object of the interface. */
function fixed(I, name, value) { def(I.prototype, name, function () { if (!isA(this, I)) throw new TypeError('Illegal invocation'); return value; }); }
var LANGUAGES = ObjectFreeze(['en']);
var navigator = windowObject('Navigator', null, function (p, I) {
  var values = ['userAgent', 'KitePDF', 'appCodeName', 'Mozilla', 'appName', 'Netscape', 'appVersion', '', 'platform', '', 'product', 'Gecko',
    'productSub', '20030107', 'vendor', '', 'vendorSub', '', 'language', 'en', 'languages', LANGUAGES, 'onLine', false, 'cookieEnabled', true,
    'maxTouchPoints', 1, 'hardwareConcurrency', 1, 'pdfViewerEnabled', false, 'webdriver', false, 'doNotTrack', null];
  for (var i = 0; i < values.length; i += 2) fixed(I, values[i], values[i + 1]);
  p.javaEnabled = function () { return false; };
});
ObjectDefineProperty(navigator, 'epubReadingSystem', { __proto__: null, value: readingSystem, enumerable: true });
var viewport = K.viewport();
var screen = windowObject('Screen', EventTarget, function (p, I) {
  var values = ['width', viewport[0], 'height', viewport[1], 'availWidth', viewport[0], 'availHeight', viewport[1], 'availLeft', 0,
    'availTop', 0, 'colorDepth', 24, 'pixelDepth', 24];
  for (var i = 0; i < values.length; i += 2) fixed(I, values[i], values[i + 1]);
});
var history = windowObject('History', null, function (p, I) {
  fixed(I, 'length', 1);
  fixed(I, 'state', null);
  def(p, 'scrollRestoration', function () { return 'auto'; }, function () {});
  p.back = p.forward = p.go = p.pushState = p.replaceState = function () {};
});
var performance = windowObject('Performance', EventTarget, function (p, I) {
  p.now = function () { return K.now(); };
  fixed(I, 'timeOrigin', 0);
  p.mark = p.measure = p.clearMarks = p.clearMeasures = function () {};
  p.getEntries = p.getEntriesByName = p.getEntriesByType = function () { return []; };
  p.toJSON = function () { return { timeOrigin: 0 }; };
});
var selection = windowObject('Selection', null, function (p, I) {
  var values = ['anchorNode', null, 'anchorOffset', 0, 'focusNode', null, 'focusOffset', 0, 'isCollapsed', true, 'rangeCount', 0, 'type', 'None',
    'direction', 'none'];
  for (var i = 0; i < values.length; i += 2) fixed(I, values[i], values[i + 1]);
  p.toString = function () { return ''; };
  p.removeAllRanges = p.empty = p.addRange = p.removeRange = p.collapse = p.collapseToStart = p.collapseToEnd = p.extend =
    p.selectAllChildren = p.deleteFromDocument = p.setBaseAndExtent = p.setPosition = p.modify = function () {};
  p.containsNode = function () { return false; };
  p.getRangeAt = function (index) {
    throw new DOMException("Failed to execute 'getRangeAt' on 'Selection': " + unsignedLong(index) + ' is not a valid index.', 'IndexSizeError');
  };
});
/* MediaQueryList, of CSSOM View, 4.2, whose query matches nothing a page can tell. */
var mediaQueries = new WeakMap();
var MediaQueryList = abstractInterface('MediaQueryList', EventTarget);
def(MediaQueryList.prototype, 'media', function () { var m = WeakMapGet(mediaQueries, this); if (m === undefined) throw new TypeError('Illegal invocation'); return m; });
def(MediaQueryList.prototype, 'matches', function () { if (!WeakMapHas(mediaQueries, this)) throw new TypeError('Illegal invocation'); return false; });
MediaQueryList.prototype.addListener = function (callback) { if (callback != null) addListener(this, 'change', callback, false, false); };
MediaQueryList.prototype.removeListener = function (callback) { if (callback != null) removeListener(this, 'change', callback, false); };
defineHandlers(MediaQueryList.prototype, ['change']);
defineInterface(MediaQueryList, 'MediaQueryList', EventTarget);
function matchMedia(query) {
  needArgs(arguments, 1, "Failed to execute 'matchMedia' on 'Window'");
  var m = ObjectCreate(MediaQueryList.prototype);
  hidden(m, '__listeners', ObjectCreate(null));
  WeakMapSet(mediaQueries, m, StringTrim(domString(query)));
  return m;
}
/* getComputedStyle of the window, of CSSOM, 9: the computed style of an element, which the host answers. */
function computedStyle(el) {
  needArgs(arguments, 1, "Failed to execute 'getComputedStyle' on 'Window'");
  return makeStyle(wrap(idOf(el)), true);
}

/* Window, of HTML, 7.2: the global's prototype, with the named properties object between it and
   EventTarget. The members of a [Global] interface are the global's own. */
var Window = abstractInterface('Window', EventTarget);
defineInterface(Window, 'Window', EventTarget);
var WindowProperties = ObjectCreate(EventTarget.prototype);
if (SymbolToStringTag) ObjectDefineProperty(WindowProperties, SymbolToStringTag, { __proto__: null, value: 'WindowProperties', configurable: true });
ObjectSetPrototypeOf(Window.prototype, WindowProperties);
ObjectSetPrototypeOf(global, Window.prototype);

var api = {
  window: global, self: global, top: global, parent: global, frames: global, opener: null, frameElement: null, origin: origin,
  document: document, location: location, console: console, atob: atob, btoa: btoa,
  navigator: navigator, clientInformation: navigator, screen: screen, history: history, performance: performance,
  innerWidth: viewport[0], innerHeight: viewport[1], outerWidth: viewport[0], outerHeight: viewport[1],
  devicePixelRatio: 1, scrollX: 0, scrollY: 0, pageXOffset: 0, pageYOffset: 0, screenX: 0, screenY: 0, screenLeft: 0, screenTop: 0,
  name: '', status: '', closed: false, length: 0, isSecureContext: true, crossOriginIsolated: false,
  localStorage: storage('local'), sessionStorage: storage('session'),
  setTimeout: function (fn, ms) { return schedule(fn, ms, listSlice(arguments, 2), false); },
  setInterval: function (fn, ms) { return schedule(fn, ms, listSlice(arguments, 2), true); },
  clearTimeout: clearTimer, clearInterval: clearTimer,
  requestAnimationFrame: requestFrame,
  cancelAnimationFrame: cancelFrame,
  queueMicrotask: function (fn) { microtask(fn); },
  alert: function (message) { K.console('alert', message === undefined ? '' : String(message)); },
  confirm: function (message) { K.console('confirm', message === undefined ? '' : String(message)); return false; },
  prompt: function (message) { K.console('prompt', message === undefined ? '' : String(message)); return null; },
  getComputedStyle: computedStyle,
  matchMedia: matchMedia,
  getSelection: function () { return selection; },
  scrollTo: function () {}, scrollBy: function () {}, scroll: function () {}, print: function () {}, focus: function () {}, blur: function () {},
  stop: function () {}, moveTo: function () {}, moveBy: function () {}, resizeTo: function () {}, resizeBy: function () {},
  open: function (url) { if (url) navigate(url); return null; },
  close: function () {},
  postMessage: function () {}
};
(function (names) { for (var i = 0; i < names.length; i++) global[names[i]] = api[names[i]]; })(ObjectKeys(api));
/* The interface objects, and the constructors such as Image, are properties of the global that a
   for-in does not see, as Web IDL, 3.7, defines them. */
(function () {
  for (var i = 0; i < INTERFACES.length; i++) hidden(global, INTERFACES[i].name, INTERFACES[i].ctor);
  hidden(global, 'webkitURL', URL);
})();
hidden(global, '__listeners', ObjectCreate(null));
defineHandlers(global, GLOBAL_HANDLERS);
defineHandlers(global, WINDOW_HANDLERS);

/* ---- what the host calls ---- */

/* An entry point of the host: a property of the global that no script replaces or deletes, and
   that a declaration of a script with its name cannot shadow. */
function hostEntry(name, fn) {
  ObjectDefineProperty(global, name, { __proto__: null, value: fn, writable: false, enumerable: false, configurable: false });
}
/* The markup's elements with handler attributes, in tree order, for the parser to reach (#539). */
hostEntry('__kite_markup', function (ids) {
  for (var i = 0; i < ids.length; i++) { ArrayPush(markup, ids[i]); pendingIds[ids[i]] = true; }
});
/* The script about to run, or null once it ran, and how many of those elements come before its end. */
hostEntry('__kite_current', function (id, reached) {
  if (reached != null) reachMarkup(reached);
  hidden(document, '__script', id == null ? null : wrap(id));
});
/* Each of these starts what the reading system runs and answers as step() does; the host calls
   __kite_step() for the rest while the answer is MORE (#535). */
function* loadedSteps() {
  reachMarkup(markup.length);
  hidden(document, '__ready', 'interactive');
  for (var a = dispatchSteps(document, new Event('readystatechange')); !GeneratorNext(a).done;) yield;
  for (var b = dispatchSteps(document, new Event('DOMContentLoaded', { __proto__: null, bubbles: true })); !GeneratorNext(b).done;) yield;
  document.__ready = 'complete';
  for (var c = dispatchSteps(document, new Event('readystatechange')); !GeneratorNext(c).done;) yield;
  var load = new Event('load');
  load.target = document;
  for (var d = dispatchSteps(global, load); !GeneratorNext(d).done;) yield;
  for (var e = dispatchSteps(global, new Event('pageshow')); !GeneratorNext(e).done;) yield;
}
function* tapSteps(id, x, y) {
  var target = wrap(id);
  var init = { __proto__: null, bubbles: true, cancelable: true, composed: true, view: global, detail: 1, clientX: x, clientY: y,
    screenX: x, screenY: y, button: 0, buttons: 1, pointerId: 1, pointerType: 'touch', isPrimary: true };
  function trusted(e) { e.isTrusted = true; return dispatchSteps(target, e); }
  for (var a = trusted(new PointerEvent('pointerdown', init)); !GeneratorNext(a).done;) yield;
  for (var b = trusted(new MouseEvent('mousedown', init)); !GeneratorNext(b).done;) yield;
  // The tap focuses the control it lands in by the focusing steps, not by a focus() a script may have replaced.
  var c = target === document ? null : control(target);
  if (c) for (var f = focusSteps(c); !GeneratorNext(f).done;) yield;
  init.buttons = 0;
  for (var d = trusted(new PointerEvent('pointerup', init)); !GeneratorNext(d).done;) yield;
  for (var e = trusted(new MouseEvent('mouseup', init)); !GeneratorNext(e).done;) yield;
  var g = activateSteps(target, new MouseEvent('click', init), true), r;
  while (!(r = GeneratorNext(g)).done) yield;
  return r.value;
}
hostEntry('__kite_step', step);
hostEntry('__kite_loaded', function () { return begin(loadedSteps()); });
hostEntry('__kite_tap', function (id, x, y) { return begin(tapSteps(id, x, y)); });
/* Queues the timers due by [now], in the order they fell due, and runs what is queued. */
hostEntry('__kite_pump', function (now) {
  pumpNow = now;
  var due = listFilter(timers, function (t) { return t.due <= now && !t.queued; });
  ArraySort(due, function (a, b) { return a.due - b.due || a.id - b.id; });
  for (var i = 0; i < due.length; i++) {
    due[i].queued = true;
    ArrayPush(tasks, timerTask(due[i]));
  }
  current = null;
  budget = tasks.length;
  framesDue = true;
  outcome = undefined;
  return step();
});
/* Milliseconds until the next pump has work: 0 for a task or a frame waiting, -1 for nothing. */
hostEntry('__kite_wait', function () {
  if (tasks.length || frames.length) return 0;
  if (!timers.length) return -1;
  var next = Infinity;
  for (var k = 0; k < timers.length; k++) if (timers[k].due < next) next = timers[k].due;
  return MathMax(0, next - K.now());
});
})(this);
"""
