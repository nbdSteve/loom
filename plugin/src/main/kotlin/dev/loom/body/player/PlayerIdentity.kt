/*
 * The stable, explicit player identity one Loom agent owns.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import java.security.MessageDigest
import java.util.UUID

private val CONTROL_AGENT_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")
private const val USERNAME_PREFIX = "loom_"
private const val USERNAME_HASH_CHARS = 10
private const val UNSIGNED_BYTE_MASK = 0xff
private const val MAX_PORT = 65_535

/**
 * Deterministically derives the account name and offline UUID for [agentId].
 *
 * The mapping is stable across process and server restarts. It is still registered
 * explicitly with the witness; no prefix or hash is ever used to infer ownership.
 */
data class PlayerIdentity(
    val agentId: AgentId,
    val username: String,
    val uuid: UUID,
)

fun playerIdentityOf(agentId: AgentId): PlayerIdentity {
    require(CONTROL_AGENT_ID.matches(agentId.value)) {
        "agentId must be 1..64 of [A-Za-z0-9._:-] for the player-body wire"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(agentId.value.toByteArray(Charsets.UTF_8))
    val suffix =
        digest
            .take((USERNAME_HASH_CHARS + 1) / 2)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
            .take(USERNAME_HASH_CHARS)
    val username = USERNAME_PREFIX + suffix
    val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))
    return PlayerIdentity(agentId, username, uuid)
}

/** The canonical loopback endpoint of one body-control process. */
data class BodyControlEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host == "127.0.0.1" || host == "::1") { "body control must use a loopback literal" }
        require(port in 1..MAX_PORT) { "body control port must be in 1..65535" }
    }

    override fun toString(): String = if (host == "::1") "[$host]:$port" else "$host:$port"
}
