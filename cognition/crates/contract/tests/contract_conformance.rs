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

use loom_contract::{Decision, Perception, MAX_FOOD_LEVEL};

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

#[test]
fn a_nightfall_countdown_below_one_is_refused_at_decode() {
    // Not only by the schema. Nothing on the wire is schema-validated at runtime -
    // `read_frame` hands a line to serde and nothing else - so a rule the schema
    // alone holds is a rule a socket can walk straight past. Zero is the value
    // worth refusing: a countdown that had run out would carry it, and a night
    // would then arrive as a nightfall permanently about to happen, which the mind
    // cannot tell from one genuinely a tick away.
    let zero = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("perception-nightfall-not-positive.json"),
    )
    .unwrap();

    let refused = serde_json::from_str::<Perception>(zero.trim_end_matches('\n'));
    assert!(
        refused.is_err(),
        "a zero nightfall countdown decoded into a Perception"
    );

    // And a negative, which is the same mistake having gone past rather than a
    // different one, so it must not need its own rule to be caught.
    let negative = zero.replace("\"ticksUntilNightfall\":0", "\"ticksUntilNightfall\":-1");
    assert_ne!(
        zero, negative,
        "the fixture no longer states a zero to edit"
    );
    assert!(
        serde_json::from_str::<Perception>(&negative).is_err(),
        "a negative nightfall countdown decoded into a Perception"
    );

    // The bound is exactly one tick, not "positive enough": a perception taken on
    // the last tick before nightfall is an ordinary one and must still decode.
    let one = zero.replace("\"ticksUntilNightfall\":0", "\"ticksUntilNightfall\":1");
    let accepted: Perception = serde_json::from_str(&one)
        .unwrap_or_else(|e| panic!("a one-tick nightfall countdown should decode: {e}"));
    assert_eq!(accepted.self_state.world.ticks_until_nightfall, 1);
}

#[test]
fn vanilla_hunger_readings_outside_their_ranges_are_refused_at_decode() {
    // Same reason as the countdown above: the schema's `minimum`/`maximum` is a rule
    // a socket walks straight past, and the JVM binding refuses it in its own `init`,
    // so this side has to as well or the contract only refuses it on one runtime.
    let above = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("perception-food-out-of-range.json"),
    )
    .unwrap();
    let above = above.trim_end_matches('\n').to_owned();

    assert!(
        serde_json::from_str::<Perception>(&above).is_err(),
        "a food level above vanilla's ceiling decoded into a Perception"
    );

    let below = above.replace("\"food\":21", "\"food\":-1");
    assert_ne!(
        above, below,
        "the fixture no longer states a food level to edit"
    );
    assert!(
        serde_json::from_str::<Perception>(&below).is_err(),
        "a negative food level decoded into a Perception"
    );

    assert!(
        serde_json::from_str::<Perception>(&above.replace(
            "\"food\":21,\"saturation\":5.0",
            "\"food\":20,\"saturation\":21.0"
        ))
        .is_err(),
        "saturation above vanilla's ceiling decoded into a Perception"
    );

    for boundary in [0, MAX_FOOD_LEVEL] {
        let at_boundary = above.replace("\"food\":21", &format!("\"food\":{boundary}"));
        let accepted: Perception = serde_json::from_str(&at_boundary)
            .unwrap_or_else(|e| panic!("a food level of {boundary} should decode: {e}"));
        assert_eq!(accepted.self_state.food, boundary);
    }
}

#[test]
fn an_inventory_summary_longer_than_the_body_is_refused_at_decode() {
    // The last of the schema's bounds on this field, and the one that was left to the schema
    // alone: `maxItems: 46` is a rule a socket walks straight past, so a 47th slot decoded
    // happily here. A summary longer than the player's whole window cannot be its inventory.
    let over = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("perception-inventory-over-slot-count.json"),
    )
    .unwrap();
    let over = over.trim_end_matches('\n').to_owned();

    assert!(
        serde_json::from_str::<Perception>(&over).is_err(),
        "a 47-slot inventory summary decoded into a Perception"
    );

    // The bound is exactly the container: a body carrying something in every slot is an
    // ordinary provisioned agent, and its whole inventory must still reach the mind.
    let full = over.replace(
        ",{\"ref\":\"i47\",\"what\":\"item.minecraft.bread\",\"count\":1}",
        "",
    );
    assert_ne!(
        over, full,
        "the fixture no longer states a 47th slot to drop"
    );
    let accepted: Perception = serde_json::from_str(&full)
        .unwrap_or_else(|e| panic!("a full 46-slot inventory should decode: {e}"));
    assert_eq!(accepted.self_state.inventory_summary.len(), 46);
}

#[test]
fn an_inventory_stack_holding_nothing_is_refused_at_decode() {
    // Same reason as the readings above, and this is the field where only one runtime ever
    // decodes: a `Perception` is written by the body and read here, so the schema's
    // `minimum: 1` and the JVM's own `init` never see this line at all. A count of zero
    // renders as "0 bread (ref i1)" in the prompt - food the agent is carrying and does not
    // have, on the one path whose whole purpose is deciding whether to eat.
    let empty = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("perception-inventory-empty-stack.json"),
    )
    .unwrap();
    let empty = empty.trim_end_matches('\n').to_owned();

    assert!(
        serde_json::from_str::<Perception>(&empty).is_err(),
        "a stack of zero items decoded into a Perception"
    );

    // A negative is the same mistake having gone past, and must not need a rule of its own.
    let negative = empty.replace("\"count\":0", "\"count\":-1");
    assert_ne!(
        empty, negative,
        "the fixture no longer states a count to edit"
    );
    assert!(
        serde_json::from_str::<Perception>(&negative).is_err(),
        "a negative stack count decoded into a Perception"
    );

    // The bound is exactly one item: the last loaf an agent has is an ordinary stack, and it
    // is the one a survival run most needs the mind to be told about.
    let last_one = empty.replace("\"count\":0", "\"count\":1");
    let accepted: Perception = serde_json::from_str(&last_one)
        .unwrap_or_else(|e| panic!("a stack of one item should decode: {e}"));
    assert_eq!(accepted.self_state.inventory_summary[0].count, 1);
}

#[test]
fn a_ref_outside_its_length_is_refused_at_decode() {
    // Same reason as the countdown above: the schema's minLength and maxLength are
    // rules a socket walks straight past, so each end has to be a type rule too.
    // A blank ref names nothing the body can resolve, and an over-long one is a
    // handle no request ever issued - a mind having invented one rather than named
    // back what it was given.
    let blank = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("decision-flee-blank-ref.json"),
    )
    .unwrap();
    assert!(
        serde_json::from_str::<Decision>(blank.trim_end_matches('\n')).is_err(),
        "a blank flee ref decoded into a Decision"
    );

    // An eat names a stack by the same shape of handle, so it inherits the bound
    // rather than restating it: a blank slot ref names no slot the body can read.
    let blank_slot = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("decision-eat-blank-ref.json"),
    )
    .unwrap();
    assert!(
        serde_json::from_str::<Decision>(blank_slot.trim_end_matches('\n')).is_err(),
        "a blank eat ref decoded into a Decision"
    );

    let over_long = fs::read_to_string(
        contract_dir()
            .join("fixtures/invalid")
            .join("perception-observation-ref-too-long.json"),
    )
    .unwrap();
    assert!(
        serde_json::from_str::<Perception>(over_long.trim_end_matches('\n')).is_err(),
        "a nine-character observation ref decoded into a Perception"
    );

    // A threat sighting carries a ref of its own, and the bound has to reach it for
    // the same reason: that handle is the one a `Flee` answering the sighting must
    // name back, so a length no request could have issued is refused where it
    // arrives rather than at the body. Only the first occurrence is replaced, which
    // is the trigger's own ref - the observation below it keeps a valid one, so what
    // the decoder refuses is the trigger.
    let sighted = fs::read_to_string(
        contract_dir()
            .join("fixtures/valid")
            .join("perception-threat-sighted.json"),
    )
    .unwrap();
    let invented = sighted.replacen("\"ref\":\"e1\"", "\"ref\":\"e123456789\"", 1);
    assert_ne!(
        sighted, invented,
        "the threat fixture no longer states the ref to edit"
    );
    assert!(
        serde_json::from_str::<Perception>(invented.trim_end_matches('\n')).is_err(),
        "an over-long threat ref decoded into a Perception"
    );

    // The bound is the length and nothing else: a ref of exactly the maximum is an
    // ordinary one the body issues, and must still decode.
    let longest = over_long.replace("\"ref\":\"entity-42\"", "\"ref\":\"entity-4\"");
    assert_ne!(
        over_long, longest,
        "the fixture no longer states the over-long ref to edit"
    );
    let accepted: Perception = serde_json::from_str(&longest)
        .unwrap_or_else(|e| panic!("an eight-character ref should decode: {e}"));
    assert_eq!(
        accepted.surroundings[0].entity_ref.as_deref(),
        Some("entity-4")
    );
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
