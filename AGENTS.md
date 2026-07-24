# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

## The shared contract

`contract/loom.schema.json` (JSON Schema draft 2020-12) is the single source of truth for the perception/decision messages exchanged between the body (`plugin/`, Kotlin) and the mind (`cognition/`, Rust). Both sides carry **hand-written** binding types, not code-generated ones, kept honest against the schema by conformance tests. Edit the schema first; then update both sides' types until their conformance tests pass again.

- Rust bindings: `cognition/crates/contract/src/lib.rs` (serde). Conformance: `cognition/crates/contract/tests/contract_conformance.rs`.
- JVM bindings: `plugin/src/main/kotlin/dev/loom/contract/Contract.kt` (kotlinx-serialization) + canonical `LoomJson` config. Conformance: `plugin/src/test/kotlin/dev/loom/contract/ContractConformanceTest.kt`.
- Shared fixtures: `contract/fixtures/valid` (must validate + round-trip byte-for-byte on both sides) and `contract/fixtures/invalid` (must be rejected by the schema). Fixtures are stored as compact JSON with no trailing newline; field order matches the internal-tag-first declaration order both serializers emit. Adding a contract case means adding a fixture in both dirs.

**Union wire tagging is internal.** `Trigger` is tagged on `event`, `Intent` on `action`, with the variant fields inlined. Rust uses `#[serde(tag = "...")]`; JVM uses `@JsonClassDiscriminator` (needs a file-level `@OptIn(ExperimentalSerializationApi::class)`). Empty variants (`Trigger.GoalReview`, `Intent.Idle`) are unit/`data object`, serializing to just `{"event":"goalReview"}` / `{"action":"idle"}`. Keep the two tag configs identical or the round-trip fixtures diverge.

## Gates (fail-the-build from commit 1)

- Rust: `cd cognition && cargo fmt --all --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test`. Lint policy lives in `[workspace.lints]` in `cognition/Cargo.toml` (clippy pedantic/all denied, no unwrap/expect/panic in non-test code, `unsafe_code` forbidden).
- JVM: `cd plugin && ./gradlew check`. Runs detekt + ktlint + ArchUnit + warnings-as-errors compile, none ignoring failures. Build on **Corretto 21** (`jvmToolchain(21)`), Gradle wrapper pinned to 8.14. detekt config: `plugin/config/detekt/detekt.yml` (formatting delegated to ktlint).
- CI runs both suites: `.github/workflows/contract.yml`.

- Add durable project-specific notes here as they are discovered through real work.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
