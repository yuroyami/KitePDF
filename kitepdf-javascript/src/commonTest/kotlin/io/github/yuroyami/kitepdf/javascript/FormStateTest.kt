package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * A form control's checkedness, selectedness, value and indeterminate flag are state, not
 * attributes, and the selectors read that state (#552). Each expected line is what headless
 * Chromium logs for the same chapter.
 */
class FormStateTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function ${"$"}(id) { return document.getElementById(id); }
        function m(el, s) { return el.matches(s); }
        var c = ${"$"}('c'), o1 = ${"$"}('o1'), o2 = ${"$"}('o2'), t = ${"$"}('t'), r1 = ${"$"}('r1'), r2 = ${"$"}('r2'), ta = ${"$"}('ta'), n = ${"$"}('n'), f = ${"$"}('f');
        out.push('start ' + [m(c, ':checked'), m(c, ':default'), m(o1, ':checked'), m(t, ':placeholder-shown'), m(t, ':invalid'), m(r1, ':indeterminate'), m(ta, ':placeholder-shown'), m(n, ':out-of-range')].join(','));
        c.checked = true;
        out.push('checked ' + [c.getAttribute('checked'), c.outerHTML.replace(/ xmlns="[^"]*"/, ''), m(c, ':checked'), m(c, ':default'), document.querySelectorAll('input:checked').length].join('|'));
        c.checked = false; c.setAttribute('checked', '');
        out.push('attr ' + [c.checked, m(c, ':checked'), m(c, ':default'), c.defaultChecked].join(','));
        o2.selected = true;
        out.push('select ' + [m(o2, ':checked'), m(o1, ':checked'), o2.getAttribute('selected'), ${"$"}('s').value, ${"$"}('s').selectedIndex].join(','));
        t.value = 'typed';
        out.push('value ' + [m(t, ':placeholder-shown'), m(t, ':invalid'), m(t, ':valid'), t.getAttribute('value'), m(f, ':invalid')].join(','));
        r1.click();
        out.push('radio ' + [r1.getAttribute('checked'), m(r1, ':checked'), m(r2, ':indeterminate'), m(r1, ':indeterminate'), r2.checked].join(','));
        r2.checked = true;
        out.push('radio2 ' + [r1.checked, m(r1, ':checked'), m(r2, ':checked')].join(','));
        c.indeterminate = true;
        out.push('indeterminate ' + [m(c, ':indeterminate'), c.hasAttribute('indeterminate')].join(','));
        ta.value = 'x';
        out.push('textarea ' + [m(ta, ':placeholder-shown'), ta.textContent, m(ta, ':valid')].join(','));
        n.value = '50';
        out.push('number ' + [m(n, ':out-of-range'), m(n, ':in-range'), n.getAttribute('value')].join(','));
        var cl = c.cloneNode(); 
        out.push('clone ' + [cl.checked, cl.indeterminate, t.cloneNode().value].join(','));
        f.reset();
        out.push('reset ' + [c.checked, m(c, ':checked'), m(o1, ':checked'), m(o2, ':checked'), t.value, m(t, ':placeholder-shown'), r2.checked, c.indeterminate, ta.value, n.value].join(','));
        c.removeAttribute('checked');
        out.push('after ' + [c.checked, m(c, ':checked')].join(','));
        console.log(out.join('\n'));
    """.trimIndent()

    private val body = "<form id=\"f\"><input type=\"checkbox\" id=\"c\"/><select id=\"s\"><option id=\"o1\">a</option><option id=\"o2\">b</option></select><input id=\"t\" required=\"required\" placeholder=\"p\"/><input type=\"radio\" name=\"g\" id=\"r1\"/><input type=\"radio\" name=\"g\" id=\"r2\"/><textarea id=\"ta\" placeholder=\"q\" required=\"required\"></textarea><input type=\"number\" id=\"n\" min=\"0\" max=\"10\" value=\"5\"/></form>" + "<script src=\"a.js\"></script>"

    private fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(body, extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    @Test
    fun an_xhtml_chapter_keeps_control_state_apart_from_attributes(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "start false,false,true,true,true,true,true,false",
                "checked |<input type=\"checkbox\" id=\"c\" />|true|false|1",
                "attr false,false,true,true",
                "select true,false,,b,1",
                "value false,false,true,,true",
                "radio ,true,false,false,false",
                "radio2 false,false,true",
                "indeterminate true,false",
                "textarea false,,true",
                "number true,false,5",
                "clone false,true,typed",
                "reset true,false,true,false,,true,false,true,,5",
                "after false,false",
            ),
            logged(html = false),
        )
    }

    @Test
    fun an_html_chapter_keeps_control_state_apart_from_attributes(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "start false,false,true,true,true,true,true,false",
                "checked |<input type=\"checkbox\" id=\"c\">|true|false|1",
                "attr false,false,true,true",
                "select true,false,,b,1",
                "value false,false,true,,true",
                "radio ,true,false,false,false",
                "radio2 false,false,true",
                "indeterminate true,false",
                "textarea false,,true",
                "number true,false,5",
                "clone false,true,typed",
                "reset true,false,true,false,,true,false,true,,5",
                "after false,false",
            ),
            logged(html = true),
        )
    }
}
