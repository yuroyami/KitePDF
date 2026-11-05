# KiteJS issues waiting to be filed

These defects and proposals belong to [KiteJS](https://github.com/yuroyami/KiteJS). They come from the November 2026 audits and were proven against KiteJS `5882331`, the source of the 0.7.0 release that KitePDF uses. The session that consumed the audits could not write to the KiteJS repository, so each is kept here as a finished issue: a title, its labels and its body. File each one in KiteJS as it stands, then delete it from this file.

The last ones are security findings (a script that runs past its budget, overflows the stack or slows the host), which the owner chose to report as ordinary public issues, like the KitePDF ones.

## The kitejs-quickjs artifacts ship compiled QuickJS-ng with no copy of its MIT licence, and their POM names only MPL-2.0

Labels: kind:bug (and the KiteJS area label for packaging or docs)

Audit ids: FABLE BUILD-DOCS-TESTS-01

`kitejs-quickjs-jvm-0.7.0.jar` holds five JNI libraries built from QuickJS-ng, which is under the MIT licence. Neither the jar nor the libraries carry that licence, and the POM of the artifact names only the Mozilla Public License 2.0. An app that ships KiteJS has nothing to pass on for QuickJS-ng, and the metadata gives the wrong licence for most of the code in the artifact.

### What happens

- `kitejs-quickjs-jvm-0.7.0.jar` (Maven Central) holds `jni/linux-aarch64/libkitejs_quickjs.so`, `jni/linux-x86_64/libkitejs_quickjs.so`, `jni/macos-aarch64/libkitejs_quickjs.dylib`, `jni/macos-x86_64/libkitejs_quickjs.dylib` and `jni/windows-x86_64/kitejs_quickjs.dll`, beside the classes, `META-INF/MANIFEST.MF` and the `.kotlin_module` file. There is no `LICENSE` or `NOTICE` file.
- None of the five libraries holds the text "Permission is hereby granted" or a copyright line (`grep -a` on each).
- NOTICE says: "QuickJS-ng is Copyright Fabrice Bellard, Charlie Gordon, Ben Noordhuis and the QuickJS-ng contributors, under the MIT license; the full text is in kitejs-quickjs/native/quickjs/LICENSE ... Every build of kitejs-quickjs, the static libraries, the JNI libraries and the WebAssembly module, is compiled from that code." The MIT licence asks that its notice "shall be included in all copies or substantial portions of the Software".
- `library.module-template.yaml` gives every artifact one licence in its POM, `Mozilla Public License, Version 2.0`. `kitejs-quickjs-jvm-0.7.0.pom` names only that. The README's Licence section says QuickJS-ng "keeps its MIT licence".
- At HEAD (`5882331`) the only `resources` folder of a published module is `kitejs-quickjs-android-natives/resources`, which holds a ProGuard file. So the Android, native and wasm artifacts carry no licence text either.

### What should happen

- Each `kitejs-quickjs` artifact carries `META-INF/LICENSE` (MPL-2.0), `META-INF/NOTICE` and the QuickJS-ng MIT licence, for example as `META-INF/LICENSE-quickjs-ng`.
- The POM of `kitejs-quickjs` (and of `kitejs-quickjs-android-natives`) lists both licences: MPL-2.0 and MIT.
- The other KiteJS artifacts carry `LICENSE` and `NOTICE` too, since NOTICE holds the Rhino and Schubfach notices.

### How to reproduce

```
unzip -l kitejs-quickjs-jvm-0.7.0.jar | grep -v '\.class$' | grep -v '/$'
grep -A3 '<license>' kitejs-quickjs-jvm-0.7.0.pom
```

The first lists the manifest, the `.kotlin_module` file and the five libraries; the second prints only the MPL-2.0 entry.

### Notes

Checked on the published 0.7.0 jar and POM and on the module files at HEAD. KitePDF (yuroyami/KitePDF#703) and ImageKodec (yuroyami/ImageKodec#115) have the same gap in their own artifacts.

Found by the November 2026 audits: FABLE BUILD-DOCS-TESTS-01.

## A push to main cancels the CI run of the commit before it, so commits on main can get no CI result

Labels: kind:test-gap

Audit ids: FABLE BUILD-DOCS-TESTS-05

CI cancels a running workflow when a newer one of the same ref starts, and on `main` every push has the same ref. A commit pushed while the run of the one before it is still going cancels that run, so the earlier commit never gets a result.

### What happens

- `.github/workflows/ci.yml` runs on `push` to `main` with `concurrency: group: ci-${{ github.ref }}` and `cancel-in-progress: true`.
- `.github/workflows/quickjs.yml` does the same for pushes to `main` that touch `kitejs-quickjs/**`, with `group: quickjs-${{ github.ref }}`.
- For a push to `main`, `github.ref` is always `refs/heads/main`. A second push cancels the run of the first, with the message "Canceling since a higher priority waiting request for ci-refs/heads/main exists".
- The run of the later commit tests both commits together, so neither has a result of its own, and a later bisect cannot use CI to tell which one broke a test.

### What should happen

A push to `main` runs to the end. Cancelling a superseded run stays right for a pull request, where only the newest push matters. In both workflows:

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
```

`test262.yml` runs on a schedule or by hand, and `docs.yml` already sets `cancel-in-progress: false`; neither needs the change.

### How to reproduce

List the runs of pushes to `main` and their conclusions:

```
gh api -X GET repos/yuroyami/KiteJS/actions/runs -f event=push -f branch=main -f per_page=100
```

A cancelled run whose job annotation gives the message above is a commit that got no result.

### Where

- `.github/workflows/ci.yml`: the `concurrency` block.
- `.github/workflows/quickjs.yml`: the `concurrency` block.

### Notes

Read from the workflows at `5882331`. The run history of KiteJS was not readable from this session, so the count of cancelled runs is not measured here. The same block cancelled 29 of 55 push runs on `main` of KitePDF from 2026-10-05 to 2026-11-05 (yuroyami/KitePDF#754), and 16 of 52 of ImageKodec from 2026-10-07 to 2026-10-21 (yuroyami/ImageKodec#120).

Found by the November 2026 audits: FABLE BUILD-DOCS-TESTS-05.

## Rhino never asks interruptWhen when an instruction budget is set

Labels: kind:bug, area:interpreter, area:coroutines, Rhino

Audit ids: FABLE KITEJS-01

On Rhino, an engine with both `instructionBudget` and `interruptWhen` never asks the hook. A deadline, a stop button or a cancelled coroutine is then ignored until the whole budget is spent.

### What happens

- `RhinoKiteJs.open` sets the observer threshold to the budget itself when there is one: `setInstructionObserverThreshold(if (config.instructionBudget > 0) config.instructionBudget else INTERRUPT_SLICE)`.
- `Context.addInstructionCount` calls the observer only when the pending count reaches the threshold, and hands it the whole count.
- `EngineFactory.observeInstructionCount` adds that count to `instructionsUsed`, finds it at or past the budget on the first call, and throws the budget error. The hook is read only after that test.

Measured on the JVM with the published `kitejs-rhino-jvm` 0.7.0, `evaluate("for (;;) {}")`:

| Engine | Settings | Result | Hook asked |
|---|---|---|---:|
| Rhino | hook that always answers true, no budget | "script was interrupted" | 1 |
| Rhino | the same hook, budget 5,000,000 | "script used more than 5000000 instructions" | 0 |
| Rhino | hook that answers true after 1 s, budget 1,000,000,000 | budget error after 5.7 s | 0 |
| QuickJS | hook that always answers true, budget 5,000,000 | "script was interrupted" | 1 |

So `asyncKiteJs(Rhino) { instructionBudget = n }` cannot be cancelled before n instructions: its cancellation check is an `interruptWhen` hook.

### What should happen

The hook is asked at its own pace whatever the budget is. `docs/limits.md` says "The hook is asked from inside the running script every hundred thousand instructions or so", and `docs/engines.md` says `instructionBudget` and `interruptWhen` "mean the same on both" engines. QuickJS asks the host at every poll whatever the budget.

### How to reproduce

```kotlin
var asked = 0
KiteJs(Rhino) { instructionBudget = 5_000_000; interruptWhen = { asked++; true } }.use { js ->
    assertFailsWith<JsEngineError> { js.evaluate("for (;;) {}") }
}
assertTrue(asked > 0)   // fails: asked is 0
```

### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/RhinoKiteJs.kt`: `open` (lines 366 to 373), `EngineFactory.observeInstructionCount` (417).
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/Context.kt:378` `addInstructionCount`.
- `kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt`: the KDoc of `asyncKiteJs` says cancellation "needs an instruction budget to be set, so one is set for you when you do not name one". The code sets none, and with Rhino a budget is what stops the hook.

### Notes

- A fix keeps the threshold at `min(budget, INTERRUPT_SLICE)` whenever a hook is set, and lets `observeInstructionCount` add up to the budget over several calls. The KDoc of `asyncKiteJs` needs the same correction.
- No test sets both: the `interruptWhen` tests in `ConfigContract`, `AsmBudgetTest`, `PromiseBudgetTest`, `AsmReentryTest` and `AsyncKiteJsTest` set no budget.
- KitePDF runs scripts on QuickJS only and is not affected.
- Related: #15, which made the observer see Promise reaction chains.
- Found by the November 2026 audits: FABLE KITEJS-01.

## JsValue is a value class, so Swift and Objective-C see Any and none of its readers

Labels: kind:api, area:api, Common API

Audit ids: FABLE KITEJS-05

`JsValue` is a `@JvmInline value class`. Kotlin/Native does not export inline classes to Objective-C, so a Swift caller of `evaluate` gets an untyped value and none of `type`, `asString()`, `asDouble()`, `asObject()` or `toKotlin()`.

### What happens

- `JsValue` is declared `@JvmInline public value class JsValue(val raw: Any?)`.
- The Kotlin documentation for Objective-C and Swift interop lists inline classes as unsupported: "arguments are mapped as either underlying primitive type or `id`" (native-objc-interop.md, Unsupported). The same mechanism makes `kotlin.time.Duration` appear as an `Int64` in Swift.
- So `KiteJs.evaluate` is exported as returning `id`, and a Swift caller receives a `JsUndefined`, a `KotlinDouble`, a `String`, a `KBigInt` or a `QuickJsObject`, and has to repeat the engine's coercion rules by hand. `JsObject.get`, `JsArray.values()`, `JsError.value` and the arguments of a host function carry `JsValue` too.
- `tools/apple_frameworks.py` builds `KiteJSApi`, `KiteJSQuickJS` and the other frameworks for this audience, and the README lists iOS and macOS for both engines.

### What should happen

The value API is usable from Swift, or the docs say what Swift sees and how to read it.

### How to reproduce

Link `KiteJSApi.framework` with `python3 tools/apple_frameworks.py --platforms macosArm64` and read its header: `evaluate` returns `id _Nullable`, and there is no `JsValue` class.

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsValue.kt:47`.
- `tools/apple_frameworks.py`, `README.md` (Targets), `docs/getting-started.md`.

### Notes

- `expect`/`actual` cannot change whether a class is inline, so the choices are a regular class everywhere (one allocation per value), or an Apple-facing reader object with the `JsValue` members as functions over `Any?`, exported from the frameworks and documented.
- Traced in the source and the Kotlin documentation, not linked: this needs a Mac.
- The missing `@Throws` is a separate problem: the issue "No KiteJS function declares @Throws, so a script error from Swift ends the app".
- Found by the November 2026 audits: FABLE KITEJS-05.

## No KiteJS function declares @Throws, so a script error from Swift ends the app

Labels: kind:api, area:api, Common API

Audit ids: OPUS KJ-05

No function in KiteJS carries `@Throws`. From Swift or Objective-C, every exception that leaves `evaluate`, including an ordinary script error, terminates the app.

### What happens

- `grep -rn "@Throws" --include=*.kt .` finds nothing.
- `KiteJs.evaluate` throws a `JsException` for every script that throws or does not parse, and a `JsEngineError` when the budget, the hook or the memory limit stops a script, as the class KDoc says. `close()` throws on the wrong thread.
- The Kotlin interop documentation (native-objc-interop.md, Errors and exceptions): "Other Kotlin exceptions reaching Swift/Objective-C are considered unhandled and cause program termination", and "Non-`suspend` functions without `@Throws` don't propagate Kotlin exceptions at all."

So a Swift app that runs a user's script with `js.evaluate(source: "throw 1", fileName: "<eval>")` ends with "Uncaught Kotlin exception", and has no `error:` parameter or `try` to catch it with.

### What should happen

`evaluate`, `compile`, `loadBytecode`, `JsScript.run`, `JsFunction.call` and `invoke`, `JsObject.get` and `set`, `runMicrotasks`, `close` and the other calls into an engine carry `@Throws(JsException::class)`, so Swift sees `throws`.

### How to reproduce

In a framework that exports `kitejs-quickjs`, call `evaluate` from Swift with `throw 1`. Expected from the documentation: the process ends with "Uncaught Kotlin exception".

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/KiteJs.kt` (lines 25 to 41 and the other abstract members), `JsRefs.kt` (`JsObject`, `JsFunction`, `JsScript`).
- The overrides in `kitejs-quickjs/.../facade/QuickJsKiteJs.kt` and `kitejs-rhino/.../facade/RhinoKiteJs.kt` inherit the annotation.

### Notes

- Traced in the source and the Kotlin documentation, not run: this needs an Apple target.
- ImageKodec fixed the same gap in its own API (yuroyami/ImageKodec#37 and yuroyami/ImageKodec#84).
- KitePDF calls KiteJS from Kotlin only and is not affected.
- Found by the November 2026 audits: OPUS KJ-05.

## kitejs-coroutines runs its tests on Rhino only

Labels: kind:test-gap, area:coroutines, QuickJS

Audit ids: FABLE KITEJS-06

The coroutine layer is tested with Rhino alone, so `asyncKiteJs(QuickJs)`, `suspendFunction`, `deferredToPromise` and `await` never run on QuickJS in CI, on any target.

### What happens

- `kitejs-coroutines/module.yaml` has one engine among its test dependencies, `../kitejs-rhino`, with the comment "Any engine will do for the tests".
- `grep -rn QuickJs kitejs-coroutines/test*` finds nothing. The five test files (`AsyncKiteJsTest`, `AwaitTest`, `ShutdownTest`, `CancellationTest`, `EngineThreadTest`) use Rhino.
- `.github/workflows/quickjs.yml` does not list `kitejs-coroutines/**` in its paths, so a change there does not run the QuickJS workflow.

The engines differ in what the coroutine layer relies on: how often and when the interrupt hook is asked (on Rhino it is not asked at all once a budget is set, see the issue "Rhino never asks interruptWhen when an instruction budget is set"), that a QuickJS host function failure surfaces only at the outermost call, QuickJS's own job queue, pausing inside the engine on the web, and freeing a C runtime in `close()`.

### What should happen

The coroutine suite runs against both engines, as the `kitejs-testkit` contracts do.

### How to reproduce

`grep -rn QuickJs kitejs-coroutines/test*` is empty.

### Where

- `kitejs-coroutines/module.yaml` (test dependencies).
- `kitejs-coroutines/test/io/github/yuroyami/kitejs/coroutines/` and `kitejs-coroutines/test@jvm/io/github/yuroyami/kitejs/coroutines/`.
- `.github/workflows/quickjs.yml` (paths).

### Notes

- A fix turns the tests into an abstract suite with a `JsEngine` parameter, instantiates it for Rhino and QuickJS, adds `../kitejs-quickjs` as a test dependency, and adds `kitejs-coroutines/**` to the QuickJS workflow.
- The Apple stack failure in the issue "asyncKiteJs(QuickJs) on Apple puts a 512 KiB stack limit on a 512 KiB thread, so runaway recursion ends the process" is the kind of defect this suite would catch.
- Found by the November 2026 audits: FABLE KITEJS-06.

## A call that throws leaves the Promise reactions it queued, and they run after the next call's own script

Labels: kind:bug, area:api, Rhino, QuickJS, Common API

Audit ids: FABLE KITEJS-07

When a script queues a Promise reaction and then throws, the outermost call neither runs nor drops the reaction. It stays queued and runs at the end of the next call into the engine, after that call's own script.

### What happens

- QuickJS: `QuickJsKiteJs.guarded` drains the job queue only after `body()` returned normally (`if (outermost && drain && drainJobs() < 0) raise()`), and discards it only when the engine stopped the script (`stopReason != STOP_NONE`).
- Rhino: `RhinoKiteJs.call` runs `cx.processMicrotasks()` only after `inTopCall` returned, and `metered` discards the queue only when the budget or the hook stopped the script.
- When the script itself throws, neither path runs. The next outermost call runs its own body first and drains the old reaction afterwards.

Measured on the JVM with the published 0.7.0 jars, two `evaluate` calls on one engine:

| Engine | Call 1 | Call 2 | Answer of call 2 |
|---|---|---|---|
| Rhino | `globalThis.log = []; Promise.resolve().then(function () { log.push('reaction') }); throw new Error('boom')` | `log.push('next'); log.join()` | `next` |
| QuickJS | the same | the same | `next` |
| Rhino, QuickJS | the same | `log.join()` | empty |

A browser runs the reaction at the microtask checkpoint after the script that threw, so the second script reads `reaction,next`.

### What should happen

The KDoc of `KiteJs`: "Every outermost call from the host into the engine ... runs the Promise reactions it queued before it returns." `docs/promises-and-coroutines.md`: the queue "drains when a top level call returns". That holds when the call ends in a script exception too. HTML runs a microtask checkpoint after a script whether or not it threw ("clean up after running script").

### How to reproduce

```kotlin
KiteJs(QuickJs).use { js ->
    assertFailsWith<JsException> {
        js.evaluate("globalThis.log = []; Promise.resolve().then(function () { log.push('reaction') }); throw new Error('boom')")
    }
    assertEquals("reaction,next", js.evaluate("log.push('next'); log.join()").asString())   // "next" today
}
```

### Where

- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt`: `guarded` (line 240).
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/RhinoKiteJs.kt`: `call` (162) and `metered` (204).

### Notes

- A fix drains in the failure path too when the throwable is a script exception and the engine did not stop the script, and keeps the discard on a stop. A reaction that throws then must not replace the original exception.
- Both engines agree, so the shared contracts pass; neither matches a browser.
- Related: #15, which made a stop drop the reactions it left (D-77).
- Found by the November 2026 audits: FABLE KITEJS-07.

## A second class loader in the same JVM cannot load QuickJS

Labels: kind:bug, area:quickjs, QuickJS

Audit ids: FABLE KITEJS-08

The JVM loader copies the JNI library to one shared path per build and loads that path. A JVM refuses to load one library file into two class loaders, so the second class loader that opens a QuickJS engine fails, and there is no fallback.

### What happens

- `loadNativeLibrary` copies the library to `java.io.tmpdir/kitejs-quickjs-<user>/<sha256 prefix>/` with `libraryCopy`, then calls `System.load` on it. `freshCopy`, a private folder per load, is used only when the shared folder cannot be made private.
- An application server, an IDE plugin host or a test harness that isolates class loaders loads `kitejs-quickjs` once per class loader.

Measured on the JVM with the published 0.7.0 jars, each loaded through its own `URLClassLoader` in one process:

| Class loader | `KiteJs(QuickJs)` |
|---|---|
| first | opens |
| second | `UnsatisfiedLinkError: Native Library /tmp/kitejs-quickjs-root/4c21732b158c1345/libkitejs_quickjs.so already loaded in another classloader` |

A temporary folder mounted `noexec` fails the same way, at the first load, with no way to choose another folder short of building a library and naming it with `kitejs.quickjs.library`.

### What should happen

Each class loader can open an engine. On an `UnsatisfiedLinkError` from the shared copy, the loader tries a private copy, which is a distinct file and loads into a distinct class loader. A system property such as `kitejs.quickjs.tmpdir` can choose the folder.

### How to reproduce

Load `kitejs-api`, `kitejs-quickjs` and their dependencies through two `URLClassLoader`s with the platform class loader as parent, and call `KiteJs(QuickJs)` through each.

### Where

- `kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/NativeLibrary.kt`: `loadNativeLibrary` (lines 30 to 65), `libraryCopy` (72), `freshCopy` (124).
- `docs/engines.md`, "Where the native code comes from".

### Notes

- Likely a side effect of #125, which replaced a new temporary copy per load with one shared path to save macOS's 350 ms check; with a copy per load each class loader had its own file. Inferred from #125's description, since the old loader is not in this tree.
- The error type is a separate defect: the issue "When the JNI library cannot load, KiteJs(QuickJs) throws an Error, not a JsEngineError".
- Found by the November 2026 audits: FABLE KITEJS-08.

## When the JNI library cannot load, KiteJs(QuickJs) throws an Error, not a JsEngineError

Labels: kind:bug, area:quickjs, QuickJS

Audit ids: OPUS KJ-02

On a JVM where the QuickJS library cannot be loaded, `KiteJs(QuickJs)` throws `ExceptionInInitializerError` or `UnsatisfiedLinkError` the first time and `NoClassDefFoundError` every time after. None of them is a `JsEngineError`, or even an `Exception`, so a caller that catches `JsException`, `RuntimeException` or `Exception` does not see the loader's message and lets the error through.

### What happens

- `JniBridge` is a Kotlin `object` whose `init` calls `loadNativeLibrary()`, so the load runs in the class's static initializer.
- The JVM wraps an exception thrown there, such as the loader's own `JsEngineError` ("QuickJS has no JNI library for linux on riscv64; set kitejs.quickjs.library ..."), in an `ExceptionInInitializerError`. An `UnsatisfiedLinkError` from `System.load` passes as it is. Every later use of the class throws `NoClassDefFoundError: Could not initialize class ...JniBridge`.
- `QuickJsKiteJs.open` builds the engine object, whose first field is `bridge()`, before its `try`, so nothing translates the error.

Measured on the JVM with the published `kitejs-quickjs-jvm` 0.7.0, two `KiteJs(QuickJs)` calls in one process:

| Setting | First call | Second call |
|---|---|---|
| `-Dkitejs.quickjs.library=/nonexistent/libkitejs_quickjs.so` | `UnsatisfiedLinkError` | `NoClassDefFoundError` |
| `-Dos.arch=riscv64` (no library in the jar) | `ExceptionInInitializerError`, cause `JsEngineError: QuickJS has no JNI library for linux on riscv64 ...` | `NoClassDefFoundError` |

None of the four is a `RuntimeException`.

Ordinary setups reach this: Windows on Arm with an Arm64 JDK (the jar has no `windows-aarch64` library), Alpine (a glibc library on musl), FreeBSD, Linux on riscv64, and an Android app packaged without the device's ABI.

### What should happen

Every attempt to open an engine where no library loads throws a `JsEngineError` with the loader's message, as the docs promise for the web: "Opening an engine on the web before loading throws a `JsEngineError` that says so."

### How to reproduce

A `kitejs-quickjs` JVM test in a forked JVM with `-Dkitejs.quickjs.library=/nonexistent.so`:

```kotlin
@Test
fun aLibraryThatDoesNotLoadIsAnEngineError() {
    repeat(2) { assertFailsWith<JsEngineError> { KiteJs(QuickJs) } }
}
```

### Where

- `kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/JniBridge.kt`: `private object JniBridge` and its `init` (lines 63 to 67), `bridge()` (113).
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt`: `private val bridge = bridge()` (62), `open` (671), which builds the engine before its `try`.
- `kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/NativeLibrary.kt:51` and `:55`, the `JsEngineError`s that get wrapped.

### Notes

- A fix loads the library from a function that remembers its outcome (a lazy holder that stores the failure), not from a static initializer, and has `open` turn a `LinkageError` into a `JsEngineError`. `QuickJs.isLoaded` should answer false then, so a host can ask first.
- `NativeLibraryTest` covers `libraryCopy` only.
- KitePDF is exposed: `PdfScriptRunner.ensureEngine` catches `RuntimeException`, so the error leaves `runDocumentOpen()` as it is (measured), and its viewer's guard catches `Exception`. KitePDF tracks its guard in yuroyami/KitePDF#830.
- Related: #122, which put the message in the `JsEngineError` that is now wrapped.
- Found by the November 2026 audits: OPUS KJ-02.

## Date.parse on Rhino accepts the year -000000, which ECMAScript forbids

Labels: kind:bug, area:interpreter, Rhino

Audit ids: FABLE KITEJS-10

`Date.parse('-000000-01-01T00:00:00Z')` on Rhino answers a time in year 0. The spec makes that string invalid, and QuickJS and V8 answer `NaN`.

### What happens

`NativeDate.parseISOString` reads a leading `-` as an expanded year with `yearmod = -1`. Six zeros then give `year = 0`, and the validity check tests only `year > 275943`, so the string parses as year 0.

Measured on the JVM with the published 0.7.0 jars:

| Expression | Rhino | QuickJS |
|---|---|---|
| `Date.parse('-000000-01-01T00:00:00Z')` | `-62167219200000` | `NaN` |
| `Date.parse('-000000-01-01')` | `-62167219200000` | `NaN` |
| `new Date('-000000-01-01T00:00:00.000Z').getTime()` | `-62167219200000` | `NaN` |
| `Date.parse('+000000-01-01T00:00:00Z')` | `-62167219200000` | `-62167219200000` |
| `Date.parse('-000001-01-01T00:00:00Z')` | `-62198755200000` | `-62198755200000` |

### What should happen

ECMA-262, 21.4.1.32.1 (Expanded Years): "The representation of the year 0 as -000000 is invalid." `Date.parse` answers `NaN`.

### How to reproduce

`Date.parse('-000000-01-01T00:00:00Z')` on Rhino. test262 covers it with `built-ins/Date/parse/year-zero.js` and `built-ins/Date/year-zero.js`.

### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeDate.kt`: `parseISOString`, the sign at line 627 and the check at 735.
- `kitejs-rhino/testResources@jvm/test262.properties`: lists both test262 files as expected to fail, inherited from upstream.

### Notes

- A fix returns `NaN` when the year has six digits, a minus sign and the value 0. The fallback parser must not then accept the string either.
- Upstream Rhino 1.9.1 fails the same tests, so the parity run agrees with it and does not report the difference. Once fixed, the two files come off upstream's list through an entry in `Test262ParityTest`.
- Related: #112 (other `Date.parse` forms).
- Found by the November 2026 audits: FABLE KITEJS-10.

## kite_new's failure path reads a handle table that is missing or not yet filled

Labels: kind:bug, area:quickjs, QuickJS

Audit ids: FABLE KITEJS-11

If one of the two small allocations of the handle table fails in `kite_new`, its clean-up in `kite_free` dereferences a NULL pointer or reads memory that was never written.

### What happens

- `kite_new` sets `e->capacity = 64` (line 346), then allocates `e->slots` and `e->next` and jumps to `fail` if either is NULL. The loop that fills `next` (352 to 356) runs only after both succeeded.
- `fail` calls `kite_free`, which, since `e->ctx` is set, loops `for (i = FIXED_HANDLES; i < e->capacity; i++) if (e->next[i] == -2) JS_FreeValue(e->ctx, e->slots[i])` (lines 379 to 382).
- With `next == NULL` that dereferences NULL. With `slots == NULL` and `next` allocated, it reads uninitialised `next` entries, and any that happens to equal -2 frees `slots[i]` through NULL.

Only a failed allocation of 256 or 1024 bytes reaches it, so this is a robustness fix, not a live crash.

### What should happen

A failed `kite_new` returns NULL and frees what it made, without touching memory it did not write.

### How to reproduce

Read from source, not run. A test with an allocator that fails the `next` allocation would show it.

### Where

- `kitejs-quickjs/native/kitejs_quickjs.c`: `kite_new` (lines 346 to 350 and 365 to 367), `kite_free` (376 to 382).

### Notes

- A fix sets `e->capacity` only after both arrays exist and are filled, or guards the loop in `kite_free` with `e->next && e->slots`.
- Found by the November 2026 audits: FABLE KITEJS-11.

## newPromise compiles its helper script on every call

Labels: kind:perf, area:api, area:coroutines, Common API

Audit ids: FABLE KITEJS-12

`KiteJs.newPromise()` evaluates a small JavaScript source each time, so every call parses it again. `AsyncKiteJs.suspendFunction` calls it once per call of the bound function.

### What happens

`newPromise()` is `evaluate(PROMISE_MAKER, "<promise>")`: a parse and a run each time. `suspendFunction` calls it inside the host function, and `deferredToPromise` calls it per promise, so a script that awaits a suspending host function in a loop parses the same 150 characters each time.

Measured on the JVM with the published 0.7.0 jars, 20,000 calls per round, third round:

| Engine | `evaluate(PROMISE_MAKER)` | The same function compiled once, then called |
|---|---:|---:|
| Rhino | 52 µs each | 8.5 µs each |
| QuickJS | 22 µs each | 11 µs each |

`newPromise()` also reads three properties on top of that, the same in both cases.

### What should happen

The helper is compiled once per engine, or the promise comes from the engine's own API (`JS_NewPromiseCapability` on QuickJS, `NativePromise` on Rhino).

### How to reproduce

Time `js.newPromise()` against a `JsFunction` made once from `(function () { var r, j; var p = new Promise(function (res, rej) { r = res; j = rej }); return { promise: p, resolve: r, reject: j } })`.

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsPromiseSupport.kt`: `PROMISE_MAKER` (line 24) and `newPromise` (29).
- `kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt`: `deferredToPromise` (150) and `suspendFunction` (171).

### Notes

- When called from the host outside any script, `newPromise()` is also an outermost call, so it drains the microtask queue and starts a new budget as a side effect.
- Found by the November 2026 audits: FABLE KITEJS-12.

## No contract test sends a lone surrogate or a NUL through the engines

Labels: kind:test-gap, area:api, Common API

Audit ids: FABLE KITEJS-13

No shared contract test checks that a lone surrogate, a NUL or a pair outside the Basic Multilingual Plane crosses between the host and either engine unchanged. Each platform copies strings its own way, so this is where targets can drift.

### What happens

- `grep` over `kitejs-testkit` and `kitejs-quickjs/test` finds no `\uD800`, `\uDC00` or `\u0000` case.
- QuickJS strings cross as UTF-16 (`JS_NewStringUTF16`, `JS_ToCStringLenUTF16`), but source text is re-encoded for `JS_Eval` and function and file names go through `JS_ToCString`. The JavaScript and Wasm glue copies code units by hand (`src@web/.../WasmGlue.kt`).

A JVM run with the published 0.7.0 jars found that values cross intact on both engines, and two places where they do not:

| Check | Rhino | QuickJS |
|---|---|---|
| `evaluate` of a source holding a raw lone surrogate, as a string literal | intact | intact |
| a raw NUL in a string literal | intact | intact |
| a host string with a lone surrogate, read by the script | intact | intact |
| a host function named `"a\u0000b"`, its `name` | `"a\u0000b"` | `"a"` |
| `var 𝑥 = 2; 𝑥 * 3` | `6` | SyntaxError |

The last two rows are defects of their own: the drafts "QuickJS cuts a host function's name and a script's file name at a NUL" and "QuickJS rejects an identifier with a character outside the Basic Multilingual Plane". Neither was caught by a test.

### What should happen

A `ValuesContract` test round-trips a lone high surrogate, a lone low surrogate, a NUL and a non-BMP pair through `evaluate`, `global[...]`, a host function argument and result, a host function name and a file name, on both engines and every target.

### How to reproduce

The absence of the test. Build such strings with `charArrayOf(...).concatToString()`: a lone surrogate in a Kotlin/JS string literal becomes `?`.

### Where

- `kitejs-testkit/src/io/github/yuroyami/kitejs/testkit/ValuesContract.kt`.

### Notes

- Found by the November 2026 audits: FABLE KITEJS-13.

## QuickJS cuts a host function's name and a script's file name at a NUL

Labels: kind:bug, area:quickjs, QuickJS

Audit ids: FABLE KITEJS-13

On QuickJS, a host function whose name holds a NUL gets a `name` cut at the NUL, and so does the file name given to `evaluate` or `compile`. Rhino keeps both whole.

### What happens

`kite_new_function` turns the UTF-16 name into a C string with `JS_ToCString` and hands it to `JS_NewCFunctionData2`, which reads it up to the first NUL. `kite_compile` does the same with the file name before `JS_Eval`.

Measured on the JVM with the published 0.7.0 jars:

| Check | Rhino | QuickJS |
|---|---|---|
| a host function made with `newFunction("a\u0000b", ...)` and set as `f`: `JSON.stringify(f.name)` | `"a\u0000b"` | `"a"` |
| `evaluate("throw new Error('x')", fileName = "a\u0000b.js")`: the file of the `JsError`'s stack frame | `a\u0000b.js` | `a` |

### What should happen

A function's `name` is the string it was given, as on Rhino. A NUL is a valid character in a JavaScript string.

### How to reproduce

```kotlin
KiteJs(QuickJs).use { js ->
    js.global["f"] = js.newFunction("a\u0000b", 0) { _, _ -> null }
    assertEquals("\"a\\u0000b\"", js.evaluate("JSON.stringify(f.name)").asString())   // "\"a\"" today
}
```

### Where

- `kitejs-quickjs/native/kitejs_quickjs.c`: `kite_new_function` (the `JS_ToCString` of the name, line 789) and `kite_compile` (the file name, line 632).

### Notes

- A fix defines `name` from the UTF-16 string after the function is made (a `JS_DefinePropertyValue` of `JS_ATOM_name`), and passes the file name with its length or replaces a NUL in it.
- Low impact; found while writing the test of the issue "No contract test sends a lone surrogate or a NUL through the engines".
- Found by the November 2026 audits: FABLE KITEJS-13.

## Two QuickJs.load() calls at once on the web decompress and instantiate the module twice

Labels: kind:perf, area:quickjs, QuickJS

Audit ids: FABLE KITEJS-14

On JavaScript and Wasm, a second `QuickJs.load()` that starts before the first has finished decompresses and instantiates the 1 MB WebAssembly module again, and then throws the result away.

### What happens

`glue.load` returns at once only when `exports !== null`, which is set when instantiation ends. Until then each call joins the base64 parts of the module, gunzips it and runs `WebAssembly.instantiate`, and the later `then` drops all but the first result (`if (exports !== null) return null`). `loadBridge` checks `glue.loaded()` first, with the same gap.

### What should happen

The first call keeps its promise, and later calls wait on it.

### How to reproduce

Call `QuickJs.load()` twice without awaiting the first, with a `console.time` around `WebAssembly.instantiate`: it runs twice. Read from source, not run.

### Where

- `kitejs-quickjs/src@web/io/github/yuroyami/kitejs/quickjs/bridge/WasmGlue.kt`: `load` (lines 106 to 130).
- `kitejs-quickjs/src@web/io/github/yuroyami/kitejs/quickjs/bridge/WebBridge.kt`: `loadBridge` (161).

### Notes

- KitePDF calls `KiteJsScriptEngine.load()` from its PDF and EPUB runners, which can start together.
- Found by the November 2026 audits: FABLE KITEJS-14.

## The asm.js profiling probes do not run under ./kotlin test, and no document says how to run them

Labels: kind:test-gap, area:docs, Rhino

Audit ids: FABLE BUILD-DOCS-TESTS-12

Three opt-in probes in `kitejs-rhino/test@jvm` tell the reader to set an environment variable to run them. Under the Kotlin Toolchain that is not enough: the JVM test runner selects classes by name, and these names do not match.

### What happens

- `AsmProfileProbe`, `AsmRealModuleProbe` and `AsmCodeHistogram` hold `@Test` functions gated by `assumeTrue` on `KITEJS_PROFILE` and `KITEJS_ASM_FILE`. Their KDoc says "Opt in with `KITEJS_PROFILE=true`".
- `./kotlin test` on the JVM runs only classes whose names start with `Test` or end with `Test` or `Tests`, JUnit's default class name pattern. KitePDF records this in its CLAUDE.md, and renamed its own probes for it when it moved to the toolchain. So `KITEJS_PROFILE=true ./kotlin test -m kitejs-rhino -p jvm` never reaches the three classes and does not report them as skipped.
- The toolchain's `--include-classes` option replaces that pattern (its help text: "Only run tests classes or suites matching the given pattern"), so `--include-classes='*Probe'` should select two of them. `README.md`, `CONTRIBUTING.md` and `docs/` mention neither the variables nor the option.

### What should happen

A developer who follows the KDoc gets the report. Either the classes get a `Test` suffix (they skip cleanly without the variables) or the KDoc and `CONTRIBUTING.md` give the full command with `--include-classes`.

### How to reproduce

`KITEJS_PROFILE=true ./kotlin test -m kitejs-rhino -p jvm`, then look for `AsmProfileProbe` in the test report. Read from the source and from the toolchain's help text, not run.

### Where

- `kitejs-rhino/test@jvm/io/github/yuroyami/kitejs/api/AsmProfileProbe.kt`, `AsmRealModuleProbe.kt`.
- `kitejs-rhino/test@jvm/io/github/yuroyami/kitejs/rhino/asm/AsmCodeHistogram.kt`.
- `CONTRIBUTING.md`, Run the tests.

### Notes

- The audit said the classes can never run. That part does not hold: `--include-classes` exists and selects classes by any pattern.
- Found by the November 2026 audits: FABLE BUILD-DOCS-TESTS-12.

## QuickJS rejects an identifier with a character outside the Basic Multilingual Plane

Labels: kind:bug, area:quickjs, QuickJS

Audit ids: OPUS KJ-06

On QuickJS, `var 𝑥 = 2; 𝑥 * 3` is a SyntaxError. Any identifier with a character outside the Basic Multilingual Plane fails, because `kite_compile` hands the parser CESU-8 instead of UTF-8.

### What happens

`kite_compile` converts the UTF-16 source with `JS_ToCStringLen2(ctx, &utf8_length, text, true)`. The last argument asks for CESU-8, which writes a surrogate pair as two 3-byte sequences. QuickJS's lexer decodes each half as its own code point, and a surrogate is neither ID_Start nor ID_Continue. String literals survive, since each half is stored as a UTF-16 unit again.

Measured:

| Source | Through `kite_compile` | Plain UTF-8 into `JS_Eval` |
|---|---|---|
| `var 𝑥 = 2; 𝑥 * 3` (U+1D465) | `SyntaxError: unexpected character` | `6` |
| `var 𠮷 = 4; 𠮷` (U+20BB7) | `SyntaxError: unexpected character` | `4` |
| `'😀'.length + ':' + '😀'.codePointAt(0)` | `2:128512` | |
| `var é = 5; é` | `5` | |

The first two columns come from the vendored sources built with gcc. The published `kitejs-quickjs-jvm` 0.7.0 gives the same `JsSyntaxError` for the first row, and Rhino answers `6`.

### What should happen

ECMA-262, 12.7 (Names and Keywords) admits any code point with the ID_Start or ID_Continue property, the supplementary planes included. V8 accepts both sources.

### How to reproduce

In `kitejs-quickjs` (common): `KiteJs(QuickJs).use { assertEquals(6.0, it.evaluate("var 𝑥 = 2; 𝑥 * 3").asDouble()) }`.

### Where

- `kitejs-quickjs/native/kitejs_quickjs.c:631` in `kite_compile`.
- `kitejs-quickjs/native/quickjs/quickjs.c`: `JS_ToCStringLen2`, whose `cesu8` branch keeps a pair as two code points.

### Notes

- A fix passes `false`. In that mode `JS_ToCStringLen2` joins a valid pair into one 4-byte sequence and still writes a lone surrogate as 3 bytes, so string literals with lone surrogates are unchanged.
- No KiteJS test has a source with a supplementary character; see the issue "No contract test sends a lone surrogate or a NUL through the engines".
- Found by the November 2026 audits: OPUS KJ-06.

## Rhino on the JVM and Android leaves an array unsorted when its comparator is inconsistent, and the other targets sort it

Labels: kind:bug, area:interpreter, Rhino

Audit ids: OPUS KJ-07

`a.sort(function () { return Math.random() - 0.5 })` on Rhino is a no-op in some runs on the JVM and Android, and shuffles on every other target. The JVM's TimSort throws on an inconsistent comparator, and `NativeArray.sort` catches that and returns the array untouched.

### What happens

`NativeArray.sort` copies the elements to an `ArrayList`, sorts it with Kotlin's `sortWith`, and on `IllegalArgumentException` returns the array as it was ("A comparator that breaks its own contract; leave the array as it was"). On the JVM and Android `sortWith` is the JDK's TimSort, which throws "Comparison method violates its general contract!" when it notices. Kotlin/Native and Wasm sort with the standard library's merge sort and Kotlin/JS with the JavaScript sort; none of them throws.

Measured on the JVM with the published 0.7.0 jars: 200 runs of a 1000-element shuffle by a random comparator, counting runs that left the array in order:

| Engine | Runs left in order |
|---|---:|
| Rhino on the JVM | 17 of 200 |
| QuickJS on the JVM | 0 of 200 |

### What should happen

ECMA-262, 23.1.3.30 (`Array.prototype.sort`) makes the order implementation-defined for an inconsistent comparator, so this is not a spec violation. But the README promises that an engine runs "the same script the same way on every target", and this is a difference between targets that the JVM parity run cannot see. The same algorithm on every target, one that does not throw, keeps that promise.

### How to reproduce

On Rhino, on the JVM and on JS: `var a = []; for (var i = 0; i < 1000; i++) a.push(i); a.sort(function () { return Math.random() - 0.5 }); a.every(function (v, i) { return v === i })`. On the JVM it is `true` in about one run in twelve, elsewhere practically never.

### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeArray.kt`: `sort` (line 993), `working.sortWith(comparator)` and its `catch` (1005 to 1009).

### Notes

- A merge sort of the port's own, used on every target, removes the difference. Upstream Rhino 1.9.1 has the same `Arrays.sort` and `catch`, so the JVM parity run agrees with the port.
- The other targets were read from the Kotlin standard library, not run.
- Related: #136, another difference between targets.
- Found by the November 2026 audits: OPUS KJ-07.

## A compiled script's native memory is freed only after a host GC, so an engine can run out of memory while every script is garbage

Labels: kind:api, area:api, area:quickjs, QuickJS

Audit ids: SOL KJS-02

`JsScript` has no way to release it. On QuickJS its native function stays in the engine until the host's garbage collector has collected the wrapper and the next call has swept it. A host that loads and runs scripts in a loop fills `memoryLimit` with dead scripts, and the engine then fails with "the engine ran out of memory".

### What happens

- `QuickJsScript` registers its handle with the `HandleCleaner`. Its only release path is a host GC that enqueues the wrapper, then `sweep()` at the start of the next outermost call.
- The wrapper is a few bytes on the host's heap, while the compiled function it holds can be tens of kilobytes of native memory. The host heap can stay far below its own GC trigger while the native side grows.
- `evaluate` releases its compiled handle at once, so only `compile` and `loadBytecode` callers are affected.

Measured on the JVM with the published `kitejs-quickjs-jvm` 0.7.0, `memoryLimit = 16 MiB`, a 64,039-character script with 88,121 bytes of bytecode, up to 2000 runs:

| Each run | Result |
|---|---|
| `evaluate(source)` | 2000 runs |
| `loadBytecode(bytes).run()`, the script not kept | 187 runs, then `JsEngineError: the engine ran out of memory` |
| the same, after `System.gc()` | runs again |

### What should happen

A host can release a compiled script when it is done with it, for example `JsScript.close()` (idempotent, and a closed script that runs throws), or `use { }`. A script the host keeps can still run any number of times.

### How to reproduce

```kotlin
KiteJs(QuickJs) { memoryLimit = 16L shl 20 }.use { js ->
    val source = "(function () { var x = 0; " + "x += 1; ".repeat(8000) + "return x })()"
    val bytes = js.compile(source).bytecode()!!
    repeat(2000) { js.loadBytecode(bytes).run() }   // fails near run 190
}
```

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsRefs.kt:182` `JsScript`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsHandles.kt:197` `QuickJsScript`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt`: `compile` (132), `loadBytecode` (142), `sweep` (281).

### Notes

- Not a leak: a host GC frees everything. The problem is the timing under a native budget.
- KitePDF hits it: `KiteJsScriptEngine` loads a new `JsScript` from its bytecode cache for every run of a source of 32 KB or more. Measured through `PdfScriptRunner.run` on the JVM: the 6,066th run of one 64 KB script action fails with "the engine ran out of memory" under KitePDF's 512 MiB limit, after 2.2 s, and runs again after a GC. KitePDF tracks its side in yuroyami/KitePDF#837.
- Related: #126, which added `bytecode()` and `loadBytecode`.
- Found by the November 2026 audits: SOL KJS-02.

## A host function cannot throw an error that the script can catch

Labels: kind:feature, area:api, area:quickjs, Rhino, QuickJS, Common API

Audit ids: FABLE line 2663, item 1

A host function that implements a specified JavaScript API has no way to throw the `TypeError`, `RangeError` or `DOMException` that the API requires. Whatever it throws ends the whole call from the host, and no `try` in the script sees it.

### What is missing

- The KDoc of `KiteJs` says: "An exception a host function throws is not a JavaScript exception: no `catch` in the script sees it, and it comes out of the call that started the script as itself when it is a [JsException], or inside a [JsEngineError] otherwise." That holds on both engines.
- So every embedder that binds a library with error cases writes the same workaround: the host function returns a marker such as `[1, message]`, and a JavaScript wrapper turns it into a `throw`. KiteJS does this itself for `toLocaleString` on QuickJS (`kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/Prelude.kt`, `localeMethod`: a `Proxy` whose `apply` trap throws `r[1]`, a `RangeError` or a `TypeError` depending on `r[0]`).
- KitePDF's EPUB DOM is built the same way: its preludes construct `DOMException` objects in 83 places, many of them only to rethrow what a host binding reported.

### What it should do

- A way for a host function to throw a script value, for example a `JsThrow(value: JsValue)` exception type (or a factory such as `JsError.toThrow(value)`) that the facades turn into a real thrown value: `JS_Throw` on QuickJS, a `JavaScriptException` on Rhino. A helper builds the usual errors by constructor name and message (`TypeError`, `RangeError`, or any constructor the global object has, such as a `DOMException` the embedder defined).
- Any other exception keeps today's meaning, so an engine failure still ends the call.
- The `toLocaleString` proxy can then call the host directly.

### How to check it is done

A contract test on both engines: a host function that throws `JsThrow` of a `new TypeError('bad')` is caught by `try { f() } catch (e) { e instanceof TypeError && e.message === 'bad' }`, which answers true, and the engine stays usable. An uncaught one comes out of `evaluate` as a `JsError` with name `TypeError`. A host function that throws an `IllegalStateException` still ends the call as before.

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsException.kt`: the new type, next to `JsError`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt` and the host-call path of the native glue (`kitejs-quickjs/native/kitejs_quickjs.c`).
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/`: the host function wrapper.
- `docs/host-objects.md`.

### Notes

Read from source. Related: #117 and #127 (other host API gaps).

Proposed by the November 2026 audits: FABLE, KiteJS lane opportunities.

## An unhandled promise rejection is not reported to the host

Labels: kind:feature, area:api, area:quickjs, Rhino, QuickJS, Common API

Audit ids: FABLE line 2663, item 2

A script that rejects a promise nobody handles fails silently through the API. A browser prints "Uncaught (in promise)" to its console; a KiteJS host learns nothing, so a viewer cannot log or show the failure of a book's or a document's script.

### What is missing

- `KiteJsConfig` (`kitejs-api/src/io/github/yuroyami/kitejs/api/JsEngine.kt`) has `console`, `clock`, `instructionBudget`, `interruptWhen` and the others, and no rejection hook.
- On QuickJS, the native glue never calls `JS_SetHostPromiseRejectionTracker` (`kitejs-quickjs/native/kitejs_quickjs.c` has no call to it), so a rejection with no handler is dropped.
- On Rhino, `Context.unhandledPromiseTracker` (`UnhandledRejectionTracker`) can collect them once `trackUnhandledPromiseRejections` is on, but only through Rhino's own `Context`, not through `KiteJs`.

### What it should do

- `KiteJsConfig.onUnhandledRejection: ((reason: JsValue) -> Unit)?`, null by default.
- On QuickJS, the glue registers the tracker and reports a rejection that is still unhandled when the microtask queue drains after the outermost call, and a later handler takes it back (the tracker's `is_handled` flag), as HTML's "unhandled rejection" step does.
- On Rhino, the facade turns tracking on when the hook is set and reports what the tracker holds at the same point.
- The hook runs on the engine's thread, outside the script, and an exception it throws is ignored.

### How to check it is done

A contract test on both engines: `Promise.reject(new Error('x'))` reports one rejection whose reason has the message `x`; `Promise.reject(1).catch(() => {})` reports none; a rejection handled in a later turn of the same call reports none.

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/JsEngine.kt`: `KiteJsConfig`.
- `kitejs-quickjs/native/kitejs_quickjs.c` and the bridges: the tracker.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/facade/`: `trackUnhandledPromiseRejections` and `UnhandledRejectionTracker`.
- `docs/promises-and-coroutines.md`.

### Notes

Read from source. KitePDF records script failures for a book or a document (`EpubScriptRunner`, `PdfScriptRunner`) and would pass this hook to its failure list.

Proposed by the November 2026 audits: FABLE, KiteJS lane opportunities.

## AsyncKiteJs has no evaluatePausing, and onEngine cannot call it

Labels: kind:feature, area:api, area:coroutines, Common API

Audit ids: FABLE line 2663, item 5

A web host that wants both a suspending engine (`AsyncKiteJs`) and a script that pauses so the page stays responsive (`KiteJs.evaluatePausing`) cannot have them together.

### What is missing

- `KiteJs.evaluatePausing(source, fileName, slice)` is a `suspend` function; on QuickJS with JSPI it pauses about every `slice` and lets the event loop run.
- `AsyncKiteJs` offers `evaluate`, `compile`, `evaluateAwaiting`, `await` and `onEngine`, but no `evaluatePausing`.
- `onEngine(block: (KiteJs) -> T)` takes a block that is not `suspend`, so the block cannot call `evaluatePausing` either.

### What it should do

`AsyncKiteJs.evaluatePausing(source, fileName, slice)` runs on the engine's dispatcher and suspends while the script is paused, with the same rules as `KiteJs.evaluatePausing`: while it is paused, other calls into this engine fail with `JsEngineError`, or wait for it, whichever the docs choose. Cancelling the caller stops the script at its next pause through the interrupt hook, as `evaluate` does. On a platform where the script cannot pause, it runs like `evaluate`.

### How to check it is done

On wasmJs with QuickJS, a test runs a script of a few hundred milliseconds through `AsyncKiteJs.evaluatePausing` while a timer of 10 ms counts: the timer fires during the script. On the JVM the same call gives the same result as `evaluate`. Cancelling the caller during a paused run ends it with `CancellationException`.

### Where

- `kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt`: the new call, beside `evaluate`.
- `docs/promises-and-coroutines.md` and `docs/evaluating.md`.

### Notes

Read from source. Related: #130 (pausing scripts on the web), #131 (the cost of a pause in a browser).

Proposed by the November 2026 audits: FABLE, KiteJS lane opportunities.

## KiteJS has no security policy, though it runs untrusted scripts

Labels: kind:feature, area:docs (use the KiteJS equivalents)

Audit ids: FABLE BUILD-DOCS-TESTS-19

Security: yes

KiteJS runs the JavaScript that documents carry, under an instruction budget so that a script cannot hang the host. It has no `SECURITY.md`, so a reporter who finds a sandbox escape, a budget bypass or a native crash in the QuickJS build has no private channel.

### What happens

- No `SECURITY.md` at the root or in `.github/` of KiteJS (checked at `5882331`). The owner has no public `.github` repository that could supply a default policy.
- KiteJS ships a native engine (QuickJS-ng, compiled into `kitejs-quickjs`) and a ported interpreter (`kitejs-rhino`). KitePDF hands it the scripts of PDF forms and EPUB books, which `docs/javascript.md` of KitePDF calls untrusted input.
- ImageKodec, from the same owner, has a `SECURITY.md` with a private advisory form and a scope.

### What should happen

A `SECURITY.md` that names a private reporting channel (GitHub's private vulnerability reporting, turned on for the repository), the versions that get fixes, and the scope: for example a script that runs past its instruction budget, reaches a host object it was not given, or crashes the native engine.

### How to reproduce

`ls SECURITY.md .github/SECURITY.md` in the KiteJS repository finds none.

### Notes

Read at HEAD of KiteJS (`5882331`). ImageKodec's `SECURITY.md` can serve as the model.

Found by the November 2026 audits: FABLE BUILD-DOCS-TESTS-19.

## The KiteJS workflows run with the default token permissions and keep the checkout credentials

Labels: kind:bug, area:ci (use the KiteJS equivalents)

Audit ids: FABLE BUILD-DOCS-TESTS-20

Security: yes

`ci.yml` and `quickjs.yml` of KiteJS set no `permissions:`, and no checkout in the four workflows sets `persist-credentials: false`. So the jobs run with the repository's default `GITHUB_TOKEN` scope, and the token stays in `.git/config` for every later step, including the build tools that the jobs download and run.

### What happens

| Workflow | Triggers | Top-level `permissions:` | Checkouts | `persist-credentials: false` |
| --- | --- | --- | --- | --- |
| `ci.yml` | `push`, `pull_request` | none | 3 | 0 |
| `quickjs.yml` | `push`, `pull_request`, `workflow_dispatch` | none | 3 | 0 |
| `docs.yml` | `push`, `workflow_dispatch` | `contents: read` (plus Pages) | 1 | 0 |
| `test262.yml` | `schedule`, `workflow_dispatch` | `contents: read` | 2 | 0 |

- Without `permissions:`, a job gets the repository's default token scope, which is read and write unless the repository setting restricts it. A pull request from a fork gets a read-only token either way.
- `actions/checkout` writes the token into `.git/config` unless `persist-credentials: false` is set. The jobs then run the Kotlin Toolchain, which downloads its JDK, Kotlin/Native and Node, and `quickjs.yml` runs C toolchains on the QuickJS sources. Any of these can read the token.
- KitePDF's workflows set `permissions: contents: read` at the top and `persist-credentials: false` on every checkout.

### What should happen

Each workflow sets `permissions: contents: read` at the top (and widens it per job only where a job needs more, as `docs.yml` does for Pages), and each `actions/checkout` sets `persist-credentials: false`.

### How to reproduce

`grep -c "^permissions" .github/workflows/*.yml` and `grep -c "persist-credentials: false" .github/workflows/*.yml` in the KiteJS repository give the counts in the table.

### Notes

Read at HEAD of KiteJS (`5882331`). The scope that the token gets today depends on the repository's Actions setting "Workflow permissions", which the files do not show. The fix is four lines per workflow, as in KitePDF's `ci.yml`.

Found by the November 2026 audits: FABLE BUILD-DOCS-TESTS-20.

## One built-in call on QuickJS runs past the instruction budget and the interrupt hook

Labels: kind:bug, area:quickjs, upstream:quickjs-ng, QuickJS

Audit ids: FABLE KITEJS-02, OPUS KJ-01

Security: yes

A script can loop inside one QuickJS built-in for as long as it likes. Neither `instructionBudget` nor `interruptWhen` is asked while the built-in runs, so one statement holds the engine's thread for hours.

### What happens

QuickJS asks the interrupt handler only from `js_poll_interrupts`. The interpreter polls at calls and backward jumps, and a few built-ins poll in their own loops (`every`, `reduce`, `includes`, `indexOf`, `lastIndexOf`, `find`). The other array built-ins loop over `length` in C with no poll. `kitejs_quickjs.c` charges the budget and asks the host only inside `interrupt_handler`, so both settings are blind while such a loop runs.

On a plain object `length` can be up to 2^53 - 1, and no element has to exist. `join` with an empty separator, `reverse` and `copyWithin` then allocate nothing, so `memoryLimit` does not end them either.

Measured with the vendored QuickJS and `kitejs_quickjs.c` built with gcc `-O2 -include kitejs_config.h`, driven the way `QuickJsKiteJs` drives them (`kite_enter`, `kite_set_budget`, `kite_compile`, `kite_run`). The interrupt hook answers "stop" once one second has passed:

| Script | Result | Hook asked |
|---|---|---|
| `for (;;) {}` | stopped after 1.00 s | 1,623,942 times |
| `Array.prototype.lastIndexOf.call({length: 1e8}, 1)` | stopped after 1.00 s | 246,969 times |
| `Array.prototype.join.call({length: 1e8}, '')` | returned after 1.15 s | once |
| `Array.prototype.reverse.call({length: 1e8})` | returned after 1.43 s | once |
| `Array.prototype.copyWithin.call({length: 1e8}, 0, 1)` | returned after 2.54 s | once |
| `Array.prototype.fill.call({length: 1e7}, 0)` | returned after 1.78 s | once |
| `new Array(2147483647).join('')` | returned after 32.9 s | once |
| `join`, `reverse` and `copyWithin` on `{length: 2**53 - 1}` | still running when killed at 20 s | once |

The same `join` with `instructionBudget = 1000` and no hook was still running when killed at 20 s, while `for (;;) {}` under that budget stops at once. The resident memory of the process stayed at 1.8 MB while `join` ran. The time is linear in the length, so the last rows would run for years.

The published `kitejs-quickjs-jvm` 0.7.0 does the same on the JVM: `Array.prototype.join.call({length: 1e8}, '')` under a budget of 1000 and a one-second hook returns after 2.5 s with the hook never asked, and the `2**53 - 1` forms do not return in 20 s.

### What should happen

The budget and the hook stop any script, a single built-in call included. `docs/limits.md` opens with "Stop a script that will not return" and says "When one call passes the budget it gives up and throws JsEngineError". `KiteJsConfig.interruptWhen` is "how an outside signal reaches a running script".

### How to reproduce

As a test in `kitejs-quickjs` (common), which hangs today:

```kotlin
@Test
fun aBuiltInLoopStopsAtTheBudget() {
    KiteJs(QuickJs) { instructionBudget = 100_000 }.use { js ->
        val start = TimeSource.Monotonic.markNow()
        assertFailsWith<JsEngineError> { js.evaluate("Array.prototype.join.call({ length: 2 ** 53 - 1 }, '')") }
        assertTrue(start.elapsedNow() < 5.seconds)
    }
}
```

The same with `interruptWhen = { deadline.hasPassedNow() }` in place of the budget, and with `reverse` and `copyWithin(0, 1)`.

### Where

- `kitejs-quickjs/native/quickjs/quickjs.c`: `js_array_join` (line 44139, also `toLocaleString`), `js_array_reverse` (44315), `js_array_copyWithin` (44616), `js_array_fill` (43848). Compare `js_array_lastIndexOf` (43984), which polls.
- `kitejs-quickjs/native/quickjs/quickjs.c:8689` `__js_poll_interrupts` and `:8702` `js_poll_interrupts`.
- `kitejs-quickjs/native/kitejs_quickjs.c:163` `interrupt_handler`, the only place the budget is charged.
- `kitejs-quickjs/native/kitejs-quickjs.patch`, where a poll in these loops would go.

### Notes

- The fix fits the existing patch: a `js_poll_interrupts(ctx)` every iteration (the counter makes it cheap) in each length-driven loop that does not poll today. A sweep of `quickjs.c` for `for` and `while` loops over an `int64_t` length that do not poll finds the full list. `String.prototype.padStart`, `repeat` and `Array.from` deserve a look too, though they allocate as they go.
- KitePDF is exposed: every document script runs on QuickJS, and `PdfScriptPolicy.budgetMillis` is enforced only through `interruptWhen`. A PDF whose open script is the `join` above holds the script thread for good, and the runner's `close()` then blocks its caller. Measured through KitePDF's `PdfScriptRunner` on the JVM: `for (;;) {}` stops after 5.2 s, the `join` script is still running after 25 s, and `close()` is still blocked 10 s later. KitePDF tracks its part in the issue KitePDF #996 ("A document script that is one long built-in call runs past the five-second budget, and closing the runner then blocks").
- Rhino has the same gap in its own built-ins: the issue "On Rhino a built-in that loops over a length the script chose is never charged to the budget".
- Related: #15 (Promise reaction chains that skipped the checks on Rhino) and #29 (recursive asm.js work past the budget) closed other ways around the budget.
- Found by the November 2026 audits: FABLE KITEJS-02, OPUS KJ-01.

## On Rhino a built-in that loops over a length the script chose is never charged to the budget

Labels: kind:bug, area:interpreter, Rhino

Audit ids: FABLE KITEJS-02, OPUS KJ-01

Security: yes

On Rhino, `Array.prototype.reverse`, `indexOf`, `lastIndexOf`, `includes` and `copyWithin` loop over a length the script controls without charging the instruction budget, so one call runs past both the budget and `interruptWhen`.

### What happens

Rhino charges the budget only in the interpreter, in `AsmRunner`, in the regexp matcher (`NativeRegExp.kt:2861`) and for microtasks. The generic paths of the `NativeArray` built-ins loop over a `Long` length with `getRawElem`, `hasElem` and `setElem` and never call `cx.addInstructionCount`. So the observer that holds both the budget and the hook is not reached while they run. On a plain object such as `{length: 2**53 - 1}` the loops allocate nothing.

Measured on the JVM with the published `kitejs-rhino-jvm` 0.7.0, with `instructionBudget = 1000` and a hook that answers "stop" after one second:

| Script | Result | Hook asked |
|---|---|---|
| `Array.prototype.includes.call({length: 1e8}, 1)` | `false` after 0.43 s | 0 times |
| `Array.prototype.reverse.call({length: 1e8}).length` | `1e8` after 0.46 s | 0 times |
| `new Array(2147483647).lastIndexOf(1)` | `-1` after 26 s | 0 times |
| `Array.prototype.reverse.call({length: 2**53 - 1})` | still running when killed at 20 s | |
| `Array.prototype.includes.call({length: 2**53 - 1}, 1)` | still running when killed at 20 s | |

A budget of 1000 stops `for (;;) {}` at once.

### What should happen

The budget and the hook stop a single built-in call, as `docs/limits.md` promises for any call ("When one call passes the budget it gives up and throws JsEngineError").

### How to reproduce

```kotlin
KiteJs(Rhino) { instructionBudget = 100_000 }.use { js ->
    js.evaluate("Array.prototype.reverse.call({ length: 2 ** 53 - 1 })")   // never returns
}
```

### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeArray.kt`: `js_reverse` (line 944), `js_indexOf` (1381), `js_lastIndexOf` (1432), `js_includes` (1488), `js_fill` (1528), `js_copyWithin` (1548), each in its generic path after the dense fast path.
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/Context.kt:378` `addInstructionCount`, which the regexp matcher already calls per step.

### Notes

- A fix charges `cx.addInstructionCount` once per iteration or per block of iterations in each length-driven loop, as `NativeRegExp` does. The typed array views and `NativeString` have loops of the same shape and want the same sweep.
- `js_join` is not in the list: its generic path allocates an array of `length` strings, so a large length ends in an `OutOfMemoryError` the facade reports, not in a hang. `js_fill` on a plain object grows the heap until the host runs out of memory, which Rhino cannot limit (`docs/limits.md`, Memory).
- KitePDF runs document scripts on QuickJS only, so this is a KiteJS issue for other embedders. QuickJS has the same gap: the issue "One built-in call on QuickJS runs past the instruction budget and the interrupt hook".
- Traced in the source and measured with the published 0.7.0 jar, whose `RhinoKiteJs` and `EngineFactory` bytecode matches the source at 5882331.
- Found by the November 2026 audits: FABLE KITEJS-02, OPUS KJ-01.

## Releasing collected handles is quadratic, so a script can make the next call run for minutes outside its budget

Labels: kind:bug, kind:perf, area:quickjs, QuickJS

Audit ids: FABLE KITEJS-04, SOL KJS-01

Security: yes

On QuickJS, every handle whose wrapper the host's collector took is queued by copying a list, so freeing n of them costs n²/2 copies. The queue is drained at the start of the next outermost call, before the budget is set, so a script that hands the host many objects makes that next call run for minutes.

### What happens

- `QuickJsKiteJs.collected` is an `AtomicReference<List<Int>>`, and the `HandleCleaner` callback queues each handle with `collected.compareAndSet(now, now + h)`. `List.plus` copies the list, so queueing n handles copies n²/2 elements.
- On the JVM, `HandleCleaner.poll()` drains the `ReferenceQueue` and calls that callback once per handle. `sweep()` runs `poll()` at the start of every outermost call, on the engine's thread, before `bridge.enter` and `setBudget`. On Kotlin/Native the callback runs on the cleaner thread, with the same copying.
- Every object, array or function that reaches the host becomes a wrapper with a handle. That includes every argument of a host function, and every nested value that `toKotlin()` reads.

Measured on the JVM with the published `kitejs-quickjs-jvm` 0.7.0 (the cleaner callback's bytecode calls `CollectionsKt.plus`, as the source does). The host holds n `newObject()` wrappers, drops them, forces a GC, then times the next `evaluate("0")`:

| Wrappers collected | Allocated by the next `evaluate("0")` | Time |
|---:|---:|---:|
| 1,000 | 4.1 MB | 16 ms |
| 4,000 | 64 MB | 108 ms |
| 16,000 | 1.0 GB | 460 ms |
| 64,000 | 16.4 GB | 4.3 s |

The call after it allocates 256 bytes.

A script can choose n. A host function that reads its arguments with `toKotlin()` makes one wrapper per element, so `f(Array.from({length: n}, () => ({})))` queues n handles in one call:

| n | The script's own call | The next `evaluate("0")` after a GC |
|---:|---:|---:|
| 100,000 | 0.2 to 0.9 s | 9.5 to 14.8 s, 40 GB allocated |
| 200,000 | 0.6 s | 286 s, 160 GB allocated |

Neither the budget nor `interruptWhen` reaches the sweep, since it runs before the call starts metering.

### What should happen

Releasing a collected handle costs O(1), and a script cannot buy host work beyond its budget.

### How to reproduce

On the JVM:

```kotlin
KiteJs(QuickJs).use { js ->
    js.global.function("f", 1) { args -> args.forEach { it.toKotlin() }; null }
    js.evaluate("f(Array.from({ length: 200000 }, function () { return {} }))")   // under a second
    repeat(3) { System.gc(); Thread.sleep(100) }
    js.evaluate("0")                                                               // minutes
}
```

### Where

- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/facade/QuickJsKiteJs.kt`: `collected` and the `HandleCleaner` callback (lines 102 to 109), `sweep()` (281), called from `guarded` before `bridge.enter`.
- `kitejs-quickjs/src@jni/io/github/yuroyami/kitejs/quickjs/bridge/JniBridge.kt:121` `HandleCleaner.poll`.
- `kitejs-quickjs/src@native/io/github/yuroyami/kitejs/quickjs/bridge/NativeBridge.kt:213` `HandleCleaner`, whose callback runs on the cleaner thread.
- `kitejs-api/src/io/github/yuroyami/kitejs/api/Converters.kt:235` `toKotlin`, one wrapper per nested value.

### Notes

- On the JVM `poll()` already runs on the engine's thread and can call `bridge.release` directly. Kotlin/Native needs a queue that is cheap to append to from the cleaner thread: a lock-free linked stack, or a growable `IntArray` under a lock, drained in one pass.
- KitePDF is exposed. `KiteJsScriptEngine.defineFunction` reads every host function's arguments with `toKotlin()`, and the Acrobat API leaves its host object `__kite` on the global object. Measured through KitePDF's `PdfScriptRunner` on the JVM: an open script `__kite.hasField(Array.from({length: 100000}, function () { return {} }))` returns in 0.6 s, and the next script, `1 + 1`, takes 8.6 s. With 200,000 objects the open script takes 1.9 s and `1 + 1` takes 178 s. Neither run records a budget failure.
- Found by the November 2026 audits: FABLE KITEJS-04, SOL KJS-01.

## Rhino parses a long decimal string in quadratic time, and the budget cannot stop it

Labels: kind:perf, area:interpreter, Rhino

Audit ids: FABLE KITEJS-09

Security: yes

`DecimalParser` builds one big integer from every significant digit of a decimal string, at a cost that grows with the square of the length. On Rhino, `Number`, `parseFloat`, a numeric literal and `JsValue.asDouble()` all go through it, and the budget is not charged while it runs.

### What happens

- `DecimalParser.parse` keeps every significant digit. A value with more than 15 of them goes to `exactPath`, which calls `KBigInt.parse(significant, 10)` and then divides by a power of ten as long as the digits.
- `KBigInt.parse` multiplies the growing magnitude by 10^9 per chunk of nine digits with `magMulAddSmall`, which allocates a new array each time. The division is schoolbook. Both cost the square of the length.
- A value whose leading digit lies beyond 10^309 returns infinity before any of this, so `Number('9'.repeat(400000))` is fast. A long value inside the range of a double is not, for example `'0.' + '1'.repeat(n)`.

Measured on the JVM with the published `kitejs-rhino-jvm` 0.7.0 and `instructionBudget = 1000`:

| Script | Result | Time |
|---|---|---:|
| `Number('9'.repeat(400000))` | `Infinity` | 0.21 s |
| `Number('0.' + '1'.repeat(100000))` | `0.1111111111111111` | 0.54 s |
| `Number('0.' + '1'.repeat(200000))` | same | 1.38 s |
| `Number('0.' + '1'.repeat(400000))` | same | 6.84 s |
| `Number('0.' + '1'.repeat(800000))` | same | 17.8 s |

QuickJS answers the 800,000-digit case in 0.01 s with its own parser. A budget of 1000 stops `for (;;) {}` at once on Rhino.

### What should happen

The same answer in linear time. Only the first 768 or so significant digits can change the rounding of a double. A parser can keep those and one sticky flag that says whether any dropped digit was not zero, and round from that, as V8 and QuickJS do.

### How to reproduce

```kotlin
KiteJs(Rhino) { instructionBudget = 1000 }.use { js ->
    js.evaluate("Number('0.' + '1'.repeat(800000))")   // 18 s on the JVM
}
```

### Where

- `kitejs-api/src/io/github/yuroyami/kitejs/api/dtoa/DecimalParser.kt`: `parse` (line 51) and `exactPath` (146).
- `kitejs-api/src/io/github/yuroyami/kitejs/api/KBigInt.kt`: `parse` (328) and `magMulAddSmall` (638).
- Callers: `kitejs-rhino/.../ScriptRuntime.kt` (lines 107 and 327), `kitejs-rhino/.../TokenStream.kt:635` for numeric literals, `kitejs-api/.../JsNumbers.kt:57` for `JsValue.asDouble()` on a string with either engine.

### Notes

- The cost is outside every instruction count, as for the other built-in loops of Rhino (the issue "On Rhino a built-in that loops over a length the script chose is never charged to the budget"), but the fix here is the truncation, which makes the work small rather than metered.
- KitePDF runs scripts on QuickJS, so it reaches this only through `JsValue.asDouble()` on a string, which it does not call on script data today.
- The audit's example `Number('9'.repeat(400000))` returns infinity before the slow path, so it does not show the cost; the table uses values inside the range of a double.
- Found by the November 2026 audits: FABLE KITEJS-09.

## asyncKiteJs(QuickJs) on Apple puts a 512 KiB stack limit on a 512 KiB thread, so runaway recursion ends the process

Labels: kind:bug, area:quickjs, area:coroutines, QuickJS

Audit ids: FABLE KITEJS-03, OPUS KJ-03

Security: yes

On iOS and macOS, `asyncKiteJs(QuickJs)` with its defaults runs the engine on a thread whose whole stack is 512 KiB, with QuickJS allowed 512 KiB of recursion below the point where the call entered. A script that recurses without end runs off the thread's stack before QuickJS throws its RangeError, and the process dies.

### What happens

- `asyncKiteJs` opens the engine on an `EngineThread` unless the caller passes a dispatcher. On Kotlin/Native, `EngineThread` is `newSingleThreadContext("KiteJS engine")`. kotlinx.coroutines 1.11.0 builds that on a `Worker`, and the Kotlin/Native runtime starts a worker with `pthread_create(&thread_, nullptr, workerRoutine, this)` (`Worker.cpp` at v2.4.21), so the thread gets the platform's default stack. On Apple that is 512 KiB for a secondary thread.
- `QuickJsConfig.maxStackSize` is 512 KiB by default. `kite_enter` calls `JS_UpdateStackTop` at each outermost call, and QuickJS sets its limit to `stack_top - stack_size` (`update_stack_limit`). The worker has already used part of its stack when the call enters, so the limit lies below the end of the stack, and `js_check_stack_overflow` never fires before the guard page.
- The KDoc of `maxStackSize` says that "a thread with a smaller stack, such as a secondary thread on iOS at 512 KiB, needs a smaller value". Here KiteJS picks both the thread and the value, and `asyncKiteJs` neither lowers the limit nor says so.
- `ErrorsContract.deepRecursionIsARangeErrorTheScriptCanCatch` runs on the test runner's main thread, and no coroutine test recurses or runs on QuickJS.

### What should happen

`docs/engines.md` (QuickJS, Memory and the stack): "A script that recurses past `maxStackSize` gets a `RangeError`, which it can catch like any other." That must hold for the thread KiteJS makes itself.

### How to reproduce

A `kitejs-coroutines` test on `macosArm64` or `iosSimulatorArm64`, with `kitejs-quickjs` as a test dependency:

```kotlin
@Test
fun deepRecursionOnTheEngineThreadIsARangeError() = runTest {
    val js = asyncKiteJs(QuickJs)
    try {
        assertEquals("RangeError", js.evaluate("try { (function f() { return f() + 1 })() } catch (e) { e.name }").asString())
    } finally {
        js.close()
    }
}
```

Expected from the trace: the test process ends with EXC_BAD_ACCESS instead of a failed assertion.

### Where

- `kitejs-coroutines/src@native/io/github/yuroyami/kitejs/coroutines/EngineThread.native.kt`: `newSingleThreadContext`.
- `kitejs-coroutines/src/io/github/yuroyami/kitejs/coroutines/AsyncKiteJs.kt:288` `asyncKiteJs`, `dispatcher: CoroutineDispatcher = EngineThread()`.
- `kitejs-quickjs/src/io/github/yuroyami/kitejs/quickjs/QuickJs.kt:61` `maxStackSize = 512L * 1024`.
- `kitejs-quickjs/native/kitejs_quickjs.c:395` `kite_enter`; `kitejs-quickjs/native/quickjs/quickjs.c:3152` `update_stack_limit`, `:2310` `js_check_stack_overflow`.

### Notes

- Two fixes, either enough: give `EngineThread` on Kotlin/Native a thread with an explicit stack (a `pthread_attr_setstacksize` thread, as KitePDF's script threads use), or clamp the limit in `kite_enter` to the current thread's remaining stack minus a reserve where the platform can say (`pthread_get_stacksize_np` and `pthread_get_stackaddr_np` on Apple, `pthread_getattr_np` on Linux). The Windows `NativeStackProbe` of Rhino is a model.
- Traced in the source, not run: this needs an Apple device or simulator. The 512 KiB default for secondary threads is Apple's documented pthread default; KitePDF's CLAUDE.md records it for its own threads (yuroyami/KitePDF#450).
- KitePDF is not affected: it starts its own script threads with an explicit stack size and passes `maxStackSize` to match (`kitepdf-javascript/src@apple/.../NativeThread.apple.kt`).
- Related: #118 and #119, the same failure on the web and through a host function.
- Found by the November 2026 audits: FABLE KITEJS-03, OPUS KJ-03.

## JSON.parse and JSON.stringify on Rhino recurse with no limit, so deep nesting overflows the host stack

Labels: kind:bug, area:interpreter, Rhino

Audit ids: OPUS KJ-04

Security: yes

On Rhino, `JSON.parse` of a deeply nested text and `JSON.stringify` of a deeply nested object recurse on the host's stack with no depth limit. The script's `catch` never sees the failure: on the JVM the call ends in an engine error, and on Kotlin/Native the process ends.

### What happens

- `JsonParser.readValue` calls `readArray` or `readObject`, which call `readValue` again for each element: one pair of Kotlin frames per level. Nothing counts the depth, and no interpreter frame is entered, so neither `maxCallDepth`, `maxHostCallDepth` nor `hasNativeStackSpace` is consulted.
- `NativeJSON.str`, `jo` and `ja` recurse the same way for `stringify`.
- `'['.repeat(n)` costs a single call of budget, so a short script reaches any depth.

Measured on the JVM with the published `kitejs-rhino-jvm` 0.7.0 and `kitejs-quickjs-jvm` 0.7.0:

| Script | Rhino | QuickJS |
|---|---|---|
| `try { JSON.parse('['.repeat(1e6) + ']'.repeat(1e6)); 'parsed' } catch (e) { e.name }` | `JsEngineError: StackOverflowError` reaches the host | `RangeError: Maximum call stack size exceeded`, caught by the script |
| `var o = {}; for (var i = 0; i < 1e6; i++) o = {a: o}; try { JSON.stringify(o).length } catch (e) { e.name }` | `JsEngineError: StackOverflowError` reaches the host | `RangeError`, caught by the script |

On Kotlin/Native a stack overflow cannot be caught, so the same script ends the process there (read from the platform's rules, not run).

### What should happen

Deep JSON ends in a `RangeError` the script can catch, on every target, as on QuickJS, whose JSON parser and serializer check the stack.

### How to reproduce

On Rhino, on `linuxX64` or `macosArm64`:

```kotlin
KiteJs(Rhino).use { js ->
    js.evaluate("try { JSON.parse('['.repeat(1e6) + ']'.repeat(1e6)); 'parsed' } catch (e) { e.name }")
}
```

Expected `"RangeError"`. The JVM gives a `JsEngineError` today, and a native test process ends.

### Where

- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/json/JsonParser.kt`: `readValue` (line 29), `readObject` (47), `readArray` (88).
- `kitejs-rhino/src/io/github/yuroyami/kitejs/rhino/NativeJSON.kt`: `parse` (74), `str` (204), `jo` (264), `ja` (302).

### Notes

- A depth counter in `JsonParser` and in the stringify state, with the engine's own `RangeError` past a fixed limit, works on every target. Upstream Rhino 1.9.1 has no limit either; there the JVM's `StackOverflowError` ends the call.
- `jo` and `ja` also check for cycles with `state.stack.contains`, a linear scan per level, so stringify of deep data is quadratic before it overflows.
- KitePDF runs scripts on QuickJS only and is not affected.
- Related: #139 (the same missing guard in the source parser) and #120 (recursion at run time). Neither covers JSON.
- Found by the November 2026 audits: OPUS KJ-04.
