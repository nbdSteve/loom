//! R46's offered sleep in a block that is no bed, replayed against today's request schema from the exact records
//! `research/experiments/2026-10-16-openai-10-agent-10m-r46/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R46: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r46/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R46
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R46
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
fn epsilon_and_kappa_could_not_sleep_in_dirt() {
    // R46 (body:00001597 and body:00001600, tick 10498): with no bed in sight, epsilon and kappa each
    // answered with a sleep in a dirt block the schema offered as a bed, refused as sleepNotBed.
    for key in [
        "tribe-epsilon:loom003398683d-sense-11s",
        "tribe-kappa:loomf309750e7b-sense-10x",
    ] {
        let (seen, decided) = shown(key);
        let offered = OfferedRefs::of(&seen);
        assert!(offered.beds.is_empty(), "{key}: no bed was in sight");
        let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
        assert!(
            !valid(&schema, decided),
            "{key}: a sleep in a block that is no bed is no answer"
        );
        assert!(!offered
            .admits(&serde_json::from_value(decided["decision"]["intent"].clone()).unwrap()));
    }
}
