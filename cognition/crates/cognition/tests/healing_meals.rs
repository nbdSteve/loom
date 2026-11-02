//! A hurt agent whose food is below vanilla's healing threshold is offered its food as a meal.
//!
//! r33: every agent's food fell below 18 with no saturation left, so none healed, and the agents
//! spent hundreds of ticks at 0.8 to 4.8 health; nothing offered a meal above food 7.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;

use loom_cognition::offered::OfferedRefs;
use loom_cognition::openai::wire::{decision_schema_for, GoalPhase};
use loom_contract::{Intent, Perception};

fn fixture(name: &str) -> Perception {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join(format!("../../../contract/fixtures/valid/{name}.json"));
    serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap()
}

#[test]
fn a_hurt_agent_below_the_healing_threshold_is_offered_only_its_food() {
    let hurt = fixture("perception-damaged-hurt-holding-food");
    let offered = OfferedRefs::of(&hurt);
    assert_eq!(
        offered.meals,
        vec!["i1".to_owned()],
        "the bread, never the dirt"
    );
    assert!(offered.survives(&Intent::Eat {
        slot_ref: "i1".to_owned()
    }));
    assert!(!offered.survives(&Intent::Eat {
        slot_ref: "i2".to_owned()
    }));
    // The request schema, survival-only beside an immediate hunter, offers the bread as a meal.
    let schema = decision_schema_for(&offered, GoalPhase::of(&hurt)).to_string();
    assert!(schema.contains("\"eat\""), "{schema}");
    assert!(schema.contains("\"i1\""), "{schema}");
}

#[test]
fn a_healthy_or_healing_agent_is_offered_no_meal_above_the_hunger_threshold() {
    let mut fed = fixture("perception-damaged-hurt-holding-food");
    fed.self_state.food = 18;
    assert!(OfferedRefs::of(&fed).meals.is_empty(), "food 18 heals");
    let mut well = fixture("perception-damaged-hurt-holding-food");
    well.self_state.health = 16.0;
    assert!(OfferedRefs::of(&well).meals.is_empty(), "not hurt");
}
