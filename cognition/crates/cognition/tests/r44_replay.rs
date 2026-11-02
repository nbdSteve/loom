//! R44's offered placement of a stack not in hand, replayed against today's request schema from the exact records
//! `research/experiments/2026-10-16-openai-10-agent-10m-r44/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R44: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r44/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R44
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R44
        .iter()
        .find(|record| record["event"] == "perceived" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no perception {key}"));
    (
        serde_json::from_value(perceived["perception"].clone()).unwrap(),
        decided,
    )
}

fn valid(schema: &Value, decided: &Value) -> bool {
    let answer = json!({
        "intent": decided["decision"]["intent"],
        "reasoning": "a replay",
        "goal": decided["decision"].get("goal").cloned().unwrap_or(Value::Null)
    });
    jsonschema::validator_for(schema).unwrap().is_valid(&answer)
}

#[test]
fn theta_could_not_place_a_stack_it_did_not_hold() {
    // R44 theta (body:00001886, tick 9800): a threat-sighting answer placed its oak wood, iu5, while
    // its main hand held the oak planks iu8, and the body refused it as placeItemNotHeld.
    let (seen, decided) = shown("tribe-theta:loom9bc6579ca4-sense-yu");
    let offered = OfferedRefs::of(&seen);
    assert_eq!(offered.held_stacks, vec!["iu8".to_owned()]);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert_eq!(decided["decision"]["intent"]["item"], "iu5");
    assert!(
        !valid(&schema, decided),
        "a placement of a stack not in hand is no answer"
    );
    let mut in_hand = decided.clone();
    in_hand["decision"]["intent"]["item"] = json!("iu8");
    assert!(valid(&schema, &in_hand), "the stack in hand still is");
    assert!(
        !offered.admits(&serde_json::from_value(decided["decision"]["intent"].clone()).unwrap())
    );
}
