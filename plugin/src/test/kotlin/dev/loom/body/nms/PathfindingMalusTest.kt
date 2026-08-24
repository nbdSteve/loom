/*
 * What a Loom body will and will not walk through, checked without a world.
 *
 * The policy is a pure function precisely so it can be read here: constructing a
 * `LoomBodyEntity` needs a real `Level`, so the pricing could not otherwise be asserted at
 * all. `PathType` is a registry-free enum with its own `getMalus`, which is what lets each
 * case say what vanilla would have done as well as what Loom does instead.
 *
 * Engine types are allowed in this source set: `BodyBoundaryTest` imports production
 * classes only, and the seam it protects is about where `net.minecraft` may be *used*, not
 * about where it may be observed from.
 */
package dev.loom.body.nms

import dev.loom.body.BODY_FOLLOW_RANGE_BLOCKS
import net.minecraft.world.level.pathfinder.PathType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The types the engine can put under a body's feet that Loom re-prices, or would be tempted
 * to.
 *
 * Which of these may be negative is the whole question, and the answer is none of them but
 * water. `getStartNode` copies the malus onto the start node with no sign gate;
 * `isNeighborValid` then accepts every neighbour of a negative node, and `PathFinder` adds
 * the malus to `g` without checking the sign - so *any* negative type under the body makes
 * water both reachable and cheaper than dry ground, whatever the type was about. Both the
 * adjacency readings (`checkNeighbourBlocks` answers `WATER_BORDER` beside water and
 * `DAMAGE_CAUTIOUS` beside a wither rose) and the standing-in readings (`getPathTypeStatic`
 * answers `DAMAGE_FIRE` for a tile whose block below is burning, which `isBurningBlock`
 * counts a magma block and a lit campfire as) land here.
 */
private val TYPES_UNDER_A_BODY =
    setOf(
        PathType.WATER_BORDER,
        PathType.DANGER_FIRE,
        PathType.DANGER_OTHER,
        PathType.DAMAGE_CAUTIOUS,
        PathType.DAMAGE_FIRE,
    )

class PathfindingMalusTest {
    /** What the body ends up with: Loom's price where it has one, the engine's otherwise. */
    private fun effectiveMalus(type: PathType): Float = loomPathfindingMalusFor(type) ?: type.malus

    @Test
    @DisplayName("water is impassable, because nothing can swim a body out")
    fun waterIsImpassable() {
        // The body has no swim mechanism at all: setCanFloat(true) is a node-evaluator
        // setting rather than buoyancy, and the villager buoyancy that would jump it -
        // the brain behaviour Swim - is never ticked. Vanilla prices water as ordinary
        // going, which is how a route Loom chose could drown an agent.
        assertEquals(8.0f, PathType.WATER.malus)

        assertEquals(IMPASSABLE_MALUS, loomPathfindingMalusFor(PathType.WATER))
        assertEquals(IMPASSABLE_MALUS, effectiveMalus(PathType.WATER))
    }

    @Test
    @DisplayName("the shore stays walkable, because pricing it impassable would make water the cheapest ground")
    fun theShoreStaysWalkable() {
        // The trap, and the reason this asserts the *effective* value rather than that Loom
        // returns null: an impassable shore is not a wall. A dry tile within one block of
        // water already resolves as WATER_BORDER, so a negative price there gives the search
        // a negative start node, which accepts every neighbour including the blocked water
        // node, and costs 1 - 1 = 0 against 1 for dry ground. Water becomes free.
        val shore = effectiveMalus(PathType.WATER_BORDER)

        assertTrue(shore >= 0.0f, "an impassable shore inverts the water policy: $shore")
        assertEquals(PathType.WATER_BORDER.malus, shore, "the shore is left at the engine's own cost")
    }

    @Test
    @DisplayName("water is the only type Loom prices negative")
    fun waterIsTheOnlyImpassableType() {
        // The general rule, over every type at once and with no exemptions: a negative start
        // node unlocks *every* hazard from that tile, not only the one its type was about.
        // Two cases got here separately - a dripstone beside a pond, because
        // checkNeighbourBlocks answers on the first matching neighbour in i,j,k order and the
        // dripstone at i=-1 is reached before the water at i=+1; and a body standing on a
        // magma block, which getPathTypeStatic answers DAMAGE_FIRE for.
        val negative = PathType.entries.filter { loomPathfindingMalusFor(it)?.let { price -> price < 0.0f } == true }

        assertEquals(listOf(PathType.WATER), negative, "only water may be negative; anything else reopens water")
    }

    @Test
    @DisplayName("no type the engine can put under a body is priced negative except water")
    fun nothingUnderABodyIsImpassable() {
        // The same rule read from the other end, over the types that actually land under a
        // body's feet. Kept separate from the sweep above because this is the set a future
        // hazard would be added to, and it names why each member is in it.
        val negativeUnderfoot = TYPES_UNDER_A_BODY.filter { effectiveMalus(it) < 0.0f }

        assertEquals(
            emptyList<PathType>(),
            negativeUnderfoot,
            "a negative type under a body makes water reachable and free from that tile",
        )
    }

    @Test
    @DisplayName("a wither rose is expensive rather than impassable, so it cannot unlock water")
    fun freeHazardsAreExpensiveNotImpassable() {
        // DAMAGE_CAUTIOUS is wither roses and pointed dripstone, and vanilla charges *zero*
        // for them, so a body would walk over a dripstone spike for free. Loom cannot answer
        // that with impassable: the engine gives the same type to a tile merely beside one,
        // so the price has to serve both readings and a negative one would invert.
        assertEquals(0.0f, PathType.DAMAGE_CAUTIOUS.malus)

        val hazard = effectiveMalus(PathType.DAMAGE_CAUTIOUS)

        assertEquals(BESIDE_HAZARD_MALUS, hazard)
        assertTrue(hazard >= 0.0f, "an impassable wither rose gives a body beside one a negative start node")
        assertTrue(hazard > PathType.DAMAGE_CAUTIOUS.malus, "vanilla charges nothing; Loom must charge more")
    }

    @Test
    @DisplayName("a hazard step costs more than any detour that could complete")
    fun oneHazardStepCostsMoreThanTheWholeWalkBudget() {
        // Why the number is the walk budget and not a taste. PathFinder charges a step
        // `distance + costMalus`, so an ordinary step costs 1 and a hazard step costs
        // 1 + BESIDE_HAZARD_MALUS, while a detour of N ordinary steps costs N - and the search
        // abandons a route whose walked length reaches the budget, so N can never exceed it.
        // Derived rather than pinned as 16, since a literal stops being true if the budget grows.
        assertEquals(BODY_FOLLOW_RANGE_BLOCKS.toFloat(), BESIDE_HAZARD_MALUS)
        assertTrue(
            1.0f + BESIDE_HAZARD_MALUS > BODY_FOLLOW_RANGE_BLOCKS.toFloat(),
            "a hazard step must cost more than the longest detour that can complete",
        )
    }

    @Test
    @DisplayName("standing in fire is the most expensive step there is, and still not impassable")
    fun standingInFireIsExpensiveNotImpassable() {
        // Impassable fire was a negative start node for any body standing on a magma block or
        // a lit campfire, which reopened water from that tile. The price is positive instead,
        // and ordered above being merely beside fire.
        val inFire = effectiveMalus(PathType.DAMAGE_FIRE)

        assertEquals(IN_HAZARD_MALUS, inFire)
        assertTrue(inFire >= 0.0f, "impassable fire gives a body standing on a magma block a negative start node")
        assertTrue(inFire > effectiveMalus(PathType.DANGER_FIRE), "being in fire must cost more than being beside it")
    }

    @Test
    @DisplayName("the three hazard prices order the way the danger does, and none is negative")
    fun theHazardPricesAreOrdered() {
        // The numbers only mean anything together: in the hazard, beside it, then the shore at
        // the engine's own cost. Every one positive, which is the property that keeps water shut.
        val inFire = effectiveMalus(PathType.DAMAGE_FIRE)
        val besideFire = effectiveMalus(PathType.DANGER_FIRE)
        val shore = effectiveMalus(PathType.WATER_BORDER)

        assertTrue(inFire > besideFire, "in fire ($inFire) must cost more than beside it ($besideFire)")
        assertTrue(besideFire > shore, "beside fire ($besideFire) must cost more than the shore ($shore)")
        assertTrue(shore > 0.0f, "the shore must stay positive: $shore")
    }

    @Test
    @DisplayName("the blocks beside fire stay expensive rather than impassable")
    fun besideFireIsMerelyExpensive() {
        // Every hazard *adjacency* is non-negative, and being in the hazard is the only
        // impassable case - see onlyBeingInAHazardIsImpassable for why. So the blocks beside
        // fire are priced above vanilla's own rather than below zero, which also leaves a
        // body already standing next to a campfire able to walk away from it.
        val besideFire =
            loomPathfindingMalusFor(PathType.DANGER_FIRE)
                ?: fail("the blocks beside fire must be priced by Loom rather than left at the engine's default")

        assertTrue(besideFire > 0.0f, "a body beside fire must still be able to walk out: $besideFire")
        assertTrue(
            besideFire > PathType.DANGER_FIRE.malus,
            "beside fire must cost more than vanilla charges (${PathType.DANGER_FIRE.malus}): $besideFire",
        )
    }

    @Test
    @DisplayName("ordinary going is left exactly as the engine priced it")
    fun ordinaryGoingIsUntouched() {
        // A policy that answered for everything would be silently re-pricing the whole
        // world, so the cases Loom does not decide must come back as "leave it alone".
        assertNull(loomPathfindingMalusFor(PathType.WALKABLE))
        assertNull(loomPathfindingMalusFor(PathType.OPEN))
        assertNull(loomPathfindingMalusFor(PathType.DOOR_OPEN))
        // Already impassable by default, so Loom has nothing to add.
        assertNull(loomPathfindingMalusFor(PathType.LAVA))
        assertNull(loomPathfindingMalusFor(PathType.POWDER_SNOW))
    }

    @Test
    @DisplayName("nothing Loom prices is cheaper than the engine already made it")
    fun noHazardIsEverMadeCheaper() {
        // The failure this rules out across every type at once: a malus meant as "costly"
        // that is positive but *below* the engine's own, which would make a hazard more
        // attractive than vanilla thought it was. Impassable is exempted here because
        // `WalkNodeEvaluator` reads it as a sign rather than a cost - but only in its accept
        // path, and `PathFinder` does add it to `g`, so the exemption is not free. What
        // bounds it is onlyBeingInAHazardIsImpassable, which is where the shore case lives.
        val repriced = PathType.entries.mapNotNull { type -> loomPathfindingMalusFor(type)?.let { type to it } }

        assertTrue(repriced.isNotEmpty(), "the policy must actually re-price something")
        for ((type, malus) in repriced) {
            assertTrue(
                malus == IMPASSABLE_MALUS || malus >= type.malus,
                "$type is priced at $malus, under the engine's own ${type.malus}",
            )
        }
    }
}
