package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.EventTag
import dev.loom.mind.ExposureGate
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityTargetEvent.TargetReason
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

private val ALPHA = UUID.nameUUIDFromBytes("alpha".toByteArray())
private val STRANGER_UUID = UUID.nameUUIDFromBytes("someone".toByteArray())

private inline fun <reified T> proxied(crossinline answer: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> T::class.java.simpleName
            else -> answer(method.name)
        }
    } as T

private fun playerWith(uuid: UUID): Player = proxied { if (it == "getUniqueId") uuid else null }

private val SKELETON: Skeleton = proxied { if (it == "getType") EntityType.SKELETON else null }

private val NO_SOURCE: DamageSource = proxied { null }

@Suppress("DEPRECATION")
private fun hit(player: Entity): EntityDamageEvent = EntityDamageEvent(player, DamageCause.PROJECTILE, NO_SOURCE, 5.0)

private fun targeting(target: LivingEntity) =
    EntityTargetLivingEntityEvent(
        SKELETON,
        target,
        TargetReason.CLOSEST_PLAYER,
    )

/** The exposure guard and the one step that ends a run's exposure, against r34's drain deaths. */
class ExposureEndTest {
    private val exposure = ExposureGate()
    private val guard = BukkitExposureGuard(exposure) { it == ALPHA }

    @Test
    fun `damage to a managed player counts until the exposure ends and is cancelled after it`() {
        // r34: delta was shot dead at tick 12141 and gamma at 12187, after admission closed at 12063.
        val before = hit(playerWith(ALPHA))
        guard.damaged(before)
        assertFalse(before.isCancelled, "nothing changes before the end")
        exposure.end(12_063)
        val after = hit(playerWith(ALPHA))
        guard.damaged(after)
        assertTrue(after.isCancelled)
        val stranger = hit(playerWith(STRANGER_UUID))
        guard.damaged(stranger)
        assertFalse(stranger.isCancelled, "a player Loom does not manage is never touched")
    }

    @Test
    fun `no creature may take a managed player as its target once the exposure ends`() {
        val before = targeting(playerWith(ALPHA))
        guard.targeted(before)
        assertFalse(before.isCancelled)
        exposure.end(12_063)
        val after = targeting(playerWith(ALPHA))
        guard.targeted(after)
        assertTrue(after.isCancelled)
        val stranger = targeting(playerWith(STRANGER_UUID))
        guard.targeted(stranger)
        assertFalse(stranger.isCancelled)
    }

    @Test
    fun `ending the exposure stops the native sources and records each agent's end on one tick, once`() {
        val records = mutableListOf<BodyEventRecord>()
        var stops = 0
        val ending =
            ExposureEnd(
                exposure = exposure,
                eventLog = BodyEventLog { records += it },
                agents = { listOf(AgentId("tribe-zeta"), AgentId("tribe-alpha")) },
                currentTick = { 12_063L },
                stopSources = { stops += 1 },
            )
        assertTrue(ending.end())
        assertFalse(ending.end(), "the end is one step")
        assertEquals(1, stops)
        assertEquals(12_063L, exposure.endedAt)
        assertEquals(listOf("tribe-alpha", "tribe-zeta"), records.map { it.agentId })
        assertTrue(
            records.all {
                it.event == BodyEvent.EXPOSURE_ENDED && it.outcome == EventTag.EXPOSURE_ENDED && it.tick == 12_063L
            },
        )
    }
}
