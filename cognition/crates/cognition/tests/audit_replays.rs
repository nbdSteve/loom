//! Audit replays: each case reads the exact perception a cited record in a committed run's
//! `research/experiments/*/evidence.jsonl` carries, with everything the matching `decided` record
//! says the mind was shown, and asks what today's request schema lets a model answer.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;
use std::sync::OnceLock;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::decision_schema_for;
use loom_contract::Perception;
use serde_json::{json, Value};

/// One committed experiment's evidence, read once and exactly as it was published.
struct CommittedEvidence {
    experiment: &'static str,
    records: OnceLock<Vec<Value>>,
}

impl CommittedEvidence {
    const fn new(experiment: &'static str) -> Self {
        Self {
            experiment,
            records: OnceLock::new(),
        }
    }

    fn all(&'static self) -> &'static [Value] {
        self.records.get_or_init(|| {
            let path = Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../../research/experiments")
                .join(self.experiment)
                .join("evidence.jsonl");
            fs::read_to_string(&path)
                .unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
                .lines()
                .filter(|line| !line.trim().is_empty())
                .map(|line| serde_json::from_str(line).unwrap())
                .collect()
        })
    }

    fn record(&'static self, id: &str) -> &'static Value {
        self.all()
            .iter()
            .find(|record| record["recordId"] == id)
            .unwrap_or_else(|| panic!("{}: no record {id}", self.experiment))
    }

    fn of_request(&'static self, event: &str, key: &str) -> &'static Value {
        self.all()
            .iter()
            .find(|record| record["event"] == event && record["requestKey"] == key)
            .unwrap_or_else(|| panic!("{}: no {event} {key}", self.experiment))
    }

    /// The perception request `key` carried, with every field its `decided` record says the mind
    /// was shown laid over it, and that record.
    fn shown(&'static self, key: &str) -> (Perception, &'static Value) {
        let decided = self.of_request("decided", key);
        let mut perception = self.of_request("perceived", key)["perception"].clone();
        if let Some(shown) = decided["shown"].as_object() {
            for (field, value) in shown {
                perception[field] = value.clone();
            }
        }
        (serde_json::from_value(perception).unwrap(), decided)
    }
}

static R23: CommittedEvidence = CommittedEvidence::new("2026-10-16-openai-10-agent-10m-r23");
static R24: CommittedEvidence = CommittedEvidence::new("2026-10-16-openai-10-agent-10m-r24");
static R27: CommittedEvidence = CommittedEvidence::new("2026-10-16-openai-10-agent-10m-r27");
static R35: CommittedEvidence = CommittedEvidence::new("2026-10-16-openai-10-agent-10m-r35");
static R41: CommittedEvidence = CommittedEvidence::new("2026-10-16-openai-10-agent-10m-r41");
static R44: CommittedEvidence = CommittedEvidence::new("2026-10-16-openai-10-agent-10m-r44");

fn schema_of(seen: &Perception) -> (OfferedRefs, Value) {
    let offered = OfferedRefs::of(seen);
    let schema = decision_schema_for(&offered);
    (offered, schema)
}

/// Whether `schema` admits `intent` beside `goal`, in the shape it asks for.
fn valid(schema: &Value, intent: &Value, goal: &Value) -> bool {
    let _ = goal;
    let answer =
        json!({ "intent": intent, "plan": { "update": "continue" }, "reasoning": "a replay" });
    jsonschema::validator_for(schema).unwrap().is_valid(&answer)
}

/// Whether `schema` admits the answer `decided` records.
fn answered(schema: &Value, decided: &Value) -> bool {
    let decision = &decided["decision"];
    let goal = decision.get("goal").cloned().unwrap_or(Value::Null);
    valid(schema, &decision["intent"], &goal)
}

fn intent_of(decided: &Value) -> loom_contract::Intent {
    serde_json::from_value(decided["decision"]["intent"].clone()).unwrap()
}

mod danger {
    use super::{answered, schema_of, R27};

    #[test]
    fn r27_delta_was_never_offered_a_flight_from_a_peer() {
        // body:00000763, decided body:00000786: four players and two sheep in sight and no hunter,
        // and the schema let it flee the player e27j beside an abandon.
        let (seen, decided) = R27.shown("tribe-delta:loom3b4ef3e082-sense-ik");
        let (offered, schema) = schema_of(&seen);
        assert!(
            offered.flee_targets.is_empty(),
            "{:?}",
            offered.flee_targets
        );
        assert!(!answered(&schema, decided), "the peer flight is no answer");
    }
}

mod crafting {
    use super::{intent_of, schema_of, valid, R35};
    use loom_contract::Intent;
    use serde_json::{json, Value};

    #[test]
    fn r35_alpha_could_never_ask_two_crafts_of_a_recipe_its_planks_made_once() {
        // sense-hh, refused craftQuantityExceeded at body:00000215: two planks made one
        // application of sticks, and the schema let it ask two.
        let (seen, decided) = R35.shown("tribe-alpha:loomaa88da42d7-sense-hh");
        let sticks = seen
            .self_state
            .crafting_options
            .iter()
            .find(|option| option.recipe_ref == "rct")
            .unwrap();
        assert_eq!(sticks.max_crafts, 1);
        let (offered, schema) = schema_of(&seen);
        assert_eq!(offered.crafts_of("rct"), 1);
        assert_eq!(offered.crafts_of("rcr"), 2);
        // Its replace of the met stick goal predates `reason`; routine-v36 states it as completed.
        let mut goal = decided["decision"]
            .get("goal")
            .cloned()
            .unwrap_or(Value::Null);
        goal["reason"] = json!("completed");
        assert_eq!(decided["decision"]["intent"]["crafts"], 2);
        assert!(
            !valid(&schema, &decided["decision"]["intent"], &goal),
            "two applications of a recipe that allows one are no answer"
        );
        let once = json!({ "action": "craft", "recipe": "rct", "crafts": 1 });
        assert!(valid(&schema, &once, &goal), "one application still is");
        assert!(!offered.admits(&intent_of(decided)));
        assert!(offered.admits(&Intent::Craft {
            recipe: "rcr".to_owned(),
            crafts: 2
        }));
    }
}

mod placement {
    use super::{answered, intent_of, schema_of, R44};
    use serde_json::json;

    #[test]
    fn r44_theta_could_not_place_a_stack_it_did_not_hold() {
        // body:00001886, tick 9800: a threat-sighting answer placed its oak wood iu5 while its
        // main hand held the oak planks iu8, refused as placeItemNotHeld.
        let (seen, decided) = R44.shown("tribe-theta:loom9bc6579ca4-sense-yu");
        let (offered, schema) = schema_of(&seen);
        assert_eq!(offered.held_stacks, vec!["iu8".to_owned()]);
        assert_eq!(decided["decision"]["intent"]["item"], "iu5");
        assert!(
            !answered(&schema, decided),
            "a placement of a stack not in hand is no answer"
        );
        let mut in_hand = decided.clone();
        in_hand["decision"]["intent"]["item"] = json!("iu8");
        assert!(answered(&schema, &in_hand), "the stack in hand still is");
        assert!(!offered.admits(&intent_of(decided)));
    }
}

mod goal_revise {
    use super::{answered, schema_of, R41};

    #[test]
    fn r41_iota_still_grows_planks_its_grounded_recipes_are_made_from() {
        // body:00001161, tick 8210: planks 17 to 19, beside a crafting table, buttons, a pressure
        // plate and sticks its horizon grounds in planks.
        let (seen, decided) = R41.shown("tribe-iota:loom8b6535c92c-sense-sq");
        let (offered, schema) = schema_of(&seen);
        assert!(offered.held_serves_chain);
        assert!(
            answered(&schema, decided),
            "an upward revise that feeds a grounded recipe is still offered"
        );
    }
}

mod item_steps {
    use super::{answered, schema_of, R23, R24};
    use loom_cognition::offered::OfferedRefs;

    #[test]
    fn r23_epsilon_beside_two_logs_is_not_offered_a_walk_to_look_further() {
        // body:00000088, decided body:00000089: adopted one log and walked to x2 with b57m and
        // b57r in dig reach.
        let (seen, _) = R23.shown("tribe-epsilon:loom003398683d-sense-9i");
        let offered = OfferedRefs::of(&seen);
        let log = offered
            .item_steps
            .iter()
            .find(|steps| steps.item == "item.minecraft.oak_log")
            .expect("logs are a goal item here");
        assert!(log.dig.iter().any(|block| block == "b57r"));
        assert!(log.toward.is_empty(), "{:?}", log.toward);
    }

    #[test]
    fn r24_theta_is_offered_the_walk_past_grass_the_ledger_now_takes() {
        // body:00000078: a continue with a walk beside 13% grass, refused goalStepUnproductive.
        // Grass is no sure source, so the walk is offered and the ledger takes it.
        assert_eq!(
            R24.record("body:00000078")["outcome"],
            "goalStepUnproductive"
        );
        let (seen, decided) = R24.shown("tribe-theta:loom9bc6579ca4-sense-3g");
        let (offered, schema) = schema_of(&seen);
        assert!(offered.productive.is_empty(), "{:?}", offered.productive);
        assert!(answered(&schema, decided));
    }
}
