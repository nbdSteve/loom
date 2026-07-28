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
 *  - movement is a cheap Loom-owned step applied from `WalkGait`.
 *
 * Two constraints force this exact shape, both discovered by running it: the body
 * must be spawned on a vanilla type, and it must genuinely `extend` that type's
 * class, because `CraftEntity.getEntity` casts the NMS entity to it.
 *
 * Villager is the chosen base type. It is the vanilla type whose identity best
 * fits a member of an artificial society: it is `Npc`, it is not a `Monster` (so
 * it is not attacked on sight and is not culled by `spawn-monsters=false`), and it
 * already declines to despawn when far from a player (`removeWhenFarAway` is
 * false), which is what Loom wants for a body whose lifecycle Loom owns.
 *
 * THIS FILE AND ITS PACKAGE ARE THE ONLY PLACE `net.minecraft` MAY BE TOUCHED.
 * BodyBoundaryTest fails the build if that stops being true. A Minecraft version
 * bump should touch this package and nothing else.
 */
package dev.loom.body.nms

import dev.loom.body.AgentId
import dev.loom.body.BodyLocation
import dev.loom.body.IntentOutcome
import dev.loom.body.WalkGait
import dev.loom.body.WalkStep
import dev.loom.contract.Intent
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/** Vanilla walking pace, in blocks per tick. */
private const val WALK_BLOCKS_PER_TICK = 0.2

/** Horizontal distance at which a waypoint counts as reached, in blocks. */
private const val ARRIVAL_RADIUS_BLOCKS = 0.35

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
 * A Loom agent's body. Renders as a villager; behaves only as Loom drives it.
 *
 * The entity holds its own waypoint and steps toward it on each Loom-driven tick.
 * It never consults a vanilla goal, brain, or navigator.
 */
class LoomBodyEntity(
    entityType: EntityType<out Villager>,
    level: Level,
) : Villager(entityType, level, VillagerType.PLAINS) {
    private val gait = WalkGait(WALK_BLOCKS_PER_TICK, ARRIVAL_RADIUS_BLOCKS)

    private var waypointX: Double? = null
    private var waypointZ: Double? = null

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
     * It is deliberately short-lived - [forgetConversionAttempt] clears it on the next
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
     * `inactiveTick` after ten seconds. That matters far beyond idling - portal transit
     * is driven from `Entity.postTick`, which an inactive entity never reaches, so a
     * body left to go inactive would stand in a lit portal forever and never travel. It
     * is renewed with enough lead to survive a tick Loom misses.
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

    /** Points the body at a horizontal waypoint. Vertical motion stays vanilla's. */
    fun walkTo(
        x: Double,
        z: Double,
    ) {
        waypointX = x
        waypointZ = z
    }

    /** Clears any waypoint, leaving the body standing still. */
    fun standStill() {
        waypointX = null
        waypointZ = null
    }

    /**
     * Carries out a contract [Intent] on this body.
     *
     * The body, not the mind, decides what an intent means and whether it can act
     * on it - the mind only ever names one. Intents this flavor cannot perform
     * yield [IntentOutcome.Unsupported] so the gap is recorded rather than
     * silently dropped.
     */
    fun applyIntent(intent: Intent): IntentOutcome =
        when (intent) {
            is Intent.MoveTo -> {
                val (targetX, _, targetZ) = intent.target
                walkTo(targetX.toDouble(), targetZ.toDouble())
                IntentOutcome.Accepted("walking toward $targetX,$targetZ")
            }

            is Intent.Idle -> {
                standStill()
                IntentOutcome.Accepted("standing still")
            }

            is Intent.Say -> IntentOutcome.Unsupported("say")
            is Intent.Flee -> IntentOutcome.Unsupported("flee")
        }

    /**
     * Advances the body's Loom-owned movement by one tick.
     *
     * Called by the adapter from the plugin's tick, not from the vanilla entity
     * tick, so the body's cost is visible in Loom's own budget. Returns the step
     * taken, or `null` if the body stood still.
     */
    fun advanceMovement(): WalkStep? {
        val targetX = waypointX
        val targetZ = waypointZ
        val step = if (targetX == null || targetZ == null) null else gait.step(x, z, targetX, targetZ)
        if (step == null) {
            standStill()
            return null
        }
        // Set position directly rather than accumulating delta movement: this is
        // the cheap movement model, and it keeps the step exactly what WalkGait
        // computed instead of letting vanilla friction and inertia reshape it.
        // Vertical position is left to vanilla gravity in the entity tick.
        setPos(x + step.stepX, y, z + step.stepZ)
        yRot = step.yaw
        yHeadRot = step.yaw
        yBodyRot = step.yaw
        // A body that owns its own position must not also carry stale horizontal
        // inertia, or vanilla travel would add a second, unwanted displacement.
        deltaMovement = Vec3(0.0, deltaMovement.y, 0.0)
        return step
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
