/*
 * The seam where "something tried to turn this agent into a witch" is caught.
 *
 * A conversion cannot be recognised after the fact. Lightning striking a villager
 * builds a witch and then calls `discard(TRANSFORMATION)`, which removes the body
 * with `RemovalReason.DISCARDED` - the same reason an ordinary discard uses - so by
 * the time a body port notices the body is gone there is nothing left to tell the
 * two apart. The attempt has to be intercepted while it is happening.
 *
 * It is intercepted at Bukkit's `EntityTransformEvent` rather than by overriding the
 * vanilla conversion methods, because the vanilla paths do not converge on one
 * method: `Mob.convertTo` handles a zombie's infection, while `Villager.thunderHit`
 * builds the witch itself and never calls `convertTo` at all. Both, however, fire
 * this one cancellable event immediately before discarding the original - and
 * cancelling it is a supported outcome every vanilla caller already handles, since
 * that is what happens whenever another plugin vetoes a transformation.
 *
 * Cancelling is the right answer and not merely the convenient one. A converted body
 * would be an entity of another class: Loom could not drive it, could not perceive
 * through it, and did not build it, so the agent would be left holding something
 * that is not a Loom body at all. Loom keeps the body it made, the agent keeps
 * living in it, and the attempt is recorded on that agent's chain as an event in its
 * life rather than the end of one.
 */
package dev.loom.body.nms

import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTransformEvent

/**
 * Refuses to let an agent's body be transformed into another creature, marking the
 * body with what was attempted.
 *
 * The mark is all this needs to do. The port sees it on its next tick and records the
 * departure against the right agent, which keeps this class free of any knowledge of
 * which agent lives in which body - and means a conversion the body then dies of is
 * reported once, by whichever outcome actually happened.
 */
internal class BodyConversions : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        val body = (event.entity as? CraftEntity)?.handle as? LoomBodyEntity ?: return
        // The transformed-to entity names what the agent was nearly turned into. It is
        // read before cancelling, since cancelling is what stops it existing.
        body.convertedInto = event.transformedEntity.type.name.lowercase()
        event.isCancelled = true
    }
}

/**
 * The conversion seam, for the plugin's wiring to register.
 *
 * Returned as a bare [Listener] because that is all the wiring needs to know: what it
 * listens for, and why it must, is this package's business.
 */
internal fun bodyConversionSeam(): Listener = BodyConversions()
