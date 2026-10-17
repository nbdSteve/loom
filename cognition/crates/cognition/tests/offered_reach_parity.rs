//! Holds the mind's reach bounds to the body and JVM bounds they mirror.
//!
//! [`OfferedRefs`](loom_cognition::offered::OfferedRefs) offers an action only what that
//! action can reach, so every bound it uses is a copy of one the body or the JVM enforces.
//! A copy can drift; this reads each original from its own source file, so moving one without
//! the other fails here rather than as a refusal in a live run.

// Tests may panic/unwrap/expect: a failed assertion is the intended signal.
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

use std::fs;
use std::path::Path;

use loom_cognition::offered::{
    ATTACK_REACH_BLOCKS, EYE_HEIGHT_BLOCKS, INTERACTION_REACH_BLOCKS, MAX_PURSUIT_BLOCKS,
    MOVE_REACH_BLOCKS, PASSIVE_PICKUP_REACH_BLOCKS,
};

fn source(relative: &str) -> String {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    fs::read_to_string(root.join(relative)).unwrap_or_else(|e| panic!("{relative}: {e}"))
}

/// The number a `const NAME = value` or `const val NAME = value` line states.
fn constant(relative: &str, name: &str) -> f64 {
    let text = source(relative);
    let line = text
        .lines()
        .find(|line| {
            let line = line.trim_start();
            (line.starts_with("const ") || line.starts_with("private const val "))
                && line.contains(&format!("{name} ="))
        })
        .unwrap_or_else(|| panic!("{relative} states no {name}"));
    let value = line.split('=').nth(1).unwrap().trim().trim_end_matches(';');
    value
        .replace('_', "")
        .parse()
        .unwrap_or_else(|e| panic!("{relative} {name} = {value}: {e}"))
}

#[test]
fn every_reach_bound_is_the_one_the_body_or_jvm_enforces() {
    let pairs = [
        (
            "body/src/sensing.js",
            "INTERACTION_REACH_BLOCKS",
            INTERACTION_REACH_BLOCKS,
        ),
        (
            "body/src/collecting.js",
            "PASSIVE_PICKUP_REACH_BLOCKS",
            PASSIVE_PICKUP_REACH_BLOCKS,
        ),
        (
            "body/src/movement.js",
            "MOVE_REACH_BLOCKS",
            MOVE_REACH_BLOCKS,
        ),
        (
            "plugin/src/main/kotlin/dev/loom/mind/WorkingGoal.kt",
            "COLLECT_REACH_BLOCKS",
            MOVE_REACH_BLOCKS,
        ),
        (
            "body/src/combat.js",
            "ATTACK_REACH_BLOCKS",
            ATTACK_REACH_BLOCKS,
        ),
        (
            "plugin/src/main/kotlin/dev/loom/body/player/PlayerSurvivalPreparer.kt",
            "COMBAT_MAX_PURSUIT_DISTANCE",
            MAX_PURSUIT_BLOCKS,
        ),
    ];
    for (file, name, mirrored) in pairs {
        assert!(
            (constant(file, name) - mirrored).abs() < f64::EPSILON,
            "{file} {name} is {} but the mind offers against {mirrored}",
            constant(file, name)
        );
    }
}

#[test]
fn interaction_reach_is_measured_from_the_eyes_the_body_measures_from() {
    // The body measures from Mineflayer's own `eyeHeight`; `body/test/libraries.test.js` pins
    // the pinned client's player value against this one.
    assert!(source("body/src/interaction.js").contains("bot.entity?.eyeHeight"));
    assert!(source("body/test/libraries.test.js").contains(&format!("{EYE_HEIGHT_BLOCKS}")));
}
