#!/usr/bin/env python3
"""Run the web tests: JS, wasmJs and wasmWasi in Node, and the browser JS tests in headless Chrome.

`./kotlin test` cannot run JS and wasmWasi tests, and it gives the wasmJs tests of a module 30 seconds in Chrome.
This script links the tests with the toolchain, then runs them: JS in Node through runner.mjs, the browser JS tests
in headless Chrome through browser-runner.html, and wasmJs and wasmWasi through the test entry point that the
module exports. Before a run, it installs the npm packages that the linked code imports into build/node_modules.

Usage: python3 scripts/web-tests/run.py [--platform js|jsBrowser|wasmJs|wasmWasi]... [--module <name>]...
"""
import argparse
import functools
import glob
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
# The modules whose tests run on each platform. The browser JS tests draw on a real Canvas2D.
JS_MODULES = ("kitepdf-core", "kitepdf-pdf", "kitepdf-epub", "kitepdf-cbz", "kitepdf-svg", "kitepdf-xps", "kitepdf",
              "kitepdf-javascript", "kitepdf-net")
JS_BROWSER_MODULES = ("kitepdf-native-renderer",)
WASI_MODULES = ("kitepdf-core", "kitepdf-pdf", "kitepdf-epub", "kitepdf-cbz", "kitepdf-svg", "kitepdf-xps", "kitepdf")
# wasmJs in Node. `./kotlin test -p wasmJs` runs wasmJs in Chrome, but gives each module 30 seconds in all,
# and the kitepdf-pdf tests need more.
# kitepdf-net has no wasmWasi target, since Ktor ships none (#768).
WASM_JS_MODULES = WASI_MODULES + ("kitepdf-javascript", "kitepdf-net")
ARTIFACTS = ROOT / "build/artifacts/CompiledWebArtifact"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def chrome():
    """Chrome from $CHROME, PATH or the usual install folders."""
    candidates = [os.environ.get("CHROME"), shutil.which("google-chrome"), shutil.which("google-chrome-stable"),
                  shutil.which("chromium"), shutil.which("chromium-browser"),
                  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]
    candidates += sorted(glob.glob(str(Path.home() / "Library/Caches/ms-playwright/chromium-*/chrome-mac*/*.app/Contents/MacOS/*")))
    candidates += sorted(glob.glob(str(Path.home() / ".cache/ms-playwright/chromium-*/chrome-linux*/chrome")))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise RuntimeError("No Chrome found. Set CHROME to a Chrome or Chromium executable.")


def run(*args, capture=False):
    print("Running " + " ".join(str(a) for a in args[:3]) + (" ..." if len(args) > 3 else ""), flush=True)
    result = subprocess.run([str(a) for a in args], cwd=ROOT, text=True, check=not capture,
                            stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    return result


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def version_key(version):
    return [int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", version)]


def resolved_klibs(module, platform):
    """The klibs that the tests of [module] resolve on [platform], as the toolchain's dependency graph names them.

    Only these choose the npm packages: the shared cache also holds the klibs of other projects and older revisions,
    which CI restores through `restore-keys` (#694).
    """
    result = run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), "show", "dependencies", "-m", module,
                 "-p", platform, "--include-tests", "--scope=runtime", capture=True)
    if result.returncode != 0:
        print(result.stdout[-4000:], flush=True)
        raise RuntimeError(f"cannot read the dependencies of {module} on {platform}")
    klibs = set()
    # A line names a Maven dependency as group:artifact:version, with " -> version" when another version won.
    # A line that names a module scope first, such as "kitepdf-epub:js:group:artifact:version", repeats the next one.
    coordinate = re.compile(r"(?<![\w.:-])([\w.-]+):([\w.-]+):([\w.+-]+)(?: -> ([\w.+-]+))?(?: \(\*\))?\s*$")
    for line in result.stdout.splitlines():
        found = coordinate.search(line)
        if not found:
            continue
        group, artifact, version = found.group(1), found.group(2), found.group(4) or found.group(3)
        klib = CACHE / ".m2.cache" / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.klib"
        if klib.is_file():
            klibs.add(klib)
    return sorted(klibs)


def npm_versions(module, platform):
    """The npm packages that the klibs of [module] on [platform] list in their package.json, by name.

    Two klibs that ask for different versions of one package get the higher one, and the run says so.
    """
    wanted = {}
    for klib in resolved_klibs(module, platform):
        try:
            with zipfile.ZipFile(klib) as archive:
                package = json.loads(archive.read("package.json"))
        except (KeyError, ValueError, zipfile.BadZipFile):
            continue
        for name, version in package.get("dependencies", {}).items():
            wanted.setdefault(name, {})[version] = klib.name
    versions = {}
    for name, asked in sorted(wanted.items()):
        versions[name] = max(asked, key=version_key)
        if len(asked) > 1:
            choices = ", ".join(f"{v} ({k})" for v, k in sorted(asked.items(), key=lambda e: version_key(e[0])))
            print(f"npm: {module} {platform} asks for {name} {choices}; installing {versions[name]}", flush=True)
    return versions


@functools.cache
def node_builtins():
    """The modules that Node itself provides, which no npm package brings."""
    names = subprocess.run([node(), "-p", "require('module').builtinModules.join('\\n')"], text=True,
                           capture_output=True, check=True).stdout.split()
    return set(names)


def install_npm_packages(output, module, platform):
    """Installs the npm packages that the linked output imports into build/node_modules, where Node finds them.

    The versions come from the klibs that [module] resolved on [platform], read after it is linked. An import that no
    klib of the module names fails the run with its name.
    """
    imported = set()
    for script in output.rglob("*.mjs"):
        for specifier in re.findall(r"""(?:\bfrom|\bimport)\s*\(?\s*['"](@?[a-z0-9][\w.-]*(?:/[\w.-]+)*)['"]""",
                                    script.read_text()):
            parts = specifier.split("/")
            imported.add("/".join(parts[:2]) if specifier.startswith("@") else parts[0])
    imported -= node_builtins()
    if not imported:
        return
    versions = npm_versions(module, platform)
    unknown = sorted(imported - versions.keys())
    if unknown:
        raise RuntimeError(f"{module} {platform} imports {', '.join(unknown)}, which no klib of the module lists")
    missing = []
    for name in sorted(imported):
        installed = ROOT / "build/node_modules" / name / "package.json"
        if not installed.is_file() or json.loads(installed.read_text()).get("version") != versions[name]:
            missing.append(f"{name}@{versions[name]}")
    if missing:
        npm = shutil.which("npm") or str(Path(node()).with_name("npm"))
        run(npm, "install", "--no-save", "--no-package-lock", "--no-audit", "--no-fund", "--prefix", ROOT / "build", *missing)


def js_entry(module):
    kotlin("task", f":{module}:linkJsTest")
    entry = ARTIFACTS / f"{module}jsTestrelease/kotlin-output/{module}_test.mjs"
    install_npm_packages(entry.parent, module, "js")
    return entry


def js_tests(module, executable):
    run(executable, HERE / "runner.mjs", js_entry(module))


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def js_browser_tests(module, browser):
    entry = js_entry(module)
    handler = functools.partial(QuietHandler, directory=str(ROOT))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        base = f"http://127.0.0.1:{server.server_address[1]}"
        query = urllib.parse.urlencode({"entry": "/" + entry.relative_to(ROOT).as_posix()})
        url = f"{base}/scripts/web-tests/browser-runner.html?{query}"
        flags = ["--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                 "--virtual-time-budget=600000", "--dump-dom"]
        if os.environ.get("CI"):
            flags.insert(1, "--no-sandbox")
        result = run(browser, *flags, url, capture=True)
    finally:
        server.shutdown()
    page = result.stdout
    for failure in re.findall(r"Failed [^\n<]*", page):
        print(failure, flush=True)
    found = re.search(r"RESULT (\d+) passed, (\d+) failed, (\d+) ignored([^<]*)", page)
    if not found:
        print(page[-4000:], flush=True)
        raise RuntimeError(f"The browser tests of {module} reported no result")
    passed, failed, ignored = (int(found.group(i)) for i in (1, 2, 3))
    print(f"{module} jsBrowser: {passed} passed, {failed} failed, {ignored} ignored{found.group(4)}", flush=True)
    if failed or passed == 0:
        raise RuntimeError(f"browser tests of {module} failed")


def wasm_tests(module, executable, platform):
    task = platform[0].upper() + platform[1:]
    kotlin("task", f":{module}:link{task}TestDebug")
    entry = ARTIFACTS / f"{module}{platform}Testdebug/kotlin-output/{module}_test.mjs"
    install_npm_packages(entry.parent, module, platform)
    script = f"const tests = await import({entry.as_uri()!r}); tests.startUnitTests();"
    result = run(executable, "--input-type=module", "-e", script, capture=True)
    # kotlin-test for Wasm reports in the TeamCity service message format.
    output = result.stdout
    finished = len(re.findall(r"##teamcity\[testFinished ", output))
    failed = re.findall(r"##teamcity\[testFailed name='([^']*)'", output)
    ignored = len(re.findall(r"##teamcity\[testIgnored ", output))
    for name in failed:
        print(f"Failed {name}", flush=True)
    print(f"{module} {platform}: {finished - len(failed)} passed, {len(failed)} failed, {ignored} ignored", flush=True)
    if result.returncode != 0 or failed or finished == 0:
        print(output[-4000:], flush=True)
        raise RuntimeError(f"{platform} tests of {module} failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("js", "jsBrowser", "wasmJs", "wasmWasi"), action="append",
                        help="run one platform; repeat it for more (default: all four)")
    parser.add_argument("--module", action="append", help="run one module; repeat it for more (default: all)")
    args = parser.parse_args()
    platforms = args.platform or ["js", "jsBrowser", "wasmJs", "wasmWasi"]
    chosen = set(args.module or [])
    pick = lambda modules: [m for m in modules if not chosen or m in chosen]
    failures = []
    lists = {"js": JS_MODULES, "jsBrowser": JS_BROWSER_MODULES, "wasmJs": WASM_JS_MODULES, "wasmWasi": WASI_MODULES}
    # A module this script never tests, or a selection that leaves nothing, must not read as a pass (#691).
    known = set().union(*lists.values())
    unknown = sorted(chosen - known)
    if unknown:
        raise RuntimeError(f"no web tests for {', '.join(unknown)}: the script tests {', '.join(sorted(known))}")
    plan = [(platform, module) for platform in platforms for module in pick(lists[platform])]
    if not plan:
        raise RuntimeError(f"nothing to run: {', '.join(sorted(chosen))} has no tests on {', '.join(platforms)}")
    for platform in platforms:
        selected = pick(lists[platform])
        if selected:
            print(f"{platform}: {', '.join(selected)}", flush=True)

    def attempt(test, module, tool):
        try:
            test(module, tool)
        except (subprocess.CalledProcessError, RuntimeError) as error:
            failures.append(f"{module}: {error}")

    executable = node() if {"js", "wasmJs", "wasmWasi"} & set(platforms) else None
    if "js" in platforms:
        for module in pick(JS_MODULES):
            attempt(js_tests, module, executable)
    if "jsBrowser" in platforms:
        browser = chrome()
        for module in pick(JS_BROWSER_MODULES):
            attempt(js_browser_tests, module, browser)
    for platform, modules in (("wasmJs", WASM_JS_MODULES), ("wasmWasi", WASI_MODULES)):
        if platform in platforms:
            for module in pick(modules):
                attempt(lambda m, tool: wasm_tests(m, tool, platform), module, executable)
    if failures:
        raise RuntimeError("Failed:\n  " + "\n  ".join(failures))


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
