//! Enforces the single-source-of-truth property on the Rust side.
//!
//! Two independent guarantees, both failing the build on drift:
//!
//! 1. Every shared fixture under `contract/fixtures/valid` validates against
//!    `contract/loom.schema.json`, and every fixture under
//!    `contract/fixtures/invalid` is rejected by it. This proves the schema is
//!    the authority and that its validation has teeth.
//! 2. Every valid fixture round-trips through the hand-written serde types and
//!    re-serializes to the exact bytes of the canonical fixture. Because the
//!    JVM side pins the same fixtures the same way, byte-for-byte agreement on
//!    the fixtures is transitively byte-for-byte agreement between the two
//!    runtimes.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::{Path, PathBuf};

use loom_contract::{Decision, Perception};

/// Absolute path to the repository's `contract/` directory, resolved from this
/// crate's manifest dir so the test is independent of the working directory.
fn contract_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../contract")
        .canonicalize()
        .expect("contract/ directory should resolve from the crate manifest dir")
}

fn load_schema() -> serde_json::Value {
    let schema_path = contract_dir().join("loom.schema.json");
    let text = fs::read_to_string(&schema_path)
        .unwrap_or_else(|e| panic!("read schema {}: {e}", schema_path.display()));
    serde_json::from_str(&text).expect("schema is valid JSON")
}

/// Every `.json` file in the given fixtures subdirectory, sorted for stable
/// iteration and failure messages.
fn fixture_files(subdir: &str) -> Vec<PathBuf> {
    let dir = contract_dir().join("fixtures").join(subdir);
    let mut files: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("read fixtures dir {}: {e}", dir.display()))
        .map(|entry| entry.expect("dir entry").path())
        .filter(|p| p.extension().is_some_and(|ext| ext == "json"))
        .collect();
    files.sort();
    assert!(!files.is_empty(), "no fixtures found in {}", dir.display());
    files
}

fn is_perception(path: &Path) -> bool {
    path.file_name()
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.starts_with("perception-"))
}

#[test]
fn valid_fixtures_conform_to_schema() {
    let schema = load_schema();

    for path in fixture_files("valid") {
        let text = fs::read_to_string(&path).unwrap();
        let instance: serde_json::Value = serde_json::from_str(&text).unwrap();

        // Fixtures name the top-level type they exercise; point the schema at
        // that definition so a Perception is not accidentally validated as a
        // Decision or vice versa.
        let def = if is_perception(&path) {
            "Perception"
        } else {
            "Decision"
        };
        let scoped = scoped_schema(&schema, def);
        let scoped_validator = jsonschema::validator_for(&scoped).expect("scoped schema compiles");
        assert!(
            scoped_validator.is_valid(&instance),
            "valid fixture {} failed {def} schema validation: {:?}",
            path.display(),
            scoped_validator
                .iter_errors(&instance)
                .map(|e| e.to_string())
                .collect::<Vec<_>>()
        );
    }
}

#[test]
fn invalid_fixtures_are_rejected_by_schema() {
    let schema = load_schema();

    for path in fixture_files("invalid") {
        let text = fs::read_to_string(&path).unwrap();
        let instance: serde_json::Value = serde_json::from_str(&text).unwrap();

        let def = if is_perception(&path) {
            "Perception"
        } else {
            "Decision"
        };
        let scoped = scoped_schema(&schema, def);
        let validator = jsonschema::validator_for(&scoped).expect("scoped schema compiles");
        assert!(
            !validator.is_valid(&instance),
            "invalid fixture {} was wrongly accepted by {def} schema",
            path.display()
        );
    }
}

#[test]
fn valid_fixtures_round_trip_byte_for_byte() {
    for path in fixture_files("valid") {
        let text = fs::read_to_string(&path).unwrap();
        // Canonical bytes are stored without a trailing newline; compare exactly.
        let canonical = text.trim_end_matches('\n');

        let reserialized = if is_perception(&path) {
            let value: Perception = serde_json::from_str(canonical)
                .unwrap_or_else(|e| panic!("deserialize {} as Perception: {e}", path.display()));
            serde_json::to_string(&value).unwrap()
        } else {
            let value: Decision = serde_json::from_str(canonical)
                .unwrap_or_else(|e| panic!("deserialize {} as Decision: {e}", path.display()));
            serde_json::to_string(&value).unwrap()
        };

        assert_eq!(
            canonical,
            reserialized,
            "round-trip of {} was not byte-identical",
            path.display()
        );
    }
}

/// Produce a self-contained schema rooted at the named `$defs` entry, carrying
/// the original `$defs` so internal `$ref`s still resolve.
fn scoped_schema(schema: &serde_json::Value, def: &str) -> serde_json::Value {
    let defs = schema
        .get("$defs")
        .expect("schema has $defs")
        .get(def)
        .unwrap_or_else(|| panic!("schema $defs has no {def}"))
        .clone();

    let mut rooted = match defs {
        serde_json::Value::Object(map) => map,
        other => panic!("$defs/{def} is not an object: {other}"),
    };
    rooted.insert("$schema".to_string(), schema["$schema"].clone());
    rooted.insert("$defs".to_string(), schema["$defs"].clone());
    serde_json::Value::Object(rooted)
}
