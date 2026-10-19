/*
 * When each living agent next reviews its situation, as pure policy.
 *
 * An agent that only thinks when an operator types a command is not autonomous, so
 * something has to decide, repeatedly, whose turn it is to think. That decision is
 * the whole of this class: given who is alive and what time it is, it hands back
 * the agents due for a scheduled goal review, and nothing more. Naming who is due is
 * not the same as asking them; `AutonomousDriver` does the asking, which is what lets
 * the cadence and the stagger be pinned by unit tests with no server and no mind.
 *
 * Two properties are load-bearing rather than incidental:
 *
 * - **The cadence is a floor, not a heartbeat.** An agent is due once the cadence
 *   has elapsed since its last review, and it may be handed out later than that if
 *   others were waiting longer. Cost per agent is therefore bounded above by the
 *   cadence; nothing here can make an agent think faster than it.
 * - **Reviews are spread across the cadence, never batched.** Each claim hands out
 *   only the population's share of one slice, so a hundred agents cost a hundred
 *   calls spread over a cadence rather than a hundred calls in one tick. Cognition
 *   is event-driven precisely so that bursts do not happen; a driver that reviewed
 *   everyone at once would reintroduce them on a timer.
 *
 * One more review is owed outside the cadence: when the step a held goal took settles, the
 * agent reviews that goal again shortly after rather than waiting out the cadence, because a
 * goal advanced at most once per two-minute review can barely be pursued over several steps
 * (r14: 18 of 19 goals needed one item at most). Each scheduled review earns the agent
 * [MAX_FOLLOW_UPS_PER_REVIEW] follow-ups to spend: any settled step may spend one of them per
 * scheduled review, and a step that leaves the agent's own drop of its goal's item lying may spend
 * every one it has earned, so an agent costs at most that many follow-ups per scheduled review
 * over a run whatever its steps do (r18 delta's third drop in one review lay unreviewed, and
 * epsilon took it).
 */
package dev.loom.mind

import dev.loom.body.AgentId

/**
 * How many authoritative server ticks an agent goes between scheduled goal reviews.
 *
 * The one knob worth turning here, and deliberately conservative: a goal review is
 * routine-tier, but it is still an inference per agent per cadence, so at a
 * twenty-agent population this is a call every six seconds and nothing more. It
 * stays watchable in a small disposable demo because an agent that has never reviewed
 * is due at once - a body acts seconds after it is spawned, then settles onto this
 * cadence.
 *
 * Raising the frequency raises the whole population's cost linearly, so this is
 * the number to change rather than anything downstream of it. Two minutes at
 * vanilla's twenty ticks per second is 2,400 ticks. Using the simulation clock
 * makes a pause or a tick sprint irrelevant to the cadence.
 */
const val DEFAULT_GOAL_REVIEW_CADENCE_TICKS: Long = 2L * 60L * 20L

/**
 * How many sweeps a cadence is divided into.
 *
 * The stagger's resolution: one slice is the shortest gap between two agents'
 * reviews, and the population is spread across this many of them. Enough that a
 * mid-sized population lands one review per slice, few enough that the sweep
 * itself is rare compared to a tick.
 */
private const val DEFAULT_SLICES_PER_CADENCE = 24

/**
 * How long after a held goal's step settles its follow-up review is due: long enough for a dug
 * block's drop to land and be picked up, which vanilla delays ten ticks.
 */
const val FOLLOW_UP_DELAY_TICKS: Long = 40

/** Follow-up reviews each scheduled review earns, spent over the run rather than within it. */
const val MAX_FOLLOW_UPS_PER_REVIEW: Int = 2

/**
 * Follow-ups any settled step may spend per scheduled review; past it, one is spent only while a
 * step leaves an own goal-item drop lying.
 */
const val ORDINARY_FOLLOW_UPS_PER_REVIEW: Int = 1

/** Follow-ups a discovery's walks and transition may claim per scheduled review, outside the allowance. */
const val MAX_DISCOVERY_FOLLOW_UPS: Int = 3

/** Earned follow-ups an ordinary step leaves unspent, for a review owed to a lying drop or a completion. */
const val OWED_RESERVE: Int = 1

/** What hears that a held goal's step settled. Main thread only. */
fun interface StepSettlement {
    /**
     * [agent]'s held goal's step settled at [tick]; [owed] when it left the agent's own drop of the
     * goal's item lying, which is owed a review to collect it before another agent does.
     */
    fun settled(
        agent: AgentId,
        tick: Long,
        owed: Boolean,
    )

    /**
     * [agent]'s discovery goal's step - a walk to the edge of sight, or the first step of the
     * resource goal it became - settled at [tick], owed a review outside the ordinary allowance.
     */
    fun discovered(
        agent: AgentId,
        tick: Long,
    ) = settled(agent, tick, owed = true)

    companion object {
        /** Nothing follows a step up. */
        val NONE: StepSettlement = StepSettlement { _, _, _ -> }
    }
}

/**
 * Decides which living agents are due for a scheduled goal review.
 *
 * Not thread-safe: it holds each agent's last review time in a plain map and is
 * driven from the server main thread, like everything else that reads which bodies
 * are alive.
 *
 * @param cadenceTicks how many authoritative server ticks an agent goes between reviews. See
 *   [DEFAULT_GOAL_REVIEW_CADENCE_TICKS].
 * @param slicesPerCadence how many sweeps the cadence is divided into, which sets
 *   how finely the population is spread.
 */
class GoalReviewSchedule(
    val cadenceTicks: Long = DEFAULT_GOAL_REVIEW_CADENCE_TICKS,
    private val slicesPerCadence: Int = DEFAULT_SLICES_PER_CADENCE,
    /**
     * How many follow-ups each scheduled review earns, at most [MAX_FOLLOW_UPS_PER_REVIEW]: a run
     * whose call ceiling must leave room for the night's danger earns fewer (r20: 50 reviews and
     * 100 follow-ups planned against a ceiling of 150 left nothing for 49 danger calls).
     */
    private val followUpsPerReview: Int = MAX_FOLLOW_UPS_PER_REVIEW,
) : StepSettlement {
    init {
        require(followUpsPerReview in 0..MAX_FOLLOW_UPS_PER_REVIEW) {
            "followUpsPerReview must be in 0..$MAX_FOLLOW_UPS_PER_REVIEW, was $followUpsPerReview"
        }
        require(cadenceTicks > 0) { "cadenceTicks must be positive, was $cadenceTicks" }
        require(slicesPerCadence >= 1) { "slicesPerCadence must be at least 1, was $slicesPerCadence" }
        require(cadenceTicks >= slicesPerCadence) {
            "cadenceTicks must leave at least one tick per slice, was $cadenceTicks/$slicesPerCadence"
        }
    }

    private val lastReview = mutableMapOf<AgentId, Long>()

    // When each owed follow-up review is due, how many each agent's latest scheduled review has
    // been followed by, and how many follow-ups its scheduled reviews have earned and it has not
    // yet spent.
    private val followUpDue = mutableMapOf<AgentId, Long>()
    private val followUps = mutableMapOf<AgentId, Int>()
    private val allowance = mutableMapOf<AgentId, Int>()

    /**
     * Longest-waiting first, so a population that outgrows one slice's share
     * rotates instead of starving whoever sorts last.
     *
     * A never-reviewed agent has no last review to compare, and sorts ahead of
     * every agent that has one: it has been waiting since it got a body. Expressed
     * as an absent value rather than as a sentinel time, because any sentinel is a
     * real reading on some clock. Ties break on the id, so the order a caller sees
     * does not depend on set iteration order.
     */
    private val longestWaitingFirst: Comparator<AgentId> =
        compareBy<AgentId, Long?>(nullsFirst()) { lastReview[it] }.thenBy { it.value }

    /**
     * How long a caller should wait between calls to [claim].
     *
     * Derived rather than configured: the stagger only holds if the sweep runs at
     * the rate the per-slice share was computed for. A caller sweeping slower would
     * silently stretch the cadence; one sweeping faster would hand out the whole
     * population sooner than a cadence's worth of slices.
     */
    val sliceTicks: Long get() = (cadenceTicks + slicesPerCadence - 1) / slicesPerCadence

    /**
     * How many agents the schedule is tracking.
     *
     * Exists for the test that pins pruning: a server runs for as long as agents
     * come and go, so a departed agent whose last review time was never forgotten
     * would grow this without bound. Nothing in production reads it.
     */
    internal val tracked: Int get() = lastReview.size

    /**
     * The agents to review now, out of [living], at monotonic time [now].
     *
     * Claiming and recording are one step on purpose: a caller that could ask who
     * was due without marking them reviewed would hand the same agent out every
     * slice. The returned agents are recorded as reviewed at [now] whether or not
     * cognition then succeeds for them, because a body that cannot be perceived is
     * a reason to move on to the next agent, not to spend every following slice
     * retrying the same one.
     *
     * Agents absent from [living] are forgotten. Loom is permadeath, so for a dead
     * agent that is final; for one whose body was merely despawned it means a fresh
     * body is due at once, which is the same treatment a newly spawned agent gets.
     *
     * A due agent [ready] rejects - one whose previous review is still waiting for the
     * mind - is neither claimed nor recorded, so the review it is owed stays due and is
     * claimed at the first slice it fits rather than refused and lost.
     */
    fun claim(
        living: Set<AgentId>,
        now: Long,
        ready: (AgentId) -> Boolean = { true },
    ): List<AgentId> {
        lastReview.keys.retainAll(living)
        followUpDue.keys.retainAll(living)
        followUps.keys.retainAll(living)
        allowance.keys.retainAll(living)
        val claimed =
            living
                .filter { isDue(it, now) && ready(it) }
                .sortedWith(longestWaitingFirst)
                .take(perSlice(living.size))
        claimed.forEach {
            lastReview[it] = now
            // A scheduled review answers whatever a follow-up was owed for, and owes one anew.
            followUpDue.remove(it)
            followUps.remove(it)
            discoveryFollowUps.remove(it)
            allowance[it] = (allowance[it] ?: 0) + followUpsPerReview
        }
        return claimed
    }

    /**
     * Owes [agent] one follow-up review [FOLLOW_UP_DELAY_TICKS] after [tick], when its held goal's
     * step settled - unless it has spent every follow-up its scheduled reviews earned, or its
     * latest scheduled review has already been followed up [ORDINARY_FOLLOW_UPS_PER_REVIEW] times
     * and the step did not leave the agent's own drop of the goal's item lying ([owed]), or it has
     * never had a scheduled review at all. r17 beta's dig at its one follow-up dropped two sticks
     * with no review owed until 2255 ticks later, and gamma took them.
     */
    override fun settled(
        agent: AgentId,
        tick: Long,
        owed: Boolean,
    ) {
        val spentHere = followUps[agent] ?: 0
        val earned = allowance[agent] ?: 0
        // An ordinary follow-up leaves the last earned one to a review that is owed (r19: the
        // allowance ran out while own goal-item drops lay unreviewed for 2000 to 2300 ticks).
        val ordinarySpent = !owed && (spentHere >= ORDINARY_FOLLOW_UPS_PER_REVIEW || earned <= OWED_RESERVE)
        if (agent !in lastReview || earned <= 0 || ordinarySpent) return
        followUpDue.putIfAbsent(agent, tick + FOLLOW_UP_DELAY_TICKS)
    }

    /**
     * Owes [agent] a follow-up [FOLLOW_UP_DELAY_TICKS] after [tick] for a discovery step, from its
     * own bounded chain of [MAX_DISCOVERY_FOLLOW_UPS] rather than the earned allowance (r24: all
     * seven first-cycle discovery walks waited about 2300 ticks for their next review).
     */
    override fun discovered(
        agent: AgentId,
        tick: Long,
    ) {
        val spent = discoveryFollowUps[agent] ?: 0
        if (agent !in lastReview || spent >= MAX_DISCOVERY_FOLLOW_UPS) return
        if (followUpDue.putIfAbsent(agent, tick + FOLLOW_UP_DELAY_TICKS) == null) {
            discoveryFollowUps[agent] = spent + 1
            // Spent from its own chain, not the allowance the claim charges.
            allowance[agent] = (allowance[agent] ?: 0) + 1
        }
    }

    private val discoveryFollowUps = mutableMapOf<AgentId, Int>()

    /**
     * The owed follow-up reviews due by [now], out of [living], in agent order. A due one [ready]
     * rejects stays owed. Claiming one counts it against the agent's latest scheduled review; it
     * does not move that review, so the cadence is unchanged.
     */
    fun claimFollowUps(
        living: Set<AgentId>,
        now: Long,
        ready: (AgentId) -> Boolean = { true },
    ): List<AgentId> {
        followUpDue.keys.retainAll(living)
        val claimed =
            followUpDue
                .filter { (agent, due) -> due <= now && ready(agent) }
                .keys
                .sortedBy { it.value }
        claimed.forEach {
            followUpDue.remove(it)
            followUps[it] = (followUps[it] ?: 0) + 1
            allowance[it] = (allowance[it] ?: 0) - 1
        }
        return claimed
    }

    /** Whether [agent] has waited a whole cadence, or has never reviewed at all. */
    private fun isDue(
        agent: AgentId,
        now: Long,
    ): Boolean {
        val last = lastReview[agent] ?: return true
        return now - last >= cadenceTicks
    }

    /**
     * How many agents one slice may review, for a population of [population].
     *
     * The population's share of a cadence, rounded up: exactly the rate that gives
     * every agent one review per cadence, and the ceiling that keeps a large
     * population from arriving as a burst. At least one, or a population smaller
     * than the slice count would never review at all.
     */
    private fun perSlice(population: Int): Int = maxOf(1, (population + slicesPerCadence - 1) / slicesPerCadence)
}
