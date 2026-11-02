//! R49's one goal incoherence, replayed against today's request schema from the exact records
//! `research/experiments/2026-10-16-openai-10-agent-10m-r49/evidence.jsonl` carries.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::LazyLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::Perception;
use serde_json::{json, Value};

static R49: LazyLock<Vec<Value>> = LazyLock::new(|| {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../research/experiments/2026-10-16-openai-10-agent-10m-r49/evidence.jsonl");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
});

/// The decided record answering request `key`, and the perception its mind was shown.
fn shown(key: &str) -> (Perception, &'static Value) {
    let decided = R49
        .iter()
        .find(|record| record["event"] == "decided" && record["requestKey"] == key)
        .unwrap_or_else(|| panic!("no decision {key}"));
    let perceived = R49
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

#[test]
fn zeta_could_not_walk_past_an_oak_log_in_reach_for_an_oak_wood_goal() {
    // R49 zeta (body:00000789, tick 6134): holding "hold 32 item.minecraft.stick", it replaced the
    // goal by 8 oak wood and walked to the edge of sight, with oak log bbog 2.7 blocks off in dig
    // reach. The schema offered only walks for oak wood, because a held goal had narrowed every
    // item's digs to its own sources, and the ledger refused the walk as goalStepUnproductive.
    let (seen, decided) = shown("tribe-zeta:loom3d8263b30e-sense-lf");
    assert_eq!(GoalPhase::of(&seen), GoalPhase::Held);
    let offered = OfferedRefs::of(&seen);
    let wood = offered
        .item_steps
        .iter()
        .find(|steps| steps.item == "item.minecraft.oak_wood")
        .expect("oak wood is offered as a replace");
    assert!(
        wood.dig.iter().any(|block| block == "bbog"),
        "the oak log in reach is oak wood's step: {wood:?}"
    );
    assert!(
        wood.toward.is_empty(),
        "no walk to look further past a source in reach: {wood:?}"
    );
    let stick = offered
        .item_steps
        .iter()
        .find(|steps| steps.item == "item.minecraft.stick")
        .expect("the held goal's own steps");
    assert!(
        !stick.dig.iter().any(|block| block == "bbog"),
        "the held goal is still stepped only toward its own item: {stick:?}"
    );
    let schema = decision_schema_for(&offered, GoalPhase::of(&seen));
    assert!(
        !valid(&schema, decided),
        "the walk the ledger refused is no answer"
    );
}
