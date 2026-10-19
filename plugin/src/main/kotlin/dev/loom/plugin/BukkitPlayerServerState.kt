/*
 * Wiring of the player adapter's authority seam to Bukkit and the witness.
 */
package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.PERCEPTION_RADIUS_BLOCKS
import dev.loom.body.player.PlayerIdentity
import dev.loom.body.player.PlayerServerState
import dev.loom.body.player.SenseAuditSnapshot
import dev.loom.body.player.ServerInteractionAudit
import dev.loom.body.player.ServerInteractionClaim
import dev.loom.bodycontrol.BodyRequest
import dev.loom.bodycontrol.BodyResponse
import dev.loom.bodycontrol.SensedEntity
import dev.loom.bodycontrol.SensedItem
import dev.loom.witness.Around
import dev.loom.witness.BodyClaim
import dev.loom.witness.ClaimAudit
import dev.loom.witness.DurablePermadeath
import dev.loom.witness.InventoryAt
import dev.loom.witness.ReadingTimes
import dev.loom.witness.ServerReading
import dev.loom.witness.WITNESS_LAG_TICKS
import dev.loom.witness.bukkit.playerStandingOf
import dev.loom.witness.bukkit.serverReadingOf
import dev.loom.witness.movementClaimOf
import org.bukkit.Server
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import java.util.UUID

private const val ENTITY_MATCH_TOLERANCE_BLOCKS = 2.0
private const val MINECRAFT_NAMESPACE = "minecraft"

private data class BukkitSenseAuditSnapshot(
    val player: UUID,
    val username: String,
    val reading: ServerReading,
    /** The player's inventory at each of the last [WITNESS_LAG_TICKS] ticks before the request, oldest first. */
    val recent: List<InventoryAt> = emptyList(),
    /** The server tick and wall nanoseconds the request's reading was taken at. */
    val tick: Long = 0,
    val nanos: Long = 0,
) : SenseAuditSnapshot

/** One server entity reduced to the facts needed to correlate a client reading. */
internal data class ServerEntitySubject(
    val uuid: UUID,
    val what: String,
    val position: List<Double>,
)

/**
 * Server-authoritative readings for a client-controlled player.
 *
 * This belongs in wiring rather than in the body domain: it names both Bukkit
 * and the witness, while the adapter sees only [PlayerServerState].
 */
class BukkitPlayerServerState(
    private val server: Server,
    private val permadeath: DurablePermadeath,
    private val claimAudit: ClaimAudit,
    private val interactionEvents: BukkitInteractionEvents? = null,
) : PlayerServerState {
    private val interactionAuditor = BukkitInteractionAuditor(server, interactionEvents)

    /** Each audited player's inventory at its last few ticks, newest last; main thread only. */
    private val history = mutableMapOf<UUID, ArrayDeque<InventoryAt>>()

    override val onTick: () -> Unit = {
        history.entries.removeIf { (uuid, readings) ->
            val player = server.getPlayer(uuid) ?: return@removeIf true
            readings.addLast(InventoryAt(server.currentTick.toLong(), serverReadingOf(player).inventory))
            while (readings.size > WITNESS_LAG_TICKS) readings.removeFirst()
            false
        }
    }

    override fun connected(identity: PlayerIdentity): Boolean =
        server.getPlayer(identity.uuid)?.let { player ->
            player.isOnline &&
                !player.isDead &&
                player.name.equals(identity.username, ignoreCase = true)
        } == true

    override fun isDead(agentId: AgentId): Boolean = permadeath.isDead(agentId)

    override fun dayOrdinal(): Long = (server.worlds.firstOrNull()?.fullTime ?: 0L) / TICKS_PER_DAY

    override fun subjectsOf(
        identity: PlayerIdentity,
        entities: List<SensedEntity>,
    ): List<UUID?> {
        val player = server.getPlayer(identity.uuid) ?: return List(entities.size) { null }
        val reach = PERCEPTION_RADIUS_BLOCKS + ENTITY_MATCH_TOLERANCE_BLOCKS
        val candidates =
            player
                .getNearbyEntities(reach, reach, reach)
                .asSequence()
                .filter { it.uniqueId != identity.uuid }
                .map(::serverSubjectOf)
                .toList()
        return matchServerSubjects(entities, candidates)
    }

    override fun itemSubjectsOf(
        identity: PlayerIdentity,
        items: List<SensedItem>,
    ): List<UUID?> {
        val player = server.getPlayer(identity.uuid) ?: return List(items.size) { null }
        val reach = PERCEPTION_RADIUS_BLOCKS + ENTITY_MATCH_TOLERANCE_BLOCKS
        val candidates =
            player
                .getNearbyEntities(reach, reach, reach)
                .asSequence()
                .filterIsInstance<Item>()
                .map { item ->
                    ServerItemSubject(
                        uuid = item.uniqueId,
                        what = item.itemStack.type.key.toString(),
                        count = item.itemStack.amount,
                        position =
                            listOf(
                                item.location.x,
                                item.location.y,
                                item.location.z,
                            ),
                    )
                }.toList()
        return matchServerItems(items, candidates)
    }

    override fun captureSenseAudit(identity: PlayerIdentity): SenseAuditSnapshot? {
        val player = server.getPlayer(identity.uuid) ?: return null
        val recent = history.getOrPut(player.uniqueId) { ArrayDeque() }
        return BukkitSenseAuditSnapshot(
            player = player.uniqueId,
            username = player.name,
            reading = serverReadingOf(player),
            recent = recent.toList(),
            tick = server.currentTick.toLong(),
            nanos = System.nanoTime(),
        )
    }

    override fun auditSense(
        identity: PlayerIdentity,
        snapshot: SenseAuditSnapshot?,
        report: BodyResponse.SenseReport,
    ) {
        val captured = snapshot as? BukkitSenseAuditSnapshot ?: return
        if (captured.player != identity.uuid) return
        claimAudit.sawClaim(
            uuid = identity.uuid,
            username = captured.username,
            claim = BodyClaim(report.vitals, report.inventory),
            reading = captured.reading,
            // Inventory agrees with the reading from before the request or this one, taken when
            // the answer is audited; vitals are compared with the span between the two.
            around =
                Around(
                    server.getPlayer(identity.uuid)?.let(::serverReadingOf),
                    captured.recent,
                    ReadingTimes(
                        captured.tick,
                        server.currentTick.toLong(),
                        (System.nanoTime() - captured.nanos) / NANOS_PER_MILLI,
                    ),
                ),
        )
    }

    override fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.MoveTo,
        report: BodyResponse.MoveReport,
    ) {
        claimAudit.sawMovement(
            uuid = identity.uuid,
            username = server.getPlayer(identity.uuid)?.name ?: identity.username,
            claim = movementClaimOf(request, report),
            standing = playerStandingOf(server.getPlayer(identity.uuid)),
        )
    }

    override fun auditInteraction(
        identity: PlayerIdentity,
        claim: ServerInteractionClaim,
    ): ServerInteractionAudit = interactionAuditor.audit(identity, claim)

    override fun auditMovement(
        identity: PlayerIdentity,
        request: BodyRequest.Flee,
        report: BodyResponse.FleeReport,
    ) {
        claimAudit.sawMovement(
            uuid = identity.uuid,
            username = server.getPlayer(identity.uuid)?.name ?: identity.username,
            claim = movementClaimOf(request, report),
            standing = playerStandingOf(server.getPlayer(identity.uuid)),
        )
    }

    private companion object {
        const val TICKS_PER_DAY = 24_000L
    }
}

internal data class ServerItemSubject(
    val uuid: UUID,
    val what: String,
    val count: Int,
    val position: List<Double>,
)

/**
 * Greedily gives each sensed entity the nearest same-kind server identity.
 *
 * A sense is a loopback round trip but not an atomic server snapshot, so exact
 * coordinates would reject every moving creature. The two-block tolerance is wider
 * than one ordinary mob can travel during that round trip while still narrow enough
 * that an unmatched or ambiguous report fails open as request-local evidence instead
 * of being attached to a distant creature. A candidate is consumed once, so two
 * observations can never acquire one UUID.
 */
internal fun matchServerSubjects(
    sensed: List<SensedEntity>,
    candidates: List<ServerEntitySubject>,
): List<UUID?> {
    val remaining = candidates.toMutableList()
    val toleranceSquared = ENTITY_MATCH_TOLERANCE_BLOCKS * ENTITY_MATCH_TOLERANCE_BLOCKS
    return sensed.map { observation ->
        val matching =
            remaining
                .asSequence()
                .filter { canonicalResource(it.what) == canonicalResource(observation.what) }
                .map { candidate -> candidate to squaredDistance(candidate.position, observation.position) }
                .filter { (_, distance) -> distance <= toleranceSquared }
                .minWithOrNull(
                    compareBy<Pair<ServerEntitySubject, Double>> { it.second }
                        .thenBy { it.first.uuid.toString() },
                )?.first
        matching?.also(remaining::remove)?.uuid
    }
}

internal fun matchServerItems(
    sensed: List<SensedItem>,
    candidates: List<ServerItemSubject>,
): List<UUID?> {
    val remaining = candidates.toMutableList()
    val toleranceSquared = ENTITY_MATCH_TOLERANCE_BLOCKS * ENTITY_MATCH_TOLERANCE_BLOCKS
    return sensed.map { observation ->
        val matching =
            remaining
                .asSequence()
                .filter {
                    canonicalResource(it.what) == canonicalResource(observation.what) &&
                        it.count == observation.count
                }.map { candidate -> candidate to squaredDistance(candidate.position, observation.position) }
                .filter { (_, distance) -> distance <= toleranceSquared }
                .minWithOrNull(
                    compareBy<Pair<ServerItemSubject, Double>> { it.second }
                        .thenBy { it.first.uuid.toString() },
                )?.first
        matching?.also(remaining::remove)?.uuid
    }
}

private fun serverSubjectOf(entity: Entity): ServerEntitySubject {
    val location = entity.location
    return ServerEntitySubject(
        uuid = entity.uniqueId,
        what = entity.type.key.toString(),
        position = listOf(location.x, location.y, location.z),
    )
}

internal fun canonicalResource(resource: String): String =
    if (':' in resource) resource else "$MINECRAFT_NAMESPACE:$resource"

private fun squaredDistance(
    left: List<Double>,
    right: List<Double>,
): Double =
    left.zip(right).sumOf { (a, b) ->
        val delta = a - b
        delta * delta
    }

private const val NANOS_PER_MILLI = 1_000_000L
