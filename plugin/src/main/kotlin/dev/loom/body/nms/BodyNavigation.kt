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

import dev.loom.body.BodyLocation
import dev.loom.body.IntentOutcome
import dev.loom.body.NavigationPolicy
import dev.loom.body.PATH_REQUEST_ACCURACY_BLOCKS
import dev.loom.body.WALK_SPEED_MODIFIER
import dev.loom.body.Walker
import dev.loom.body.blocksApart
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.level.pathfinder.Path

/**
 * The body's own navigator.
 *
 * Through the accessor rather than the field, because the engine's accessor answers
 * with the navigator of whatever a mob is riding. A Loom body rides nothing, so today
 * the two are the same thing and the accessor is simply the one that stays right.
 */
private val LoomBodyEntity.navigator: PathNavigation get() = getNavigation()

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
    return movement.route(BodyWalker(this), here, target, tick)
}

/**
 * The body as the movement policy sees it: two navigator calls and no world.
 *
 * The whole of the adapter's half of routing. Which of the two a request earns - and what
 * a refusal does to a route already running - is [NavigationPolicy.route]'s to decide.
 */
private class BodyWalker(
    private val body: LoomBodyEntity,
) : Walker {
    override fun followRouteTo(target: BodyLocation): IntentOutcome = body.followRouteTo(target)

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
private fun LoomBodyEntity.followRouteTo(target: BodyLocation): IntentOutcome {
    val named = namedBlock(target)
    // Accuracy 0 routes to the named block itself; this overload also fixes the region
    // margin the hold radius is derived from. See Navigation.kt.
    val block = BlockPos(target.x.toInt(), target.y.toInt(), target.z.toInt())
    val path = navigator.createPath(block, PATH_REQUEST_ACCURACY_BLOCKS)
    return when {
        path == null -> IntentOutcome.Rejected("the engine offered no route to $named")
        !navigator.moveTo(path, WALK_SPEED_MODIFIER) ->
            IntentOutcome.Rejected("the navigator would not follow the route to $named")
        else -> IntentOutcome.Accepted("walking to $named ${describeRoute(path, target)}")
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
 */
private fun describeRoute(
    path: Path,
    target: BodyLocation,
): String {
    val verdict = if (path.canReach()) "reaching what it searched for" else "getting as close as it can"
    val end = path.endNode
    val ending =
        if (end == null) {
            "ending nowhere"
        } else {
            val stop = BodyLocation(target.world, end.x.toDouble(), end.y.toDouble(), end.z.toDouble())
            "ending ${coordinate(blocksApart(stop, target))} blocks from it"
        }
    return "by ${path.nodeCount} node(s), $verdict, $ending"
}

/**
 * The target as the mind named it: three whole blocks, without the two decimal places a
 * measured position carries. A named block is not a measurement.
 */
private fun namedBlock(target: BodyLocation): String = "${target.x.toLong()},${target.y.toLong()},${target.z.toLong()}"

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
