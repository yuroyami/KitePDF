package io.github.yuroyami.kitepdf.javascript

/**
 * The `AF` helper functions every form tool writes into its fields.
 *
 * A form built in Acrobat, LiveCycle or a web form tool almost never carries its own formatting
 * code. It calls `AFNumber_Format` for currency, `AFDate_FormatEx` for dates and
 * `AFSimple_Calculate` for a total, and a viewer without those functions leaves every such field
 * unformatted and every total empty. Acrobat ships them as a JavaScript library, and Chrome's PDF
 * engine implements the same set in C++; this is the same library, written once here.
 *
 * Each formatting function reads and writes `event.value`, and each keystroke function may set
 * `event.rc` to false to refuse what was typed, which is the contract the Acrobat JavaScript
 * reference describes for the format and keystroke triggers.
 */
internal object AformApi {

    val SOURCE: String = """
(function () {
  'use strict';

  var g = globalThis;

  function separators(sepStyle) {
    switch (Number(sepStyle)) {
      case 1: return { group: '', decimal: '.' };
      case 2: return { group: '.', decimal: ',' };
      case 3: return { group: '', decimal: ',' };
      case 4: return { group: "'", decimal: '.' };
      default: return { group: ',', decimal: '.' };
    }
  }

  function group(digits, separator) {
    if (!separator) return digits;
    var out = '';
    for (var i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 === 0) out += separator;
      out += digits.charAt(i);
    }
    return out;
  }

  /** The number a field holds, whatever separators it was typed with. */
  function AFMakeNumber(value) {
    if (typeof value === 'number') return value;
    var text = String(value === undefined || value === null ? '' : value).trim();
    if (text === '') return null;
    // A comma may be the decimal mark, so it only groups when a period is there too.
    if (text.indexOf('.') >= 0 && text.indexOf(',') >= 0) {
      text = text.replace(/,/g, '');
    } else if (text.indexOf(',') >= 0) {
      text = text.replace(/,/g, '.');
    }
    text = text.replace(/[^0-9.eE+-]/g, '');
    var n = Number(text);
    return isNaN(n) ? null : n;
  }

  function AFExtractNums(text) {
    var found = String(text === undefined ? '' : text).match(/\d+/g);
    return found === null ? [] : found;
  }

  /** What the field will hold once this keystroke is taken. */
  function AFMergeChange(ev) {
    var e = ev || g.event;
    var value = String(e.value === undefined ? '' : e.value);
    if (e.willCommit) return value;
    var start = Number(e.selStart || 0);
    var end = Number(e.selEnd || 0);
    if (start < 0) start = 0;
    if (end < start) end = start;
    return value.substring(0, start) + String(e.change === undefined ? '' : e.change) + value.substring(end);
  }

  function isNumberText(text) {
    var t = String(text).trim();
    if (t === '') return true;
    return /^[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?${'$'}/.test(t);
  }

  function formatNumber(value, nDec, sepStyle, negStyle, currency, prepend) {
    var n = AFMakeNumber(value);
    if (n === null) return { text: '', negative: false };
    var negative = n < 0;
    var sep = separators(sepStyle);
    var fixed = Math.abs(n).toFixed(Number(nDec === undefined ? 2 : nDec));
    var dot = fixed.indexOf('.');
    var whole = dot < 0 ? fixed : fixed.substring(0, dot);
    var fraction = dot < 0 ? '' : fixed.substring(dot + 1);
    var text = group(whole, sep.group) + (fraction === '' ? '' : sep.decimal + fraction);
    var money = currency === undefined ? '' : String(currency);
    if (money !== '') text = prepend === false ? text + money : money + text;
    if (negative) {
      switch (Number(negStyle)) {
        case 0: case 1: text = '-' + text; break;
        case 2: case 3: text = '(' + text + ')'; break;
        default: text = '-' + text;
      }
    }
    return { text: text, negative: negative };
  }

  /** Number formatting, the most common line in any form: AFNumber_Format(2, 0, 0, 0, "${'$'}", true). */
  function AFNumber_Format(nDec, sepStyle, negStyle, currStyle, strCurrency, bCurrencyPrepend) {
    var e = g.event;
    // currStyle is a legacy argument Acrobat ignores: the symbol itself is strCurrency.
    var formatted = formatNumber(e.value, nDec, sepStyle, negStyle, strCurrency, bCurrencyPrepend);
    if (String(e.value).trim() === '') { e.value = ''; return; }
    e.value = formatted.text;
    // Red for the two negative styles that ask for it (Acrobat: 1 and 3).
    if (formatted.negative && (Number(negStyle) === 1 || Number(negStyle) === 3) && e.target) {
      e.target.textColor = g.color.red;
    } else if (e.target && (Number(negStyle) === 1 || Number(negStyle) === 3)) {
      e.target.textColor = g.color.black;
    }
  }

  function AFNumber_Keystroke(nDec, sepStyle, negStyle, currStyle, strCurrency, bCurrencyPrepend) {
    var e = g.event;
    var merged = AFMergeChange(e);
    if (merged === '' || merged === '-' || merged === '+' || merged === '.' || merged === ',') return;
    if (!isNumberText(merged)) {
      e.rc = false;
      if (e.willCommit) {
        g.app.alert({ cMsg: 'The value entered does not match the format of the field [ ' + (e.targetName || '') + ' ]' });
      }
    }
  }

  function AFPercent_Format(nDec, sepStyle, bPercentPrepend) {
    var e = g.event;
    var n = AFMakeNumber(e.value);
    if (n === null) { e.value = ''; return; }
    var formatted = formatNumber(n * 100, nDec, sepStyle, 0, '', true);
    e.value = bPercentPrepend ? '%' + formatted.text : formatted.text + '%';
  }

  function AFPercent_Keystroke(nDec, sepStyle) {
    AFNumber_Keystroke(nDec, sepStyle, 0, 0, '', true);
  }

  function AFDate_FormatEx(cFormat) {
    var e = g.event;
    if (String(e.value).trim() === '') return;
    var date = g.util.scand(cFormat, e.value) || g.util.scand('m/d/yy', e.value) || new Date(String(e.value));
    if (!date || isNaN(date.getTime())) return;
    e.value = g.util.printd(cFormat, date);
  }

  function AFDate_Format(pdf) {
    var formats = ['m/d', 'm/d/yy', 'mm/dd/yy', 'mm/yy', 'd-mmm', 'd-mmm-yy', 'dd-mmm-yy',
      'yy-mm-dd', 'mmm-yy', 'mmmm-yy', 'mmm d, yyyy', 'mmmm d, yyyy', 'm/d/yy h:MM tt',
      'm/d/yy HH:MM'];
    AFDate_FormatEx(formats[Number(pdf)] || formats[1]);
  }

  function AFDate_KeystrokeEx(cFormat) {
    var e = g.event;
    if (!e.willCommit) return;
    var merged = AFMergeChange(e);
    if (merged === '') return;
    var date = g.util.scand(cFormat, merged) || new Date(merged);
    if (!date || isNaN(date.getTime())) {
      e.rc = false;
      g.app.alert({ cMsg: 'Invalid date/time: please ensure that the date/time exists. Field [ ' + (e.targetName || '') + ' ]' });
    }
  }

  function AFDate_Keystroke(pdf) {
    var formats = ['m/d', 'm/d/yy', 'mm/dd/yy', 'mm/yy', 'd-mmm', 'd-mmm-yy', 'dd-mmm-yy',
      'yy-mm-dd', 'mmm-yy', 'mmmm-yy', 'mmm d, yyyy', 'mmmm d, yyyy', 'm/d/yy h:MM tt',
      'm/d/yy HH:MM'];
    AFDate_KeystrokeEx(formats[Number(pdf)] || formats[1]);
  }

  function AFTime_FormatEx(cFormat) { AFDate_FormatEx(cFormat); }

  function AFTime_Format(pdf) {
    var formats = ['HH:MM', 'h:MM tt', 'HH:MM:ss', 'h:MM:ss tt'];
    AFTime_FormatEx(formats[Number(pdf)] || formats[0]);
  }

  function AFTime_KeystrokeEx(cFormat) { AFDate_KeystrokeEx(cFormat); }

  function AFTime_Keystroke(pdf) {
    var formats = ['HH:MM', 'h:MM tt', 'HH:MM:ss', 'h:MM:ss tt'];
    AFTime_KeystrokeEx(formats[Number(pdf)] || formats[0]);
  }

  /** The special formats: 0 zip, 1 zip plus four, 2 phone, 3 social security number. */
  function AFSpecial_Format(psf) {
    var e = g.event;
    var digits = String(e.value === undefined ? '' : e.value).replace(/\D/g, '');
    switch (Number(psf)) {
      case 0: e.value = digits.substring(0, 5); break;
      case 1:
        e.value = digits.length > 5 ? digits.substring(0, 5) + '-' + digits.substring(5, 9) : digits;
        break;
      case 2:
        if (digits.length === 10) {
          e.value = '(' + digits.substring(0, 3) + ') ' + digits.substring(3, 6) + '-' + digits.substring(6);
        } else if (digits.length === 7) {
          e.value = digits.substring(0, 3) + '-' + digits.substring(3);
        }
        break;
      case 3:
        if (digits.length === 9) {
          e.value = digits.substring(0, 3) + '-' + digits.substring(3, 5) + '-' + digits.substring(5);
        }
        break;
    }
  }

  function AFSpecial_KeystrokeEx(mask) {
    var e = g.event;
    if (!mask) return;
    var merged = AFMergeChange(e);
    if (merged === '') return;
    var m = String(mask);
    if (merged.length > m.length) { e.rc = false; return; }
    for (var i = 0; i < merged.length; i++) {
      var c = merged.charAt(i);
      var rule = m.charAt(i);
      var ok = true;
      switch (rule) {
        case '9': ok = /\d/.test(c); break;
        case 'A': ok = /[A-Za-z]/.test(c); break;
        case 'O': ok = /[A-Za-z0-9]/.test(c); break;
        case 'X': ok = true; break;
        default: ok = c === rule;
      }
      if (!ok) { e.rc = false; return; }
    }
    if (e.willCommit && merged.length < m.length) e.rc = false;
  }

  function AFSpecial_Keystroke(psf) {
    var masks = ['99999', '99999-9999', '(999) 999-9999', '999-99-9999'];
    var e = g.event;
    var merged = AFMergeChange(e);
    if (merged === '') return;
    switch (Number(psf)) {
      case 0: if (!/^\d{0,5}${'$'}/.test(merged)) e.rc = false; break;
      case 1: if (!/^\d{0,5}(-?\d{0,4})?${'$'}/.test(merged)) e.rc = false; break;
      case 2: if (!/^[\d()\s.-]{0,14}${'$'}/.test(merged)) e.rc = false; break;
      case 3: if (!/^\d{0,3}(-?\d{0,2})?(-?\d{0,4})?${'$'}/.test(merged)) e.rc = false; break;
      default: AFSpecial_KeystrokeEx(masks[Number(psf)] || '');
    }
  }

  /** The totals row of every invoice form: AFSimple_Calculate("SUM", "price.1, price.2"). */
  function AFSimple_Calculate(cFunction, cFields) {
    var names = cFields;
    if (typeof names === 'string') names = names.split(/\s*,\s*/);
    if (!names || !names.length) { g.event.value = 0; return; }
    var result = null;
    var count = 0;
    for (var i = 0; i < names.length; i++) {
      var name = String(names[i]).trim();
      if (name === '') continue;
      var field = g.getField(name);
      if (!field) continue;
      var parts = field.getArray();
      for (var p = 0; p < parts.length; p++) {
        var n = AFMakeNumber(parts[p].value);
        if (n === null) continue;
        count++;
        switch (String(cFunction).toUpperCase()) {
          case 'PRD': result = result === null ? n : result * n; break;
          case 'MIN': result = result === null ? n : Math.min(result, n); break;
          case 'MAX': result = result === null ? n : Math.max(result, n); break;
          default: result = result === null ? n : result + n; // SUM and AVG both add first
        }
      }
    }
    if (result === null) result = 0;
    if (String(cFunction).toUpperCase() === 'AVG') result = count === 0 ? 0 : result / count;
    g.event.value = result;
  }

  function AFSimple(cFunction, nValue1, nValue2) {
    var a = AFMakeNumber(nValue1) || 0;
    var b = AFMakeNumber(nValue2) || 0;
    switch (String(cFunction).toUpperCase()) {
      case 'AVG': return (a + b) / 2;
      case 'PRD': return a * b;
      case 'MIN': return Math.min(a, b);
      case 'MAX': return Math.max(a, b);
      default: return a + b;
    }
  }

  function AFRange_Validate(bGreaterThan, nGreaterThan, bLessThan, nLessThan) {
    var e = g.event;
    if (String(e.value).trim() === '') return;
    var n = AFMakeNumber(e.value);
    if (n === null) return;
    var low = AFMakeNumber(nGreaterThan);
    var high = AFMakeNumber(nLessThan);
    var ok = true;
    if (bGreaterThan && low !== null && n < low) ok = false;
    if (bLessThan && high !== null && n > high) ok = false;
    if (!ok) {
      e.rc = false;
      var message = bGreaterThan && bLessThan
        ? 'Value must be greater than or equal to ' + low + ' and less than or equal to ' + high + '.'
        : bGreaterThan ? 'Value must be greater than or equal to ' + low + '.'
          : 'Value must be less than or equal to ' + high + '.';
      g.app.alert({ cMsg: message });
    }
  }

  function AFParseDateEx(text, format) { return g.util.scand(format, text); }

  function AFGetDecimalSeparator(sepStyle) { return separators(sepStyle).decimal; }

  function AFGetGroupSeparator(sepStyle) { return separators(sepStyle).group; }

  g.AFMakeNumber = AFMakeNumber;
  g.AFExtractNums = AFExtractNums;
  g.AFMergeChange = AFMergeChange;
  g.AFNumber_Format = AFNumber_Format;
  g.AFNumber_Keystroke = AFNumber_Keystroke;
  g.AFPercent_Format = AFPercent_Format;
  g.AFPercent_Keystroke = AFPercent_Keystroke;
  g.AFDate_Format = AFDate_Format;
  g.AFDate_FormatEx = AFDate_FormatEx;
  g.AFDate_Keystroke = AFDate_Keystroke;
  g.AFDate_KeystrokeEx = AFDate_KeystrokeEx;
  g.AFTime_Format = AFTime_Format;
  g.AFTime_FormatEx = AFTime_FormatEx;
  g.AFTime_Keystroke = AFTime_Keystroke;
  g.AFTime_KeystrokeEx = AFTime_KeystrokeEx;
  g.AFSpecial_Format = AFSpecial_Format;
  g.AFSpecial_Keystroke = AFSpecial_Keystroke;
  g.AFSpecial_KeystrokeEx = AFSpecial_KeystrokeEx;
  g.AFSimple = AFSimple;
  g.AFSimple_Calculate = AFSimple_Calculate;
  g.AFRange_Validate = AFRange_Validate;
  g.AFParseDateEx = AFParseDateEx;
  g.AFMakeArrayFromList = function (list) { return String(list).split(/\s*,\s*/); };
  g.AFGetDecimalSeparator = AFGetDecimalSeparator;
  g.AFGetGroupSeparator = AFGetGroupSeparator;
})();
"""
}
