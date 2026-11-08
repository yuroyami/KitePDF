package io.github.yuroyami.kitepdf.epub.script

/**
 * The part of [DOM_PRELUDE] that runs the script elements a script inserts (#935), as the steps
 * of HTML, 4.12.1.1, "prepare the script element", run them. The host runs the script elements
 * the markup holds, in its own order: these are the others.
 */
internal const val DOM_PRELUDE_SCRIPTS: String = """/* ---- scripts that a script inserts, of HTML 4.12.1.1 (#935) ----
   A script element runs once: its number goes into startedScripts when it starts. The parser's
   own scripts, which the host runs, are marked as they are found, and so are the scripts that
   markup parsing made, which never run (HTML, 8.4: innerHTML and its kin). Any other script
   element runs when it is connected to the book's document with a src or some text: an inline
   one at once, one with a src in a task, as an async script, with load or error after it. */
var startedScripts = new Map(), scriptLoads = [];
/* A call of eval by another name evaluates in the global scope, as a script element does. */
var globalEval = global.eval;
/* How many script elements scripts made: until one did, an insertion has none to look for. */
var scriptsMade = 0;
/* The script elements at and under [id], in tree order, walked without recursion, as a tree can nest deep. */
function scriptElementsAt(id) {
  var out = [], stack = [id];
  while (stack.length) {
    var n = ArrayPop(stack), kind = K.kind(n);
    if (kind === 1 && isHtml(n, 'script')) { ArrayPush(out, n); continue; }
    if (kind !== 1 && kind !== 9 && kind !== 11) continue;
    var c = K.children(n) || [];
    for (var i = c.length - 1; i >= 0; i--) ArrayPush(stack, c[i]);
  }
  return out;
}
/* Marks the scripts that markup parsing put at and under [id] as started, so none of them runs. */
function markMarkupScripts(id) {
  var list = scriptElementsAt(id);
  for (var i = 0; i < list.length; i++) if (!MapHas(startedScripts, list[i])) MapSet(startedScripts, list[i], true);
}
/* Prepares each script element at and under [id] that has not started (HTML, 4.12.1.1). */
function prepareScriptsAt(list) {
  for (var i = 0; i < list.length; i++) prepareScript(list[i]);
}
function prepareScript(id) {
  if (MapHas(startedScripts, id) || ownerDocId(id) !== rootId || !K.connected(id)) return;
  var src = K.attr(id, 'src');
  // Without a src and without text it waits: a src set later, or text put in, prepares it then.
  if (src == null && K.text(id) === '') return;
  // A module and a data block do not run, and stay unstarted, as the parser's do.
  if (!K.classicScript(id)) return;
  MapSet(startedScripts, id, true);
  if (src == null) { runScriptCode(id, K.scriptSource(id)); return; }
  var load = { __proto__: null, id: id, done: false };
  ArrayPush(scriptLoads, load);
  queueTask(function* () { for (var g = scriptLoadSteps(load); !GeneratorNext(g).done;) yield; });
}
/* The task that runs a script element's src, and fires load, or error when the book has no such file. */
function* scriptLoadSteps(load) {
  if (load.done) return;
  load.done = true;
  var i = ArrayIndexOf(scriptLoads, load);
  if (i >= 0) listRemoveAt(scriptLoads, i);
  var code = K.scriptSource(load.id);
  if (code != null) runScriptCode(load.id, code);
  var event = new Event(code != null ? 'load' : 'error');
  trustEvent(event);
  for (var g = dispatchSteps(wrap(load.id), event); !GeneratorNext(g).done;) yield;
}
/* Evaluates [code], a name and a source, as the script [id] runs it: currentScript is [id] meanwhile. */
function runScriptCode(id, code) {
  if (code == null) return;
  var before = document.__script;
  hidden(document, '__script', wrap(id));
  try { globalEval(code[1]); } catch (e) { report(e); } finally { hidden(document, '__script', before); }
}
/* Runs every script load still waiting, as the window's load event waits for them (HTML, 4.12.1.1). */
function* pendingScriptSteps() {
  while (scriptLoads.length) for (var g = scriptLoadSteps(scriptLoads[0]); !GeneratorNext(g).done;) yield;
}
/* The scripts the parser found in the markup, which the host runs itself. */
hostEntry('__kite_parser_scripts', function (ids) {
  for (var i = 0; i < ids.length; i++) MapSet(startedScripts, ids[i], true);
});
"""
