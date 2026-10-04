package io.github.yuroyami.kitepdf.epub.script

/**
 * The nodes of [DOM_PRELUDE]: the names of elements, `Node`, `CharacterData`, `Text` and `Element`, attributes and style declarations.
 */
internal const val DOM_PRELUDE_NODES: String = """/* ---- names, of the DOM Standard, 4.9 (#541) ----
   An element has a namespace, a prefix and a local name, which the host keeps, since the parser's
   tag is lowercased and has no prefix. Its qualified name is the prefix and the local name, and
   its tagName that name, uppercased for an HTML element of an HTML document. A chapter served as
   text/html is an HTML document, and one served as XHTML an XML document, as in a browser. */
var XHTML_NS = 'http://www.w3.org/1999/xhtml', SVG_NS = 'http://www.w3.org/2000/svg', MATHML_NS = 'http://www.w3.org/1998/Math/MathML',
  XML_NS = 'http://www.w3.org/XML/1998/namespace', XMLNS_NS = 'http://www.w3.org/2000/xmlns/';
var CONTENT_TYPE = K.contentType(), HTML_DOCUMENT = CONTENT_TYPE === 'text/html';
var elementNames = new Map();
function nameOf(id) {
  var n = MapGet(elementNames, id);
  if (n === undefined) {
    var parts = K.name(id), qualified = parts[1] == null ? parts[2] : parts[1] + ':' + parts[2];
    n = { __proto__: null, ns: parts[0], prefix: parts[1], local: parts[2], qualified: qualified, upper: null };
    MapSet(elementNames, id, n);
  }
  return n;
}
/* The documents a script made, by id, each with whether it is an HTML document and its content
   type; the node document of a node one of them made while no tree holds the node; and how many
   documents there are besides the chapter's. */
var madeDocuments = new Map(), nodeDocuments = new Map(), madeDocumentCount = 0;
/* The node document of [id], as an id: the document its tree is in, or else the one that made it. */
function ownerDocId(id) {
  if (!madeDocumentCount) return rootId;
  var root = rootOf(id);
  if (K.kind(root) === 9) return root;
  var d = MapGet(nodeDocuments, root);
  return d === undefined ? rootId : d;
}
function isHtmlDocument(docId) { return docId === rootId ? HTML_DOCUMENT : MapGet(madeDocuments, docId).html; }
function documentContentType(docId) { return docId === rootId ? CONTENT_TYPE : MapGet(madeDocuments, docId).contentType; }
/* The tagName of the element [id]: its qualified name, uppercased for an HTML element of an HTML document. */
function tagNameOf(id) {
  var n = nameOf(id);
  if (n.ns !== XHTML_NS || !isHtmlDocument(ownerDocId(id))) return n.qualified;
  return n.upper === null ? (n.upper = asciiUpperCase(n.qualified)) : n.upper;
}
function namespaceOfId(id) { return nameOf(id).ns; }
/* The names that HTML keeps from a custom element, though they are valid otherwise. */
var RESERVED_NAMES = nameSet(['annotation-xml', 'color-profile', 'font-face', 'font-face-src', 'font-face-uri', 'font-face-format',
  'font-face-name', 'missing-glyph']);
/* Whether [name] is a valid custom element name, of HTML, 4.13.2. */
function isCustomName(name) {
  return StringIndexOf(name, '-') > 0 && RegExpTest(RE_CUSTOM_ELEMENT, name) && !RESERVED_NAMES[name];
}
/* [name] as the local name of an element, or an InvalidCharacterError. */
function validLocalName(name, what) {
  if (!RegExpTest(RE_ELEMENT_NAME, name)) {
    throw new DOMException(what + ": The tag name provided ('" + name + "') is not a valid name.", 'InvalidCharacterError');
  }
  return name;
}
/* The namespace, prefix and local name of [qualifiedName] in [ns], by validate and extract of the DOM Standard, 1.4. */
function extractName(ns, qualifiedName, what) {
  ns = ns == null || ns === '' ? null : domString(ns);
  var prefix = null, local = qualifiedName, colon = StringIndexOf(qualifiedName, ':');
  if (colon >= 0) {
    prefix = StringSubstring(qualifiedName, 0, colon);
    local = StringSubstring(qualifiedName, colon + 1);
    if (!prefix.length || hasAsciiSpace(prefix) || StringIndexOf(prefix, '/') >= 0 || StringIndexOf(prefix, '>') >= 0 || StringIndexOf(prefix, '\0') >= 0) {
      throw new DOMException(what + ": The qualified name provided ('" + qualifiedName + "') contains the invalid prefix '" + prefix + "'.", 'InvalidCharacterError');
    }
  }
  validLocalName(local, what);
  function namespaceError(message) { throw new DOMException(what + ': ' + message, 'NamespaceError'); }
  if (prefix !== null && ns === null) namespaceError("The namespace URI provided ('') is not valid for the qualified name provided ('" + qualifiedName + "').");
  if (prefix === 'xml' && ns !== XML_NS) namespaceError("The prefix 'xml' is only for the namespace '" + XML_NS + "'.");
  if ((qualifiedName === 'xmlns' || prefix === 'xmlns') !== (ns === XMLNS_NS)) {
    namespaceError("The name 'xmlns' and its prefix go with the namespace '" + XMLNS_NS + "' alone.");
  }
  return { __proto__: null, ns: ns, prefix: prefix, local: local };
}
/* A new element of the name [n], as an id. */
function createElementId(n) { return K.create(n.local, n.ns, n.prefix); }
/* The document's base URL, of HTML, 2.4.1: the href of its first base element, resolved against
   its URL, or else its URL. */
var baseCache = { __proto__: null, version: -1, location: '', url: '' };
function documentBase() {
  var v = K.version(), loc = K.location();
  if (baseCache.version !== v || baseCache.location !== loc) {
    var url = loc, bases = queryAll(rootId, 'base[href]');
    for (var i = 0; i < bases.length; i++) {
      if (nameOf(bases[i]).ns !== XHTML_NS) continue;
      var u = parseUrl(K.attr(bases[i], 'href'), loc);
      if (u != null) url = u[0];
      break;
    }
    baseCache.version = v; baseCache.location = loc; baseCache.url = url;
  }
  return baseCache.url;
}

/* ---- nodes ---- */

function Node() { illegal('Node'); }
Node.prototype = ObjectCreate(EventTarget.prototype);
constants(Node, ['ELEMENT_NODE', 'ATTRIBUTE_NODE', 'TEXT_NODE', 'CDATA_SECTION_NODE', 'ENTITY_REFERENCE_NODE', 'ENTITY_NODE',
  'PROCESSING_INSTRUCTION_NODE', 'COMMENT_NODE', 'DOCUMENT_NODE', 'DOCUMENT_TYPE_NODE', 'DOCUMENT_FRAGMENT_NODE', 'NOTATION_NODE'], 1);
(function (names) {
  for (var i = 0; i < names.length; i++) constants(Node, [names[i]], MathPow(2, i));
})(['DOCUMENT_POSITION_DISCONNECTED', 'DOCUMENT_POSITION_PRECEDING', 'DOCUMENT_POSITION_FOLLOWING', 'DOCUMENT_POSITION_CONTAINS',
  'DOCUMENT_POSITION_CONTAINED_BY', 'DOCUMENT_POSITION_IMPLEMENTATION_SPECIFIC']);
def(Node.prototype, 'nodeType', function () { return K.kind(idOf(this)); });
def(Node.prototype, 'nodeName', function () {
  var id = idOf(this), kind = K.kind(id);
  return kind === 1 ? tagNameOf(id) : kind === 3 ? '#text' : kind === 8 ? '#comment' : kind === 9 ? '#document' : kind === 11 ? '#document-fragment' : '';
});
def(Node.prototype, 'baseURI', function () { idOf(this); return documentBase(); });
def(Node.prototype, 'parentNode', function () { return wrap(K.parent(idOf(this))); });
def(Node.prototype, 'parentElement', function () { var p = K.parent(idOf(this)); return p == null || K.kind(p) !== 1 ? null : wrap(p); });
/* The ids of the children of [id], which a text node has none of. */
function childIds(id) { return K.children(id) || []; }
def(Node.prototype, 'childNodes', function () {
  var id = idOf(this);
  return cachedList(this, 'childNodes', function () { return liveNodes(function () { return childIds(id); }); });
});
def(Node.prototype, 'firstChild', function () { var c = childIds(idOf(this)); return c.length ? wrap(c[0]) : null; });
def(Node.prototype, 'lastChild', function () { var c = childIds(idOf(this)); return c.length ? wrap(c[c.length - 1]) : null; });
/* The sibling of [id] [step] places along, an element if [elementsOnly], as an id, or null. */
function siblingId(id, step, elementsOnly) {
  var p = K.parent(id);
  if (p == null) return null;
  var c = K.children(p);
  for (var i = ArrayIndexOf(c, id) + step; i >= 0 && i < c.length; i += step) {
    if (!elementsOnly || K.kind(c[i]) === 1) return c[i];
  }
  return null;
}
def(Node.prototype, 'nextSibling', function () { return wrap(siblingId(idOf(this), 1, false)); });
def(Node.prototype, 'previousSibling', function () { return wrap(siblingId(idOf(this), -1, false)); });
def(Node.prototype, 'ownerDocument', function () { var id = idOf(this); return K.kind(id) === 9 ? null : wrap(ownerDocId(id)); });
def(Node.prototype, 'isConnected', function () { return K.connected(idOf(this)); });
def(Node.prototype, 'textContent', function () { var id = idOf(this); return K.kind(id) === 9 ? null : K.text(id); },
  function (v) { var id = idOf(this); if (K.kind(id) !== 9) K.setText(id, v == null ? '' : domString(v)); });
/* Whether [id] is a CharacterData node: a text node or a comment. */
function isCharacterData(id) { var kind = K.kind(id); return kind === 3 || kind === 8; }
def(Node.prototype, 'nodeValue', function () { return isCharacterData(idOf(this)) ? K.text(this.__id) : null; },
  function (v) { if (isCharacterData(idOf(this))) K.setText(this.__id, v == null ? '' : domString(v)); });
Node.prototype.hasChildNodes = function () { return childIds(idOf(this)).length > 0; };
Node.prototype.appendChild = function (child) { return insertNode(idOf(this), child, null, 'appendChild'); };
Node.prototype.insertBefore = function (child, ref) { return insertNode(idOf(this), child, ref, 'insertBefore'); };
Node.prototype.removeChild = function (child) { return removeNode(idOf(this), child, 'removeChild'); };
Node.prototype.replaceChild = function (child, old) {
  var id = idOf(this);
  insertNode(id, child, old, 'replaceChild');
  removeNode(id, old, 'replaceChild');
  return old;
};
Node.prototype.cloneNode = function (deep) { return wrap(K.clone(idOf(this), !!deep)); };
Node.prototype.contains = function (other) {
  if (other == null) return false;
  var self = idOf(this);
  for (var t = idOf(other); t != null; t = K.parent(t)) if (t === self) return true;
  return false;
};
Node.prototype.isSameNode = function (other) { return this === other; };
Node.prototype.normalize = function () {};
Node.prototype.getRootNode = function () { var t = idOf(this), p; while ((p = K.parent(t)) != null) t = p; return wrap(t); };
defineInterface(Node, 'Node', EventTarget);

/* The nodes of a call such as append(), a string made a text node, as the DOM converts them. */
function nodesFrom(args) {
  var out = [];
  for (var i = 0; i < args.length; i++) ArrayPush(out, isNode(args[i]) ? args[i] : textNode(args[i]));
  return out;
}
function ChildNode(proto) {
  proto.remove = function () { var p = K.parent(idOf(this)); if (p != null) K.remove(p, this.__id); };
  proto.before = function () {
    var p = K.parent(idOf(this)); if (p == null) return;
    var nodes = nodesFrom(arguments);
    for (var i = 0; i < nodes.length; i++) insertNode(p, nodes[i], this, 'before');
  };
  proto.after = function () {
    var p = K.parent(idOf(this)); if (p == null) return;
    var next = wrap(siblingId(this.__id, 1, false)), nodes = nodesFrom(arguments);
    for (var i = 0; i < nodes.length; i++) insertNode(p, nodes[i], next, 'after');
  };
  proto.replaceWith = function () {
    var p = K.parent(idOf(this)); if (p == null) return;
    var nodes = nodesFrom(arguments);
    for (var i = 0; i < nodes.length; i++) insertNode(p, nodes[i], this, 'replaceWith');
    removeNode(p, this, 'replaceWith');
  };
}
function appendNodes(id, args, what) {
  var nodes = nodesFrom(args);
  for (var i = 0; i < nodes.length; i++) insertNode(id, nodes[i], null, what);
}
/* The elements under [id] whose qualified name is [name], of the DOM Standard, 4.4: in an HTML
   document, an HTML element matches the name lowercased. A plain name is looked up by the host's
   selectors, whose tags are lowercased, and the rest among every element. */
function byTagName(id, name) {
  if (name === '*') return queryAll(id, '*');
  var lower = asciiLowerCase(name), html = isHtmlDocument(ownerDocId(id));
  var found = RegExpTest(RE_TYPE_NAME, name) ? queryAll(id, lower) : queryAll(id, '*');
  return listFilter(found, function (e) {
    var n = nameOf(e);
    return html && n.ns === XHTML_NS ? n.qualified === lower : n.qualified === name;
  });
}
function byTagNameNS(id, ns, local) {
  ns = ns == null || ns === '' ? null : domString(ns);
  return listFilter(queryAll(id, '*'), function (e) {
    var n = nameOf(e);
    return (ns === '*' || n.ns === ns) && (local === '*' || n.local === local);
  });
}
/* The elements under [id] whose classes hold every one of [names], in tree order. */
function byClassNames(id, names) {
  var wanted = tokenSet(names);
  if (!wanted.length) return [];
  return listFilter(queryAll(id, '*'), function (e) {
    var have = asciiTokens(K.attr(e, 'class') || '');
    for (var j = 0; j < wanted.length; j++) if (ArrayIndexOf(have, wanted[j]) < 0) return false;
    return true;
  });
}
function ParentNode(proto) {
  def(proto, 'children', function () {
    var id = idOf(this);
    return cachedList(this, 'children', function () { return liveElements(function () { return elementIds(id); }); });
  });
  def(proto, 'childElementCount', function () { return elementIds(idOf(this)).length; });
  def(proto, 'firstElementChild', function () { var c = elementIds(idOf(this)); return c.length ? wrap(c[0]) : null; });
  def(proto, 'lastElementChild', function () { var c = elementIds(idOf(this)); return c.length ? wrap(c[c.length - 1]) : null; });
  proto.append = function () { appendNodes(idOf(this), arguments, 'append'); };
  proto.prepend = function () {
    var id = idOf(this), c = childIds(id), first = c.length ? wrap(c[0]) : null, nodes = nodesFrom(arguments);
    for (var i = 0; i < nodes.length; i++) insertNode(id, nodes[i], first, 'prepend');
  };
  proto.replaceChildren = function () { var id = idOf(this); K.setText(id, ''); appendNodes(id, arguments, 'replaceChildren'); };
  proto.querySelector = function (selectors) { return wrap(queryFirst(idOf(this), selectors)); };
  proto.querySelectorAll = function (selectors) { return staticNodes(queryAll(idOf(this), selectors)); };
  proto.getElementsByTagName = function (name) {
    var id = idOf(this), n = domString(name);
    return cachedList(this, 'tag ' + n, function () { return liveElements(function () { return byTagName(id, n); }); });
  };
  proto.getElementsByTagNameNS = function (ns, localName) {
    var id = idOf(this), n = ns == null ? null : domString(ns), local = domString(localName);
    return cachedList(this, 'tagNS ' + n + ' ' + local, function () { return liveElements(function () { return byTagNameNS(id, n, local); }); });
  };
  proto.getElementsByClassName = function (names) {
    var id = idOf(this), n = domString(names);
    return cachedList(this, 'class ' + n, function () { return liveElements(function () { return byClassNames(id, n); }); });
  };
}

/* CharacterData, of the DOM Standard, 4.10, and Text and Comment, which a script may construct. */
function CharacterData() { illegal('CharacterData'); }
CharacterData.prototype = ObjectCreate(Node.prototype);
def(CharacterData.prototype, 'data', function () { return K.text(idOf(this)); }, function (v) { K.setText(idOf(this), v == null ? '' : domString(v)); });
def(CharacterData.prototype, 'length', function () { return K.text(idOf(this)).length; });
/* The data of [node] with [count] code units at [offset] replaced by [data], of the DOM Standard, 4.10. */
function replaceData(node, offset, count, data, what) {
  var id = idOf(node), text = K.text(id), at = unsignedLong(offset);
  if (at > text.length) throw new DOMException(what + ': The offset ' + at + ' is greater than the length ' + text.length + '.', 'IndexSizeError');
  var end = MathMin(text.length, at + unsignedLong(count));
  K.setText(id, StringSubstring(text, 0, at) + data + StringSubstring(text, end));
}
CharacterData.prototype.substringData = function (offset, count) {
  var text = K.text(idOf(this)), at = unsignedLong(offset);
  if (at > text.length) throw new DOMException("Failed to execute 'substringData' on 'CharacterData': The offset " + at + ' is greater than the length ' + text.length + '.', 'IndexSizeError');
  return StringSubstring(text, at, MathMin(text.length, at + unsignedLong(count)));
};
CharacterData.prototype.appendData = function (data) { var id = idOf(this); K.setText(id, K.text(id) + domString(data)); };
CharacterData.prototype.insertData = function (offset, data) { replaceData(this, offset, 0, domString(data), "Failed to execute 'insertData' on 'CharacterData'"); };
CharacterData.prototype.deleteData = function (offset, count) { replaceData(this, offset, count, '', "Failed to execute 'deleteData' on 'CharacterData'"); };
CharacterData.prototype.replaceData = function (offset, count, data) { replaceData(this, offset, count, domString(data), "Failed to execute 'replaceData' on 'CharacterData'"); };
ChildNode(CharacterData.prototype);
defineInterface(CharacterData, 'CharacterData', Node);
function Text(data) {
  needNew(this, Text, 'Text', isNode);
  return textNode(arguments.length && data !== undefined ? domString(data) : '');
}
Text.prototype = ObjectCreate(CharacterData.prototype);
def(Text.prototype, 'wholeText', function () { return K.text(idOf(this)); });
defineInterface(Text, 'Text', CharacterData, 0);
function Comment(data) {
  needNew(this, Comment, 'Comment', isNode);
  return wrap(K.createComment(arguments.length && data !== undefined ? domString(data) : ''));
}
Comment.prototype = ObjectCreate(CharacterData.prototype);
defineInterface(Comment, 'Comment', CharacterData, 0);

/* ---- elements ---- */

function Element() { illegal('Element'); }
Element.prototype = ObjectCreate(Node.prototype);
ChildNode(Element.prototype);
ParentNode(Element.prototype);
def(Element.prototype, 'tagName', function () { return tagNameOf(idOf(this)); });
def(Element.prototype, 'localName', function () { return nameOf(idOf(this)).local; });
def(Element.prototype, 'namespaceURI', function () { return nameOf(idOf(this)).ns; });
def(Element.prototype, 'prefix', function () { return nameOf(idOf(this)).prefix; });
/* The name of an attribute as the host keeps it: lowercased, as the parser lowercases it. */
function attrName(name) { return StringToLowerCase(domString(name)); }
function localPart(name) { return RegExpReplace(RE_PREFIX, domString(name), ''); }
function getAttr(el, name) { var v = K.attr(idOf(el), attrName(name)); return v == null ? null : v; }
function setAttr(el, name, value) {
  var id = idOf(el), n = attrName(name);
  settle(el);
  K.setAttr(id, n, domString(value));
  attributeChanged(el, n, true);
}
function removeAttr(el, name) {
  var id = idOf(el), n = attrName(name);
  if (K.attr(id, n) == null) return;
  settle(el);
  K.removeAttr(id, n);
  attributeChanged(el, n, false);
}
Element.prototype.getAttribute = function (name) { return getAttr(this, name); };
Element.prototype.getAttributeNS = function (ns, name) { return getAttr(this, localPart(name)); };
Element.prototype.setAttribute = function (name, value) {
  needArgs(arguments, 2, "Failed to execute 'setAttribute' on 'Element'");
  var n = domString(name);
  if (!RegExpTest(RE_ATTRIBUTE_NAME, n)) {
    throw new DOMException("Failed to execute 'setAttribute' on 'Element': '" + n + "' is not a valid attribute name.", 'InvalidCharacterError');
  }
  setAttr(this, n, value);
};
Element.prototype.setAttributeNS = function (ns, name, value) { setAttr(this, localPart(name), value); };
Element.prototype.removeAttribute = function (name) { removeAttr(this, name); };
Element.prototype.removeAttributeNS = function (ns, name) { removeAttr(this, localPart(name)); };
Element.prototype.hasAttribute = function (name) { return getAttr(this, name) !== null; };
Element.prototype.hasAttributeNS = function (ns, name) { return getAttr(this, localPart(name)) !== null; };
Element.prototype.hasAttributes = function () { return K.attrNames(idOf(this)).length > 0; };
Element.prototype.getAttributeNames = function () { return listSlice(K.attrNames(idOf(this))); };
Element.prototype.toggleAttribute = function (name, force) {
  var has = getAttr(this, name) !== null;
  var want = force === undefined ? !has : !!force;
  if (want && !has) setAttr(this, name, '');
  if (!want && has) removeAttr(this, name);
  return want;
};
def(Element.prototype, 'attributes', function () { idOf(this); return attributesOf(this); });
Element.prototype.getAttributeNode = function (name) { return namedAttr(wrap(idOf(this)), name); };
Element.prototype.getAttributeNodeNS = function (ns, name) { return namedAttr(wrap(idOf(this)), localPart(name)); };
Element.prototype.setAttributeNode = function (attr) { return setAttrNode(wrap(idOf(this)), attr); };
Element.prototype.setAttributeNodeNS = Element.prototype.setAttributeNode;
Element.prototype.removeAttributeNode = function (attr) {
  var el = wrap(idOf(this)), s = attrOfNode(attr);
  if (s.owner !== el) throw new DOMException("Failed to execute 'removeAttributeNode' on 'Element': The node provided is owned by another element.", 'NotFoundError');
  var name = s.name;
  detachAttr(attr);
  removeAttr(el, name);
  return attr;
};
/* The IDL attribute [prop] that reflects the content attribute [attr] as a string. */
function reflect(proto, prop, attr) {
  def(proto, prop, function () { var v = K.attr(idOf(this), attr); return v == null ? '' : v; }, function (v) { setAttr(this, attr, domString(v)); });
}
reflect(Element.prototype, 'id', 'id');
reflect(Element.prototype, 'className', 'class');
reflect(Element.prototype, 'slot', 'slot');
/* classList, whose setter sets its value ([PutForwards=value]). */
def(Element.prototype, 'classList', function () { idOf(this); return tokenList(this, 'class'); },
  function (v) { idOf(this); tokenList(this, 'class').value = v; });
Element.prototype.matches = function (selectors) { return matchesId(idOf(this), selectors); };
Element.prototype.webkitMatchesSelector = Element.prototype.matches;
Element.prototype.closest = function (selectors) { return wrap(closestId(idOf(this), selectors)); };
def(Element.prototype, 'innerHTML', function () { return K.html(idOf(this), false); }, function (v) { K.setHtml(idOf(this), v == null ? '' : String(v)); });
def(Element.prototype, 'outerHTML', function () { return K.html(idOf(this), true); }, function (v) {
  var p = K.parent(idOf(this)); if (p == null) return;
  check(K.insertHtml(this.__id, 'beforebegin', v == null ? '' : String(v)), 'outerHTML');
  removeNode(p, this, 'outerHTML');
});
Element.prototype.insertAdjacentHTML = function (position, html) { check(K.insertHtml(idOf(this), String(position), String(html)), 'insertAdjacentHTML'); };
/* Puts [node] at [position] of [el], as insertAdjacentElement does; null where el has no parent to put it by. */
function insertAdjacent(el, position, node, what) {
  var id = idOf(el), p = K.parent(id);
  switch (StringToLowerCase(String(position))) {
    case 'beforebegin': if (p == null) return null; insertNode(p, node, el, what); break;
    case 'afterbegin': var c = K.children(id); insertNode(id, node, c && c.length ? wrap(c[0]) : null, what); break;
    case 'beforeend': insertNode(id, node, null, what); break;
    case 'afterend': if (p == null) return null; insertNode(p, node, wrap(siblingId(id, 1, false)), what); break;
    default: throw new DOMException('not a position: ' + position, 'SyntaxError');
  }
  return node;
}
Element.prototype.insertAdjacentElement = function (position, el) { return insertAdjacent(this, position, el, 'insertAdjacentElement'); };
Element.prototype.insertAdjacentText = function (position, text) { insertAdjacent(this, position, textNode(text), 'insertAdjacentText'); };
def(Element.prototype, 'nextElementSibling', function () { return wrap(siblingId(idOf(this), 1, true)); });
def(Element.prototype, 'previousElementSibling', function () { return wrap(siblingId(idOf(this), -1, true)); });
/* The box of [el], in CSS pixels, as [x, y, width, height]. */
function boxOf(el) { return K.rect(idOf(el)) || [0, 0, 0, 0]; }
Element.prototype.getBoundingClientRect = function () { var r = boxOf(this); return newRect(DOMRect, r[0], r[1], r[2], r[3]); };
Element.prototype.getClientRects = function () {
  var r = boxOf(this);
  return makeList(DOMRectList.prototype, STATIC, fixedSource(r[2] || r[3] ? [newRect(DOMRect, r[0], r[1], r[2], r[3])] : []), null);
};
def(Element.prototype, 'clientWidth', function () { return MathRound(boxOf(this)[2]); });
def(Element.prototype, 'clientHeight', function () { return MathRound(boxOf(this)[3]); });
def(Element.prototype, 'scrollWidth', function () { return MathRound(boxOf(this)[2]); });
def(Element.prototype, 'scrollHeight', function () { return MathRound(boxOf(this)[3]); });
def(Element.prototype, 'scrollTop', function () { return 0; }, function () {});
def(Element.prototype, 'scrollLeft', function () { return 0; }, function () {});
Element.prototype.scrollIntoView = function () {};
Element.prototype.scrollTo = function () {};
/* A promise that is already fulfilled, made by the Promise this DOM took. */
function resolved(v) { return new Promise(function (resolve) { resolve(v); }); }
/* A promise that is already rejected with [e], made the same way. */
function rejected(e) { return new Promise(function (resolve, reject) { reject(e); }); }
Element.prototype.animate = function () { return { finished: resolved(), cancel: function () {}, play: function () {}, pause: function () {} }; };
defineInterface(Element, 'Element', Node);

/* True when [s] holds ASCII whitespace, which no token of a DOMTokenList may. */
function hasAsciiSpace(s) {
  for (var i = 0; i < s.length; i++) {
    var c = StringCharCodeAt(s, i);
    if (c === 32 || c === 9 || c === 10 || c === 12 || c === 13) return true;
  }
  return false;
}

/* ---- CSSStyleDeclaration, of CSSOM, 6.6 ----
   The declarations of an element's style attribute, or the computed values of its properties. A
   declaration is a legacy platform object of Web IDL, 3.9, whose indices name its properties; a
   CSS property, in camel case or dashed, comes before what the object inherits, as an attribute of
   the interface would, and any other key is a plain property of the object, as in a browser. */
/* A property name as CSSOM takes it: lowercased, but for a custom property. */
function propertyName(name) { name = domString(name); return StringSubstring(name, 0, 2) === '--' ? name : asciiLowerCase(name); }
function parseStyle(text) {
  var out = [];
  var parts = [], depth = 0, quote = '', start = 0;
  for (var i = 0; i < text.length; i++) {
    var c = StringCharAt(text, i);
    if (quote) { if (c === quote) quote = ''; }
    else if (c === '"' || c === "'") quote = c;
    else if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (c === ';' && depth === 0) { ArrayPush(parts, StringSubstring(text, start, i)); start = i + 1; }
  }
  ArrayPush(parts, StringSubstring(text, start));
  for (var j = 0; j < parts.length; j++) {
    var colon = StringIndexOf(parts[j], ':');
    if (colon < 0) continue;
    var name = propertyName(StringTrim(StringSubstring(parts[j], 0, colon)));
    var value = StringTrim(StringSubstring(parts[j], colon + 1));
    var important = RegExpTest(RE_IMPORTANT, value);
    if (important) value = StringTrim(RegExpReplace(RE_IMPORTANT, value, ''));
    if (name.length) ArrayPush(out, { __proto__: null, name: name, value: value, important: important });
  }
  return out;
}
var CSSStyleDeclaration = abstractInterface('CSSStyleDeclaration');
var styles = new WeakMap(), inlineStyles = new WeakMap();
function styleState(o) {
  var s = o !== null && typeof o === 'object' ? WeakMapGet(styles, o) : undefined;
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
/* The declarations of [s]: a computed style lists none, as the host answers it a property at a time. */
function styleDecls(s) { return s.computed ? [] : parseStyle(K.attr(idOf(s.el), 'style') || ''); }
function writeStyle(s, decls) {
  var text = '';
  for (var i = 0; i < decls.length; i++) text += (i ? ' ' : '') + decls[i].name + ': ' + decls[i].value + (decls[i].important ? ' !important' : '') + ';';
  if (text.length) K.setAttr(idOf(s.el), 'style', text); else K.removeAttr(idOf(s.el), 'style');
}
function styleValue(s, name) {
  if (s.computed) return K.computed(idOf(s.el), name) || '';
  var d = styleDecls(s);
  for (var i = d.length - 1; i >= 0; i--) if (d[i].name === name) return cssValue(d[i].value);
  return '';
}
function stylePriority(s, name) {
  var d = styleDecls(s);
  for (var i = d.length - 1; i >= 0; i--) if (d[i].name === name) return d[i].important ? 'important' : '';
  return '';
}
function writable(s, what) {
  if (s.computed) {
    throw new DOMException("Failed to execute '" + what + "' on 'CSSStyleDeclaration': These styles are computed, and therefore read-only.", 'NoModificationAllowedError');
  }
}
/* Whether [name] is a property a declaration takes: a custom property or a CSS property it knows. */
function knownProperty(name) { return StringSubstring(name, 0, 2) === '--' || cssProperty(name) !== null; }
function removeStyle(s, name) {
  writable(s, 'removeProperty');
  var old = styleValue(s, name);
  var d = styleDecls(s), kept = listFilter(d, function (x) { return x.name !== name; });
  if (kept.length !== d.length) writeStyle(s, kept);
  return old;
}
function setStyle(s, name, value, priority) {
  writable(s, 'setProperty');
  if (!knownProperty(name)) return;
  var v = value === null ? '' : domString(value);
  if (v === '') { removeStyle(s, name); return; }
  var p = priority === undefined ? '' : domString(priority);
  if (p !== '' && asciiLowerCase(p) !== 'important') return;
  var d = listFilter(styleDecls(s), function (x) { return x.name !== name; });
  ArrayPush(d, { __proto__: null, name: name, value: v, important: p !== '' });
  writeStyle(s, d);
}
(function (p) {
  def(p, 'cssText', function () { var s = styleState(this); return s.computed ? '' : K.attr(idOf(s.el), 'style') || ''; }, function (v) {
    var s = styleState(this);
    writable(s, 'cssText');
    var text = v === null ? '' : domString(v);
    if (text === '') K.removeAttr(idOf(s.el), 'style'); else K.setAttr(idOf(s.el), 'style', text);
  });
  def(p, 'length', function () { return styleDecls(styleState(this)).length; });
  def(p, 'parentRule', function () { styleState(this); return null; });
  def(p, 'cssFloat', function () { return styleValue(styleState(this), 'float'); }, function (v) { setStyle(styleState(this), 'float', v, ''); });
  p.item = function (index) { var d = styleDecls(styleState(this)), i = unsignedLong(index); return i < d.length ? d[i].name : ''; };
  p.getPropertyValue = function (property) { return styleValue(styleState(this), propertyName(property)); };
  p.getPropertyPriority = function (property) { return stylePriority(styleState(this), propertyName(property)); };
  p.setProperty = function (property, value, priority) {
    var s = styleState(this);
    writable(s, 'setProperty');
    setStyle(s, propertyName(property), value, priority);
  };
  p.removeProperty = function (property) {
    var s = styleState(this);
    writable(s, 'removeProperty');
    var name = propertyName(property);
    return knownProperty(name) ? removeStyle(s, name) : '';
  };
})(CSSStyleDeclaration.prototype);
defineInterface(CSSStyleDeclaration, 'CSSStyleDeclaration');
var STYLE_HANDLER = {
  __proto__: null,
  get: function (t, key, receiver) {
    if (typeof key === 'string' && !ObjectHasOwn(t, key)) {
      var s = WeakMapGet(styles, t), i = arrayIndex(key);
      if (i >= 0) { var d = styleDecls(s); return i < d.length ? d[i].name : undefined; }
      var name = cssProperty(key);
      if (name !== null) return styleValue(s, name);
    }
    return receiverGet(t, key, receiver);
  },
  set: function (t, key, value) {
    if (typeof key === 'string' && !ObjectHasOwn(t, key)) {
      if (arrayIndex(key) >= 0) return false;
      var name = cssProperty(key);
      if (name !== null) { setStyle(WeakMapGet(styles, t), name, value, ''); return true; }
    }
    return ReflectSet(t, key, value);
  },
  has: function (t, key) {
    if (typeof key === 'string') {
      var i = arrayIndex(key);
      if (i >= 0) return i < styleDecls(WeakMapGet(styles, t)).length;
      if (cssProperty(key) !== null) return true;
    }
    return ReflectHas(t, key);
  },
  ownKeys: function (t) {
    var d = styleDecls(WeakMapGet(styles, t)), out = [], own = ReflectOwnKeys(t);
    for (var i = 0; i < d.length; i++) ArrayPush(out, String(i));
    for (var j = 0; j < own.length; j++) ArrayPush(out, own[j]);
    return out;
  },
  getOwnPropertyDescriptor: function (t, key) {
    var i = arrayIndex(key);
    if (i >= 0) {
      var d = styleDecls(WeakMapGet(styles, t));
      return i < d.length ? { __proto__: null, value: d[i].name, writable: false, enumerable: true, configurable: true } : undefined;
    }
    return ObjectGetOwnPropertyDescriptor(t, key);
  },
  defineProperty: function (t, key, desc) { return arrayIndex(key) >= 0 ? false : ReflectDefineProperty(t, key, ownDescriptor(desc)); },
  deleteProperty: function (t, key) {
    var i = arrayIndex(key);
    return i >= 0 ? i >= styleDecls(WeakMapGet(styles, t)).length : ReflectDeleteProperty(t, key);
  },
  preventExtensions: function () { return false; }
};
/* A style declaration of [el]: its style attribute's, or its computed one for [computed]. */
function makeStyle(el, computed) {
  var t = ObjectCreate(CSSStyleDeclaration.prototype), p = new Proxy(t, STYLE_HANDLER), s = { __proto__: null, el: el, computed: computed };
  WeakMapSet(styles, t, s);
  WeakMapSet(styles, p, s);
  return p;
}
function styleOf(el) {
  var style = WeakMapGet(inlineStyles, el);
  if (style === undefined) { style = makeStyle(el, false); WeakMapSet(inlineStyles, el, style); }
  return style;
}
"""

/**
 * The lists of [DOM_PRELUDE] (#542): `NodeList`, `HTMLCollection`, `DOMTokenList`, `Attr` and `NamedNodeMap`, and the rectangles of Geometry Interfaces.
 */
internal const val DOM_PRELUDE_COLLECTIONS: String = """/* ---- lists (#542) ----
   A NodeList, an HTMLCollection and the other lists of the DOM are legacy platform objects of Web
   IDL, 3.9: a proxy over an object of the list's interface answers an array index with an item,
   and a list with named properties a name with one too. A live list reads its items again from
   its source once the host's count of the changes to the tree moved, so a script that holds one
   sees the tree as it is now; a static list keeps the items it was made with. */
var lists = new WeakMap();
var STATIC = 0, LIVE = 1, ALWAYS = 2;
/* The number an array index names, or -1 for any other key. */
function arrayIndex(key) {
  if (typeof key !== 'string') return -1;
  var n = +key;
  return n >= 0 && n < 4294967295 && MathFloor(n) === n && String(n) === key ? n : -1;
}
function listState(o) {
  var s = o !== null && typeof o === 'object' ? WeakMapGet(lists, o) : undefined;
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
/* The items of a list as its source answers them: node ids, or the values themselves. */
function listItems(s) {
  if (s.items === null || s.mode === ALWAYS || (s.mode === LIVE && s.version !== K.version())) {
    var source = s.source;
    if (s.mode === LIVE) s.version = K.version();
    s.items = source();
  }
  return s.items;
}
function listItem(s, i) {
  var items = listItems(s), w = s.wrap;
  if (i >= items.length) return undefined;
  return w === null ? items[i] : w(items[i]);
}
/* The item a list names [key] with, or undefined. */
function listNamed(s, key) {
  var named = s.named;
  return named === null || typeof key !== 'string' || key === '' ? undefined : named(listItems(s), key);
}
var LIST_HANDLER = {
  __proto__: null,
  get: function (t, key, receiver) {
    var s = WeakMapGet(lists, t), i = arrayIndex(key);
    if (i >= 0) return listItem(s, i);
    if (s.named !== null && !ReflectHas(t, key)) {
      var n = listNamed(s, key);
      if (n !== undefined) return n;
    }
    return receiverGet(t, key, receiver);
  },
  set: function (t, key, value) {
    var s = WeakMapGet(lists, t), i = arrayIndex(key), setIndex = s.setIndex;
    if (i >= 0) {
      if (setIndex === null) return false;
      setIndex(i, value);
      return true;
    }
    if (s.named !== null && !ReflectHas(t, key) && listNamed(s, key) !== undefined) return false;
    return ReflectSet(t, key, value);
  },
  has: function (t, key) {
    var s = WeakMapGet(lists, t), i = arrayIndex(key);
    if (i >= 0) return i < listItems(s).length;
    if (s.named !== null && !ReflectHas(t, key) && listNamed(s, key) !== undefined) return true;
    return ReflectHas(t, key);
  },
  ownKeys: function (t) {
    var s = WeakMapGet(lists, t), items = listItems(s), out = [], names = s.names;
    for (var i = 0; i < items.length; i++) ArrayPush(out, String(i));
    if (names !== null) {
      var all = names(items);
      for (var j = 0; j < all.length; j++) if (!ReflectHas(t, all[j])) ArrayPush(out, all[j]);
    }
    var own = ReflectOwnKeys(t);
    for (var k = 0; k < own.length; k++) ArrayPush(out, own[k]);
    return out;
  },
  getOwnPropertyDescriptor: function (t, key) {
    var s = WeakMapGet(lists, t), i = arrayIndex(key);
    if (i >= 0) {
      if (i >= listItems(s).length) return undefined;
      return { __proto__: null, value: listItem(s, i), writable: s.setIndex !== null, enumerable: true, configurable: true };
    }
    if (s.named !== null && !ReflectHas(t, key)) {
      var n = listNamed(s, key);
      if (n !== undefined) return { __proto__: null, value: n, writable: false, enumerable: false, configurable: true };
    }
    return ObjectGetOwnPropertyDescriptor(t, key);
  },
  defineProperty: function (t, key, desc) {
    var s = WeakMapGet(lists, t), i = arrayIndex(key), setIndex = s.setIndex;
    if (i >= 0) {
      if (setIndex === null || !ObjectHasOwn(desc, 'value') || ObjectHasOwn(desc, 'get') || ObjectHasOwn(desc, 'set')) return false;
      setIndex(i, desc.value);
      return true;
    }
    if (s.named !== null && !ObjectHasOwn(t, key) && listNamed(s, key) !== undefined) return false;
    return ReflectDefineProperty(t, key, ownDescriptor(desc));
  },
  deleteProperty: function (t, key) {
    var s = WeakMapGet(lists, t), i = arrayIndex(key);
    if (i >= 0) return i >= listItems(s).length;
    if (s.named !== null && !ReflectHas(t, key) && listNamed(s, key) !== undefined) return false;
    return ReflectDeleteProperty(t, key);
  },
  preventExtensions: function () { return false; }
};
/* A list of [proto]'s interface whose items [source] answers, kept as they are for STATIC, read
   again after a change to the tree for LIVE, and on each use for ALWAYS. [wrapItem] makes an
   item of what the source answers (a node of an id unless given), [named] answers the item of a
   name and [names] the names a list has, for one with named properties. */
function makeList(proto, mode, source, wrapItem, named, names) {
  var t = ObjectCreate(proto);
  var s = { __proto__: null, mode: mode, source: source, wrap: wrapItem === undefined ? wrap : wrapItem,
    named: named || null, names: names || null, items: null, version: -1, setIndex: null, owner: null };
  var p = new Proxy(t, LIST_HANDLER);
  WeakMapSet(lists, t, s);
  WeakMapSet(lists, p, s);
  return p;
}
function fixedSource(items) { return function () { return items; }; }
/* The members of an iterable list, of Web IDL, 3.7.8: the methods of Array.prototype. */
function iterableList(proto, forEachToo) {
  if (forEachToo) {
    proto.entries = ArrayEntries; proto.keys = ArrayKeys; proto.values = ArrayValues; proto.forEach = ArrayForEach;
  }
  ObjectDefineProperty(proto, SymbolIterator, { __proto__: null, value: ArrayValues, writable: true, configurable: true });
}
function listLength(proto) { def(proto, 'length', function () { return listItems(listState(this)).length; }); }
function listItemMethod(proto) {
  proto.item = function (index) { var v = listItem(listState(this), unsignedLong(index)); return v === undefined ? null : v; };
}

/* NodeList, of the DOM Standard, 4.2.10.1. */
var NodeList = abstractInterface('NodeList');
listLength(NodeList.prototype);
listItemMethod(NodeList.prototype);
iterableList(NodeList.prototype, true);
defineInterface(NodeList, 'NodeList');
/* A static NodeList of [ids]. */
function staticNodes(ids) { return makeList(NodeList.prototype, STATIC, fixedSource(listSlice(ids || []))); }
/* A live NodeList of what [source] answers. */
function liveNodes(source) { return makeList(NodeList.prototype, LIVE, source); }

/* HTMLCollection, of the DOM Standard, 4.2.10.2: its named properties are the ids of its elements
   and the names of those in the HTML namespace. */
var HTMLCollection = abstractInterface('HTMLCollection');
listLength(HTMLCollection.prototype);
listItemMethod(HTMLCollection.prototype);
HTMLCollection.prototype.namedItem = function (name) { var v = listNamed(listState(this), domString(name)); return v === undefined ? null : v; };
iterableList(HTMLCollection.prototype, false);
defineInterface(HTMLCollection, 'HTMLCollection');
function namedElement(ids, key) {
  for (var i = 0; i < ids.length; i++) {
    if (K.attr(ids[i], 'id') === key || (K.attr(ids[i], 'name') === key && namespaceOfId(ids[i]) === XHTML_NS)) return wrap(ids[i]);
  }
  return undefined;
}
function collectionNames(ids) {
  var out = [];
  for (var i = 0; i < ids.length; i++) {
    var id = K.attr(ids[i], 'id'), name = K.attr(ids[i], 'name');
    if (id && ArrayIndexOf(out, id) < 0) ArrayPush(out, id);
    if (name && namespaceOfId(ids[i]) === XHTML_NS && ArrayIndexOf(out, name) < 0) ArrayPush(out, name);
  }
  return out;
}
/* A live HTMLCollection of the elements [source] answers, as ids. */
function liveElements(source, proto) { return makeList(proto || HTMLCollection.prototype, LIVE, source, wrap, namedElement, collectionNames); }
/* The collection a [key] of [owner] answers each time, made the first time by [make]. */
var listCaches = new WeakMap();
function cachedList(owner, key, make) {
  var cache = WeakMapGet(listCaches, owner);
  if (cache === undefined) { cache = ObjectCreate(null); WeakMapSet(listCaches, owner, cache); }
  return cache[key] || (cache[key] = make());
}

/* The elements under [id] in tree order, as ids, that [keep] keeps, or all of them. */
function descendants(id, keep) {
  var all = queryAll(id, '*');
  return keep ? listFilter(all, keep) : all;
}

/* ---- DOMTokenList, of the DOM Standard, 4.2.10.3 ----
   The tokens of an element's attribute as an ordered set, a live list whose changes write the
   attribute back. One element has one list for each attribute. */
var DOMTokenList = abstractInterface('DOMTokenList');
function tokenState(list) {
  var s = listState(list);
  if (s.owner === null) throw new TypeError('Illegal invocation');
  return s;
}
/* The ordered set of the tokens of [value], of the DOM Standard, 4.2.10.3. */
function tokenSet(value) {
  var all = asciiTokens(value || ''), out = [];
  for (var i = 0; i < all.length; i++) if (ArrayIndexOf(out, all[i]) < 0) ArrayPush(out, all[i]);
  return out;
}
function validToken(t) {
  t = domString(t);
  if (!t.length) throw new DOMException('The token provided must not be empty.', 'SyntaxError');
  if (hasAsciiSpace(t)) throw new DOMException("The token provided ('" + t + "') contains HTML space characters, which are not valid in tokens.", 'InvalidCharacterError');
  return t;
}
/* The update steps: the set written back, unless the element has no such attribute and the set is empty. */
function writeTokens(s, items) {
  var el = s.owner;
  if (K.attr(idOf(el), s.attr) == null && !items.length) return;
  setAttr(el, s.attr, ArrayJoin(items, ' '));
}
function tokensOf(s) { return listSlice(listItems(s)); }
listLength(DOMTokenList.prototype);
listItemMethod(DOMTokenList.prototype);
DOMTokenList.prototype.contains = function (token) { return ArrayIndexOf(listItems(tokenState(this)), domString(token)) >= 0; };
DOMTokenList.prototype.add = function () {
  var s = tokenState(this), add = [];
  for (var i = 0; i < arguments.length; i++) ArrayPush(add, validToken(arguments[i]));
  var items = tokensOf(s);
  for (var j = 0; j < add.length; j++) if (ArrayIndexOf(items, add[j]) < 0) ArrayPush(items, add[j]);
  writeTokens(s, items);
};
DOMTokenList.prototype.remove = function () {
  var s = tokenState(this), remove = [];
  for (var i = 0; i < arguments.length; i++) ArrayPush(remove, validToken(arguments[i]));
  writeTokens(s, listFilter(tokensOf(s), function (t) { return ArrayIndexOf(remove, t) < 0; }));
};
DOMTokenList.prototype.toggle = function (token, force) {
  var s = tokenState(this), t = validToken(token), items = tokensOf(s), has = ArrayIndexOf(items, t) >= 0;
  if (has) {
    if (force === undefined || !force) { writeTokens(s, listFilter(items, function (x) { return x !== t; })); return false; }
    return true;
  }
  if (force === undefined || force) { ArrayPush(items, t); writeTokens(s, items); return true; }
  return false;
};
DOMTokenList.prototype.replace = function (token, newToken) {
  var s = tokenState(this), a = validToken(token), b = validToken(newToken), items = tokensOf(s);
  if (ArrayIndexOf(items, a) < 0) return false;
  var out = [];
  for (var i = 0; i < items.length; i++) {
    var t = items[i] === a || items[i] === b ? b : items[i];
    if (ArrayIndexOf(out, t) < 0) ArrayPush(out, t);
  }
  writeTokens(s, out);
  return true;
};
DOMTokenList.prototype.supports = function (token) {
  var s = tokenState(this), supported = s.supported;
  if (supported === null) throw new TypeError("Failed to execute 'supports' on 'DOMTokenList': DOMTokenList has no supported tokens.");
  return supported[asciiLowerCase(domString(token))] === true;
};
def(DOMTokenList.prototype, 'value', function () { var s = tokenState(this); return K.attr(idOf(s.owner), s.attr) || ''; },
  function (v) { var s = tokenState(this); setAttr(s.owner, s.attr, domString(v)); });
DOMTokenList.prototype.toString = function () { var s = tokenState(this); return K.attr(idOf(s.owner), s.attr) || ''; };
iterableList(DOMTokenList.prototype, true);
defineInterface(DOMTokenList, 'DOMTokenList');
/* The DOMTokenList of [el]'s [attr], whose supported tokens are [supported], or none. */
function tokenList(el, attr, supported) {
  return cachedList(el, 'tokens ' + attr, function () {
    var list = makeList(DOMTokenList.prototype, LIVE, function () { return tokenSet(K.attr(el.__id, attr)); }, null);
    var s = WeakMapGet(lists, list);
    s.owner = el; s.attr = attr; s.supported = supported || null;
    return list;
  });
}

/* ---- Attr and NamedNodeMap, of the DOM Standard, 4.9.1 and 4.9.2 ----
   An attribute of an element is one Attr while it is there; one that was taken off, or made by
   createAttribute, keeps its name and value for itself. */
var attrs = new WeakMap();
var Attr = abstractInterface('Attr', Node);
function attrOfNode(a) {
  var s = a !== null && typeof a === 'object' ? WeakMapGet(attrs, a) : undefined;
  if (s === undefined) throw new TypeError('Illegal invocation');
  return s;
}
function newAttr(name, value, owner) {
  var a = ObjectCreate(Attr.prototype);
  WeakMapSet(attrs, a, { __proto__: null, name: name, value: value, owner: owner });
  return a;
}
/* The Attr of [el]'s attribute [name], which it has. */
var attrNodes = new WeakMap();
function attrNodeCache(el) {
  var cache = WeakMapGet(attrNodes, el);
  if (cache === undefined) { cache = ObjectCreate(null); WeakMapSet(attrNodes, el, cache); }
  return cache;
}
function attrNode(el, name) {
  var cache = attrNodeCache(el);
  var a = cache[name];
  if (a && attrOfNode(a).owner === el) return a;
  return (cache[name] = newAttr(name, null, el));
}
function attrValue(s) { if (s.owner === null) return s.value; var v = K.attr(s.owner.__id, s.name); return v == null ? s.value || '' : v; }
/* Takes [a] off its element, which keeps no attribute of its name. */
function detachAttr(a) {
  var s = attrOfNode(a);
  if (s.owner === null) return;
  s.value = attrValue(s);
  var cache = attrNodeCache(s.owner);
  if (cache[s.name] === a) cache[s.name] = undefined;
  s.owner = null;
}
def(Attr.prototype, 'name', function () { return attrOfNode(this).name; });
def(Attr.prototype, 'localName', function () { return attrOfNode(this).name; });
def(Attr.prototype, 'nodeName', function () { return attrOfNode(this).name; });
def(Attr.prototype, 'namespaceURI', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'prefix', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'ownerElement', function () { return attrOfNode(this).owner; });
def(Attr.prototype, 'specified', function () { attrOfNode(this); return true; });
def(Attr.prototype, 'nodeType', function () { attrOfNode(this); return 2; });
function attrText(name) {
  def(Attr.prototype, name, function () { return attrValue(attrOfNode(this)); }, function (v) {
    var s = attrOfNode(this), value = v === null && name !== 'value' ? '' : domString(v);
    if (s.owner === null) s.value = value; else setAttr(s.owner, s.name, value);
  });
}
attrText('value'); attrText('nodeValue'); attrText('textContent');
def(Attr.prototype, 'ownerDocument', function () { attrOfNode(this); return document; });
def(Attr.prototype, 'parentNode', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'parentElement', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'childNodes', function () { attrOfNode(this); return cachedList(this, 'childNodes', function () { return staticNodes([]); }); });
def(Attr.prototype, 'firstChild', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'lastChild', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'previousSibling', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'nextSibling', function () { attrOfNode(this); return null; });
def(Attr.prototype, 'isConnected', function () { var s = attrOfNode(this); return s.owner !== null && K.connected(s.owner.__id); });
Attr.prototype.hasChildNodes = function () { attrOfNode(this); return false; };
Attr.prototype.cloneNode = function () { var s = attrOfNode(this); return newAttr(s.name, attrValue(s), null); };
Attr.prototype.isSameNode = function (other) { attrOfNode(this); return this === other; };
Attr.prototype.contains = function (other) { attrOfNode(this); return this === other; };
Attr.prototype.getRootNode = function () { attrOfNode(this); return this; };
defineInterface(Attr, 'Attr', Node);

var NamedNodeMap = abstractInterface('NamedNodeMap');
listLength(NamedNodeMap.prototype);
listItemMethod(NamedNodeMap.prototype);
function mapOwner(map) {
  var s = listState(map);
  if (s.owner === null) throw new TypeError('Illegal invocation');
  return s.owner;
}
/* The Attr of [el]'s attribute [name], or null when it has none of that name. */
function namedAttr(el, name) { var n = attrName(name); return K.attr(idOf(el), n) == null ? null : attrNode(el, n); }
/* Takes [el]'s attribute [name] off, as removeNamedItem does, and answers its Attr. */
function removeNamedAttr(el, name) {
  var n = attrName(name);
  if (K.attr(idOf(el), n) == null) throw new DOMException("Failed to execute 'removeNamedItem' on 'NamedNodeMap': No item with name '" + n + "' was found.", 'NotFoundError');
  var a = attrNode(el, n);
  detachAttr(a);
  removeAttr(el, n);
  return a;
}
NamedNodeMap.prototype.getNamedItem = function (name) { return namedAttr(mapOwner(this), name); };
NamedNodeMap.prototype.getNamedItemNS = function (ns, localName) { return namedAttr(mapOwner(this), localName); };
NamedNodeMap.prototype.setNamedItem = function (attr) { return setAttrNode(mapOwner(this), attr); };
NamedNodeMap.prototype.setNamedItemNS = NamedNodeMap.prototype.setNamedItem;
NamedNodeMap.prototype.removeNamedItem = function (name) { return removeNamedAttr(mapOwner(this), name); };
NamedNodeMap.prototype.removeNamedItemNS = function (ns, localName) { return removeNamedAttr(mapOwner(this), localName); };
iterableList(NamedNodeMap.prototype, false);
defineInterface(NamedNodeMap, 'NamedNodeMap');
/* Sets the attribute [attr] stands for on [el], and answers the Attr it replaced, or null. */
function setAttrNode(el, attr) {
  var s = attrOfNode(attr);
  if (s.owner !== null && s.owner !== el) throw new DOMException("Failed to execute 'setAttributeNode' on 'Element': The node provided is an attribute node that is already an attribute of another Element; attribute nodes must be explicitly cloned.", 'InUseAttributeError');
  if (s.owner === el) return attr;
  var old = K.attr(idOf(el), s.name) == null ? null : attrNode(el, s.name);
  if (old !== null) detachAttr(old);
  setAttr(el, s.name, s.value || '');
  attrNodeCache(el)[s.name] = attr;
  s.owner = el;
  return old;
}
function attributesOf(el) {
  return cachedList(el, 'attributes', function () {
    var map = makeList(NamedNodeMap.prototype, LIVE, function () { return listSlice(K.attrNames(el.__id)); },
      function (name) { return attrNode(el, name); },
      function (names, key) { return ArrayIndexOf(names, key) >= 0 ? attrNode(el, key) : undefined; },
      function (names) { return listSlice(names); });
    WeakMapGet(lists, map).owner = el;
    return map;
  });
}

/* ---- DOMRectReadOnly, DOMRect and DOMRectList, of Geometry Interfaces ---- */
var rects = new WeakMap();
function rectOf(r) {
  var d = r !== null && typeof r === 'object' ? WeakMapGet(rects, r) : undefined;
  if (d === undefined) throw new TypeError('Illegal invocation');
  return d;
}
function initRect(self, ctor, name, args) {
  if (!isA(self, ctor) || WeakMapHas(rects, self)) throw new TypeError("Failed to construct '" + name + "': Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
  WeakMapSet(rects, self, { __proto__: null, x: args.length > 0 && args[0] !== undefined ? +args[0] : 0,
    y: args.length > 1 && args[1] !== undefined ? +args[1] : 0, width: args.length > 2 && args[2] !== undefined ? +args[2] : 0,
    height: args.length > 3 && args[3] !== undefined ? +args[3] : 0 });
}
function DOMRectReadOnly() { initRect(this, DOMRectReadOnly, 'DOMRectReadOnly', arguments); }
function DOMRect() { initRect(this, DOMRect, 'DOMRect', arguments); }
DOMRect.prototype = ObjectCreate(DOMRectReadOnly.prototype);
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) {
    def(DOMRectReadOnly.prototype, n, function () { return rectOf(this)[n]; });
    def(DOMRect.prototype, n, function () { return rectOf(this)[n]; }, function (v) { rectOf(this)[n] = +v; });
  })(names[i]);
})(['x', 'y', 'width', 'height']);
def(DOMRectReadOnly.prototype, 'top', function () { var r = rectOf(this); return MathMin(r.y, r.y + r.height); });
def(DOMRectReadOnly.prototype, 'right', function () { var r = rectOf(this); return MathMax(r.x, r.x + r.width); });
def(DOMRectReadOnly.prototype, 'bottom', function () { var r = rectOf(this); return MathMax(r.y, r.y + r.height); });
def(DOMRectReadOnly.prototype, 'left', function () { var r = rectOf(this); return MathMin(r.x, r.x + r.width); });
DOMRectReadOnly.prototype.toJSON = function () {
  var r = rectOf(this);
  return { x: r.x, y: r.y, width: r.width, height: r.height, top: MathMin(r.y, r.y + r.height), right: MathMax(r.x, r.x + r.width),
    bottom: MathMax(r.y, r.y + r.height), left: MathMin(r.x, r.x + r.width) };
};
function rectFrom(ctor, other) {
  var o = other == null ? { __proto__: null } : other, r = ObjectCreate(ctor.prototype);
  WeakMapSet(rects, r, { __proto__: null, x: o.x === undefined ? 0 : +o.x, y: o.y === undefined ? 0 : +o.y,
    width: o.width === undefined ? 0 : +o.width, height: o.height === undefined ? 0 : +o.height });
  return r;
}
/* A new rectangle of [ctor]'s interface. */
function newRect(ctor, x, y, width, height) {
  var r = ObjectCreate(ctor.prototype);
  WeakMapSet(rects, r, { __proto__: null, x: +x, y: +y, width: +width, height: +height });
  return r;
}
DOMRectReadOnly.fromRect = function (other) { return rectFrom(DOMRectReadOnly, other); };
DOMRect.fromRect = function (other) { return rectFrom(DOMRect, other); };
defineInterface(DOMRectReadOnly, 'DOMRectReadOnly');
defineInterface(DOMRect, 'DOMRect', DOMRectReadOnly);
var DOMRectList = abstractInterface('DOMRectList');
listLength(DOMRectList.prototype);
listItemMethod(DOMRectList.prototype);
iterableList(DOMRectList.prototype, false);
defineInterface(DOMRectList, 'DOMRectList');
"""
