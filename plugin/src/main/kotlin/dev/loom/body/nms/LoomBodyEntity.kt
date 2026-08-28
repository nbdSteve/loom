/*
 * The bespoke body entity: a custom NMS class on a vanilla entity type.
 *
 * A literally-new registered entity type is not viable for a client-visible body
 * on this platform: registering one into the NMS registry succeeds only through
 * reflection against frozen internals, and then every spawn path dead-ends in
 * CraftBukkit's `CraftEntityType.minecraftToBukkit`, whose Bukkit-side registry is
 * an enum with no entry for a plugin's type - and a vanilla client has no renderer
 * for an unknown type regardless. So the body borrows a vanilla *type identity*
 * (`EntityType.VILLAGER`) while its *behavior* is entirely Loom's:
 *
 *  - `registerGoals` is empty, so not one vanilla goal selector runs;
 *  - `customServerAiStep` does not tick the villager brain, so no vanilla
 *    profession/trade/schedule behavior runs either;
 *  - the one piece of vanilla machinery the body does use is the ground navigator,
 *    and it only ever walks a route Loom asked it for. See `BodyNavigation.kt`.
 *
 * Two constraints force this exact shape, both discovered by running it: the body
 * must be spawned on a vanilla type, and it must genuinely `extend` that type's
 * class, because `CraftEntity.getEntity` casts the NMS entity to it.
 *
 * Villager is the chosen base type. It is the vanilla type whose identity best
 * fits a member of an artificial society: it is `Npc` rather than a `Monster`, so
 * `spawn-monsters=false` does not cull it, and it already declines to despawn when
 * far from a player (`removeWhenFarAway` is false), which is what Loom wants for a
 * body whose lifecycle Loom owns.
 *
 * IT IS NOT SAFE FROM ATTACK, whatever "not a monster" suggests. Pillager,
 * Vindicator, Evoker, Illusioner and Ravager all target `AbstractVillager` on sight,
 * and Zombie and Drowned do too unless `zombie-aggressive-towards-villager` is turned
 * off in `spigot.yml`. A run that wants to measure starvation rather than predation
 * has to choose those settings deliberately; `DEMO.md` lists them.
 *
 * THIS FILE AND ITS PACKAGE ARE THE ONLY PLACE `net.minecraft` MAY BE TOUCHED.
 * BodyBoundaryTest fails the build if that stops being true. A Minecraft version
 * bump should touch this package and nothing else.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BODY_FOLLOW_RANGE_BLOCKS
import dev.loom.body.BodyLocation
import dev.loom.body.IntentOutcome
import dev.loom.body.IntentSeam
import dev.loom.body.NavigationPolicy
import dev.loom.body.RetreatAttempt
import dev.loom.body.hasTravelled
import dev.loom.body.outcomeOf
import dev.loom.contract.Intent
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerType
import net.minecraft.world.level.Level
import net.minecraft.world.level.pathfinder.PathType

/**
 * How far ahead a body keeps itself activated, in ticks.
 *
 * Spigot's activation range decides whether an entity gets a full tick by asking
 * whether a *player* is nearby, and Loom has no players: left alone, every body
 * would drop to `inactiveTick` after ten seconds. That would not merely idle the
 * body, it would silently disable portal travel, because the portal transit is
 * driven from `postTick`, which an inactive entity never reaches. So a body keeps
 * its own activation, renewed from Loom's tick with enough margin to survive a tick
 * Loom misses.
 */
private const val ACTIVATION_LEAD_TICKS = 20L

/**
 * The pathfinding penalty that makes a kind of block impassable.
 *
 * Water is the only thing Loom prices this way, and the sign is the reason it has to be:
 * negative is the engine's own encoding for "never route through this" rather than a large
 * cost. `PathFinder` itself does not check the sign - it adds the malus to a node's `g`,
 * where a negative number would make the node *cheaper* - so what enforces this is
 * `WalkNodeEvaluator`: `findAcceptedNode` builds a node only for a malus of zero or more,
 * and `isNeighborValid` rejects the blocked node the descent scan does build.
 *
 * The same sign is also what makes it escapable, and that is inherent rather than a gap.
 * `isNeighborValid` opens *every* neighbour of a negative node, so a body already in water
 * can path out - and so can a body standing anywhere else the engine prices negative.
 */
internal const val IMPASSABLE_MALUS = -1.0f

/**
 * What one step *beside* a hazard costs when the hazard may not be made impassable.
 *
 * The whole walk budget, derived rather than written down, because that is exactly what
 * makes the number work. `PathFinder` charges a step `distance + costMalus`, so an ordinary
 * step costs 1 and a step priced here costs 17, while a detour of N ordinary steps costs N -
 * and the search abandons any route whose walked length reaches the budget. So every detour
 * that can complete at all is cheaper than one step through the hazard, and a route crosses
 * one only when there is no way round inside the budget. A literal would silently stop being
 * true the moment the budget grew.
 */
internal val BESIDE_HAZARD_MALUS = BODY_FOLLOW_RANGE_BLOCKS.toFloat()

/**
 * What one step *into* a hazard costs, for a hazard that may not be made impassable.
 *
 * Twice [BESIDE_HAZARD_MALUS], so the three prices order the way the danger does: being in
 * fire costs more than being beside it, which costs more than the shore at the engine's own
 * 8, and every one of them is positive. That last part is the point rather than a detail -
 * see the rule at [loomPathfindingMalusFor].
 */
internal val IN_HAZARD_MALUS = BESIDE_HAZARD_MALUS * 2

/**
 * What Loom prices [type] at, or `null` to leave the engine's own default alone.
 *
 * A pure function so the whole policy can be read, and tested, without a world: the body
 * does nothing but apply whatever this returns. Every entry is a survival decision rather
 * than a tuning knob, because Loom is permadeath and no flight escapes a hazard: an
 * `Intent.Flee` runs from a creature a mind named, and nothing runs from the fire or the
 * water a route walked into. The engine's defaults are written for mobs that respawn.
 *
 * Expensive but passable, in an order that matches the danger: standing *in* fire costs
 * [IN_HAZARD_MALUS], the blocks *beside* fire and the wither roses and pointed dripstone the
 * engine files under `DAMAGE_CAUTIOUS` cost [BESIDE_HAZARD_MALUS], and the shore keeps the
 * engine's own 8. Vanilla charges *zero* for `DAMAGE_CAUTIOUS`, so a body would otherwise
 * walk over a dripstone spike for free. Lava, cactus, sweet berries and powder snow are
 * already impassable by default and are left alone.
 *
 * Neither fire case may be impassable, for the reason the rule below gives, and the cost of
 * that is real and is accepted rather than welcomed: a route may now step through fire or a
 * wither rose where the only way round is a long detour, which an impassable price forbade.
 * `DAMAGE_CAUTIOUS` could not have been split in any case - `checkNeighbourBlocks` answers
 * it for a tile merely beside a wither rose as well as for one on it, and it has no
 * `DANGER_` variant - so one price has to serve both readings.
 *
 * Impassable, and the only thing here that is: water, and it costs a capability. THIS BODY
 * HAS NO SWIM MECHANISM AT ALL. `Villager`'s constructor calls `setCanFloat(true)`, but that is a
 * node-evaluator setting - it makes the *pathfinder* willing to route through water and
 * has nothing to do with buoyancy. A vanilla villager's buoyancy is the brain behaviour
 * `Swim(0.8F)`, whose `tick` jumps the mob, and [LoomBodyEntity.customServerAiStep] never
 * ticks the brain. So a body walked into water two blocks deep sinks, stops advancing its
 * route, and drowns on a route Loom itself chose. Rivers are deliberate walls until a
 * swimming capability exists, and that is the accepted cost of not drowning agents.
 *
 * WATER IS THE ONLY TYPE LOOM MAY PRICE NEGATIVE. What unlocks water is a negative *start*
 * node, whatever made it negative, so this is a rule about every type at once rather than
 * about water: pricing a second one impassable does not build a second wall, it takes the
 * first one down, which is the trap this paragraph exists to keep anyone from walking back
 * into. Four engine steps make it so. `getStartNode` assigns
 * `costMalus = getPathfindingMalus(type)` with no `>= 0` gate, so a negatively priced type
 * under the body hands the search a negative start node. `isNeighborValid` is
 * `node.costMalus >= 0 || successor.costMalus < 0`, so a negative *parent* accepts every
 * neighbour, including the `BLOCKED` water nodes `tryFindFirstGroundNodeBelow` builds. Each
 * of those is negative in turn, so the acceptance propagates across the whole river. And
 * `PathFinder` computes `g = node.g + f + node2.costMalus` with no sign check, so a water
 * step costs `1 - 1 = 0` against `1` for dry ground - water becomes the *cheapest* ground on
 * the map.
 *
 * Both readings of "under the body" reach it, which is why the rule cannot be narrowed to
 * adjacency types. `checkNeighbourBlocks` answers `WATER_BORDER` for a dry tile with water
 * anywhere in its 26 neighbours, and `DAMAGE_CAUTIOUS` for one beside a wither rose. And
 * `getPathTypeStatic` maps a standable tile whose block *below* is a burning block straight
 * to `DAMAGE_FIRE`, where `NodeEvaluator.isBurningBlock` counts a magma block and a lit
 * campfire as well as fire and lava - so a body standing on a magma block was a negative
 * start node too, until fire stopped being impassable here.
 *
 * The malus alone does the work, so `canFloat` is deliberately left on. Nothing in the
 * accept path lets it override the malus - it reaches only `getFloorLevel`, `getStart`,
 * and a branch that requires `!canFloat()` - and leaving it on helps a body that is
 * already under water, because `getStart` lifts its start node to the surface.
 *
 * Nothing Loom prices strands a body. A body standing in fire, beside fire, on dripstone or
 * on the shore now has a positive start node, so `isNeighborValid`'s first clause admits
 * every neighbour on its own. A body in water has the negative one, so the second clause
 * admits every neighbour instead and a route out exists - though nothing can swim it out.
 * The engine's own negatives do the same for the tiles it prices: of the nine in `PathType`,
 * the ones a body can be standing in are `LAVA`, `POWDER_SNOW`, `FENCE` and the sweet-berry
 * `DAMAGE_OTHER`, and from each of those the escape rule opens every neighbour by design -
 * water included.
 */
internal fun loomPathfindingMalusFor(type: PathType): Float? =
    when (type) {
        PathType.WATER -> IMPASSABLE_MALUS
        PathType.DAMAGE_FIRE -> IN_HAZARD_MALUS
        PathType.DANGER_FIRE, PathType.DAMAGE_CAUTIOUS -> BESIDE_HAZARD_MALUS
        else -> null
    }

/**
 * A Loom agent's body. Renders as a villager; behaves only as Loom drives it.
 *
 * The body walks with Minecraft's own ground navigator, which the engine ticks for it,
 * and never with a vanilla goal, brain, or target of its own. Loom is the only thing
 * that ever puts a route on it.
 */
class LoomBodyEntity(
    entityType: EntityType<out Villager>,
    level: Level,
) : Villager(entityType, level, VillagerType.PLAINS) {
    /**
     * Where this body was the last time a move was recorded for it, if ever.
     *
     * Movement is now *observed* rather than caused - the navigator moves the body and
     * Loom reads where it ended up - so the record needs a baseline to compare against.
     * It is the last *reported* position rather than the last position seen, so that
     * movement too slow to clear the threshold in one tick still accumulates into a
     * record instead of being lost a hundredth of a block at a time.
     */
    private var lastReported: BodyLocation? = null

    /**
     * What the engine tried to turn this body into during the tick just gone, if
     * anything.
     *
     * A conversion cannot be read back from a removal: lightning turning a villager
     * into a witch discards the original with `RemovalReason.DISCARDED`, the very
     * reason an ordinary discard uses, so the two are indistinguishable after the
     * fact. [BodyConversions] refuses the conversion outright, and this marker is the
     * failsafe for a path that refusal does not reach: a body that disappears with an
     * attempt against it just now went to conversion, not to death.
     *
     * It is deliberately short-lived - [readyForTheEngineTick] clears it on the next
     * tick that finds the body alive - because a marker that outlived its tick would
     * make a genuine death months later read as a conversion, and an agent that never
     * really died would stop being counted among the dead. Guessing wrong in that
     * direction is worse than guessing wrong in either direction once.
     */
    var convertedInto: String? = null

    init {
        // The body's lifecycle is Loom's: never save it into the world's entity
        // data, and never let vanilla despawn rules remove it.
        isPersistenceRequired = true
        chooseWhatIsWorthWalkingThrough()
    }

    /**
     * Builds the body's navigator, after narrowing how far it will ever search.
     *
     * THE ATTRIBUTE MUST BE SET HERE, AND THIS IS THE ONLY PLACE IT CAN BE.
     * `PathNavigation`'s constructor reads `FOLLOW_RANGE` once and keeps the result as
     * `PathFinder.maxVisitedNodes`, so the node budget is decided at the instant the
     * navigator is built. `Mob`'s constructor builds it, which is before any Kotlin
     * `init` block or property initializer in this class runs: setting the attribute
     * there would leave a body bounded to Loom's walk budget while searching a
     * villager's 768 nodes over a 113-block cube - and that cube is wider than the
     * chunks Loom holds, so the extra search would be spent reading unloaded chunks as
     * air. `LivingEntity`'s constructor has already built the attribute map by the time
     * this runs, which is what makes the write possible this early.
     *
     * FOR THE SAME REASON THIS MAY NOT READ ANY PROPERTY OF THIS CLASS. Kotlin has not
     * initialized them yet, so [lastReported] and [convertedInto] are still null here
     * whatever they are declared as. Only `const val`s are safe, and only because the
     * compiler inlines them.
     */
    override fun createNavigation(world: Level): PathNavigation {
        val walkBudget =
            checkNotNull(getAttribute(Attributes.FOLLOW_RANGE)) {
                "a Loom body must have a FOLLOW_RANGE attribute to bound its path searches"
            }
        walkBudget.baseValue = BODY_FOLLOW_RANGE_BLOCKS
        return super.createNavigation(world)
    }

    /**
     * Applies Loom's pricing to every kind of block a route could cross.
     *
     * Which hazards a route may cross, and at what price, is [loomPathfindingMalusFor]'s
     * to say - a pure function a unit test can read without a world. This walks every
     * type so a type the engine adds later is priced by that one function rather than by
     * whichever call somebody remembered to add here.
     */
    private fun chooseWhatIsWorthWalkingThrough() {
        for (type in PathType.entries) {
            loomPathfindingMalusFor(type)?.let { setPathfindingMalus(type, it) }
        }
    }

    /** Zero vanilla goal selectors. This is what makes the body cheap to tick. */
    override fun registerGoals() = Unit

    /**
     * Suppresses the vanilla villager brain entirely.
     *
     * `Villager.customServerAiStep` is what ticks the brain, runs professions and
     * trading, and joins raids. Overriding it without calling `super` is how the
     * body keeps a villager's *appearance* and none of its *behavior*. The
     * upstream method is deprecated in favor of its inactive-aware overload, so
     * both are overridden; neither delegates upward.
     *
     * The navigator is untouched by this. `Mob.serverAiStep` ticks it outside the hook
     * these two replace, which is exactly why a body with no goals still walks.
     */
    @Deprecated("Overrides a deprecated upstream hook; see class docs.", level = DeprecationLevel.WARNING)
    override fun customServerAiStep() = Unit

    override fun customServerAiStep(inactive: Boolean) = Unit

    /** Loom-owned bodies are never removed by distance-based vanilla despawn. */
    override fun removeWhenFarAway(distanceSquared: Double): Boolean = false

    /**
     * Loom re-spawns bodies from its own authoritative state, so a body must not
     * round-trip through the world save. A saved body would deserialize as a plain
     * villager, since the custom class is not part of the vanilla type.
     */
    override fun shouldBeSaved(): Boolean = false

    /**
     * Readies the body for the vanilla entity tick that follows Loom's, and reports any
     * conversion attempted against it since the last one.
     *
     * The activation exists because Loom has no players: Spigot's activation range only
     * ever activates entities near a *player*, so an untouched body drops to
     * `inactiveTick` after ten seconds. That matters far beyond idling - the whole
     * server-AI chain that ticks the navigator hangs off the full tick, and portal
     * transit is driven from `Entity.postTick`, which an inactive entity never reaches,
     * so a body left to go inactive would stop mid-route and stand in a lit portal
     * forever. It is renewed with enough lead to survive a tick Loom misses.
     *
     * The conversion mark is taken rather than read, so each attempt is reported exactly
     * once and no mark outlives the tick it was made in - a mark that lingered would
     * make a genuine death later read as a conversion, and an agent that really died
     * would stop being counted among the dead.
     */
    fun readyForTheEngineTick(): String? {
        activatedTick = MinecraftServer.currentTick + ACTIVATION_LEAD_TICKS
        val attempted = convertedInto
        convertedInto = null
        return attempted
    }

    /**
     * Carries out a contract [Intent] on this body, within [movement]'s bounds on [tick].
     *
     * The body, not the mind, decides what an intent means and whether it can act
     * on it - the mind only ever names one. Intents this flavor cannot perform
     * yield [IntentOutcome.Unsupported] so the gap is recorded rather than
     * silently dropped.
     */
    fun applyIntent(
        intent: Intent,
        movement: NavigationPolicy,
        tick: Long,
        seam: IntentSeam,
        onRetreat: (RetreatAttempt) -> Unit,
    ): IntentOutcome =
        when (intent) {
            is Intent.MoveTo -> {
                val (targetX, targetY, targetZ) = intent.target
                walkToward(targetX, targetY, targetZ, movement, tick)
            }

            is Intent.Idle -> {
                stopWalking()
                IntentOutcome.Accepted("standing still")
            }

            // Resting is stopping and holding this position on purpose, and that is the
            // whole of it: it restores nothing. Food is the only thing that refills a
            // reserve, so a resting agent is spending its life waiting rather than
            // buying any of it back. Kept as its own intent rather than folded into
            // Idle because sealing a hole and waiting for dawn is the play that wins a
            // night, and a deliberate wait and an unconsidered one are the two states a
            // survival run most needs to tell apart.
            is Intent.Rest -> {
                stopWalking()
                IntentOutcome.Accepted("resting")
            }

            // The one intent that names something the body is carrying. It resolves
            // through a seam of its own for the reason a flight does - a handle means
            // something only inside the request that minted it - and it is the only
            // intent that adds to a reserve.
            is Intent.Eat -> eatFrom(intent.ref, seam.slotOf, seam.reserve)

            is Intent.Say -> IntentOutcome.Unsupported("say")

            // The one intent that names something the body saw rather than a place. It is
            // resolved through the port's ref seam, which is what [threatOf] carries in:
            // the body must not be able to turn a handle into a creature by itself, or a
            // stale name would quietly act on whatever holds it now.
            //
            // It is also the one intent that outlives the tick that applied it, so the
            // attempt itself is handed out through [onRetreat] rather than only its
            // outcome: an accepted flight becomes a state the port keeps, and the
            // resolution happens here once because a ref means nothing outside its request.
            is Intent.Flee -> {
                val attempt = retreatFrom(intent.from, seam.threatOf, movement, tick)
                onRetreat(attempt)
                outcomeOf(attempt)
            }
        }

    /**
     * Where the body has got to since its last recorded move, or `null` if nowhere.
     *
     * Loom no longer moves its own bodies - the navigator does, from the engine's tick -
     * so this reads a position rather than producing one. The first call always answers
     * `null`, because the spawn record has just stated where the body is and a move
     * record repeating it would say nothing.
     */
    fun observeDisplacement(): BodyLocation? {
        val now = whereItIs()
        val before = lastReported
        if (before != null && !hasTravelled(before, now)) {
            return null
        }
        lastReported = now
        return if (before == null) null else now
    }
}

/**
 * Builds an unspawned body for [agentId], positioned at [at] and labelled with the
 * agent's id so a body is identifiable in-world.
 *
 * Placing the body into a world is the port's job; this only constructs it.
 */
internal fun newLoomBody(
    level: Level,
    agentId: AgentId,
    at: BodyLocation,
): LoomBodyEntity =
    LoomBodyEntity(EntityType.VILLAGER, level).apply {
        moveTo(at.x, at.y, at.z, 0.0f, 0.0f)
        customName = Component.literal(agentId.value)
        isCustomNameVisible = true
    }
