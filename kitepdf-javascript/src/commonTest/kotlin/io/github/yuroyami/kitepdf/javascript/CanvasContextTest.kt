package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * A canvas's 2D context as Chromium answers it (#501): its members, its defaults, what each setter takes,
 * colours and fonts as it writes them back, transforms, save and restore, gradients, patterns,
 * and the geometry of paths and strokes through isPointInPath and isPointInStroke.
 */
class CanvasContextTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function log(s) { out.push(s); }
        function err(e) { return e && typeof e === 'object' && 'name' in e ? e.name + ': ' + e.message : 'value ' + String(e); }
        function tryit(name, f) { try { log(name + ' ' + f()); } catch (e) { log(name + ' threw ' + err(e)); } }
        function show(v) {
          if (v === undefined) return 'undefined';
          if (v === null || typeof v !== 'object') return JSON.stringify(v);
          if (ArrayBuffer.isView(v)) return Object.prototype.toString.call(v).slice(8, -1) + '[' + Array.prototype.join.call(new Uint8Array(v.buffer, v.byteOffset, v.byteLength), ',') + ']@' + v.byteOffset + '/' + v.buffer.byteLength;
          if (Array.isArray(v)) return '[' + v.map(show).join(',') + ']';
          var keys = Object.keys(v);
          return '{' + keys.map(function (k) { return k + ':' + show(v[k]); }).join(',') + '}';
        }
        function names(o) { return Object.getOwnPropertyNames(o).sort().join(','); }
        function done(p, name) { return p.then(function (v) { log(name + ' fulfilled ' + show(v)); }, function (e) { log(name + ' rejected ' + err(e)); }); }
        function readAll(reader, name) {
          return reader.read().then(function (r) { log(name + ' read ' + show(r)); if (!r.done) return readAll(reader, name); }, function (e) { log(name + ' read rejected ' + err(e)); });
        }
        var steps = [];
        function step(name, f) { steps.push([name, f]); }
        function next() {
          var s = steps.shift();
          if (!s) { log('done'); console.log('C501\n' + out.join('\n')); return; }
          log('== ' + s[0]);
          var p;
          try { p = s[1](); } catch (e) { log('step threw ' + err(e)); }
          Promise.resolve(p).then(function () { setTimeout(next, 0); }, function (e) { log('step rejected ' + err(e)); setTimeout(next, 0); });
        }
        function r(v) { return typeof v === 'number' && v === v && Math.abs(v) !== Infinity ? Number(v.toPrecision(10)) : v; }
        function mk(w, h) { var c = document.createElement('canvas'); if (w !== undefined) { c.width = w; c.height = h; } document.body.appendChild(c); return c; }
        var ATTRS = ['fillStyle', 'strokeStyle', 'lineWidth', 'lineCap', 'lineJoin', 'miterLimit', 'lineDashOffset', 'globalAlpha', 'globalCompositeOperation',
          'shadowBlur', 'shadowColor', 'shadowOffsetX', 'shadowOffsetY', 'font', 'textAlign', 'textBaseline', 'direction', 'filter', 'imageSmoothingEnabled',
          'imageSmoothingQuality', 'letterSpacing', 'wordSpacing', 'fontKerning', 'fontStretch', 'fontVariantCaps', 'textRendering', 'lang'];
        function state(x) { return ATTRS.map(function (a) { var v = x[a]; return a + '=' + (typeof v === 'object' ? Object.prototype.toString.call(v) : String(r(v))); }).join(' ') + ' dash=' + x.getLineDash().join(','); }
        function tf(x) { var m = x.getTransform(); return '[' + [m.a, m.b, m.c, m.d, m.e, m.f].map(function (v) { return String(r(v)); }).join(',') + ']'; }
        function set(x, a, vs) { return vs.map(function (v) { var c = mk(), y = c.getContext('2d'); c.remove(); try { y[a] = v; } catch (e) { return String(v) + '->threw ' + err(e); } var g = y[a]; return (typeof v === 'string' ? JSON.stringify(v) : String(v)) + '->' + (typeof g === 'string' ? JSON.stringify(g) : String(r(g))); }).join(' | '); }
        // A grid of hits: '#' inside, '.' outside, over x and y from 0 to 20 step 2 (11 by 11), at the centres of device pixels.
        function grid(test) { var rows = []; for (var y = 0; y <= 20; y += 2) { var s = ''; for (var x = 0; x <= 20; x += 2) s += test(x + 0.5, y + 0.5) ? '#' : '.'; rows.push(s); } return rows.join('/'); }
        function path(name, build, rule) {
          tryit(name, function () { var c = mk(), x = c.getContext('2d'); c.remove(); build(x); return grid(function (a, b) { return rule ? x.isPointInPath(a, b, rule) : x.isPointInPath(a, b); }); });
        }
        function strokeAt(name, build) {
          tryit(name, function () { var c = mk(), x = c.getContext('2d'); c.remove(); build(x); return grid(function (a, b) { return x.isPointInStroke(a, b); }); });
        }

        step('shape', function () {
          tryit('ctors', function () { return [CanvasRenderingContext2D, Path2D, CanvasGradient, CanvasPattern, TextMetrics].map(function (c) { return c.name + c.length; }).join(' '); });
          tryit('context names', function () { return names(CanvasRenderingContext2D.prototype); });
          tryit('path names', function () { return names(Path2D.prototype); });
          tryit('gradient names', function () { return names(CanvasGradient.prototype) + ' | ' + names(CanvasPattern.prototype); });
          tryit('metrics names', function () { return names(TextMetrics.prototype); });
          tryit('canvas names', function () { return ['getContext', 'width', 'height'].map(function (n) { return n + ':' + (n in HTMLCanvasElement.prototype); }).join(); });
          tryit('tags', function () { var x = mk().getContext('2d'); return [x, x.createLinearGradient(0, 0, 1, 1), new Path2D(), x.measureText('a')].map(function (o) { return Object.prototype.toString.call(o); }).join(); });
          tryit('new context', function () { return new CanvasRenderingContext2D(); });
          tryit('new gradient', function () { return new CanvasGradient(); });
          tryit('new metrics', function () { return new TextMetrics(); });
          tryit('getter on proto', function () { return CanvasRenderingContext2D.prototype.lineWidth; });
        });

        step('context', function () {
          var c = mk();
          tryit('size', function () { return c.width + 'x' + c.height + ' attr=' + c.getAttribute('width'); });
          tryit('same', function () { return c.getContext('2d') === c.getContext('2d'); });
          tryit('canvas', function () { return c.getContext('2d').canvas === c; });
          tryit('other kinds', function () { var d = mk(); return [d.getContext('webgl'), d.getContext('2D'), d.getContext('foo')].map(String).join(); });
          tryit('after 2d', function () { return String(c.getContext('webgl')) + ',' + String(c.getContext('bitmaprenderer')); });
          tryit('no args', function () { return c.getContext(); });
          tryit('attributes', function () { return show(c.getContext('2d').getContextAttributes()); });
          tryit('attributes alpha', function () { var d = mk(); return show(d.getContext('2d', { alpha: false, willReadFrequently: true, desynchronized: 1 }).getContextAttributes()); });
          tryit('lost', function () { return c.getContext('2d').isContextLost(); });
          tryit('width set', function () { var d = mk(); d.width = -5; return d.width + ' ' + d.getAttribute('width'); });
          tryit('width attr', function () { var d = mk(); d.setAttribute('width', ' 40px'); d.setAttribute('height', 'abc'); return d.width + 'x' + d.height; });
          tryit('width big', function () { var d = mk(); d.width = 4294967295; return d.width; });
          tryit('width neg attr', function () { var d = mk(); d.setAttribute('width', '-3'); return d.width; });
        });

        step('defaults', function () {
          var x = mk().getContext('2d');
          log('defaults ' + state(x));
          log('transform ' + tf(x));
        });

        step('setters', function () {
          var x = mk().getContext('2d');
          log('lineWidth ' + set(x, 'lineWidth', [2.5, 0, -1, NaN, Infinity, '3', null, '']));
          log('miterLimit ' + set(x, 'miterLimit', [4, 0, -2, NaN]));
          log('lineCap ' + set(x, 'lineCap', ['round', 'square', 'butt', 'ROUND', 'bogus', null]));
          log('lineJoin ' + set(x, 'lineJoin', ['round', 'bevel', 'miter', 'Bevel', '']));
          log('lineDashOffset ' + set(x, 'lineDashOffset', [3, -3, NaN, Infinity]));
          log('globalAlpha ' + set(x, 'globalAlpha', [0.5, 0, 1, 1.5, -0.1, NaN, '0.25']));
          log('composite ' + set(x, 'globalCompositeOperation', ['source-in', 'source-out', 'source-atop', 'destination-over', 'destination-in', 'destination-out', 'destination-atop', 'lighter', 'copy', 'xor', 'multiply', 'screen', 'overlay', 'darken', 'lighten', 'color-dodge', 'color-burn', 'hard-light', 'soft-light', 'difference', 'exclusion', 'hue', 'saturation', 'color', 'luminosity', 'plus-lighter', 'normal', 'darker', 'Multiply', '']));
          log('shadowBlur ' + set(x, 'shadowBlur', [2, 0, -1, NaN]));
          log('shadowOffsetX ' + set(x, 'shadowOffsetX', [2, -3, NaN, Infinity]));
          log('textAlign ' + set(x, 'textAlign', ['left', 'right', 'center', 'end', 'start', 'middle', 'LEFT']));
          log('textBaseline ' + set(x, 'textBaseline', ['top', 'hanging', 'middle', 'ideographic', 'bottom', 'alphabetic', 'center']));
          log('direction ' + set(x, 'direction', ['rtl', 'ltr', 'inherit', 'auto']));
          log('smoothing ' + set(x, 'imageSmoothingEnabled', [false, 0, '', 'x']));
          log('quality ' + set(x, 'imageSmoothingQuality', ['medium', 'high', 'low', 'best']));
          log('filter ' + set(x, 'filter', ['blur(2px)', 'blur( 2px )', 'grayscale(50%) blur(1px)', 'none', 'bogus', 'blur(-1px)', 'url(#f)', 'drop-shadow(1px 2px 3px red)', '']));
          log('letterSpacing ' + set(x, 'letterSpacing', ['2px', '1em', '0px', '-1px', '2', 'bogus', '1.5pt']));
          log('wordSpacing ' + set(x, 'wordSpacing', ['3px', 'normal']));
          log('fontKerning ' + set(x, 'fontKerning', ['normal', 'none', 'bogus']));
          log('fontStretch ' + set(x, 'fontStretch', ['condensed', 'ultra-expanded', 'Condensed', '50%']));
          log('fontVariantCaps ' + set(x, 'fontVariantCaps', ['small-caps', 'all-small-caps', 'petite-caps', 'all-petite-caps', 'unicase', 'titling-caps', 'bogus']));
          log('textRendering ' + set(x, 'textRendering', ['optimizeSpeed', 'optimizeLegibility', 'geometricPrecision', 'auto', 'optimizespeed']));
          log('lang ' + set(x, 'lang', ['en', 'bogus value', '', 'inherit']));
          tryit('dash', function () {
            var y = mk().getContext('2d'), o = [];
            y.setLineDash([1, 2, 3]); o.push(y.getLineDash().join(','));
            y.setLineDash([4, -1]); o.push(y.getLineDash().join(','));
            y.setLineDash([NaN]); o.push(y.getLineDash().join(','));
            y.setLineDash([Infinity, 1]); o.push(y.getLineDash().join(','));
            y.setLineDash([0, 0]); o.push(y.getLineDash().join(','));
            y.setLineDash(['5', 6]); o.push(y.getLineDash().join(','));
            y.setLineDash([]); o.push(y.getLineDash().join(',') + ';');
            var a = [7]; y.setLineDash(a); a[0] = 9; var g = y.getLineDash(); g[0] = 8; o.push(y.getLineDash().join(',') + ' fresh=' + (y.getLineDash() !== y.getLineDash()));
            return o.join(' | ');
          });
          tryit('dash bad', function () { mk().getContext('2d').setLineDash(5); });
          tryit('dash none', function () { mk().getContext('2d').setLineDash(); });
        });

        step('colors', function () {
          var x = mk().getContext('2d');
          log('fill ' + set(x, 'fillStyle', ['red', 'RED', '#0f0', '#00ff0080', '#abcd', 'rgb(1,2,3)', 'rgba(1,2,3,0.5)', 'rgba(1, 2, 3, 1)', 'rgb(10% 20% 30% / 40%)',
            'rgba(1,2,3,0.123456)', 'rgba(1,2,3,0.001)', 'hsl(120, 100%, 50%)', 'hsla(0, 100%, 50%, 0.3)', 'transparent', 'TransParent', 'currentcolor', 'currentColor',
            'bogus', '', ' red ', 'rgb(300, -1, 2.6)', 'hwb(0 0% 0%)', 'rebeccapurple', 'inherit', 'initial', '#12345', 'rgb(1 2 3)', 'rgb(0.5, 1.5, 254.5)', 'none']));
          log('stroke ' + set(x, 'strokeStyle', ['blue', 'rgba(0,0,0,0)', 'bogus']));
          log('shadowColor ' + set(x, 'shadowColor', ['red', 'rgba(0,0,0,0.5)', 'bogus', 'currentcolor']));
          tryit('non string', function () { var y = mk().getContext('2d'); y.fillStyle = 5; var a = y.fillStyle; y.fillStyle = null; var b = y.fillStyle; y.fillStyle = {}; var c = y.fillStyle; y.fillStyle = { toString: function () { return 'red'; } }; return [a, b, c, y.fillStyle].join(); });
          tryit('current in red', function () { var c = mk(); c.style.color = 'red'; var y = c.getContext('2d'); y.fillStyle = 'currentcolor'; return y.fillStyle; });
        });

        step('fonts', function () {
          var x = mk().getContext('2d');
          log('font ' + set(x, 'font', ['10px sans-serif', 'bold 16px serif', '12pt Arial', 'italic small-caps bold 20px/2 "Times New Roman", serif', '2em serif', '50% serif', 'bogus', '16px',
            'bold', '700 10px serif', 'normal normal 400 12px serif', 'oblique 10px a', 'larger serif', 'small serif', 'xx-large serif', '10px  Arial ,  serif', 'condensed 10px serif',
            '10px "a b"', '10px inherit', 'inherit', '10px var(--x)', 'caption', 'menu', 'lighter 10px serif', '1e1px serif', '-1px serif', '0px serif', '10.5px serif', 'italic 400 normal 10px serif', '10px monospace']));
          tryit('font in 20px', function () { var c = mk(); c.style.fontSize = '20px'; var y = c.getContext('2d'); y.font = '2em serif'; var a = y.font; y.font = '50% serif'; return a + ' | ' + y.font + ' | ' + (y.font = 'larger serif', y.font); });
        });

        step('transform', function () {
          var x = mk().getContext('2d'), o = [];
          x.translate(10, 20); o.push(tf(x));
          x.scale(2, 3); o.push(tf(x));
          x.rotate(Math.PI / 2); o.push(tf(x));
          x.transform(1, 2, 3, 4, 5, 6); o.push(tf(x));
          x.translate(NaN, 1); o.push(tf(x));
          x.scale(Infinity, 1); o.push(tf(x));
          x.setTransform(1, 0, 0, 1, 7, 8); o.push(tf(x));
          x.setTransform({ a: 2, d: 2 }); o.push(tf(x));
          x.setTransform({ m11: 3, m22: 4, m41: 5 }); o.push(tf(x));
          x.setTransform(new DOMMatrix([1, 2, 3, 4, 5, 6])); o.push(tf(x));
          x.setTransform(); o.push(tf(x));
          x.resetTransform(); o.push(tf(x));
          x.setTransform(1, 0, 0, 1, NaN, 0); o.push(tf(x));
          x.rotate(0.5); o.push(tf(x));
          x.setTransform(0, 0, 0, 0, 0, 0); o.push(tf(x));
          log('steps ' + o.join(' '));
          tryit('set 3d', function () { x.setTransform({ m33: 2 }); return tf(x); });
          tryit('set 3d is2D', function () { x.setTransform({ m13: 1 }); return tf(x); });
          tryit('set mismatch', function () { x.setTransform({ a: 1, m11: 2 }); return tf(x); });
          tryit('set few', function () { x.setTransform(1, 2); return tf(x); });
          tryit('getTransform fresh', function () { var y = mk().getContext('2d'); var m = y.getTransform(); m.a = 5; return tf(y) + ' ' + (y.getTransform() !== y.getTransform()) + ' ' + Object.prototype.toString.call(m); });
          tryit('transform few', function () { var y = mk().getContext('2d'); y.transform(1, 2, 3); return tf(y); });
          tryit('scale one', function () { var y = mk().getContext('2d'); y.scale(2); return tf(y); });
        });

        step('save restore', function () {
          var x = mk().getContext('2d'), o = [];
          x.fillStyle = 'red'; x.lineWidth = 3; x.translate(5, 5); x.setLineDash([1, 2]); x.font = 'bold 12px serif'; x.globalAlpha = 0.5;
          x.save();
          x.fillStyle = 'blue'; x.lineWidth = 7; x.scale(2, 2); x.setLineDash([3]); x.font = '20px serif'; x.globalAlpha = 0.25; x.shadowBlur = 4; x.textAlign = 'center';
          o.push(x.fillStyle + ' ' + x.lineWidth + ' ' + tf(x) + ' ' + x.getLineDash() + ' ' + x.font + ' ' + x.globalAlpha);
          x.restore();
          o.push(x.fillStyle + ' ' + x.lineWidth + ' ' + tf(x) + ' ' + x.getLineDash() + ' ' + x.font + ' ' + x.globalAlpha + ' ' + x.shadowBlur + ' ' + x.textAlign);
          x.restore(); x.restore();
          o.push(x.fillStyle + ' ' + tf(x));
          log('stack ' + o.join(' | '));
          tryit('path survives restore', function () { var y = mk().getContext('2d'); y.rect(0, 0, 10, 10); y.save(); y.beginPath(); y.rect(20, 20, 5, 5); y.restore(); return y.isPointInPath(1, 1) + ',' + y.isPointInPath(21, 21); });
          tryit('reset', function () { var y = mk().getContext('2d'); y.fillStyle = 'red'; y.translate(3, 3); y.save(); y.rect(0, 0, 50, 50); y.reset(); y.restore(); return y.fillStyle + ' ' + tf(y) + ' ' + y.isPointInPath(1, 1); });
          tryit('width resets', function () { var c = mk(), y = c.getContext('2d'); y.fillStyle = 'red'; y.translate(3, 3); y.setLineDash([2]); y.save(); y.rect(0, 0, 50, 50); c.width = c.width; y.restore(); return y.fillStyle + ' ' + tf(y) + ' ' + y.getLineDash() + ' ' + y.isPointInPath(1, 1); });
          tryit('height attr resets', function () { var c = mk(), y = c.getContext('2d'); y.fillStyle = 'red'; c.setAttribute('height', '150'); return y.fillStyle; });
          tryit('other attr keeps', function () { var c = mk(), y = c.getContext('2d'); y.fillStyle = 'red'; c.setAttribute('id', 'q'); return y.fillStyle; });
          tryit('remove width resets', function () { var c = mk(), y = c.getContext('2d'); c.setAttribute('width', '300'); y.fillStyle = 'red'; c.removeAttribute('width'); return y.fillStyle + ' ' + c.width; });
        });

        step('gradients', function () {
          var x = mk().getContext('2d');
          tryit('linear nan', function () { return x.createLinearGradient(0, 0, NaN, 1); });
          tryit('linear inf', function () { return x.createLinearGradient(0, 0, Infinity, 1); });
          tryit('linear few', function () { return x.createLinearGradient(0, 0, 1); });
          tryit('radial negative', function () { return x.createRadialGradient(0, 0, -1, 1, 1, 1); });
          tryit('radial nan', function () { return x.createRadialGradient(0, 0, 1, 1, 1, NaN); });
          tryit('conic', function () { return Object.prototype.toString.call(x.createConicGradient(0, 1, 2)); });
          tryit('conic nan', function () { return x.createConicGradient(NaN, 1, 2); });
          var g = x.createLinearGradient(0, 0, 10, 0);
          tryit('stop ok', function () { g.addColorStop(0, 'red'); g.addColorStop(1, 'rgba(0,0,255,0.5)'); return 'ok'; });
          tryit('stop range', function () { g.addColorStop(1.5, 'red'); });
          tryit('stop negative', function () { g.addColorStop(-0.1, 'red'); });
          tryit('stop nan', function () { g.addColorStop(NaN, 'red'); });
          tryit('stop color', function () { g.addColorStop(0.5, 'bogus'); });
          tryit('stop current', function () { g.addColorStop(0.5, 'currentcolor'); return 'ok'; });
          tryit('stop few', function () { g.addColorStop(0.5); });
          tryit('as style', function () { x.fillStyle = g; return (x.fillStyle === g) + ' ' + Object.prototype.toString.call(x.fillStyle); });
          tryit('style then color', function () { x.fillStyle = 'red'; return x.fillStyle; });
          tryit('saved object', function () { x.strokeStyle = g; x.save(); x.strokeStyle = 'blue'; x.restore(); return x.strokeStyle === g; });
        });

        step('patterns', function () {
          var x = mk().getContext('2d'), c2 = mk(4, 4);
          tryit('canvas', function () { return Object.prototype.toString.call(x.createPattern(c2, 'repeat')); });
          tryit('null rep', function () { return Object.prototype.toString.call(x.createPattern(c2, null)); });
          tryit('empty rep', function () { return Object.prototype.toString.call(x.createPattern(c2, '')); });
          tryit('reps', function () { return ['repeat-x', 'repeat-y', 'no-repeat'].map(function (s) { return Object.prototype.toString.call(x.createPattern(c2, s)); }).join(); });
          tryit('bad rep', function () { return x.createPattern(c2, 'bogus'); });
          tryit('upper rep', function () { return x.createPattern(c2, 'REPEAT'); });
          tryit('zero canvas', function () { return x.createPattern(mk(0, 4), 'repeat'); });
          tryit('not image', function () { return x.createPattern({}, 'repeat'); });
          tryit('null image', function () { return x.createPattern(null, 'repeat'); });
          tryit('unloaded image', function () { return String(x.createPattern(new Image(), 'repeat')); });
          tryit('set transform', function () { var p = x.createPattern(c2, 'repeat'); p.setTransform({ a: 2 }); p.setTransform(); return 'ok'; });
          tryit('set transform bad', function () { var p = x.createPattern(c2, 'repeat'); p.setTransform({ a: 1, m11: 2 }); });
        });

        step('drawImage args', function () {
          var x = mk().getContext('2d');
          tryit('null', function () { x.drawImage(null, 0, 0); });
          tryit('object', function () { x.drawImage({}, 0, 0); });
          tryit('few', function () { x.drawImage(mk(2, 2)); });
          tryit('four', function () { x.drawImage(mk(2, 2), 0, 0, 1); });
          tryit('zero canvas', function () { x.drawImage(mk(0, 2), 0, 0); });
          tryit('unloaded', function () { x.drawImage(new Image(), 0, 0); return 'ok'; });
          tryit('broken', function () { var i = new Image(); i.src = 'missing.png'; x.drawImage(i, 0, 0); return 'ok'; });
          tryit('nan', function () { x.drawImage(mk(2, 2), NaN, 0); return 'ok'; });
          tryit('nine', function () { x.drawImage(mk(2, 2), 0, 0, 1, 1, 0, 0, 1, 1); return 'ok'; });
          tryit('svg image', function () { x.drawImage(document.createElementNS('http://www.w3.org/2000/svg', 'image'), 0, 0); return 'ok'; });
          tryit('video', function () { x.drawImage(document.createElement('video'), 0, 0); return 'ok'; });
        });

        step('paths', function () {
          path('rect', function (x) { x.rect(4, 4, 10, 6); });
          path('rect negative', function (x) { x.rect(14, 10, -10, -6); });
          path('arc', function (x) { x.arc(10, 10, 7, 0, 2 * Math.PI); });
          path('arc half', function (x) { x.arc(10, 10, 8, 0, Math.PI); });
          path('arc ccw', function (x) { x.arc(10, 10, 8, 0, Math.PI / 2, true); });
          path('arc over', function (x) { x.arc(10, 10, 8, 0, 7 * Math.PI); });
          path('arc ccw over', function (x) { x.arc(10, 10, 8, 0.5, 0.5 - 7, true); });
          path('arc negative', function (x) { x.arc(10, 10, 8, Math.PI / 2, 0); });
          path('arc line', function (x) { x.moveTo(0, 0); x.arc(10, 10, 5, 0, Math.PI); });
          tryit('arc radius', function () { mk().getContext('2d').arc(0, 0, -1, 0, 1); });
          tryit('arc nan', function () { var y = mk().getContext('2d'); y.arc(0, 0, NaN, 0, 1); y.arc(0, 0, -Infinity, 0, 1); return 'ok'; });
          path('ellipse', function (x) { x.ellipse(10, 10, 9, 4, Math.PI / 4, 0, 2 * Math.PI); });
          path('ellipse part', function (x) { x.ellipse(10, 10, 9, 5, 0.3, 0, 2, false); });
          tryit('ellipse radius', function () { mk().getContext('2d').ellipse(0, 0, 1, -1, 0, 0, 1); });
          path('arcTo', function (x) { x.moveTo(2, 2); x.arcTo(18, 2, 18, 18, 8); x.lineTo(18, 18); x.lineTo(2, 18); });
          path('arcTo collinear', function (x) { x.moveTo(2, 2); x.arcTo(10, 2, 18, 2, 5); x.lineTo(10, 18); });
          path('arcTo zero', function (x) { x.moveTo(2, 2); x.arcTo(18, 2, 18, 18, 0); x.lineTo(2, 18); });
          path('arcTo empty', function (x) { x.arcTo(18, 2, 18, 18, 4); x.lineTo(2, 18); x.lineTo(2, 2); });
          tryit('arcTo radius', function () { var y = mk().getContext('2d'); y.moveTo(0, 0); y.arcTo(1, 1, 2, 2, -1); });
          path('bezier', function (x) { x.moveTo(2, 18); x.bezierCurveTo(2, -10, 18, -10, 18, 18); });
          path('quadratic', function (x) { x.moveTo(2, 18); x.quadraticCurveTo(10, -10, 18, 18); });
          path('curve no start', function (x) { x.bezierCurveTo(2, 2, 18, 2, 18, 18); x.lineTo(2, 18); });
          path('lineTo no start', function (x) { x.lineTo(2, 2); x.lineTo(18, 2); x.lineTo(18, 18); });
          path('roundRect', function (x) { x.roundRect(2, 2, 16, 16, 6); });
          path('roundRect list', function (x) { x.roundRect(2, 2, 16, 16, [10, 0, 4, 2]); });
          path('roundRect point', function (x) { x.roundRect(2, 2, 16, 16, [{ x: 10, y: 4 }]); });
          path('roundRect big', function (x) { x.roundRect(2, 2, 16, 8, 30); });
          path('roundRect negative', function (x) { x.roundRect(18, 18, -16, -16, [8, 0]); });
          tryit('roundRect radii', function () { mk().getContext('2d').roundRect(0, 0, 1, 1, [1, 2, 3, 4, 5]); });
          tryit('roundRect empty', function () { mk().getContext('2d').roundRect(0, 0, 1, 1, []); });
          tryit('roundRect negative radius', function () { mk().getContext('2d').roundRect(0, 0, 1, 1, -1); });
          tryit('roundRect none', function () { var y = mk().getContext('2d'); y.roundRect(0, 0, 4, 4); return y.isPointInPath(0.2, 0.2); });
          path('evenodd', function (x) { x.rect(2, 2, 16, 16); x.rect(6, 6, 8, 8); }, 'evenodd');
          path('nonzero', function (x) { x.rect(2, 2, 16, 16); x.rect(6, 6, 8, 8); }, 'nonzero');
          path('winding', function (x) { x.rect(2, 2, 16, 16); x.rect(14, 6, -8, 8); });
          path('transformed', function (x) { x.translate(10, 0); x.rotate(Math.PI / 4); x.rect(0, 0, 10, 10); });
          path('transform after', function (x) { x.rect(0, 0, 6, 6); x.scale(3, 3); });
          path('scaled arc', function (x) { x.scale(2, 1); x.arc(5, 10, 4, 0, 2 * Math.PI); });
          path('closePath', function (x) { x.moveTo(2, 2); x.lineTo(18, 2); x.lineTo(10, 18); x.closePath(); x.lineTo(2, 18); x.lineTo(2, 10); });
          path('moveTo only', function (x) { x.moveTo(5, 5); x.moveTo(15, 15); });
          path('nan point', function (x) { x.moveTo(2, 2); x.lineTo(NaN, 18); x.lineTo(18, 2); x.lineTo(18, 18); });
          path('beginPath', function (x) { x.rect(0, 0, 20, 20); x.beginPath(); x.rect(5, 5, 4, 4); });
          tryit('point transform', function () { var y = mk().getContext('2d'); y.rect(0, 0, 5, 5); y.translate(100, 100); return y.isPointInPath(1, 1) + ',' + y.isPointInPath(101, 101); });
          tryit('point nan', function () { var y = mk().getContext('2d'); y.rect(0, 0, 5, 5); return y.isPointInPath(NaN, 1) + ',' + y.isPointInPath(Infinity, 1); });
          tryit('point edge', function () { var y = mk().getContext('2d'); y.rect(0, 0, 5, 5); return [y.isPointInPath(0, 0), y.isPointInPath(5, 5), y.isPointInPath(5, 2), y.isPointInPath(0, 2), y.isPointInPath(2.5, 5)].join(); });
          tryit('point bad rule', function () { var y = mk().getContext('2d'); y.rect(0, 0, 5, 5); return y.isPointInPath(1, 1, 'bogus'); });
          tryit('point few', function () { return mk().getContext('2d').isPointInPath(1); });
        });

        step('strokes', function () {
          strokeAt('line', function (x) { x.lineWidth = 4; x.moveTo(2, 10); x.lineTo(18, 10); });
          strokeAt('butt', function (x) { x.lineWidth = 6; x.moveTo(6, 10); x.lineTo(14, 10); });
          strokeAt('square', function (x) { x.lineWidth = 6; x.lineCap = 'square'; x.moveTo(6, 10); x.lineTo(14, 10); });
          strokeAt('round', function (x) { x.lineWidth = 8; x.lineCap = 'round'; x.moveTo(6, 10); x.lineTo(14, 10); });
          strokeAt('miter', function (x) { x.lineWidth = 6; x.moveTo(2, 18); x.lineTo(10, 4); x.lineTo(18, 18); });
          strokeAt('bevel', function (x) { x.lineWidth = 6; x.lineJoin = 'bevel'; x.moveTo(2, 18); x.lineTo(10, 4); x.lineTo(18, 18); });
          strokeAt('round join', function (x) { x.lineWidth = 6; x.lineJoin = 'round'; x.moveTo(2, 18); x.lineTo(10, 4); x.lineTo(18, 18); });
          strokeAt('miter limit', function (x) { x.lineWidth = 6; x.miterLimit = 1; x.moveTo(2, 18); x.lineTo(10, 4); x.lineTo(18, 18); });
          strokeAt('dash', function (x) { x.lineWidth = 4; x.setLineDash([4, 4]); x.moveTo(0, 10); x.lineTo(20, 10); });
          strokeAt('dash offset', function (x) { x.lineWidth = 4; x.setLineDash([4, 4]); x.lineDashOffset = 2; x.moveTo(0, 10); x.lineTo(20, 10); });
          strokeAt('closed rect', function (x) { x.lineWidth = 2; x.rect(4, 4, 12, 12); });
          strokeAt('scaled', function (x) { x.scale(1, 3); x.lineWidth = 2; x.moveTo(2, 3); x.lineTo(18, 3); });
          strokeAt('arc', function (x) { x.lineWidth = 3; x.arc(10, 10, 6, 0, 2 * Math.PI); });
          strokeAt('zero length round', function (x) { x.lineWidth = 8; x.lineCap = 'round'; x.moveTo(10, 10); x.lineTo(10, 10); });
          strokeAt('zero length butt', function (x) { x.lineWidth = 8; x.moveTo(10, 10); x.lineTo(10, 10); });
        });

        step('path2d', function () {
          tryit('empty', function () { var p = new Path2D(); var y = mk().getContext('2d'); return y.isPointInPath(p, 1, 1); });
          tryit('svg', function () { var p = new Path2D('M2 2 h16 v16 h-16 z'); var y = mk().getContext('2d'); return grid(function (a, b) { return y.isPointInPath(p, a, b); }); });
          tryit('svg arcs', function () { var p = new Path2D('M2 10 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0'); var y = mk().getContext('2d'); return grid(function (a, b) { return y.isPointInPath(p, a, b); }); });
          tryit('svg bad tail', function () { var p = new Path2D('M2 2 L18 2 L18 18 Z L bogus'); var y = mk().getContext('2d'); return grid(function (a, b) { return y.isPointInPath(p, a, b); }); });
          tryit('svg bad', function () { var p = new Path2D('bogus'); var y = mk().getContext('2d'); return y.isPointInPath(p, 1, 1); });
          tryit('copy', function () { var p = new Path2D(); p.rect(2, 2, 6, 6); var q = new Path2D(p); p.rect(10, 10, 6, 6); var y = mk().getContext('2d'); return y.isPointInPath(q, 3, 3) + ',' + y.isPointInPath(q, 11, 11); });
          tryit('addPath', function () { var p = new Path2D(); p.rect(0, 0, 4, 4); var q = new Path2D(); q.addPath(p, { e: 10, f: 10 }); q.addPath(p); var y = mk().getContext('2d'); return grid(function (a, b) { return y.isPointInPath(q, a, b); }); });
          tryit('addPath bad', function () { new Path2D().addPath({}); });
          tryit('addPath matrix bad', function () { new Path2D().addPath(new Path2D(), { a: 1, m11: 2 }); });
          tryit('used with transform', function () { var p = new Path2D(); p.rect(0, 0, 5, 5); var y = mk().getContext('2d'); y.translate(10, 10); return y.isPointInPath(p, 1, 1) + ',' + y.isPointInPath(p, 11, 11); });
          tryit('stroke', function () { var p = new Path2D(); p.moveTo(0, 10); p.lineTo(20, 10); var y = mk().getContext('2d'); y.lineWidth = 4; return grid(function (a, b) { return y.isPointInStroke(p, a, b); }); });
          tryit('context path kept', function () { var y = mk().getContext('2d'); y.rect(0, 0, 5, 5); y.fill(new Path2D()); return y.isPointInPath(1, 1); });
          tryit('fill rule arg', function () { var y = mk().getContext('2d'); y.fill('bogus'); });
          tryit('fill path rule arg', function () { var y = mk().getContext('2d'); y.fill(new Path2D(), 'bogus'); });
          tryit('fill not path', function () { var y = mk().getContext('2d'); y.fill({}); });
          tryit('clip rule', function () { var y = mk().getContext('2d'); y.clip('bogus'); });
          tryit('stroke not path', function () { var y = mk().getContext('2d'); y.stroke(5); });
        });

        step('text', function () {
          var x = mk().getContext('2d');
          tryit('metrics keys', function () { var m = x.measureText('Hello'); return Object.keys(m).length + ' ' + typeof m.width + ' ' + (m.width > 0) + ' ' + (m.actualBoundingBoxAscent > 0) + ' ' + (m.fontBoundingBoxAscent > 0) + ' ' + r(m.alphabeticBaseline) + ' ' + (m.ideographicBaseline <= 0); });
          tryit('empty', function () { var m = x.measureText(''); return r(m.width) + ' ' + r(m.actualBoundingBoxLeft) + ' ' + r(m.actualBoundingBoxRight); });
          tryit('few', function () { return x.measureText(); });
          tryit('fillText few', function () { x.fillText('a', 1); });
          tryit('fillText ok', function () { x.fillText('a', 1, 2); x.fillText('a', 1, 2, 0); x.fillText('a', NaN, 2); x.fillText('a', 1, 2, -1); x.strokeText('b', 1, 2, 5); return 'ok'; });
          tryit('fillText nonstring', function () { x.fillText(5, 1, 2); x.fillText(null, 1, 2); return 'ok'; });
          tryit('width scales', function () { var a = x.measureText('Hello').width; x.font = '20px sans-serif'; var b = x.measureText('Hello').width; return r(b / a); });
          tryit('transform ignored', function () { x.font = '10px sans-serif'; var a = x.measureText('Hello').width; x.scale(3, 3); return r(x.measureText('Hello').width / a); });
          tryit('spaces', function () { x.font = '10px sans-serif'; return r(x.measureText('a  b').width - x.measureText('a b').width) > 0; });
        });

        step('draw calls', function () {
          var x = mk().getContext('2d');
          tryit('rects', function () { x.fillRect(0, 0, 1, 1); x.strokeRect(0, 0, 1, 1); x.clearRect(0, 0, 1, 1); x.fillRect(NaN, 0, 1, 1); x.fillRect(0, 0, 0, 0); x.strokeRect(0, 0, 0, 5); return 'ok'; });
          tryit('rects few', function () { x.fillRect(0, 0, 1); });
          tryit('rect keeps path', function () { var y = mk().getContext('2d'); y.rect(0, 0, 5, 5); y.fillRect(10, 10, 5, 5); y.clearRect(0, 0, 300, 150); return y.isPointInPath(1, 1) + ',' + y.isPointInPath(11, 11); });
          tryit('focus', function () { x.drawFocusIfNeeded(document.body); return 'ok'; });
          tryit('focus bad', function () { x.drawFocusIfNeeded({}); });
        });
        setTimeout(next, 0);
    """.trimIndent()

    private val chromium = listOf(
        """== shape""",
        """ctors CanvasRenderingContext2D0 Path2D0 CanvasGradient0 CanvasPattern0 TextMetrics0""",
        """context names arc,arcTo,beginPath,bezierCurveTo,canvas,clearRect,clip,closePath,constructor,createConicGradient,createLinearGradient,createPattern,createRadialGradient,direction,drawFocusIfNeeded,drawImage,ellipse,fill,fillRect,fillStyle,fillText,filter,font,fontKerning,fontStretch,fontVariantCaps,getContextAttributes,getLineDash,getTransform,globalAlpha,globalCompositeOperation,imageSmoothingEnabled,imageSmoothingQuality,isContextLost,isPointInPath,isPointInStroke,lang,letterSpacing,lineCap,lineDashOffset,lineJoin,lineTo,lineWidth,measureText,miterLimit,moveTo,quadraticCurveTo,rect,reset,resetTransform,restore,rotate,roundRect,save,scale,setLineDash,setTransform,shadowBlur,shadowColor,shadowOffsetX,shadowOffsetY,stroke,strokeRect,strokeStyle,strokeText,textAlign,textBaseline,textRendering,transform,translate,wordSpacing""",
        """path names addPath,arc,arcTo,bezierCurveTo,closePath,constructor,ellipse,lineTo,moveTo,quadraticCurveTo,rect,roundRect""",
        """gradient names addColorStop,constructor | constructor,setTransform""",
        """metrics names actualBoundingBoxAscent,actualBoundingBoxDescent,actualBoundingBoxLeft,actualBoundingBoxRight,alphabeticBaseline,constructor,fontBoundingBoxAscent,fontBoundingBoxDescent,hangingBaseline,ideographicBaseline,width""",
        """canvas names getContext:true,width:true,height:true""",
        """tags [object CanvasRenderingContext2D],[object CanvasGradient],[object Path2D],[object TextMetrics]""",
        """new context threw TypeError: Failed to construct 'CanvasRenderingContext2D': Illegal constructor""",
        """new gradient threw TypeError: Failed to construct 'CanvasGradient': Illegal constructor""",
        """new metrics threw TypeError: Failed to construct 'TextMetrics': Illegal constructor""",
        """getter on proto threw TypeError: Illegal invocation""",
        """== context""",
        """size 300x150 attr=null""",
        """same true""",
        """canvas true""",
        """other kinds null,null,null""",
        """after 2d null,null""",
        """no args threw TypeError: Failed to execute 'getContext' on 'HTMLCanvasElement': 1 argument required, but only 0 present.""",
        """attributes {alpha:true,colorSpace:"srgb",colorType:"unorm8",desynchronized:false,toneMapping:{mode:"standard"},willReadFrequently:false}""",
        """attributes alpha {alpha:false,colorSpace:"srgb",colorType:"unorm8",desynchronized:true,toneMapping:{mode:"standard"},willReadFrequently:true}""",
        """lost false""",
        """width set 300 300""",
        """width attr 40x150""",
        """width big 300""",
        """width neg attr 300""",
        """== defaults""",
        """defaults fillStyle=#000000 strokeStyle=#000000 lineWidth=1 lineCap=butt lineJoin=miter miterLimit=10 lineDashOffset=0 globalAlpha=1 globalCompositeOperation=source-over shadowBlur=0 shadowColor=rgba(0, 0, 0, 0) shadowOffsetX=0 shadowOffsetY=0 font=10px sans-serif textAlign=start textBaseline=alphabetic direction=ltr filter=none imageSmoothingEnabled=true imageSmoothingQuality=low letterSpacing=0px wordSpacing=0px fontKerning=auto fontStretch=normal fontVariantCaps=normal textRendering=auto lang=inherit dash=""",
        """transform [1,0,0,1,0,0]""",
        """== setters""",
        """lineWidth 2.5->2.5 | 0->1 | -1->1 | NaN->1 | Infinity->1 | "3"->3 | null->1 | ""->1""",
        """miterLimit 4->4 | 0->10 | -2->10 | NaN->10""",
        """lineCap "round"->"round" | "square"->"square" | "butt"->"butt" | "ROUND"->"butt" | "bogus"->"butt" | null->"butt"""",
        """lineJoin "round"->"round" | "bevel"->"bevel" | "miter"->"miter" | "Bevel"->"miter" | ""->"miter"""",
        """lineDashOffset 3->3 | -3->-3 | NaN->0 | Infinity->0""",
        """globalAlpha 0.5->0.5 | 0->0 | 1->1 | 1.5->1 | -0.1->1 | NaN->1 | "0.25"->0.25""",
        """composite "source-in"->"source-in" | "source-out"->"source-out" | "source-atop"->"source-atop" | "destination-over"->"destination-over" | "destination-in"->"destination-in" | "destination-out"->"destination-out" | "destination-atop"->"destination-atop" | "lighter"->"lighter" | "copy"->"copy" | "xor"->"xor" | "multiply"->"multiply" | "screen"->"screen" | "overlay"->"overlay" | "darken"->"darken" | "lighten"->"lighten" | "color-dodge"->"color-dodge" | "color-burn"->"color-burn" | "hard-light"->"hard-light" | "soft-light"->"soft-light" | "difference"->"difference" | "exclusion"->"exclusion" | "hue"->"hue" | "saturation"->"saturation" | "color"->"color" | "luminosity"->"luminosity" | "plus-lighter"->"source-over" | "normal"->"source-over" | "darker"->"source-over" | "Multiply"->"source-over" | ""->"source-over"""",
        """shadowBlur 2->2 | 0->0 | -1->0 | NaN->0""",
        """shadowOffsetX 2->2 | -3->-3 | NaN->0 | Infinity->0""",
        """textAlign "left"->"left" | "right"->"right" | "center"->"center" | "end"->"end" | "start"->"start" | "middle"->"start" | "LEFT"->"start"""",
        """textBaseline "top"->"top" | "hanging"->"hanging" | "middle"->"middle" | "ideographic"->"ideographic" | "bottom"->"bottom" | "alphabetic"->"alphabetic" | "center"->"alphabetic"""",
        """direction "rtl"->"rtl" | "ltr"->"ltr" | "inherit"->"ltr" | "auto"->"ltr"""",
        """smoothing false->false | 0->false | ""->false | "x"->true""",
        """quality "medium"->"medium" | "high"->"high" | "low"->"low" | "best"->"low"""",
        """filter "blur(2px)"->"blur(2px)" | "blur( 2px )"->"blur( 2px )" | "grayscale(50%) blur(1px)"->"grayscale(50%) blur(1px)" | "none"->"none" | "bogus"->"none" | "blur(-1px)"->"none" | "url(#f)"->"url(#f)" | "drop-shadow(1px 2px 3px red)"->"drop-shadow(1px 2px 3px red)" | ""->"none"""",
        """letterSpacing "2px"->"2px" | "1em"->"1em" | "0px"->"0px" | "-1px"->"-1px" | "2"->"0px" | "bogus"->"0px" | "1.5pt"->"1.5pt"""",
        """wordSpacing "3px"->"3px" | "normal"->"0px"""",
        """fontKerning "normal"->"normal" | "none"->"none" | "bogus"->"auto"""",
        """fontStretch "condensed"->"condensed" | "ultra-expanded"->"ultra-expanded" | "Condensed"->"normal" | "50%"->"normal"""",
        """fontVariantCaps "small-caps"->"small-caps" | "all-small-caps"->"all-small-caps" | "petite-caps"->"petite-caps" | "all-petite-caps"->"all-petite-caps" | "unicase"->"unicase" | "titling-caps"->"titling-caps" | "bogus"->"normal"""",
        """textRendering "optimizeSpeed"->"optimizeSpeed" | "optimizeLegibility"->"optimizeLegibility" | "geometricPrecision"->"geometricPrecision" | "auto"->"auto" | "optimizespeed"->"auto"""",
        """lang "en"->"en" | "bogus value"->"bogus value" | ""->"" | "inherit"->"inherit"""",
        """dash 1,2,3,1,2,3 | 1,2,3,1,2,3 | 1,2,3,1,2,3 | 1,2,3,1,2,3 | 0,0 | 5,6 | ; | 7,7 fresh=true""",
        """dash bad threw TypeError: Failed to execute 'setLineDash' on 'CanvasRenderingContext2D': The provided value cannot be converted to a sequence.""",
        """dash none threw TypeError: Failed to execute 'setLineDash' on 'CanvasRenderingContext2D': 1 argument required, but only 0 present.""",
        """== colors""",
        """fill "red"->"#ff0000" | "RED"->"#ff0000" | "#0f0"->"#00ff00" | "#00ff0080"->"rgba(0, 255, 0, 0.5)" | "#abcd"->"rgba(170, 187, 204, 0.867)" | "rgb(1,2,3)"->"#010203" | "rgba(1,2,3,0.5)"->"rgba(1, 2, 3, 0.5)" | "rgba(1, 2, 3, 1)"->"#010203" | "rgb(10% 20% 30% / 40%)"->"rgba(26, 51, 77, 0.4)" | "rgba(1,2,3,0.123456)"->"rgba(1, 2, 3, 0.12)" | "rgba(1,2,3,0.001)"->"rgba(1, 2, 3, 0)" | "hsl(120, 100%, 50%)"->"#00ff00" | "hsla(0, 100%, 50%, 0.3)"->"rgba(255, 0, 0, 0.3)" | "transparent"->"rgba(0, 0, 0, 0)" | "TransParent"->"rgba(0, 0, 0, 0)" | "currentcolor"->"#000000" | "currentColor"->"#000000" | "bogus"->"#000000" | ""->"#000000" | " red "->"#ff0000" | "rgb(300, -1, 2.6)"->"#ff0003" | "hwb(0 0% 0%)"->"#ff0000" | "rebeccapurple"->"#663399" | "inherit"->"#000000" | "initial"->"#000000" | "#12345"->"#000000" | "rgb(1 2 3)"->"#010203" | "rgb(0.5, 1.5, 254.5)"->"#0102ff" | "none"->"#000000"""",
        """stroke "blue"->"#0000ff" | "rgba(0,0,0,0)"->"rgba(0, 0, 0, 0)" | "bogus"->"#000000"""",
        """shadowColor "red"->"#ff0000" | "rgba(0,0,0,0.5)"->"rgba(0, 0, 0, 0.5)" | "bogus"->"rgba(0, 0, 0, 0)" | "currentcolor"->"#000000"""",
        """non string #000000,#000000,#000000,#ff0000""",
        """current in red #ff0000""",
        """== fonts""",
        """font "10px sans-serif"->"10px sans-serif" | "bold 16px serif"->"bold 16px serif" | "12pt Arial"->"16px Arial" | "italic small-caps bold 20px/2 \"Times New Roman\", serif"->"italic bold small-caps 20px \"Times New Roman\", serif" | "2em serif"->"20px serif" | "50% serif"->"5px serif" | "bogus"->"10px sans-serif" | "16px"->"10px sans-serif" | "bold"->"10px sans-serif" | "700 10px serif"->"bold 10px serif" | "normal normal 400 12px serif"->"12px serif" | "oblique 10px a"->"italic 10px a" | "larger serif"->"12px serif" | "small serif"->"13px serif" | "xx-large serif"->"32px serif" | "10px  Arial ,  serif"->"10px Arial, serif" | "condensed 10px serif"->"10px serif" | "10px \"a b\""->"10px \"a b\"" | "10px inherit"->"10px sans-serif" | "inherit"->"10px sans-serif" | "10px var(--x)"->"10px sans-serif" | "caption"->"16px Arial" | "menu"->"16px Arial" | "lighter 10px serif"->"100 10px serif" | "1e1px serif"->"10px serif" | "-1px serif"->"10px sans-serif" | "0px serif"->"0px serif" | "10.5px serif"->"10.5px serif" | "italic 400 normal 10px serif"->"italic 10px serif" | "10px monospace"->"10px monospace"""",
        """font in 20px 40px serif | 10px serif | 24px serif""",
        """== transform""",
        """steps [1,0,0,1,10,20] [2,0,0,3,10,20] [1.224646799e-16,3,-2,1.836970199e-16,10,20] [-4,3,-8,9,-2,35] [-4,3,-8,9,-2,35] [-4,3,-8,9,-2,35] [1,0,0,1,7,8] [2,0,0,2,0,0] [3,0,0,4,5,0] [1,2,3,4,5,6] [1,0,0,1,0,0] [1,0,0,1,0,0] [1,0,0,1,0,0] [0.8775825619,0.4794255386,-0.4794255386,0.8775825619,0,0] [0,0,0,0,0,0]""",
        """set 3d [1,0,0,1,0,0]""",
        """set 3d is2D [1,0,0,1,0,0]""",
        """set mismatch threw TypeError: Failed to execute 'setTransform' on 'CanvasRenderingContext2D': Property mismatch on matrix initialization.""",
        """set few threw TypeError: Failed to execute 'setTransform' on 'CanvasRenderingContext2D': The provided value is not of type 'DOMMatrixInit'.""",
        """getTransform fresh [1,0,0,1,0,0] true [object DOMMatrix]""",
        """transform few threw TypeError: Failed to execute 'transform' on 'CanvasRenderingContext2D': 6 arguments required, but only 3 present.""",
        """scale one threw TypeError: Failed to execute 'scale' on 'CanvasRenderingContext2D': 2 arguments required, but only 1 present.""",
        """== save restore""",
        """stack #0000ff 7 [2,0,0,2,5,5] 3,3 20px serif 0.25 | #ff0000 3 [1,0,0,1,5,5] 1,2 bold 12px serif 0.5 0 start | #ff0000 [1,0,0,1,5,5]""",
        """path survives restore false,true""",
        """reset #000000 [1,0,0,1,0,0] false""",
        """width resets #000000 [1,0,0,1,0,0]  false""",
        """height attr resets #000000""",
        """other attr keeps #ff0000""",
        """remove width resets #000000 300""",
        """== gradients""",
        """linear nan threw TypeError: Failed to execute 'createLinearGradient' on 'CanvasRenderingContext2D': The provided double value is non-finite.""",
        """linear inf threw TypeError: Failed to execute 'createLinearGradient' on 'CanvasRenderingContext2D': The provided double value is non-finite.""",
        """linear few threw TypeError: Failed to execute 'createLinearGradient' on 'CanvasRenderingContext2D': 4 arguments required, but only 3 present.""",
        """radial negative threw IndexSizeError: Failed to execute 'createRadialGradient' on 'CanvasRenderingContext2D': The r0 provided is less than 0.""",
        """radial nan threw TypeError: Failed to execute 'createRadialGradient' on 'CanvasRenderingContext2D': The provided double value is non-finite.""",
        """conic [object CanvasGradient]""",
        """conic nan threw TypeError: Failed to execute 'createConicGradient' on 'CanvasRenderingContext2D': The provided double value is non-finite.""",
        """stop ok ok""",
        """stop range threw IndexSizeError: Failed to execute 'addColorStop' on 'CanvasGradient': The provided value (1.5) is outside the range (0.0, 1.0).""",
        """stop negative threw IndexSizeError: Failed to execute 'addColorStop' on 'CanvasGradient': The provided value (-0.1) is outside the range (0.0, 1.0).""",
        """stop nan threw TypeError: Failed to execute 'addColorStop' on 'CanvasGradient': The provided double value is non-finite.""",
        """stop color threw SyntaxError: Failed to execute 'addColorStop' on 'CanvasGradient': The value provided ('bogus') could not be parsed as a color.""",
        """stop current ok""",
        """stop few threw TypeError: Failed to execute 'addColorStop' on 'CanvasGradient': 2 arguments required, but only 1 present.""",
        """as style true [object CanvasGradient]""",
        """style then color #ff0000""",
        """saved object true""",
        """== patterns""",
        """canvas [object CanvasPattern]""",
        """null rep [object CanvasPattern]""",
        """empty rep [object CanvasPattern]""",
        """reps [object CanvasPattern],[object CanvasPattern],[object CanvasPattern]""",
        """bad rep threw SyntaxError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': The provided type ('bogus') is not one of 'repeat', 'no-repeat', 'repeat-x', or 'repeat-y'.""",
        """upper rep threw SyntaxError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': The provided type ('REPEAT') is not one of 'repeat', 'no-repeat', 'repeat-x', or 'repeat-y'.""",
        """zero canvas threw InvalidStateError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': The image argument is a canvas element with a width or height of 0.""",
        """not image threw TypeError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': The provided value is not of type '(CSSImageValue or HTMLCanvasElement or HTMLImageElement or HTMLVideoElement or ImageBitmap or OffscreenCanvas or SVGImageElement or VideoFrame)'.""",
        """null image threw TypeError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': The provided value is not of type '(CSSImageValue or HTMLCanvasElement or HTMLImageElement or HTMLVideoElement or ImageBitmap or OffscreenCanvas or SVGImageElement or VideoFrame)'.""",
        """unloaded image null""",
        """set transform ok""",
        """set transform bad threw TypeError: Failed to execute 'setTransform' on 'CanvasPattern': Property mismatch on matrix initialization.""",
        """== drawImage args""",
        """null threw TypeError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': The provided value is not of type '(CSSImageValue or HTMLCanvasElement or HTMLImageElement or HTMLVideoElement or ImageBitmap or OffscreenCanvas or SVGImageElement or VideoFrame)'.""",
        """object threw TypeError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': The provided value is not of type '(CSSImageValue or HTMLCanvasElement or HTMLImageElement or HTMLVideoElement or ImageBitmap or OffscreenCanvas or SVGImageElement or VideoFrame)'.""",
        """few threw TypeError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': 3 arguments required, but only 1 present.""",
        """four threw TypeError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': Overload resolution failed.""",
        """zero canvas threw InvalidStateError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': The image argument is a canvas element with a width or height of 0.""",
        """unloaded ok""",
        """broken ok""",
        """nan ok""",
        """nine ok""",
        """svg image ok""",
        """video ok""",
        """== paths""",
        """rect .........../.........../..#####..../..#####..../..#####..../.........../.........../.........../.........../.........../...........""",
        """rect negative .........../.........../..#####..../..#####..../..#####..../.........../.........../.........../.........../.........../...........""",
        """arc .........../.........../...####..../..######.../..#######../..#######../..#######../...#####.../....###..../.........../...........""",
        """arc half .........../.........../.........../.........../.........../.########../.########../..#######../...#####.../.........../...........""",
        """arc ccw .........../....###..../..######.../..#######../.########../.########../.#######.../..#####..../...###...../.........../...........""",
        """arc over .........../....###..../..######.../..#######../.########../.########../.########../..#######../...#####.../.........../...........""",
        """arc ccw over .........../....###..../..######.../..#######../.########../.########../.########../..#######../...#####.../.........../...........""",
        """arc negative .........../....###..../..######.../..#######../.########../.########../.#######.../..#####..../...###...../.........../...........""",
        """arc line #........../.#........./.###......./..###....../..#####..../...#####.../...####..../....##...../.........../.........../...........""",
        """arc radius threw IndexSizeError: Failed to execute 'arc' on 'CanvasRenderingContext2D': The radius provided (-1) is negative.""",
        """arc nan ok""",
        """ellipse .........../.........../..###....../..####...../..#####..../...#####.../....#####../.....####../......##.../.........../...........""",
        """ellipse part .........../.........../.........../.........../.........../.........../.........../....#####../.........../.........../...........""",
        """ellipse radius threw IndexSizeError: Failed to execute 'ellipse' on 'CanvasRenderingContext2D': The minor-axis radius provided (-1) is negative.""",
        """arcTo .........../.######..../.#######.../.########../.########../.########../.########../.########../.########../.........../...........""",
        """arcTo collinear .........../.####....../..###....../..###....../...##....../...##....../....#....../....#....../.........../.........../...........""",
        """arcTo zero .........../.########../.#######.../.######..../.#####...../.####....../.###......./.##......../.#........./.........../...........""",
        """arcTo empty .........../.########../.#######.../.######..../.#####...../.####....../.###......./.##......../.#........./.........../...........""",
        """arcTo radius threw IndexSizeError: Failed to execute 'arcTo' on 'CanvasRenderingContext2D': The radius provided (-1) is negative.""",
        """bezier ...#####.../..######.../..######.../..#######../..#######../.########../.########../.########../.########../.........../...........""",
        """quadratic .........../.........../....##...../....###..../...#####.../...#####.../..######.../..#######../.########../.........../...........""",
        """curve no start .........../.##......../.#####...../.######..../.#######.../.#######.../.########../.########../.########../.........../...........""",
        """lineTo no start .........../.########../..#######../...######../....#####../.....####../......###../.......##../........#../.........../...........""",
        """roundRect .........../...####..../..#######../.########../.########../.########../.########../..#######../..######.../.........../...........""",
        """roundRect list .........../.....####../...######../..#######../..#######../.########../.########../.########../.########../.........../...........""",
        """roundRect point .........../...####..../.########../.########../.########../.########../.########../.########../..#######../.........../...........""",
        """roundRect big .........../..######.../.########../.########../..#######../.........../.........../.........../.........../.........../...........""",
        """roundRect negative .........../....#####../..#######../..#######../.########../.########../.########../.########../.#######.../.........../...........""",
        """roundRect radii threw RangeError: Failed to execute 'roundRect' on 'CanvasRenderingContext2D': 5 radii provided. Between one and four radii are necessary.""",
        """roundRect empty threw RangeError: Failed to execute 'roundRect' on 'CanvasRenderingContext2D': 0 radii provided. Between one and four radii are necessary.""",
        """roundRect negative radius threw RangeError: Failed to execute 'roundRect' on 'CanvasRenderingContext2D': Radius value -1 is negative.""",
        """roundRect none true""",
        """evenodd .........../.########../.########../.##....##../.##....##../.##....##../.##....##../.########../.########../.........../...........""",
        """nonzero .........../.########../.########../.########../.########../.########../.########../.########../.########../.........../...........""",
        """winding .........../.########../.########../.##....##../.##....##../.##....##../.##....##../.########../.########../.........../...........""",
        """transformed .....#...../....###..../...#####.../..#######../..######.../...####..../....##...../.........../.........../.........../...........""",
        """transform after ###......../###......../###......../.........../.........../.........../.........../.........../.........../.........../...........""",
        """scaled arc .........../.........../.........../...####..../..#######../.########../..######.../.........../.........../.........../...........""",
        """closePath .........../.########../..#######../..######.../...#####.../...####..../....###..../....##...../.....#...../.........../...........""",
        """moveTo only .........../.........../.........../.........../.........../.........../.........../.........../.........../.........../...........""",
        """nan point .........../.########../..#######../...######../....#####../.....####../......###../.......##../........#../.........../...........""",
        """beginPath .........../.........../.........../...##....../...##....../.........../.........../.........../.........../.........../...........""",
        """point transform true,false""",
        """point nan false,false""",
        """point edge true,true,true,true,true""",
        """point bad rule threw TypeError: Failed to execute 'isPointInPath' on 'CanvasRenderingContext2D': The provided value 'bogus' is not a valid enum value of type CanvasFillRule.""",
        """point few threw TypeError: Failed to execute 'isPointInPath' on 'CanvasRenderingContext2D': 2 arguments required, but only 1 present.""",
        """== strokes""",
        """line .........../.........../.........../.........../.########../.########../.........../.........../.........../.........../...........""",
        """butt .........../.........../.........../.........../...####..../...####..../...####..../.........../.........../.........../...........""",
        """square .........../.........../.........../.........../..#######../..#######../..#######../.........../.........../.........../...........""",
        """round .........../.........../.........../..######.../.########../.########../..#######../.........../.........../.........../...........""",
        """miter .....#...../....###..../...####..../...#####.../..######.../..#######../.####.###../.###...###./###....####/..#.....#../...........""",
        """bevel .........../.........../...####..../...#####.../..######.../..#######../.####.###../.###...###./###....####/..#.....#../...........""",
        """round join .........../....###..../...####..../...#####.../..######.../..#######../.####.###../.###...###./###....####/..#.....#../...........""",
        """miter limit .........../.........../...####..../...#####.../..######.../..#######../.####.###../.###...###./###....####/..#.....#../...........""",
        """dash .........../.........../.........../.........../##..##..##./##..##..##./.........../.........../.........../.........../...........""",
        """dash offset .........../.........../.........../.........../#..##..##../#..##..##../.........../.........../.........../.........../...........""",
        """closed rect .........../.........../..#######../..#.....#../..#.....#../..#.....#../..#.....#../..#.....#../..#######../.........../...........""",
        """scaled .........../.........../.........../.########../.########../.########../.........../.........../.........../.........../...........""",
        """arc .........../.........../...#####.../..##...##../..#....##../..#....##../..#....##../..######.../...####..../.........../...........""",
        """zero length round .........../.........../.........../....##...../...####..../...####..../....###..../.........../.........../.........../...........""",
        """zero length butt .........../.........../.........../.........../.........../.........../.........../.........../.........../.........../...........""",
        """== path2d""",
        """empty false""",
        """svg .........../.########../.########../.########../.########../.########../.########../.########../.########../.........../...........""",
        """svg arcs .........../....###..../..######.../..#######../.########../.########../.########../..#######../...#####.../.........../...........""",
        """svg bad tail .........../.########../..#######../...######../....#####../.....####../......###../.......##../........#../.........../...........""",
        """svg bad false""",
        """copy true,false""",
        """addPath ##........./##........./.........../.........../.........../.....##..../.....##..../.........../.........../.........../...........""",
        """addPath bad threw TypeError: Failed to execute 'addPath' on 'Path2D': parameter 1 is not of type 'Path2D'.""",
        """addPath matrix bad threw TypeError: Failed to execute 'addPath' on 'Path2D': Property mismatch on matrix initialization.""",
        """used with transform false,true""",
        """stroke .........../.........../.........../.........../##########./##########./.........../.........../.........../.........../...........""",
        """context path kept true""",
        """fill rule arg threw TypeError: Failed to execute 'fill' on 'CanvasRenderingContext2D': The provided value 'bogus' is not a valid enum value of type CanvasFillRule.""",
        """fill path rule arg threw TypeError: Failed to execute 'fill' on 'CanvasRenderingContext2D': The provided value 'bogus' is not a valid enum value of type CanvasFillRule.""",
        """fill not path threw TypeError: Failed to execute 'fill' on 'CanvasRenderingContext2D': The provided value '[object Object]' is not a valid enum value of type CanvasFillRule.""",
        """clip rule threw TypeError: Failed to execute 'clip' on 'CanvasRenderingContext2D': The provided value 'bogus' is not a valid enum value of type CanvasFillRule.""",
        """stroke not path threw TypeError: Failed to execute 'stroke' on 'CanvasRenderingContext2D': parameter 1 is not of type 'Path2D'.""",
        """== text""",
        """metrics keys 0 number true true true 0 true""",
        """empty 0 0 0""",
        """few threw TypeError: Failed to execute 'measureText' on 'CanvasRenderingContext2D': 1 argument required, but only 0 present.""",
        """fillText few threw TypeError: Failed to execute 'fillText' on 'CanvasRenderingContext2D': 3 arguments required, but only 2 present.""",
        """fillText ok ok""",
        """fillText nonstring ok""",
        """width scales 2""",
        """transform ignored 1""",
        """spaces true""",
        """== draw calls""",
        """rects ok""",
        """rects few threw TypeError: Failed to execute 'fillRect' on 'CanvasRenderingContext2D': 4 arguments required, but only 3 present.""",
        """rect keeps path true,false""",
        """focus ok""",
        """focus bad threw TypeError: Failed to execute 'drawFocusIfNeeded' on 'CanvasRenderingContext2D': parameter 1 is not of type 'Element'.""",
        """done""",
    )

    private fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("C501") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_context_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_context_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
