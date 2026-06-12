import * as vscode from "vscode";
import * as path from "path";
import * as fs from "fs";
import * as os from "os";
import { execFile, spawn } from "child_process";

// ---------------------------------------------------------------------------
// Types mirroring the JSON emitted by `allx outline <file> --json`.
// We only declare the fields we actually consume — the CLI may emit more.
// ---------------------------------------------------------------------------
interface OutlineParam {
  type: string;
  name: string;
}

interface OutlineMethod {
  name: string;
  line: number; // 1-based line of the method signature
  static: boolean;
  isTest: boolean;
  returnType?: string;
  params?: OutlineParam[];
}

interface Outline {
  file: string;
  class: string;
  methods: OutlineMethod[];
}

// Where installers put allx. A GUI-launched VS Code on macOS doesn't inherit the
// login shell's PATH (and Reload Window doesn't re-capture it), so a fresh
// `brew install` is invisible to a bare PATH lookup until the app fully restarts.
// When the setting is the bare default, resolve against these install dirs first.
const CLI_INSTALL_DIRS = [
  "/opt/homebrew/bin", // Homebrew, Apple Silicon
  "/usr/local/bin", // Homebrew, Intel mac / common Linux prefix
  path.join(os.homedir(), ".local", "bin"),
];

let resolvedCli: string | undefined;

/**
 * The CLI to execute. An explicit setting (path or custom name) is used as-is;
 * the bare default "allx" is resolved to a known install location when one
 * exists, else left to the process's PATH lookup.
 */
function cliPath(): string {
  const configured = vscode.workspace.getConfiguration("alloyx").get<string>("cliPath", "allx");
  if (configured !== "allx") {
    return configured;
  }
  if (!resolvedCli) {
    for (const dir of CLI_INSTALL_DIRS) {
      const candidate = path.join(dir, "allx");
      if (fs.existsSync(candidate)) {
        resolvedCli = candidate;
        break;
      }
    }
  }
  return resolvedCli ?? configured;
}

// ---------------------------------------------------------------------------
// Onboarding: the extension is a thin client over the `allx` CLI — without it
// nothing works, and the failure mode would otherwise be silent (no lenses, no
// diagnostics). When any CLI spawn fails with ENOENT we tell the user how to
// install it, once per session, with the right instruction per platform.
// ---------------------------------------------------------------------------
const RELEASES_URL = "https://github.com/colatusso/alloyx/releases/latest";
const BREW_CMD = "brew install colatusso/alloyx/allx";
const BREW_UPGRADE_CMD = "brew upgrade allx";
let cliMissingShown = false;

// Lowest CLI version this extension is built against. Bumped at release time when
// the extension starts to rely on a newer CLI feature/output. Warning-only.
const MIN_CLI_VERSION = "0.2.0";
let cliOutdatedShown = false;

function notifyCliMissing(): void {
  if (cliMissingShown) {
    return;
  }
  cliMissingShown = true;
  const isMac = process.platform === "darwin";
  const actions = isMac
    ? ["Copy brew command", "Open releases", "Set CLI path"]
    : ["Download CLI", "Set CLI path"];
  void vscode.window
    .showWarningMessage(
      isMac
        ? `AlloyX needs the allx CLI. Install it with Homebrew: ${BREW_CMD}`
        : "AlloyX needs the allx CLI. Download the release zip, unpack it and put bin/ on your PATH (or set alloyx.cliPath).",
      ...actions
    )
    .then((pick) => {
      if (pick === "Copy brew command") {
        void vscode.env.clipboard.writeText(BREW_CMD);
        void vscode.window.showInformationMessage("Copied — paste it in a terminal.");
      } else if (pick === "Open releases" || pick === "Download CLI") {
        void vscode.env.openExternal(vscode.Uri.parse(RELEASES_URL));
      } else if (pick === "Set CLI path") {
        void vscode.commands.executeCommand("workbench.action.openSettings", "alloyx.cliPath");
      }
    });
}

/** Whether this exec error means "the binary itself wasn't found". */
function isCliMissing(err: unknown): boolean {
  return !!err && (err as NodeJS.ErrnoException).code === "ENOENT";
}

// ---------------------------------------------------------------------------
// CLI version gate. After the CLI is found, probe `allx --version` once and warn
// (never block) when it's older than what this extension is built against. An old
// CLI predates --version, so it prints usage / exits non-zero / has no semver in
// its output — all treated as outdated. Generic numeric semver comparison only.
// ---------------------------------------------------------------------------

/** Compare dotted numeric versions. Returns <0, 0, >0 like a comparator. */
function compareVersions(a: string, b: string): number {
  const pa = a.split(".").map((n) => parseInt(n, 10));
  const pb = b.split(".").map((n) => parseInt(n, 10));
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (x !== y) {
      return x - y;
    }
  }
  return 0;
}

/** First `MAJOR.MINOR.PATCH` in the text, or undefined if there's none. */
function parseSemver(text: string): string | undefined {
  return text.match(/\d+\.\d+\.\d+/)?.[0];
}

/** One-per-session warning that the installed CLI is older than recommended. */
function notifyCliOutdated(found: string | undefined): void {
  if (cliOutdatedShown) {
    return;
  }
  cliOutdatedShown = true;
  const isMac = process.platform === "darwin";
  const actions = isMac ? ["Copy brew command"] : ["Open releases"];
  void vscode.window
    .showWarningMessage(
      `AlloyX CLI ${found ?? "(unknown)"} is older than the recommended ${MIN_CLI_VERSION}.`,
      ...actions
    )
    .then((pick) => {
      if (pick === "Copy brew command") {
        void vscode.env.clipboard.writeText(BREW_UPGRADE_CMD);
        void vscode.window.showInformationMessage("Copied — paste it in a terminal.");
      } else if (pick === "Open releases") {
        void vscode.env.openExternal(vscode.Uri.parse(RELEASES_URL));
      }
    });
}

/**
 * Probe the resolved CLI's version once. Missing binary is left to the
 * notifyCliMissing flow (we do nothing here). Anything that isn't a recognizable
 * semver >= MIN_CLI_VERSION — including a pre-`--version` CLI that errors/prints
 * usage — is reported as outdated.
 */
function checkCliVersion(): void {
  execFile(
    cliPath(),
    ["--version"],
    { timeout: 15000, env: execEnv() },
    (err, stdout, stderr) => {
      if (isCliMissing(err)) {
        return; // not installed: notifyCliMissing owns that case
      }
      const found = parseSemver(`${stdout ?? ""}\n${stderr ?? ""}`);
      // old CLI: non-zero exit / usage output / no semver at all -> outdated
      if (!found || compareVersions(found, MIN_CLI_VERSION) < 0) {
        notifyCliOutdated(found);
      }
    }
  );
}

/** `--org <alias>` when alloyx.org is set, so a run's SOQL/DML/sObject hits that org. */
function orgArgs(): string[] {
  const org = vscode.workspace.getConfiguration("alloyx").get<string>("org", "").trim();
  return org ? ["--org", org] : [];
}

/**
 * Environment for the allx child process. The VS Code GUI usually lacks JAVA_HOME
 * (it doesn't source the login shell), so the allx launcher falls back to the macOS
 * /usr/bin/java stub and fails to find the JDK. Inject JAVA_HOME from the
 * `alloyx.javaHome` setting (or the existing env) and put its bin on PATH.
 */
function execEnv(): NodeJS.ProcessEnv {
  const configured = vscode.workspace
    .getConfiguration("alloyx")
    .get<string>("javaHome", "");
  const javaHome = configured || process.env.JAVA_HOME || "";
  if (!javaHome) {
    return process.env;
  }
  return {
    ...process.env,
    JAVA_HOME: javaHome,
    PATH: `${javaHome}/bin:${process.env.PATH ?? ""}`,
  };
}

/**
 * Call `allx outline <file> --json` and parse the single JSON line it prints.
 * Returns undefined on any failure (CLI missing, parse error, etc.) so the
 * CodeLens provider can simply render no lenses instead of crashing.
 */
function getOutline(absFile: string): Promise<Outline | undefined> {
  return new Promise((resolve) => {
    execFile(
      cliPath(),
      ["outline", absFile, "--json"],
      // cwd = the file's folder so the `.apexcache/schema` synced next to the
      // classes is found (the workspace root usually has none).
      { timeout: 15000, env: execEnv(), cwd: path.dirname(absFile) },
      (err, stdout) => {
        if (err) {
          if (isCliMissing(err)) {
            notifyCliMissing();
          }
          resolve(undefined);
          return;
        }
        try {
          resolve(JSON.parse(stdout.trim()) as Outline);
        } catch {
          resolve(undefined);
        }
      }
    );
  });
}

// ---------------------------------------------------------------------------
// Run. One path for "run this method", whether triggered from the CodeLens
// above the method or from the right-click menu:
//   - no parameters  -> run it straight away
//   - has parameters -> open a buffer with the call pre-written (one typed
//     placeholder per argument), the dev fills the values and hits ▶ Run
// Either way it executes locally on the JVM; results print to the "AlloyX"
// output panel. `allx eval` is the engine underneath — never surfaced to the
// user, who only ever sees "Run".
// ---------------------------------------------------------------------------
const output = vscode.window.createOutputChannel("AlloyX");

// run buffers we opened -> the classes dir to resolve against + a label to show.
const runBuffers = new Map<string, { dir: string; label: string }>();
const runLenses = new vscode.EventEmitter<void>();

/**
 * A ready-to-run call for a method. Arguments are left as `/* Type name *\/`
 * placeholders — never a guessed default — so the dev sees exactly what each
 * slot expects, and `allx check` flags the empty slots until they're filled.
 * Static methods call `Class.m(...)`; instance methods `new Class().m(...)`.
 */
function buildCallStub(klass: string, m: OutlineMethod): string {
  const args = (m.params ?? []).map((p) => `/* ${p.type} ${p.name} */`).join(", ");
  const receiver = m.static ? klass : `new ${klass}()`;
  const call = `${receiver}.${m.name}(${args})`;
  // wrap a returning method in System.debug so its result prints
  return m.returnType && m.returnType !== "void" ? `System.debug(${call});` : `${call};`;
}

/**
 * The single run path: execute an Apex snippet locally via `allx eval` and stream
 * the result to the AlloyX panel, headed by "▶ Run <label>". The CLI command is an
 * implementation detail — the user sees "Run", not "eval".
 */
function runSnippet(snippet: string, dir: string, label: string): void {
  output.show(true);
  output.appendLine(`\n▶ Run  ${label}`);
  const child = execFile(
    cliPath(),
    ["eval", "--stdin", "--dir", dir, ...orgArgs()],
    // cwd = the classes dir so the synced `.apexcache/schema` is found
    { env: execEnv(), timeout: 60000, maxBuffer: 8 * 1024 * 1024, cwd: dir },
    (err, stdout, stderr) => {
      if (stdout) {
        output.append(stdout);
      }
      if (stderr) {
        output.append(stderr);
      }
      if (err && !stdout && !stderr) {
        if (isCliMissing(err)) {
          notifyCliMissing();
        }
        output.appendLine(String(err));
      }
    }
  );
  child.stdin?.end(snippet);
}

/** Open a buffer pre-filled with the call so the dev can fill args, then ▶ Run. */
async function openRunBuffer(file: string, klass: string, m: OutlineMethod): Promise<void> {
  const label = `${klass}.${m.name}`;
  const header = `// Run ${label} — fill in the arguments, then ▶ Run (above). Runs locally.`;
  const content = `${header}\n${buildCallStub(klass, m)}\n`;
  const doc = await vscode.workspace.openTextDocument({ language: "apex", content });
  runBuffers.set(doc.uri.toString(), { dir: path.dirname(file), label });
  runLenses.fire(); // make the "▶ Run" lens show up on the fresh buffer
  await vscode.window.showTextDocument(doc);
}

/** Single entry point: run now if there are no args, else open a buffer to fill them. */
async function runMethod(file: string, klass: string, m: OutlineMethod): Promise<void> {
  if (!m.params || m.params.length === 0) {
    runSnippet(buildCallStub(klass, m), path.dirname(file), `${klass}.${m.name}`);
  } else {
    await openRunBuffer(file, klass, m);
  }
}

/** The method whose signature line is nearest at/above the given (1-based) line. */
function methodAtLine(outline: Outline, line1: number): OutlineMethod | undefined {
  let best: OutlineMethod | undefined;
  for (const m of outline.methods) {
    if (m.line <= line1 && (!best || m.line > best.line)) {
      best = m;
    }
  }
  return best;
}

/** Right-click handler: run the method under the cursor. */
async function runMethodAtCursor(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor || !editor.document.uri.fsPath.endsWith(".cls")) {
    return;
  }
  const file = editor.document.uri.fsPath;
  const outline = await getOutline(file);
  if (!outline) {
    vscode.window.showWarningMessage("AlloyX: couldn't parse this file.");
    return;
  }
  const m = methodAtLine(outline, editor.selection.active.line + 1);
  if (!m) {
    vscode.window.showWarningMessage("AlloyX: put the cursor inside a method to run it.");
    return;
  }
  await runMethod(file, outline.class, m);
}

/** Run the active run-buffer (its ▶ Run lens). */
function runBuffer(uriStr?: string): void {
  const doc = vscode.window.activeTextEditor?.document;
  if (!doc) {
    return;
  }
  const key = uriStr ?? doc.uri.toString();
  const info = runBuffers.get(key);
  const dir =
    info?.dir ??
    vscode.workspace.getWorkspaceFolder(doc.uri)?.uri.fsPath ??
    vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ??
    path.dirname(doc.uri.fsPath);
  runSnippet(doc.getText(), dir, info?.label ?? "anonymous Apex");
}

/**
 * Describe the org's sObjects into the local schema cache (`allx schema sync`), so
 * typed checks/runs work offline. Prompts for the org alias, saves it as alloyx.org
 * (so a Run targets the same org), and runs against the open file's classes folder.
 */
async function syncSchema(): Promise<void> {
  const cfg = vscode.workspace.getConfiguration("alloyx");
  const alias = await vscode.window.showInputBox({
    title: "AlloyX: Sync Org Schema",
    prompt: "Org alias (from the sf CLI) to describe sObjects from — also saved as alloyx.org",
    value: cfg.get<string>("org", ""),
    placeHolder: "e.g. my-sandbox",
  });
  if (alias === undefined) {
    return; // cancelled
  }
  const org = alias.trim();
  if (!org) {
    vscode.window.showWarningMessage("AlloyX: no org alias given.");
    return;
  }

  // remember it so a Run targets the same org
  const target = vscode.workspace.workspaceFolders?.length
    ? vscode.ConfigurationTarget.Workspace
    : vscode.ConfigurationTarget.Global;
  await cfg.update("org", org, target);

  // classes dir: the active .cls's folder, else the workspace folder
  const active = vscode.window.activeTextEditor?.document;
  const dir =
    active && active.uri.fsPath.endsWith(".cls")
      ? path.dirname(active.uri.fsPath)
      : vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!dir) {
    vscode.window.showWarningMessage(
      "AlloyX: open a .cls (or a folder) so I know where your classes are."
    );
    return;
  }

  output.show(true);
  output.appendLine(`\n⟳ Sync schema  (org: ${org}, classes: ${dir})`);
  const child = spawn(cliPath(), ["schema", "sync", ".", "--org", org], {
    cwd: dir,
    env: execEnv(),
  });
  child.stdout.on("data", (d) => output.append(d.toString()));
  child.stderr.on("data", (d) => output.append(d.toString()));
  child.on("error", (e) => {
    if (isCliMissing(e)) {
      notifyCliMissing();
    }
    output.appendLine(String(e));
  });
  child.on("close", (code) =>
    output.appendLine(code === 0 ? "✓ schema synced" : `✗ sync exited with ${code}`)
  );
}

// ---------------------------------------------------------------------------
// CodeLens: "▶ Run" / "▶ Test" above each static / @isTest method, and a single
// "▶ Run" at the top of a run-buffer. Both route into the one run path above.
// ---------------------------------------------------------------------------
class ApexCodeLensProvider implements vscode.CodeLensProvider {
  async provideCodeLenses(
    document: vscode.TextDocument
  ): Promise<vscode.CodeLens[]> {
    const outline = await getOutline(document.uri.fsPath);
    if (!outline) {
      return [];
    }

    const lenses: vscode.CodeLens[] = [];
    for (const method of outline.methods) {
      const isTest = method.isTest === true;
      const isStatic = method.static === true;
      if (!isTest && !isStatic) {
        continue; // only static or test methods get a lens
      }
      // `line` is 1-based; VSCode ranges are 0-based.
      const lineIdx = Math.max(0, method.line - 1);
      const range = new vscode.Range(lineIdx, 0, lineIdx, 0);
      lenses.push(
        new vscode.CodeLens(range, {
          title: isTest ? "▶ Test" : "▶ Run",
          command: "alloyx.runMethod",
          arguments: [document.uri.fsPath, outline.class, method],
        })
      );
    }
    return lenses;
  }
}

/** Puts a single "▶ Run" lens at the top of the run-buffers we created. */
class RunBufferCodeLensProvider implements vscode.CodeLensProvider {
  onDidChangeCodeLenses = runLenses.event;
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    if (!runBuffers.has(document.uri.toString())) {
      return [];
    }
    return [
      new vscode.CodeLens(new vscode.Range(0, 0, 0, 0), {
        title: "▶ Run",
        command: "alloyx.runBuffer",
        arguments: [document.uri.toString()],
      }),
    ];
  }
}

// ---------------------------------------------------------------------------
// Diagnostics: `allx check <file> --stdin` type-checks the *current* buffer
// (unsaved included, piped via stdin) and prints diagnostics as JSON in .cls
// coordinates. We debounce on-type and also check on save/open.
// ---------------------------------------------------------------------------
interface CheckDiag {
  severity: string; // "ERROR" | "WARNING" | "NOTE" ...
  line: number; // 1-based line in the .cls
  column: number; // 1-based column
  message: string;
}

/** Run `allx check` against the given buffer contents (piped via stdin). */
function checkSource(absFile: string, source: string): Promise<CheckDiag[]> {
  return new Promise((resolve) => {
    const child = execFile(
      cliPath(),
      ["check", absFile, "--stdin"],
      // cwd = the file's folder so the synced `.apexcache/schema` is found
      { timeout: 15000, maxBuffer: 8 * 1024 * 1024, env: execEnv(), cwd: path.dirname(absFile) },
      (err, stdout) => {
        if (isCliMissing(err)) {
          notifyCliMissing();
        }
        try {
          resolve(JSON.parse(stdout.trim()) as CheckDiag[]);
        } catch {
          resolve([]); // crash/garbage output -> publish nothing
        }
      }
    );
    child.stdin?.end(source);
  });
}

function severityOf(s: string): vscode.DiagnosticSeverity {
  switch (s.toUpperCase()) {
    case "WARNING":
    case "MANDATORY_WARNING":
      return vscode.DiagnosticSeverity.Warning;
    case "NOTE":
    case "INFO":
      return vscode.DiagnosticSeverity.Information;
    default:
      return vscode.DiagnosticSeverity.Error;
  }
}

/** Type-check a document and publish the diagnostics into the collection. */
async function refreshDiagnostics(
  doc: vscode.TextDocument,
  collection: vscode.DiagnosticCollection
): Promise<void> {
  if (!doc.uri.fsPath.endsWith(".cls")) {
    return;
  }
  const found = await checkSource(doc.uri.fsPath, doc.getText());
  collection.set(
    doc.uri,
    found.map((d) => {
      // CLI is 1-based; VSCode ranges are 0-based. We only have a start column,
      // so underline from there to end of line.
      const line = Math.max(0, d.line - 1);
      const col = Math.max(0, d.column - 1);
      const range = new vscode.Range(line, col, line, Number.MAX_SAFE_INTEGER);
      const diag = new vscode.Diagnostic(range, d.message, severityOf(d.severity));
      diag.source = "allx";
      return diag;
    })
  );
}

// ---------------------------------------------------------------------------
// Activation.
// ---------------------------------------------------------------------------
export function activate(context: vscode.ExtensionContext): void {
  // Glob pattern so it works regardless of language id (apex/plaintext) and
  // without the official Salesforce extension installed.
  const selector: vscode.DocumentSelector = { pattern: "**/*.cls" };

  const diagnostics = vscode.languages.createDiagnosticCollection("allx");

  const checkDelayMs = (): number =>
    vscode.workspace.getConfiguration("alloyx").get<number>("checkDelayMs", 1500);

  // one debounce timer per document, reset on each keystroke
  const timers = new Map<string, NodeJS.Timeout>();
  const schedule = (doc: vscode.TextDocument): void => {
    if (!doc.uri.fsPath.endsWith(".cls")) {
      return;
    }
    const key = doc.uri.toString();
    const pending = timers.get(key);
    if (pending) {
      clearTimeout(pending);
    }
    timers.set(
      key,
      setTimeout(() => {
        timers.delete(key);
        void refreshDiagnostics(doc, diagnostics);
      }, checkDelayMs())
    );
  };

  context.subscriptions.push(
    diagnostics,
    output,
    vscode.languages.registerCodeLensProvider(selector, new ApexCodeLensProvider()),
    // "▶ Run" on top of run-buffers (untitled apex docs we created)
    vscode.languages.registerCodeLensProvider(
      { scheme: "untitled", language: "apex" },
      new RunBufferCodeLensProvider()
    ),
    // the one run path, reached from the CodeLens (with the method) ...
    vscode.commands.registerCommand(
      "alloyx.runMethod",
      (file: string, klass: string, method: OutlineMethod) => runMethod(file, klass, method)
    ),
    // ... and from the right-click menu (method under the cursor) ...
    vscode.commands.registerCommand("alloyx.runMethodAtCursor", () => runMethodAtCursor()),
    // ... and the ▶ Run at the top of a run-buffer.
    vscode.commands.registerCommand("alloyx.runBuffer", (uri?: string) => runBuffer(uri)),
    // describe the org's sObjects into the local schema cache (also saves alloyx.org)
    vscode.commands.registerCommand("alloyx.syncSchema", () => syncSchema()),
    // on-type (debounced), and immediate on save / open
    vscode.workspace.onDidChangeTextDocument((e) => schedule(e.document)),
    vscode.workspace.onDidSaveTextDocument((doc) => void refreshDiagnostics(doc, diagnostics)),
    vscode.workspace.onDidOpenTextDocument((doc) => void refreshDiagnostics(doc, diagnostics)),
    vscode.workspace.onDidCloseTextDocument((doc) => {
      diagnostics.delete(doc.uri);
      runBuffers.delete(doc.uri.toString()); // don't leak closed run-buffers
    }),
    // the user just pointed at a (new) CLI — allow the missing-CLI hint again
    // so a still-wrong path keeps guiding instead of failing silently
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("alloyx.cliPath")) {
        cliMissingShown = false;
        cliOutdatedShown = false; // re-probe the version of the newly pointed-at CLI
        resolvedCli = undefined; // re-resolve install locations on next call
        checkCliVersion();
      }
    })
  );

  // one-shot CLI version probe (warning only; never blocks anything)
  checkCliVersion();

  // check whatever is already open at activation time
  for (const doc of vscode.workspace.textDocuments) {
    void refreshDiagnostics(doc, diagnostics);
  }
}

export function deactivate(): void {
  // Nothing to clean up — VSCode disposes the output channel on exit.
}
