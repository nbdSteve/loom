/*
 * When Loom's native cognition sources begin: the goal-review schedule, the threat scan and
 * the damage witness.
 *
 * By default they begin as the plugin enables, which is what an operator running a world
 * wants. A research run instead needs its initial conditions - clock, positions and empty
 * inventories - applied before any agent perceives anything, and the fleet starts agents one
 * pair at a time, so the first agent's first scheduled review could otherwise arrive while
 * later agents were still starting. `held` keeps every native source off until the operator
 * says `/loombody begin`, once, after the conditions are in place.
 */
package dev.loom.plugin

/** The environment setting that selects when native cognition begins. */
const val NATIVE_COGNITION_ENV: String = "LOOM_NATIVE_COGNITION"

/**
 * A one-shot latch over the native cognition sources.
 *
 * @param held whether [start] waits for [begin] rather than running from [enable].
 * @param start starts every native source. Runs exactly once, on the server main thread.
 */
class NativeCognitionStart(
    private val held: Boolean,
    private val start: () -> Unit,
) {
    private var started = false

    /** Starts the native sources now unless they are held for [begin]. */
    fun enable() {
        if (!held) begin()
    }

    /** Starts the native sources, answering whether this call was the one that did. */
    fun begin(): Boolean {
        if (started) return false
        started = true
        start()
        return true
    }

    companion object {
        /** A latch held or not by [NATIVE_COGNITION_ENV] in [environment]; see [heldBy]. */
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            start: () -> Unit,
        ): NativeCognitionStart = NativeCognitionStart(heldBy(environment[NATIVE_COGNITION_ENV]), start)

        /**
         * Whether [value] holds native cognition until `/loombody begin`.
         *
         * Unset is `immediate`. Anything else that is not one of the two words stops the
         * plugin, because a misspelt `held` that silently started at once would let a
         * research run's first perceptions predate its initial conditions.
         */
        fun heldBy(value: String?): Boolean =
            when (value?.trim()) {
                null, "", "immediate" -> false
                "held" -> true
                else -> throw IllegalArgumentException("$NATIVE_COGNITION_ENV must be immediate or held")
            }
    }
}
