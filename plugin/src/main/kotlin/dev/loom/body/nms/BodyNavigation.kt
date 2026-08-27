/*
 * The engine half of walking: a handful of navigator calls, and nothing else.
 *
 * Everything Loom *decides* about movement - how far a body may be sent, how many
 * searches a tick may spend, what a refusal says - is `dev.loom.body.Navigation` in
 * the engine-free domain, where a unit test pins it. What is left here is the contact
 * with `PathNavigation`, which needs a world and so can only be proven on a real
 * server. Keeping the split at that line is what makes the in-world run a check on a
 * few API calls rather than on Loom's policy.
 *
 * The navigator Loom drives is the one the engine already ticks. `Mob.serverAiStep`
 * calls `navigation.tick()` unconditionally, outside the every-other-tick branch that
 * gates the goal and target selectors, so a body with zero goals still follows a route
 * Loom put on it. That is the whole trick: Minecraft finds and walks the route, Loom
 * alone says where to.
 *
 * Nothing here moves a body. There is no `setPos` and no `deltaMovement` write left in
 * Loom, and there must not be one again: the navigator's `MoveControl` owns the body's
 * position while a route is running, and a second writer would fight it - the body
 * would stutter, or slide through the route without ever following it.
 */
package dev.loom.body.nms

import dev.loom.body.BodyBlock
import dev.loom.body.BodyLocation
import dev.loom.body.FLEE_SPEED_MODIFIER
import dev.loom.body.IntentOutcome
import dev.loom.body.NavigationPolicy
import dev.loom.body.PATH_REQUEST_ACCURACY_BLOCKS
import dev.loom.body.PathRequest
import dev.loom.body.RETREAT_SEARCH_ATTEMPTS
import dev.loom.body.RETREAT_SEARCH_HORIZONTAL_BLOCKS
import dev.loom.body.RETREAT_SEARCH_VERTICAL_BLOCKS
import dev.loom.body.RefResolution
import dev.loom.body.RetreatAttempt
import dev.loom.body.RetreatChoice
import dev.loom.body.RetreatRefusal
import dev.loom.body.WALK_SPEED_MODIFIER
import dev.loom.body.Walker
import dev.loom.body.blockAt
import dev.loom.body.blocksApart
import dev.loom.body.chooseRetreat
import dev.loom.body.refusalFor
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.world.level.pathfinder.Path
import java.util.UUID

/**
 * The body's own navigator.
 *
 * Through the accessor rather than the field, because the engine's accessor answers
 * with the navigator of whatever a mob is riding. A Loom body rides nothing, so today
 * the two are the same thing and the accessor is simply the one that stays right.
 */
internal val LoomBodyEntity.navigator: PathNavigation get() = getNavigation()

/**
 * Routes [this] body toward the whole-block target ([x], [y], [z]) if [movement] allows
 * it on [tick], and says what happened either way.
 *
 * Loom's verdict decides everything, and this only supplies the body to carry it out on.
 * Two of the three refusals are Loom's own bounds and cost no search at all: a target
 * further off than `PATH_REQUEST_REACH_BLOCKS`, the reach derived from the walk budget,
 * and a tick that has already spent its `DEFAULT_PATH_REQUESTS_PER_TICK` searches. The
 * third is the engine's, from [followRouteTo], and arrives only after a search Loom
 * granted. All three *stop* the body, because a route already running would otherwise keep
 * walking it and put `moved` records after the `rejected` one. Every part of that lives in
 * [NavigationPolicy.route], in the engine-free domain where a unit test proves it.
 *
 * Asking twice for the same target is cheap but not free: the engine hands back the route
 * the body is already walking without searching again, and the budget token is spent
 * anyway. Charging for a request Loom cannot know in advance will be answered from cache
 * keeps the bound conservative, which is the direction to err in.
 */
internal fun LoomBodyEntity.walkToward(
    x: Long,
    y: Long,
    z: Long,
    movement: NavigationPolicy,
    tick: Long,
): IntentOutcome {
    val here = whereItIs()
    // The contract's MoveTo names three coordinates and no world, so a target is always
    // a place in the body's own world.
    val target = BodyLocation(here.world, x.toDouble(), y.toDouble(), z.toDouble())
    return movement.route(BodyWalker(this, WALK_SPEED_MODIFIER), here, target, tick)
}

/**
 * Sets [this] body running from [threat] if [movement] allows it on [tick], and says what
 * happened either way.
 *
 * Engine calls and no policy. `LandRandomPos.getPosAway` is the vanilla retreat search - the
 * same one `AvoidEntityGoal` uses - and it answers with somewhere the body can *stand*, near
 * itself and in a cone away from [threat], or with nothing at all. Which of its answers is
 * worth walking to is [chooseRetreat]'s, in the engine-free domain; whatever that picks is
 * then routed to through [NavigationPolicy] exactly like a target the mind named, so a
 * retreat inherits the reach, the per-tick search budget, the node budget and the hazard
 * prices rather than getting a private path of its own. Loom picks the direction; the engine
 * picks the ground and the way there.
 *
 * IT IS ASKED [RETREAT_SEARCH_ATTEMPTS] TIMES, AND THAT IS NOT A SEARCH BUDGET IT SPENDS.
 * The search scores random *blocks* for standability and never asks the navigator for a
 * route, so the samples cost block probes; only the one target the choice earns is routed
 * to, so a flight still spends exactly one token of the per-tick path budget. Asking once
 * was what an in-world run died of: the single answer was 0.71 blocks away, and nothing
 * retried it before the agent's next goal review.
 *
 * The one thing that differs from an ordinary walk is the pace: a retreat is walked at
 * [FLEE_SPEED_MODIFIER] rather than [WALK_SPEED_MODIFIER], which is the whole of "running".
 *
 * Either refusal stops the body, and for the same reason [NavigationPolicy.route] does: a
 * body left walking its previous route would be walking it under a flee the log recorded as
 * refused. Which refusal it was is a fact about the ground worth keeping - see
 * [RetreatRefusal.NoRetreat] against [RetreatRefusal.NoMeaningfulRetreat].
 *
 * The retreat target is named in the accepted detail because it is the one fact about a
 * flight nothing else records: the threat is in the intent, the route is in the outcome, and
 * where the body decided to go is only knowable here.
 */
internal fun LoomBodyEntity.fleeFrom(
    threat: LivingEntity,
    movement: NavigationPolicy,
    tick: Long,
): RetreatAttempt {
    // Asked before anything is spent, because a ground navigator will not search while its
    // body is off the ground and a body being hit is off the ground: `createPath` would
    // answer a perfectly reachable retreat with no route at all. An in-world run lost a
    // whole flight that way, and one of a night's eight pivotal model calls with it. The
    // route the body was walking still stops - a flight is not carrying on with the old
    // route - but the retreat is kept and asked again once the body is back on its feet.
    if (!onGround()) {
        stopWalking()
        return RetreatAttempt.Grounding(threat.uuid)
    }
    val here = whereItIs()
    val threatAt = threat.position()
    // Each answer is the *bottom centre* of a block the search scored - `Vec3.atBottomCenterOf`,
    // so x and z carry half a block - which is where the body would stand on arriving, and so
    // is the right position to measure a step and a gain against. Turning it back into the
    // block a route is asked for is `blockAt`'s, in [followRouteTo].
    val offered =
        (1..RETREAT_SEARCH_ATTEMPTS).mapNotNull {
            LandRandomPos.getPosAway(
                this,
                RETREAT_SEARCH_HORIZONTAL_BLOCKS,
                RETREAT_SEARCH_VERTICAL_BLOCKS,
                threatAt,
            )?.let { away -> BodyLocation(here.world, away.x, away.y, away.z) }
        }
    val standing = BodyLocation(here.world, threatAt.x, threatAt.y, threatAt.z)
    return when (val choice = chooseRetreat(here, standing, offered)) {
        is RetreatChoice.Refuse -> {
            stopWalking()
            RetreatAttempt.Refused(choice.refusal)
        }
        is RetreatChoice.Take -> routeAway(threat.uuid, here, choice.target, movement, tick)
    }
}

/**
 * Routes the body from [here] to the chosen [target], away from [threat].
 *
 * The one place a flight meets [NavigationPolicy], so a retreat is bounded by the reach, the
 * per-tick search budget and the hazard prices exactly as a walk the mind named is. A
 * refusal here is [RetreatAttempt.Unrouted] rather than [RetreatAttempt.Refused] because it
 * is an answer about a *route* rather than about a *threat* - a target past the reach, an
 * engine that offered nothing - and the policy has already stopped the body on its way out.
 *
 * The one exception is a tick whose search budget was already spent, which is not an answer
 * at all: nothing was searched for, and the same target is there to be reached on the next
 * tick. That is [RetreatAttempt.PendingBudget], and it is what the verdict is read for.
 */
private fun LoomBodyEntity.routeAway(
    threat: UUID,
    here: BodyLocation,
    target: BodyLocation,
    movement: NavigationPolicy,
    tick: Long,
): RetreatAttempt =
    with(movement.routing(BodyWalker(this, FLEE_SPEED_MODIFIER), here, target, tick)) {
        when (outcome) {
            is IntentOutcome.Accepted ->
                RetreatAttempt.Routed(threat, "retreating to ${namedBlock(blockAt(target))}, ${outcome.effect}")
            // A spent search budget is the one refusal here that is not about this flight:
            // Loom grants a fixed number of searches per tick across the whole server, and
            // the target is unchanged and still reachable on the next one. Held rather than
            // ended, so an agent does not lose a flight to Loom's own bookkeeping.
            is IntentOutcome.Rejected ->
                if (request is PathRequest.OverBudget) {
                    RetreatAttempt.PendingBudget(threat)
                } else {
                    RetreatAttempt.Unrouted(outcome.reason)
                }
            // Neither can be reached: the body is in hand and the policy answers only these
            // three. Stated rather than defaulted so a fourth outcome has to be thought about.
            else -> RetreatAttempt.Unrouted("the body could not be routed away")
        }
    }

/**
 * What [this] body's retreat asks of the ground on [tick], against the threat it is already
 * running from.
 *
 * The continuation, and it obeys every rule the first hop does because it *is* the first
 * hop's code: the threat is looked for again in the body's own world, and a threat that has
 * gone is refused rather than routed. What decides whether this is called at all - the
 * cadence, the live route, the ground - is `ActiveRetreats.next`, in the engine-free domain.
 */
internal fun LoomBodyEntity.retreatAgainFrom(
    threat: UUID,
    movement: NavigationPolicy,
    tick: Long,
): RetreatAttempt {
    val found = livingThreat(threat)
    if (found == null) {
        stopWalking()
        return RetreatAttempt.Refused(RetreatRefusal.ThreatGone)
    }
    return fleeFrom(found, movement, tick)
}

/**
 * Sets [this] body running from whatever [handle] named, or says why it did not.
 *
 * The handle is resolved through [threatOf] - the port's request-scoped [dev.loom.body.EntityRefs]
 * seam, the only thing that can turn a name the mind used back into a creature - and then
 * looked for in *this body's own world*. Both halves can refuse, and the refusals mean
 * different things ([RetreatRefusal]), so the resolution's own answer is preferred and
 * `ThreatGone` is what is left when the handle was good and the creature is not there.
 *
 * A threat that is dead, despawned, or in another dimension all arrive as the same absence,
 * which is the honest answer: the lookup is scoped to one level, so a creature in another
 * world is simply not found, and a body must never be sent fleeing a position from a
 * dimension it is not in.
 *
 * Every refusal stops the body. A flee that could not be carried out must not leave the
 * previous route running, or the log would show `moved` records after the `rejected` one -
 * movement under an intent Loom refused, which is the rule `NavigationPolicy.route` exists
 * to hold for the granted path.
 */
internal fun LoomBodyEntity.retreatFrom(
    handle: String,
    threatOf: (String) -> RefResolution,
    movement: NavigationPolicy,
    tick: Long,
): RetreatAttempt {
    val resolution = threatOf(handle)
    val threat = if (resolution is RefResolution.Bound) livingThreat(resolution.subject) else null
    if (threat == null) {
        stopWalking()
        return RetreatAttempt.Refused(refusalFor(resolution) ?: RetreatRefusal.ThreatGone)
    }
    return fleeFrom(threat, movement, tick)
}

/**
 * The body as the movement policy sees it: two navigator calls and no world.
 *
 * The whole of the adapter's half of routing. Which of the two a request earns - and what
 * a refusal does to a route already running - is [NavigationPolicy.route]'s to decide.
 */
private class BodyWalker(
    private val body: LoomBodyEntity,
    private val speedModifier: Double,
) : Walker {
    override fun followRouteTo(target: BodyLocation): IntentOutcome = body.followRouteTo(target, speedModifier)

    override fun stopWalking() {
        body.stopWalking()
    }
}

/**
 * Asks the engine for a route to [target] and sets the body walking it.
 *
 * The one search, and the only place a body is ever set moving. The engine refuses by
 * offering no route - and note that it says the same thing for a body that is simply not
 * standing on anything, since a ground navigator will not search while its body is in
 * mid-air, so the record must not be read as "no route exists". Such a refusal leaves the
 * navigator untouched, which is why [NavigationPolicy.route] stops the body on any
 * rejection this returns and not only on its own.
 *
 * THE ENGINE MOVES THE TARGET BEFORE IT SEARCHES, AND SAYS NOTHING ABOUT IT.
 * `GroundPathNavigation.createPath` looks at the named block first: a block full of air
 * becomes the ground *below* it, and a solid block becomes the first non-solid block
 * *above* it. Only that adjusted block is searched for. So the mind naming a `y` a few
 * blocks off does not get a refusal, it gets a route somewhere else - which is why the
 * record measures the route's own end against the block the mind named. See [describeRoute].
 */
private fun LoomBodyEntity.followRouteTo(
    target: BodyLocation,
    speedModifier: Double,
): IntentOutcome {
    // One rounding, `blockAt`'s, for the block the search is asked for and the block the
    // record names alike. A target that reached those two through separate conversions is a
    // target the navigator and the log can disagree about.
    val named = blockAt(target)
    // Accuracy 0 routes to the named block itself; this overload also fixes the region
    // margin the hold radius is derived from. See Navigation.kt.
    val block = BlockPos(named.x.toInt(), named.y.toInt(), named.z.toInt())
    val path = navigator.createPath(block, PATH_REQUEST_ACCURACY_BLOCKS)
    val stated = namedBlock(named)
    return when {
        path == null -> IntentOutcome.Rejected("the engine offered no route to $stated")
        !navigator.moveTo(path, speedModifier) ->
            IntentOutcome.Rejected("the navigator would not follow the route to $stated")
        else -> IntentOutcome.Accepted("walking to $stated ${describeRoute(path, named)}")
    }
}

/**
 * What the search actually found, for the applied record.
 *
 * Two facts, about two different targets, and keeping them apart is the whole of this
 * function. `canReach` is the engine's verdict on the target *it* searched for, after the
 * vertical adjustment [followRouteTo] describes; the distance is Loom's own measure from
 * the end of the route to the block the *mind* named. A route can therefore be complete
 * and still end blocks away from what was asked for, and the record now says both.
 *
 * `Path.distToTarget` is deliberately not used, and reading it as the shortfall is the
 * mistake this wording exists to prevent: it is measured against the adjusted target, so a
 * body sent three blocks into the ground was recorded as reaching it with nothing to walk,
 * which an in-world run found and no server log would have contradicted.
 *
 * Both facts are worth carrying. A route that does not reach still walks the body
 * somewhere, and an agent told only "accepted" could not tell being on its way from being
 * sent at a wall it cannot get round.
 *
 * IT IS BLOCK AGAINST BLOCK, AND MEASURING THE MEASURED POSITION INSTEAD READ AS A LIE.
 * A route's end is a node, which is a block; [named] is the block Loom asked for. Measured
 * against the position a retreat was *chosen* at - the bottom centre of that same block, so
 * half a block off on x and on z - a flight that arrived exactly where it meant to reported
 * `ending 0.71 blocks from it`, which an operator is told to read as the engine having moved
 * the target vertically. There is nothing to read into a shortfall of zero, and that is the
 * point of it.
 */
private fun describeRoute(
    path: Path,
    named: BodyBlock,
): String {
    val verdict = if (path.canReach()) "reaching what it searched for" else "getting as close as it can"
    val end = path.endNode
    val ending =
        if (end == null) {
            "ending nowhere"
        } else {
            val stop = BodyBlock(end.x.toLong(), end.y.toLong(), end.z.toLong())
            "ending ${coordinate(blocksApart(stop, named))} blocks from it"
        }
    return "by ${path.nodeCount} node(s), $verdict, $ending"
}

/**
 * The target as the mind named it: three whole blocks, without the two decimal places a
 * measured position carries. A named block is not a measurement.
 */
private fun namedBlock(block: BodyBlock): String = "${block.x},${block.y},${block.z}"

/**
 * Stops [this] body where it stands, dropping any route it was following.
 *
 * Dropping the route is the whole of it. A body with no route has nothing setting a
 * wanted position, so `MoveControl` runs it down to a standstill by itself - which is
 * why this does not, and must not, write a velocity.
 */
internal fun LoomBodyEntity.stopWalking() {
    navigator.stop()
}

/**
 * What the body's route is doing, for the movement record.
 *
 * Four states, because displacement is something Loom observes now rather than something
 * it causes, so a record that only said *where* could not tell a body walking node three
 * of nine from a body the engine gave up on, or from one that is merely falling.
 *
 * THE FIRST TWO CHECKS ARE TWO READINGS OF ONE CONDITION, WHICH IS WHY THEY ARE PAIRED.
 * `doStuckDetection` sets the flag and calls `stop()` in the same breath once a body covers
 * less ground over a hundred navigation ticks than its speed says it should, so a stuck
 * verdict always arrives with the route already dropped: `stuck` and `drifting` are both
 * "no route", and the flag is only what chooses between them. A live route never reads
 * stuck, because `createPath` clears the flag through `resetStuckTimeout` whenever it hands
 * back a route with a target.
 *
 * The imprecision that is left is deliberate and bounded: a body that got stuck and was
 * then told to stand still keeps reading `stuck` rather than `drifting`. Nothing clears the
 * flag while there is no route to clear it from - `stop()` leaves it alone, and the
 * hundred-tick check runs only from `followThePath`, which needs a path - so it survives
 * until the next route is granted. The first reading is the more informative of the two.
 */
internal fun LoomBodyEntity.describeJourney(): String {
    val path = navigator.path
    return when {
        path == null && navigator.isStuck -> "stuck"
        path == null -> "drifting"
        path.isDone -> "at the end of its route"
        // `nextNodeIndex` is the node the body is walking *toward*, counted from zero, so
        // it is always one short of the ordinal a reader expects - and `isDone` is that
        // index reaching the node count, so printing it raw could never say `9 of 9` at
        // all. A nine-node route now reads node 1 of 9 as it sets off and node 9 of 9 on
        // its last leg.
        else -> "node ${path.nextNodeIndex + 1} of ${path.nodeCount}"
    }
}
