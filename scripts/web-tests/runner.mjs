// Runs one linked Kotlin/JS test module in Node and reports every test.
// kotlin-test reports tests to Mocha-style describe/it functions when they exist, so this file defines them.
// Usage: node runner.mjs <module>_test.mjs
import { pathToFileURL } from "node:url";

const root = { name: "", children: [], skipped: false };
let current = root;

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

await import(pathToFileURL(process.argv[2]).href);

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
        console.log(`Failed ${name}: ${error?.stack ?? error}`);
      }
    }
  }
}

await run(root, "");
console.log(`${counts.passed} passed, ${counts.failed} failed, ${counts.ignored} ignored`);
process.exitCode = counts.failed > 0 || counts.passed === 0 ? 1 : 0;
