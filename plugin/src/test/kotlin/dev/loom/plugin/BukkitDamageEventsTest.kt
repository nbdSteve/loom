package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.WitnessedPlayer
import dev.loom.mind.DamageReport
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Arrow
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Zombie
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

private val MANAGED = UUID.nameUUIDFromBytes("managed".toByteArray())
private val STRANGER = UUID.nameUUIDFromBytes("stranger".toByteArray())

private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> T::class.java.simpleName
            else -> answer(method.name)
        }
    } as T

private fun player(
    uuid: UUID,
    dead: Boolean = false,
): Player =
    stub { name ->
        when (name) {
            "getUniqueId" -> uuid
            "getName" -> "loom_alpha"
            "isDead" -> dead
            "getHealth" -> 20.0
            else -> null
        }
    }

private fun entityOf(type: EntityType): Entity =
    when (type) {
        EntityType.ZOMBIE -> stub<Zombie> { if (it == "getType") type else null }
        else -> stub<Skeleton> { if (it == "getType") type else null }
    }

private fun arrowFrom(shooter: Entity): Arrow =
    stub { name ->
        when (name) {
            "getType" -> EntityType.ARROW
            "getShooter" -> shooter
            else -> null
        }
    }

private val SOURCE: DamageSource = stub { null }

// The modifier-free constructor is deprecated, and the alternatives need modifier maps.
@Suppress("DEPRECATION")
private fun attacked(
    damager: Entity,
    damagee: Entity,
    cause: DamageCause,
    damage: Double,
): EntityDamageByEntityEvent = EntityDamageByEntityEvent(damager, damagee, cause, SOURCE, damage)

private class DamageRig {
    val reports = mutableListOf<DamageReport>()
    val events =
        BukkitDamageEvents(
            agentOf = { uuid -> AgentId("agent-alpha").takeIf { uuid == MANAGED } },
            currentTick = { 77L },
            sink = { reports += it },
        )
}

class BukkitDamageEventsTest {
    @Test
    fun `final damage to a managed player is reported under its server uuid`() {
        val rig = DamageRig()
        rig.events.damaged(attacked(entityOf(EntityType.ZOMBIE), player(MANAGED), DamageCause.ENTITY_ATTACK, 5.5))
        assertEquals(
            listOf(
                DamageReport(
                    AgentId("agent-alpha"),
                    5.5,
                    "zombie",
                    77L,
                    WitnessedPlayer(MANAGED.toString(), "loom_alpha"),
                    playerDead = false,
                    // The server's health before the hit, and that less the hit's final damage.
                    healthBefore = 20.0,
                    healthAfter = 14.5,
                ),
            ),
            rig.reports,
        )
    }

    @Test
    fun `unmanaged players, cancelled and zero damage produce nothing`() {
        val rig = DamageRig()
        rig.events.damaged(EntityDamageEvent(player(STRANGER), DamageCause.FALL, SOURCE, 4.0))
        rig.events.damaged(EntityDamageEvent(player(MANAGED), DamageCause.FALL, SOURCE, 0.0))
        val cancelled = EntityDamageEvent(player(MANAGED), DamageCause.FALL, SOURCE, 4.0)
        cancelled.isCancelled = true
        rig.events.damaged(cancelled)
        rig.events.damaged(EntityDamageEvent(entityOf(EntityType.ZOMBIE), DamageCause.FALL, SOURCE, 4.0))
        assertTrue(rig.reports.isEmpty())
    }

    @Test
    fun `a dying player is reported as dead so the driver can refuse it`() {
        val rig = DamageRig()
        rig.events.damaged(EntityDamageEvent(player(MANAGED, dead = true), DamageCause.LAVA, SOURCE, 4.0))
        assertTrue(rig.reports.single().playerDead)
        assertEquals("lava", rig.reports.single().source)
    }

    @Test
    fun `source maps to the vanilla attacker key or the cause, never free text`() {
        assertEquals("zombie", damageSourceOf(DamageCause.ENTITY_ATTACK, EntityType.ZOMBIE))
        assertEquals("fall", damageSourceOf(DamageCause.FALL, null))
        assertEquals("fire_tick", damageSourceOf(DamageCause.FIRE_TICK, null))
        assertEquals("entity_attack", damageSourceOf(DamageCause.ENTITY_ATTACK, EntityType.UNKNOWN))
        assertEquals("player", damageSourceOf(DamageCause.ENTITY_ATTACK, EntityType.PLAYER))

        val rig = DamageRig()
        val arrow = arrowFrom(entityOf(EntityType.SKELETON))
        rig.events.damaged(attacked(arrow, player(MANAGED), DamageCause.PROJECTILE, 3.0))
        assertEquals("skeleton", rig.reports.single().source)
    }

    @Test
    fun `close unregisters the listener and stops every later report`() {
        val rig = DamageRig()
        val plugin: Plugin = stub { name -> if (name == "isEnabled") true else null }
        val handlers = EntityDamageEvent.getHandlerList()
        handlers.register(RegisteredListener(rig.events, { _, _ -> }, EventPriority.MONITOR, plugin, true))
        assertTrue(handlers.registeredListeners.any { it.listener === rig.events })

        rig.events.close()

        assertFalse(handlers.registeredListeners.any { it.listener === rig.events })
        rig.events.damaged(EntityDamageEvent(player(MANAGED), DamageCause.FALL, SOURCE, 4.0))
        assertTrue(rig.reports.isEmpty())
    }
}
