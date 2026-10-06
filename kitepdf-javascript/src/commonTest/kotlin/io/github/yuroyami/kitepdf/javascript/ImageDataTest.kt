package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * `ImageData`, `createImageData`, `getImageData`, `putImageData`, `toDataURL` and `toBlob` in a book's
 * scripts, against what Chromium logs for the same script (#610).
 */
class ImageDataTest {

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
          if (!s) { log('done'); console.log('C610\n' + out.join('\n')); return; }
          log('== ' + s[0]);
          var p;
          try { p = s[1](); } catch (e) { log('step threw ' + err(e)); }
          Promise.resolve(p).then(function () { setTimeout(next, 0); }, function (e) { log('step rejected ' + err(e)); setTimeout(next, 0); });
        }
        function mk(w, h) { var c = document.createElement('canvas'); if (w !== undefined) { c.width = w; c.height = h; } return c; }
        function px(d, i) { return Array.prototype.slice.call(d.data, i * 4, i * 4 + 4).join(','); }
        function all(d) { var s = []; for (var i = 0; i < d.width * d.height; i++) s.push(px(d, i)); return s.join(' '); }

        step('shape', function () {
          tryit('ctor', function () { return ImageData.name + ImageData.length; });
          tryit('names', function () { return names(ImageData.prototype); });
          tryit('canvas names', function () { return ['toDataURL', 'toBlob'].map(function (n) { return n + ':' + typeof HTMLCanvasElement.prototype[n]; }).join(); });
          tryit('tag', function () { return Object.prototype.toString.call(new ImageData(1, 1)); });
        });

        step('constructor', function () {
          tryit('2x3', function () { var d = new ImageData(2, 3); return d.width + 'x' + d.height + ' ' + Object.prototype.toString.call(d.data) + ' ' + d.data.length + ' ' + d.colorSpace + ' ' + (d.data === d.data); });
          tryit('zero', function () { return new ImageData(0, 1); });
          tryit('neg', function () { return new ImageData(-1, 1); });
          tryit('nan', function () { return new ImageData(NaN, 1).width; });
          tryit('frac', function () { var d = new ImageData(2.7, 1.2); return d.width + 'x' + d.height; });
          tryit('one arg', function () { return new ImageData(2); });
          tryit('no new', function () { return ImageData(1, 1); });
          tryit('from data', function () { var a = new Uint8ClampedArray(16); a[0] = 7; var d = new ImageData(a, 2); return d.width + 'x' + d.height + ' ' + (d.data === a) + ' ' + d.data[0]; });
          tryit('from data h', function () { var d = new ImageData(new Uint8ClampedArray(16), 2, 2); return d.width + 'x' + d.height; });
          tryit('from data bad h', function () { return new ImageData(new Uint8ClampedArray(16), 2, 3); });
          tryit('from data bad len', function () { return new ImageData(new Uint8ClampedArray(6), 1); });
          tryit('from data not multiple', function () { return new ImageData(new Uint8ClampedArray(12), 2); });
          tryit('from data empty', function () { return new ImageData(new Uint8ClampedArray(0), 1); });
          tryit('from u8', function () { return new ImageData(new Uint8Array(4), 1); });
          tryit('from array', function () { return new ImageData([0, 0, 0, 0], 1); });
          tryit('settings', function () { return new ImageData(1, 1, { colorSpace: 'display-p3' }).colorSpace; });
          tryit('settings bad', function () { return new ImageData(1, 1, { colorSpace: 'nope' }).colorSpace; });
          tryit('readonly', function () { var d = new ImageData(1, 1); d.width = 9; d.data = null; return d.width + ' ' + (d.data !== null); });
          tryit('clamp', function () { var d = new ImageData(1, 1); d.data[0] = 300; d.data[1] = -5; d.data[2] = 1.5; d.data[3] = 2.5; return px(d, 0); });
        });

        step('create', function () {
          var x = mk(10, 10).getContext('2d');
          tryit('create', function () { var d = x.createImageData(2, 3); return d.width + 'x' + d.height + ' ' + d.data.length + ' ' + px(d, 0); });
          tryit('create neg', function () { var d = x.createImageData(-2, -3); return d.width + 'x' + d.height; });
          tryit('create zero', function () { return x.createImageData(0, 3); });
          tryit('create frac', function () { var d = x.createImageData(2.9, 1.1); return d.width + 'x' + d.height; });
          tryit('create from', function () { var s = new ImageData(3, 2); s.data[0] = 9; var d = x.createImageData(s); return d.width + 'x' + d.height + ' ' + d.data[0] + ' ' + (d.data === s.data); });
          tryit('create none', function () { return x.createImageData(); });
          tryit('create obj', function () { return x.createImageData({}); });
          tryit('create one num', function () { return x.createImageData(3); });
          tryit('create nan', function () { return x.createImageData(NaN, 2); });
          tryit('create inf', function () { return x.createImageData(Infinity, 2); });
        });

        step('get', function () {
          var c = mk(4, 4), x = c.getContext('2d');
          x.fillStyle = 'rgba(255,0,0,0.5)'; x.fillRect(0, 0, 2, 2);
          x.fillStyle = '#00ff00'; x.fillRect(2, 2, 2, 2);
          tryit('all', function () { return all(x.getImageData(0, 0, 4, 4)); });
          tryit('fresh', function () { var a = x.getImageData(0, 0, 1, 1), b = x.getImageData(0, 0, 1, 1); return (a.data === b.data) + ' ' + a.colorSpace; });
          tryit('outside', function () { return all(x.getImageData(-1, -1, 2, 2)); });
          tryit('far', function () { return all(x.getImageData(10, 10, 2, 1)); });
          tryit('neg size', function () { var d = x.getImageData(4, 4, -2, -2); return d.width + 'x' + d.height + ' ' + all(d); });
          tryit('frac', function () { var d = x.getImageData(0.5, 0.5, 1.5, 1.5); return d.width + 'x' + d.height + ' ' + all(d); });
          tryit('frac2', function () { var d = x.getImageData(1.7, 1.7, 1.2, 1); return d.width + 'x' + d.height + ' ' + all(d); });
          tryit('zero w', function () { return x.getImageData(0, 0, 0, 1); });
          tryit('nan', function () { return x.getImageData(NaN, 0, 1, 1); });
          tryit('inf', function () { return x.getImageData(0, 0, Infinity, 1); });
          tryit('three', function () { return x.getImageData(0, 0, 1); });
          tryit('transform ignored', function () { x.setTransform(2, 0, 0, 2, 1, 1); var d = x.getImageData(0, 0, 1, 1); x.resetTransform(); return all(d); });
          tryit('settings', function () { return x.getImageData(0, 0, 1, 1, { colorSpace: 'srgb' }).colorSpace; });
          tryit('empty canvas', function () { return all(mk(2, 1).getContext('2d').getImageData(0, 0, 2, 1)); });
          tryit('alpha 1/255', function () { var y = mk(1, 1).getContext('2d'); y.fillStyle = 'rgba(200,100,50,0.004)'; y.fillRect(0, 0, 1, 1); return all(y.getImageData(0, 0, 1, 1)); });
          tryit('alpha 0.3', function () { var y = mk(1, 1).getContext('2d'); y.fillStyle = 'rgba(200,100,50,0.3)'; y.fillRect(0, 0, 1, 1); return all(y.getImageData(0, 0, 1, 1)); });
          tryit('half pixel', function () { var y = mk(2, 1).getContext('2d'); y.fillStyle = '#000'; y.fillRect(0, 0, 1.5, 1); return all(y.getImageData(0, 0, 2, 1)); });
        });

        step('put', function () {
          function fresh() { var y = mk(3, 3).getContext('2d'); y.fillStyle = '#0000ff'; y.fillRect(0, 0, 3, 3); return y; }
          function src() { var d = new ImageData(2, 2); for (var i = 0; i < 4; i++) { d.data[i * 4] = 10 * (i + 1); d.data[i * 4 + 3] = 255; } d.data[15] = 128; return d; }
          tryit('plain', function () { var y = fresh(); y.putImageData(src(), 1, 1); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('replaces', function () { var y = fresh(); var d = new ImageData(1, 1); y.putImageData(d, 0, 0); return all(y.getImageData(0, 0, 2, 1)); });
          tryit('ignores state', function () { var y = fresh(); y.globalAlpha = 0.5; y.globalCompositeOperation = 'multiply'; y.setTransform(2, 0, 0, 2, 0, 0); y.beginPath(); y.rect(0, 0, 0.5, 0.5); y.clip(); y.shadowColor = 'red'; y.shadowBlur = 3; y.putImageData(src(), 1, 1); y.resetTransform(); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('dirty', function () { var y = fresh(); y.putImageData(src(), 1, 1, 1, 0, 1, 2); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('dirty neg', function () { var y = fresh(); y.putImageData(src(), 0, 0, 2, 2, -1, -1); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('dirty out', function () { var y = fresh(); y.putImageData(src(), 0, 0, -5, -5, 6, 6); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('dirty zero', function () { var y = fresh(); y.putImageData(src(), 0, 0, 0, 0, 0, 2); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('off canvas', function () { var y = fresh(); y.putImageData(src(), -1, 2); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('frac pos', function () { var y = fresh(); y.putImageData(src(), 0.6, 1.4); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('frac dirty', function () { var y = fresh(); y.putImageData(src(), 0, 0, 0.5, 0.5, 1, 1); return all(y.getImageData(0, 0, 3, 3)); });
          tryit('five args', function () { var y = fresh(); y.putImageData(src(), 0, 0, 1, 1); return 'ok'; });
          tryit('two args', function () { fresh().putImageData(src(), 0); });
          tryit('not image', function () { fresh().putImageData({ width: 1, height: 1, data: [1, 2, 3, 4] }, 0, 0); });
          tryit('nan', function () { var y = fresh(); y.putImageData(src(), NaN, 0); return all(y.getImageData(0, 0, 1, 1)); });
          tryit('round trip', function () { var y = mk(2, 1).getContext('2d'); var d = new ImageData(2, 1); d.data.set([200, 100, 50, 128, 255, 255, 255, 1]); y.putImageData(d, 0, 0); return all(y.getImageData(0, 0, 2, 1)); });
          tryit('round trip 2', function () { var y = mk(4, 1).getContext('2d'); var d = new ImageData(4, 1); d.data.set([1, 2, 3, 3, 90, 91, 92, 77, 254, 0, 17, 254, 33, 66, 99, 0]); y.putImageData(d, 0, 0); return all(y.getImageData(0, 0, 4, 1)); });
          tryit('draw over put', function () { var y = mk(1, 1).getContext('2d'); var d = new ImageData(1, 1); d.data.set([255, 0, 0, 255]); y.putImageData(d, 0, 0); y.fillStyle = 'rgba(0,0,255,0.5)'; y.fillRect(0, 0, 1, 1); return all(y.getImageData(0, 0, 1, 1)); });
          tryit('mutate after put', function () { var y = mk(1, 1).getContext('2d'); var d = new ImageData(1, 1); d.data.set([255, 0, 0, 255]); y.putImageData(d, 0, 0); d.data[0] = 0; return all(y.getImageData(0, 0, 1, 1)); });
          tryit('draw canvas', function () { var a = mk(2, 1), b = mk(2, 1).getContext('2d'); var d = new ImageData(2, 1); d.data.set([255, 0, 0, 255, 0, 255, 0, 255]); a.getContext('2d').putImageData(d, 0, 0); b.drawImage(a, 0, 0); return all(b.getImageData(0, 0, 2, 1)); });
          tryit('pattern of put', function () { var a = mk(1, 1); var d = new ImageData(1, 1); d.data.set([0, 0, 255, 255]); a.getContext('2d').putImageData(d, 0, 0); var b = mk(2, 2).getContext('2d'); b.fillStyle = b.createPattern(a, 'repeat'); b.fillRect(0, 0, 2, 2); return all(b.getImageData(0, 0, 2, 2)); });
          tryit('resize clears', function () { var c = mk(1, 1), y = c.getContext('2d'); var d = new ImageData(1, 1); d.data.set([9, 9, 9, 255]); y.putImageData(d, 0, 0); c.width = 1; return all(y.getImageData(0, 0, 1, 1)); });
        });

        step('dataurl', function () {
          tryit('blank', function () { return mk(2, 2).toDataURL().slice(0, 22); });
          tryit('png', function () { var c = mk(2, 2); c.getContext('2d').fillRect(0, 0, 1, 1); return c.toDataURL('image/png').slice(0, 22); });
          tryit('jpeg', function () { return mk(2, 2).toDataURL('image/jpeg').slice(0, 23); });
          tryit('jpeg q', function () { return mk(2, 2).toDataURL('image/jpeg', 0.5).slice(0, 23); });
          tryit('upper', function () { return mk(2, 2).toDataURL('IMAGE/JPEG').slice(0, 23); });
          tryit('unknown', function () { return mk(2, 2).toDataURL('image/bmp').slice(0, 22); });
          tryit('zero', function () { return mk(0, 2).toDataURL(); });
          tryit('zero h', function () { return mk(2, 0).toDataURL('image/jpeg'); });
          tryit('no context', function () { return mk(2, 2).toDataURL().length > 30; });
          tryit('this', function () { return HTMLCanvasElement.prototype.toDataURL.call({}); });
          tryit('png decodes', function () { var c = mk(3, 1); var d = new ImageData(3, 1); d.data.set([255, 0, 0, 255, 0, 255, 0, 128, 0, 0, 0, 0]); c.getContext('2d').putImageData(d, 0, 0); var u = c.toDataURL(); var b = atob(u.slice(22)); return b.charCodeAt(1) + b.slice(1, 4); });
        });

        step('blob', function () {
          tryit('no cb', function () { mk(1, 1).toBlob(); });
          tryit('cb not fn', function () { mk(1, 1).toBlob(5); });
          return new Promise(function (resolve) {
            var c = mk(2, 2);
            c.getContext('2d').fillRect(0, 0, 1, 1);
            var sync = true;
            c.toBlob(function (b) {
              log('png sync=' + sync + ' ' + Object.prototype.toString.call(b) + ' ' + b.type + ' ' + (b.size > 0) + ' args=' + arguments.length);
              mk(2, 2).toBlob(function (j) {
                log('jpeg ' + j.type);
                mk(0, 2).toBlob(function (z) {
                  log('zero ' + z);
                  mk(2, 2).toBlob(function (u) {
                    log('unknown ' + u.type);
                    b.arrayBuffer().then(function (ab) { var u8 = new Uint8Array(ab); log('bytes ' + u8[1] + u8[2] + u8[3] + ' ' + String.fromCharCode(u8[1], u8[2], u8[3])); resolve(); });
                  }, 'image/bmp');
                });
              }, 'image/jpeg', 0.8);
            });
            sync = false;
          });
        });
        step('formats', function () {
          tryit('default fmt', function () { return new ImageData(1, 1).pixelFormat; });
          tryit('f16', function () { var d = new ImageData(1, 1, { pixelFormat: 'rgba-float16' }); return d.pixelFormat + ' ' + Object.prototype.toString.call(d.data) + ' ' + d.data.length; });
          tryit('f16 bad', function () { return new ImageData(1, 1, { pixelFormat: 'x' }); });
          tryit('f16 from u8c', function () { return new ImageData(new Uint8ClampedArray(4), 1, 1, { pixelFormat: 'rgba-float16' }).pixelFormat; });
          tryit('from f16', function () { var d = new ImageData(new Float16Array(4), 1); return d.pixelFormat + ' ' + d.width; });
          tryit('from f32', function () { return new ImageData(new Float32Array(4), 1).pixelFormat; });
          tryit('get f16', function () { var x = mk(1, 1).getContext('2d'); x.fillStyle = 'rgba(255,128,0,0.5)'; x.fillRect(0, 0, 1, 1); var d = x.getImageData(0, 0, 1, 1, { pixelFormat: 'rgba-float16' }); return d.pixelFormat + ' ' + Array.prototype.join.call(d.data, ','); });
          tryit('put f16', function () { var x = mk(1, 1).getContext('2d'); var d = new ImageData(new Float16Array([1, 0.5, 0.25, 1]), 1, 1, { pixelFormat: 'rgba-float16' }); x.putImageData(d, 0, 0); return Array.prototype.join.call(x.getImageData(0, 0, 1, 1).data, ','); });
          tryit('put f16 out', function () { var x = mk(1, 1).getContext('2d'); var d = new ImageData(new Float16Array([2, -1, 0.5, 0.5]), 1, 1, { pixelFormat: 'rgba-float16' }); x.putImageData(d, 0, 0); return Array.prototype.join.call(x.getImageData(0, 0, 1, 1).data, ','); });
          tryit('create f16', function () { var x = mk(1, 1).getContext('2d'); return x.createImageData(1, 1, { pixelFormat: 'rgba-float16' }).pixelFormat + ' ' + x.createImageData(new ImageData(1, 1, { pixelFormat: 'rgba-float16' })).pixelFormat; });
          tryit('create cs', function () { var x = mk(1, 1).getContext('2d'); return x.createImageData(new ImageData(1, 1, { colorSpace: 'display-p3' })).colorSpace + ' ' + x.createImageData(1, 1, { colorSpace: 'display-p3' }).colorSpace; });
          tryit('p3 get', function () { var x = mk(1, 1).getContext('2d'); x.fillStyle = '#ff0000'; x.fillRect(0, 0, 1, 1); return Array.prototype.join.call(x.getImageData(0, 0, 1, 1, { colorSpace: 'display-p3' }).data, ','); });
          tryit('p3 put', function () { var x = mk(1, 1).getContext('2d'); var d = new ImageData(1, 1, { colorSpace: 'display-p3' }); d.data.set([255, 0, 0, 255]); x.putImageData(d, 0, 0); return Array.prototype.join.call(x.getImageData(0, 0, 1, 1).data, ','); });
          tryit('from f16 ok', function () { var d = new ImageData(new Float16Array(8), 2, undefined, { pixelFormat: 'rgba-float16' }); return d.pixelFormat + ' ' + d.width + 'x' + d.height; });
          tryit('f16 round', function () { var x = mk(1, 1).getContext('2d'); x.fillStyle = 'rgba(10,200,77,0.3)'; x.fillRect(0, 0, 1, 1); var d = x.getImageData(0, 0, 1, 1, { pixelFormat: 'rgba-float16' }); return Array.prototype.join.call(d.data, ','); });
          tryit('p3 get 2', function () { var x = mk(3, 1).getContext('2d'); x.fillStyle = '#00ff00'; x.fillRect(0, 0, 1, 1); x.fillStyle = 'rgba(30,60,200,0.5)'; x.fillRect(1, 0, 1, 1); x.fillStyle = '#808080'; x.fillRect(2, 0, 1, 1); return Array.prototype.join.call(x.getImageData(0, 0, 3, 1, { colorSpace: 'display-p3' }).data, ','); });
          tryit('p3 put 2', function () { var x = mk(2, 1).getContext('2d'); var d = new ImageData(2, 1, { colorSpace: 'display-p3' }); d.data.set([234, 51, 35, 255, 100, 150, 200, 128]); x.putImageData(d, 0, 0); return Array.prototype.join.call(x.getImageData(0, 0, 2, 1).data, ','); });
          tryit('p3 ctx', function () { var x = mk(1, 1).getContext('2d', { colorSpace: 'display-p3' }); x.fillStyle = '#ff0000'; x.fillRect(0, 0, 1, 1); var d = x.getImageData(0, 0, 1, 1); return d.colorSpace + ' ' + Array.prototype.join.call(d.data, ',') + ' ' + Array.prototype.join.call(x.getImageData(0, 0, 1, 1, { colorSpace: 'srgb' }).data, ','); });
          tryit('clone', function () { var d = new ImageData(1, 1); d.data[0] = 5; var c = structuredClone(d); return Object.prototype.toString.call(c) + ' ' + (c.data === d.data) + ' ' + c.data[0] + ' ' + c.width + ' ' + (c instanceof ImageData); });
          tryit('clone transfer', function () { var d = new ImageData(1, 1); var c = structuredClone(d, { transfer: [d.data.buffer] }); return d.data.length + ' ' + c.data.length; });
          tryit('data detached', function () { var d = new ImageData(1, 1); structuredClone(d.data.buffer, { transfer: [d.data.buffer] }); var x = mk(1, 1).getContext('2d'); x.putImageData(d, 0, 0); return 'ok'; });
          tryit('get proto', function () { return Object.getOwnPropertyDescriptor(ImageData.prototype, 'data').get.call({}); });
          tryit('large', function () { return mk(1, 1).getContext('2d').getImageData(0, 0, 100000, 100000); });
          tryit('ctx canvas size', function () { var c = mk(3, 2); var d = c.getContext('2d').getImageData(0, 0, 3, 2); c.width = 5; return d.width + ' ' + c.getContext('2d').getImageData(0, 0, 5, 2).width; });
        });
        setTimeout(next, 0);
    """.trimIndent()

    private val chromium = listOf(
        """== shape""",
        """ctor ImageData2""",
        """names colorSpace,constructor,data,height,pixelFormat,width""",
        """canvas names toDataURL:function,toBlob:function""",
        """tag [object ImageData]""",
        """== constructor""",
        """2x3 2x3 [object Uint8ClampedArray] 24 srgb true""",
        """zero threw IndexSizeError: Failed to construct 'ImageData': The source width is zero or not a number.""",
        """neg threw IndexSizeError: Failed to construct 'ImageData': The requested image size exceeds the supported range.""",
        """nan threw IndexSizeError: Failed to construct 'ImageData': The source width is zero or not a number.""",
        """frac 2x1""",
        """one arg threw TypeError: Failed to construct 'ImageData': 2 arguments required, but only 1 present.""",
        """no new threw TypeError: Failed to construct 'ImageData': Please use the 'new' operator, this DOM object constructor cannot be called as a function.""",
        """from data 2x2 true 7""",
        """from data h 2x2""",
        """from data bad h threw IndexSizeError: Failed to construct 'ImageData': The input data length is not equal to (4 * width * height).""",
        """from data bad len threw InvalidStateError: Failed to construct 'ImageData': The input data length is not a multiple of 4.""",
        """from data not multiple threw IndexSizeError: Failed to construct 'ImageData': The input data length is not a multiple of (4 * width).""",
        """from data empty threw InvalidStateError: Failed to construct 'ImageData': The input data has zero elements.""",
        """from u8 threw IndexSizeError: Failed to construct 'ImageData': The source width is zero or not a number.""",
        """from array threw IndexSizeError: Failed to construct 'ImageData': The source width is zero or not a number.""",
        """settings display-p3""",
        """settings bad threw TypeError: Failed to construct 'ImageData': Failed to read the 'colorSpace' property from 'ImageDataSettings': The provided value 'nope' is not a valid enum value of type PredefinedColorSpace.""",
        """readonly 1 true""",
        """clamp 255,0,2,2""",
        """== create""",
        """create 2x3 24 0,0,0,0""",
        """create neg 2x3""",
        """create zero threw IndexSizeError: Failed to execute 'createImageData' on 'CanvasRenderingContext2D': The source width is zero or not a number.""",
        """create frac 2x1""",
        """create from 3x2 0 false""",
        """create none threw TypeError: Failed to execute 'createImageData' on 'CanvasRenderingContext2D': 1 argument required, but only 0 present.""",
        """create obj threw TypeError: Failed to execute 'createImageData' on 'CanvasRenderingContext2D': parameter 1 is not of type 'ImageData'.""",
        """create one num threw TypeError: Failed to execute 'createImageData' on 'CanvasRenderingContext2D': parameter 1 is not of type 'ImageData'.""",
        """create nan threw TypeError: Failed to execute 'createImageData' on 'CanvasRenderingContext2D': Value is not of type 'long'.""",
        """create inf threw TypeError: Failed to execute 'createImageData' on 'CanvasRenderingContext2D': Value is infinite and not of type 'long'.""",
        """== get""",
        """all 255,0,0,128 255,0,0,128 0,0,0,0 0,0,0,0 255,0,0,128 255,0,0,128 0,0,0,0 0,0,0,0 0,0,0,0 0,0,0,0 0,255,0,255 0,255,0,255 0,0,0,0 0,0,0,0 0,255,0,255 0,255,0,255""",
        """fresh false srgb""",
        """outside 0,0,0,0 0,0,0,0 0,0,0,0 255,0,0,128""",
        """far 0,0,0,0 0,0,0,0""",
        """neg size 2x2 0,255,0,255 0,255,0,255 0,255,0,255 0,255,0,255""",
        """frac 1x1 255,0,0,128""",
        """frac2 1x1 255,0,0,128""",
        """zero w threw IndexSizeError: Failed to execute 'getImageData' on 'CanvasRenderingContext2D': The source width is 0.""",
        """nan threw TypeError: Failed to execute 'getImageData' on 'CanvasRenderingContext2D': Value is not of type 'long'.""",
        """inf threw TypeError: Failed to execute 'getImageData' on 'CanvasRenderingContext2D': Value is infinite and not of type 'long'.""",
        """three threw TypeError: Failed to execute 'getImageData' on 'CanvasRenderingContext2D': 4 arguments required, but only 3 present.""",
        """transform ignored 255,0,0,128""",
        """settings srgb""",
        """empty canvas 0,0,0,0 0,0,0,0""",
        """alpha 1/255 255,0,0,1""",
        """alpha 0.3 199,99,50,77""",
        """half pixel 0,0,0,255 0,0,0,128""",
        """== put""",
        """plain 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 10,0,0,255 20,0,0,255 0,0,255,255 30,0,0,255 40,0,0,128""",
        """replaces 0,0,0,0 0,0,255,255""",
        """ignores state 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 10,0,0,255 20,0,0,255 0,0,255,255 30,0,0,255 40,0,0,128""",
        """dirty 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 20,0,0,255 0,0,255,255 0,0,255,255 40,0,0,128""",
        """dirty neg 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 40,0,0,128 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255""",
        """dirty out 10,0,0,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255""",
        """dirty zero 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255""",
        """off canvas 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 20,0,0,255 0,0,255,255 0,0,255,255""",
        """frac pos 0,0,255,255 0,0,255,255 0,0,255,255 10,0,0,255 20,0,0,255 0,0,255,255 30,0,0,255 40,0,0,128 0,0,255,255""",
        """frac dirty 10,0,0,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255""",
        """five args threw TypeError: Failed to execute 'putImageData' on 'CanvasRenderingContext2D': Overload resolution failed.""",
        """two args threw TypeError: Failed to execute 'putImageData' on 'CanvasRenderingContext2D': 3 arguments required, but only 2 present.""",
        """not image threw TypeError: Failed to execute 'putImageData' on 'CanvasRenderingContext2D': parameter 1 is not of type 'ImageData'.""",
        """nan threw TypeError: Failed to execute 'putImageData' on 'CanvasRenderingContext2D': Value is not of type 'long'.""",
        """round trip 199,100,50,128 255,255,255,1""",
        """round trip 2 0,0,0,3 89,89,93,77 254,0,17,254 0,0,0,0""",
        """draw over put 127,0,128,255""",
        """mutate after put 255,0,0,255""",
        """draw canvas 255,0,0,255 0,255,0,255""",
        """pattern of put 0,0,255,255 0,0,255,255 0,0,255,255 0,0,255,255""",
        """resize clears 0,0,0,0""",
        """== dataurl""",
        """blank data:image/png;base64,""",
        """png data:image/png;base64,""",
        """jpeg data:image/jpeg;base64,""",
        """jpeg q data:image/jpeg;base64,""",
        """upper data:image/jpeg;base64,""",
        """unknown data:image/png;base64,""",
        """zero data:,""",
        """zero h data:,""",
        """no context true""",
        """this threw TypeError: Illegal invocation""",
        """png decodes 80PNG""",
        """== blob""",
        """no cb threw TypeError: Failed to execute 'toBlob' on 'HTMLCanvasElement': 1 argument required, but only 0 present.""",
        """cb not fn threw TypeError: Failed to execute 'toBlob' on 'HTMLCanvasElement': parameter 1 is not of type 'Function'.""",
        """png sync=false [object Blob] image/png true args=1""",
        """jpeg image/jpeg""",
        """zero null""",
        """unknown image/png""",
        """bytes 807871 PNG""",
        """== formats""",
        """default fmt rgba-unorm8""",
        """f16 rgba-float16 [object Float16Array] 4""",
        """f16 bad threw TypeError: Failed to construct 'ImageData': Failed to read the 'pixelFormat' property from 'ImageDataSettings': The provided value 'x' is not a valid enum value of type ImageDataPixelFormat.""",
        """f16 from u8c threw InvalidStateError: Failed to construct 'ImageData': Uint8ClampedArray must use rgba-unorm8 pixelFormat.""",
        """from f16 threw InvalidStateError: Failed to construct 'ImageData': Float16Array must use rgba-float16 pixelFormat.""",
        """from f32 threw InvalidStateError: Failed to construct 'ImageData': Float32Array must use rgba-float32 pixelFormat.""",
        """get f16 rgba-float16 1,0.5,0,0.501953125""",
        """put f16 255,128,64,255""",
        """put f16 out 255,0,128,128""",
        """create f16 rgba-float16 rgba-float16""",
        """create cs display-p3 display-p3""",
        """p3 get 234,51,35,255""",
        """p3 put 255,0,0,255""",
        """from f16 ok threw IndexSizeError: Failed to construct 'ImageData': The source height is zero or not a number.""",
        """f16 round 0.038970947265625,0.779296875,0.298583984375,0.302001953125""",
        """p3 get 2 117,251,76,255,37,59,192,128,128,128,128,255""",
        """p3 put 2 255,0,0,255,84,151,205,128""",
        """p3 ctx display-p3 234,51,35,255 255,0,0,255""",
        """clone [object ImageData] false 5 1 true""",
        """clone transfer 0 4""",
        """data detached threw InvalidStateError: Failed to execute 'putImageData' on 'CanvasRenderingContext2D': The source data has been detached.""",
        """get proto threw TypeError: Illegal invocation""",
        """large threw RangeError: Failed to execute 'getImageData' on 'CanvasRenderingContext2D': Out of memory at ImageData creation""",
        """ctx canvas size 3 5""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("C610") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_image_data_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_image_data_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
