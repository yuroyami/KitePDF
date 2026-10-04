package io.github.yuroyami.kitepdf.epub.script

/**
 * The reflection of content attributes by IDL attributes in [DOM_PRELUDE], of HTML, 2.6.1 (#538).
 */
internal const val DOM_PRELUDE_REFLECTION: String = """/* ---- reflection, of HTML, 2.6.1 (#538) ----
   An IDL attribute that reflects a content attribute reads the attribute and writes it by the
   rules of its type. An interface lists the attributes it reflects in a string, each one the name
   of the IDL attribute, then `=` and the name of the content attribute where that is not the
   first in lower case, then `:` and its type where it is no DOMString:
     N  a DOMString that null sets to the empty string ([LegacyNullToEmptyString])
     u  a URL; a  the URL of a form's action, which is the document's while it is missing or empty
     b  a boolean
     l  a long, then its default
     L  a long limited to non-negative numbers
     U  an unsigned long, then its default
     P  an unsigned long limited to positive numbers, then its default
     F  the same with fallback, then its default
     C  an unsigned long clamped to a range: its default, its least and its greatest, by commas
     d  a double, then its default
     D  a double limited to positive numbers, then its default
     e  an enumerated attribute limited to known values, then the name of its keywords in ENUMS
     t  a DOMTokenList, then the name of its supported tokens in TOKENS, if it has them */

/* The ASCII whitespace of HTML, 2.3.4, and the ASCII digits. */
function isHtmlSpace(c) { return c === 32 || c === 9 || c === 10 || c === 12 || c === 13; }
function isDigit(c) { return c >= 48 && c <= 57; }
/* The rules for parsing integers of HTML, 2.3.4.1, or of non-negative integers for
   [nonNegative]: the number, or null for an error. */
function parseHtmlInteger(input, nonNegative) {
  var i = 0, n = input.length, sign = 1, c;
  while (i < n && isHtmlSpace(StringCharCodeAt(input, i))) i++;
  if (i >= n) return null;
  c = StringCharCodeAt(input, i);
  if (c === 45) { sign = -1; i++; } else if (c === 43) i++;
  if (i >= n || !isDigit(StringCharCodeAt(input, i))) return null;
  var value = 0;
  for (; i < n && isDigit(c = StringCharCodeAt(input, i)); i++) value = value * 10 + (c - 48);
  if (value === 0) return 0;
  value = sign * value;
  return nonNegative && value < 0 ? null : value;
}
/* The rules for parsing floating-point number values of HTML, 2.3.4.3: the number, or null for an
   error. The steps find the longest prefix that is a number, which the engine's own conversion
   then rounds, as the steps round the exact value. */
function parseHtmlFloat(input) {
  var i = 0, n = input.length, c;
  while (i < n && isHtmlSpace(StringCharCodeAt(input, i))) i++;
  var start = i;
  if (i < n && ((c = StringCharCodeAt(input, i)) === 45 || c === 43)) i++;
  if (i >= n) return null;
  if (StringCharCodeAt(input, i) === 46) {
    if (i + 1 >= n || !isDigit(StringCharCodeAt(input, i + 1))) return null;
  } else if (!isDigit(StringCharCodeAt(input, i))) {
    return null;
  }
  while (i < n && isDigit(StringCharCodeAt(input, i))) i++;
  var end = i;
  if (i < n && StringCharCodeAt(input, i) === 46) {
    for (i++; i < n && isDigit(StringCharCodeAt(input, i)); i++);
    end = i;
  }
  if (i < n && ((c = StringCharCodeAt(input, i)) === 101 || c === 69)) {
    i++;
    if (i < n && ((c = StringCharCodeAt(input, i)) === 45 || c === 43)) i++;
    if (i < n && isDigit(StringCharCodeAt(input, i))) {
      while (i < n && isDigit(StringCharCodeAt(input, i))) i++;
      end = i;
    }
  }
  var value = +StringSubstring(input, start, end);
  if (!isFinite(value)) return null;
  return value === 0 ? 0 : value;
}
var MAX_LONG = 2147483647;
/* The content attribute [attr] of [el], or null. */
function attrValueOf(el, attr) { return K.attr(idOf(el), attr); }
/* [value] of an unsigned long that a reflecting setter writes: itself in range, else [fallback]. */
function unsignedValue(value, fallback) { return value > MAX_LONG ? fallback : value; }

/* The keywords of an enumerated attribute: each keyword is its own canonical value, `a>b` makes a
   the same state as b, and `>b` the empty string; and the values of a missing and of an invalid
   attribute. A missing value of null makes the IDL attribute nullable. */
function keywords(list, missing, invalid) {
  var map = ObjectCreate(null), all = asciiTokens(list);
  for (var i = 0; i < all.length; i++) {
    var k = all[i], at = StringIndexOf(k, '>');
    if (at < 0) map[k] = k; else map[StringSubstring(k, 0, at)] = StringSubstring(k, at + 1);
  }
  return { __proto__: null, map: map, missing: missing, invalid: invalid === undefined ? missing : invalid };
}
var REFERRER_POLICIES = '> no-referrer no-referrer-when-downgrade same-origin origin strict-origin origin-when-cross-origin strict-origin-when-cross-origin unsafe-url';
var ENCTYPES = 'application/x-www-form-urlencoded multipart/form-data text/plain';
var ENUMS = {
  __proto__: null,
  dir: keywords('ltr rtl auto', ''),
  cors: keywords('anonymous use-credentials >anonymous', null, 'anonymous'),
  referrer: keywords(REFERRER_POLICIES, ''),
  decoding: keywords('sync async auto', 'auto'),
  loading: keywords('lazy eager', 'eager'),
  fetchpriority: keywords('high low auto', 'auto'),
  preload: keywords('none metadata auto >auto', 'metadata'),
  autocomplete: keywords('on off', 'on'),
  enctype: keywords(ENCTYPES, 'application/x-www-form-urlencoded'),
  formenctype: keywords(ENCTYPES, '', 'application/x-www-form-urlencoded'),
  method: keywords('get post dialog', 'get'),
  formmethod: keywords('get post dialog', '', 'get'),
  inputtype: keywords('hidden text search tel url email password date month week time datetime-local number range color ' +
    'checkbox radio file submit image reset button', 'text'),
  buttontype: keywords('submit reset button', 'submit'),
  trackkind: keywords('subtitles captions descriptions chapters metadata', 'subtitles', 'metadata'),
  scope: keywords('row col rowgroup colgroup', ''),
  as: keywords('fetch audio document embed font image manifest object report script sharedworker style track video worker xslt', ''),
  enterkeyhint: keywords('enter done go next previous search send', ''),
  inputmode: keywords('none text tel url email numeric decimal search', ''),
  popover: keywords('auto manual hint >auto', null, 'manual'),
  autocapitalize: keywords('off>none none on>sentences sentences words characters >', '', 'sentences'),
  behavior: keywords('scroll slide alternate', 'scroll'),
  direction: keywords('up right down left', 'left'),
  writingsuggestions: keywords('true false >true', 'true', 'true')
};
/* The supported tokens of a DOMTokenList, of HTML. */
var TOKENS = {
  __proto__: null,
  rel: nameSet(['noopener', 'noreferrer', 'opener']),
  linkrel: nameSet(['alternate', 'dns-prefetch', 'expect', 'icon', 'manifest', 'modulepreload', 'next', 'pingback', 'preconnect',
    'prefetch', 'preload', 'search', 'stylesheet']),
  sandbox: nameSet(['allow-downloads', 'allow-forms', 'allow-modals', 'allow-orientation-lock', 'allow-pointer-lock', 'allow-popups',
    'allow-popups-to-escape-sandbox', 'allow-presentation', 'allow-same-origin', 'allow-scripts', 'allow-top-navigation',
    'allow-top-navigation-by-user-activation', 'allow-top-navigation-to-custom-protocols']),
  blocking: nameSet(['render'])
};
/* The state an enumerated attribute of [value] is in, by [e] from ENUMS. */
function enumState(e, value) {
  if (value == null) return e.missing;
  var state = e.map[asciiLowerCase(value)];
  return state === undefined ? e.invalid : state;
}
/* The URL [value] names, parsed against the document's base URL, or [value] itself when it names none. */
function resolvedUrl(value) {
  var parts = parseUrl(value, documentBase());
  return parts == null ? value : parts[0];
}

/* How each type reflects, by its letter: a function of the prototype, the IDL attribute, the
   content attribute and what follows the letter. */
var REFLECTORS = {
  __proto__: null,
  '': function (proto, name, attr) {
    def(proto, name, function () { var v = attrValueOf(this, attr); return v == null ? '' : v; },
      function (v) { setAttr(this, attr, domString(v)); });
  },
  N: function (proto, name, attr) {
    def(proto, name, function () { var v = attrValueOf(this, attr); return v == null ? '' : v; },
      function (v) { setAttr(this, attr, v === null ? '' : domString(v)); });
  },
  u: function (proto, name, attr) {
    def(proto, name, function () { var v = attrValueOf(this, attr); return v == null ? '' : resolvedUrl(v); },
      function (v) { setAttr(this, attr, usv(v)); });
  },
  a: function (proto, name, attr) {
    def(proto, name, function () { var v = attrValueOf(this, attr); return v == null || v === '' ? K.location() : resolvedUrl(v); },
      function (v) { setAttr(this, attr, usv(v)); });
  },
  b: function (proto, name, attr) {
    def(proto, name, function () { return attrValueOf(this, attr) != null; },
      function (v) { if (v) setAttr(this, attr, ''); else removeAttr(this, attr); });
  },
  l: function (proto, name, attr, arg) {
    var fallback = +arg;
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlInteger(v, false);
      return n === null || n > MAX_LONG || n < -MAX_LONG - 1 ? fallback : n;
    }, function (v) { setAttr(this, attr, String(webIdlLong(v))); });
  },
  L: function (proto, name, attr) {
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlInteger(v, true);
      return n === null || n > MAX_LONG ? -1 : n;
    }, function (v) {
      var x = webIdlLong(v);
      if (x < 0) throw new DOMException("Failed to set the '" + name + "' property: The value provided (" + x + ') is negative.', 'IndexSizeError');
      setAttr(this, attr, String(x));
    });
  },
  U: function (proto, name, attr, arg) {
    var fallback = +arg;
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlInteger(v, true);
      return n === null || n > MAX_LONG ? fallback : n;
    }, function (v) { setAttr(this, attr, String(unsignedValue(unsignedLong(v), fallback))); });
  },
  P: function (proto, name, attr, arg) {
    var fallback = +arg;
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlInteger(v, true);
      return n === null || n < 1 || n > MAX_LONG ? fallback : n;
    }, function (v) {
      var x = unsignedLong(v);
      if (x === 0) throw new DOMException("Failed to set the '" + name + "' property: The value provided is 0, which is an invalid size.", 'IndexSizeError');
      setAttr(this, attr, String(unsignedValue(x, fallback)));
    });
  },
  F: function (proto, name, attr, arg) {
    var fallback = +arg;
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlInteger(v, true);
      return n === null || n < 1 || n > MAX_LONG ? fallback : n;
    }, function (v) { var x = unsignedLong(v); setAttr(this, attr, String(x < 1 ? fallback : unsignedValue(x, fallback))); });
  },
  C: function (proto, name, attr, arg) {
    var range = splitOn(arg, ','), fallback = +range[0], least = +range[1], greatest = +range[2];
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlInteger(v, true);
      return n === null ? fallback : n < least ? least : n > greatest ? greatest : n;
    }, function (v) { setAttr(this, attr, String(unsignedValue(unsignedLong(v), fallback))); });
  },
  d: function (proto, name, attr, arg) {
    var fallback = +arg;
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlFloat(v);
      return n === null ? fallback : n;
    }, function (v) { setAttr(this, attr, String(webIdlDouble(v, "Failed to set the '" + name + "' property"))); });
  },
  D: function (proto, name, attr, arg) {
    var fallback = +arg;
    def(proto, name, function () {
      var v = attrValueOf(this, attr), n = v == null ? null : parseHtmlFloat(v);
      return n === null || n <= 0 ? fallback : n;
    }, function (v) {
      var x = webIdlDouble(v, "Failed to set the '" + name + "' property");
      if (x > 0) setAttr(this, attr, String(x));
    });
  },
  e: function (proto, name, attr, arg) {
    var e = ENUMS[arg];
    def(proto, name, function () { return enumState(e, attrValueOf(this, attr)); }, function (v) {
      if (v == null && e.missing === null) removeAttr(this, attr); else setAttr(this, attr, domString(v));
    });
  },
  t: function (proto, name, attr, arg) {
    var supported = arg ? TOKENS[arg] : undefined;
    def(proto, name, function () { idOf(this); return tokenList(this, attr, supported); },
      function (v) { setAttr(this, attr, domString(v)); });
  }
};
/* Defines on [proto] the reflecting IDL attributes that [spec] lists. */
function reflectAll(proto, spec) {
  var entries = asciiTokens(spec);
  for (var i = 0; i < entries.length; i++) {
    var entry = entries[i], type = '', colon = StringIndexOf(entry, ':');
    if (colon >= 0) { type = StringSubstring(entry, colon + 1); entry = StringSubstring(entry, 0, colon); }
    var eq = StringIndexOf(entry, '='), name = eq < 0 ? entry : StringSubstring(entry, 0, eq);
    var attr = eq < 0 ? asciiLowerCase(entry) : StringSubstring(entry, eq + 1);
    var reflector = REFLECTORS[StringSubstring(type, 0, 1)];
    reflector(proto, name, attr, StringSubstring(type, 1));
  }
}
"""

/**
 * The elements of [DOM_PRELUDE] (#538): `HTMLElement`, the members it shares with the elements of SVG and MathML, and the interfaces of
 * HTML's elements but those of forms.
 */
internal const val DOM_PRELUDE_ELEMENTS: String = """/* ---- HTMLElement and the members it shares with SVGElement and MathMLElement ---- */

/* An interface of an element, which no script constructs, inheriting from [parent], with the
   reflecting attributes of [spec]; [setup] adds the rest of its members to the prototype. */
function elementInterface(name, parent, spec, setup) {
  var I = abstractInterface(name, parent);
  if (spec) reflectAll(I.prototype, spec);
  if (setup) setup(I.prototype);
  return defineInterface(I, name, parent);
}

/* DOMStringMap, of HTML, 3.2.6.6: the data attributes of an element as named properties, which
   come before what the object inherits ([LegacyOverrideBuiltIns]). */
var DOMStringMap = abstractInterface('DOMStringMap');
defineInterface(DOMStringMap, 'DOMStringMap');
var datasets = new WeakMap();
/* The data attribute a name of a DOMStringMap stands for, with [check] for a name being set. */
function dataAttribute(name, check) {
  var out = 'data-';
  for (var i = 0; i < name.length; i++) {
    var c = StringCharCodeAt(name, i);
    if (check && c === 45 && i + 1 < name.length) {
      var d = StringCharCodeAt(name, i + 1);
      if (d >= 97 && d <= 122) throw new DOMException("Failed to set a named property on 'DOMStringMap': '" + name + "' is not a valid property name.", 'SyntaxError');
    }
    out += c >= 65 && c <= 90 ? '-' + StringFromCharCode(c + 32) : StringCharAt(name, i);
  }
  return out;
}
/* The names of [el]'s data attributes as a DOMStringMap gives them, in the order of the attributes. */
function dataNames(el) {
  var all = plainAttrNames(idOf(el)), out = [];
  for (var i = 0; i < all.length; i++) {
    var n = all[i];
    if (StringSubstring(n, 0, 5) !== 'data-' || RegExpTest(RE_ASCII_UPPER, n)) continue;
    var name = '', s = StringSubstring(n, 5);
    for (var j = 0; j < s.length; j++) {
      var c = StringCharCodeAt(s, j), d = j + 1 < s.length ? StringCharCodeAt(s, j + 1) : 0;
      if (c === 45 && d >= 97 && d <= 122) { name += StringFromCharCode(d - 32); j++; } else name += StringCharAt(s, j);
    }
    ArrayPush(out, name);
  }
  return out;
}
/* The value of the data attribute [name] of [el] stands for, or undefined when it has none. */
function dataValue(el, name) {
  if (typeof name !== 'string' || ArrayIndexOf(dataNames(el), name) < 0) return undefined;
  return K.attr(el.__id, dataAttribute(name, false));
}
/* Sets the data attribute that the name [key] of [el]'s DOMStringMap stands for, as its named setter does. */
function setDataValue(el, key, value) {
  var attr = dataAttribute(key, true);
  if (!RegExpTest(RE_ELEMENT_NAME, attr)) throw new DOMException("Failed to set a named property on 'DOMStringMap': '" + attr + "' is not a valid attribute name.", 'InvalidCharacterError');
  setAttr(el, attr, domString(value));
}
var DATASET_HANDLER = {
  __proto__: null,
  get: function (t, key, receiver) {
    var v = dataValue(WeakMapGet(datasets, t), key);
    return v !== undefined ? v : receiverGet(t, key, receiver);
  },
  set: function (t, key, value) {
    if (typeof key !== 'string') return ReflectSet(t, key, value);
    setDataValue(WeakMapGet(datasets, t), key, value);
    return true;
  },
  has: function (t, key) { return dataValue(WeakMapGet(datasets, t), key) !== undefined || ReflectHas(t, key); },
  deleteProperty: function (t, key) {
    var el = WeakMapGet(datasets, t);
    if (dataValue(el, key) === undefined) return ReflectDeleteProperty(t, key);
    removeAttr(el, dataAttribute(key, false));
    return true;
  },
  ownKeys: function (t) {
    var out = dataNames(WeakMapGet(datasets, t)), own = ReflectOwnKeys(t);
    for (var i = 0; i < own.length; i++) ArrayPush(out, own[i]);
    return out;
  },
  getOwnPropertyDescriptor: function (t, key) {
    var v = dataValue(WeakMapGet(datasets, t), key);
    if (v === undefined) return ObjectGetOwnPropertyDescriptor(t, key);
    return { __proto__: null, value: v, writable: true, enumerable: true, configurable: true };
  },
  defineProperty: function (t, key, desc) {
    var d = ownDescriptor(desc);
    if (typeof key !== 'string') return ReflectDefineProperty(t, key, d);
    if (ObjectHasOwn(d, 'get') || ObjectHasOwn(d, 'set')) return false;
    setDataValue(WeakMapGet(datasets, t), key, d.value);
    return true;
  },
  preventExtensions: function () { return false; }
};
function datasetOf(el) {
  var d = WeakMapGet(datasets, el);
  if (d === undefined) {
    var t = ObjectCreate(DOMStringMap.prototype);
    d = new Proxy(t, DATASET_HANDLER);
    WeakMapSet(datasets, t, el);
    WeakMapSet(datasets, el, d);
  }
  return d;
}
/* The default of tabIndex, of HTML, 6.6.3: 0 for an element a reader can focus by itself. */
var FOCUSABLE = nameSet(['a', 'area', 'button', 'frame', 'iframe', 'input', 'object', 'select', 'textarea']);
function defaultTabIndex(id) {
  var n = nameOf(id);
  if (n.ns === XHTML_NS) {
    if (FOCUSABLE[n.local]) return 0;
    if (n.local === 'summary') { var p = K.parent(id); if (p != null && K.kind(p) === 1 && K.tag(p) === 'details' && queryFirst(p, 'summary') === id) return 0; }
  } else if (n.ns === SVG_NS && n.local === 'a') {
    return 0;
  }
  return -1;
}
/* The cryptographic nonce of an element (HTML, 2.6.3), which a script's setter keeps out of the attribute. */
var nonces = new WeakMap();
/* Whether a script element that a script made runs as if async, until its async is set (HTML, 4.12.1). */
var forceAsync = new WeakMap();
/* The members of the HTMLOrSVGElement mixin, ElementCSSInlineStyle and GlobalEventHandlers, which
   HTML, SVG and MathML elements share. */
function sharedElementMembers(proto) {
  defineHandlers(proto, GLOBAL_HANDLERS);
  def(proto, 'style', function () { return styleOf(wrap(idOf(this))); }, function (v) { styleOf(wrap(idOf(this))).cssText = v; });
  def(proto, 'dataset', function () { return datasetOf(wrap(idOf(this))); });
  def(proto, 'nonce', function () {
    var el = wrap(idOf(this)), n = WeakMapGet(nonces, el);
    if (n !== undefined) return n;
    var v = K.attr(el.__id, 'nonce');
    return v == null ? '' : v;
  }, function (v) { WeakMapSet(nonces, wrap(idOf(this)), domString(v)); });
  reflectAll(proto, 'autofocus:b');
  def(proto, 'tabIndex', function () {
    var id = idOf(this), v = K.attr(id, 'tabindex'), n = v == null ? null : parseHtmlInteger(v, false);
    return n === null || n > MAX_LONG || n < -MAX_LONG - 1 ? defaultTabIndex(id) : n;
  }, function (v) { setAttr(this, 'tabindex', String(webIdlLong(v))); });
  proto.focus = function () { drain(focusSteps(wrap(idOf(this)))); };
  proto.blur = function () {
    var el = wrap(idOf(this));
    if (document.__active !== el) return;
    document.__active = null;
    fireEvent(el, new FocusEvent('blur', { __proto__: null }));
    fireEvent(el, new FocusEvent('focusout', { __proto__: null, bubbles: true }));
  };
}
function* focusSteps(el) {
  if (document.__active === el) return;
  var before = document.__active;
  document.__active = el;
  if (before && before !== el) {
    for (var a = dispatchSteps(before, new FocusEvent('blur', { __proto__: null, relatedTarget: el })); !GeneratorNext(a).done;) yield;
    for (var b = dispatchSteps(before, new FocusEvent('focusout', { __proto__: null, bubbles: true, relatedTarget: el })); !GeneratorNext(b).done;) yield;
  }
  for (var c = dispatchSteps(el, new FocusEvent('focus', { __proto__: null, relatedTarget: before || null })); !GeneratorNext(c).done;) yield;
  for (var d = dispatchSteps(el, new FocusEvent('focusin', { __proto__: null, bubbles: true, relatedTarget: before || null })); !GeneratorNext(d).done;) yield;
}
function* clickSteps(el) {
  if (el.__clicking) return;
  hidden(el, '__clicking', true);
  try {
    var click = new MouseEvent('click', { __proto__: null, bubbles: true, cancelable: true, composed: true, view: global, detail: 1 });
    for (var g = activateSteps(el, click, false); !GeneratorNext(g).done;) yield;
  } finally { el.__clicking = false; }
}
/* The value of an inherited flag of HTML, as translate and spellcheck are: the state of the
   nearest element whose [attr] is one of the keywords, [yes] for '' and 'true' or 'yes', else
   [fallback]. */
function inheritedFlag(id, attr, yes, no, fallback) {
  for (var t = id; t != null && K.kind(t) === 1; t = K.parent(t)) {
    var v = K.attr(t, attr);
    if (v == null) continue;
    var l = asciiLowerCase(v);
    if (l === '' || l === yes) return true;
    if (l === no) return false;
  }
  return fallback;
}
/* The content editable state of [id]'s attribute: 'true', 'false', 'plaintext-only' or 'inherit'. */
function editableState(id) {
  var v = K.attr(id, 'contenteditable');
  if (v == null) return 'inherit';
  var l = asciiLowerCase(v);
  return l === '' || l === 'true' ? 'true' : l === 'false' ? 'false' : l === 'plaintext-only' ? 'plaintext-only' : 'inherit';
}

/* HTMLElement, of HTML, 3.2.8. */
function HTMLElement() { illegal('HTMLElement'); }
HTMLElement.prototype = ObjectCreate(Element.prototype);
sharedElementMembers(HTMLElement.prototype);
reflectAll(HTMLElement.prototype, 'title lang dir:edir accessKey inert:b autocapitalize:eautocapitalize popover:epopover ' +
  'enterKeyHint:eenterkeyhint inputMode:einputmode writingSuggestions:ewritingsuggestions');
(function (p) {
  def(p, 'translate', function () { return inheritedFlag(idOf(this), 'translate', 'yes', 'no', true); },
    function (v) { setAttr(this, 'translate', v ? 'yes' : 'no'); });
  def(p, 'spellcheck', function () { return inheritedFlag(idOf(this), 'spellcheck', 'true', 'false', true); },
    function (v) { setAttr(this, 'spellcheck', v ? 'true' : 'false'); });
  def(p, 'draggable', function () {
    var id = idOf(this), v = K.attr(id, 'draggable'), l = v == null ? '' : asciiLowerCase(v);
    if (l === 'true') return true;
    if (l === 'false') return false;
    var tag = K.tag(id);
    return tag === 'img' || (tag === 'a' && K.attr(id, 'href') != null);
  }, function (v) { setAttr(this, 'draggable', v ? 'true' : 'false'); });
  // hidden is a boolean, or 'until-found' (HTML, 6.1).
  def(p, 'hidden', function () {
    var v = K.attr(idOf(this), 'hidden');
    return v == null ? false : asciiLowerCase(v) === 'until-found' ? 'until-found' : true;
  }, function (v) {
    if (typeof v === 'string' && asciiLowerCase(v) === 'until-found') setAttr(this, 'hidden', 'until-found');
    else if (v === false || v === null || v === undefined || v === '' || v === 0 || v !== v) removeAttr(this, 'hidden');
    else setAttr(this, 'hidden', '');
  });
  def(p, 'accessKeyLabel', function () { idOf(this); return ''; });
  def(p, 'contentEditable', function () { return editableState(idOf(this)); }, function (v) {
    var l = asciiLowerCase(domString(v));
    if (l === 'inherit') removeAttr(this, 'contenteditable');
    else if (l === 'true' || l === 'false' || l === 'plaintext-only') setAttr(this, 'contenteditable', l);
    else throw new DOMException("Failed to set the 'contentEditable' property on 'HTMLElement': The value provided ('" + v + "') is not one of 'true', 'false', 'plaintext-only', or 'inherit'.", 'SyntaxError');
  });
  def(p, 'isContentEditable', function () {
    for (var t = idOf(this); t != null && K.kind(t) === 1; t = K.parent(t)) {
      var s = editableState(t);
      if (s !== 'inherit') return s !== 'false';
    }
    return false;
  });
  def(p, 'innerText', function () { return K.text(idOf(this)); }, function (v) { K.setText(idOf(this), v == null ? '' : domString(v)); });
  def(p, 'outerText', function () { return K.text(idOf(this)); }, function (v) {
    var el = wrap(idOf(this)), parent = K.parent(el.__id);
    if (parent == null) throw new DOMException("Failed to set the 'outerText' property on 'HTMLElement': This element has no parent node.", 'NoModificationAllowedError');
    insertNode(parent, textNode(v == null ? '' : domString(v)), el, 'outerText');
    removeNode(parent, el, 'outerText');
  });
  def(p, 'offsetWidth', function () { return MathRound(boxOf(this)[2]); });
  def(p, 'offsetHeight', function () { return MathRound(boxOf(this)[3]); });
  def(p, 'offsetLeft', function () { return MathRound(boxOf(this)[0]); });
  def(p, 'offsetTop', function () { return MathRound(boxOf(this)[1]); });
  def(p, 'offsetParent', function () { idOf(this); return bodyOf(rootId); });
  p.click = function () { drain(clickSteps(wrap(idOf(this)))); };
  p.showPopover = function () { idOf(this); };
  p.hidePopover = function () { idOf(this); };
  p.togglePopover = function () { idOf(this); return false; };
})(HTMLElement.prototype);
defineInterface(HTMLElement, 'HTMLElement', Element);
var HTMLUnknownElement = elementInterface('HTMLUnknownElement', HTMLElement);

/* ---- the interfaces of HTML's elements ---- */

/* HTMLHyperlinkElementUtils, of HTML, 4.6.3: the parts of the URL of an element's href. */
function hyperlinkUrl(el) {
  var v = K.attr(idOf(el), 'href');
  return v == null ? null : parseUrl(v, documentBase());
}
function hyperlinkUtils(p) {
  def(p, 'href', function () {
    var v = K.attr(idOf(this), 'href');
    if (v == null) return '';
    var u = parseUrl(v, documentBase());
    return u == null ? v : u[0];
  }, function (v) { setAttr(this, 'href', usv(v)); });
  p.toString = function () { var u = hyperlinkUrl(this); return u == null ? K.attr(this.__id, 'href') || '' : u[0]; };
  def(p, 'origin', function () { var u = hyperlinkUrl(this); return u == null ? '' : u[1]; });
  var parts = ['protocol', 'username', 'password', 'host', 'hostname', 'port', 'pathname', 'search', 'hash'];
  for (var i = 0; i < parts.length; i++) (function (name, at) {
    def(p, name, function () { var u = hyperlinkUrl(this); return u == null ? (name === 'protocol' ? ':' : '') : u[at]; }, function (v) {
      var u = hyperlinkUrl(this);
      if (u == null) return;
      var changed = K.urlSet(u[0], name, usv(v));
      if (changed != null) setAttr(this, 'href', changed[0]);
    });
  })(parts[i], i + 2);
}
/* The text of an element's children, and what a setter of it leaves: one text node. */
function textOf(el) { return K.text(idOf(el)); }
function setTextOf(el, v) { K.setText(idOf(el), domString(v)); }
/* An element of HTML's namespace named [local], as an id. */
function htmlElementId(local) { return K.create(local, XHTML_NS, null); }
/* The children of [id] that are HTML elements named [local], as ids. */
function childrenNamed(id, local) {
  return listFilter(elementIds(id), function (c) { var n = nameOf(c); return n.ns === XHTML_NS && n.local === local; });
}
function firstChildNamed(id, local) { var c = childrenNamed(id, local); return c.length ? c[0] : null; }
/* A live collection of what [source] answers for [el], the same object for [key]. */
function elementsOf(el, key, source, proto) {
  return cachedList(el, key, function () { return liveElements(source, proto); });
}
function liveChildren(el, key, local) { var id = el.__id; return elementsOf(el, key, function () { return childrenNamed(id, local); }); }
function liveDescendants(el, key, local) {
  var id = el.__id;
  return elementsOf(el, key, function () {
    return listFilter(queryAll(id, local), function (e) { return nameOf(e).ns === XHTML_NS; });
  });
}

var HTMLHtmlElement = elementInterface('HTMLHtmlElement', HTMLElement, 'version');
var HTMLHeadElement = elementInterface('HTMLHeadElement', HTMLElement);
var HTMLTitleElement = elementInterface('HTMLTitleElement', HTMLElement, '', function (p) {
  def(p, 'text', function () { return textOf(this); }, function (v) { setTextOf(this, v); });
});
var HTMLBaseElement = elementInterface('HTMLBaseElement', HTMLElement, 'target', function (p) {
  def(p, 'href', function () {
    var v = K.attr(idOf(this), 'href'), url = K.location();
    if (v == null) return url;
    var u = parseUrl(v, url);
    return u == null ? v : u[0];
  }, function (v) { setAttr(this, 'href', domString(v)); });
});
var HTMLLinkElement = elementInterface('HTMLLinkElement', HTMLElement, 'href:u crossOrigin:ecors rel as:eas relList=rel:tlinkrel ' +
  'media integrity hreflang type sizes:t imageSrcset=imagesrcset imageSizes=imagesizes referrerPolicy:ereferrer blocking:tblocking ' +
  'disabled:b fetchPriority:efetchpriority charset rev target', function (p) {
  def(p, 'sheet', function () { idOf(this); return null; });
});
var HTMLMetaElement = elementInterface('HTMLMetaElement', HTMLElement, 'name httpEquiv=http-equiv content media scheme');
var HTMLStyleElement = elementInterface('HTMLStyleElement', HTMLElement, 'media type blocking:tblocking', function (p) {
  def(p, 'disabled', function () { idOf(this); return false; }, function () { idOf(this); });
  def(p, 'sheet', function () { idOf(this); return null; });
});
/* The window handlers of a body element, or a frameset element, are the window's, read and set through it. */
var HTMLBodyElement = elementInterface('HTMLBodyElement', HTMLElement, 'text:N link:N vLink=vlink:N aLink=alink:N bgColor=bgcolor:N background',
  function (p) { defineHandlers(p, BODY_WINDOW_HANDLERS, true); });
var HTMLHeadingElement = elementInterface('HTMLHeadingElement', HTMLElement, 'align');
var HTMLParagraphElement = elementInterface('HTMLParagraphElement', HTMLElement, 'align');
var HTMLHRElement = elementInterface('HTMLHRElement', HTMLElement, 'align color noShade=noshade:b size width');
var HTMLPreElement = elementInterface('HTMLPreElement', HTMLElement, 'width:l0');
var HTMLQuoteElement = elementInterface('HTMLQuoteElement', HTMLElement, 'cite:u');
var HTMLOListElement = elementInterface('HTMLOListElement', HTMLElement, 'reversed:b start:l1 type compact:b');
var HTMLUListElement = elementInterface('HTMLUListElement', HTMLElement, 'compact:b type');
var HTMLMenuElement = elementInterface('HTMLMenuElement', HTMLElement, 'compact:b');
var HTMLLIElement = elementInterface('HTMLLIElement', HTMLElement, 'value:l0 type');
var HTMLDListElement = elementInterface('HTMLDListElement', HTMLElement, 'compact:b');
var HTMLDivElement = elementInterface('HTMLDivElement', HTMLElement, 'align');
var HTMLAnchorElement = elementInterface('HTMLAnchorElement', HTMLElement, 'target download ping rel relList=rel:trel hreflang type ' +
  'referrerPolicy:ereferrer coords charset name rev shape', function (p) {
  hyperlinkUtils(p);
  def(p, 'text', function () { return textOf(this); }, function (v) { setTextOf(this, v); });
});
var HTMLDataElement = elementInterface('HTMLDataElement', HTMLElement, 'value');
var HTMLTimeElement = elementInterface('HTMLTimeElement', HTMLElement, 'dateTime=datetime');
var HTMLSpanElement = elementInterface('HTMLSpanElement', HTMLElement);
var HTMLBRElement = elementInterface('HTMLBRElement', HTMLElement, 'clear');
var HTMLModElement = elementInterface('HTMLModElement', HTMLElement, 'cite:u dateTime=datetime');
var HTMLPictureElement = elementInterface('HTMLPictureElement', HTMLElement);
var HTMLSourceElement = elementInterface('HTMLSourceElement', HTMLElement, 'src:u type srcset sizes media width:U0 height:U0');
/* A dimension of an image as a page gives it: its box once laid out, else its attribute. */
function imageSize(el, attr, at) {
  var v = K.attr(idOf(el), attr), n = v == null ? null : parseHtmlInteger(v, true);
  return n !== null && n <= MAX_LONG ? n : MathRound(boxOf(el)[at]);
}
function sizeAttribute(p, name, at) {
  def(p, name, function () { return imageSize(this, name, at); }, function (v) { setAttr(this, name, String(unsignedValue(unsignedLong(v), 0))); });
}
var HTMLImageElement = elementInterface('HTMLImageElement', HTMLElement, 'alt src:u srcset sizes crossOrigin:ecors useMap=usemap ' +
  'isMap=ismap:b referrerPolicy:ereferrer decoding:edecoding loading:eloading fetchPriority:efetchpriority name lowsrc:u align ' +
  'hspace:U0 vspace:U0 longDesc=longdesc:u border:N', function (p) {
  sizeAttribute(p, 'width', 2);
  sizeAttribute(p, 'height', 3);
  def(p, 'naturalWidth', function () { var v = parseInt(K.attr(idOf(this), 'width'), 10); return v > 0 ? v : 0; });
  def(p, 'naturalHeight', function () { var v = parseInt(K.attr(idOf(this), 'height'), 10); return v > 0 ? v : 0; });
  def(p, 'complete', function () { idOf(this); return true; });
  def(p, 'currentSrc', function () { var v = K.attr(idOf(this), 'src'); return v == null ? '' : resolvedUrl(v); });
  def(p, 'x', function () { return MathRound(boxOf(this)[0]); });
  def(p, 'y', function () { return MathRound(boxOf(this)[1]); });
  p.decode = function () { idOf(this); return resolved(); };
});
function nestedBrowsingContext(p) {
  def(p, 'contentWindow', function () { idOf(this); return null; });
  def(p, 'contentDocument', function () { idOf(this); return null; });
}
var HTMLIFrameElement = elementInterface('HTMLIFrameElement', HTMLElement, 'src:u srcdoc name sandbox:tsandbox allow ' +
  'allowFullscreen=allowfullscreen:b width height referrerPolicy:ereferrer loading:eloading align scrolling frameBorder=frameborder ' +
  'longDesc=longdesc:u marginHeight=marginheight:N marginWidth=marginwidth:N', nestedBrowsingContext);
var HTMLEmbedElement = elementInterface('HTMLEmbedElement', HTMLElement, 'src:u type width height align name');
var HTMLObjectElement = elementInterface('HTMLObjectElement', HTMLElement, 'data:u type name useMap=usemap width height align archive ' +
  'code declare:b hspace:U0 standby vspace:U0 codeBase=codebase:u codeType=codetype border:N', function (p) {
  nestedBrowsingContext(p);
  formControl(p);
});
var HTMLParamElement = elementInterface('HTMLParamElement', HTMLElement, 'name value type valueType=valuetype');
var HTMLMediaElement = elementInterface('HTMLMediaElement', HTMLElement, 'src:u crossOrigin:ecors preload:epreload loading:eloading ' +
  'autoplay:b loop:b controls:b defaultMuted=muted:b', function (p) {
  p.play = function () { idOf(this); return resolved(); };
  p.pause = function () { idOf(this); };
  p.load = function () { idOf(this); };
  p.canPlayType = function () { idOf(this); return ''; };
  def(p, 'currentSrc', function () { var v = K.attr(idOf(this), 'src'); return v == null ? '' : resolvedUrl(v); });
  def(p, 'paused', function () { idOf(this); return true; });
  def(p, 'ended', function () { idOf(this); return false; });
  def(p, 'seeking', function () { idOf(this); return false; });
  def(p, 'error', function () { idOf(this); return null; });
  def(p, 'networkState', function () { idOf(this); return 0; });
  def(p, 'readyState', function () { idOf(this); return 0; });
  def(p, 'duration', function () { idOf(this); return NaN; });
  def(p, 'currentTime', function () { idOf(this); return 0; }, function () { idOf(this); });
  def(p, 'volume', function () { idOf(this); return 1; }, function () { idOf(this); });
  def(p, 'muted', function () { idOf(this); return false; }, function () { idOf(this); });
  def(p, 'playbackRate', function () { idOf(this); return 1; }, function () { idOf(this); });
  def(p, 'defaultPlaybackRate', function () { idOf(this); return 1; }, function () { idOf(this); });
});
constants(HTMLMediaElement, ['NETWORK_EMPTY', 'NETWORK_IDLE', 'NETWORK_LOADING', 'NETWORK_NO_SOURCE'], 0);
constants(HTMLMediaElement, ['HAVE_NOTHING', 'HAVE_METADATA', 'HAVE_CURRENT_DATA', 'HAVE_FUTURE_DATA', 'HAVE_ENOUGH_DATA'], 0);
var HTMLVideoElement = elementInterface('HTMLVideoElement', HTMLMediaElement, 'width:U0 height:U0 poster:u playsInline=playsinline:b', function (p) {
  def(p, 'videoWidth', function () { idOf(this); return 0; });
  def(p, 'videoHeight', function () { idOf(this); return 0; });
});
var HTMLAudioElement = elementInterface('HTMLAudioElement', HTMLMediaElement);
var HTMLTrackElement = elementInterface('HTMLTrackElement', HTMLElement, 'kind:etrackkind src:u srclang label default:b', function (p) {
  def(p, 'readyState', function () { idOf(this); return 0; });
  def(p, 'track', function () { idOf(this); return null; });
});
constants(HTMLTrackElement, ['NONE', 'LOADING', 'LOADED', 'ERROR'], 0);
var HTMLMapElement = elementInterface('HTMLMapElement', HTMLElement, 'name', function (p) {
  def(p, 'areas', function () { return liveDescendants(wrap(idOf(this)), 'areas', 'area'); });
});
var HTMLAreaElement = elementInterface('HTMLAreaElement', HTMLElement, 'alt coords shape target download ping rel relList=rel:trel ' +
  'referrerPolicy:ereferrer hreflang type noHref=nohref:b', hyperlinkUtils);
var HTMLCanvasElement = elementInterface('HTMLCanvasElement', HTMLElement, 'width:U300 height:U150', function (p) {
  p.getContext = function () { idOf(this); return null; };
  p.toDataURL = function () { idOf(this); return 'data:,'; };
  p.toBlob = function () { idOf(this); };
});
var HTMLScriptElement = elementInterface('HTMLScriptElement', HTMLElement, 'src:u type noModule=nomodule:b defer:b crossOrigin:ecors ' +
  'integrity referrerPolicy:ereferrer fetchPriority:efetchpriority blocking:tblocking charset event htmlFor=for', function (p) {
  def(p, 'async', function () {
    var el = wrap(idOf(this));
    return WeakMapGet(forceAsync, el) === true || K.attr(el.__id, 'async') != null;
  }, function (v) {
    var el = wrap(idOf(this));
    WeakMapSet(forceAsync, el, false);
    if (v) setAttr(el, 'async', ''); else removeAttr(el, 'async');
  });
  def(p, 'text', function () { return textOf(this); }, function (v) { setTextOf(this, v); });
});
HTMLScriptElement.supports = function (type) { var t = domString(type); return t === 'classic' || t === 'module' || t === 'importmap'; };
var HTMLTemplateElement = elementInterface('HTMLTemplateElement', HTMLElement);
var HTMLSlotElement = elementInterface('HTMLSlotElement', HTMLElement, 'name', function (p) {
  p.assignedNodes = function () { idOf(this); return []; };
  p.assignedElements = function () { idOf(this); return []; };
  p.assign = function () { idOf(this); };
});
var HTMLDetailsElement = elementInterface('HTMLDetailsElement', HTMLElement, 'name open:b');
var HTMLDialogElement = elementInterface('HTMLDialogElement', HTMLElement, 'open:b', function (p) {
  p.show = function () { setAttr(wrap(idOf(this)), 'open', ''); };
  p.showModal = function () { setAttr(wrap(idOf(this)), 'open', ''); };
  p.close = function (value) {
    var el = wrap(idOf(this));
    if (K.attr(el.__id, 'open') == null) return;
    removeAttr(el, 'open');
    if (value !== undefined) el.returnValue = domString(value);
    fireEvent(el, new Event('close'));
  };
  p.requestClose = p.close;
});
var HTMLMarqueeElement = elementInterface('HTMLMarqueeElement', HTMLElement, 'behavior:ebehavior bgColor=bgcolor direction:edirection ' +
  'height hspace:U0 scrollAmount=scrollamount:U6 scrollDelay=scrolldelay:U85 trueSpeed=truespeed:b vspace:U0 width', function (p) {
  p.start = function () { idOf(this); };
  p.stop = function () { idOf(this); };
});
var HTMLFrameSetElement = elementInterface('HTMLFrameSetElement', HTMLElement, 'cols rows',
  function (p) { defineHandlers(p, BODY_WINDOW_HANDLERS, true); });
var HTMLFrameElement = elementInterface('HTMLFrameElement', HTMLElement, 'name scrolling src:u frameBorder=frameborder ' +
  'longDesc=longdesc:u noResize=noresize:b marginHeight=marginheight:N marginWidth=marginwidth:N', nestedBrowsingContext);
var HTMLDirectoryElement = elementInterface('HTMLDirectoryElement', HTMLElement, 'compact:b');
var HTMLFontElement = elementInterface('HTMLFontElement', HTMLElement, 'color:N face size');

/* ---- tables, of HTML, 4.9 ---- */

/* The rows of a table, in the order HTML gives them: those of its thead elements, its own and
   those of its tbody elements, then those of its tfoot elements. */
function tableRows(id) {
  var out = [], c = elementIds(id), i, j;
  function add(section) { var r = childrenNamed(section, 'tr'); for (j = 0; j < r.length; j++) ArrayPush(out, r[j]); }
  for (i = 0; i < c.length; i++) if (K.tag(c[i]) === 'thead' && nameOf(c[i]).ns === XHTML_NS) add(c[i]);
  for (i = 0; i < c.length; i++) {
    var n = nameOf(c[i]);
    if (n.ns !== XHTML_NS) continue;
    if (n.local === 'tr') ArrayPush(out, c[i]); else if (n.local === 'tbody') add(c[i]);
  }
  for (i = 0; i < c.length; i++) if (K.tag(c[i]) === 'tfoot' && nameOf(c[i]).ns === XHTML_NS) add(c[i]);
  return out;
}
function indexError(what, index, count) {
  return new DOMException(what + ': The index provided (' + index + ') is outside the range [-1, ' + count + '].', 'IndexSizeError');
}
/* Puts a new [local] element in [parentId] at [index] of the ones [rows] answers, as insertRow and insertCell do. */
function insertAtIndex(parentId, rows, index, local, what) {
  var at = webIdlLong(index === undefined ? -1 : index);
  if (at < -1 || at > rows.length) throw indexError(what, at, rows.length);
  var made = wrap(htmlElementId(local));
  if (at === -1 || at === rows.length) {
    var last = rows.length ? rows[rows.length - 1] : null;
    insertNode(last != null && K.parent(last) !== parentId ? K.parent(last) : parentId, made, null, what);
  } else {
    insertNode(K.parent(rows[at]), made, wrap(rows[at]), what);
  }
  return made;
}
function deleteAtIndex(rows, index, what) {
  var at = webIdlLong(index);
  if (at === -1) at = rows.length - 1;
  if (at < 0 || at >= rows.length) { if (at === -1 && !rows.length) return; throw indexError(what, at, rows.length); }
  removeNode(K.parent(rows[at]), wrap(rows[at]), what);
}
/* A child of a table that a setter of caption, tHead or tFoot puts in place of the first of its name. */
function setTablePart(table, local, value, before) {
  var id = idOf(table);
  if (value !== null && !(isNode(value) && nameOf(value.__id).local === local && nameOf(value.__id).ns === XHTML_NS)) {
    throw new TypeError("Failed to set the property on 'HTMLTableElement': The provided value is not of type 'HTMLTable" +
      (local === 'caption' ? 'CaptionElement' : 'SectionElement') + "'.");
  }
  var old = firstChildNamed(id, local);
  if (old != null) removeNode(id, wrap(old), local);
  if (value !== null) insertNode(id, value, before(id), local);
}
function tableHeadBefore(id) {
  var c = elementIds(id);
  for (var i = 0; i < c.length; i++) { var l = nameOf(c[i]).local; if (l !== 'caption' && l !== 'colgroup') return wrap(c[i]); }
  return null;
}
function firstChildWrap(id) { var c = childIds(id); return c.length ? wrap(c[0]) : null; }
function createTablePart(table, local, before) {
  var id = idOf(table), old = firstChildNamed(id, local);
  if (old != null) return wrap(old);
  return insertNode(id, wrap(htmlElementId(local)), before(id), 'create');
}
function deleteTablePart(table, local) {
  var id = idOf(table), old = firstChildNamed(id, local);
  if (old != null) removeNode(id, wrap(old), 'delete');
}
var HTMLTableElement = elementInterface('HTMLTableElement', HTMLElement, 'align border frame rules summary width bgColor=bgcolor:N ' +
  'cellPadding=cellpadding:N cellSpacing=cellspacing:N', function (p) {
  def(p, 'caption', function () { return wrap(firstChildNamed(idOf(this), 'caption')); },
    function (v) { setTablePart(this, 'caption', v, firstChildWrap); });
  def(p, 'tHead', function () { return wrap(firstChildNamed(idOf(this), 'thead')); },
    function (v) { setTablePart(this, 'thead', v, tableHeadBefore); });
  def(p, 'tFoot', function () { return wrap(firstChildNamed(idOf(this), 'tfoot')); },
    function (v) { setTablePart(this, 'tfoot', v, function () { return null; }); });
  def(p, 'tBodies', function () { return liveChildren(wrap(idOf(this)), 'tBodies', 'tbody'); });
  def(p, 'rows', function () { var id = idOf(this); return elementsOf(wrap(id), 'rows', function () { return tableRows(id); }); });
  p.createCaption = function () { return createTablePart(this, 'caption', firstChildWrap); };
  p.deleteCaption = function () { deleteTablePart(this, 'caption'); };
  p.createTHead = function () { return createTablePart(this, 'thead', tableHeadBefore); };
  p.deleteTHead = function () { deleteTablePart(this, 'thead'); };
  p.createTFoot = function () { return createTablePart(this, 'tfoot', function () { return null; }); };
  p.deleteTFoot = function () { deleteTablePart(this, 'tfoot'); };
  p.createTBody = function () {
    var id = idOf(this), bodies = childrenNamed(id, 'tbody'), last = bodies.length ? bodies[bodies.length - 1] : null;
    return insertNode(id, wrap(htmlElementId('tbody')), last == null ? null : wrap(siblingId(last, 1, false)), 'createTBody');
  };
  p.insertRow = function (index) {
    var id = idOf(this), rows = tableRows(id), at = webIdlLong(index === undefined ? -1 : index);
    if (at < -1 || at > rows.length) throw indexError("Failed to execute 'insertRow' on 'HTMLTableElement'", at, rows.length);
    if (!rows.length && firstChildNamed(id, 'tbody') == null) {
      var body = insertNode(id, wrap(htmlElementId('tbody')), null, 'insertRow');
      return insertNode(body.__id, wrap(htmlElementId('tr')), null, 'insertRow');
    }
    if (!rows.length) {
      var bodies = childrenNamed(id, 'tbody');
      return insertNode(bodies[bodies.length - 1], wrap(htmlElementId('tr')), null, 'insertRow');
    }
    return insertAtIndex(id, rows, at, 'tr', "Failed to execute 'insertRow' on 'HTMLTableElement'");
  };
  p.deleteRow = function (index) { deleteAtIndex(tableRows(idOf(this)), index, "Failed to execute 'deleteRow' on 'HTMLTableElement'"); };
});
var HTMLTableCaptionElement = elementInterface('HTMLTableCaptionElement', HTMLElement, 'align');
var HTMLTableColElement = elementInterface('HTMLTableColElement', HTMLElement, 'span:C1,1,1000 align ch=char chOff=charoff vAlign=valign width');
var HTMLTableSectionElement = elementInterface('HTMLTableSectionElement', HTMLElement, 'align ch=char chOff=charoff vAlign=valign', function (p) {
  def(p, 'rows', function () { return liveChildren(wrap(idOf(this)), 'rows', 'tr'); });
  p.insertRow = function (index) {
    var id = idOf(this);
    return insertAtIndex(id, childrenNamed(id, 'tr'), index, 'tr', "Failed to execute 'insertRow' on 'HTMLTableSectionElement'");
  };
  p.deleteRow = function (index) { deleteAtIndex(childrenNamed(idOf(this), 'tr'), index, "Failed to execute 'deleteRow' on 'HTMLTableSectionElement'"); };
});
/* The cells of a row, its td and th children. */
function rowCells(id) { return listFilter(elementIds(id), function (c) { var n = nameOf(c); return n.ns === XHTML_NS && (n.local === 'td' || n.local === 'th'); }); }
/* The table a row belongs to, as an id: its parent, or the parent of its section. */
function rowTable(id) {
  var p = K.parent(id);
  if (p == null || K.kind(p) !== 1) return null;
  var l = nameOf(p).local;
  if (l === 'table') return p;
  if (l !== 'thead' && l !== 'tbody' && l !== 'tfoot') return null;
  var t = K.parent(p);
  return t != null && K.kind(t) === 1 && nameOf(t).local === 'table' ? t : null;
}
var HTMLTableRowElement = elementInterface('HTMLTableRowElement', HTMLElement, 'align ch=char chOff=charoff vAlign=valign bgColor=bgcolor:N', function (p) {
  def(p, 'rowIndex', function () { var id = idOf(this), t = rowTable(id); return t == null ? -1 : ArrayIndexOf(tableRows(t), id); });
  def(p, 'sectionRowIndex', function () { var id = idOf(this), s = K.parent(id); return s == null ? -1 : ArrayIndexOf(childrenNamed(s, 'tr'), id); });
  def(p, 'cells', function () { var id = idOf(this); return elementsOf(wrap(id), 'cells', function () { return rowCells(id); }); });
  p.insertCell = function (index) {
    var id = idOf(this), cells = rowCells(id), at = webIdlLong(index === undefined ? -1 : index);
    if (at < -1 || at > cells.length) throw indexError("Failed to execute 'insertCell' on 'HTMLTableRowElement'", at, cells.length);
    return insertNode(id, wrap(htmlElementId('td')), at === -1 || at === cells.length ? null : wrap(cells[at]), 'insertCell');
  };
  p.deleteCell = function (index) { deleteAtIndex(rowCells(idOf(this)), index, "Failed to execute 'deleteCell' on 'HTMLTableRowElement'"); };
});
var HTMLTableCellElement = elementInterface('HTMLTableCellElement', HTMLElement, 'colSpan=colspan:C1,1,1000 rowSpan=rowspan:C1,0,65534 ' +
  'headers scope:escope abbr align axis height width ch=char chOff=charoff noWrap=nowrap:b vAlign=valign bgColor=bgcolor:N', function (p) {
  def(p, 'cellIndex', function () { var id = idOf(this), r = K.parent(id); return r == null || K.kind(r) !== 1 || nameOf(r).local !== 'tr' ? -1 : ArrayIndexOf(rowCells(r), id); });
});
"""
