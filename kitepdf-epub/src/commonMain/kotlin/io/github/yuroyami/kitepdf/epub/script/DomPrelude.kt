package io.github.yuroyami.kitepdf.epub.script

/**
 * The DOM a chapter's scripts see (#41), written in JavaScript over the host functions that
 * [io.github.yuroyami.kitepdf.epub.EpubScriptSession] defines under `__kite`: each node is a
 * number there, and an object here. It runs before the chapter's own scripts, in the global
 * scope that is their `window`.
 *
 * Written in the JavaScript every engine of the library runs: functions and prototypes, no
 * `class`, no `async`. The source is kept in two literals joined at run time, since a JVM class
 * file holds no string constant over 64 KB.
 */
internal val DOM_PRELUDE: String = buildString {
    append(DOM_PRELUDE_HEAD)
    append(DOM_PRELUDE_TAIL)
}

/** The first part of [DOM_PRELUDE]: the helpers, the nodes and the style objects. */
private const val DOM_PRELUDE_HEAD: String = """(function (global) {
var K = global.__kite;
var wrappers = new Map();
var rootId = K.root();

function def(proto, name, get, set) {
  Object.defineProperty(proto, name, { get: get, set: set, configurable: true, enumerable: true });
}
function hidden(obj, name, value) {
  Object.defineProperty(obj, name, { value: value, writable: true, configurable: true, enumerable: false });
}
function report(e) {
  try { K.error(e && e.name && e.message !== undefined ? e.name + ': ' + e.message : String(e)); } catch (ignored) {}
}
function kebab(name) {
  if (name === 'cssFloat') return 'float';
  var out = String(name).replace(/[A-Z]/g, function (c) { return '-' + c.toLowerCase(); });
  if (/^(webkit|moz|ms|o)-/.test(out)) out = '-' + out;
  return out;
}
/* The properties a style declaration answers, as a browser's does: any other name is not CSS. */
var CSS_PROPERTIES = {};
('align-content align-items align-self all animation animation-delay animation-direction ' +
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
  'y z-index zoom ').split(' ').forEach(function (n) { if (n) CSS_PROPERTIES[n] = true; });
/* The CSS property a style declaration's key names, or null for a key that names none. */
function cssProperty(key) {
  var name = key.indexOf('-') >= 0 ? key.toLowerCase() : kebab(key);
  var bare = name.replace(/^-(webkit|moz|ms|o|epub)-/, '');
  return CSS_PROPERTIES.hasOwnProperty(bare) ? name : null;
}
/* A declared value as a browser gives it back: a number keeps its leading zero, so `.5` reads `0.5`. */
function cssValue(value) {
  return String(value).replace(/(^|[\s,(\/+*-])\.(\d)/g, '$10.$2');
}
function camel(name) {
  return String(name).replace(/-([a-z])/g, function (m, c) { return c.toUpperCase(); });
}
function list(items) {
  hidden(items, 'item', function (i) { return this[i] === undefined ? null : this[i]; });
  return items;
}

function DOMException(message, name) {
  this.message = message === undefined ? '' : String(message);
  this.name = name || 'Error';
}
DOMException.prototype = Object.create(Error.prototype);
DOMException.prototype.constructor = DOMException;
function check(error, what) {
  if (error != null) throw new DOMException(what + ': ' + error, error);
}

function idOf(node) {
  if (node == null || typeof node !== 'object' || typeof node.__id !== 'number') throw new TypeError('parameter is not a Node');
  return node.__id;
}
function wrap(id) {
  if (id == null) return null;
  var w = wrappers.get(id);
  if (w) return w;
  var kind = K.kind(id);
  var proto = kind === 9 ? Document.prototype : kind === 3 ? Text.prototype : kind === 11 ? DocumentFragment.prototype : protoFor(K.tag(id));
  w = Object.create(proto);
  hidden(w, '__id', id);
  wrappers.set(id, w);
  return w;
}
function wraps(ids) {
  var out = [];
  if (ids) for (var i = 0; i < ids.length; i++) out.push(wrap(ids[i]));
  return list(out);
}
function listenersOf(t) {
  if (!t.__listeners) hidden(t, '__listeners', {});
  return t.__listeners;
}

/* ---- events ---- */

function EventTarget() {}
EventTarget.prototype.addEventListener = function (type, fn, options) {
  if (fn == null) return;
  var capture = options === true || !!(options && typeof options === 'object' && options.capture);
  var once = !!(options && typeof options === 'object' && options.once);
  var all = listenersOf(this);
  var entries = all[type] || (all[type] = []);
  for (var i = 0; i < entries.length; i++) if (entries[i].fn === fn && entries[i].capture === capture) return;
  entries.push({ fn: fn, capture: capture, once: once, removed: false });
};
EventTarget.prototype.removeEventListener = function (type, fn, options) {
  var capture = options === true || !!(options && typeof options === 'object' && options.capture);
  var entries = listenersOf(this)[type];
  if (!entries) return;
  for (var i = 0; i < entries.length; i++) {
    if (entries[i].fn === fn && entries[i].capture === capture) { entries[i].removed = true; entries.splice(i, 1); return; }
  }
};
EventTarget.prototype.dispatchEvent = function (event) {
  if (!(event instanceof Event)) throw new TypeError('parameter is not an Event');
  event.isTrusted = false;
  return dispatch(this, event);
};

function Event(type, init) {
  init = init || {};
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
Event.NONE = 0; Event.CAPTURING_PHASE = 1; Event.AT_TARGET = 2; Event.BUBBLING_PHASE = 3;
Event.prototype.preventDefault = function () { if (this.cancelable) this.defaultPrevented = true; };
Event.prototype.stopPropagation = function () { this.__stop = true; };
Event.prototype.stopImmediatePropagation = function () { this.__stop = true; this.__stopNow = true; };
Event.prototype.initEvent = function (type, bubbles, cancelable) { this.type = String(type); this.bubbles = !!bubbles; this.cancelable = !!cancelable; };
Event.prototype.composedPath = function () { var out = []; for (var t = this.target; t; t = parentTarget(t)) out.push(t); return out; };
def(Event.prototype, 'srcElement', function () { return this.target; });
def(Event.prototype, 'returnValue', function () { return !this.defaultPrevented; }, function (v) { if (!v) this.preventDefault(); });
def(Event.prototype, 'cancelBubble', function () { return this.__stop; }, function (v) { if (v) this.__stop = true; });

function subEvent(parent, fill) {
  var E = function (type, init) { parent.call(this, type, init); fill.call(this, init || {}); };
  E.prototype = Object.create(parent.prototype);
  E.prototype.constructor = E;
  return E;
}
var UIEvent = subEvent(Event, function (init) { this.view = init.view || null; this.detail = init.detail || 0; });
var MouseEvent = subEvent(UIEvent, function (init) {
  this.clientX = init.clientX || 0; this.clientY = init.clientY || 0;
  this.screenX = init.screenX || 0; this.screenY = init.screenY || 0;
  this.pageX = init.pageX === undefined ? this.clientX : init.pageX; this.pageY = init.pageY === undefined ? this.clientY : init.pageY;
  this.offsetX = init.offsetX || 0; this.offsetY = init.offsetY || 0;
  this.button = init.button || 0; this.buttons = init.buttons || 0;
  this.ctrlKey = !!init.ctrlKey; this.shiftKey = !!init.shiftKey; this.altKey = !!init.altKey; this.metaKey = !!init.metaKey;
  this.relatedTarget = init.relatedTarget || null;
});
MouseEvent.prototype.getModifierState = function () { return false; };
var PointerEvent = subEvent(MouseEvent, function (init) {
  this.pointerId = init.pointerId || 0; this.pointerType = init.pointerType || '';
  this.isPrimary = !!init.isPrimary; this.width = init.width || 1; this.height = init.height || 1; this.pressure = init.pressure || 0;
});
var KeyboardEvent = subEvent(UIEvent, function (init) {
  this.key = init.key || ''; this.code = init.code || ''; this.keyCode = init.keyCode || 0; this.which = this.keyCode;
  this.ctrlKey = !!init.ctrlKey; this.shiftKey = !!init.shiftKey; this.altKey = !!init.altKey; this.metaKey = !!init.metaKey;
  this.repeat = !!init.repeat;
});
var FocusEvent = subEvent(UIEvent, function (init) { this.relatedTarget = init.relatedTarget || null; });
var InputEvent = subEvent(UIEvent, function (init) { this.data = init.data === undefined ? null : init.data; this.inputType = init.inputType || ''; });
var CustomEvent = subEvent(Event, function (init) { this.detail = init.detail === undefined ? null : init.detail; });
CustomEvent.prototype.initCustomEvent = function (type, bubbles, cancelable, detail) { this.initEvent(type, bubbles, cancelable); this.detail = detail; };

function parentTarget(t) {
  if (t === global) return null;
  if (t === document) return global;
  if (typeof t.__id !== 'number') return null;
  var p = K.parent(t.__id);
  return p == null ? null : wrap(p);
}
function dispatch(target, event) {
  event.target = target;
  var path = [];
  for (var t = target; t; t = parentTarget(t)) path.push(t);
  event.eventPhase = 1;
  for (var i = path.length - 1; i > 0 && !event.__stop; i--) invoke(path[i], event, 1);
  if (!event.__stop) { event.eventPhase = 2; invoke(path[0], event, 2); }
  if (event.bubbles) {
    event.eventPhase = 3;
    for (var j = 1; j < path.length && !event.__stop; j++) invoke(path[j], event, 3);
  }
  event.eventPhase = 0;
  event.currentTarget = null;
  return !event.defaultPrevented;
}
function invoke(t, event, phase) {
  event.currentTarget = t;
  if (phase !== 1) {
    var h = handlerOf(t, event.type);
    if (h && call(h, t, event) === false) event.preventDefault();
  }
  var entries = (listenersOf(t)[event.type] || []).slice();
  for (var i = 0; i < entries.length && !event.__stopNow; i++) {
    var l = entries[i];
    if (l.removed) continue;
    if (phase === 1 && !l.capture) continue;
    if (phase === 3 && l.capture) continue;
    if (l.once) t.removeEventListener(event.type, l.fn, l.capture);
    call(l.fn, t, event);
  }
}
function call(fn, t, event) {
  try {
    if (typeof fn === 'function') return fn.call(t, event);
    if (fn && typeof fn.handleEvent === 'function') return fn.handleEvent(event);
  } catch (e) { report(e); }
  return undefined;
}

var HANDLED = ['abort', 'animationend', 'animationstart', 'blur', 'change', 'click', 'contextmenu', 'dblclick', 'error',
  'focus', 'input', 'keydown', 'keypress', 'keyup', 'load', 'mousedown', 'mouseenter', 'mouseleave', 'mousemove', 'mouseout',
  'mouseover', 'mouseup', 'pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'reset', 'resize', 'scroll', 'select',
  'submit', 'toggle', 'touchcancel', 'touchend', 'touchmove', 'touchstart', 'transitionend', 'wheel'];
var BODY_TO_WINDOW = { load: 1, error: 1, resize: 1, scroll: 1, focus: 1, blur: 1 };

function handlerOf(t, type) {
  var key = 'on' + type;
  if (t.__handlers && Object.prototype.hasOwnProperty.call(t.__handlers, key)) return t.__handlers[key];
  var source = t;
  if (t === global) { if (!BODY_TO_WINDOW[type]) return null; source = document.body; }
  if (source == null || typeof source.__id !== 'number' || source === document) return null;
  var code = K.attr(source.__id, key);
  if (code == null) return null;
  if (!source.__compiled) hidden(source, '__compiled', {});
  var cached = source.__compiled[key];
  if (cached && cached.code === code) return cached.fn;
  var fn = null;
  try {
    fn = new Function('event', 'with (document) { with (this === window ? {} : this) { return (function (event) {\n' + code + '\n}).call(this, event); } }');
  } catch (e) { report(e); }
  source.__compiled[key] = { code: code, fn: fn };
  return fn;
}
function defineHandlers(target) {
  HANDLED.forEach(function (type) {
    def(target, 'on' + type, function () {
      return this.__handlers && Object.prototype.hasOwnProperty.call(this.__handlers, 'on' + type) ? this.__handlers['on' + type] : handlerOf(this, type);
    }, function (fn) {
      if (!this.__handlers) hidden(this, '__handlers', {});
      this.__handlers['on' + type] = typeof fn === 'function' ? fn : null;
    });
  });
}

/* ---- nodes ---- */

function Node() {}
Node.prototype = Object.create(EventTarget.prototype);
Node.prototype.constructor = Node;
Node.ELEMENT_NODE = 1; Node.TEXT_NODE = 3; Node.COMMENT_NODE = 8; Node.DOCUMENT_NODE = 9; Node.DOCUMENT_FRAGMENT_NODE = 11;
def(Node.prototype, 'nodeType', function () { return K.kind(this.__id); });
def(Node.prototype, 'nodeName', function () {
  var kind = K.kind(this.__id);
  return kind === 3 ? '#text' : kind === 9 ? '#document' : kind === 11 ? '#document-fragment' : K.tag(this.__id).toUpperCase();
});
def(Node.prototype, 'parentNode', function () { return wrap(K.parent(this.__id)); });
def(Node.prototype, 'parentElement', function () { var p = K.parent(this.__id); return p == null || p === rootId || K.kind(p) !== 1 ? null : wrap(p); });
def(Node.prototype, 'childNodes', function () { return wraps(K.children(this.__id)); });
def(Node.prototype, 'firstChild', function () { var c = K.children(this.__id); return c && c.length ? wrap(c[0]) : null; });
def(Node.prototype, 'lastChild', function () { var c = K.children(this.__id); return c && c.length ? wrap(c[c.length - 1]) : null; });
function sibling(node, step, elementsOnly) {
  var p = K.parent(node.__id);
  if (p == null) return null;
  var c = K.children(p);
  for (var i = c.indexOf(node.__id) + step; i >= 0 && i < c.length; i += step) {
    if (!elementsOnly || K.kind(c[i]) === 1) return wrap(c[i]);
  }
  return null;
}
def(Node.prototype, 'nextSibling', function () { return sibling(this, 1, false); });
def(Node.prototype, 'previousSibling', function () { return sibling(this, -1, false); });
def(Node.prototype, 'ownerDocument', function () { return this === document ? null : document; });
def(Node.prototype, 'isConnected', function () { return K.connected(this.__id); });
def(Node.prototype, 'textContent', function () { return this === document ? null : K.text(this.__id); },
  function (v) { if (this !== document) K.setText(this.__id, v == null ? '' : String(v)); });
def(Node.prototype, 'nodeValue', function () { return K.kind(this.__id) === 3 ? K.text(this.__id) : null; },
  function (v) { if (K.kind(this.__id) === 3) K.setText(this.__id, v == null ? '' : String(v)); });
Node.prototype.hasChildNodes = function () { var c = K.children(this.__id); return !!(c && c.length); };
Node.prototype.appendChild = function (child) { check(K.insert(this.__id, idOf(child), null), 'appendChild'); return child; };
Node.prototype.insertBefore = function (child, ref) { check(K.insert(this.__id, idOf(child), ref == null ? null : idOf(ref)), 'insertBefore'); return child; };
Node.prototype.removeChild = function (child) { check(K.remove(this.__id, idOf(child)), 'removeChild'); return child; };
Node.prototype.replaceChild = function (child, old) {
  check(K.insert(this.__id, idOf(child), idOf(old)), 'replaceChild');
  check(K.remove(this.__id, idOf(old)), 'replaceChild');
  return old;
};
Node.prototype.cloneNode = function (deep) { return wrap(K.clone(this.__id, !!deep)); };
Node.prototype.contains = function (other) {
  if (other == null) return false;
  for (var t = other; t; t = t.parentNode) if (t === this) return true;
  return false;
};
Node.prototype.isSameNode = function (other) { return this === other; };
Node.prototype.normalize = function () {};
Node.prototype.getRootNode = function () { var t = this; while (t.parentNode) t = t.parentNode; return t; };

function nodesFrom(args) {
  var out = [];
  for (var i = 0; i < args.length; i++) out.push(typeof args[i] === 'object' && args[i] !== null ? args[i] : document.createTextNode(String(args[i])));
  return out;
}
function ChildNode(proto) {
  proto.remove = function () { var p = K.parent(this.__id); if (p != null) K.remove(p, this.__id); };
  proto.before = function () { var p = this.parentNode; if (!p) return; var nodes = nodesFrom(arguments); for (var i = 0; i < nodes.length; i++) p.insertBefore(nodes[i], this); };
  proto.after = function () {
    var p = this.parentNode; if (!p) return;
    var next = this.nextSibling, nodes = nodesFrom(arguments);
    for (var i = 0; i < nodes.length; i++) p.insertBefore(nodes[i], next);
  };
  proto.replaceWith = function () { var p = this.parentNode; if (!p) return; this.before.apply(this, arguments); p.removeChild(this); };
}
function ParentNode(proto) {
  def(proto, 'children', function () { var all = K.children(this.__id), out = []; if (all) for (var i = 0; i < all.length; i++) if (K.kind(all[i]) === 1) out.push(wrap(all[i])); return list(out); });
  def(proto, 'childElementCount', function () { return this.children.length; });
  def(proto, 'firstElementChild', function () { return this.children[0] || null; });
  def(proto, 'lastElementChild', function () { var c = this.children; return c[c.length - 1] || null; });
  proto.append = function () { var nodes = nodesFrom(arguments); for (var i = 0; i < nodes.length; i++) this.appendChild(nodes[i]); };
  proto.prepend = function () { var first = this.firstChild, nodes = nodesFrom(arguments); for (var i = 0; i < nodes.length; i++) this.insertBefore(nodes[i], first); };
  proto.replaceChildren = function () { this.textContent = ''; this.append.apply(this, arguments); };
  proto.querySelector = function (selectors) {
    var found = K.query(this.__id, String(selectors), false);
    if (found == null) throw new DOMException('not a valid selector: ' + selectors, 'SyntaxError');
    return found.length ? wrap(found[0]) : null;
  };
  proto.querySelectorAll = function (selectors) {
    var found = K.query(this.__id, String(selectors), true);
    if (found == null) throw new DOMException('not a valid selector: ' + selectors, 'SyntaxError');
    return wraps(found);
  };
  proto.getElementsByTagName = function (name) { return this.querySelectorAll(name === '*' ? '*' : String(name).toLowerCase()); };
  proto.getElementsByClassName = function (names) {
    var parts = String(names).split(/\s+/).filter(function (s) { return s.length; });
    if (!parts.length) return list([]);
    return this.querySelectorAll(parts.map(function (s) { return '.' + s; }).join(''));
  };
}

function CharacterData() {}
CharacterData.prototype = Object.create(Node.prototype);
def(CharacterData.prototype, 'data', function () { return K.text(this.__id); }, function (v) { K.setText(this.__id, v == null ? '' : String(v)); });
def(CharacterData.prototype, 'length', function () { return K.text(this.__id).length; });
ChildNode(CharacterData.prototype);
function Text() { throw new TypeError('use document.createTextNode'); }
Text.prototype = Object.create(CharacterData.prototype);
Text.prototype.constructor = Text;
def(Text.prototype, 'wholeText', function () { return K.text(this.__id); });

/* ---- elements ---- */

function Element() { throw new TypeError('use document.createElement'); }
Element.prototype = Object.create(Node.prototype);
Element.prototype.constructor = Element;
ChildNode(Element.prototype);
ParentNode(Element.prototype);
def(Element.prototype, 'tagName', function () { return K.tag(this.__id).toUpperCase(); });
def(Element.prototype, 'localName', function () { return K.tag(this.__id); });
def(Element.prototype, 'namespaceURI', function () { return 'http://www.w3.org/1999/xhtml'; });
Element.prototype.getAttribute = function (name) { var v = K.attr(this.__id, String(name).toLowerCase()); return v == null ? null : v; };
Element.prototype.getAttributeNS = function (ns, name) { return this.getAttribute(String(name).replace(/^.*:/, '')); };
Element.prototype.setAttribute = function (name, value) { K.setAttr(this.__id, String(name).toLowerCase(), String(value)); };
Element.prototype.setAttributeNS = function (ns, name, value) { this.setAttribute(String(name).replace(/^.*:/, ''), value); };
Element.prototype.removeAttribute = function (name) { K.removeAttr(this.__id, String(name).toLowerCase()); };
Element.prototype.removeAttributeNS = function (ns, name) { this.removeAttribute(String(name).replace(/^.*:/, '')); };
Element.prototype.hasAttribute = function (name) { return K.attr(this.__id, String(name).toLowerCase()) != null; };
Element.prototype.hasAttributes = function () { return K.attrNames(this.__id).length > 0; };
Element.prototype.getAttributeNames = function () { return K.attrNames(this.__id).slice(); };
Element.prototype.toggleAttribute = function (name, force) {
  var has = this.hasAttribute(name);
  var want = force === undefined ? !has : !!force;
  if (want && !has) this.setAttribute(name, '');
  if (!want && has) this.removeAttribute(name);
  return want;
};
def(Element.prototype, 'attributes', function () {
  var id = this.__id;
  return list(K.attrNames(id).map(function (n) { return { name: n, localName: n, value: K.attr(id, n), specified: true }; }));
});
function reflect(proto, prop, attr) {
  def(proto, prop, function () { var v = K.attr(this.__id, attr); return v == null ? '' : v; }, function (v) { K.setAttr(this.__id, attr, String(v)); });
}
function reflectBool(proto, prop, attr) {
  def(proto, prop, function () { return K.attr(this.__id, attr) != null; }, function (v) { if (v) K.setAttr(this.__id, attr, ''); else K.removeAttr(this.__id, attr); });
}
reflect(Element.prototype, 'id', 'id');
reflect(Element.prototype, 'className', 'class');
reflect(Element.prototype, 'slot', 'slot');
def(Element.prototype, 'classList', function () { return tokens(this, 'class'); });
Element.prototype.matches = function (selectors) {
  var r = K.matches(this.__id, String(selectors));
  if (r == null) throw new DOMException('not a valid selector: ' + selectors, 'SyntaxError');
  return r;
};
Element.prototype.webkitMatchesSelector = Element.prototype.matches;
Element.prototype.closest = function (selectors) {
  for (var t = this; t && t.nodeType === 1; t = t.parentNode) if (t.matches(selectors)) return t;
  return null;
};
def(Element.prototype, 'innerHTML', function () { return K.html(this.__id, false); }, function (v) { K.setHtml(this.__id, v == null ? '' : String(v)); });
def(Element.prototype, 'outerHTML', function () { return K.html(this.__id, true); }, function (v) {
  var p = this.parentNode; if (!p) return;
  check(K.insertHtml(this.__id, 'beforebegin', v == null ? '' : String(v)), 'outerHTML');
  p.removeChild(this);
});
Element.prototype.insertAdjacentHTML = function (position, html) { check(K.insertHtml(this.__id, String(position), String(html)), 'insertAdjacentHTML'); };
Element.prototype.insertAdjacentElement = function (position, el) {
  switch (String(position).toLowerCase()) {
    case 'beforebegin': if (this.parentNode) this.parentNode.insertBefore(el, this); else return null; break;
    case 'afterbegin': this.insertBefore(el, this.firstChild); break;
    case 'beforeend': this.appendChild(el); break;
    case 'afterend': if (this.parentNode) this.parentNode.insertBefore(el, this.nextSibling); else return null; break;
    default: throw new DOMException('not a position: ' + position, 'SyntaxError');
  }
  return el;
};
Element.prototype.insertAdjacentText = function (position, text) { this.insertAdjacentElement(position, document.createTextNode(String(text))); };
def(Element.prototype, 'nextElementSibling', function () { return sibling(this, 1, true); });
def(Element.prototype, 'previousElementSibling', function () { return sibling(this, -1, true); });
function rect(el) {
  var r = K.rect(el.__id) || [0, 0, 0, 0];
  return { x: r[0], y: r[1], width: r[2], height: r[3], left: r[0], top: r[1], right: r[0] + r[2], bottom: r[1] + r[3],
    toJSON: function () { return { x: r[0], y: r[1], width: r[2], height: r[3] }; } };
}
Element.prototype.getBoundingClientRect = function () { return rect(this); };
Element.prototype.getClientRects = function () { var r = rect(this); return list(r.width || r.height ? [r] : []); };
def(Element.prototype, 'clientWidth', function () { return Math.round(rect(this).width); });
def(Element.prototype, 'clientHeight', function () { return Math.round(rect(this).height); });
def(Element.prototype, 'scrollWidth', function () { return Math.round(rect(this).width); });
def(Element.prototype, 'scrollHeight', function () { return Math.round(rect(this).height); });
def(Element.prototype, 'scrollTop', function () { return 0; }, function () {});
def(Element.prototype, 'scrollLeft', function () { return 0; }, function () {});
Element.prototype.scrollIntoView = function () {};
Element.prototype.scrollTo = function () {};
Element.prototype.animate = function () { return { finished: Promise.resolve(), cancel: function () {}, play: function () {}, pause: function () {} }; };

function tokens(el, attr) {
  function read() { return (K.attr(el.__id, attr) || '').split(/\s+/).filter(function (s) { return s.length; }); }
  function write(items) { K.setAttr(el.__id, attr, items.join(' ')); }
  function valid(t) {
    t = String(t);
    if (!t.length) throw new DOMException('an empty token', 'SyntaxError');
    if (/\s/.test(t)) throw new DOMException('a token with a space: ' + t, 'InvalidCharacterError');
    return t;
  }
  var api = {
    item: function (i) { var r = read(); return i < r.length ? r[i] : null; },
    contains: function (t) { return read().indexOf(String(t)) >= 0; },
    add: function () { var r = read(); for (var i = 0; i < arguments.length; i++) { var t = valid(arguments[i]); if (r.indexOf(t) < 0) r.push(t); } write(r); },
    remove: function () { var r = read(); for (var i = 0; i < arguments.length; i++) { var t = valid(arguments[i]); var k = r.indexOf(t); while (k >= 0) { r.splice(k, 1); k = r.indexOf(t); } } write(r); },
    toggle: function (t, force) {
      t = valid(t);
      var has = read().indexOf(t) >= 0;
      var want = force === undefined ? !has : !!force;
      if (want && !has) api.add(t);
      if (!want && has) api.remove(t);
      return want;
    },
    replace: function (a, b) { var r = read(), k = r.indexOf(valid(a)); if (k < 0) return false; r[k] = valid(b); write(r); return true; },
    forEach: function (fn, self) { read().forEach(fn, self); },
    toString: function () { return K.attr(el.__id, attr) || ''; },
    supports: function () { return true; }
  };
  def(api, 'length', function () { return read().length; });
  def(api, 'value', function () { return K.attr(el.__id, attr) || ''; }, function (v) { K.setAttr(el.__id, attr, String(v)); });
  return api;
}

function parseStyle(text) {
  var out = [];
  var parts = [], depth = 0, quote = '', start = 0;
  for (var i = 0; i < text.length; i++) {
    var c = text.charAt(i);
    if (quote) { if (c === quote) quote = ''; }
    else if (c === '"' || c === "'") quote = c;
    else if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (c === ';' && depth === 0) { parts.push(text.substring(start, i)); start = i + 1; }
  }
  parts.push(text.substring(start));
  for (var j = 0; j < parts.length; j++) {
    var colon = parts[j].indexOf(':');
    if (colon < 0) continue;
    var name = parts[j].substring(0, colon).trim().toLowerCase();
    var value = parts[j].substring(colon + 1).trim();
    var important = /!\s*important$/i.test(value);
    if (important) value = value.replace(/!\s*important$/i, '').trim();
    if (name.length) out.push({ name: name, value: value, important: important });
  }
  return out;
}
function styleOf(el) {
  if (el.__style) return el.__style;
  function read() { return parseStyle(K.attr(el.__id, 'style') || ''); }
  function write(decls) {
    var text = decls.map(function (d) { return d.name + ': ' + d.value + (d.important ? ' !important' : ''); }).join('; ');
    if (text.length) K.setAttr(el.__id, 'style', text + ';'); else K.removeAttr(el.__id, 'style');
  }
  var decl = {
    getPropertyValue: function (name) {
      var d = read(); name = String(name).toLowerCase();
      for (var i = d.length - 1; i >= 0; i--) if (d[i].name === name) return cssValue(d[i].value);
      return '';
    },
    getPropertyPriority: function (name) {
      var d = read(); name = String(name).toLowerCase();
      for (var i = d.length - 1; i >= 0; i--) if (d[i].name === name) return d[i].important ? 'important' : '';
      return '';
    },
    setProperty: function (name, value, priority) {
      name = String(name).toLowerCase();
      if (value == null || String(value) === '') { decl.removeProperty(name); return; }
      var d = read().filter(function (x) { return x.name !== name; });
      d.push({ name: name, value: String(value), important: String(priority || '').toLowerCase() === 'important' });
      write(d);
    },
    removeProperty: function (name) {
      name = String(name).toLowerCase();
      var old = decl.getPropertyValue(name);
      write(read().filter(function (x) { return x.name !== name; }));
      return old;
    },
    item: function (i) { var d = read(); return i < d.length ? d[i].name : ''; }
  };
  def(decl, 'cssText', function () { return K.attr(el.__id, 'style') || ''; }, function (v) { if (v == null || v === '') K.removeAttr(el.__id, 'style'); else K.setAttr(el.__id, 'style', String(v)); });
  def(decl, 'length', function () { return read().length; });
  // A key that names no CSS property is a plain property of the object, as in a browser.
  var own = {};
  var style = new Proxy(decl, {
    get: function (target, key) {
      if (typeof key !== 'string' || key in target) return target[key];
      if (/^\d+$/.test(key)) return target.item(Number(key));
      var name = cssProperty(key);
      return name ? target.getPropertyValue(name) : own[key];
    },
    set: function (target, key, value) {
      if (typeof key !== 'string') return false;
      if (key === 'cssText') { target.cssText = value; return true; }
      if (key in target) return false;
      var name = cssProperty(key);
      if (name) target.setProperty(name, value); else own[key] = value;
      return true;
    },
    has: function (target, key) {
      return key in target || (typeof key === 'string' && (cssProperty(key) !== null || key in own));
    }
  });
  hidden(el, '__style', style);
  return style;
}
"""

/** The rest of [DOM_PRELUDE]: elements, events, the document, the window and what the host calls. */
private const val DOM_PRELUDE_TAIL: String = """function datasetOf(el) {
  if (el.__dataset) return el.__dataset;
  function attr(key) { return 'data-' + kebab(key); }
  var ds = new Proxy({}, {
    get: function (t, key) { if (typeof key !== 'string') return undefined; var v = K.attr(el.__id, attr(key)); return v == null ? undefined : v; },
    set: function (t, key, value) { if (typeof key !== 'string') return false; K.setAttr(el.__id, attr(key), String(value)); return true; },
    has: function (t, key) { return typeof key === 'string' && K.attr(el.__id, attr(key)) != null; },
    deleteProperty: function (t, key) { if (typeof key === 'string') K.removeAttr(el.__id, attr(key)); return true; },
    ownKeys: function () { return K.attrNames(el.__id).filter(function (n) { return n.indexOf('data-') === 0; }).map(function (n) { return camel(n.substring(5)); }); },
    getOwnPropertyDescriptor: function (t, key) {
      if (typeof key !== 'string') return undefined;
      var v = K.attr(el.__id, attr(key));
      return v == null ? undefined : { value: v, writable: true, enumerable: true, configurable: true };
    }
  });
  hidden(el, '__dataset', ds);
  return ds;
}

function HTMLElement() { throw new TypeError('use document.createElement'); }
HTMLElement.prototype = Object.create(Element.prototype);
HTMLElement.prototype.constructor = HTMLElement;
defineHandlers(HTMLElement.prototype);
def(HTMLElement.prototype, 'style', function () { return styleOf(this); }, function (v) { styleOf(this).cssText = v; });
def(HTMLElement.prototype, 'dataset', function () { return datasetOf(this); });
reflectBool(HTMLElement.prototype, 'hidden', 'hidden');
reflect(HTMLElement.prototype, 'title', 'title');
reflect(HTMLElement.prototype, 'lang', 'lang');
reflect(HTMLElement.prototype, 'dir', 'dir');
reflect(HTMLElement.prototype, 'accessKey', 'accesskey');
def(HTMLElement.prototype, 'tabIndex', function () { var v = parseInt(K.attr(this.__id, 'tabindex'), 10); return isNaN(v) ? -1 : v; }, function (v) { K.setAttr(this.__id, 'tabindex', String(v)); });
def(HTMLElement.prototype, 'innerText', function () { return K.text(this.__id); }, function (v) { K.setText(this.__id, v == null ? '' : String(v)); });
def(HTMLElement.prototype, 'outerText', function () { return K.text(this.__id); });
def(HTMLElement.prototype, 'offsetWidth', function () { return Math.round(rect(this).width); });
def(HTMLElement.prototype, 'offsetHeight', function () { return Math.round(rect(this).height); });
def(HTMLElement.prototype, 'offsetLeft', function () { return Math.round(rect(this).left); });
def(HTMLElement.prototype, 'offsetTop', function () { return Math.round(rect(this).top); });
def(HTMLElement.prototype, 'offsetParent', function () { return document.body; });
HTMLElement.prototype.focus = function () {
  if (document.__active === this) return;
  var before = document.__active;
  document.__active = this;
  if (before && before !== this) { fireEvent(before, new FocusEvent('blur', { relatedTarget: this })); fireEvent(before, new FocusEvent('focusout', { bubbles: true, relatedTarget: this })); }
  fireEvent(this, new FocusEvent('focus', { relatedTarget: before || null }));
  fireEvent(this, new FocusEvent('focusin', { bubbles: true, relatedTarget: before || null }));
};
HTMLElement.prototype.blur = function () {
  if (document.__active !== this) return;
  document.__active = null;
  fireEvent(this, new FocusEvent('blur', {}));
  fireEvent(this, new FocusEvent('focusout', { bubbles: true }));
};
HTMLElement.prototype.click = function () {
  if (this.__clicking) return;
  hidden(this, '__clicking', true);
  try { activate(this, new MouseEvent('click', { bubbles: true, cancelable: true, composed: true, view: global, detail: 1 }), false); }
  finally { this.__clicking = false; }
};

function elementType(parent, setup) {
  var T = function () { throw new TypeError('use document.createElement'); };
  T.prototype = Object.create(parent.prototype);
  T.prototype.constructor = T;
  if (setup) setup(T.prototype);
  return T;
}
function liveValue(proto, attr) {
  def(proto, 'value', function () { return this.__value !== undefined ? this.__value : (K.attr(this.__id, attr) || ''); },
    function (v) { hidden(this, '__value', v == null ? '' : String(v)); });
  def(proto, 'defaultValue', function () { return K.attr(this.__id, attr) || ''; }, function (v) { K.setAttr(this.__id, attr, String(v)); });
}
var HTMLAnchorElement = elementType(HTMLElement, function (p) {
  reflect(p, 'href', 'href'); reflect(p, 'target', 'target'); reflect(p, 'rel', 'rel'); reflect(p, 'download', 'download');
  def(p, 'text', function () { return K.text(this.__id); }, function (v) { K.setText(this.__id, String(v)); });
  p.toString = function () { return this.href; };
});
var HTMLAreaElement = elementType(HTMLElement, function (p) { reflect(p, 'href', 'href'); reflect(p, 'alt', 'alt'); });
var HTMLImageElement = elementType(HTMLElement, function (p) {
  reflect(p, 'src', 'src'); reflect(p, 'alt', 'alt'); reflect(p, 'srcset', 'srcset');
  def(p, 'width', function () { return parseInt(K.attr(this.__id, 'width'), 10) || Math.round(rect(this).width); }, function (v) { K.setAttr(this.__id, 'width', String(v)); });
  def(p, 'height', function () { return parseInt(K.attr(this.__id, 'height'), 10) || Math.round(rect(this).height); }, function (v) { K.setAttr(this.__id, 'height', String(v)); });
  def(p, 'complete', function () { return true; });
  def(p, 'naturalWidth', function () { return parseInt(K.attr(this.__id, 'width'), 10) || 0; });
  def(p, 'naturalHeight', function () { return parseInt(K.attr(this.__id, 'height'), 10) || 0; });
});
var HTMLInputElement = elementType(HTMLElement, function (p) {
  liveValue(p, 'value');
  def(p, 'type', function () { return (K.attr(this.__id, 'type') || 'text').toLowerCase(); }, function (v) { K.setAttr(this.__id, 'type', String(v)); });
  def(p, 'checked', function () { return this.__checked !== undefined ? this.__checked : K.attr(this.__id, 'checked') != null; },
    function (v) {
      hidden(this, '__checked', !!v);
      // The rendering follows the checkedness, so a selector and the page see it.
      if (v) K.setAttr(this.__id, 'checked', ''); else K.removeAttr(this.__id, 'checked');
      if (v && this.type === 'radio') uncheckGroup(this);
    });
  reflectBool(p, 'defaultChecked', 'checked');
  reflectBool(p, 'disabled', 'disabled'); reflectBool(p, 'readOnly', 'readonly'); reflectBool(p, 'required', 'required');
  reflect(p, 'name', 'name'); reflect(p, 'placeholder', 'placeholder');
  def(p, 'form', function () { return this.closest('form'); });
  p.select = function () {};
  p.setSelectionRange = function () {};
  p.checkValidity = function () { return true; };
  p.reportValidity = function () { return true; };
});
function uncheckGroup(input) {
  var name = input.getAttribute('name');
  if (!name) return;
  var scope = input.closest('form') || document;
  var all = scope.querySelectorAll('input');
  for (var i = 0; i < all.length; i++) {
    var other = all[i];
    if (other !== input && other.type === 'radio' && other.getAttribute('name') === name && other.checked) {
      hidden(other, '__checked', false);
      K.removeAttr(other.__id, 'checked');
    }
  }
}
var HTMLTextAreaElement = elementType(HTMLElement, function (p) {
  def(p, 'value', function () { return this.__value !== undefined ? this.__value : K.text(this.__id); }, function (v) { hidden(this, '__value', v == null ? '' : String(v)); });
  def(p, 'defaultValue', function () { return K.text(this.__id); }, function (v) { K.setText(this.__id, String(v)); });
  reflectBool(p, 'disabled', 'disabled'); reflect(p, 'name', 'name');
  def(p, 'form', function () { return this.closest('form'); });
});
var HTMLButtonElement = elementType(HTMLElement, function (p) {
  liveValue(p, 'value');
  def(p, 'type', function () { return (K.attr(this.__id, 'type') || 'submit').toLowerCase(); }, function (v) { K.setAttr(this.__id, 'type', String(v)); });
  reflectBool(p, 'disabled', 'disabled'); reflect(p, 'name', 'name');
  def(p, 'form', function () { return this.closest('form'); });
});
var HTMLOptionElement = elementType(HTMLElement, function (p) {
  def(p, 'value', function () { var v = K.attr(this.__id, 'value'); return v == null ? K.text(this.__id).trim() : v; }, function (v) { K.setAttr(this.__id, 'value', String(v)); });
  def(p, 'text', function () { return K.text(this.__id); }, function (v) { K.setText(this.__id, String(v)); });
  def(p, 'selected', function () { return this.__selected !== undefined ? this.__selected : K.attr(this.__id, 'selected') != null; },
    function (v) { hidden(this, '__selected', !!v); });
  reflectBool(p, 'disabled', 'disabled');
});
var HTMLSelectElement = elementType(HTMLElement, function (p) {
  def(p, 'options', function () { return this.querySelectorAll('option'); });
  def(p, 'selectedIndex', function () {
    var o = this.options;
    for (var i = 0; i < o.length; i++) if (o[i].selected) return i;
    return o.length && !this.multiple ? 0 : -1;
  }, function (v) { var o = this.options; for (var i = 0; i < o.length; i++) o[i].selected = i === v; });
  def(p, 'value', function () { var i = this.selectedIndex; return i < 0 ? '' : this.options[i].value; },
    function (v) { var o = this.options; for (var i = 0; i < o.length; i++) o[i].selected = o[i].value === String(v); });
  reflectBool(p, 'multiple', 'multiple'); reflectBool(p, 'disabled', 'disabled'); reflect(p, 'name', 'name');
  def(p, 'form', function () { return this.closest('form'); });
});
var HTMLFormElement = elementType(HTMLElement, function (p) {
  def(p, 'elements', function () { return this.querySelectorAll('input, select, textarea, button'); });
  reflect(p, 'action', 'action'); reflect(p, 'method', 'method'); reflect(p, 'name', 'name');
  p.submit = function () {};
  p.requestSubmit = function () { if (fireEvent(this, new Event('submit', { bubbles: true, cancelable: true }))) {} };
  p.reset = function () {
    if (!fireEvent(this, new Event('reset', { bubbles: true, cancelable: true }))) return;
    var all = this.elements;
    for (var i = 0; i < all.length; i++) { delete all[i].__value; delete all[i].__checked; }
  };
  p.checkValidity = function () { return true; };
});
var HTMLLabelElement = elementType(HTMLElement, function (p) {
  reflect(p, 'htmlFor', 'for');
  def(p, 'control', function () { var f = this.getAttribute('for'); return f ? document.getElementById(f) : this.querySelector('input, select, textarea, button'); });
});
var HTMLDetailsElement = elementType(HTMLElement, function (p) { reflectBool(p, 'open', 'open'); });
var HTMLDialogElement = elementType(HTMLElement, function (p) {
  reflectBool(p, 'open', 'open');
  p.show = function () { this.open = true; };
  p.showModal = function () { this.open = true; };
  p.close = function (value) { this.open = false; this.returnValue = value === undefined ? '' : String(value); fireEvent(this, new Event('close', {})); };
});
var HTMLMediaElement = elementType(HTMLElement, function (p) {
  reflect(p, 'src', 'src');
  p.play = function () { return Promise.resolve(); };
  p.pause = function () {};
  p.load = function () {};
  def(p, 'paused', function () { return true; });
  def(p, 'currentTime', function () { return 0; }, function () {});
});
var HTMLCanvasElement = elementType(HTMLElement, function (p) {
  p.getContext = function () { return null; };
  def(p, 'width', function () { return parseInt(K.attr(this.__id, 'width'), 10) || 300; }, function (v) { K.setAttr(this.__id, 'width', String(v)); });
  def(p, 'height', function () { return parseInt(K.attr(this.__id, 'height'), 10) || 150; }, function (v) { K.setAttr(this.__id, 'height', String(v)); });
});
var HTMLScriptElement = elementType(HTMLElement, function (p) {
  reflect(p, 'src', 'src'); reflect(p, 'type', 'type');
  def(p, 'text', function () { return K.text(this.__id); }, function (v) { K.setText(this.__id, String(v)); });
});
var HTMLIFrameElement = elementType(HTMLElement, function (p) {
  reflect(p, 'src', 'src');
  def(p, 'contentWindow', function () { return null; });
  def(p, 'contentDocument', function () { return null; });
});
var TYPES = {
  a: HTMLAnchorElement, area: HTMLAreaElement, img: HTMLImageElement, input: HTMLInputElement, textarea: HTMLTextAreaElement,
  button: HTMLButtonElement, option: HTMLOptionElement, select: HTMLSelectElement, form: HTMLFormElement, label: HTMLLabelElement,
  details: HTMLDetailsElement, dialog: HTMLDialogElement, audio: HTMLMediaElement, video: HTMLMediaElement,
  canvas: HTMLCanvasElement, script: HTMLScriptElement, iframe: HTMLIFrameElement
};
function protoFor(tag) { var T = TYPES[tag]; return T ? T.prototype : HTMLElement.prototype; }

/* ---- activation: what a click does once its listeners ran ---- */

function fireEvent(target, event) { return dispatch(target, event); }
function control(el) {
  for (var t = el; t && t.nodeType === 1; t = t.parentNode) {
    var tag = t.localName;
    if (tag === 'input' || tag === 'button' || tag === 'select' || tag === 'textarea' || tag === 'summary') return t;
    if (tag === 'label') return t;
    if (tag === 'a' && t.hasAttribute('href')) return t;
  }
  return null;
}
/**
 * A click on [target], with the activation of the control it lands in: a check box or a radio
 * button changes before the listeners run and changes back when one cancels the click, then
 * reports `input` and `change`; a label clicks its control; a summary opens its details; a
 * submit button submits its form. A link opens only for a [synthetic] click, since the viewer
 * follows the links of a reader's tap itself.
 */
function activate(target, click, trusted) {
  var c = control(target);
  if (c && (c.localName === 'input' || c.localName === 'button' || c.localName === 'select' || c.localName === 'textarea') && c.disabled) return false;
  var undo = null;
  if (c && c.localName === 'input' && (c.type === 'checkbox' || c.type === 'radio')) {
    var was = c.checked;
    var group = [];
    if (c.type === 'radio') {
      var name = c.getAttribute('name');
      var all = name ? (c.closest('form') || document).querySelectorAll('input') : [];
      for (var i = 0; i < all.length; i++) if (all[i] !== c && all[i].type === 'radio' && all[i].getAttribute('name') === name && all[i].checked) group.push(all[i]);
      c.checked = true;
    } else {
      c.checked = !was;
    }
    undo = function () { c.checked = was; for (var j = 0; j < group.length; j++) group[j].checked = true; };
  }
  click.isTrusted = trusted;
  var ok = dispatch(target, click);
  if (!ok) { if (undo) undo(); return true; }
  if (undo && c.checked !== undefined) {
    fireEvent(c, new InputEvent('input', { bubbles: true }));
    fireEvent(c, new Event('change', { bubbles: true }));
  } else if (c && c.localName === 'label') {
    var labelled = c.control;
    if (labelled && labelled !== target && !labelled.contains(target)) labelled.click();
  } else if (c && c.localName === 'summary') {
    var details = c.parentNode;
    if (details && details.localName === 'details' && details.querySelector('summary') === c) {
      details.open = !details.open;
      fireEvent(details, new Event('toggle', {}));
    }
  } else if (c && c.localName === 'button' && c.type === 'submit' && c.form) {
    fireEvent(c.form, new Event('submit', { bubbles: true, cancelable: true }));
  } else if (c && c.localName === 'button' && c.type === 'reset' && c.form) {
    c.form.reset();
  } else if (c && c.localName === 'a' && !trusted) {
    K.navigate(c.getAttribute('href'));
  }
  return false;
}

/* ---- the document ---- */

function Document() {}
Document.prototype = Object.create(Node.prototype);
Document.prototype.constructor = Document;
ParentNode(Document.prototype);
defineHandlers(Document.prototype);
function childByTag(parent, tag) {
  if (!parent) return null;
  var c = parent.children;
  for (var i = 0; i < c.length; i++) if (c[i].localName === tag) return c[i];
  return null;
}
def(Document.prototype, 'documentElement', function () { return this.firstElementChild; });
def(Document.prototype, 'head', function () { return childByTag(this.documentElement, 'head'); });
def(Document.prototype, 'body', function () { return childByTag(this.documentElement, 'body'); });
def(Document.prototype, 'title', function () { var t = this.querySelector('title'); return t ? t.textContent.replace(/\s+/g, ' ').trim() : ''; },
  function (v) {
    var t = this.querySelector('title');
    if (!t) { t = this.createElement('title'); var h = this.head; if (h) h.appendChild(t); else return; }
    t.textContent = String(v);
  });
def(Document.prototype, 'readyState', function () { return this.__ready || 'loading'; });
def(Document.prototype, 'defaultView', function () { return global; });
def(Document.prototype, 'location', function () { return location; }, function (v) { location.href = v; });
def(Document.prototype, 'URL', function () { return location.href; });
def(Document.prototype, 'documentURI', function () { return location.href; });
def(Document.prototype, 'baseURI', function () { return location.href; });
def(Document.prototype, 'characterSet', function () { return 'UTF-8'; });
def(Document.prototype, 'contentType', function () { return 'application/xhtml+xml'; });
def(Document.prototype, 'compatMode', function () { return 'CSS1Compat'; });
def(Document.prototype, 'visibilityState', function () { return 'visible'; });
def(Document.prototype, 'hidden', function () { return false; });
def(Document.prototype, 'activeElement', function () { return this.__active || this.body; });
def(Document.prototype, 'currentScript', function () { return this.__script || null; });
def(Document.prototype, 'cookie', function () {
  var c = this.__cookies || {};
  return Object.keys(c).map(function (k) { return k + '=' + c[k]; }).join('; ');
}, function (v) {
  if (!this.__cookies) hidden(this, '__cookies', {});
  var pair = String(v).split(';')[0];
  var eq = pair.indexOf('=');
  if (eq > 0) this.__cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim();
});
Document.prototype.getElementById = function (id) { return wrap(K.byId(String(id))); };
Document.prototype.getElementsByName = function (name) { return this.querySelectorAll('[name="' + String(name).replace(/"/g, '\\"') + '"]'); };
Document.prototype.createElement = function (tag) { return wrap(K.create(String(tag).toLowerCase())); };
Document.prototype.createElementNS = function (ns, tag) { return wrap(K.create(String(tag).replace(/^.*:/, '').toLowerCase())); };
Document.prototype.createTextNode = function (data) { return wrap(K.createText(String(data))); };
Document.prototype.createComment = function () { return wrap(K.createText('')); };
Document.prototype.createDocumentFragment = function () { return wrap(K.createFragment()); };
Document.prototype.createEvent = function (kind) {
  var k = String(kind).toLowerCase();
  var e = k.indexOf('mouse') === 0 ? new MouseEvent('', {}) : k.indexOf('custom') === 0 ? new CustomEvent('', {}) : k.indexOf('keyboard') === 0 ? new KeyboardEvent('', {}) : new Event('', {});
  return e;
};
Document.prototype.hasFocus = function () { return true; };
Document.prototype.write = function () {
  var html = Array.prototype.join.call(arguments, '');
  var at = this.__script;
  if (at) K.write(at.__id, html); else if (this.body) this.body.insertAdjacentHTML('beforeend', html);
};
Document.prototype.writeln = function () { this.write(Array.prototype.join.call(arguments, '') + '\n'); };
Document.prototype.open = function () { return this; };
Document.prototype.close = function () {};
Document.prototype.elementFromPoint = function () { return null; };
Document.prototype.execCommand = function () { return false; };
Document.prototype.getSelection = function () { return global.getSelection(); };
Document.prototype.importNode = function (node, deep) { return node.cloneNode(deep); };
Document.prototype.adoptNode = function (node) { return node; };

function DocumentFragment() { return document.createDocumentFragment(); }
DocumentFragment.prototype = Object.create(Node.prototype);
DocumentFragment.prototype.constructor = DocumentFragment;
ParentNode(DocumentFragment.prototype);
DocumentFragment.prototype.getElementById = function (id) { return this.querySelector('[id="' + String(id).replace(/"/g, '\\"') + '"]'); };

var document = wrap(rootId);

/* ---- the window ---- */

var location = {};
function navigate(v) { K.navigate(String(v)); }
def(location, 'href', function () { return K.location(); }, navigate);
def(location, 'protocol', function () { return 'epub:'; });
/* The book's origin, the same in each of its chapters (#500). */
var origin = K.origin();
def(location, 'host', function () { return origin.replace(/^epub:\/\//, ''); });
def(location, 'hostname', function () { return origin.replace(/^epub:\/\//, ''); });
def(location, 'port', function () { return ''; });
def(location, 'origin', function () { return origin; });
def(location, 'pathname', function () { return K.location().substring(origin.length).replace(/#.*/, ''); });
def(location, 'search', function () { return ''; });
def(location, 'hash', function () { return location.__hash || ''; }, function (v) { v = String(v); location.__hash = v && v.charAt(0) !== '#' ? '#' + v : v; navigate(location.__hash); });
location.assign = navigate;
location.replace = navigate;
location.reload = function () {};
location.toString = function () { return location.href; };

function Storage(kind) { hidden(this, '__kind', kind); }
Storage.prototype.getItem = function (key) { var v = K.storage(this.__kind, 'get', String(key), null); return v == null ? null : v; };
Storage.prototype.setItem = function (key, value) { K.storage(this.__kind, 'set', String(key), String(value)); };
Storage.prototype.removeItem = function (key) { K.storage(this.__kind, 'remove', String(key), null); };
Storage.prototype.clear = function () { K.storage(this.__kind, 'clear', null, null); };
Storage.prototype.key = function (i) { var v = K.storage(this.__kind, 'key', String(i), null); return v == null ? null : v; };
def(Storage.prototype, 'length', function () { return Number(K.storage(this.__kind, 'length', null, null)); });
function storage(kind) {
  return new Proxy(new Storage(kind), {
    get: function (t, key) { if (typeof key !== 'string' || key in t) return t[key]; return t.getItem(key); },
    set: function (t, key, value) { if (typeof key !== 'string') return false; t.setItem(key, value); return true; },
    deleteProperty: function (t, key) { if (typeof key === 'string') t.removeItem(key); return true; }
  });
}

var timers = [];
var frames = [];
var nextTimer = 1;
function timersChanged() { K.timers(timers.length + frames.length); }
function schedule(fn, ms, args, repeat) {
  if (typeof fn !== 'function') { var code = String(fn); fn = new Function(code); }
  ms = Number(ms) || 0;
  if (ms < 0) ms = 0;
  var t = { id: nextTimer++, due: K.now() + ms, fn: fn, args: args, every: repeat ? Math.max(ms, 1) : 0 };
  timers.push(t);
  timersChanged();
  return t.id;
}
function clearTimer(id) {
  for (var i = 0; i < timers.length; i++) if (timers[i].id === id) { timers.splice(i, 1); timersChanged(); return; }
}

var console = {};
['log', 'info', 'warn', 'error', 'debug', 'trace'].forEach(function (level) {
  console[level] = function () {
    var parts = [];
    for (var i = 0; i < arguments.length; i++) {
      var a = arguments[i];
      try { parts.push(typeof a === 'string' ? a : a instanceof Error ? String(a) : JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
    }
    K.console(level, parts.join(' '));
  };
});
console.dir = console.log;
console.table = console.log;
console.assert = function (ok) { if (!ok) console.error.apply(console, ['Assertion failed:'].concat(Array.prototype.slice.call(arguments, 1))); };
console.group = console.groupCollapsed = console.groupEnd = console.time = console.timeEnd = console.count = function () {};

/*
 * What a book's scripts ask of the reading system (EPUB Reading Systems 3.3, appendix B). A
 * chapter's scripts change its tree and its styles, which is laid out again, and a tap reaches
 * them as mouse events; no touch or key event reaches them. A feature this does not know
 * answers undefined. `name` and `version` are deprecated, and there is no version to give.
 */
var features = { 'dom-manipulation': true, 'layout-changes': true, 'spine-scripting': true,
  'mouse-events': true, 'touch-events': false, 'keyboard-events': false };
var readingSystem = {
  name: 'KitePDF', version: '',
  hasFeature: function (feature) { var f = String(feature); return features.hasOwnProperty(f) ? features[f] : undefined; }
};
Object.freeze(readingSystem);
var navigator = { userAgent: 'KitePDF', appName: 'KitePDF', language: 'en', languages: ['en'], platform: '', onLine: false, cookieEnabled: true, maxTouchPoints: 1 };
Object.defineProperty(navigator, 'epubReadingSystem', { value: readingSystem, enumerable: true });

var viewport = K.viewport();
var api = {
  window: global, self: global, top: global, parent: global, frames: global, opener: null, frameElement: null, origin: origin,
  document: document, location: location, console: console,
  navigator: navigator,
  screen: { width: viewport[0], height: viewport[1], availWidth: viewport[0], availHeight: viewport[1], colorDepth: 24 },
  history: { length: 1, state: null, back: function () {}, forward: function () {}, go: function () {}, pushState: function () {}, replaceState: function () {} },
  innerWidth: viewport[0], innerHeight: viewport[1], outerWidth: viewport[0], outerHeight: viewport[1],
  devicePixelRatio: 1, scrollX: 0, scrollY: 0, pageXOffset: 0, pageYOffset: 0,
  performance: { now: function () { return K.now(); }, timeOrigin: 0, mark: function () {}, measure: function () {} },
  localStorage: storage('local'), sessionStorage: storage('session'),
  EventTarget: EventTarget, Event: Event, UIEvent: UIEvent, MouseEvent: MouseEvent, PointerEvent: PointerEvent,
  KeyboardEvent: KeyboardEvent, FocusEvent: FocusEvent, InputEvent: InputEvent, CustomEvent: CustomEvent, TouchEvent: UIEvent,
  Node: Node, CharacterData: CharacterData, Text: Text, Element: Element, HTMLElement: HTMLElement, Document: Document,
  HTMLDocument: Document, DocumentFragment: DocumentFragment, DOMException: DOMException,
  HTMLAnchorElement: HTMLAnchorElement, HTMLImageElement: HTMLImageElement, HTMLInputElement: HTMLInputElement,
  HTMLTextAreaElement: HTMLTextAreaElement, HTMLButtonElement: HTMLButtonElement, HTMLSelectElement: HTMLSelectElement,
  HTMLOptionElement: HTMLOptionElement, HTMLFormElement: HTMLFormElement, HTMLLabelElement: HTMLLabelElement,
  HTMLDetailsElement: HTMLDetailsElement, HTMLDialogElement: HTMLDialogElement, HTMLMediaElement: HTMLMediaElement,
  HTMLAudioElement: HTMLMediaElement, HTMLVideoElement: HTMLMediaElement, HTMLCanvasElement: HTMLCanvasElement,
  HTMLScriptElement: HTMLScriptElement, HTMLIFrameElement: HTMLIFrameElement,
  Image: function (w, h) { var img = document.createElement('img'); if (w !== undefined) img.width = w; if (h !== undefined) img.height = h; return img; },
  setTimeout: function (fn, ms) { return schedule(fn, ms, Array.prototype.slice.call(arguments, 2), false); },
  setInterval: function (fn, ms) { return schedule(fn, ms, Array.prototype.slice.call(arguments, 2), true); },
  clearTimeout: clearTimer, clearInterval: clearTimer,
  requestAnimationFrame: function (fn) { var id = nextTimer++; frames.push({ id: id, fn: fn }); timersChanged(); return id; },
  cancelAnimationFrame: function (id) { for (var i = 0; i < frames.length; i++) if (frames[i].id === id) { frames.splice(i, 1); timersChanged(); return; } },
  queueMicrotask: function (fn) { Promise.resolve().then(function () { try { fn(); } catch (e) { report(e); } }); },
  alert: function (message) { K.console('alert', message === undefined ? '' : String(message)); },
  confirm: function (message) { K.console('confirm', message === undefined ? '' : String(message)); return false; },
  prompt: function (message) { K.console('prompt', message === undefined ? '' : String(message)); return null; },
  getComputedStyle: function (el) {
    var id = idOf(el);
    var decl = { getPropertyValue: function (name) { return K.computed(id, String(name).toLowerCase()) || ''; } };
    return new Proxy(decl, {
      get: function (t, key) { if (typeof key !== 'string' || key in t) return t[key]; var name = cssProperty(key); return name ? t.getPropertyValue(name) : undefined; },
      has: function (t, key) { return key in t || (typeof key === 'string' && cssProperty(key) !== null); }
    });
  },
  matchMedia: function (query) {
    var noop = function () {};
    return { matches: false, media: String(query), onchange: null, addListener: noop, removeListener: noop, addEventListener: noop, removeEventListener: noop };
  },
  getSelection: function () { return { rangeCount: 0, toString: function () { return ''; }, removeAllRanges: function () {}, addRange: function () {} }; },
  scrollTo: function () {}, scrollBy: function () {}, scroll: function () {}, print: function () {}, focus: function () {}, blur: function () {},
  open: function (url) { if (url) navigate(url); return null; },
  close: function () {},
  postMessage: function () {},
  addEventListener: EventTarget.prototype.addEventListener,
  removeEventListener: EventTarget.prototype.removeEventListener,
  dispatchEvent: EventTarget.prototype.dispatchEvent
};
for (var name in api) global[name] = api[name];
hidden(global, '__listeners', {});
defineHandlers(global);

/* ---- what the host calls ---- */

global.__kite_current = function (id) { hidden(document, '__script', id == null ? null : wrap(id)); };
global.__kite_loaded = function () {
  hidden(document, '__ready', 'interactive');
  fireEvent(document, new Event('readystatechange', {}));
  fireEvent(document, new Event('DOMContentLoaded', { bubbles: true }));
  document.__ready = 'complete';
  fireEvent(document, new Event('readystatechange', {}));
  var load = new Event('load', {});
  load.target = document;
  fireEvent(global, load);
  fireEvent(global, new Event('pageshow', {}));
};
global.__kite_tap = function (id, x, y) {
  var target = wrap(id);
  var init = { bubbles: true, cancelable: true, composed: true, view: global, detail: 1, clientX: x, clientY: y, screenX: x, screenY: y,
    button: 0, buttons: 1, pointerId: 1, pointerType: 'touch', isPrimary: true };
  function trusted(e) { e.isTrusted = true; return dispatch(target, e); }
  trusted(new PointerEvent('pointerdown', init));
  trusted(new MouseEvent('mousedown', init));
  if (target.focus && target !== document) {
    var c = control(target);
    if (c && c.focus) c.focus();
  }
  init.buttons = 0;
  trusted(new PointerEvent('pointerup', init));
  trusted(new MouseEvent('mouseup', init));
  return activate(target, new MouseEvent('click', init), true);
};
global.__kite_pump = function (now) {
  var due = timers.filter(function (t) { return t.due <= now; }).sort(function (a, b) { return a.due - b.due || a.id - b.id; });
  for (var i = 0; i < due.length; i++) {
    var t = due[i];
    var at = timers.indexOf(t);
    if (at < 0) continue;
    if (t.every) t.due = now + t.every; else timers.splice(at, 1);
    try { t.fn.apply(global, t.args); } catch (e) { report(e); }
  }
  var ready = frames;
  frames = [];
  for (var j = 0; j < ready.length; j++) { try { ready[j].fn.call(global, now); } catch (e) { report(e); } }
  timersChanged();
  if (frames.length) return 0;
  if (!timers.length) return -1;
  var next = Infinity;
  for (var k = 0; k < timers.length; k++) if (timers[k].due < next) next = timers[k].due;
  return Math.max(0, next - K.now());
};
})(this);
"""
