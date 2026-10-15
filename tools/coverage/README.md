# Coverage

Line and branch coverage for all four Loom runtimes, checked against non-regression floors.

## Running it

```sh
tools/coverage/run.sh                  # all four suites, then the floor check
tools/coverage/run.sh body research    # any subset
node tools/coverage/check.js [runtime] # re-check existing reports without re-running
```

The whole run takes about 2.5 minutes on the development host (JVM 63 s, Rust 34 s, body 36 s, research 4 s).
It needs Corretto 21, `cargo-llvm-cov` 0.8.5 with the `llvm-tools-preview` component, and Node 22 or newer with `npm ci` already run in `body/` and `research/`.
On macOS the script selects JDK 21 through `/usr/libexec/java_home`, because Gradle 8.14 refuses a newer JDK.

| Runtime | Tool | Machine-readable | HTML |
|---|---|---|---|
| `jvm` (`plugin/`) | JaCoCo 0.8.15 (Gradle `jacoco`) | `plugin/build/reports/jacoco/test/jacocoTestReport.xml` | `plugin/build/reports/jacoco/test/html/` |
| `rust` (`cognition/`) | `cargo-llvm-cov` 0.8.5 | `cognition/target/llvm-cov/{lcov.info,cobertura.xml,summary.json}` | `cognition/target/llvm-cov/html/` |
| `body` | c8 12.0.0 | `body/coverage/{lcov.info,cobertura-coverage.xml,coverage-summary.json}` | `body/coverage/index.html` |
| `research` | c8 12.0.0 | `research/coverage/{lcov.info,cobertura-coverage.xml,coverage-summary.json}` | `research/coverage/index.html` |

All outputs are under ignored directories.
`./gradlew check` writes the JaCoCo report as well, so CI's plugin job checks the report its ordinary gate already produced.
c8 counts Node child processes without extra wiring, because Node passes `NODE_V8_COVERAGE` to every child even when a test builds the child's environment itself; `body/test/entry.test.js`'s real `bin/loom-body.js` children are counted that way.

## Metrics

JVM and Node report lines, branches and functions (JaCoCo calls them methods).
Rust reports lines, functions and regions.
Rust branch coverage needs a nightly compiler (`-Z coverage-options=branch`), and the gate toolchain is stable 1.91.0, so Rust has no branch figure; regions are its closest stable measure.

## Floors

`floors.json` is the single authority for every floor, and `check.js` is the single checker.
Each floor is `floor(measured - 0.5)` of the lower of the local baseline and the first CI run, so platform and run-to-run noise (the body suite's branch count moves by a few branches between identical runs) cannot fail identical code.
A seam floor applies to a named group of safety-critical files, and a metric is listed for a seam only where it is stricter than the runtime's own floor.
Raise a floor when coverage rises; never lower one to let a regression pass.

## Baseline

Measured on the clean main this change starts from (`755adeb`): locally on macOS (Node 25, rustc 1.97) and in CI on `ubuntu-latest` (Node 22, rustc 1.91.0).
The same code yields slightly different counts on each, which is why floors take the lower run.

| Scope | Run | Lines | Branches | Functions / methods | Regions |
|---|---|---|---|---|---|
| jvm | local | 82.68% (6434/7782) | 66.83% (2668/3992) | 85.09% (1878/2207) | - |
| jvm | CI | 82.69% (6435/7782) | 66.83% (2668/3992) | 85.09% (1878/2207) | - |
| rust | local | 93.56% (3428/3664) | not on stable | 94.72% (377/398) | 93.45% (4493/4808) |
| rust | CI | 91.69% (3431/3742) | not on stable | 94.72% (377/398) | 91.51% (4594/5020) |
| body | local | 97.75% (8697/8897) | 84.54% (1897/2244) | 96.65% (346/358) | - |
| body | CI | 97.75% (8697/8897) | 84.65% (1902/2247) | 96.67% (348/360) | - |
| research | local | 82.36% (6250/7589) | 80.94% (1584/1957) | 81.12% (202/249) | - |
| research | CI | 82.34% (6249/7589) | 80.90% (1580/1953) | 81.12% (202/249) | - |

Seams with their own floors:

- jvm `codecs` (`dev.loom.bodycontrol`, `dev.loom.contract`), `request-refs` (`RequestRefs`, `EntityRefs`, `InventoryRefs`, `WorldTargetRefs`, `RequestWindow`), `witness-permadeath` (`dev.loom.witness`, `Mortality`) and `cognition-budget` (`AsyncCognitionBridge`, `PerAgentCognitionBridge`, `CognitionSettings`, `PerceptionBound`).
- rust `codecs` (`crates/contract`, `wire.rs`, `openai/wire.rs`).
- body `protocol-codec` (`src/protocol.js`).
- research `evidence` (`lib/evidence.js`), `budget-cleanup` (`budget-proxy.js`, `controlled-stop.js`, `lifecycle.js`) and `publication` (`artifact.js`).

The body's ref resolution (`interaction.js`, `sensing.js`) has no seam floor: its branch coverage (79.40%) is below the body total, so a seam floor would be no stricter than the runtime floor.

## Exclusions

Everything not listed here is measured, including entry points the suites never run (`cognition/src/main.rs` and `research/bin/loom-tribe.js` are at 0%).

- Test code: JVM `src/test` is a separate source set; `cargo-llvm-cov` excludes `tests/` by default; c8 includes only `src/`+`bin/` (body) and `lib/`+`bin/` (research).
- Generated code: kotlinx-serialization's `$$serializer` classes and their nested annotation impls, which the compiler plugin writes and no source line maps to.
- Dependencies: `node_modules/`, the Cargo registry and the Rust toolchain, which every tool excludes by default.

One limitation cannot be excluded on stable Rust: the inline `#[cfg(test)]` modules in `cognition/crates/*/src` count with their file.
The Node processes started by the JVM suite (`SpeechEndToEndTest`, `CrossLayerActionFlowTest`) are not counted either, so each runtime's figure comes from its own suite alone.
