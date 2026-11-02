//! The independent r27 audit's peer-flight blocker, replayed against today's request schema from the
//! exact records `research/experiments/2026-10-16-openai-10-agent-10m-r27/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R27: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r27/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R27
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R27
        .iter()
        .find(|record| record["event"] == "perceived" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no perception {key}"));
    let mut perception = perceived["perception"].clone();
    for field in ["goal", "horizon"] {
        if let Some(value) = decided["shown"].get(field) {
            perception[field] = value.clone();
        }
    }
    (serde_json::from_value(perception).unwrap(), decided)
}

fn valid(schema: &Value, decided: &Value) -> bool {
    let decision = &decided["decision"];
    let answer = json!({
        "decision": { "intent": decision["intent"], "goal": decision.get("goal").cloned().unwrap_or(Value::Null) },
        "reasoning": "a replay",
    });
    jsonschema::validator_for(schema).unwrap().is_valid(&answer)
}

#[test]
fn delta_was_never_offered_a_flight_from_a_peer() {
    // r27 delta sense-ik (body:00000763, decided body:00000786): four players and two sheep in
    // sight and no hunter, and the schema let it flee the player e27j beside an abandon.
    let (seen, decided) = shown("tribe-delta:loom3b4ef3e082-sense-ik");
    let offered = OfferedRefs::of(&seen);
    assert!(
        offered.flee_targets.is_empty(),
        "{:?}",
        offered.flee_targets
    );
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(
        !valid(&schema, decided),
        "the peer flight is no longer an answer"
    );
}

#[test]
fn theta_at_two_hearts_beside_hunters_is_offered_only_a_survival_response() {
    // r27 theta sense-11q and sense-12o (body:00004235, 00004314): at 2.2 health, a spider 10.2 and
    // two zombies 10 to 11 blocks off, both reviews crafted.
    for key in [
        "tribe-theta:loom9bc6579ca4-sense-11q",
        "tribe-theta:loom9bc6579ca4-sense-12o",
    ] {
        let (seen, decided) = shown(key);
        assert!(
            seen.self_state.health <= loom_cognition::offered::LOW_HEALTH,
            "{key}"
        );
        let offered = OfferedRefs::of(&seen);
        assert!(offered.in_immediate_danger(), "{key}: {:?}", offered.danger);
        let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
        assert!(
            !valid(&schema, decided),
            "{key}: the craft is no longer an answer"
        );
    }
}
