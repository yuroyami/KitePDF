package io.github.yuroyami.kitepdf.javascript

/**
 * The objects a PDF's scripts expect, written in JavaScript and evaluated once per runner.
 *
 * A PDF script is written against Acrobat's JavaScript API, and in practice against the part of
 * it that Chrome's PDF engine implements. That engine puts the document's own members on the
 * global object, so a file may say `getField("total")` or `this.getField("total")` and both have
 * to work. This source builds that surface on top of a small set of Kotlin functions bound under
 * `__kite`, so the engine itself stays a plain JavaScript engine with no knowledge of PDF.
 *
 * Keeping the object model here rather than in Kotlin has one more reason: the compatibility
 * rules of these objects are quirky, they are easier to read as the scripts see them, and the
 * same source runs on any engine that can bind a handful of functions.
 *
 * Plain ES5 on purpose: a document script may run on an engine without newer syntax.
 */
internal object AcrobatApi {

    /** The core objects: the document, fields, the event, `app`, `util`, `color` and `console`. */
    val SOURCE: String = """
(function () {
  'use strict';

  var host = __kite;

  /* ─── util ────────────────────────────────────────────────────────────── */

  function pad(n, width) {
    var s = String(n);
    while (s.length < width) s = '0' + s;
    return s;
  }

  var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
    'August', 'September', 'October', 'November', 'December'];
  var DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

  /** The date formats printd takes as a number, from the Acrobat reference. */
  var NUMBERED_DATE_FORMATS = ['D:yyyymmddHHMMss', 'yyyy.mm.dd HH:MM:ss', 'm/d/yy h:MM:ss tt'];

  function formatDate(format, date) {
    var out = '';
    var i = 0;
    while (i < format.length) {
      var ch = format.charAt(i);
      var run = ch;
      while (i + run.length < format.length && format.charAt(i + run.length) === ch) run += ch;
      var handled = true;
      switch (run) {
        case 'yyyy': out += pad(date.getFullYear(), 4); break;
        case 'yy': out += pad(date.getFullYear() % 100, 2); break;
        case 'mmmm': out += MONTHS[date.getMonth()]; break;
        case 'mmm': out += MONTHS[date.getMonth()].substring(0, 3); break;
        case 'mm': out += pad(date.getMonth() + 1, 2); break;
        case 'm': out += String(date.getMonth() + 1); break;
        case 'dddd': out += DAYS[date.getDay()]; break;
        case 'ddd': out += DAYS[date.getDay()].substring(0, 3); break;
        case 'dd': out += pad(date.getDate(), 2); break;
        case 'd': out += String(date.getDate()); break;
        case 'HH': out += pad(date.getHours(), 2); break;
        case 'H': out += String(date.getHours()); break;
        case 'hh': out += pad(((date.getHours() + 11) % 12) + 1, 2); break;
        case 'h': out += String(((date.getHours() + 11) % 12) + 1); break;
        case 'MM': out += pad(date.getMinutes(), 2); break;
        case 'M': out += String(date.getMinutes()); break;
        case 'ss': out += pad(date.getSeconds(), 2); break;
        case 's': out += String(date.getSeconds()); break;
        case 'tt': out += date.getHours() < 12 ? 'am' : 'pm'; break;
        case 't': out += date.getHours() < 12 ? 'a' : 'p'; break;
        default: handled = false;
      }
      if (handled) {
        i += run.length;
      } else {
        out += ch;
        i += 1;
      }
    }
    return out;
  }

  /** The scan half: reads a date written in one of the formats above. */
  function scanDate(format, text) {
    var order = [];
    var pattern = '';
    var i = 0;
    while (i < format.length) {
      var ch = format.charAt(i);
      var run = ch;
      while (i + run.length < format.length && format.charAt(i + run.length) === ch) run += ch;
      var known = true;
      switch (run) {
        case 'yyyy': pattern += '(\\d{4})'; order.push('y4'); break;
        case 'yy': pattern += '(\\d{2})'; order.push('y2'); break;
        case 'mmmm': pattern += '([A-Za-z]+)'; order.push('mname'); break;
        case 'mmm': pattern += '([A-Za-z]{3})'; order.push('mname'); break;
        case 'mm': case 'm': pattern += '(\\d{1,2})'; order.push('m'); break;
        case 'dd': case 'd': pattern += '(\\d{1,2})'; order.push('d'); break;
        case 'HH': case 'H': case 'hh': case 'h': pattern += '(\\d{1,2})'; order.push('H'); break;
        case 'MM': case 'M': pattern += '(\\d{1,2})'; order.push('Min'); break;
        case 'ss': case 's': pattern += '(\\d{1,2})'; order.push('S'); break;
        case 'tt': pattern += '([AaPp][Mm])'; order.push('ampm'); break;
        default: known = false;
      }
      if (known) {
        i += run.length;
      } else {
        pattern += run.replace(/[.*+?^$\{\}()|\[\]\\]/g, '\\$&');
        i += run.length;
      }
    }
    var match = new RegExp('^' + pattern).exec(String(text));
    if (!match) return null;
    var now = new Date();
    var parts = { y: now.getFullYear(), m: 0, d: 1, H: 0, Min: 0, S: 0, pm: null };
    for (var k = 0; k < order.length; k++) {
      var v = match[k + 1];
      switch (order[k]) {
        case 'y4': parts.y = parseInt(v, 10); break;
        case 'y2': parts.y = 2000 + parseInt(v, 10); break;
        case 'm': parts.m = parseInt(v, 10) - 1; break;
        case 'mname':
          for (var mi = 0; mi < MONTHS.length; mi++) {
            if (MONTHS[mi].toLowerCase().indexOf(String(v).toLowerCase()) === 0) parts.m = mi;
          }
          break;
        case 'd': parts.d = parseInt(v, 10); break;
        case 'H': parts.H = parseInt(v, 10); break;
        case 'Min': parts.Min = parseInt(v, 10); break;
        case 'S': parts.S = parseInt(v, 10); break;
        case 'ampm': parts.pm = String(v).toLowerCase().charAt(0) === 'p'; break;
      }
    }
    if (parts.pm === true && parts.H < 12) parts.H += 12;
    if (parts.pm === false && parts.H === 12) parts.H = 0;
    return new Date(parts.y, parts.m, parts.d, parts.H, parts.Min, parts.S);
  }

  var util = {
    /** printf, with the %d, %f, %s and %x conversions Acrobat documents. */
    printf: function (format) {
      var args = Array.prototype.slice.call(arguments, 1);
      var next = 0;
      return String(format).replace(
        /%(,[0-4])?([+ 0#]*)(\d*)(?:\.(\d+))?([dfsxX])/g,
        function (whole, sep, flags, width, precision, kind) {
          var arg = args[next++];
          var out;
          if (kind === 'd') {
            out = String(Math.round(Number(arg)) || 0);
          } else if (kind === 'f') {
            var digits = precision === undefined ? 2 : parseInt(precision, 10);
            out = Number(arg).toFixed(digits);
          } else if (kind === 'x' || kind === 'X') {
            out = (Math.round(Number(arg)) || 0).toString(16);
            if (kind === 'X') out = out.toUpperCase();
          } else {
            out = arg === undefined ? '' : String(arg);
          }
          if (sep && (kind === 'd' || kind === 'f')) out = groupThousands(out, sep.charAt(1));
          if (flags.indexOf('+') >= 0 && Number(arg) >= 0 && kind !== 's') out = '+' + out;
          var w = parseInt(width || '0', 10);
          while (out.length < w) out = (flags.indexOf('0') >= 0 ? '0' : ' ') + out;
          return out;
        }
      );
    },
    printd: function (format, date, xfaPicture) {
      var d = date === undefined ? new Date() : date;
      if (!(d instanceof Date)) d = new Date(d);
      var f = typeof format === 'number' ? NUMBERED_DATE_FORMATS[format] || NUMBERED_DATE_FORMATS[0] : format;
      return formatDate(String(f), d);
    },
    printx: function (mask, value) {
      var text = String(value === undefined ? '' : value);
      var out = '';
      var vi = 0;
      var mode = 0; // 0 none, 1 upper, 2 lower
      for (var i = 0; i < String(mask).length; i++) {
        var m = String(mask).charAt(i);
        var ch = vi < text.length ? text.charAt(vi) : '';
        switch (m) {
          case '?': if (ch !== '') { out += apply(ch); vi++; } break;
          case 'X':
            while (vi < text.length && !/[0-9a-zA-Z]/.test(text.charAt(vi))) vi++;
            if (vi < text.length) { out += apply(text.charAt(vi)); vi++; }
            break;
          case 'A':
            while (vi < text.length && !/[a-zA-Z]/.test(text.charAt(vi))) vi++;
            if (vi < text.length) { out += apply(text.charAt(vi)); vi++; }
            break;
          case '9':
            while (vi < text.length && !/[0-9]/.test(text.charAt(vi))) vi++;
            if (vi < text.length) { out += text.charAt(vi); vi++; }
            break;
          case '*':
            while (vi < text.length) { out += apply(text.charAt(vi)); vi++; }
            break;
          case '\\': i++; if (i < String(mask).length) out += String(mask).charAt(i); break;
          case '>': mode = 1; break;
          case '<': mode = 2; break;
          case '=': mode = 0; break;
          default: out += m;
        }
      }
      function apply(c) { return mode === 1 ? c.toUpperCase() : mode === 2 ? c.toLowerCase() : c; }
      return out;
    },
    scand: function (format, text) {
      var f = typeof format === 'number' ? NUMBERED_DATE_FORMATS[format] || NUMBERED_DATE_FORMATS[0] : format;
      var parsed = scanDate(String(f), text);
      if (parsed) return parsed;
      var fallback = new Date(String(text));
      return isNaN(fallback.getTime()) ? null : fallback;
    },
    byteToChar: function (code) { return String.fromCharCode(Number(code) & 0xff); },
    iconStreamFromIcon: function () { return null; },
    stringFromStream: function () { return ''; },
    streamFromString: function (s) { return String(s); },
  };

  function groupThousands(text, separator) {
    var sep = separator === '1' ? '' : separator === '2' ? '.' : separator === '3' ? '' : ',';
    if (separator === '2') sep = '.';
    if (separator === '3' || separator === '1') sep = separator === '1' ? '' : '';
    var sign = '';
    var body = text;
    if (body.charAt(0) === '-' || body.charAt(0) === '+') { sign = body.charAt(0); body = body.substring(1); }
    var dot = body.indexOf('.');
    var whole = dot < 0 ? body : body.substring(0, dot);
    var rest = dot < 0 ? '' : body.substring(dot);
    if (sep !== '') {
      var grouped = '';
      for (var i = 0; i < whole.length; i++) {
        if (i > 0 && (whole.length - i) % 3 === 0) grouped += sep;
        grouped += whole.charAt(i);
      }
      whole = grouped;
    }
    return sign + whole + rest;
  }

  /* ─── color ───────────────────────────────────────────────────────────── */

  var color = {
    transparent: ['T'], black: ['G', 0], white: ['G', 1], gray: ['G', 0.5], dkGray: ['G', 0.25],
    ltGray: ['G', 0.75], red: ['RGB', 1, 0, 0], green: ['RGB', 0, 1, 0], blue: ['RGB', 0, 0, 1],
    cyan: ['CMYK', 1, 0, 0, 0], magenta: ['CMYK', 0, 1, 0, 0], yellow: ['CMYK', 0, 0, 1, 0],
    equal: function (a, b) {
      if (!a || !b || a[0] !== b[0] || a.length !== b.length) return false;
      for (var i = 1; i < a.length; i++) if (Number(a[i]) !== Number(b[i])) return false;
      return true;
    },
    convert: function (value, space) {
      if (!value || value[0] === space) return value;
      var rgb = toRgb(value);
      if (space === 'RGB') return rgb;
      if (space === 'G') return ['G', 0.3 * rgb[1] + 0.59 * rgb[2] + 0.11 * rgb[3]];
      if (space === 'CMYK') {
        var k = 1 - Math.max(rgb[1], Math.max(rgb[2], rgb[3]));
        if (k >= 1) return ['CMYK', 0, 0, 0, 1];
        return ['CMYK', (1 - rgb[1] - k) / (1 - k), (1 - rgb[2] - k) / (1 - k), (1 - rgb[3] - k) / (1 - k), k];
      }
      if (space === 'T') return ['T'];
      return value;
    },
  };

  function toRgb(value) {
    switch (value[0]) {
      case 'RGB': return value;
      case 'G': return ['RGB', Number(value[1]), Number(value[1]), Number(value[1])];
      case 'CMYK':
        return ['RGB',
          1 - Math.min(1, Number(value[1]) + Number(value[4])),
          1 - Math.min(1, Number(value[2]) + Number(value[4])),
          1 - Math.min(1, Number(value[3]) + Number(value[4]))];
      default: return ['RGB', 0, 0, 0];
    }
  }

  /* ─── Field ───────────────────────────────────────────────────────────── */

  /** The display values of `field.display` (Acrobat: visible, hidden, noView, noPrint). */
  var display = { visible: 0, hidden: 1, noView: 2, noPrint: 3 };

  function Field(name) {
    this.__name = name;
  }

  function defineFieldProp(prop, reader, writer) {
    Object.defineProperty(Field.prototype, prop, {
      get: function () { return reader(this.__name); },
      set: writer ? function (v) { writer(this.__name, v); } : function () {},
      enumerable: true,
      configurable: true,
    });
  }

  function fieldProp(name, prop) { return host.fieldProp(name, prop); }
  function setFieldProp(name, prop, value) { host.setFieldProp(name, prop, value); }

  defineFieldProp('value',
    function (n) {
      var raw = host.fieldProp(n, 'value');
      var type = host.fieldProp(n, 'type');
      if (type === 'text' || type === 'combobox' || type === 'listbox') {
        var asNumber = Number(raw);
        // A text field whose value looks like a number reads as one, which is what every
        // calculation script relies on.
        if (raw !== '' && raw !== null && !isNaN(asNumber)) return asNumber;
      }
      return raw === null ? '' : raw;
    },
    function (n, v) { setFieldProp(n, 'value', v === null || v === undefined ? '' : String(v)); });
  defineFieldProp('valueAsString', function (n) {
    var v = host.fieldProp(n, 'value');
    return v === null ? '' : String(v);
  });
  defineFieldProp('name', function (n) { return n; });
  defineFieldProp('type', function (n) { return fieldProp(n, 'type'); });
  defineFieldProp('page', function (n) { return fieldProp(n, 'page'); });
  defineFieldProp('rect', function (n) { return fieldProp(n, 'rect'); },
    function (n, v) { setFieldProp(n, 'rect', v); });
  defineFieldProp('readonly', function (n) { return !!fieldProp(n, 'readonly'); },
    function (n, v) { setFieldProp(n, 'readonly', !!v); });
  defineFieldProp('required', function (n) { return !!fieldProp(n, 'required'); },
    function (n, v) { setFieldProp(n, 'required', !!v); });
  defineFieldProp('hidden', function (n) { return !!fieldProp(n, 'hidden'); },
    function (n, v) { setFieldProp(n, 'hidden', !!v); });
  defineFieldProp('display',
    function (n) { return fieldProp(n, 'hidden') ? display.hidden : display.visible; },
    function (n, v) { setFieldProp(n, 'hidden', Number(v) === display.hidden || Number(v) === display.noView); });
  defineFieldProp('doc', function () { return globalThis; });
  defineFieldProp('numItems', function (n) { return fieldProp(n, 'numItems'); });
  defineFieldProp('defaultValue', function (n) { return fieldProp(n, 'defaultValue'); });
  defineFieldProp('multiline', function (n) { return !!fieldProp(n, 'multiline'); });
  defineFieldProp('charLimit', function (n) { return fieldProp(n, 'charLimit'); });
  defineFieldProp('borderStyle', function (n) { return fieldProp(n, 'borderStyle'); },
    function (n, v) { setFieldProp(n, 'borderStyle', String(v)); });
  defineFieldProp('lineWidth', function (n) { return fieldProp(n, 'lineWidth'); },
    function (n, v) { setFieldProp(n, 'lineWidth', Number(v)); });
  defineFieldProp('print', function (n) { return !!fieldProp(n, 'print'); },
    function (n, v) { setFieldProp(n, 'print', !!v); });
  defineFieldProp('delay', function () { return false; }, function () {});
  defineFieldProp('currentValueIndices',
    function (n) { return fieldProp(n, 'currentValueIndices'); },
    function (n, v) { setFieldProp(n, 'currentValueIndices', v); });
  defineFieldProp('strokeColor', function (n) { return fieldProp(n, 'strokeColor') || color.transparent; },
    function (n, v) { setFieldProp(n, 'strokeColor', v); });
  defineFieldProp('fillColor', function (n) { return fieldProp(n, 'fillColor') || color.transparent; },
    function (n, v) { setFieldProp(n, 'fillColor', v); });
  defineFieldProp('textColor', function (n) { return fieldProp(n, 'textColor') || color.black; },
    function (n, v) { setFieldProp(n, 'textColor', v); });
  defineFieldProp('textSize', function (n) { return fieldProp(n, 'textSize'); },
    function (n, v) { setFieldProp(n, 'textSize', Number(v)); });
  defineFieldProp('alignment', function (n) { return fieldProp(n, 'alignment'); });
  defineFieldProp('comb', function (n) { return !!fieldProp(n, 'comb'); });
  defineFieldProp('editable', function (n) { return !!fieldProp(n, 'editable'); });
  defineFieldProp('password', function (n) { return !!fieldProp(n, 'password'); });
  defineFieldProp('userName', function (n) { return fieldProp(n, 'userName'); });

  // The rest of the Field surface. A script reads these far more often than it writes them, and
  // one missing name stops the script at its first line, so each answers what PDFium answers for
  // a field that carries nothing of its own.
  var fieldDefaults = {
    buttonAlignX: 50, buttonAlignY: 50, buttonFitBounds: false, buttonPosition: 0,
    buttonScaleHow: 0, buttonScaleWhen: 0, calcOrderIndex: -1, commitOnSelChange: false,
    defaultStyle: null, doNotScroll: false, doNotSpellCheck: false, exportValues: [],
    fileSelect: false, highlight: 'invert', multipleSelection: false, radiosInUnison: false,
    richText: false, richValue: [], rotation: 0, style: 'check', submitName: '',
    textFont: 'Helv', source: null,
  };
  for (var defaultName in fieldDefaults) {
    (function (prop, value) {
      Object.defineProperty(Field.prototype, prop, {
        get: function () { return value; },
        set: function () {},
        enumerable: true,
        configurable: true,
      });
    })(defaultName, fieldDefaults[defaultName]);
  }

  Field.prototype.setFocus = function () { host.action('setFocus', { field: this.__name }); };
  Field.prototype.browseForFileToSubmit = function () {};
  Field.prototype.buttonGetIcon = function () { return null; };
  Field.prototype.buttonImportIcon = function () { return 1; };
  Field.prototype.buttonSetIcon = function () {};
  Field.prototype.signatureGetModifications = function () { return ''; };
  Field.prototype.signatureGetSeedValue = function () { return null; };
  Field.prototype.signatureSetSeedValue = function () {};
  Field.prototype.signatureSign = function () { return false; };
  Field.prototype.checkThisBox = function (widget, on) {
    host.setFieldProp(this.__name, 'checked', on === undefined ? true : !!on);
  };
  Field.prototype.isBoxChecked = function () { return !!host.fieldProp(this.__name, 'checked'); };
  Field.prototype.isDefaultChecked = function () { return !!host.fieldProp(this.__name, 'defaultChecked'); };
  Field.prototype.defaultIsChecked = function (widget, on) {
    host.setFieldProp(this.__name, 'defaultChecked', !!on);
  };
  Field.prototype.getArray = function () { return [this]; };
  Field.prototype.getItemAt = function (index, exportValue) {
    return host.itemAt(this.__name, Number(index), exportValue === undefined ? true : !!exportValue);
  };
  Field.prototype.setItems = function (items) { host.setItems(this.__name, items); };
  Field.prototype.clearItems = function () { host.setItems(this.__name, []); };
  Field.prototype.insertItemAt = function (name, exportValue, index) {
    host.insertItem(this.__name, String(name), exportValue === undefined ? String(name) : String(exportValue),
      index === undefined ? 0 : Number(index));
  };
  Field.prototype.deleteItemAt = function (index) { host.deleteItem(this.__name, Number(index)); };
  Field.prototype.buttonGetCaption = function () { return host.fieldProp(this.__name, 'caption'); };
  Field.prototype.buttonSetCaption = function (caption) {
    host.setFieldProp(this.__name, 'caption', String(caption));
  };
  Field.prototype.getLock = function () { return null; };
  Field.prototype.setLock = function () {};
  Field.prototype.signatureInfo = function () { return {}; };
  Field.prototype.signatureValidate = function () { return 0; };
  Field.prototype.setAction = function () {};
  Field.prototype.toString = function () { return '[object Field]'; };

  /* ─── app ─────────────────────────────────────────────────────────────── */

  var app = {
    viewerType: 'KitePDF',
    viewerVersion: host.docProp('viewerVersion'),
    viewerVariation: 'Reader',
    formsVersion: 8,
    language: host.docProp('language'),
    platform: host.docProp('platform'),
    plugIns: [],
    activeDocs: [],
    calculate: true,
    fullscreen: false,
    fs: { isFullScreen: false },
    media: {},
    monitors: [],
    numPlugIns: 0,
    runtimeHighlight: false,
    thermometer: {
      begin: function () {}, end: function () {}, text: '', value: 0, duration: 0, cancelled: false,
    },
    toolbar: false,
    alert: function (message, icon, type, title) {
      var options = message;
      if (message === null || typeof message !== 'object') {
        options = { cMsg: message, nIcon: icon, nType: type, cTitle: title };
      }
      return host.alert({
        message: options.cMsg === undefined ? '' : String(options.cMsg),
        icon: Number(options.nIcon || 0),
        type: Number(options.nType || 0),
        title: options.cTitle === undefined ? '' : String(options.cTitle),
      });
    },
    beep: function () { host.action('beep', {}); },
    response: function (question) {
      var options = question;
      if (question === null || typeof question !== 'object') {
        options = { cQuestion: question, cTitle: arguments[1], cDefault: arguments[2] };
      }
      return host.response({
        question: options.cQuestion === undefined ? '' : String(options.cQuestion),
        title: options.cTitle === undefined ? '' : String(options.cTitle),
        defaultValue: options.cDefault === undefined ? '' : String(options.cDefault),
        password: !!options.bPassword,
      });
    },
    setInterval: function (code, period) {
      return { __timer: host.setTimer(String(code), Number(period === undefined ? 1000 : period), true) };
    },
    setTimeOut: function (code, delay) {
      return { __timer: host.setTimer(String(code), Number(delay === undefined ? 1000 : delay), false) };
    },
    clearInterval: function (timer) { if (timer) host.clearTimer(timer.__timer); },
    clearTimeOut: function (timer) { if (timer) host.clearTimer(timer.__timer); },
    launchURL: function (url, newFrame) { host.action('launchURL', { url: String(url), newFrame: !!newFrame }); },
    mailMsg: function () { host.action('mailMsg', {}); },
    execMenuItem: function (item) { host.action('execMenuItem', { item: String(item) }); },
    execDialog: function () { return 'cancel'; },
    goBack: function () { host.action('goBack', {}); },
    goForward: function () { host.action('goForward', {}); },
    newDoc: function () { return null; },
    openDoc: function () { return null; },
    popUpMenu: function () { return null; },
    popUpMenuEx: function () { return null; },
    getNthPlugInName: function () { return ''; },
    getPath: function () { return ''; },
    addToolButton: function () {},
    removeToolButton: function () {},
    trustedFunction: function (f) { return f; },
    trustPropagatorFunction: function (f) { return f; },
    beginPriv: function () {},
    endPriv: function () {},
  };

  /* ─── console ─────────────────────────────────────────────────────────── */

  var console_ = {
    println: function (text) { host.console(text === undefined ? '' : String(text)); },
    clear: function () {}, show: function () {}, hide: function () {},
  };

  /* ─── the document, whose members are globals ─────────────────────────── */

  function getField(name) {
    if (name === undefined || name === null) return null;
    var full = String(name);
    if (!host.hasField(full)) return null;
    return new Field(full);
  }

  var doc = {
    getField: getField,
    getNthFieldName: function (index) { return host.nthFieldName(Number(index)); },
    calculateNow: function () { host.action('calculateNow', {}); },
    resetForm: function (names) { host.action('resetForm', { fields: names || null }); },
    submitForm: function (options) {
      var o = (options === null || typeof options !== 'object') ? { cURL: options } : options;
      host.action('submitForm', { url: String(o.cURL === undefined ? '' : o.cURL) });
    },
    mailForm: function () { host.action('mailForm', {}); },
    mailDoc: function () { host.action('mailDoc', {}); },
    print: function () { host.action('print', {}); },
    closeDoc: function () { host.action('closeDoc', {}); },
    saveAs: function () { host.action('saveAs', {}); },
    gotoNamedDest: function (name) { host.action('gotoNamedDest', { name: String(name) }); },
    getPageNumWords: function (page) { return host.pageWords(Number(page)).length; },
    getPageNthWord: function (page, index) {
      var words = host.pageWords(Number(page === undefined ? 0 : page));
      var i = Number(index === undefined ? 0 : index);
      return i >= 0 && i < words.length ? words[i] : '';
    },
    getPageNthWordQuads: function () { return []; },
    getPageBox: function (which, page) { return host.pageBox(String(which || 'Crop'), Number(page || 0)); },
    getAnnots: function () { return []; },
    getAnnot: function () { return null; },
    getOCGs: function () { return []; },
    getLinks: function () { return []; },
    addField: function () { return null; },
    removeField: function () {},
    addAnnot: function () { return null; },
    addLink: function () { return null; },
    addIcon: function () {},
    getIcon: function () { return null; },
    removeIcon: function () {},
    importAnFDF: function () { return false; },
    exportAsFDF: function () { return ''; },
    exportAsText: function () { return ''; },
    deletePages: function () {}, insertPages: function () {}, replacePages: function () {},
    extractPages: function () { return null; },
    createDataObject: function () {},
    getPrintParams: function () { return { firstPage: 0, lastPage: host.docProp('numPages') - 1 }; },
    syncAnnotScan: function () {},
    spellDictionaryOrder: [], spellLanguageOrder: [],
    ADBE: null, Collab: { addStateModel: function () {}, removeStateModel: function () {} },
    external: false, dirty: false, delay: false, isModal: false, noautocomplete: false,
    layout: 'SinglePage', media: {}, mouseX: 0, mouseY: 0, zoomType: 'FitPage', zoom: 100,
    viewState: null, selectedAnnots: [], icons: null, hidden: false, xfa: null,
    hostContainer: null, innerAppWindowRect: [0, 0, 0, 0], innerDocWindowRect: [0, 0, 0, 0],
    outerAppWindowRect: [0, 0, 0, 0], outerDocWindowRect: [0, 0, 0, 0], pageWindowRect: [0, 0, 0, 0],
    requiresFullSave: false, securityHandler: null, sounds: [], templates: [],
    dataObjects: [], bookmarkRoot: null, calculate: true, disclosed: false,
  };

  /** The read-only facts about the file, asked of the host each time. */
  var docReadOnly = ['numPages', 'pageNum', 'numFields', 'title', 'author', 'subject', 'keywords',
    'creator', 'producer', 'creationDate', 'modDate', 'documentFileName', 'path', 'URL', 'baseURL',
    'filesize', 'permStatusReady', 'security'];
  for (var i = 0; i < docReadOnly.length; i++) {
    (function (prop) {
      Object.defineProperty(doc, prop, {
        get: function () { return host.docProp(prop); },
        set: function (v) { if (prop === 'pageNum') host.action('gotoPage', { page: Number(v) }); },
        enumerable: true, configurable: true,
      });
    })(docReadOnly[i]);
  }

  Object.defineProperty(doc, 'info', {
    get: function () { return host.docProp('info'); }, enumerable: true, configurable: true,
  });

  /* ─── binding it all onto the global object ───────────────────────────── */

  // Chrome's PDF engine makes the document the global object, so a file written for it says
  // getField(...) with no receiver. Acrobat's own documents say this.getField(...), and at the
  // top level `this` is the global object, so putting the members here serves both.
  var g = globalThis;
  for (var key in doc) g[key] = doc[key];
  var docProps = Object.getOwnPropertyNames(doc);
  for (var p = 0; p < docProps.length; p++) {
    var name = docProps[p];
    var descriptor = Object.getOwnPropertyDescriptor(doc, name);
    if (descriptor.get) Object.defineProperty(g, name, descriptor);
  }
  g.app = app;
  g.util = util;
  g.color = color;
  g.console = console_;
  g.display = display;
  g.font = { Cour: 'Cour', Helv: 'Helv', Times: 'Times', TimesB: 'TimesB', ZapfD: 'ZapfD' };
  g.border = { s: 's', d: 'd', b: 'b', i: 'i', u: 'u' };
  g.position = { textOnly: 0, iconOnly: 1, iconTextV: 2, textIconV: 3, iconTextH: 4, textIconH: 5, overlay: 6 };
  g.scaleHow = { proportional: 0, anamorphic: 1 };
  g.scaleWhen = { always: 0, never: 1, tooBig: 2, tooSmall: 3 };
  g.style = { ch: 'check', cr: 'cross', di: 'diamond', ci: 'circle', st: 'star', sq: 'square' };
  g.zoomtype = { none: 'NoVary', fitP: 'FitPage', fitW: 'FitWidth', fitH: 'FitHeight', fitV: 'FitVisibleWidth', pref: 'Preferred', refW: 'ReflowWidth' };
  g.global = { setPersistent: function () {}, subscribe: function () {} };
  g.identity = { name: '', corporation: '', email: '' };
  g.security = {};
  g.Doc = doc;
  g.Field = Field;
  // The event a script sees outside any trigger. A file that reads event.value at the top level
  // of its document script must find the same shape it finds inside one.
  function emptyEvent() {
    return {
      name: 'Open', type: 'Doc', rc: true, willCommit: true, value: '', change: '',
      changeEx: undefined, commitKey: 0, fieldFull: false, keyDown: false, modifier: false,
      richChange: [], richChangeEx: [], richValue: [], selEnd: 0, selStart: 0, shift: false,
      source: null, target: null, targetName: '',
    };
  }
  g.event = emptyEvent();

  /** Builds the event object for one trigger, runs [body], and reports what the script left. */
  g.__kiteEvent = function (info, body) {
    var previous = g.event;
    var ev = emptyEvent();
    var built = {
      name: info.name, type: info.type, rc: true, willCommit: !!info.willCommit,
      value: info.value === undefined ? '' : info.value,
      change: info.change === undefined ? '' : info.change,
      changeEx: info.changeEx === undefined ? undefined : info.changeEx,
      selStart: info.selStart === undefined ? 0 : info.selStart,
      selEnd: info.selEnd === undefined ? 0 : info.selEnd,
      commitKey: info.commitKey === undefined ? 0 : info.commitKey,
      fieldFull: false, keyDown: false, modifier: false, shift: !!info.shift,
      richChange: [], richChangeEx: [], richValue: [],
      source: info.field ? getField(info.field) : null,
      target: info.field ? getField(info.field) : null,
      targetName: info.field === undefined ? '' : info.field,
    };
    for (var key in built) ev[key] = built[key];
    g.event = ev;
    try {
      body();
    } finally {
      // The result goes to the host even when the script threw, because a script that fails
      // half way still leaves the value it set, which is what Acrobat shows.
      var result = {
        rc: ev.rc === undefined ? true : !!ev.rc,
        value: ev.value === undefined ? '' : String(ev.value),
        change: ev.change === undefined ? '' : String(ev.change),
      };
      host.eventResult(result);
      g.event = previous;
    }
  };
})();
"""
}
