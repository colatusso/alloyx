const assert = require("node:assert/strict");
const Module = require("node:module");
const test = require("node:test");

// The helper under test is pure, but extension.js also loads the VS Code runtime at module
// initialization. Keep this test independent of a running editor with the smallest stub needed
// for that initialization.
const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === "vscode") {
    return {
      window: { createOutputChannel: () => ({}) },
      EventEmitter: class {
        constructor() {
          this.event = () => {};
        }
        fire() {}
      },
    };
  }
  return originalLoad.call(this, request, parent, isMain);
};

const {
  buildRunEvalArgs,
  diagnosticsFromCheckResult,
  parseCheckOutput,
  shouldPublishDiagnostics,
} = require("../out/extension.js");
Module._load = originalLoad;

test("keeps a failed check distinct from a clean empty diagnostics array", () => {
  const diagnostics = diagnosticsFromCheckResult({ code: "ENOENT" }, "[]");

  assert.equal(diagnostics.length, 1);
  assert.equal(diagnostics[0].severity, "ERROR");
  assert.match(diagnostics[0].message, /check failed/i);
});

test("turns malformed check output into a visible diagnostic", () => {
  const diagnostics = parseCheckOutput("not json");

  assert.equal(diagnostics.length, 1);
  assert.equal(diagnostics[0].severity, "ERROR");
  assert.match(diagnostics[0].message, /invalid diagnostics/i);
});

test("keeps a valid clean check empty", () => {
  assert.deepEqual(parseCheckOutput("[]"), []);
});

test("passes --test only for test CodeLens runs", () => {
  assert.deepEqual(buildRunEvalArgs("/classes", true, ["--org", "sandbox"]), [
    "eval",
    "--stdin",
    "--dir",
    "/classes",
    "--test",
    "--org",
    "sandbox",
  ]);
  assert.deepEqual(buildRunEvalArgs("/classes", false, ["--org", "sandbox"]), [
    "eval",
    "--stdin",
    "--dir",
    "/classes",
    "--org",
    "sandbox",
  ]);
});

test("does not publish diagnostics from a stale or closed document", () => {
  assert.equal(shouldPublishDiagnostics(3, 3, false), true);
  assert.equal(shouldPublishDiagnostics(3, 4, false), false);
  assert.equal(shouldPublishDiagnostics(3, 3, true), false);
});
