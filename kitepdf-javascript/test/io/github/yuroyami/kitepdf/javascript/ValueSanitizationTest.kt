package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * An input's value is sanitized by its type, and a textarea's value has LF line breaks, for a
 * script and for the selectors alike (#605). Each expected line is what headless Chromium logs
 * for the same chapter, but for the one line marked below, where KitePDF follows HTML.
 */
class ValueSanitizationTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function log(s) { out.push(s); }
        var cases = [
          ['text', 'a\r\nb\nc\rd'], ['search', ' a\nb '], ['tel', 'x\ry'], ['password', 'p\nq'], ['url', '  http://x/\n  '], ['email', ' a@b.c \n'],
          ['email', ' a@b.c , d@e.f ', 'multiple'], ['number', '1e3'], ['number', ' 5'], ['number', 'abc'], ['number', '-0'], ['number', '1.'], ['number', '.5'],
          ['range', ''], ['range', '200'], ['range', '-3'], ['range', '7.4'], ['range', 'x'], ['range', '7', null, '0', '10', '5'], ['range', '', null, '10', '0'],
          ['range', '', null, '0', '7', '3'],
          ['color', ''], ['color', '#ABCDEF'], ['color', 'red'], ['color', '#abc'], ['date', '2024-02-30'], ['date', '2024-02-29'], ['date', ' 2024-01-01'],
          ['month', '2024-13'], ['month', '2024-12'], ['week', '2024-W53'], ['week', '2020-W53'], ['time', '13:05'], ['time', '13:05:00.000'], ['time', '25:00'],
          ['datetime-local', '2024-01-01T10:00'], ['datetime-local', '2024-01-01 10:00'], ['datetime-local', '2024-01-01T10:00:00.000'], ['datetime-local', '2024-01-01T10:00:30'],
          ['hidden', ' a\nb '], ['checkbox', ' a\nb '], ['submit', 'a\nb'], ['file', '']
        ];
        for (var i = 0; i < cases.length; i++) {
          var c = cases[i], el = document.createElement('input');
          el.type = c[0]; if (c[2]) el.setAttribute(c[2], ''); if (c[3] != null) el.min = c[3]; if (c[4] != null) el.max = c[4]; if (c[5] != null) el.step = c[5];
          el.setAttribute('value', c[1]);
          var attrValue = el.value;
          var el2 = document.createElement('input'); el2.type = c[0]; if (c[2]) el2.setAttribute(c[2], ''); if (c[3] != null) el2.min = c[3]; if (c[4] != null) el2.max = c[4]; if (c[5] != null) el2.step = c[5];
          try { el2.value = c[1]; } catch (e) { el2 = { value: 'threw ' + e.name }; }
          log(c[0] + (c[2] ? '+' + c[2] : '') + (c[3] != null ? ' [' + c[3] + ',' + c[4] + ',' + c[5] + ']' : '') + ' ' + JSON.stringify(c[1]) + ' attr=' + JSON.stringify(attrValue) + ' set=' + JSON.stringify(el2.value));
        }
        var ta = document.createElement('textarea');
        ta.value = 'a\r\nb\rc\nd';
        log('textarea ' + JSON.stringify(ta.value) + ' ' + ta.textLength + ' ' + JSON.stringify(ta.defaultValue));
        var tb = document.createElement('textarea'); tb.textContent = 'x\r\ny';
        log('textarea default ' + JSON.stringify(tb.value) + ' ' + JSON.stringify(tb.defaultValue));
        var t3 = document.createElement('input'); t3.setAttribute('value', 'a\nb'); t3.type = 'number';
        log('type change ' + JSON.stringify(t3.value));
        var t4 = document.createElement('input'); t4.value = 'a\nb'; t4.type = 'color';
        log('type change dirty ' + JSON.stringify(t4.value));
        var t5 = document.createElement('input'); t5.type = 'range'; t5.min = '0'; t5.max = '10'; t5.step = '3'; t5.value = '8';
        log('range step ' + t5.value + ' ' + t5.valueAsNumber);

        function mk(type, attrs, value) {
          var el = document.createElement('input'); el.type = type;
          for (var k in attrs) el.setAttribute(k, attrs[k]);
          if (value !== undefined) el.value = value;
          document.body.appendChild(el); return el;
        }
        function m(el, list) { return list.map(function (s) { return el.matches(s) ? 1 : 0; }).join(''); }
        var shown = [':placeholder-shown', ':invalid'], range = [':in-range', ':out-of-range', ':invalid'];
        log('ph ' + m(mk('number', { placeholder: 'x', value: 'abc' }), shown));
        log('ph set ' + m(mk('number', { placeholder: 'x' }, '1.'), shown));
        log('range ' + m(mk('number', { min: '0', max: '10', value: ' 50' }), range));
        log('range2 ' + m(mk('number', { min: '0', max: '10', value: '50' }), range));
        log('req ' + m(mk('number', { required: '', value: '1.' }), shown));
        log('req date ' + m(mk('date', { required: '', value: '2024-02-30' }), shown));
        log('req text ' + m(mk('text', { required: '', value: '\n' }), shown));
        var a = mk('text', {}, 'abc'); a.type = 'number'; a.type = 'text';
        log('round trip ' + JSON.stringify(a.value));
        var b = mk('range', { min: '0', max: '10', step: '3' }); b.value = '8'; b.max = '4';
        log('range max ' + b.value);
        var c2 = mk('range', { value: '7.4' });
        log('range base ' + c2.value + ' ' + c2.valueAsNumber);
        log('range tenth ' + mk('range', { step: '0.1' }, '0.35').value);
        log('range any ' + mk('range', { step: 'any' }, '7.4').value);
        log('dtl ' + mk('datetime-local', {}, '2024-01-01T10:00:30.500').value);
        log('color ws ' + mk('color', {}, '  RED ').value);
        log('color alpha ' + mk('color', {}, 'rgb(0 0 255 / 0.5)').value);
        log('color lab ' + mk('color', {}, 'lab(50 0 0)').value);
        log('huge ' + JSON.stringify(mk('number', {}, '1e400').value));
        log('year ' + JSON.stringify(mk('date', {}, '0000-01-01').value) + ' ' + JSON.stringify(mk('date', {}, '10000-01-01').value));
        var k = document.createElement('textarea'); k.setAttribute('placeholder', 'y'); k.value = '\r'; document.body.appendChild(k);
        log('ta ' + JSON.stringify(k.value) + ' ' + k.matches(':placeholder-shown'));
        console.log(out.join('\n'));
    """.trimIndent()

    private val chromium = listOf(
        """text "a\r\nb\nc\rd" attr="abcd" set="abcd"""",
        """search " a\nb " attr=" ab " set=" ab """",
        """tel "x\ry" attr="xy" set="xy"""",
        """password "p\nq" attr="pq" set="pq"""",
        """url "  http://x/\n  " attr="http://x/" set="http://x/"""",
        """email " a@b.c \n" attr="a@b.c" set="a@b.c"""",
        // Chromium answers "" here when the value comes from the attribute. HTML 4.10.5.1.5 joins the trimmed addresses either way.
        """email+multiple " a@b.c , d@e.f " attr="a@b.c,d@e.f" set="a@b.c,d@e.f"""",
        """number "1e3" attr="1e3" set="1e3"""",
        """number " 5" attr="" set=""""",
        """number "abc" attr="" set=""""",
        """number "-0" attr="-0" set="-0"""",
        """number "1." attr="" set=""""",
        """number ".5" attr=".5" set=".5"""",
        """range "" attr="50" set="50"""",
        """range "200" attr="100" set="100"""",
        """range "-3" attr="0" set="0"""",
        """range "7.4" attr="7.4" set="7"""",
        """range "x" attr="50" set="50"""",
        """range [0,10,5] "7" attr="5" set="5"""",
        """range [10,0,undefined] "" attr="10" set="10"""",
        """range [0,7,3] "" attr="3" set="3"""",
        """color "" attr="#000000" set="#000000"""",
        """color "#ABCDEF" attr="#abcdef" set="#abcdef"""",
        """color "red" attr="#ff0000" set="#ff0000"""",
        """color "#abc" attr="#aabbcc" set="#aabbcc"""",
        """date "2024-02-30" attr="" set=""""",
        """date "2024-02-29" attr="2024-02-29" set="2024-02-29"""",
        """date " 2024-01-01" attr="" set=""""",
        """month "2024-13" attr="" set=""""",
        """month "2024-12" attr="2024-12" set="2024-12"""",
        """week "2024-W53" attr="" set=""""",
        """week "2020-W53" attr="2020-W53" set="2020-W53"""",
        """time "13:05" attr="13:05" set="13:05"""",
        """time "13:05:00.000" attr="13:05:00.000" set="13:05:00.000"""",
        """time "25:00" attr="" set=""""",
        """datetime-local "2024-01-01T10:00" attr="2024-01-01T10:00" set="2024-01-01T10:00"""",
        """datetime-local "2024-01-01 10:00" attr="2024-01-01T10:00" set="2024-01-01T10:00"""",
        """datetime-local "2024-01-01T10:00:00.000" attr="2024-01-01T10:00" set="2024-01-01T10:00"""",
        """datetime-local "2024-01-01T10:00:30" attr="2024-01-01T10:00:30" set="2024-01-01T10:00:30"""",
        """hidden " a\nb " attr=" a\nb " set=" a\nb """",
        """checkbox " a\nb " attr=" a\nb " set=" a\nb """",
        """submit "a\nb" attr="a\nb" set="a\nb"""",
        """file "" attr="" set=""""",
        """textarea "a\nb\nc\nd" 7 """"",
        """textarea default "x\ny" "x\r\ny"""",
        """type change """"",
        """type change dirty "#000000"""",
        "range step 9 9",
        "ph 10",
        "ph set 10",
        "range 100",
        "range2 011",
        "req 01",
        "req date 01",
        "req text 01",
        """round trip """"",
        "range max 3",
        "range base 7.4 7.4",
        "range tenth 0.4",
        "range any 7.4",
        "dtl 2024-01-01T10:00:30.5",
        "color ws #000000",
        "color alpha #0000ff",
        "color lab #777777",
        """huge """"",
        """year "" "10000-01-01"""",
        """ta "\n" false""",
    )

    private suspend fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("<script src=\"a.js\"></script>", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    @Test
    fun an_xhtml_chapter_sanitizes_values_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = false))
    }

    @Test
    fun an_html_chapter_sanitizes_values_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = true))
    }
}
