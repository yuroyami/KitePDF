package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * DOMPoint, DOMQuad and DOMMatrix in a book's scripts (#609). Each expected line is what headless\n * Chromium logs for the same chapter.
 */
class GeometryTest {

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
          if (!s) { log('done'); console.log('G609\n' + out.join('\n')); return; }
          log('== ' + s[0]);
          var p;
          try { p = s[1](); } catch (e) { log('step threw ' + err(e)); }
          Promise.resolve(p).then(function () { setTimeout(next, 0); }, function (e) { log('step rejected ' + err(e)); setTimeout(next, 0); });
        }


        function r(v) { return typeof v === 'number' && v === v && Math.abs(v) !== Infinity ? Number(v.toPrecision(12)) : v; }
        function mx(m) { var k = ['m11','m12','m13','m14','m21','m22','m23','m24','m31','m32','m33','m34','m41','m42','m43','m44']; return '[' + k.map(function (n) { return String(r(m[n])); }).join(',') + '] 2d=' + m.is2D + ' id=' + m.isIdentity; }
        function pt(p) { return '(' + [p.x, p.y, p.z, p.w].map(function (v) { return String(r(v)); }).join(',') + ')'; }
        step('shape', function () {
          tryit('ctors', function () { return [DOMPointReadOnly, DOMPoint, DOMQuad, DOMMatrixReadOnly, DOMMatrix].map(function (c) { return c.name + c.length; }).join(' '); });
          tryit('alias', function () { return WebKitCSSMatrix === DOMMatrix; });
          tryit('protos', function () { return [Object.getPrototypeOf(DOMPoint.prototype) === DOMPointReadOnly.prototype, Object.getPrototypeOf(DOMMatrix.prototype) === DOMMatrixReadOnly.prototype, Object.getPrototypeOf(DOMMatrix) === DOMMatrixReadOnly].join(); });
          tryit('point names', function () { return names(DOMPointReadOnly.prototype) + ' | ' + names(DOMPoint.prototype) + ' | ' + names(DOMPointReadOnly); });
          tryit('quad names', function () { return names(DOMQuad.prototype) + ' | ' + names(DOMQuad); });
          tryit('matrix ro names', function () { return names(DOMMatrixReadOnly.prototype) + ' | ' + names(DOMMatrixReadOnly); });
          tryit('matrix names', function () { return names(DOMMatrix.prototype) + ' | ' + names(DOMMatrix); });
          tryit('tags', function () { return [new DOMPoint(), new DOMQuad(), new DOMMatrix(), new DOMMatrixReadOnly()].map(function (o) { return Object.prototype.toString.call(o); }).join(); });
          tryit('call', function () { DOMPoint(); return 'ok'; });
        });
        step('point', function () {
          tryit('default', function () { return pt(new DOMPoint()); });
          tryit('args', function () { return pt(new DOMPoint('3', undefined, null, NaN)); });
          tryit('readonly', function () { var p = new DOMPointReadOnly(1, 2); p.x = 9; return pt(p); });
          tryit('set', function () { var p = new DOMPoint(1, 2); p.x = '7'; p.w = Infinity; return pt(p); });
          tryit('fromPoint', function () { return pt(DOMPoint.fromPoint({ x: 5, z: 2 })) + ' ' + Object.prototype.toString.call(DOMPointReadOnly.fromPoint()) + ' ' + Object.prototype.toString.call(DOMPoint.fromPoint()); });
          tryit('fromPoint bad', function () { return pt(DOMPoint.fromPoint(5)); });
          tryit('fromPoint sym', function () { return pt(DOMPoint.fromPoint({ x: Symbol() })); });
          tryit('json', function () { return JSON.stringify(new DOMPoint(1, 2, 3, 4)) + ' ' + JSON.stringify(new DOMPointReadOnly(1, 2).toJSON()); });
          tryit('transform', function () { var p = new DOMPoint(1, 2).matrixTransform({ e: 10, f: 20 }); return pt(p) + ' ' + Object.prototype.toString.call(p); });
          tryit('transform 3d', function () { return pt(new DOMPoint(1, 2, 3, 1).matrixTransform(new DOMMatrix([1,0,0,0, 0,1,0,0, 0,0,1,0, 5,6,7,1]))); });
          tryit('transform bad', function () { return pt(new DOMPoint(1, 2).matrixTransform({ a: 2, m11: 3 })); });
        });
        step('quad', function () {
          tryit('default', function () { var q = new DOMQuad(); return [q.p1, q.p2, q.p3, q.p4].map(pt).join(' ') + ' ' + (q.p1 === q.p1); });
          tryit('points', function () { var q = new DOMQuad({ x: 1 }, { x: 4, y: 1 }, { x: 5, y: 6 }, { y: 7, w: 2 }); return [q.p1, q.p2, q.p3, q.p4].map(pt).join(' ') + ' ' + Object.prototype.toString.call(q.p1); });
          tryit('bounds', function () { var b = new DOMQuad({ x: 1 }, { x: 4, y: 1 }, { x: 5, y: 6 }, { y: 7 }).getBounds(); return [b.x, b.y, b.width, b.height].join() + ' ' + Object.prototype.toString.call(b); });
          tryit('fromRect', function () { var q = DOMQuad.fromRect({ x: 1, y: 2, width: 3, height: 4 }); return [q.p1, q.p2, q.p3, q.p4].map(pt).join(' '); });
          tryit('fromQuad', function () { var q = DOMQuad.fromQuad({ p1: { x: 1 }, p3: { x: 3, y: 3 } }); return [q.p1, q.p2, q.p3, q.p4].map(pt).join(' '); });
          tryit('json', function () { return JSON.stringify(DOMQuad.fromRect({ width: 2, height: 2 })); });
          tryit('mutate', function () { var q = new DOMQuad(); q.p1.x = 5; q.p1 = null; return pt(q.p1); });
        });
        step('matrix make', function () {
          tryit('none', function () { return mx(new DOMMatrix()); });
          tryit('six', function () { return mx(new DOMMatrix([1, 2, 3, 4, 5, 6])); });
          tryit('sixteen', function () { return mx(new DOMMatrix([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1])); });
          tryit('five', function () { return mx(new DOMMatrix([1, 2, 3, 4, 5])); });
          tryit('string', function () { return mx(new DOMMatrix('translate(10px, 20px) scale(2)')); });
          tryit('string rotate', function () { return mx(new DOMMatrix('rotate(45deg)')); });
          tryit('string 3d', function () { return mx(new DOMMatrix('translate3d(1px, 2px, 3px)')); });
          tryit('string none', function () { return mx(new DOMMatrix('none')); });
          tryit('string empty', function () { return mx(new DOMMatrix('')); });
          tryit('string bad', function () { return mx(new DOMMatrix('foo')); });
          tryit('string em', function () { return mx(new DOMMatrix('translate(1em)')); });
          tryit('string matrix', function () { return mx(new DOMMatrix('matrix(1, 2, 3, 4, 5, 6)')); });
          tryit('readonly', function () { return mx(new DOMMatrixReadOnly([2, 0, 0, 2, 0, 0])); });
          tryit('from matrix obj', function () { return mx(new DOMMatrix(new DOMMatrix([1, 2, 3, 4, 5, 6]))); });
          tryit('number', function () { return mx(new DOMMatrix(5)); });
          tryit('fromMatrix', function () { return mx(DOMMatrix.fromMatrix({ a: 2, d: 3, e: 4 })); });
          tryit('fromMatrix m', function () { return mx(DOMMatrix.fromMatrix({ m11: 2, m22: 3, m43: 4 })); });
          tryit('fromMatrix bad a', function () { return mx(DOMMatrix.fromMatrix({ a: 2, m11: 3 })); });
          tryit('fromMatrix bad 2d', function () { return mx(DOMMatrix.fromMatrix({ is2D: true, m13: 1 })); });
          tryit('fromMatrix 2d false', function () { return mx(DOMMatrix.fromMatrix({ is2D: false })); });
          tryit('fromMatrix nan', function () { return mx(DOMMatrix.fromMatrix({ a: NaN })); });
          tryit('fromFloat32', function () { return mx(DOMMatrix.fromFloat32Array(new Float32Array([1, 2, 3, 4, 5, 6.5]))); });
          tryit('fromFloat64 bad', function () { return mx(DOMMatrix.fromFloat64Array(new Float64Array(3))); });
          tryit('fromFloat32 arr', function () { return mx(DOMMatrix.fromFloat32Array([1, 2, 3, 4, 5, 6])); });
        });
        step('matrix read', function () {
          var m = new DOMMatrix([1, 2, 3, 4, 5, 6]);
          tryit('2d attrs', function () { return [m.a, m.b, m.c, m.d, m.e, m.f].join(); });
          tryit('string', function () { return m.toString() + ' | ' + String(new DOMMatrix([1,0,0,0, 0,1,0,0, 0,0,1,0, 1,2,3,1])); });
          tryit('string frac', function () { return new DOMMatrix([0.1, 1e21, 1e-7, -0, 0.5, 1 / 3]).toString(); });
          tryit('string nan', function () { return new DOMMatrix([NaN, 0, 0, 1, 0, 0]).toString(); });
          tryit('json', function () { return JSON.stringify(m); });
          tryit('f32', function () { var a = m.toFloat32Array(); return Object.prototype.toString.call(a) + a.length + ' ' + Array.prototype.join.call(a); });
          tryit('f64', function () { var a = new DOMMatrix([1, 2, 3, 4, 5, 6.1]).toFloat64Array(); return Object.prototype.toString.call(a) + a.length + ' ' + a[13]; });
          tryit('ro set', function () { var ro = new DOMMatrixReadOnly(); ro.a = 5; return ro.a; });
          tryit('set m13', function () { var w = new DOMMatrix(); w.m13 = 0; var x = w.is2D; w.m13 = 1; return x + ' ' + mx(w); });
          tryit('set a', function () { var w = new DOMMatrix(); w.a = '3'; w.f = 4; return mx(w); });
          tryit('identity checks', function () { return [new DOMMatrix([1, 0, 0, 1, 0, -0]).isIdentity, new DOMMatrix([1, 0, 0, 1, 0, 1e-300]).isIdentity].join(); });
        });
        step('matrix ops', function () {
          var m = new DOMMatrix([1, 2, 3, 4, 5, 6]);
          tryit('translate', function () { return mx(m.translate(10, 20)) + ' same ' + (m.translate() === m); });
          tryit('translate z', function () { return mx(m.translate(1, 2, 3)); });
          tryit('scale', function () { return mx(m.scale(2)); });
          tryit('scale xy', function () { return mx(m.scale(2, 3)); });
          tryit('scale origin', function () { return mx(m.scale(2, 3, 1, 10, 20, 0)); });
          tryit('scale3d', function () { return mx(m.scale3d(2, 1, 1, 1)); });
          tryit('scaleNonUniform', function () { return typeof m.scaleNonUniform === 'function' ? mx(m.scaleNonUniform(2, 3)) : 'none'; });
          tryit('rotate', function () { return mx(new DOMMatrix().rotate(90)); });
          tryit('rotate 30', function () { return mx(new DOMMatrix().rotate(30)); });
          tryit('rotate xyz', function () { return mx(new DOMMatrix().rotate(10, 20, 30)); });
          tryit('rotate xy', function () { return mx(new DOMMatrix().rotate(90, 0)); });
          tryit('rotateFromVector', function () { return mx(new DOMMatrix().rotateFromVector(1, 1)) + ' ' + mx(new DOMMatrix().rotateFromVector(0, 0)); });
          tryit('rotateAxisAngle', function () { return mx(new DOMMatrix().rotateAxisAngle(1, 1, 0, 45)); });
          tryit('rotateAxisAngle zero', function () { return mx(new DOMMatrix().rotateAxisAngle(0, 0, 0, 45)); });
          tryit('skew', function () { return mx(new DOMMatrix().skewX(45)) + ' ' + mx(new DOMMatrix().skewY(30)); });
          tryit('multiply', function () { return mx(m.multiply(new DOMMatrix([2, 0, 0, 2, 1, 1]))) + ' ' + mx(m.multiply({ m43: 2 })); });
          tryit('multiply none', function () { return mx(m.multiply()); });
          tryit('flip', function () { return mx(m.flipX()) + ' ' + mx(m.flipY()); });
          tryit('inverse', function () { return mx(m.inverse()); });
          tryit('inverse singular', function () { return mx(new DOMMatrix([1, 1, 1, 1, 0, 0]).inverse()); });
          tryit('inverse 3d', function () { return mx(new DOMMatrix([2,0,0,0, 0,2,0,0, 0,0,4,0, 1,2,3,1]).inverse()); });
          tryit('transformPoint', function () { return pt(m.transformPoint({ x: 1, y: 1 })) + ' ' + pt(m.transformPoint()); });
          tryit('result type', function () { return Object.prototype.toString.call(new DOMMatrixReadOnly().translate(1)); });
        });
        step('matrix self', function () {
          var w = new DOMMatrix([1, 2, 3, 4, 5, 6]);
          tryit('translateSelf', function () { return (w.translateSelf(1, 1) === w) + ' ' + mx(w); });
          tryit('scaleSelf', function () { return mx(w.scaleSelf(2, 2, 0, 1, 1)); });
          tryit('rotateSelf', function () { return mx(w.rotateSelf(0, 0, 90)); });
          tryit('skewXSelf', function () { return mx(w.skewXSelf(0)); });
          tryit('multiplySelf', function () { return mx(w.multiplySelf({ a: 2, d: 2 })); });
          tryit('preMultiplySelf', function () { return mx(w.preMultiplySelf({ e: 1 })); });
          tryit('invertSelf', function () { return mx(w.invertSelf()); });
          tryit('invertSelf singular', function () { var s = new DOMMatrix([0, 0, 0, 0, 0, 0]); s.invertSelf(); return mx(s); });
          tryit('setMatrixValue', function () { return mx(w.setMatrixValue('translate(5px)')) + ' ' + (w.setMatrixValue('') === w); });
          tryit('setMatrixValue bad', function () { return mx(w.setMatrixValue('bad(')); });
          tryit('rotateFromVectorSelf', function () { return mx(new DOMMatrix().rotateFromVectorSelf(0, 1)); });
          tryit('rotateAxisAngleSelf', function () { return mx(new DOMMatrix().rotateAxisAngleSelf(0, 0, 1, 90)); });
          tryit('scale3dSelf', function () { return mx(new DOMMatrix().scale3dSelf(2)); });
          tryit('illegal', function () { return DOMMatrix.prototype.translateSelf.call({}); });
          tryit('ro illegal', function () { return Object.getOwnPropertyDescriptor(DOMMatrixReadOnly.prototype, 'a').get.call({}); });
        });
        step('css', function () {
          var el = document.getElementById('p');
          el.style.transform = 'translate(3px, 4px) rotate(90deg)';
          tryit('computed', function () { return mx(new DOMMatrix(getComputedStyle(el).transform)); });
          tryit('computed string', function () { return getComputedStyle(el).transform; });
          tryit('perspective', function () { return mx(new DOMMatrix('perspective(100px)')); });
          tryit('skew string', function () { return mx(new DOMMatrix('skew(10deg, 20deg) translateX(5px) scaleY(3)')); });
          tryit('units', function () { return mx(new DOMMatrix('rotate(0.25turn) translate(1in, 2cm)')); });
          tryit('calc', function () { return mx(new DOMMatrix('translate(calc(1px + 2px))')); });
          tryit('percent', function () { return mx(new DOMMatrix('translate(50%)')); });
          tryit('inherit', function () { return mx(new DOMMatrix('inherit')); });
        });
        step('parse', function () {
          var cs = ['rotateZ(90deg)', 'rotateX(0deg)', 'rotate3d(0, 0, 1, 90deg)', 'perspective(0)', 'perspective(0px)', 'perspective(-10px)', 'perspective(0.5px)',
            'perspective(3.3px)', 'scale(50%)', 'scale(50%, 2)', 'translate(10px 20px)', 'TRANSLATE(10PX)', 'translate(10px,)', 'rotate(calc(45deg * 2))',
            'matrix3d(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0)', 'translate(5)', 'translate(0)', 'rotate(0)', '  translate(1px)  ', 'none none', 'rotate(1rad)',
            'rotate(100grad)', 'translate(1px)translate(2px)', 'translate(1px),translate(2px)', 'translateZ(0)', 'scaleZ(1)', 'translate3d(0,0,0)',
            'scale3d(1,1,1)', 'skew(10deg)', 'translate(calc(10px + 5%))', 'translate(calc(2px * 3))', 'translate(calc((1px + 1px) * 2))',
            'translate(min(1px, 2px))', 'translate(1q)', 'translate(1pc, 1mm)', 'rotate(1.5e1deg)', 'translate(+1px, -.5px)', 'rotate(90)', 'translate(1vw)',
            'scale(calc(2))', 'rotate(calc(0.5turn))', 'translateX(1px) none', 'rotateY(30deg)', 'skewX(90deg)', 'translate(1in)', 'rotate(-0deg)',
            'translate(10.1px)', 'matrix(0.1, 0, 0, 1, 0.1, 0)', 'scale(1.1)', 'rotate(0.1rad)', 'skewX(0.1rad)', 'translate(calc(0.1px * 3))',
            'rotate3d(1, 2, 3, 30deg)', 'rotate3d(0, 0, 0, 30deg)', 'rotate3d(0, 1, 0, 90deg)', 'rotate(450deg)', 'rotate(-90deg)', 'rotate(45deg) rotate(45deg)',
            'translate(calc(1px+2px))', 'translate(calc(1px - 2px))', 'translate(max(1px, 1in))', 'translate(clamp(1px, 5px, 3px))', 'scale(1, 2, 3)',
            'rotate(1deg, 2deg)', 'translate(1e2px)', 'translate(.px)', 'rotate(45DEG)', 'matrix(1, 0, 0, 1, 0, 0) translate(2px)'];
          for (var i = 0; i < cs.length; i++) (function (c) { tryit('[' + c + ']', function () { return mx(new DOMMatrix(c)); }); })(cs[i]);
        });
        setTimeout(next, 0);
    """.trimIndent()

    private val chromium = listOf(
        """== shape""",
        """ctors DOMPointReadOnly0 DOMPoint0 DOMQuad0 DOMMatrixReadOnly0 DOMMatrix0""",
        """alias true""",
        """protos true,true,true""",
        """point names constructor,matrixTransform,toJSON,w,x,y,z | constructor,w,x,y,z | fromPoint,length,name,prototype""",
        """quad names constructor,getBounds,p1,p2,p3,p4,toJSON | fromQuad,fromRect,length,name,prototype""",
        """matrix ro names a,b,c,constructor,d,e,f,flipX,flipY,inverse,is2D,isIdentity,m11,m12,m13,m14,m21,m22,m23,m24,m31,m32,m33,m34,m41,m42,m43,m44,multiply,rotate,rotateAxisAngle,rotateFromVector,scale,scale3d,scaleNonUniform,skewX,skewY,toFloat32Array,toFloat64Array,toJSON,toString,transformPoint,translate | fromFloat32Array,fromFloat64Array,fromMatrix,length,name,prototype""",
        """matrix names a,b,c,constructor,d,e,f,invertSelf,m11,m12,m13,m14,m21,m22,m23,m24,m31,m32,m33,m34,m41,m42,m43,m44,multiplySelf,preMultiplySelf,rotateAxisAngleSelf,rotateFromVectorSelf,rotateSelf,scale3dSelf,scaleSelf,setMatrixValue,skewXSelf,skewYSelf,translateSelf | fromFloat32Array,fromFloat64Array,fromMatrix,length,name,prototype""",
        """tags [object DOMPoint],[object DOMQuad],[object DOMMatrix],[object DOMMatrixReadOnly]""",
        """call threw TypeError: Failed to construct 'DOMPoint': Please use the 'new' operator, this DOM object constructor cannot be called as a function.""",
        """== point""",
        """default (0,0,0,1)""",
        """args (3,0,0,NaN)""",
        """readonly (1,2,0,1)""",
        """set (7,2,0,Infinity)""",
        """fromPoint (5,0,2,1) [object DOMPointReadOnly] [object DOMPoint]""",
        """fromPoint bad threw TypeError: Failed to execute 'fromPoint' on 'DOMPoint': The provided value is not of type 'DOMPointInit'.""",
        """fromPoint sym threw TypeError: Failed to execute 'fromPoint' on 'DOMPoint': Failed to read the 'x' property from 'DOMPointInit': Cannot convert a Symbol value to a number""",
        """json {"x":1,"y":2,"z":3,"w":4} {"x":1,"y":2,"z":0,"w":1}""",
        """transform (11,22,0,1) [object DOMPoint]""",
        """transform 3d (6,8,10,1)""",
        """transform bad threw TypeError: Failed to execute 'matrixTransform' on 'DOMPointReadOnly': Property mismatch on matrix initialization.""",
        """== quad""",
        """default (0,0,0,1) (0,0,0,1) (0,0,0,1) (0,0,0,1) true""",
        """points (1,0,0,1) (4,1,0,1) (5,6,0,1) (0,7,0,2) [object DOMPoint]""",
        """bounds 0,0,5,7 [object DOMRect]""",
        """fromRect (1,2,0,1) (4,2,0,1) (4,6,0,1) (1,6,0,1)""",
        """fromQuad (1,0,0,1) (0,0,0,1) (3,3,0,1) (0,0,0,1)""",
        """json {"p1":{"x":0,"y":0,"z":0,"w":1},"p2":{"x":2,"y":0,"z":0,"w":1},"p3":{"x":2,"y":2,"z":0,"w":1},"p4":{"x":0,"y":2,"z":0,"w":1}}""",
        """mutate (5,0,0,1)""",
        """== matrix make""",
        """none [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """six [1,2,0,0,3,4,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """sixteen [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """five threw TypeError: Failed to construct 'DOMMatrix': The sequence must contain 6 elements for a 2D matrix or 16 elements for a 3D matrix.""",
        """string [2,0,0,0,0,2,0,0,0,0,1,0,10,20,0,1] 2d=true id=false""",
        """string rotate [0.707106781187,0.707106781187,0,0,-0.707106781187,0.707106781187,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """string 3d [1,0,0,0,0,1,0,0,0,0,1,0,1,2,3,1] 2d=false id=false""",
        """string none [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """string empty [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """string bad threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'foo'.""",
        """string em threw SyntaxError: Failed to construct 'DOMMatrix': Values must be resolvable at parse time""",
        """string matrix [1,2,0,0,3,4,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """readonly [2,0,0,0,0,2,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """from matrix obj [1,2,0,0,3,4,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """number threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse '5'.""",
        """fromMatrix [2,0,0,0,0,3,0,0,0,0,1,0,4,0,0,1] 2d=true id=false""",
        """fromMatrix m [2,0,0,0,0,3,0,0,0,0,1,0,0,0,4,1] 2d=false id=false""",
        """fromMatrix bad a threw TypeError: Failed to execute 'fromMatrix' on 'DOMMatrix': Property mismatch on matrix initialization.""",
        """fromMatrix bad 2d threw TypeError: Failed to execute 'fromMatrix' on 'DOMMatrix': The is2D member is set to true but the input matrix is a 3d matrix.""",
        """fromMatrix 2d false [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """fromMatrix nan [NaN,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """fromFloat32 [1,2,0,0,3,4,0,0,0,0,1,0,5,6.5,0,1] 2d=true id=false""",
        """fromFloat64 bad threw TypeError: Failed to execute 'fromFloat64Array' on 'DOMMatrix': The sequence must contain 6 elements for a 2D matrix or 16 elements for a 3D matrix.""",
        """fromFloat32 arr threw TypeError: Failed to execute 'fromFloat32Array' on 'DOMMatrix': parameter 1 is not of type 'Float32Array'.""",
        """== matrix read""",
        """2d attrs 1,2,3,4,5,6""",
        """string matrix(1, 2, 3, 4, 5, 6) | matrix3d(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 2, 3, 1)""",
        """string frac matrix(0.1, 1e+21, 1e-7, 0, 0.5, 0.3333333333333333)""",
        """string nan threw InvalidStateError: Failed to execute 'toString' on 'DOMMatrixReadOnly': DOMMatrix cannot be serialized with NaN or Infinity values.""",
        """json {"a":1,"b":2,"c":3,"d":4,"e":5,"f":6,"m11":1,"m12":2,"m13":0,"m14":0,"m21":3,"m22":4,"m23":0,"m24":0,"m31":0,"m32":0,"m33":1,"m34":0,"m41":5,"m42":6,"m43":0,"m44":1,"is2D":true,"isIdentity":false}""",
        """f32 [object Float32Array]16 1,2,0,0,3,4,0,0,0,0,1,0,5,6,0,1""",
        """f64 [object Float64Array]16 6.1""",
        """ro set 1""",
        """set m13 true [1,0,1,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=false""",
        """set a [3,0,0,0,0,1,0,0,0,0,1,0,0,4,0,1] 2d=true id=false""",
        """identity checks true,false""",
        """== matrix ops""",
        """translate [1,2,0,0,3,4,0,0,0,0,1,0,75,106,0,1] 2d=true id=false same false""",
        """translate z [1,2,0,0,3,4,0,0,0,0,1,0,12,16,3,1] 2d=false id=false""",
        """scale [2,4,0,0,6,8,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """scale xy [2,4,0,0,9,12,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """scale origin [2,4,0,0,9,12,0,0,0,0,1,0,-125,-174,0,1] 2d=true id=false""",
        """scale3d [2,4,0,0,6,8,0,0,0,0,2,0,1,0,-1,1] 2d=false id=false""",
        """scaleNonUniform [2,4,0,0,9,12,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """rotate [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """rotate 30 [0.866025403784,0.5,0,0,-0.5,0.866025403784,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """rotate xyz [0.813797681349,0.469846310393,-0.342020143326,0,-0.44096961053,0.882564119259,0.163175911167,0,0.37852230637,0.0180283112363,0.925416578398,0,0,0,0,1] 2d=false id=false""",
        """rotate xy [1,0,0,0,0,0,1,0,0,-1,0,0,0,0,0,1] 2d=false id=false""",
        """rotateFromVector [0.707106781187,0.707106781187,0,0,-0.707106781187,0.707106781187,0,0,0,0,1,0,0,0,0,1] 2d=true id=false [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """rotateAxisAngle [0.853553390593,0.146446609407,-0.5,0,0.146446609407,0.853553390593,0.5,0,0.5,-0.5,0.707106781187,0,0,0,0,1] 2d=false id=false""",
        """rotateAxisAngle zero [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """skew [1,0,0,0,1,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false [1,0.57735026919,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """multiply [2,4,0,0,6,8,0,0,0,0,1,0,9,12,0,1] 2d=true id=false [1,2,0,0,3,4,0,0,0,0,1,0,5,6,2,1] 2d=false id=false""",
        """multiply none [1,2,0,0,3,4,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """flip [-1,-2,0,0,3,4,0,0,0,0,1,0,5,6,0,1] 2d=true id=false [1,2,0,0,-3,-4,0,0,0,0,1,0,5,6,0,1] 2d=true id=false""",
        """inverse [-2,1,0,0,1.5,-0.5,0,0,0,0,1,0,1,-2,0,1] 2d=true id=false""",
        """inverse singular [NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN] 2d=false id=false""",
        """inverse 3d [0.5,0,0,0,0,0.5,0,0,0,0,0.25,0,-0.5,-1,-0.75,1] 2d=false id=false""",
        """transformPoint (9,12,0,1) (5,6,0,1)""",
        """result type [object DOMMatrix]""",
        """== matrix self""",
        """translateSelf true [1,2,0,0,3,4,0,0,0,0,1,0,9,12,0,1] 2d=true id=false""",
        """scaleSelf [2,4,0,0,6,8,0,0,0,0,0,0,5,6,0,1] 2d=false id=false""",
        """rotateSelf [6,8,0,0,-2,-4,0,0,0,0,0,0,5,6,0,1] 2d=false id=false""",
        """skewXSelf [6,8,0,0,-2,-4,0,0,0,0,0,0,5,6,0,1] 2d=false id=false""",
        """multiplySelf [12,16,0,0,-4,-8,0,0,0,0,0,0,5,6,0,1] 2d=false id=false""",
        """preMultiplySelf [12,16,0,0,-4,-8,0,0,0,0,0,0,6,6,0,1] 2d=false id=false""",
        """invertSelf [NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN] 2d=false id=false""",
        """invertSelf singular [NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN] 2d=false id=false""",
        """setMatrixValue [1,0,0,0,0,1,0,0,0,0,1,0,5,0,0,1] 2d=true id=false true""",
        """setMatrixValue bad threw SyntaxError: Failed to execute 'setMatrixValue' on 'DOMMatrix': Failed to parse 'bad('.""",
        """rotateFromVectorSelf [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """rotateAxisAngleSelf [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """scale3dSelf [2,0,0,0,0,2,0,0,0,0,2,0,0,0,0,1] 2d=false id=false""",
        """illegal threw TypeError: Illegal invocation""",
        """ro illegal threw TypeError: Illegal invocation""",
        """== css""",
        """computed [0,1,0,0,-1,0,0,0,0,0,1,0,3,4,0,1] 2d=true id=false""",
        """computed string matrix(0, 1, -1, 0, 3, 4)""",
        """perspective [1,0,0,0,0,1,0,0,0,0,1,-0.01,0,0,0,1] 2d=false id=false""",
        """skew string [1,0.363970234266,0,0,0.528980942125,3,0,0,0,0,1,0,5,1.81985117133,0,1] 2d=true id=false""",
        """units [0,1,0,0,-1,0,0,0,0,0,1,0,-75.5905532837,96,0,1] 2d=true id=false""",
        """calc [1,0,0,0,0,1,0,0,0,0,1,0,3,0,0,1] 2d=true id=false""",
        """percent threw SyntaxError: Failed to construct 'DOMMatrix': Values must be resolvable at parse time""",
        """inherit threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'inherit'.""",
        """== parse""",
        """[rotateZ(90deg)] [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[rotateX(0deg)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """[rotate3d(0, 0, 1, 90deg)] [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=false id=false""",
        """[perspective(0)] [1,0,0,0,0,1,0,0,0,0,1,-1,0,0,0,1] 2d=false id=false""",
        """[perspective(0px)] [1,0,0,0,0,1,0,0,0,0,1,-1,0,0,0,1] 2d=false id=false""",
        """[perspective(-10px)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'perspective(-10px)'.""",
        """[perspective(0.5px)] [1,0,0,0,0,1,0,0,0,0,1,-1,0,0,0,1] 2d=false id=false""",
        """[perspective(3.3px)] [1,0,0,0,0,1,0,0,0,0,1,-0.30303030303,0,0,0,1] 2d=false id=false""",
        """[scale(50%)] [0.5,0,0,0,0,0.5,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[scale(50%, 2)] [0.5,0,0,0,0,2,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(10px 20px)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translate(10px 20px)'.""",
        """[TRANSLATE(10PX)] [1,0,0,0,0,1,0,0,0,0,1,0,10,0,0,1] 2d=true id=false""",
        """[translate(10px,)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translate(10px,)'.""",
        """[rotate(calc(45deg * 2))] [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[matrix3d(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'matrix3d(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0)'.""",
        """[translate(5)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translate(5)'.""",
        """[translate(0)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """[rotate(0)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """[  translate(1px)  ] [1,0,0,0,0,1,0,0,0,0,1,0,1,0,0,1] 2d=true id=false""",
        """[none none] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'none none'.""",
        """[rotate(1rad)] [0.540302305868,0.841470984808,0,0,-0.841470984808,0.540302305868,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[rotate(100grad)] [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(1px)translate(2px)] [1,0,0,0,0,1,0,0,0,0,1,0,3,0,0,1] 2d=true id=false""",
        """[translate(1px),translate(2px)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translate(1px),translate(2px)'.""",
        """[translateZ(0)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """[scaleZ(1)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """[translate3d(0,0,0)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """[scale3d(1,1,1)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """[skew(10deg)] [1,0,0,0,0.176326980708,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(calc(10px + 5%))] threw SyntaxError: Failed to construct 'DOMMatrix': Values must be resolvable at parse time""",
        """[translate(calc(2px * 3))] [1,0,0,0,0,1,0,0,0,0,1,0,6,0,0,1] 2d=true id=false""",
        """[translate(calc((1px + 1px) * 2))] [1,0,0,0,0,1,0,0,0,0,1,0,4,0,0,1] 2d=true id=false""",
        """[translate(min(1px, 2px))] [1,0,0,0,0,1,0,0,0,0,1,0,1,0,0,1] 2d=true id=false""",
        """[translate(1q)] [1,0,0,0,0,1,0,0,0,0,1,0,0.944881916046,0,0,1] 2d=true id=false""",
        """[translate(1pc, 1mm)] [1,0,0,0,0,1,0,0,0,0,1,0,16,3.77952766418,0,1] 2d=true id=false""",
        """[rotate(1.5e1deg)] [0.965925826289,0.258819045103,0,0,-0.258819045103,0.965925826289,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(+1px, -.5px)] [1,0,0,0,0,1,0,0,0,0,1,0,1,-0.5,0,1] 2d=true id=false""",
        """[rotate(90)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'rotate(90)'.""",
        """[translate(1vw)] threw SyntaxError: Failed to construct 'DOMMatrix': Values must be resolvable at parse time""",
        """[scale(calc(2))] [2,0,0,0,0,2,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[rotate(calc(0.5turn))] [-1,0,0,0,0,-1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translateX(1px) none] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translateX(1px) none'.""",
        """[rotateY(30deg)] [0.866025403784,0,-0.5,0,0,1,0,0,0.5,0,0.866025403784,0,0,0,0,1] 2d=false id=false""",
        """[skewX(90deg)] [1,0,0,0,16331239353200000,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(1in)] [1,0,0,0,0,1,0,0,0,0,1,0,96,0,0,1] 2d=true id=false""",
        """[rotate(-0deg)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=true""",
        """[translate(10.1px)] [1,0,0,0,0,1,0,0,0,0,1,0,10.1000003815,0,0,1] 2d=true id=false""",
        """[matrix(0.1, 0, 0, 1, 0.1, 0)] [0.1,0,0,0,0,1,0,0,0,0,1,0,0.1,0,0,1] 2d=true id=false""",
        """[scale(1.1)] [1.10000002384,0,0,0,0,1.10000002384,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[rotate(0.1rad)] [0.995004165278,0.0998334166468,0,0,-0.0998334166468,0.995004165278,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[skewX(0.1rad)] [1,0,0,0,0.100334672085,1,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(calc(0.1px * 3))] [1,0,0,0,0,1,0,0,0,0,1,0,0.300000011921,0,0,1] 2d=true id=false""",
        """[rotate3d(1, 2, 3, 30deg)] [0.8755950178,0.420031090899,-0.238552399866,0,-0.381752634838,0.904303859846,0.191048305049,0,0.295970083959,-0.0762129368638,0.952151929923,0,0,0,0,1] 2d=false id=false""",
        """[rotate3d(0, 0, 0, 30deg)] [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] 2d=false id=true""",
        """[rotate3d(0, 1, 0, 90deg)] [0,0,-1,0,0,1,0,0,1,0,0,0,0,0,0,1] 2d=false id=false""",
        """[rotate(450deg)] [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[rotate(-90deg)] [0,-1,0,0,1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[rotate(45deg) rotate(45deg)] [0,1,0,0,-1,0,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[translate(calc(1px+2px))] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translate(calc(1px+2px))'.""",
        """[translate(calc(1px - 2px))] [1,0,0,0,0,1,0,0,0,0,1,0,-1,0,0,1] 2d=true id=false""",
        """[translate(max(1px, 1in))] [1,0,0,0,0,1,0,0,0,0,1,0,96,0,0,1] 2d=true id=false""",
        """[translate(clamp(1px, 5px, 3px))] [1,0,0,0,0,1,0,0,0,0,1,0,3,0,0,1] 2d=true id=false""",
        """[scale(1, 2, 3)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'scale(1, 2, 3)'.""",
        """[rotate(1deg, 2deg)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'rotate(1deg, 2deg)'.""",
        """[translate(1e2px)] [1,0,0,0,0,1,0,0,0,0,1,0,100,0,0,1] 2d=true id=false""",
        """[translate(.px)] threw SyntaxError: Failed to construct 'DOMMatrix': Failed to parse 'translate(.px)'.""",
        """[rotate(45DEG)] [0.707106781187,0.707106781187,0,0,-0.707106781187,0.707106781187,0,0,0,0,1,0,0,0,0,1] 2d=true id=false""",
        """[matrix(1, 0, 0, 1, 0, 0) translate(2px)] [1,0,0,0,0,1,0,0,0,0,1,0,2,0,0,1] 2d=true id=false""",
        """done""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val book = ScriptBooks.chapter("""<p id="p">x</p><script src="a.js"></script>""", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("G609") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_geometry_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_geometry_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }
}
