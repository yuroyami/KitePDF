package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * FormData over the entry list of a form, the formdata event, and the submit event with its
 * submitter (#531). Each expected line is what headless Chromium logs for the same chapter.
 * Chromium keeps a control in a datalist in the entries, which HTML skips, and so does KitePDF.
 */
class FormDataTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function log(s) { out.push(s); }
        function dump(fd) { var out = []; fd.forEach(function (v, k) { out.push(JSON.stringify(k) + '=' + (typeof v === 'string' ? JSON.stringify(v) : '[' + Object.prototype.toString.call(v) + ' ' + v.name + ' ' + v.size + ' ' + v.type + ']')); }); return out.join(' '); }
        function tryit(name, f) { try { log(name + ' ' + f()); } catch (e) { log(name + ' threw ' + e.name + ': ' + e.message); } }
        var f = document.getElementById('f');
        document.getElementById('ta').value = 'x\ny\r\nz\rw';
        document.getElementById('t2').value = 'p\r\nq';
        tryit('form', function () { return dump(new FormData(f)); });
        tryit('submitter', function () { return dump(new FormData(f, document.getElementById('sb'))); });
        tryit('image', function () { return dump(new FormData(f, document.getElementById('img'))); });
        tryit('bad submitter', function () { return dump(new FormData(f, document.getElementById('t2'))); });
        tryit('other form submitter', function () { return dump(new FormData(f, document.getElementById('osb'))); });
        tryit('none', function () { return dump(new FormData()); });
        tryit('undefined', function () { return dump(new FormData(undefined)); });
        tryit('null', function () { return dump(new FormData(null)); });
        tryit('not form', function () { return dump(new FormData(document.body)); });
        tryit('call', function () { return FormData(); });
        var fd = new FormData();
        fd.append('a', '1'); fd.append('a', 2); fd.append('b\r\nc', 'x\ny');
        fd.append('blob', new Blob(['abc'], { type: 'text/plain' }));
        fd.append('named', new Blob(['ab']), 'n.txt');
        fd.append('file', new File(['q'], 'f.bin', { type: 'x/y' }));
        fd.append('file2', new File(['q'], 'f.bin'), 'renamed');
        tryit('appended', function () { return dump(fd); });
        tryit('get', function () { return fd.get('a') + ' ' + fd.get('zz') + ' ' + fd.getAll('a').length + ' ' + fd.has('a') + ' ' + fd.has('zz'); });
        fd.set('a', 'set'); tryit('set', function () { return dump(fd); });
        fd.delete('blob'); tryit('delete', function () { return dump(fd); });
        tryit('keys', function () { return JSON.stringify(Array.from(fd.keys())); });
        tryit('values', function () { return JSON.stringify(Array.from(fd.values()).map(String)); });
        tryit('entries', function () { return JSON.stringify(Array.from(fd.entries()).map(function (e) { return e[0]; })); });
        tryit('iter', function () { return fd[Symbol.iterator] === fd.entries; });
        tryit('string', function () { return Object.prototype.toString.call(fd) + ' ' + FormData.length + ' ' + FormData.name; });
        tryit('usp', function () { return new URLSearchParams(new FormData(f)).toString(); });
        tryit('append3', function () { fd.append('x', 'y', 'z'); return 'ok'; });
        tryit('append1', function () { fd.append('x'); return 'ok'; });
        tryit('lone', function () { var g = new FormData(); g.append('\uD800', '\uDC00x'); return Array.from(g.keys())[0].charCodeAt(0) + ' ' + g.get('\uFFFD').charCodeAt(0); });
        f.addEventListener('formdata', function (e) { log('formdata event ' + (e instanceof FormDataEvent) + ' ' + (e.formData instanceof FormData) + ' ' + e.bubbles + ' ' + e.cancelable); e.formData.append('extra', 'e'); });
        tryit('with event', function () { return dump(new FormData(f)); });
        tryit('event ctor', function () { var e = new FormDataEvent('formdata', { formData: new FormData() }); return e.formData.has('a'); });

        var h = document.getElementById('h');
        h.addEventListener('submit', function (e) { log('submit ' + (e instanceof SubmitEvent) + ' ' + (e.submitter ? e.submitter.id : null) + ' ' + e.bubbles + ' ' + e.cancelable + ' ' + e.isTrusted); });
        h.addEventListener('formdata', function (e) { log('formdata ' + Array.from(e.formData.keys()).join(',') + ' ' + e.isTrusted); });
        log('requestSubmit'); h.requestSubmit();
        log('requestSubmit hs'); h.requestSubmit(document.getElementById('hs'));
        log('click hs'); document.getElementById('hs').click();
        log('click hi'); document.getElementById('hi').click();
        log('click hm'); document.getElementById('hm').click();
        log('click hd'); document.getElementById('hd').click();
        log('submit'); h.submit();
        log('cancel');
        h.addEventListener('submit', function (e) { e.preventDefault(); }, { once: true });
        h.requestSubmit();
        h.addEventListener('formdata', function (e) { try { new FormData(h); log('nested ok'); } catch (x) { log('nested ' + x.name + ': ' + x.message); } }, { once: true });
        log('construct'); new FormData(h);
        tryit('requestSubmit bad', function () { h.requestSubmit(document.getElementById('hx')); });
        tryit('requestSubmit input', function () { h.requestSubmit(h.querySelector('input')); });
        tryit('se', function () { var e = new SubmitEvent('submit', { submitter: document.body }); return e.submitter === document.body; });
        tryit('se none', function () { return new SubmitEvent('submit').submitter; });
        tryit('se bad', function () { return new SubmitEvent('submit', { submitter: 5 }).submitter; });
        tryit('fde none', function () { new FormDataEvent('formdata'); return 'ok'; });
        tryit('fde bad', function () { new FormDataEvent('formdata', { formData: 5 }); return 'ok'; });
        tryit('disabled', function () { return document.getElementById('hd').matches(':disabled') + ' ' + h.querySelector('[name=inlegend]').matches(':disabled'); });

        console.log(out.join('\n'));
    """.trimIndent()

    private val body = """<form id="f"><input name="a" value="1"/><input type="checkbox" name="b" value="2" checked="checked"/><input type="checkbox" name="b2"/><input type="checkbox" name="b3" checked="checked"/><input name="c" value="3" disabled="disabled"/><input value="noname"/><input name="" value="empty"/><input type="radio" name="r" value="r1"/><input type="radio" name="r" value="r2" checked="checked"/><select name="s"><option>one</option><option selected="selected" value="2v">two</option></select><select name="m" multiple="multiple"><option selected="selected">x</option><option selected="selected" disabled="disabled">y</option><option selected="selected">z</option></select><select name="empty"></select><textarea name="ta" id="ta" dirname="ta.dir"></textarea><input name="t2" id="t2" dirname="t2dir" dir="rtl"/><input type="hidden" name="_charset_" value="x"/><input type="hidden" name="h" value="hv"/><input type="file" name="up"/><input type="submit" name="sb" id="sb" value="Send"/><input type="submit" name="sb2" value="Other"/><button name="bt" value="bv">B</button><button type="button" name="bb" value="x">C</button><button type="reset" name="br" value="r">R</button><input type="image" name="img" id="img"/><input type="button" name="ib" value="ibv"/><input type="reset" name="ir" value="irv"/><fieldset disabled="disabled"><input name="inset" value="v"/></fieldset><datalist><input name="dl" value="dlv"/></datalist><output name="out">o</output><object name="obj"></object><input type="number" name="num" value="5"/><input type="color" name="col"/><input type="range" name="rng"/></form><input name="outside" form="f" value="ov"/><form id="g"><input type="submit" id="osb" name="o"/></form><form id="h" target="sink"><input name="a" value="1"/><button id="hs" name="hs" value="b">S</button><input type="submit" id="hi" name="hi" value="i"/><input type="image" id="hm" name="hm"/><fieldset disabled="disabled"><legend><input name="inlegend" value="l"/></legend><button id="hd" name="hd">D</button></fieldset></form><button id="hx">X</button><script src="a.js"></script>"""

    private val chromium = listOf(
        """form "a"="1" "b"="2" "b3"="on" "r"="r2" "s"="2v" "m"="x" "m"="z" "ta"="x\ny\nz\nw" "ta.dir"="ltr" "t2"="pq" "t2dir"="rtl" "_charset_"="UTF-8" "h"="hv" "up"=[[object File]  0 application/octet-stream] "dl"="dlv" "num"="5" "col"="#000000" "rng"="50" "outside"="ov"""",
        """submitter "a"="1" "b"="2" "b3"="on" "r"="r2" "s"="2v" "m"="x" "m"="z" "ta"="x\ny\nz\nw" "ta.dir"="ltr" "t2"="pq" "t2dir"="rtl" "_charset_"="UTF-8" "h"="hv" "up"=[[object File]  0 application/octet-stream] "sb"="Send" "dl"="dlv" "num"="5" "col"="#000000" "rng"="50" "outside"="ov"""",
        """image "a"="1" "b"="2" "b3"="on" "r"="r2" "s"="2v" "m"="x" "m"="z" "ta"="x\ny\nz\nw" "ta.dir"="ltr" "t2"="pq" "t2dir"="rtl" "_charset_"="UTF-8" "h"="hv" "up"=[[object File]  0 application/octet-stream] "img.x"="0" "img.y"="0" "dl"="dlv" "num"="5" "col"="#000000" "rng"="50" "outside"="ov"""",
        """bad submitter threw TypeError: Failed to construct 'FormData': The specified element is not a submit button.""",
        """other form submitter threw NotFoundError: Failed to construct 'FormData': The specified element is not owned by this form element.""",
        """none """,
        """undefined """,
        """null threw TypeError: Failed to construct 'FormData': parameter 1 is not of type 'HTMLFormElement'.""",
        """not form threw TypeError: Failed to construct 'FormData': parameter 1 is not of type 'HTMLFormElement'.""",
        """call threw TypeError: Failed to construct 'FormData': Please use the 'new' operator, this DOM object constructor cannot be called as a function.""",
        """appended "a"="1" "a"="2" "b\r\nc"="x\ny" "blob"=[[object File] blob 3 text/plain] "named"=[[object File] n.txt 2 ] "file"=[[object File] f.bin 1 x/y] "file2"=[[object File] renamed 1 ]""",
        """get 1 null 2 true false""",
        """set "a"="set" "b\r\nc"="x\ny" "blob"=[[object File] blob 3 text/plain] "named"=[[object File] n.txt 2 ] "file"=[[object File] f.bin 1 x/y] "file2"=[[object File] renamed 1 ]""",
        """delete "a"="set" "b\r\nc"="x\ny" "named"=[[object File] n.txt 2 ] "file"=[[object File] f.bin 1 x/y] "file2"=[[object File] renamed 1 ]""",
        """keys ["a","b\r\nc","named","file","file2"]""",
        """values ["set","x\ny","[object File]","[object File]","[object File]"]""",
        """entries ["a","b\r\nc","named","file","file2"]""",
        """iter true""",
        """string [object FormData] 0 FormData""",
        """usp a=1&b=2&b3=on&r=r2&s=2v&m=x&m=z&ta=x%0Ay%0Az%0Aw&ta.dir=ltr&t2=pq&t2dir=rtl&_charset_=UTF-8&h=hv&up=%5Bobject+File%5D&dl=dlv&num=5&col=%23000000&rng=50&outside=ov""",
        """append3 threw TypeError: Failed to execute 'append' on 'FormData': parameter 2 is not of type 'Blob'.""",
        """append1 threw TypeError: Failed to execute 'append' on 'FormData': 2 arguments required, but only 1 present.""",
        """lone 65533 65533""",
        """formdata event true true true false""",
        """with event "a"="1" "b"="2" "b3"="on" "r"="r2" "s"="2v" "m"="x" "m"="z" "ta"="x\ny\nz\nw" "ta.dir"="ltr" "t2"="pq" "t2dir"="rtl" "_charset_"="UTF-8" "h"="hv" "up"=[[object File]  0 application/octet-stream] "dl"="dlv" "num"="5" "col"="#000000" "rng"="50" "outside"="ov" "extra"="e"""",
        """event ctor false""",
        """requestSubmit""",
        """submit true null true true true""",
        """formdata a,inlegend true""",
        """requestSubmit hs""",
        """submit true hs true true true""",
        """formdata a,hs,inlegend true""",
        """click hs""",
        """submit true hs true true true""",
        """formdata a,hs,inlegend true""",
        """click hi""",
        """submit true hi true true true""",
        """formdata a,hi,inlegend true""",
        """click hm""",
        """submit true hm true true true""",
        """formdata a,hm.x,hm.y,inlegend true""",
        """click hd""",
        """submit""",
        """formdata a,inlegend true""",
        """cancel""",
        """submit true null true true true""",
        """construct""",
        """formdata a,inlegend true""",
        """nested InvalidStateError: Failed to construct 'FormData': The form is constructing entry list.""",
        """requestSubmit bad threw NotFoundError: Failed to execute 'requestSubmit' on 'HTMLFormElement': The specified element is not owned by this form element.""",
        """requestSubmit input threw TypeError: Failed to execute 'requestSubmit' on 'HTMLFormElement': The specified element is not a submit button.""",
        """se true""",
        """se none null""",
        """se bad threw TypeError: Failed to construct 'SubmitEvent': Failed to read the 'submitter' property from 'SubmitEventInit': Failed to convert value to 'HTMLElement'.""",
        """fde none threw TypeError: Failed to construct 'FormDataEvent': 2 arguments required, but only 1 present.""",
        """fde bad threw TypeError: Failed to construct 'FormDataEvent': Failed to read the 'formData' property from 'FormDataEventInit': Failed to convert value to 'FormData'.""",
        """disabled true false""",
    )

    private fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(body, extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    @Test
    fun an_xhtml_chapter_builds_form_data_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = false))
    }

    @Test
    fun an_html_chapter_builds_form_data_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = true))
    }
}
