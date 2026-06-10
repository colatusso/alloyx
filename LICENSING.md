# Licensing

AlloyX uses **two licenses on purpose**, so the engine stays strongly copyleft
while the code generated from your Apex never inherits that copyleft.

| Component | Path | License |
|-----------|------|---------|
| **Engine** — transpiler, parser, lexer, workspace, CLI, schema | `src/main/java/alloyx/` (everything **except** `runtime/`) | **AGPL-3.0-only** |
| **Runtime** — the library the generated Java links against | `src/main/java/alloyx/runtime/` | **Apache-2.0** |
| **VS Code extension** | `vscode-extension/` | **Apache-2.0** |

## Why the split

The Java that AlloyX generates from your Apex **links against the runtime**
(`alloyx.runtime.*` — `List`, `Map`, `Database`, `SObject`, `Decimal`, …). If
that runtime were AGPL, code you generate, distribute, or serve could inherit
AGPL obligations. Keeping the runtime under **Apache-2.0** — the same
"Classpath-exception" idea OpenJDK uses — means **your Apex, and the Java
produced from it, stay yours.** The copyleft applies only to the AlloyX engine.

## Engine — AGPL-3.0

The engine is where the value is. AGPL-3.0 means anyone who **modifies** AlloyX
and offers it as a network service must release their modifications under the
same license. Running AlloyX locally as a dev tool triggers **no** such
obligation (the network clause, §13, only fires on modified network use).

## Dual licensing

The copyright holder retains the right to offer AlloyX under separate
**commercial terms** (e.g. to run a hosted/managed service without the AGPL
obligations). AGPL obligations bind third parties — not the copyright holder.
To keep this option open once external contributions arrive, contributions are
accepted under the CLA (see `CLA.md`).

---

*This document is an explanation of intent, not legal advice.*
