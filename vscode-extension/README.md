# AlloyX for VS Code

Runs your Salesforce **Apex** locally and flags **type errors as you type** —
both powered by the `allx` CLI. The extension itself doesn't parse Apex; it
shells out to `allx` for everything.

- **Run** — a `▶ Run` / `▶ Test` CodeLens above each method (or right-click) runs it locally.
- **Type-check** — on every edit (debounced) it calls `allx check` and underlines
  type errors inline: a `String` assigned to an `Integer` field, a field or method
  that doesn't exist, basic syntax slips — the org's own compile errors, on your
  machine in milliseconds instead of minutes after a deploy.

## What you see

- `▶ Run` above every `static` method, `▶ Test` above every `@isTest` method.
- Red squiggles under type errors as you type, with the message in Apex terms.

## Run a method

Click **▶ Run** above the method (or right-click → **AlloyX: Run Method**) — same
thing either way. Results show in the **AlloyX** output panel. It runs locally on
the JVM in milliseconds; the org is only touched if your code does SOQL/DML.

- **No parameters** → it runs straight away.
- **Has parameters** → it opens the call pre-written, one typed placeholder per
  argument (no guessed defaults — static methods call `Class.m(...)`, instance
  methods `new Class().m(...)`):

  ```apex
  // Run MyClass.process — fill in the arguments, then ▶ Run (above). Runs locally.
  System.debug(new MyClass().process(/* Account acct */, /* Integer qty */));
  ```

  Fill in the values — it's a full Apex block, so you can `new Account(...)`,
  `insert`, query, whatever — then hit **▶ Run** at the top.

## Org & schema

Runs that touch Salesforce (SOQL/DML/sObject) need an org, and typed checks need the
org's sObject describes cached locally. Run **AlloyX: Sync Org Schema** from the command
palette (`⌘⇧P`): with a `.cls` open, enter your org alias (from the `sf` CLI). It
describes the sObjects your classes reference into a local cache, and saves the alias as
`alloyx.org` so a Run targets the same org. The Run output shows that org at the top.

## How to install

The extension drives the `allx` CLI — install that first.

**macOS (Homebrew):** one command, the JDK comes along as a dependency:

```bash
brew install colatusso/alloyx/allx
```

**Windows / Linux:** grab the `allx-*.zip` from the
[latest release](https://github.com/colatusso/alloyx/releases/latest), unpack it
anywhere, and either put its `bin/` folder on your `PATH` or set `alloyx.cliPath`
to the launcher (`bin\allx.bat` on Windows, `bin/allx` on Linux). You also need a
**JDK 21+** — a JDK, not a JRE ([Temurin 21](https://adoptium.net) works great).

**Then install this extension** from the Marketplace (search “AlloyX”), or from a
`.vsix` on the release page:

```bash
code --install-extension alloyx-vscode-0.1.0.vsix
```

**JDK not found? (macOS GUI)** — when VS Code is launched from the Dock it doesn't
inherit your shell's `JAVA_HOME`. Point the extension at your JDK in Settings
(brew installs put it under `$(brew --prefix openjdk@21)`):

```json
"alloyx.javaHome": "/opt/homebrew/opt/openjdk@21"
```

## Settings

| Setting               | Default | Description                                                                |
| --------------------- | ------- | -------------------------------------------------------------------------- |
| `alloyx.cliPath`      | `allx`  | Path to the `allx` binary. Defaults to a `PATH` lookup.                    |
| `alloyx.org`          | `""`    | Org alias for runs that do SOQL/DML/sObject. Passed as `--org` (else `alloyx.json`). |
| `alloyx.checkDelayMs` | `1500`  | Idle time (ms) after typing stops before a `.cls` is re-checked.           |
| `alloyx.javaHome`     | `""`    | JDK path used to run `allx`, for when VS Code can't see your shell's env.   |

## Requirements

The `allx` CLI on your `PATH` (or pointed at via `alloyx.cliPath`) and a
**JDK 21+** — a JDK, not a JRE, since AlloyX compiles Java at runtime.

## Develop

```bash
npm install
npm run watch        # recompile on change, then F5 for an Extension Dev Host
# or test it as a real installed extension:
npm run reinstall    # package + install the .vsix, then Reload Window
```
