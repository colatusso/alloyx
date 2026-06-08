import * as vscode from "vscode";
import * as path from "path";
import { execFile } from "child_process";

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

/** Read the configured CLI path (defaults to "allx", resolved via PATH). */
function cliPath(): string {
  return vscode.workspace.getConfiguration("alloyx").get<string>("cliPath", "allx");
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
      { timeout: 15000, env: execEnv() },
      (err, stdout) => {
        if (err) {
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
// CodeLens provider: one lens per static method ("▶ Run") and per @isTest
// method ("▶ Test"). Methods that are neither get no lens in v0.
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
        continue; // v0: only static or test methods get a lens
      }

      // `line` is 1-based; VSCode ranges are 0-based.
      const lineIdx = Math.max(0, method.line - 1);
      const range = new vscode.Range(lineIdx, 0, lineIdx, 0);
      const params = method.params ?? [];

      lenses.push(
        new vscode.CodeLens(range, {
          title: isTest ? "▶ Test" : "▶ Run",
          command: "alloyx.runMethod",
          arguments: [document.uri.fsPath, outline.class, method.name, params],
        })
      );
    }
    return lenses;
  }
}

// ---------------------------------------------------------------------------
// Terminal handling: reuse a single named "AlloyX" terminal.
// ---------------------------------------------------------------------------
function getTerminal(): vscode.Terminal {
  const existing = vscode.window.terminals.find((t) => t.name === "AlloyX");
  return existing ?? vscode.window.createTerminal("AlloyX");
}

/** Quote an argument for the shell only if it contains whitespace/specials. */
function shellQuote(value: string): string {
  if (/^[A-Za-z0-9._/:=-]+$/.test(value)) {
    return value;
  }
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/**
 * Command handler invoked by the CodeLens (or manually). Runs
 * `allx run <file> --method <Class>.<method> [--args v1 v2 ...]`
 * in the shared terminal. Prompts for args when the method has params.
 */
async function runMethod(
  file: string,
  klass: string,
  method: string,
  params: OutlineParam[] | undefined
): Promise<void> {
  const argParts: string[] = [];

  if (params && params.length > 0) {
    const hint = params.map((p) => `${p.type} ${p.name}`).join(", ");
    const input = await vscode.window.showInputBox({
      title: `Arguments for ${klass}.${method}`,
      prompt: `Space-separated values for: ${hint}`,
      placeHolder: params.map((p) => p.name).join(" "),
    });
    // Cancelled (Esc) → abort. Empty string is allowed (user chose no args).
    if (input === undefined) {
      return;
    }
    const trimmed = input.trim();
    if (trimmed.length > 0) {
      argParts.push("--args", ...trimmed.split(/\s+/));
    }
  }

  const parts = [
    shellQuote(cliPath()),
    "run",
    shellQuote(file),
    "--method",
    shellQuote(`${klass}.${method}`),
    ...argParts.map(shellQuote),
  ];

  const terminal = getTerminal();
  terminal.show();
  terminal.sendText(parts.join(" "));
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
      { timeout: 15000, maxBuffer: 8 * 1024 * 1024, env: execEnv() },
      (_err, stdout) => {
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
// Anonymous Apex ("scratch") run. Right-clicking a method runs it; when the
// method takes parameters there's nothing sensible to type into a dialog for an
// sObject or a List, so instead we open a scratch buffer with the call already
// written out (one typed placeholder per argument). The dev fills the values and
// runs the buffer as an anonymous block via `allx eval` — locally, on the JVM.
// ---------------------------------------------------------------------------
const output = vscode.window.createOutputChannel("AlloyX");

// scratch buffers we created -> the classes dir their snippet resolves against,
// so `allx eval --dir` can find the called class and its dependencies.
const scratchDirs = new Map<string, string>();
const scratchLenses = new vscode.EventEmitter<void>();

/**
 * A ready-to-run call for a method. Arguments are left as `/* Type name *\/`
 * placeholders — never a guessed default — so the dev sees exactly what each
 * slot expects, and `allx check` flags the empty slots until they're filled.
 */
function buildCallStub(klass: string, m: OutlineMethod): string {
  const args = (m.params ?? []).map((p) => `/* ${p.type} ${p.name} */`).join(", ");
  const call = `${klass}.${m.name}(${args})`;
  // wrap a returning method in System.debug so its result prints
  return m.returnType && m.returnType !== "void" ? `System.debug(${call});` : `${call};`;
}

/** Open a scratch buffer pre-filled with the call; the dev fills args and runs it. */
async function openScratch(file: string, klass: string, m: OutlineMethod): Promise<void> {
  const header =
    "// AlloyX scratch — fill in the arguments, then ▶ Run (above). Runs locally.";
  const content = `${header}\n${buildCallStub(klass, m)}\n`;
  const doc = await vscode.workspace.openTextDocument({ language: "apex", content });
  scratchDirs.set(doc.uri.toString(), path.dirname(file));
  scratchLenses.fire(); // make the "▶ Run" lens show up on the fresh buffer
  await vscode.window.showTextDocument(doc);
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

/** Right-click handler: run the method under the cursor (scratch if it has params). */
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
  if (!m.params || m.params.length === 0) {
    await runMethod(file, outline.class, m.name, m.params); // no args -> run straight away
  } else {
    await openScratch(file, outline.class, m);
  }
}

/** Run the active scratch buffer as anonymous Apex (`allx eval --stdin`). */
function runScratch(uriStr?: string): void {
  const doc = vscode.window.activeTextEditor?.document;
  if (!doc) {
    return;
  }
  const key = uriStr ?? doc.uri.toString();
  const dir =
    scratchDirs.get(key) ??
    vscode.workspace.getWorkspaceFolder(doc.uri)?.uri.fsPath ??
    vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ??
    path.dirname(doc.uri.fsPath);

  output.show(true);
  output.appendLine(`\n$ allx eval  (classes: ${dir})`);
  const child = execFile(
    cliPath(),
    ["eval", "--stdin", "--dir", dir],
    { env: execEnv(), timeout: 60000, maxBuffer: 8 * 1024 * 1024 },
    (err, stdout, stderr) => {
      if (stdout) {
        output.append(stdout);
      }
      if (stderr) {
        output.append(stderr);
      }
      if (err && !stdout && !stderr) {
        output.appendLine(String(err));
      }
    }
  );
  child.stdin?.end(doc.getText());
}

/** Puts a single "▶ Run" lens at the top of the scratch buffers we created. */
class ScratchCodeLensProvider implements vscode.CodeLensProvider {
  onDidChangeCodeLenses = scratchLenses.event;
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    if (!scratchDirs.has(document.uri.toString())) {
      return [];
    }
    return [
      new vscode.CodeLens(new vscode.Range(0, 0, 0, 0), {
        title: "▶ Run (AlloyX)",
        command: "alloyx.runScratch",
        arguments: [document.uri.toString()],
      }),
    ];
  }
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
    // "▶ Run" on top of scratch buffers (untitled apex docs we created)
    vscode.languages.registerCodeLensProvider(
      { scheme: "untitled", language: "apex" },
      new ScratchCodeLensProvider()
    ),
    vscode.commands.registerCommand(
      "alloyx.runMethod",
      (file: string, klass: string, method: string, params?: OutlineParam[]) =>
        runMethod(file, klass, method, params)
    ),
    // right-click in a .cls -> run the method under the cursor
    vscode.commands.registerCommand("alloyx.runMethodAtCursor", () => runMethodAtCursor()),
    vscode.commands.registerCommand("alloyx.runScratch", (uri?: string) => runScratch(uri)),
    // on-type (debounced), and immediate on save / open
    vscode.workspace.onDidChangeTextDocument((e) => schedule(e.document)),
    vscode.workspace.onDidSaveTextDocument((doc) => void refreshDiagnostics(doc, diagnostics)),
    vscode.workspace.onDidOpenTextDocument((doc) => void refreshDiagnostics(doc, diagnostics)),
    vscode.workspace.onDidCloseTextDocument((doc) => {
      diagnostics.delete(doc.uri);
      scratchDirs.delete(doc.uri.toString()); // don't leak closed scratch buffers
    })
  );

  // check whatever is already open at activation time
  for (const doc of vscode.workspace.textDocuments) {
    void refreshDiagnostics(doc, diagnostics);
  }
}

export function deactivate(): void {
  // Nothing to clean up — the terminal is disposed by VSCode on exit.
}
