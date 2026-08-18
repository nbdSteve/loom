/*
 * When each living agent next reviews its situation, as pure policy.
 *
 * An agent that only thinks when an operator types a command is not autonomous, so
 * something has to decide, repeatedly, whose turn it is to think. That decision is
 * the whole of this class: given who is alive and what time it is, it hands back
 * the agents due for a scheduled goal review. It runs cognition itself no more than
 * `WalkGait` moves a body - `AutonomousDriver` does that - which is what lets the
 * cadence and the stagger be pinned by unit tests with no server and no mind.
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
 */
package dev.loom.mind

import dev.loom.body.AgentId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long an agent goes between scheduled goal reviews, by default.
 *
 * The one knob worth turning here, and deliberately conservative: a goal review is
 * routine-tier, but it is still an inference per agent per cadence, so at a
 * twenty-agent population this is a call every six seconds and nothing more. It
 * stays watchable in a single-agent demo because an agent that has never reviewed
 * is due at once - a body acts seconds after it is spawned, then settles onto this
 * cadence.
 *
 * Raising the frequency raises the whole population's cost linearly, so this is
 * the number to change - and the number the forthcoming cognition config will
 * carry - rather than anything downstream of it.
 */
val DEFAULT_GOAL_REVIEW_CADENCE: Duration = 2.minutes

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
 * Decides which living agents are due for a scheduled goal review.
 *
 * Not thread-safe: it holds each agent's last review time in a plain map and is
 * driven from the server main thread, like everything else that reads which bodies
 * are alive.
 *
 * @param cadence how long an agent goes between reviews. See
 *   [DEFAULT_GOAL_REVIEW_CADENCE].
 * @param slicesPerCadence how many sweeps the cadence is divided into, which sets
 *   how finely the population is spread.
 */
class GoalReviewSchedule(
    val cadence: Duration = DEFAULT_GOAL_REVIEW_CADENCE,
    private val slicesPerCadence: Int = DEFAULT_SLICES_PER_CADENCE,
) {
    init {
        require(cadence.isPositive()) { "cadence must be positive, was $cadence" }
        require(slicesPerCadence >= 1) { "slicesPerCadence must be at least 1, was $slicesPerCadence" }
    }

    private val lastReview = mutableMapOf<AgentId, Duration>()

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
        compareBy<AgentId, Duration?>(nullsFirst()) { lastReview[it] }.thenBy { it.value }

    /**
     * How long a caller should wait between calls to [claim].
     *
     * Derived rather than configured: the stagger only holds if the sweep runs at
     * the rate the per-slice share was computed for. A caller sweeping slower would
     * silently stretch the cadence; one sweeping faster would hand out the whole
     * population sooner than a cadence's worth of slices.
     */
    val slice: Duration get() = cadence / slicesPerCadence

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
     */
    fun claim(
        living: Set<AgentId>,
        now: Duration,
    ): List<AgentId> {
        lastReview.keys.retainAll(living)
        val claimed =
            living
                .filter { isDue(it, now) }
                .sortedWith(longestWaitingFirst)
                .take(perSlice(living.size))
        claimed.forEach { lastReview[it] = now }
        return claimed
    }

    /** Whether [agent] has waited a whole cadence, or has never reviewed at all. */
    private fun isDue(
        agent: AgentId,
        now: Duration,
    ): Boolean {
        val last = lastReview[agent] ?: return true
        return now - last >= cadence
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
