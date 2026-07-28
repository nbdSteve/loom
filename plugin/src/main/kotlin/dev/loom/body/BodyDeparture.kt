/*
 * Why a body left the world, and whether the agent left with it.
 *
 * Loom is permadeath, so "the body is gone" used to be all a body port needed to
 * know: a live body that vanished had died. That was only ever true because Loom
 * holds the world loaded around every living agent, and it stops being true the
 * moment a body can leave the world *alive* - which is exactly what travelling
 * through a portal is. The destination dimension is another world; the body that
 * arrives there is a different engine object; the agent is the same agent.
 *
 * So a departure now carries its own verdict. Only a departure that [endsTheAgent]
 * reaches `Mortality`, and `Mortality` itself is unchanged: death is still recorded
 * once and never cleared. Everything else is continuity - the agent keeps its id,
 * and therefore its memory and its relationships, and gets a body again on the far
 * side.
 *
 * The verdict lives in the domain rather than in the adapter because it is a Loom
 * rule about who is alive, not an engine detail about how entities are removed. The
 * adapter's job is only to say which departure an engine removal *was*; deciding
 * what that costs the agent is the domain's.
 */
package dev.loom.body

/**
 * The ways a body can stop being in the world.
 *
 * @property endsTheAgent whether the agent dies with the body. True for [DEATH]
 *   alone: every other departure is a body changing, not an agent ending.
 */
enum class BodyDeparture(
    val endsTheAgent: Boolean,
) {
    /**
     * The body died in-world. Terminal, and the only terminal departure.
     *
     * This is what `Mortality` records, and it is why a dead agent id is never
     * embodied again.
     */
    DEATH(endsTheAgent = true),

    /**
     * The body crossed into another dimension, and the same agent continues there.
     *
     * The engine ends a dimension transit by removing the travelling body and
     * putting a fresh one in the destination, so a body port sees a removal that
     * looks exactly like a disappearance. It is a journey: the agent is alive at the
     * far end and owes the mind no death.
     */
    DIMENSION_TRAVEL(endsTheAgent = false),

    /**
     * Something tried to turn the body into a different kind of creature.
     *
     * Lightning turning a villager into a witch, or a zombie infecting one, removes
     * the original body with the same removal reason an ordinary discard uses, so
     * this departure cannot be recognised from the removal alone - it has to be
     * reported by the conversion path itself. The agent survives it either way:
     * being struck by lightning is something that happens *to* an agent, not the end
     * of one.
     */
    CONVERSION(endsTheAgent = false),

    /**
     * Loom itself put the body away, as it does when the plugin unloads.
     *
     * Not death and not travel: the agent simply has no body for now, and the id
     * stays embodiable.
     */
    STOWED(endsTheAgent = false),
}
