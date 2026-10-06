package io.github.yuroyami.kitepdf.epub.script

/**
 * The forms of [DOM_PRELUDE] (#538): the interfaces of form controls and their collections, then `SVGElement` and those of SVG,
 * `MathMLElement`, and the interface of each element by its name.
 */
internal const val DOM_PRELUDE_FORMS: String = """/* ---- forms, of HTML, 4.10 ----
   What the DOM's own algorithms read of a form control is its state and its attributes, not the
   properties of its prototype, which a script can redefine. The host keeps the state beside the
   element, where the page and the selectors read it, and an attribute never stands for it (#552). */

/* The state [key] of the control [el] as a boolean, or undefined while the control follows its attributes. */
function flagState(el, key) { var s = K.state(el.__id, key); return s == null ? undefined : s === '1'; }
function setFlagState(el, key, v) { K.setState(el.__id, key, v === undefined ? null : v ? '1' : '0'); }
/* Whether [id] is an element of HTML named [local]. */
function isHtml(id, local) { if (K.kind(id) !== 1) return false; var n = nameOf(id); return n.local === local && n.ns === XHTML_NS; }
/* The listed elements of HTML, 4.10.2, whose form owner a form's elements gather. */
var LISTED = nameSet(['button', 'fieldset', 'input', 'object', 'output', 'select', 'textarea']);
/* The form owner of [id], as an id: the form its form attribute names, or else its nearest form ancestor. */
function formOwnerId(id) {
  var named = K.attr(id, 'form');
  if (named != null) { var f = K.byId(named); return f != null && isHtml(f, 'form') ? f : null; }
  for (var t = K.parent(id); t != null && K.kind(t) === 1; t = K.parent(t)) if (isHtml(t, 'form')) return t;
  return null;
}
function formOf(el) { return wrap(formOwnerId(el.__id)); }
function rootOf(id) { var p; while ((p = K.parent(id)) != null) id = p; return id; }
/* The listed elements whose form owner is the form [formId], in tree order, but for image buttons. */
function formElementIds(formId) {
  var root = rootOf(formId), scope = queryFirst(root, '[form]') == null ? formId : root;
  return listFilter(queryAll(scope, '*'), function (e) {
    var n = nameOf(e);
    return n.ns === XHTML_NS && LISTED[n.local] === true && formOwnerId(e) === formId && !(n.local === 'input' && inputType(wrap(e)) === 'image');
  });
}
/* The elements of [ids] whose id or name is [name]. */
function idsNamed(ids, name) {
  return listFilter(ids, function (e) { return K.attr(e, 'id') === name || K.attr(e, 'name') === name; });
}
/* What a form's elements answer for [name]: one element, a RadioNodeList of several, or undefined. */
function controlsNamed(owner, ids, name, source) {
  var found = idsNamed(ids, name);
  if (!found.length) return undefined;
  if (found.length === 1) return wrap(found[0]);
  return cachedList(owner, 'radio ' + name, function () {
    return makeList(RadioNodeList.prototype, LIVE, function () { return idsNamed(source(), name); });
  });
}

/* HTMLFormControlsCollection and RadioNodeList, of HTML, 2.6.2. */
var HTMLFormControlsCollection = abstractInterface('HTMLFormControlsCollection', HTMLCollection);
HTMLFormControlsCollection.prototype.namedItem = function (name) {
  var v = listNamed(listState(this), domString(name));
  return v === undefined ? null : v;
};
defineInterface(HTMLFormControlsCollection, 'HTMLFormControlsCollection', HTMLCollection);
var RadioNodeList = abstractInterface('RadioNodeList', NodeList);
function radioIn(list) {
  return listFilter(listItems(listState(list)), function (e) { return isHtml(e, 'input') && inputType(wrap(e)) === 'radio'; });
}
def(RadioNodeList.prototype, 'value', function () {
  var radios = radioIn(this);
  for (var i = 0; i < radios.length; i++) {
    var el = wrap(radios[i]);
    if (checkedOf(el)) { var v = K.attr(radios[i], 'value'); return v == null ? 'on' : v; }
  }
  return '';
}, function (v) {
  var radios = radioIn(this), want = domString(v);
  for (var i = 0; i < radios.length; i++) {
    var value = K.attr(radios[i], 'value');
    if ((value == null ? 'on' : value) === want) { setChecked(wrap(radios[i]), true); return; }
  }
});
defineInterface(RadioNodeList, 'RadioNodeList', NodeList);
function formControlsOf(el, key, source) {
  var list = cachedList(el, key, function () {
    return makeList(HTMLFormControlsCollection.prototype, LIVE, source, wrap, function (ids, name) { return controlsNamed(list, ids, name, source); }, collectionNames);
  });
  return list;
}
function formElementsOf(form) { var id = form.__id; return formControlsOf(form, 'elements', function () { return formElementIds(id); }); }

/* A form is a legacy platform object (Web IDL, 3.9): its controls by index, and by name where it
   has no property of that name. */
function formNamed(id, key) {
  if (typeof key !== 'string' || key === '') return undefined;
  var form = wrap(id), found = controlsNamed(form, formElementIds(id), key, function () { return formElementIds(id); });
  if (found !== undefined) return found;
  var images = idsNamed(listFilter(queryAll(id, 'img'), function (e) { return nameOf(e).ns === XHTML_NS; }), key);
  return images.length ? wrap(images[0]) : undefined;
}
/* Whether the engine hands a proxy's set trap the object the assignment started from, as
   ECMAScript, 10.5.9 does: one that hands it undefined would run a setter of the form on nothing. */
var SET_TRAP_GETS_RECEIVER = (function () {
  var seen, probe = new Proxy(ObjectCreate(null), { __proto__: null, set: function (t, key, value, receiver) { seen = receiver; return true; } });
  probe.x = 1;
  return seen === probe;
})();
var FORM_HANDLER = {
  __proto__: null,
  get: function (t, key, receiver) {
    var i = arrayIndex(key);
    if (i >= 0) { var ids = formElementIds(t.__id); return i < ids.length ? wrap(ids[i]) : undefined; }
    if (!ReflectHas(t, key)) { var n = formNamed(t.__id, key); if (n !== undefined) return n; }
    return receiverGet(t, key, receiver);
  },
  set: function (t, key, value, receiver) {
    if (arrayIndex(key) >= 0) return false;
    if (!ReflectHas(t, key) && formNamed(t.__id, key) !== undefined) return false;
    return receiverSet(t, key, value, SET_TRAP_GETS_RECEIVER ? receiver : MapGet(wrappers, t.__id));
  },
  has: function (t, key) {
    var i = arrayIndex(key);
    if (i >= 0) return i < formElementIds(t.__id).length;
    return ReflectHas(t, key) || formNamed(t.__id, key) !== undefined;
  },
  getOwnPropertyDescriptor: function (t, key) {
    var i = arrayIndex(key);
    if (i >= 0) {
      var ids = formElementIds(t.__id);
      return i < ids.length ? { __proto__: null, value: wrap(ids[i]), writable: false, enumerable: true, configurable: true } : undefined;
    }
    if (!ObjectHasOwn(t, key)) {
      var n = formNamed(t.__id, key);
      if (n !== undefined) return { __proto__: null, value: n, writable: false, enumerable: false, configurable: true };
    }
    return ObjectGetOwnPropertyDescriptor(t, key);
  },
  defineProperty: function (t, key, desc) {
    if (arrayIndex(key) >= 0) return false;
    if (!ObjectHasOwn(t, key) && formNamed(t.__id, key) !== undefined) return false;
    return ReflectDefineProperty(t, key, ownDescriptor(desc));
  },
  deleteProperty: function (t, key) {
    var i = arrayIndex(key);
    if (i >= 0) return i >= formElementIds(t.__id).length;
    if (!ObjectHasOwn(t, key) && formNamed(t.__id, key) !== undefined) return false;
    return ReflectDeleteProperty(t, key);
  },
  ownKeys: function (t) {
    var ids = formElementIds(t.__id), out = [], names = collectionNames(ids);
    for (var i = 0; i < ids.length; i++) ArrayPush(out, String(i));
    for (var j = 0; j < names.length; j++) if (!ObjectHasOwn(t, names[j])) ArrayPush(out, names[j]);
    var own = ReflectOwnKeys(t);
    for (var k = 0; k < own.length; k++) ArrayPush(out, own[k]);
    return out;
  },
  preventExtensions: function () { return false; }
};

/* The members of a form-associated element: its form owner. */
function formControl(p) { def(p, 'form', function () { return formOf(wrap(idOf(this))); }); }
/* The constraint validation API, of HTML, 4.10.20.3: a reading system submits no form, so every control is valid. */
var ValidityState = abstractInterface('ValidityState');
(function (names) {
  for (var i = 0; i < names.length; i++) (function (n) { def(ValidityState.prototype, n, function () { return n === 'valid'; }); })(names[i]);
})(['valueMissing', 'typeMismatch', 'patternMismatch', 'tooLong', 'tooShort', 'rangeUnderflow', 'rangeOverflow', 'stepMismatch',
  'badInput', 'customError', 'valid']);
defineInterface(ValidityState, 'ValidityState');
var validity = ObjectCreate(ValidityState.prototype);
function validationMembers(p) {
  def(p, 'validity', function () { idOf(this); return validity; });
  def(p, 'validationMessage', function () { idOf(this); return ''; });
  def(p, 'willValidate', function () { idOf(this); return false; });
  p.checkValidity = function () { idOf(this); return true; };
  p.reportValidity = function () { idOf(this); return true; };
  p.setCustomValidity = function () { idOf(this); };
}
/* The labels of a labelable element, a live NodeList. */
function labelsOf(el) {
  var id = el.__id;
  return cachedList(el, 'labels', function () {
    return liveNodes(function () {
      return listFilter(queryAll(rootOf(id), 'label'), function (l) { var c = labelControl(wrap(l)); return c !== null && c.__id === id; });
    });
  });
}
function labelable(p) { def(p, 'labels', function () { return labelsOf(wrap(idOf(this))); }); }
var LABELABLE = nameSet(['button', 'input', 'meter', 'output', 'progress', 'select', 'textarea']);
/* The control a label labels: the element its for attribute names, or its first labelable descendant. */
function labelControl(label) {
  var f = K.attr(label.__id, 'for');
  if (f != null) {
    var t = K.byId(f);
    return t != null && LABELABLE[K.tag(t)] && nameOf(t).ns === XHTML_NS && !(K.tag(t) === 'input' && inputType(wrap(t)) === 'hidden') ? wrap(t) : null;
  }
  var all = queryAll(label.__id, '*');
  for (var i = 0; i < all.length; i++) {
    if (LABELABLE[K.tag(all[i])] && nameOf(all[i]).ns === XHTML_NS && !(K.tag(all[i]) === 'input' && inputType(wrap(all[i])) === 'hidden')) return wrap(all[i]);
  }
  return null;
}
/* The text selection of a text control, which a reader of the book does not see. */
function selectionMembers(p) {
  p.select = function () { idOf(this); };
  p.setSelectionRange = function () { idOf(this); };
  p.setRangeText = function () { idOf(this); };
  def(p, 'selectionStart', function () { idOf(this); return 0; }, function () { idOf(this); });
  def(p, 'selectionEnd', function () { idOf(this); return 0; }, function () { idOf(this); });
  def(p, 'selectionDirection', function () { idOf(this); return 'none'; }, function () { idOf(this); });
}

/* The autofill tokens of HTML, 4.10.18.7, which the autocomplete IDL attribute of a control gives back. */
var AUTOFILL_FIELDS = nameSet(splitOn('name honorific-prefix given-name additional-name family-name honorific-suffix nickname ' +
  'username new-password current-password one-time-code organization-title organization street-address address-line1 ' +
  'address-line2 address-line3 address-level4 address-level3 address-level2 address-level1 country country-name postal-code ' +
  'cc-name cc-given-name cc-additional-name cc-family-name cc-number cc-exp cc-exp-month cc-exp-year cc-csc cc-type ' +
  'transaction-currency transaction-amount language bday bday-day bday-month bday-year sex url photo', ' '));
var AUTOFILL_CONTACT_FIELDS = nameSet(splitOn('tel tel-country-code tel-national tel-area-code tel-local tel-local-prefix ' +
  'tel-local-suffix tel-extension email impp', ' '));
var AUTOFILL_CONTACT_KINDS = nameSet(['home', 'work', 'mobile', 'fax', 'pager']);
/* The IDL-exposed autofill value of a control's autocomplete attribute [v]: its tokens, lowercased, or '' when they are not valid. */
function autofillValue(v) {
  var tokens = asciiTokens(asciiLowerCase(v == null ? '' : v)), n = tokens.length;
  if (!n) return '';
  if (n === 1 && (tokens[0] === 'on' || tokens[0] === 'off')) return tokens[0];
  var at = n - 1;
  if (tokens[at] === 'webauthn') at--;
  if (at < 0) return '';
  var field = tokens[at--], contact = AUTOFILL_CONTACT_FIELDS[field] === true;
  if (!contact && AUTOFILL_FIELDS[field] !== true) return '';
  if (contact && at >= 0 && AUTOFILL_CONTACT_KINDS[tokens[at]]) at--;
  if (at >= 0 && (tokens[at] === 'shipping' || tokens[at] === 'billing')) at--;
  if (at >= 0 && StringSubstring(tokens[at], 0, 8) === 'section-') at--;
  return at >= 0 ? '' : ArrayJoin(tokens, ' ');
}
function autocompleteMember(p) {
  def(p, 'autocomplete', function () { return autofillValue(K.attr(idOf(this), 'autocomplete')); },
    function (v) { setAttr(this, 'autocomplete', domString(v)); });
}

/* The state of an input: its type, by the keywords of HTML, 4.10.5. */
function inputType(el) { return enumState(ENUMS.inputtype, K.attr(el.__id, 'type')); }
function buttonType(el) { return enumState(ENUMS.buttontype, K.attr(el.__id, 'type')); }
function disabledOf(el) { return controlDisabled(el.__id); }
function checkedOf(el) { var c = flagState(el, 'checked'); return c !== undefined ? c : K.attr(el.__id, 'checked') != null; }
/* Sets the checkedness of [el], which makes it dirty, so its checked attribute no longer sets it (HTML, 4.10.5). */
function setChecked(el, v) {
  setFlagState(el, 'checked', !!v);
  if (v && inputType(el) === 'radio') uncheckGroup(el);
}
/* The value mode of an input, of HTML, 4.10.5.4. */
function valueMode(type) {
  switch (type) {
    case 'hidden': case 'submit': case 'image': case 'reset': case 'button': return 'default';
    case 'checkbox': case 'radio': return 'default/on';
    case 'file': return 'filename';
    default: return 'value';
  }
}
/* The other radio buttons of the group of [input] that are checked: those of its form, or else
   of its tree with no form, with its name. */
function radioGroup(input) {
  var name = K.attr(input.__id, 'name'), out = [];
  if (!name) return out;
  var form = formOwnerId(input.__id);
  var all = queryAll(form == null ? rootOf(input.__id) : rootOf(form), 'input');
  for (var i = 0; i < all.length; i++) {
    var other = wrap(all[i]);
    if (other !== input && inputType(other) === 'radio' && K.attr(all[i], 'name') === name && formOwnerId(all[i]) === form && checkedOf(other)) ArrayPush(out, other);
  }
  return out;
}
function uncheckGroup(input) {
  var group = radioGroup(input);
  for (var i = 0; i < group.length; i++) setFlagState(group[i], 'checked', false);
}
function* resetSteps(form) {
  var g = dispatchSteps(form, new Event('reset', { __proto__: null, bubbles: true, cancelable: true })), r;
  while (!(r = GeneratorNext(g)).done) yield;
  if (!r.value) return;
  var all = formElementIds(form.__id);
  for (var i = 0; i < all.length; i++) {
    var el = wrap(all[i]);
    K.setState(all[i], 'value', null);
    K.setState(all[i], 'checked', null);
    if (isHtml(all[i], 'select')) { var o = optionIds(all[i]); for (var j = 0; j < o.length; j++) K.setState(o[j], 'selected', null); }
  }
}
/* A new type of [input] sanitizes the value a script set by that type (HTML, 4.10.5). */
function inputTypeChanged(input) {
  var id = idOf(input);
  if (!isHtml(id, 'input')) return;
  var v = dirtyValue(input), type = inputType(input);
  if (v !== undefined && valueMode(type) === 'value') K.setState(id, 'value', K.sanitize(id, type, v));
}
/* The value of an input, by its value mode (HTML, 4.10.5.4), and the API value of a textarea. */
function inputValue(el) {
  var type = inputType(el), mode = valueMode(type), v;
  if (mode === 'filename') return '';
  if (mode === 'value') v = dirtyValue(el);
  if (v === undefined) v = K.attr(el.__id, 'value');
  if (mode === 'value') return K.sanitize(el.__id, type, v == null ? '' : v);
  return v != null ? v : mode === 'default/on' ? 'on' : '';
}
function textareaValue(el) { var v = dirtyValue(el); return K.sanitize(el.__id, 'textarea', v !== undefined ? v : textOf(el)); }
/* The dirty value of a control, or undefined while it has none. */
function dirtyValue(el) { var v = K.state(el.__id, 'value'); return v == null ? undefined : v; }
function setDirtyValue(el, v) { K.setState(el.__id, 'value', v === null ? '' : domString(v)); }

/* ---- FormData, of XMLHttpRequest, 4.3, over the entry list of a form, of HTML, 4.10.21.4 (#531) ---- */

/* The entry list of a FormData lives in a weak map, as a holder whose list set() and delete()
   replace, so an iterator reads the list live. */
var formDatas = new WeakMap();
function formDataOf(d) {
  var h = WeakMapGet(formDatas, d);
  if (h === undefined) throw new TypeError('Illegal invocation');
  return h;
}
function newFormData(list) { var d = ObjectCreate(FormData.prototype); WeakMapSet(formDatas, d, { list: list }); return d; }
/* An entry's value: a scalar value string, or a File. A Blob that is no File becomes one named
   "blob", and a filename names a new File over the same bytes. */
function entryValue(value, filename) {
  var data = value !== null && typeof value === 'object' ? WeakMapGet(blobs, value) : undefined;
  if (data === undefined) return usv(value);
  var file = WeakMapGet(files, value);
  if (file !== undefined && filename === undefined) return value;
  var out = ObjectCreate(File.prototype);
  WeakMapSet(blobs, out, data);
  WeakMapSet(files, out, { name: filename !== undefined ? usv(filename) : file !== undefined ? file.name : 'blob', lastModified: file !== undefined ? file.lastModified : DateNow() });
  return out;
}
/* The value and filename of append() and set(): a third argument needs a Blob. */
function entryArgs(args, what) {
  needArgs(args, 2, what);
  var isBlob = args[1] !== null && typeof args[1] === 'object' && WeakMapHas(blobs, args[1]);
  if (args.length > 2 && !isBlob) throw new TypeError(what + ": parameter 2 is not of type 'Blob'.");
  return [usv(args[0]), entryValue(args[1], args.length > 2 && args[2] !== undefined ? args[2] : undefined)];
}

/* Whether the control [id] is disabled (HTML, 4.10.18.5): by its own attribute, or a disabled
   fieldset around it, unless it sits in that fieldset's first legend. */
function controlDisabled(id) {
  if (K.attr(id, 'disabled') != null) return true;
  for (var child = id, p = K.parent(id); p != null && K.kind(p) === 1; child = p, p = K.parent(p)) {
    if (isHtml(p, 'fieldset') && K.attr(p, 'disabled') != null) {
      var legend = childrenNamed(p, 'legend')[0];
      if (legend === undefined || legend !== child) return true;
    }
  }
  return false;
}
/* A submit button: a button of type submit, or an input of type submit or image. */
function isSubmitButton(id) {
  if (isHtml(id, 'button')) return buttonType(wrap(id)) === 'submit';
  return isHtml(id, 'input') && (function (t) { return t === 'submit' || t === 'image'; })(inputType(wrap(id)));
}
/* The directionality of [id] (HTML, 3.2.6.4), for a control's dirname entry. A control whose
   dir is auto takes the direction of the first strong character of its value. */
function directionOf(id) {
  for (var e = id; e != null && K.kind(e) === 1; e = K.parent(e)) {
    var dir = K.attr(e, 'dir');
    dir = dir == null ? '' : asciiLowerCase(dir);
    if (dir === 'ltr' || dir === 'rtl') return dir;
    if (dir === 'auto') return firstStrong(isHtml(e, 'input') || isHtml(e, 'textarea') ? (isHtml(e, 'input') ? inputValue(wrap(e)) : textareaValue(wrap(e))) : K.text(e));
  }
  return 'ltr';
}
function firstStrong(s) {
  for (var i = 0; i < s.length; i++) {
    var c = StringCharCodeAt(s, i);
    if ((c >= 0x590 && c <= 0x8FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) return 'rtl';
    if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || (c >= 0xC0 && c <= 0x24F && c !== 0xD7 && c !== 0xF7) || (c >= 0x370 && c <= 0x58F) || c >= 0x900) return 'ltr';
  }
  return 'ltr';
}
/* The types of input whose dirname attribute adds the direction to the entries. */
var DIRNAME_TYPES = nameSet(['hidden', 'text', 'search', 'tel', 'url', 'email', 'password', 'submit', 'reset', 'button']);

/* The forms that construct their entry list now, which a formdata listener cannot do again. */
var constructing = new WeakMap();
/* The entry list of [form] (HTML, 4.10.21.4) with [submitter], after the formdata event, or null
   while the form constructs one already. Chromium keeps the controls in a datalist, as here. */
function entryList(form, submitter) {
  var formId = form.__id;
  if (WeakMapHas(constructing, form)) return null;
  WeakMapSet(constructing, form, true);
  var list = [];
  try {
    var root = rootOf(formId), scope = queryFirst(root, '[form]') == null ? formId : root;
    var ids = listFilter(queryAll(scope, '*'), function (e) {
      var n = nameOf(e);
      return n.ns === XHTML_NS && (n.local === 'button' || n.local === 'input' || n.local === 'select' || n.local === 'textarea') && formOwnerId(e) === formId;
    });
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i], el = wrap(id), local = nameOf(id).local, type = local === 'input' ? inputType(el) : null;
      if (controlDisabled(id)) continue;
      var button = local === 'button' || type === 'submit' || type === 'image' || type === 'reset' || type === 'button';
      if (button && el !== submitter) continue;
      if ((type === 'checkbox' || type === 'radio') && !checkedOf(el)) continue;
      var name = K.attr(id, 'name');
      if (type === 'image') {
        var prefix = name ? name + '.' : '';
        ArrayPush(list, [usv(prefix + 'x'), '0'], [usv(prefix + 'y'), '0']);
        continue;
      }
      if (!name) continue;
      name = usv(name);
      if (local === 'select') {
        var options = optionIds(id), chosen;
        if (K.attr(id, 'multiple') != null) chosen = listFilter(options, function (o) { return selectedOf(wrap(o)); });
        else { var at = selectedIndexOf(el); chosen = at < 0 ? [] : [options[at]]; }
        for (var j = 0; j < chosen.length; j++) {
          var option = wrap(chosen[j]);
          if (!controlDisabled(chosen[j]) && !optionGroupDisabled(chosen[j])) ArrayPush(list, [name, usv(optionValue(option))]);
        }
      } else if (type === 'checkbox' || type === 'radio') {
        var v = K.attr(id, 'value');
        ArrayPush(list, [name, usv(v == null ? 'on' : v)]);
      } else if (type === 'file') {
        ArrayPush(list, [name, new File([], '', { __proto__: null, type: 'application/octet-stream' })]);
      } else if (type === 'hidden' && asciiLowerCase(name) === '_charset_') {
        ArrayPush(list, [name, 'UTF-8']);
      } else {
        ArrayPush(list, [name, usv(local === 'textarea' ? textareaValue(el) : inputValue(el))]);
      }
      var dirname = K.attr(id, 'dirname');
      if (dirname && (local === 'textarea' || DIRNAME_TYPES[type] === true)) ArrayPush(list, [usv(dirname), directionOf(id)]);
    }
    var data = newFormData(list);
    var event = new FormDataEvent('formdata', { __proto__: null, bubbles: true, formData: data });
    event.isTrusted = true;
    fireEvent(form, event);
    return listSlice(formDataOf(data).list);
  } finally {
    WeakMapDelete(constructing, form);
  }
}
function optionGroupDisabled(id) { var p = K.parent(id); return p != null && isHtml(p, 'optgroup') && K.attr(p, 'disabled') != null; }

function FormData(form, submitter) {
  var what = "Failed to construct 'FormData'";
  if (!isA(this, FormData) || WeakMapHas(formDatas, this)) {
    throw new TypeError(what + ": Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
  }
  var list = [];
  if (form !== undefined) {
    if (!isNode(form) || !isHtml(form.__id, 'form')) throw new TypeError(what + ": parameter 1 is not of type 'HTMLFormElement'.");
    if (submitter != null) {
      if (!isNode(submitter) || K.kind(submitter.__id) !== 1 || nameOf(submitter.__id).ns !== XHTML_NS) throw new TypeError(what + ": parameter 2 is not of type 'HTMLElement'.");
      if (!isSubmitButton(submitter.__id)) throw new TypeError(what + ': The specified element is not a submit button.');
      if (formOwnerId(submitter.__id) !== form.__id) throw new DOMException(what + ': The specified element is not owned by this form element.', 'NotFoundError');
    }
    list = entryList(form, submitter == null ? null : submitter);
    if (list === null) throw new DOMException(what + ': The form is constructing entry list.', 'InvalidStateError');
  }
  WeakMapSet(formDatas, this, { list: list });
}
interfaceProto(FormData, {}, 'FormData');
var FP = FormData.prototype;
FP.append = function () {
  var h = formDataOf(this), e = entryArgs(arguments, "Failed to execute 'append' on 'FormData'");
  ArrayPush(h.list, e);
};
FP['delete'] = function (name) {
  needArgs(arguments, 1, "Failed to execute 'delete' on 'FormData'");
  var h = formDataOf(this), n = usv(name);
  h.list = listFilter(h.list, function (e) { return e[0] !== n; });
};
FP.get = function (name) {
  needArgs(arguments, 1, "Failed to execute 'get' on 'FormData'");
  var list = formDataOf(this).list, n = usv(name);
  for (var i = 0; i < list.length; i++) if (list[i][0] === n) return list[i][1];
  return null;
};
FP.getAll = function (name) {
  needArgs(arguments, 1, "Failed to execute 'getAll' on 'FormData'");
  var list = formDataOf(this).list, n = usv(name), out = [];
  for (var i = 0; i < list.length; i++) if (list[i][0] === n) ArrayPush(out, list[i][1]);
  return out;
};
FP.has = function (name) {
  needArgs(arguments, 1, "Failed to execute 'has' on 'FormData'");
  var list = formDataOf(this).list, n = usv(name);
  for (var i = 0; i < list.length; i++) if (list[i][0] === n) return true;
  return false;
};
FP.set = function () {
  var h = formDataOf(this), e = entryArgs(arguments, "Failed to execute 'set' on 'FormData'"), found = false;
  h.list = listFilter(h.list, function (x) {
    if (x[0] !== e[0]) return true;
    if (found) return false;
    found = true;
    x[1] = e[1];
    return true;
  });
  if (!found) ArrayPush(h.list, e);
};
FP.forEach = function (callback, thisArg) {
  needArgs(arguments, 1, "Failed to execute 'forEach' on 'FormData'");
  if (typeof callback !== 'function') throw new TypeError("Failed to execute 'forEach' on 'FormData': The callback provided as parameter 1 is not a function.");
  var h = formDataOf(this);
  for (var i = 0; i < h.list.length; i++) ReflectApply(callback, thisArg, [h.list[i][1], h.list[i][0], this]);
};
function FormDataIterator(h, kind) { hidden(this, '__p', h); hidden(this, '__kind', kind); hidden(this, '__at', 0); }
FormDataIterator.prototype = ObjectCreate(IteratorPrototype);
hidden(FormDataIterator.prototype, 'next', function () {
  var list = this.__p.list;
  if (this.__at >= list.length) return { value: undefined, done: true };
  var e = list[this.__at++];
  return { value: this.__kind === 'keys' ? e[0] : this.__kind === 'values' ? e[1] : [e[0], e[1]], done: false };
});
if (SymbolToStringTag) ObjectDefineProperty(FormDataIterator.prototype, SymbolToStringTag, { __proto__: null, value: 'FormData Iterator', configurable: true });
FP.entries = function () { return new FormDataIterator(formDataOf(this), 'entries'); };
FP.keys = function () { return new FormDataIterator(formDataOf(this), 'keys'); };
FP.values = function () { return new FormDataIterator(formDataOf(this), 'values'); };
hidden(FP, SymbolIterator, FP.entries);
defineInterface(FormData, 'FormData', null, 0);

/* FormDataEvent, whose formData a formdata listener adds entries to, and SubmitEvent, whose
   submitter is the button that submits the form. */
var FormDataEvent = subEvent(Event, 'FormDataEvent', function (init) {
  var d = init.formData, what = "Failed to construct 'FormDataEvent': Failed to read the 'formData' property from 'FormDataEventInit': ";
  if (d === undefined) throw new TypeError(what + 'Required member is undefined.');
  if (d === null || typeof d !== 'object' || !WeakMapHas(formDatas, d)) throw new TypeError(what + "Failed to convert value to 'FormData'.");
  this.formData = d;
}, 2);
var SubmitEvent = subEvent(Event, 'SubmitEvent', function (init) {
  var s = init.submitter;
  if (s != null && !(isNode(s) && K.kind(s.__id) === 1)) throw new TypeError("Failed to construct 'SubmitEvent': Failed to read the 'submitter' property from 'SubmitEventInit': Failed to convert value to 'HTMLElement'.");
  this.submitter = s == null ? null : s;
});
/* The steps of submitting [form] from [submitter]: the submit event, then, unless a listener
   cancelled it, the entry list with its formdata event. A book navigates nowhere after that. */
function* submitSteps(form, submitter) {
  if (WeakMapHas(constructing, form)) return;
  var event = new SubmitEvent('submit', { __proto__: null, bubbles: true, cancelable: true, submitter: submitter }), r;
  event.isTrusted = true;
  var g = dispatchSteps(form, event);
  while (!(r = GeneratorNext(g)).done) yield;
  if (r.value) entryList(form, submitter);
}

var HTMLFormElement = elementInterface('HTMLFormElement', HTMLElement, 'acceptCharset=accept-charset action:a autocomplete:eautocomplete ' +
  'enctype:eenctype encoding=enctype:eenctype method:emethod name noValidate=novalidate:b target rel relList=rel:trel', function (p) {
  def(p, 'elements', function () { return formElementsOf(wrap(idOf(this))); });
  def(p, 'length', function () { return formElementIds(idOf(this)).length; });
  p.submit = function () { entryList(wrap(idOf(this)), null); };
  p.requestSubmit = function (submitter) {
    var f = wrap(idOf(this)), what = "Failed to execute 'requestSubmit' on 'HTMLFormElement'";
    if (submitter != null) {
      if (!isNode(submitter) || !isSubmitButton(submitter.__id)) throw new TypeError(what + ': The specified element is not a submit button.');
      if (formOwnerId(submitter.__id) !== f.__id) throw new DOMException(what + ': The specified element is not owned by this form element.', 'NotFoundError');
    }
    drain(submitSteps(f, submitter == null ? null : submitter));
  };
  p.reset = function () { drain(resetSteps(wrap(idOf(this)))); };
  p.checkValidity = function () { idOf(this); return true; };
  p.reportValidity = function () { idOf(this); return true; };
});
var HTMLLabelElement = elementInterface('HTMLLabelElement', HTMLElement, 'htmlFor=for', function (p) {
  def(p, 'control', function () { return labelControl(wrap(idOf(this))); });
  def(p, 'form', function () { var c = labelControl(wrap(idOf(this))); return c === null ? null : formOf(c); });
});
var HTMLInputElement = elementInterface('HTMLInputElement', HTMLElement, 'accept alt defaultChecked=checked:b dirName=dirname disabled:b ' +
  'formAction=formaction:a formEnctype=formenctype:eformenctype formMethod=formmethod:eformmethod formNoValidate=formnovalidate:b ' +
  'formTarget=formtarget max maxLength=maxlength:L min minLength=minlength:L multiple:b name pattern placeholder readOnly=readonly:b ' +
  'required:b size:P20 src:u step type:einputtype defaultValue=value align useMap=usemap', function (p) {
  formControl(p); labelable(p); validationMembers(p); selectionMembers(p); autocompleteMember(p);
  sizeAttribute(p, 'width', 2);
  sizeAttribute(p, 'height', 3);
  def(p, 'value', function () { return inputValue(wrap(idOf(this))); }, function (v) {
    var el = wrap(idOf(this)), type = inputType(el), mode = valueMode(type);
    if (mode === 'value') setDirtyValue(el, K.sanitize(el.__id, type, v === null ? '' : domString(v)));
    else if (mode === 'filename') {
      if (domString(v) !== '') throw new DOMException("Failed to set the 'value' property on 'HTMLInputElement': This input element accepts a filename, which may only be programmatically set to the empty string.", 'InvalidStateError');
    } else setAttr(el, 'value', v === null ? '' : domString(v));
  });
  def(p, 'checked', function () { return checkedOf(wrap(idOf(this))); }, function (v) { setChecked(wrap(idOf(this)), v); });
  def(p, 'indeterminate', function () { return K.state(idOf(this), 'indeterminate') === '1'; },
    function (v) { K.setState(idOf(this), 'indeterminate', v ? '1' : null); });
  def(p, 'files', function () { idOf(this); return null; });
  def(p, 'list', function () {
    var v = K.attr(idOf(this), 'list'), t = v == null ? null : K.byId(v);
    return t != null && isHtml(t, 'datalist') ? wrap(t) : null;
  });
  def(p, 'valueAsNumber', function () { var n = parseHtmlFloat(domString(this.value)); return n === null ? NaN : n; }, function () { idOf(this); });
  def(p, 'valueAsDate', function () { idOf(this); return null; }, function () { idOf(this); });
  p.stepUp = function () { idOf(this); };
  p.stepDown = function () { idOf(this); };
  p.showPicker = function () { idOf(this); };
});
var HTMLButtonElement = elementInterface('HTMLButtonElement', HTMLElement, 'disabled:b formAction=formaction:a formEnctype=formenctype:eformenctype ' +
  'formMethod=formmethod:eformmethod formNoValidate=formnovalidate:b formTarget=formtarget name type:ebuttontype value', function (p) {
  formControl(p); labelable(p); validationMembers(p);
});
/* The options of a select, of HTML, 4.10.7: its option children and those of its optgroup children. */
function optionIds(selectId) {
  var out = [], c = elementIds(selectId);
  for (var i = 0; i < c.length; i++) {
    var n = nameOf(c[i]);
    if (n.ns !== XHTML_NS) continue;
    if (n.local === 'option') ArrayPush(out, c[i]);
    else if (n.local === 'optgroup') { var g = childrenNamed(c[i], 'option'); for (var j = 0; j < g.length; j++) ArrayPush(out, g[j]); }
  }
  return out;
}
/* The select an option belongs to, as an id, or null. */
function optionSelect(id) {
  var p = K.parent(id);
  if (p != null && isHtml(p, 'optgroup')) p = K.parent(p);
  return p != null && isHtml(p, 'select') ? p : null;
}
function selectedOf(option) { var s = flagState(option, 'selected'); return s !== undefined ? s : K.attr(option.__id, 'selected') != null; }
function setSelected(option, v) {
  setFlagState(option, 'selected', !!v);
  var select = optionSelect(option.__id);
  if (!v || select == null || K.attr(select, 'multiple') != null) return;
  var all = optionIds(select);
  for (var i = 0; i < all.length; i++) if (all[i] !== option.__id) K.setState(all[i], 'selected', '0');
}
function optionText(id) { return ArrayJoin(asciiTokens(K.text(id)), ' '); }
function optionValue(option) { var v = K.attr(option.__id, 'value'); return v == null ? optionText(option.__id) : v; }
function selectedIndexOf(select) {
  var o = optionIds(select.__id);
  for (var i = 0; i < o.length; i++) if (selectedOf(wrap(o[i]))) return i;
  return o.length && K.attr(select.__id, 'multiple') == null ? 0 : -1;
}
function setSelectedIndex(select, index) {
  var o = optionIds(select.__id), at = webIdlLong(index);
  for (var i = 0; i < o.length; i++) K.setState(o[i], 'selected', i === at ? '1' : '0');
}
/* An option or an optgroup that add() of a select takes, or a TypeError. */
function optionOrGroup(v, what) {
  if (!isNode(v) || !(isHtml(v.__id, 'option') || isHtml(v.__id, 'optgroup'))) {
    throw new TypeError(what + ": The provided value is not of type '(HTMLOptGroupElement or HTMLOptionElement)'.");
  }
  return v;
}
/* Whether [ancestor] is [id] or one of its ancestors. */
function isInclusiveAncestor(ancestor, id) {
  for (var t = id; t != null; t = K.parent(t)) if (t === ancestor) return true;
  return false;
}
/* add() of a select and of its options, of HTML, 2.6.2.3. */
function addOption(selectId, element, before, what) {
  optionOrGroup(element, what);
  if (isInclusiveAncestor(element.__id, selectId)) throw new DOMException(what + ': The new child element contains the parent.', 'HierarchyRequestError');
  var ref = null;
  if (typeof before === 'number' || (before != null && !isNode(before))) {
    var o = optionIds(selectId), at = webIdlLong(before);
    ref = at >= 0 && at < o.length ? wrap(o[at]) : null;
  } else if (before != null) {
    if (!isInclusiveAncestor(selectId, before.__id)) {
      throw new DOMException(what + ': The node before which the new node is to be inserted is not a descendant of this node.', 'NotFoundError');
    }
    ref = before;
  }
  if (ref === element) return;
  insertNode(ref === null ? selectId : K.parent(ref.__id), element, ref, what);
}
function removeOptionAt(selectId, index) {
  var o = optionIds(selectId), at = webIdlLong(index);
  if (at >= 0 && at < o.length) removeNode(K.parent(o[at]), wrap(o[at]), 'remove');
}
/* HTMLOptionsCollection, of HTML, 2.6.2.3. */
var HTMLOptionsCollection = abstractInterface('HTMLOptionsCollection', HTMLCollection);
function optionsOwner(list) { var s = listState(list); if (s.owner === null) throw new TypeError('Illegal invocation'); return s.owner; }
def(HTMLOptionsCollection.prototype, 'length', function () { return listItems(listState(this)).length; }, function (v) {
  var select = optionsOwner(this), o = optionIds(select.__id), n = unsignedLong(v);
  if (n > 100000) return;
  for (var i = o.length; i < n; i++) insertNode(select.__id, wrap(htmlElementId('option')), null, 'length');
  for (var j = o.length - 1; j >= n; j--) removeNode(K.parent(o[j]), wrap(o[j]), 'length');
});
HTMLOptionsCollection.prototype.add = function (element, before) {
  addOption(optionsOwner(this).__id, element, before, "Failed to execute 'add' on 'HTMLOptionsCollection'");
};
HTMLOptionsCollection.prototype.remove = function (index) { removeOptionAt(optionsOwner(this).__id, index); };
def(HTMLOptionsCollection.prototype, 'selectedIndex', function () { return selectedIndexOf(optionsOwner(this)); },
  function (v) { setSelectedIndex(optionsOwner(this), v); });
defineInterface(HTMLOptionsCollection, 'HTMLOptionsCollection', HTMLCollection);
/* The indexed setter of a select's options: null takes the option out, and an option past the end comes after new blank ones. */
function setOptionAt(select, index, option) {
  var id = select.__id, o = optionIds(id);
  if (option === null) { removeOptionAt(id, index); return; }
  if (!isNode(option) || !isHtml(option.__id, 'option')) throw new TypeError("Failed to set an indexed property on 'HTMLOptionsCollection': The provided value is not of type 'HTMLOptionElement'.");
  for (var i = o.length; i < index; i++) insertNode(id, wrap(htmlElementId('option')), null, 'set');
  if (index >= o.length) insertNode(id, option, null, 'set');
  else { var old = wrap(o[index]); insertNode(K.parent(o[index]), option, old, 'set'); if (old !== option) removeNode(K.parent(old.__id), old, 'set'); }
}
function optionsOf(select) {
  return cachedList(select, 'options', function () {
    var id = select.__id;
    var list = makeList(HTMLOptionsCollection.prototype, LIVE, function () { return optionIds(id); }, wrap, namedElement, collectionNames);
    var s = WeakMapGet(lists, list);
    s.owner = select;
    s.setIndex = function (i, v) { setOptionAt(select, i, v); };
    return list;
  });
}
var HTMLSelectElement = elementInterface('HTMLSelectElement', HTMLElement, 'disabled:b multiple:b name required:b size:U0', function (p) {
  formControl(p); labelable(p); validationMembers(p); autocompleteMember(p);
  def(p, 'type', function () { return K.attr(idOf(this), 'multiple') == null ? 'select-one' : 'select-multiple'; });
  def(p, 'options', function () { return optionsOf(wrap(idOf(this))); });
  def(p, 'length', function () { return optionIds(idOf(this)).length; }, function (v) { optionsOf(wrap(idOf(this))).length = v; });
  def(p, 'selectedOptions', function () {
    var el = wrap(idOf(this));
    return elementsOf(el, 'selectedOptions', function () { return listFilter(optionIds(el.__id), function (o) { return selectedOf(wrap(o)); }); });
  });
  def(p, 'selectedIndex', function () { return selectedIndexOf(wrap(idOf(this))); }, function (v) { setSelectedIndex(wrap(idOf(this)), v); });
  def(p, 'value', function () {
    var el = wrap(idOf(this)), i = selectedIndexOf(el);
    return i < 0 ? '' : optionValue(wrap(optionIds(el.__id)[i]));
  }, function (v) {
    var o = optionIds(idOf(this)), s = domString(v), found = false;
    for (var i = 0; i < o.length; i++) { var opt = wrap(o[i]), hit = !found && optionValue(opt) === s; K.setState(o[i], 'selected', hit ? '1' : '0'); if (hit) found = true; }
  });
  p.item = function (index) { var o = optionIds(idOf(this)), at = unsignedLong(index); return at < o.length ? wrap(o[at]) : null; };
  p.namedItem = function (name) { var v = listNamed(listState(optionsOf(wrap(idOf(this)))), domString(name)); return v === undefined ? null : v; };
  p.add = function (element, before) { addOption(idOf(this), element, before, "Failed to execute 'add' on 'HTMLSelectElement'"); };
  p.remove = function (index) {
    var id = idOf(this);
    if (arguments.length) { removeOptionAt(id, index); return; }
    var parent = K.parent(id);
    if (parent != null) K.remove(parent, id);
  };
  p.showPicker = function () { idOf(this); };
});
var HTMLDataListElement = elementInterface('HTMLDataListElement', HTMLElement, '', function (p) {
  def(p, 'options', function () { return liveDescendants(wrap(idOf(this)), 'options', 'option'); });
});
var HTMLOptGroupElement = elementInterface('HTMLOptGroupElement', HTMLElement, 'disabled:b label');
var HTMLOptionElement = elementInterface('HTMLOptionElement', HTMLElement, 'disabled:b defaultSelected=selected:b', function (p) {
  def(p, 'form', function () { var s = optionSelect(idOf(this)); return s == null ? null : formOf(wrap(s)); });
  def(p, 'label', function () { var id = idOf(this), v = K.attr(id, 'label'); return v == null ? optionText(id) : v; },
    function (v) { setAttr(this, 'label', domString(v)); });
  def(p, 'value', function () { return optionValue(wrap(idOf(this))); }, function (v) { setAttr(this, 'value', domString(v)); });
  def(p, 'text', function () { return optionText(idOf(this)); }, function (v) { setTextOf(this, v); });
  def(p, 'selected', function () { return selectedOf(wrap(idOf(this))); }, function (v) { setSelected(wrap(idOf(this)), v); });
  def(p, 'index', function () { var id = idOf(this), s = optionSelect(id); return s == null ? 0 : ArrayIndexOf(optionIds(s), id); });
});
var HTMLTextAreaElement = elementInterface('HTMLTextAreaElement', HTMLElement, 'cols:F20 dirName=dirname disabled:b maxLength=maxlength:L ' +
  'minLength=minlength:L name placeholder readOnly=readonly:b required:b rows:F2 wrap', function (p) {
  formControl(p); labelable(p); validationMembers(p); selectionMembers(p); autocompleteMember(p);
  def(p, 'type', function () { idOf(this); return 'textarea'; });
  def(p, 'value', function () { return textareaValue(wrap(idOf(this))); },
    function (v) { setDirtyValue(wrap(idOf(this)), v); });
  def(p, 'defaultValue', function () { return textOf(this); }, function (v) { setTextOf(this, v); });
  def(p, 'textLength', function () { return domString(this.value).length; });
});
var HTMLOutputElement = elementInterface('HTMLOutputElement', HTMLElement, 'htmlFor=for:t name', function (p) {
  formControl(p); labelable(p); validationMembers(p);
  def(p, 'type', function () { idOf(this); return 'output'; });
  def(p, 'value', function () { return textOf(this); }, function (v) { setTextOf(this, v); });
  def(p, 'defaultValue', function () { return textOf(this); }, function (v) { setTextOf(this, v); });
});
var HTMLFieldSetElement = elementInterface('HTMLFieldSetElement', HTMLElement, 'disabled:b name', function (p) {
  formControl(p); validationMembers(p);
  def(p, 'type', function () { idOf(this); return 'fieldset'; });
  def(p, 'elements', function () {
    var id = idOf(this);
    return elementsOf(wrap(id), 'elements', function () {
      return listFilter(queryAll(id, '*'), function (e) { var n = nameOf(e); return n.ns === XHTML_NS && LISTED[n.local] === true; });
    });
  });
});
var HTMLLegendElement = elementInterface('HTMLLegendElement', HTMLElement, 'align', function (p) {
  def(p, 'form', function () { var f = K.parent(idOf(this)); return f != null && isHtml(f, 'fieldset') ? formOf(wrap(f)) : null; });
});
/* The value of a progress element, of HTML, 4.10.13: none while it is indeterminate. */
var HTMLProgressElement = elementInterface('HTMLProgressElement', HTMLElement, 'max:D1', function (p) {
  labelable(p);
  def(p, 'value', function () {
    var v = K.attr(idOf(this), 'value'), n = v == null ? null : parseHtmlFloat(v);
    if (n === null || n < 0) return 0;
    var max = this.max;
    return n > max ? max : n;
  }, function (v) { setAttr(this, 'value', String(webIdlDouble(v, "Failed to set the 'value' property on 'HTMLProgressElement'"))); });
  def(p, 'position', function () { var id = idOf(this); return K.attr(id, 'value') == null ? -1 : this.value / this.max; });
});
/* The numbers of a meter element, of HTML, 4.10.14, each in the range the others give it. */
function meterNumber(id, attr, fallback) { var v = K.attr(id, attr), n = v == null ? null : parseHtmlFloat(v); return n === null ? fallback : n; }
function meterValues(id) {
  var min = meterNumber(id, 'min', 0), max = meterNumber(id, 'max', 1);
  if (max < min) max = min;
  var value = MathMin(MathMax(meterNumber(id, 'value', 0), min), max);
  var low = MathMin(MathMax(meterNumber(id, 'low', min), min), max);
  var high = MathMin(MathMax(meterNumber(id, 'high', max), low), max);
  var optimum = MathMin(MathMax(meterNumber(id, 'optimum', (min + max) / 2), min), max);
  return { __proto__: null, min: min, max: max, value: value, low: low, high: high, optimum: optimum };
}
var HTMLMeterElement = elementInterface('HTMLMeterElement', HTMLElement, '', function (p) {
  labelable(p);
  var names = ['value', 'min', 'max', 'low', 'high', 'optimum'];
  for (var i = 0; i < names.length; i++) (function (n) {
    def(p, n, function () { return meterValues(idOf(this))[n]; },
      function (v) { setAttr(this, n, String(webIdlDouble(v, "Failed to set the '" + n + "' property on 'HTMLMeterElement'"))); });
  })(names[i]);
});

/* The constructors of HTML, 4.8.4.1, 4.10.10 and 4.8.9, which make an element and share the prototype of its interface. */
function legacyFactory(F, name, length, I) {
  ObjectDefineProperty(F, 'name', { __proto__: null, value: name, configurable: true });
  ObjectDefineProperty(F, 'length', { __proto__: null, value: length, configurable: true });
  ObjectDefineProperty(F, 'prototype', { __proto__: null, value: I.prototype, writable: false, enumerable: false, configurable: false });
  ArrayPush(INTERFACES, { __proto__: null, name: name, ctor: F });
}
function Image(width, height) {
  needNew(this, Image, 'Image', isNode);
  var img = wrap(htmlElementId('img'));
  if (width !== undefined) setAttr(img, 'width', String(unsignedLong(width)));
  if (height !== undefined) setAttr(img, 'height', String(unsignedLong(height)));
  return img;
}
legacyFactory(Image, 'Image', 0, HTMLImageElement);
function Option(text, value, defaultSelected, selected) {
  needNew(this, Option, 'Option', isNode);
  var option = wrap(htmlElementId('option'));
  if (text !== undefined && domString(text) !== '') insertNode(option.__id, textNode(domString(text)), null, 'Option');
  if (value !== undefined) setAttr(option, 'value', domString(value));
  if (defaultSelected) setAttr(option, 'selected', '');
  setFlagState(option, 'selected', !!selected);
  return option;
}
legacyFactory(Option, 'Option', 0, HTMLOptionElement);
function Audio(src) {
  needNew(this, Audio, 'Audio', isNode);
  var audio = wrap(htmlElementId('audio'));
  setAttr(audio, 'preload', 'auto');
  if (src !== undefined) setAttr(audio, 'src', domString(src));
  return audio;
}
legacyFactory(Audio, 'Audio', 0, HTMLAudioElement);

/* ---- SVG and MathML ---- */

/* SVGAnimatedString, of SVG 2, 4.5.16: an attribute of an SVG element, whose animated value is its value here. */
var animatedStrings = new WeakMap();
var SVGAnimatedString = abstractInterface('SVGAnimatedString');
function animatedOf(a) { var s = a !== null && typeof a === 'object' ? WeakMapGet(animatedStrings, a) : undefined; if (s === undefined) throw new TypeError('Illegal invocation'); return s; }
def(SVGAnimatedString.prototype, 'baseVal', function () { var s = animatedOf(this), v = K.attr(s.el.__id, s.attr); return v == null ? '' : v; },
  function (v) { var s = animatedOf(this); setAttr(s.el, s.attr, domString(v)); });
def(SVGAnimatedString.prototype, 'animVal', function () { var s = animatedOf(this), v = K.attr(s.el.__id, s.attr); return v == null ? '' : v; });
defineInterface(SVGAnimatedString, 'SVGAnimatedString');
function animatedString(el, attr) {
  return cachedList(el, 'animated ' + attr, function () {
    var a = ObjectCreate(SVGAnimatedString.prototype);
    WeakMapSet(animatedStrings, a, { __proto__: null, el: el, attr: attr });
    return a;
  });
}
function animatedHref(p) { def(p, 'href', function () { return animatedString(wrap(idOf(this)), 'href'); }); }
/* The nearest svg element above [id], as an id, or null. */
function svgAncestor(id) {
  for (var t = K.parent(id); t != null && K.kind(t) === 1; t = K.parent(t)) { var n = nameOf(t); if (n.ns === SVG_NS && n.local === 'svg') return t; }
  return null;
}
var SVGElement = elementInterface('SVGElement', Element, '', function (p) {
  sharedElementMembers(p);
  def(p, 'className', function () { return animatedString(wrap(idOf(this)), 'class'); });
  def(p, 'ownerSVGElement', function () { return wrap(svgAncestor(idOf(this))); });
  def(p, 'viewportElement', function () { return wrap(svgAncestor(idOf(this))); });
});
var SVGGraphicsElement = elementInterface('SVGGraphicsElement', SVGElement, '', function (p) {
  p.getBBox = function () { var r = boxOf(this); return newRect(DOMRect, r[0], r[1], r[2], r[3]); };
  p.getCTM = function () { idOf(this); return null; };
  p.getScreenCTM = function () { idOf(this); return null; };
});
var SVGGeometryElement = elementInterface('SVGGeometryElement', SVGGraphicsElement, '', function (p) {
  p.getTotalLength = function () { idOf(this); return 0; };
  p.isPointInFill = function () { idOf(this); return false; };
  p.isPointInStroke = function () { idOf(this); return false; };
});
var SVGTextContentElement = elementInterface('SVGTextContentElement', SVGGraphicsElement, '', function (p) {
  p.getNumberOfChars = function () { return K.text(idOf(this)).length; };
  p.getComputedTextLength = function () { return boxOf(this)[2]; };
});
constants(SVGTextContentElement, ['LENGTHADJUST_UNKNOWN', 'LENGTHADJUST_SPACING', 'LENGTHADJUST_SPACINGANDGLYPHS'], 0);
var SVG_PARENTS = {
  __proto__: null, E: SVGElement, G: SVGGraphicsElement, M: SVGGeometryElement, C: SVGTextContentElement,
  N: elementInterface('SVGAnimationElement', SVGElement, '', function (p) {
    p.beginElement = function () { idOf(this); };
    p.endElement = function () { idOf(this); };
    p.getStartTime = function () { idOf(this); return 0; };
  }),
  R: elementInterface('SVGGradientElement', SVGElement, '', animatedHref),
  P: elementInterface('SVGTextPositioningElement', SVGTextContentElement),
  F: elementInterface('SVGComponentTransferFunctionElement', SVGElement)
};
/* The interface of each SVG element by its local name: the part of the interface's name after
   SVG, and the letter of its parent above. */
var SVG_TYPES = ObjectCreate(null);
(function (spec) {
  for (var i = 0; i < spec.length; i++) {
    var parts = splitOn(spec[i], ':'), name = 'SVG' + parts[1] + 'Element';
    SVG_TYPES[parts[0]] = elementInterface(name, SVG_PARENTS[parts[2]], '', parts[0] === 'a' || parts[0] === 'image' ||
      parts[0] === 'use' || parts[0] === 'script' || parts[0] === 'pattern' || parts[0] === 'filter' || parts[0] === 'feImage' ||
      parts[0] === 'textPath' || parts[0] === 'mpath' ? animatedHref : null);
  }
})(asciiTokens('a:A:G animate:Animate:N animateMotion:AnimateMotion:N animateTransform:AnimateTransform:N set:Set:N circle:Circle:M ' +
  'ellipse:Ellipse:M line:Line:M path:Path:M polygon:Polygon:M polyline:Polyline:M rect:Rect:M clipPath:ClipPath:E defs:Defs:G ' +
  'desc:Desc:E filter:Filter:E foreignObject:ForeignObject:G g:G:G image:Image:G linearGradient:LinearGradient:R ' +
  'radialGradient:RadialGradient:R marker:Marker:E mask:Mask:E metadata:Metadata:E mpath:MPath:E pattern:Pattern:E script:Script:E ' +
  'stop:Stop:E style:Style:E svg:SVG:G switch:Switch:G symbol:Symbol:E text:Text:P textPath:TextPath:C title:Title:E tspan:TSpan:P ' +
  'use:Use:G view:View:E feBlend:FEBlend:E feColorMatrix:FEColorMatrix:E feComponentTransfer:FEComponentTransfer:E ' +
  'feComposite:FEComposite:E feConvolveMatrix:FEConvolveMatrix:E feDiffuseLighting:FEDiffuseLighting:E ' +
  'feDisplacementMap:FEDisplacementMap:E feDistantLight:FEDistantLight:E feDropShadow:FEDropShadow:E feFlood:FEFlood:E ' +
  'feFuncA:FEFuncA:F feFuncB:FEFuncB:F feFuncG:FEFuncG:F feFuncR:FEFuncR:F feGaussianBlur:FEGaussianBlur:E feImage:FEImage:E ' +
  'feMerge:FEMerge:E feMergeNode:FEMergeNode:E feMorphology:FEMorphology:E feOffset:FEOffset:E fePointLight:FEPointLight:E ' +
  'feSpecularLighting:FESpecularLighting:E feSpotLight:FESpotLight:E feTile:FETile:E feTurbulence:FETurbulence:E'));
var MathMLElement = elementInterface('MathMLElement', Element, '', sharedElementMembers);

/* The interface of each element of HTML by its local name; one HTML does not name is an
   HTMLUnknownElement, but for a valid custom element name, which is an HTMLElement. */
var HTML_TYPES = ObjectCreate(null);
function htmlTypes(names, I) { var all = asciiTokens(names); for (var i = 0; i < all.length; i++) HTML_TYPES[all[i]] = I; }
htmlTypes('abbr acronym address article aside b basefont bdi bdo big center cite code dd dfn dt em figcaption figure footer header ' +
  'hgroup i kbd main mark nav nobr noembed noframes noscript plaintext rb rp rt rtc ruby s samp search section small strike strong ' +
  'sub summary sup tt u var wbr', HTMLElement);
htmlTypes('h1 h2 h3 h4 h5 h6', HTMLHeadingElement);
htmlTypes('q blockquote', HTMLQuoteElement);
htmlTypes('ins del', HTMLModElement);
htmlTypes('pre listing xmp', HTMLPreElement);
htmlTypes('col colgroup', HTMLTableColElement);
htmlTypes('thead tbody tfoot', HTMLTableSectionElement);
htmlTypes('td th', HTMLTableCellElement);
(function (pairs) {
  for (var i = 0; i < pairs.length; i += 2) HTML_TYPES[pairs[i]] = pairs[i + 1];
})(['html', HTMLHtmlElement, 'head', HTMLHeadElement, 'title', HTMLTitleElement, 'base', HTMLBaseElement, 'link', HTMLLinkElement,
  'meta', HTMLMetaElement, 'style', HTMLStyleElement, 'body', HTMLBodyElement, 'p', HTMLParagraphElement, 'hr', HTMLHRElement,
  'ol', HTMLOListElement, 'ul', HTMLUListElement, 'menu', HTMLMenuElement, 'li', HTMLLIElement, 'dl', HTMLDListElement,
  'div', HTMLDivElement, 'a', HTMLAnchorElement, 'data', HTMLDataElement, 'time', HTMLTimeElement, 'span', HTMLSpanElement,
  'br', HTMLBRElement, 'picture', HTMLPictureElement, 'source', HTMLSourceElement, 'img', HTMLImageElement,
  'iframe', HTMLIFrameElement, 'embed', HTMLEmbedElement, 'object', HTMLObjectElement, 'param', HTMLParamElement,
  'video', HTMLVideoElement, 'audio', HTMLAudioElement, 'track', HTMLTrackElement, 'map', HTMLMapElement, 'area', HTMLAreaElement,
  'canvas', HTMLCanvasElement, 'script', HTMLScriptElement, 'template', HTMLTemplateElement, 'slot', HTMLSlotElement,
  'details', HTMLDetailsElement, 'dialog', HTMLDialogElement, 'marquee', HTMLMarqueeElement, 'frameset', HTMLFrameSetElement,
  'frame', HTMLFrameElement, 'dir', HTMLDirectoryElement, 'font', HTMLFontElement, 'table', HTMLTableElement,
  'caption', HTMLTableCaptionElement, 'tr', HTMLTableRowElement, 'form', HTMLFormElement, 'label', HTMLLabelElement,
  'input', HTMLInputElement, 'button', HTMLButtonElement, 'select', HTMLSelectElement, 'datalist', HTMLDataListElement,
  'optgroup', HTMLOptGroupElement, 'option', HTMLOptionElement, 'textarea', HTMLTextAreaElement, 'output', HTMLOutputElement,
  'fieldset', HTMLFieldSetElement, 'legend', HTMLLegendElement, 'progress', HTMLProgressElement, 'meter', HTMLMeterElement]);
/* The interface object of the element [id]. */
function interfaceFor(id) {
  var n = nameOf(id);
  if (n.ns === XHTML_NS) return HTML_TYPES[n.local] || (isCustomName(n.local) ? HTMLElement : HTMLUnknownElement);
  if (n.ns === SVG_NS) return SVG_TYPES[n.local] || SVGElement;
  return n.ns === MATHML_NS ? MathMLElement : Element;
}
function protoFor(id) { return interfaceFor(id).prototype; }
"""
