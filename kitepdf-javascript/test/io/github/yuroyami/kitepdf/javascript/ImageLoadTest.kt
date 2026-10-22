package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.encodePng
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * How an image loads in a book's scripts (#611): the load and error events, `complete`, the
 * natural size, `decode`, and what a canvas draws of an image before and after it loads,
 * against what Chromium logs for the same script. Each step waits for the event of the one
 * before it, so the order is the same in both.
 */
class ImageLoadTest {

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
        function px(x, y, w) { return Array.prototype.join.call(x.getImageData(0, y, w || 1, 1).data, ','); }
        var U = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAEklEQVR4nGNg+I8EGRj+/4dhAJKHC/VtD/uqAAAAAElFTkSuQmCC';
        var steps = [], a = document.getElementById('a');
        function step(f) { steps.push(f); }
        function next() { var f = steps.shift(); if (!f) { log('done'); console.log('C611\n' + out.join('\n')); return; } f(); }
        log('script a complete=' + a.complete + ' natural=' + a.naturalWidth + ' width=' + a.width);
        tryit('decode pending is a promise', function () { return Object.prototype.toString.call(a.decode()); });
        document.addEventListener('DOMContentLoaded', function () { log('dcl a complete=' + a.complete); });
        window.addEventListener('load', function () { log('window load a=' + a.naturalWidth + ' ' + a.complete + ' width=' + a.width); setTimeout(next, 0); });
        step(function () {
          var n = new Image();
          n.onload = function (ev) {
            log('n onload ' + n.naturalWidth + 'x' + n.naturalHeight + ' ' + n.complete + ' trusted=' + ev.isTrusted + ' ' + ev.bubbles + ' ' + ev.cancelable + ' width=' + n.width + 'x' + n.height);
            var x = document.createElement('canvas').getContext('2d'); x.drawImage(n, 0, 0);
            log('n drawn ' + px(x, 0) + ' ' + px(x, 1));
            var p = x.createPattern(n, 'repeat'); log('n pattern ' + Object.prototype.toString.call(p));
            setTimeout(next, 0);
          };
          n.src = U;
          log('n after src complete=' + n.complete + ' natural=' + n.naturalWidth + ' width=' + n.width);
          var x0 = document.createElement('canvas').getContext('2d'); x0.drawImage(n, 0, 0); log('n drawn before load ' + px(x0, 0));
          tryit('n pattern before load', function () { return x0.createPattern(n, 'repeat'); });
          tryit('n pattern before load bad repetition', function () { return x0.createPattern(n, 'bogus'); });
        });
        step(function () {
          var bad = new Image();
          bad.onload = function () { log('bad onload'); };
          bad.onerror = function (ev) {
            log('bad onerror ' + ev.type + ' ' + bad.complete + ' ' + bad.naturalWidth + ' ' + bad.width + ' ' + ev.bubbles);
            var x = document.createElement('canvas').getContext('2d');
            tryit('draw broken', function () { x.drawImage(bad, 0, 0); return 'ok'; });
            tryit('draw broken bad args', function () { x.drawImage(bad, 0); return 'ok'; });
            tryit('pattern broken', function () { return x.createPattern(bad, 'repeat'); });
            bad.decode().then(function () { log('bad decoded'); }, function (e) { log('bad decode ' + err(e)); setTimeout(next, 0); });
          };
          bad.src = 'missing.png';
        });
        step(function () {
          var empty = new Image();
          empty.onerror = function () {
            log('empty onerror ' + empty.complete);
            var x = document.createElement('canvas').getContext('2d');
            tryit('draw empty', function () { x.drawImage(empty, 0, 0); return 'ok'; });
            tryit('pattern empty', function () { return x.createPattern(empty, 'repeat'); });
            setTimeout(next, 0);
          };
          empty.src = '';
          log('empty after src complete=' + empty.complete);
        });
        step(function () {
          var none = new Image(5, 7);
          log('none ' + none.complete + ' ' + none.width + 'x' + none.height + ' ' + none.naturalWidth + ' ' + none.currentSrc + '|');
          none.decode().then(function () { log('none decoded'); }, function (e) { log('none decode ' + err(e)); setTimeout(next, 0); });
        });
        step(function () {
          var d = new Image();
          d.src = 'v1-d.png';
          d.decode().then(function () { log('decode ' + d.naturalWidth + ' ' + d.complete); setTimeout(next, 0); }, function (e) { log('decode ' + err(e)); });
        });
        step(function () {
          var r = new Image();
          r.onload = function () {
            log('reset onload ' + r.naturalWidth);
            r.onload = function () { log('reset again ' + r.complete + ' ' + r.naturalWidth); setTimeout(next, 0); };
            r.src = 'v1-r.png';
            log('reset after set ' + r.complete + ' ' + r.naturalWidth);
          };
          r.src = 'v1-r.png';
        });
        step(function () {
          var c = a.cloneNode();
          c.onload = function () { log('clone onload ' + c.naturalWidth); setTimeout(next, 0); };
        });
        step(function () {
          var div = document.createElement('div');
          div.innerHTML = '<img src="v1-i.png" alt=""/>';
          var img = div.firstChild;
          log('inner complete=' + img.complete);
          img.onload = function () { log('inner onload ' + img.naturalWidth + ' width=' + img.width); setTimeout(next, 0); };
        });
        step(function () {
          var parsed = new DOMParser().parseFromString('<p><img src="v1-p.png"/></p>', 'text/html').querySelector('img');
          var fired = false;
          parsed.addEventListener('load', function () { fired = true; });
          parsed.addEventListener('error', function () { fired = true; });
          var gone = new Image(); gone.onload = function () { fired = true; }; gone.onerror = function () { fired = true; };
          gone.src = 'v1-g.png'; gone.removeAttribute('src');
          log('gone ' + gone.complete + ' ' + gone.naturalWidth);
          var swapped = new Image(); swapped.onload = function () { log('swapped onload ' + swapped.naturalWidth + ' ' + swapped.src.slice(-6)); };
          swapped.src = 'missing.png'; swapped.src = U;
          setTimeout(function () { log('parsed or gone fired=' + fired + ' parsed complete=' + parsed.complete); setTimeout(next, 0); }, 200);
        });
        step(function () {
          var b = document.createElement('img');
          document.body.appendChild(b);
          b.onload = function () { log('appended onload ' + b.naturalWidth + ' width=' + b.width); setTimeout(next, 0); };
          b.setAttribute('src', 'v1-b.png');
        });
    """.trimIndent()

    private val chromium = listOf(
        """script a complete=false natural=0 width=0""",
        """decode pending is a promise [object Promise]""",
        """dcl a complete=false""",
        """a onload 3x2 true interactive""",
        """window load a=3 true width=3""",
        """n after src complete=false natural=0 width=0""",
        """n drawn before load 0,0,0,0""",
        """n pattern before load null""",
        """n pattern before load bad repetition threw SyntaxError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': The provided type ('bogus') is not one of 'repeat', 'no-repeat', 'repeat-x', or 'repeat-y'.""",
        """n onload 3x2 true trusted=true false false width=3x2""",
        """n drawn 0,255,0,255 0,0,255,255""",
        """n pattern [object CanvasPattern]""",
        """bad onerror error true 0 0 false""",
        """draw broken threw InvalidStateError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': The HTMLImageElement provided is in the 'broken' state.""",
        """draw broken bad args threw TypeError: Failed to execute 'drawImage' on 'CanvasRenderingContext2D': 3 arguments required, but only 2 present.""",
        """pattern broken threw InvalidStateError: Failed to execute 'createPattern' on 'CanvasRenderingContext2D': Source image is in the 'broken' state.""",
        """bad decode EncodingError: The source image cannot be decoded.""",
        """empty after src complete=true""",
        """empty onerror true""",
        """draw empty ok""",
        """pattern empty null""",
        """none true 5x7 0 |""",
        """none decode EncodingError: The source image cannot be decoded.""",
        """decode 3 true""",
        """reset onload 3""",
        """reset after set true 3""",
        """reset again true 3""",
        """clone onload 3""",
        """inner complete=false""",
        """inner onload 3 width=3""",
        """gone true 0""",
        """swapped onload 3 SuQmCC""",
        """parsed or gone fired=false parsed complete=true""",
        """appended onload 3 width=3""",
        """done""",
    )

    /** A PNG of 3 by 2 pixels: a red row over a blue row. */
    private val picture = KiteRaster(3, 2, intArrayOf(RED, RED, RED, BLUE, BLUE, BLUE)).encodePng()

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        var now = 0L
        val files = listOf("a", "d", "r", "i", "b", "p", "g").associate { "v1-$it.png" to picture }
        val book = ScriptBooks.chapter(
            """<p id="p">x</p><img id="a" src="v1-a.png" alt="" onload="log('a onload ' + this.naturalWidth + 'x' + this.naturalHeight + ' ' + this.complete + ' ' + document.readyState)"/><script src="a.js"></script>""",
            extraFiles = mapOf("a.js" to script),
            html = html,
            binaryFiles = files,
        )
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }, clock = { now }).also { runners += it }
        runner.chapterOpened(0)
        while (console.none { it.startsWith("C611") } && now < 5_000) {
            now++
            runner.pumpTimers(now)
        }
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.last().lines().drop(1)
    }

    @Test
    fun an_xhtml_chapter_loads_images_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = false).joinToString("\n"))
    }

    @Test
    fun an_html_chapter_loads_images_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium.joinToString("\n"), logged(html = true).joinToString("\n"))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
