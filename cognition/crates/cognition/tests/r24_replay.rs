//! The independent r24 audit's two blockers, replayed against today's request schema from the
//! exact records `research/experiments/2026-10-16-openai-10-agent-10m-r24/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R24: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r24/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

fn record(id: &str) -> &'static Value {
    R24.iter()
        .find(|record| record["recordId"] == id)
        .unwrap_or_else(|| panic!("no record {id}"))
}

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R24
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R24
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
fn theta_is_no_longer_offered_the_walk_past_grass_the_ledger_refused() {
    // body:00000078: the schema offered a continue with a walk to x-cells beside 13% grass, and
    // the ledger refused it as goalStepUnproductive. Today the two agree: grass is no sure source,
    // so the walk is offered and the ledger takes it (`WorkingGoal.productive`).
    assert_eq!(record("body:00000078")["outcome"], "goalStepUnproductive");
    let (seen, decided) = shown("tribe-theta:loom9bc6579ca4-sense-3g");
    let offered = OfferedRefs::of(&seen);
    assert!(
        offered.productive.is_empty(),
        "13% grass is no sure source: {:?}",
        offered.productive
    );
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(valid(&schema, decided));
}

#[test]
fn delta_is_offered_no_attack_its_health_floor_would_refuse_by_the_time_it_arrives() {
    // body:00003496: delta, perceived at 8.5 with a spider biting, attacked and met the 4.5 floor.
    assert_eq!(record("body:00003496")["outcome"], "attackHealthBound");
    let (seen, decided) = shown("tribe-delta:loom3b4ef3e082-sense-sc");
    assert!(seen.self_state.health <= 8.5);
    let offered = OfferedRefs::of(&seen);
    assert!(offered.strike_targets.is_empty() && offered.pursuit_targets.is_empty());
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(!valid(&schema, decided), "the attack it took is withheld");
}
