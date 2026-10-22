// The browser twin of runner.mjs. kotlin-test reports tests to Mocha-style describe/it functions when they exist,
// so this file defines them, runs every test, and writes the counts into #result for run.py to read.
const root = { name: "", children: [], skipped: false };
let current = root;
const log = document.getElementById("log");
const result = document.getElementById("result");

function suite(name, body, skipped) {
  const node = { name, children: [], skipped: skipped || current.skipped };
  current.children.push(node);
  const parent = current;
  current = node;
  try {
    body();
  } finally {
    current = parent;
  }
}

globalThis.describe = (name, body) => suite(name, body, false);
globalThis.xdescribe = (name, body) => suite(name, body, true);
globalThis.it = (name, body) => current.children.push({ name, body, skipped: current.skipped });
globalThis.xit = (name, body) => current.children.push({ name, body, skipped: true });

const counts = { passed: 0, failed: 0, ignored: 0 };

async function run(node, path) {
  for (const child of node.children) {
    const name = path ? `${path}.${child.name}` : child.name;
    if (child.children) {
      await run(child, name);
    } else if (child.skipped) {
      counts.ignored++;
    } else {
      try {
        // An asynchronous test, such as one that uses runTest, returns a promise.
        await child.body();
        counts.passed++;
      } catch (error) {
        counts.failed++;
        log.textContent += `Failed ${name}: ${error?.stack ?? error}\n`;
      }
    }
  }
}

try {
  const entry = new URLSearchParams(location.search).get("entry");
  await import(entry);
  await run(root, "");
  result.textContent = `RESULT ${counts.passed} passed, ${counts.failed} failed, ${counts.ignored} ignored`;
} catch (error) {
  result.textContent = `RESULT 0 passed, 1 failed, 0 ignored (load error: ${error?.stack ?? error})`;
}
