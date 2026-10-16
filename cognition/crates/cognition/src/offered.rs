//! The refs one perception offers, by the namespace each action may name.
//!
//! A ref is a request-scoped handle, and the body keeps one store per kind of thing it
//! names: creatures, blocks, dropped items, inventory stacks and recipes. An action can
//! spend a handle only from its own store, so a `dig` naming a creature or a `moveTo`
//! naming a stack is a decision the body must refuse. Prose alone does not prevent that,
//! so this is the set both the strict provider schema ([`crate::openai::wire`]) and
//! [`crate::mind::Mind::decide`] hold every answer to.
//!
//! The set is exactly what the prompt shows, cut by the same bounds as
//! [`crate::prompt`], because a handle the mind was never shown is not one it was offered.
//!
//! A walk is narrower than a dig. A block in the agent's own column - the one under its
//! feet, or a plant it stands in - is a block it can dig or place against, and a walk to
//! it goes nowhere, so it is not offered to `moveTo` at all.

use loom_contract::{Bearing, Intent, ObservationKind, Perception, Trigger};

use crate::prompt::{MAX_CRAFTING_LINES, MAX_INVENTORY_LINES, MAX_OBSERVATIONS};

/// The handles one perception offers, each list in the order the prompt shows it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct OfferedRefs {
    /// Every shown block: what `dig` and `place` may name.
    pub blocks: Vec<String>,
    /// Shown blocks outside the agent's own column: what `moveTo` may name.
    pub walk_targets: Vec<String>,
    /// Shown living things, and a sighted threat: what `flee` and `attack` may name.
    pub creatures: Vec<String>,
    /// Shown dropped-item entities: what `collect` may name.
    pub dropped_items: Vec<String>,
    /// Shown inventory stacks: what `eat`, `equip`, `place` and an attack's weapon may name.
    pub stacks: Vec<String>,
    /// Shown craftable recipes: what `craft` may name.
    pub recipes: Vec<String>,
}

/// Appends `handle` unless the list already holds it.
fn offer(list: &mut Vec<String>, handle: &str) {
    if !list.iter().any(|held| held == handle) {
        list.push(handle.to_owned());
    }
}

impl OfferedRefs {
    /// The handles `perception` offers.
    #[must_use]
    pub fn of(perception: &Perception) -> Self {
        let mut offered = Self::default();
        for seen in perception.surroundings.iter().take(MAX_OBSERVATIONS) {
            let handle = seen.target_ref.as_str();
            match seen.kind {
                ObservationKind::Block => {
                    offer(&mut offered.blocks, handle);
                    if seen.direction != Bearing::Here {
                        offer(&mut offered.walk_targets, handle);
                    }
                }
                ObservationKind::Entity => offer(&mut offered.creatures, handle),
                ObservationKind::DroppedItem => offer(&mut offered.dropped_items, handle),
            }
        }
        // The sighted threat's handle travels with the trigger and names a creature.
        if let Trigger::ThreatSighted { entity_ref, .. } = &perception.trigger {
            offer(&mut offered.creatures, entity_ref);
        }
        let state = &perception.self_state;
        for stack in state.inventory_summary.iter().take(MAX_INVENTORY_LINES) {
            offer(&mut offered.stacks, &stack.slot_ref);
        }
        for option in state.crafting_options.iter().take(MAX_CRAFTING_LINES) {
            offer(&mut offered.recipes, &option.recipe_ref);
        }
        offered
    }

    /// Whether every ref `intent` names is offered in the namespace its action spends.
    #[must_use]
    pub fn admits(&self, intent: &Intent) -> bool {
        let held = |list: &[String], handle: &str| list.iter().any(|offered| offered == handle);
        match intent {
            Intent::MoveTo { onto } => held(&self.walk_targets, onto),
            Intent::Flee { from } => held(&self.creatures, from),
            Intent::Eat { slot_ref } => held(&self.stacks, slot_ref),
            Intent::Dig { block } => held(&self.blocks, block),
            Intent::Collect { item, .. } => held(&self.dropped_items, item),
            Intent::Place { block, item, .. } => {
                held(&self.blocks, block) && held(&self.stacks, item)
            }
            Intent::Equip { item, .. } => held(&self.stacks, item),
            Intent::Craft { recipe, .. } => held(&self.recipes, recipe),
            Intent::Attack { target, weapon, .. } => {
                held(&self.creatures, target) && held(&self.stacks, weapon)
            }
            Intent::Sleep { bed } => held(&self.blocks, bed),
            Intent::Say { .. } | Intent::Rest | Intent::Idle => true,
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::OfferedRefs;
    use crate::prompt::{MAX_CRAFTING_LINES, MAX_INVENTORY_LINES};
    use loom_contract::{
        BlockFace, CraftingOption, EquipmentDestination, Intent, InventoryStack, Perception,
    };

    fn fixture(name: &str) -> Perception {
        let path = format!(
            "{}/../../../contract/fixtures/valid/{name}.json",
            env!("CARGO_MANIFEST_DIR")
        );
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    fn handles(list: &[&str]) -> Vec<String> {
        list.iter().map(|&handle| handle.to_owned()).collect()
    }

    #[test]
    fn each_kind_of_thing_is_offered_only_to_its_own_namespace() {
        let offered = OfferedRefs::of(&fixture("perception-threat-sighted"));
        assert_eq!(offered.creatures, handles(&["e1", "e2"]));
        assert_eq!(offered.blocks, handles(&["b1"]));
        assert_eq!(offered.walk_targets, handles(&["b1"]));
        assert!(offered.dropped_items.is_empty());
        assert!(offered.stacks.is_empty());
        assert!(offered.recipes.is_empty());

        let crafting = OfferedRefs::of(&fixture("perception-crafting"));
        assert_eq!(crafting.stacks, handles(&["i1"]));
        assert_eq!(crafting.recipes, handles(&["r1"]));
    }

    #[test]
    fn an_own_column_block_is_dug_or_placed_against_but_never_walked_to() {
        let offered = OfferedRefs::of(&fixture("perception-standing-on"));
        assert_eq!(offered.blocks, handles(&["b1", "b2", "b3"]));
        assert_eq!(offered.walk_targets, handles(&["b3"]));
        for here in ["b1", "b2"] {
            assert!(offered.admits(&Intent::Dig {
                block: here.to_owned()
            }));
            assert!(!offered.admits(&Intent::MoveTo {
                onto: here.to_owned()
            }));
        }
        assert!(offered.admits(&Intent::MoveTo {
            onto: "b3".to_owned()
        }));
    }

    #[test]
    fn a_sighted_threat_is_a_creature_even_when_the_scene_was_cut_before_it() {
        let mut seen = fixture("perception-threat-sighted");
        seen.surroundings.clear();
        let offered = OfferedRefs::of(&seen);
        assert_eq!(offered.creatures.len(), 1);
        assert!(offered.admits(&Intent::Flee {
            from: offered.creatures[0].clone()
        }));
    }

    #[test]
    fn a_handle_of_the_wrong_kind_or_never_offered_is_refused_for_every_action() {
        let mut seen = fixture("perception-threat-sighted");
        seen.self_state.inventory_summary.push(InventoryStack {
            slot_ref: "i1".to_owned(),
            what: "item.minecraft.bread".to_owned(),
            count: 1,
        });
        seen.self_state.crafting_options.push(CraftingOption {
            recipe_ref: "r1".to_owned(),
            what: "item.minecraft.stick".to_owned(),
            result_count: 4,
            max_crafts: 1,
            requires_table: false,
        });
        let offered = OfferedRefs::of(&seen);
        let s = |handle: &str| handle.to_owned();
        let admitted = [
            Intent::MoveTo { onto: s("b1") },
            Intent::Flee { from: s("e1") },
            Intent::Eat { slot_ref: s("i1") },
            Intent::Dig { block: s("b1") },
            Intent::Place {
                block: s("b1"),
                item: s("i1"),
                face: BlockFace::Up,
            },
            Intent::Equip {
                item: s("i1"),
                destination: EquipmentDestination::Hand,
            },
            Intent::Craft {
                recipe: s("r1"),
                crafts: 1,
            },
            Intent::Attack {
                target: s("e1"),
                weapon: s("i1"),
                attacks: 1,
                approach: false,
            },
            Intent::Sleep { bed: s("b1") },
            Intent::Say { text: s("hello") },
            Intent::Rest,
            Intent::Idle,
        ];
        for intent in &admitted {
            assert!(offered.admits(intent), "{intent:?}");
        }
        let refused = [
            Intent::MoveTo { onto: s("e1") },
            Intent::Flee { from: s("b1") },
            Intent::Eat { slot_ref: s("r1") },
            Intent::Dig { block: s("e2") },
            Intent::Collect {
                item: s("b1"),
                approach: true,
            },
            Intent::Place {
                block: s("b1"),
                item: s("e1"),
                face: BlockFace::Up,
            },
            Intent::Place {
                block: s("i1"),
                item: s("i1"),
                face: BlockFace::Up,
            },
            Intent::Equip {
                item: s("b1"),
                destination: EquipmentDestination::Hand,
            },
            Intent::Craft {
                recipe: s("i1"),
                crafts: 1,
            },
            Intent::Attack {
                target: s("b1"),
                weapon: s("i1"),
                attacks: 1,
                approach: false,
            },
            Intent::Attack {
                target: s("e1"),
                weapon: s("e2"),
                attacks: 1,
                approach: false,
            },
            Intent::Sleep { bed: s("e1") },
            Intent::Dig { block: s("b9") },
        ];
        for intent in &refused {
            assert!(!offered.admits(intent), "{intent:?}");
        }
    }

    #[test]
    fn a_stack_or_recipe_the_prompt_cut_is_not_offered() {
        let mut seen = fixture("perception-crafting");
        let stack = seen.self_state.inventory_summary[0].clone();
        let option = seen.self_state.crafting_options[0].clone();
        seen.self_state.inventory_summary = (0..=MAX_INVENTORY_LINES)
            .map(|index| InventoryStack {
                slot_ref: format!("i{index}"),
                ..stack.clone()
            })
            .collect();
        seen.self_state.crafting_options = (0..=MAX_CRAFTING_LINES)
            .map(|index| CraftingOption {
                recipe_ref: format!("r{index}"),
                ..option.clone()
            })
            .collect();
        let offered = OfferedRefs::of(&seen);
        assert_eq!(offered.stacks.len(), MAX_INVENTORY_LINES);
        assert_eq!(offered.recipes.len(), MAX_CRAFTING_LINES);
        assert!(!offered.admits(&Intent::Eat {
            slot_ref: format!("i{MAX_INVENTORY_LINES}")
        }));
    }
}
