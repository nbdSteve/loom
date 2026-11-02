//! The independent r23 audit, replayed against today's request schema.
//!
//! Each case reads the exact perception a cited record carries in
//! `research/experiments/2026-10-16-openai-10-agent-10m-r23/evidence.jsonl`, with the goal and
//! horizon the matching `decided` record says the mind was shown, and asks what the schema now
//! lets a model answer.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R23: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r23/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

fn record(id: &str) -> &'static Value {
    R23.iter()
        .find(|record| record["recordId"] == id)
        .unwrap_or_else(|| panic!("no record {id}"))
}

/// The perception `perceived` carries, as the mind was shown it by `decided`.
fn shown(perceived: &str, decided: &str) -> Perception {
    let mut perception = record(perceived)["perception"].clone();
    let shown = &record(decided)["shown"];
    for key in ["goal", "horizon"] {
        if let Some(value) = shown.get(key) {
            perception[key] = value.clone();
        }
    }
    serde_json::from_value(perception).unwrap()
}

fn valid(schema: &Value, intent: &Value, goal: &Value) -> bool {
    let answer = json!({ "decision": { "intent": intent, "goal": goal }, "reasoning": "a replay" });
    jsonschema::validator_for(schema).unwrap().is_valid(&answer)
}

#[test]
fn epsilon_with_nothing_grounded_at_body_36_sets_a_discovery_goal_rather_than_none() {
    // body:00000038 walked to x1 holding no goal, because none could be set.
    let seen = shown("body:00000036", "body:00000038");
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let walk = json!({ "action": "moveTo", "onto": "x1" });
    assert!(valid(
        &schema,
        &walk,
        &json!({ "disposition": "adopt", "discover": true })
    ));
    assert!(!valid(&schema, &walk, &json!(null)));
}

#[test]
fn eta_holding_two_logs_at_body_1532_may_switch_its_sapling_goal_into_planks() {
    // body:00001542 could only abandon its unmet sapling goal before crafting eight planks. Eight is
    // all one craft of both logs makes, so the objective asks nine (the r39 offline audit), which
    // the horizon today's JVM grounds in the logs reaches: past the craft by its span.
    let mut seen = shown("body:00001532", "body:00001542");
    for goal in &mut seen.horizon {
        if goal.what == "item.minecraft.oak_planks" {
            goal.most = 8 + 8;
        }
    }
    assert_eq!(
        seen.goal
            .as_ref()
            .and_then(|goal| goal.success_item.as_deref()),
        Some("item.minecraft.oak_sapling")
    );
    let schema = decision_schema_for(&OfferedRefs::of(&seen), GoalPhase::of(&seen));
    let craft = json!({ "action": "craft", "recipe": "r6j", "crafts": 2 });
    let switch = json!({
        "disposition": "replace",
        "successItem": "item.minecraft.oak_planks",
        "successCount": 9,
        "stepResult": "noProgress",
        "reason": "superseded",
    });
    assert!(valid(&schema, &craft, &switch));
    let mut trivial = switch.clone();
    trivial["successCount"] = json!(8);
    assert!(!valid(&schema, &craft, &trivial));
}

#[test]
fn epsilon_beside_two_logs_at_body_88_is_not_offered_a_walk_to_look_further() {
    // body:00000089 adopted one log and walked to x2 with b57m and b57r in dig reach.
    let seen = shown("body:00000088", "body:00000089");
    let offered = OfferedRefs::of(&seen);
    let log = offered
        .item_steps
        .iter()
        .find(|steps| steps.item == "item.minecraft.oak_log")
        .expect("logs are a goal item here");
    assert!(log.dig.iter().any(|block| block == "b57r"));
    assert!(log.toward.is_empty(), "{:?}", log.toward);
}
