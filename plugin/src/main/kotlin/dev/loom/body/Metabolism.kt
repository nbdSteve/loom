/*
 * Loom's first survival drive: an energy reserve, spent by living and refilled only
 * by eating.
 *
 * It lives in the domain rather than in a body flavor because it is a rule about
 * agents and not about entities: every flavor must spend a reserve the same way, and
 * an agent that runs out must be as dead in a headless simulation as in a world. It
 * is also why the reserve is counted in server ticks and never in wall-clock time -
 * the same tick sequence must end the same agent at the same tick however fast it is
 * replayed, and a paused server must starve nobody.
 *
 * This is deliberately one need and not a needs framework. Loom has a single drive
 * and a single knob (`lifespan`); a second drive should be added when there is a
 * second drive to add, shaped by what that one turns out to need rather than by what
 * this one guessed.
 *
 * The reserve is only reported here. Ending the agent that ran out is the body port's
 * to do, and whether an agent has ended is `Mortality`'s to say, so there is one
 * source of truth for death and this is not it.
 */
package dev.loom.body

/**
 * How long a full energy reserve lasts, in server ticks.
 *
 * Half an hour at twenty ticks a second. Long enough that a body spawned to be looked
 * at is not dying while it is being looked at, and short enough that a whole life -
 * reserve spent down, fed, spent again, exhausted - fits in one sitting at a test
 * server. It is a development-safe default in the sense that matters here: nothing
 * dies unexpectedly early, but an agent that is never driven does still starve,
 * because starving is the outcome the drive exists to produce.
 */
const val DEFAULT_ENERGY_LIFESPAN_TICKS: Long = 36_000L

/**
 * How many points of nutrition fill a whole reserve.
 *
 * Twenty, which is the vanilla food bar. Anchoring the conversion to a number the
 * engine already uses is what keeps nutrition explainable without a tuning knob of
 * Loom's own: twenty nutrition is one reserve, so bread at `FoodProperties.nutrition`
 * 5 is a quarter of one, and every food in the game is priced by the engine rather
 * than by a table here that would have to be maintained against it.
 *
 * Deliberately not `Villager.FOOD_POINTS`, which encodes villager *breeding*
 * semantics over four items and disagrees with the engine's own nutrition - bread is
 * 4 there and 5 in `FoodProperties`.
 */
const val VANILLA_FOOD_BAR_NUTRITION: Long = 20L

/**
 * How much of each agent's energy reserve is left, in server ticks.
 *
 * Ticks rather than the fraction the mind sees, so decay is exact: a fraction
 * decremented every tick would accumulate float error, and the boundary it drifts
 * across is a permadeath. The fraction is derived on demand ([energyOf]) and never
 * stored.
 *
 * An agent this has never been told about has a full reserve, so a body port has
 * nothing to call when it spawns one. Only the agents handed to [spendUpTo] are
 * charged, so an agent whose body is put away has its reserve frozen rather than
 * spent while it is out of the world.
 *
 * Not thread-safe, and driven from the server tick: like the rest of the body domain
 * it belongs to the main thread.
 *
 * @param lifespan how many ticks a full reserve lasts.
 */
class Metabolism(private val lifespan: Long = DEFAULT_ENERGY_LIFESPAN_TICKS) {
    init {
        require(lifespan > 0) { "lifespan must be a positive number of ticks, was $lifespan" }
    }

    private val remaining = mutableMapOf<AgentId, Long>()

    /**
     * The last tick [spendUpTo] has charged for, or `null` before the first one.
     *
     * Only ever moves forward, so a tick is charged at most once whatever order the
     * sweeps arrive in.
     */
    private var chargedThrough: Long? = null

    /** How many ticks of reserve [agentId] has left. */
    fun reserveOf(agentId: AgentId): Long = remaining[agentId] ?: lifespan

    /**
     * The reserve of [agentId] as the contract's `SelfState.energy` fraction.
     *
     * A fraction is what crosses to the mind, so a mind never has to know how long a
     * reserve lasts to know how much of one is left. A full reserve is exactly
     * `ENERGY_FULL` and a spent one exactly `ENERGY_EXHAUSTED`, since both ends of the
     * division are exact.
     */
    fun energyOf(agentId: AgentId): Double = reserveOf(agentId).toDouble() / lifespan

    /**
     * Whether [agentId]'s reserve is already full, so a meal would add nothing.
     *
     * Asked before an agent eats rather than after: [feed] would silently clamp, and the
     * item would be gone for nothing. A refusal keeps the food.
     */
    fun isSated(agentId: AgentId): Boolean = reserveOf(agentId) == lifespan

    /** Whether [agentId] has spent its whole reserve. Terminal: nothing refills one. */
    fun isExhausted(agentId: AgentId): Boolean = reserveOf(agentId) == 0L

    /**
     * Charges every agent in [living] for the ticks that have passed, and names the
     * ones whose reserve ran out doing so.
     *
     * Called once per server tick with the living population. An agent is named at
     * most once, on the call that empties its reserve: running out is a permadeath, so
     * the caller must be able to act on it exactly once without having to remember
     * which agents it has already ended.
     */
    fun spendUpTo(
        tick: Long,
        living: Set<AgentId>,
    ): Set<AgentId> {
        val elapsed = elapsedSince(tick)
        val ranOut = mutableSetOf<AgentId>()
        for (agent in living) {
            val before = reserveOf(agent)
            // An already-spent reserve is left alone, so no agent is ever named twice.
            if (before == 0L) continue
            val after = (before - elapsed).coerceAtLeast(0L)
            remaining[agent] = after
            if (after == 0L) ranOut.add(agent)
        }
        return ranOut
    }

    /**
     * How many ticks of reserve one point of vanilla nutrition is worth.
     *
     * Derived from the capacity rather than chosen, so the whole conversion is one
     * fact: [VANILLA_FOOD_BAR_NUTRITION] points fill a reserve of any size. Integer
     * division, rounding down, because no float may ever touch a reserve - the
     * boundary it would drift across is a permadeath.
     *
     * At the shipped 36 000-tick capacity that is 1 800 ticks a point, so bread at
     * nutrition 5 restores 9 000 ticks: exactly a quarter of a life.
     */
    val ticksPerNutrition: Long = lifespan / VANILLA_FOOD_BAR_NUTRITION

    /** What [nutrition] points of vanilla nutrition are worth, in ticks of reserve. */
    fun ticksForNutrition(nutrition: Int): Long = nutrition.toLong() * ticksPerNutrition

    /**
     * Adds [ticks] of reserve to [agentId], and reports whether it could be.
     *
     * The only way a reserve goes back up. Food is the whole of it: resting holds a
     * position and restores nothing, so an agent that never eats starves however
     * carefully it waits.
     *
     * Never past the capacity, so a reserve cannot be banked beyond one life and the
     * fraction the mind reads stays a fraction. False only for a reserve that has
     * already run out: exhaustion is terminal, and an agent that could eat its way
     * back out of it would not be permadead. Both are structural rather than checks a
     * caller is trusted to make first.
     */
    fun feed(
        agentId: AgentId,
        ticks: Long,
    ): Boolean {
        require(ticks >= 0) { "ticks fed must not be negative, was $ticks" }
        if (isExhausted(agentId)) return false
        val before = reserveOf(agentId)
        // Compared against the headroom rather than summed and then clamped, so an
        // absurd number of ticks cannot wrap a Long into a reserve smaller than the
        // one the agent had.
        remaining[agentId] = if (ticks >= lifespan - before) lifespan else before + ticks
        return true
    }

    /**
     * How many ticks to charge for a sweep at [tick].
     *
     * The first sweep charges one tick rather than every tick the server has been up
     * for: a reserve is spent by living, and an agent is not owed the uptime that
     * passed before it existed. A tick that has not advanced charges nothing, so two
     * sweeps in one tick cannot cost two ticks of life, and it does not wind the mark
     * back either - a tick already paid for must not become payable again.
     */
    private fun elapsedSince(tick: Long): Long {
        val previous = chargedThrough
        chargedThrough = if (previous == null) tick else maxOf(previous, tick)
        return if (previous == null) 1L else (tick - previous).coerceAtLeast(0L)
    }
}
