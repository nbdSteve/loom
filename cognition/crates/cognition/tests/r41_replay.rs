//! R41's upward goal revisions, replayed against today's request schema from the exact records
//! `research/experiments/2026-10-16-openai-10-agent-10m-r41/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R41: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r41/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R41
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R41
        .iter()
        .find(|record| record["event"] == "perceived" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no perception {key}"));
    let mut perception = perceived["perception"].clone();
    for field in ["goal", "lastRefusal", "horizon"] {
        if let Some(value) = decided["shown"].get(field) {
            perception[field] = value.clone();
        }
    }
    (serde_json::from_value(perception).unwrap(), decided)
}

fn valid(schema: &Value, decided: &Value) -> bool {
    let answer = json!({
        "decision": { "intent": decided["decision"]["intent"], "goal": decided["decision"]["goal"] },
        "reasoning": "a replay"
    });
    jsonschema::validator_for(schema).unwrap().is_valid(&answer)
}

/// The least total of every revise shape `schema` offers.
fn revise_floors(schema: &Value) -> Vec<i64> {
    let mut floors = Vec::new();
    let mut stack = vec![schema];
    while let Some(node) = stack.pop() {
        match node {
            Value::Object(map) => {
                let properties = map.get("properties");
                let disposition = properties.and_then(|props| props.get("disposition"));
                if disposition.and_then(|field| field.get("enum")) == Some(&json!(["revise"])) {
                    let count = properties.and_then(|props| props.get("successCount"));
                    if let Some(least) = count
                        .and_then(|field| field.get("minimum"))
                        .and_then(Value::as_i64)
                    {
                        floors.push(least);
                    }
                }
                stack.extend(map.values());
            }
            Value::Array(items) => stack.extend(items),
            _ => {}
        }
    }
    floors
}

#[test]
fn theta_could_not_grow_its_met_dirt_goal_sealed_in_at_night() {
    // R41 theta (body:00000947, tick 7016): sealed in at night, its met "hold 10 dirt" revised to
    // 28 with no recipe and no shelter face to use it on, and dirt totals kept growing instead of
    // the goal completing.
    let (seen, decided) = shown("tribe-theta:loom9bc6579ca4-sense-oz");
    assert_eq!(GoalPhase::of(&seen), GoalPhase::Met);
    let offered = OfferedRefs::of(&seen);
    assert!(offered.shelter.sealed());
    assert!(!offered.held_serves_chain);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(
        !valid(&schema, decided),
        "an upward revise that serves no chain is no answer"
    );
    assert!(
        revise_floors(&schema).iter().all(|least| *least <= 10),
        "no revise above the held total is offered: {:?}",
        revise_floors(&schema)
    );
}

#[test]
fn iota_still_grows_planks_its_grounded_recipes_are_made_from() {
    // R41 iota (body:00001161, tick 8210): planks 17 to 19, beside a crafting table, buttons, a
    // pressure plate and sticks its horizon grounds in planks.
    let (seen, decided) = shown("tribe-iota:loom8b6535c92c-sense-sq");
    let offered = OfferedRefs::of(&seen);
    assert!(offered.held_serves_chain);
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(
        valid(&schema, decided),
        "an upward revise that feeds a grounded recipe is still offered"
    );
}
