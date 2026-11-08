package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * An event's state sits behind read-only getters of `Event.prototype`, `isTrusted` is its one
 * own property, and an event that is being dispatched cannot be dispatched again
 * (DOM Standard, 2.2 and 2.7; #876).
 */
class EventStateTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private suspend fun run(script: String): Pair<List<String>, List<String>> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("""<p id="p">text</p><script>$script</script>""")
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        return console to runner.failures.mapNotNull { it.message }
    }

    @Test
    fun the_state_of_an_event_is_not_its_own_and_cannot_be_written(): TestResult = scriptTest {
        val (log, failures) = run(
            """
            console.log(JSON.stringify(new Event('a')));
            console.log(JSON.stringify(Object.keys(new Event('a'))));
            var ev = new Event('x', { cancelable: true }); ev.target = 'forged'; ev.defaultPrevented = true; ev.type = 'y';
            console.log(ev.target + ' ' + ev.defaultPrevented + ' ' + ev.type);
            ev.preventDefault();
            console.log(ev.defaultPrevented + ' ' + ev.returnValue);
            console.log(('type' in Event.prototype) + ' ' + ('isTrusted' in Event.prototype) + ' ' + Object.getOwnPropertyNames(ev).join());
            var d = Object.getOwnPropertyDescriptor(ev, 'isTrusted');
            console.log(typeof d.get + ' ' + d.configurable);
            try { Object.getOwnPropertyDescriptor(Event.prototype, 'type').get.call({}); } catch (e) { console.log(e.name); }
            """.trimIndent(),
        )
        assertEquals(emptyList(), failures)
        assertEquals(
            listOf(
                """{"isTrusted":false}""",
                """["isTrusted"]""",
                "null false x",
                "true false",
                "true false isTrusted",
                "function false",
                "TypeError",
            ),
            log,
        )
    }

    @Test
    fun an_event_being_dispatched_cannot_be_dispatched_again(): TestResult = scriptTest {
        val (log, failures) = run(
            """
            var p = document.getElementById('p'), depth = 0, err = 'none', seen = [];
            p.addEventListener('x', function (e) {
              depth++;
              seen.push(e.target === p, e.currentTarget === p, e.eventPhase);
              if (depth < 3) { try { p.dispatchEvent(e); } catch (x) { err = x.name; } }
            });
            var ev = new Event('x');
            p.dispatchEvent(ev);
            console.log(depth + ' ' + err + ' ' + seen.join());
            console.log(ev.eventPhase + ' ' + ev.currentTarget + ' ' + (ev.target === p));
            p.dispatchEvent(ev);
            console.log(depth);
            var stopped = new Event('s', { bubbles: true }), runs = 0;
            document.body.addEventListener('s', function () { runs++; });
            p.addEventListener('s', function (e) { e.stopPropagation(); });
            p.dispatchEvent(stopped);
            console.log(runs + ' ' + stopped.cancelBubble);
            """.trimIndent(),
        )
        assertEquals(emptyList(), failures)
        assertEquals(listOf("1 InvalidStateError true,true,2", "0 null true", "2", "0 false"), log)
    }
}
