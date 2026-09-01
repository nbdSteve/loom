/*
 * Which players Loom is willing to say anything about.
 *
 * Loom's second body flavor is an ordinary Minecraft player driven by an external process,
 * which means the bodies Loom is responsible for and the players connected to the server are
 * two different sets that overlap. A witness that got that overlap wrong would be worse than
 * no witness at all: recording an unrelated player's death as a Loom agent's permadeath
 * poisons the research record, and refusing an unrelated player's login makes Loom a
 * liability on any server it is installed on.
 *
 * So membership is explicit and nothing else. There is no prefix convention, no name pattern,
 * no "looks like one of ours" heuristic - a player is managed when something has registered
 * the exact triple of agent id, username and uuid, and is unmanaged otherwise. A heuristic
 * would be the same code with a silent failure mode, since the one thing a name pattern
 * cannot do is tell a Loom agent called `loomscout2` from a human who chose that name first.
 *
 * The registry starts empty and stays empty until a caller registers something, which is what
 * makes this whole package inert on a server that has no player bodies yet.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How long a Minecraft username may be, and the characters it may contain.
 *
 * Checked at registration rather than trusted, because a registration that can never match a
 * real connection is a body Loom would witness nothing about while believing it was watching.
 * The bound is the game's own, so a registration that passes here can at least in principle
 * be the account that connects.
 */
private val USERNAME_SHAPE = Regex("[A-Za-z0-9_]{3,16}")

/**
 * Whether [username] is a name a Minecraft client could actually connect with.
 *
 * Stated here rather than at each caller so the shape has one owner: a second copy of it is a
 * second answer to "could this ever match a login", and the durable death state asks the same
 * question of a name read back off a file that a registration asks of a name handed to it.
 */
internal fun isConnectableUsername(username: String): Boolean = USERNAME_SHAPE.matches(username)

/**
 * [username] in the one case anything in this package compares names by.
 *
 * Always `Locale.ROOT`, never the default locale, which lowercases a dotted capital I to a
 * different code point in Turkish and would make a lookup depend on the operator's machine.
 */
internal fun usernameKeyOf(username: String): String = username.lowercase(Locale.ROOT)

/**
 * One player Loom is responsible for.
 *
 * The three fields are one fact and are always carried together. Two of them are how the
 * outside world names the body - a server log line says the username, `playerdata` is filed
 * under the uuid - and the third is how Loom names the agent inside it. Splitting them would
 * mean re-deriving one from another somewhere, which in offline mode is possible (the uuid is
 * a hash of the username) and is exactly the inference that makes a witness untrustworthy the
 * day the server changes mode.
 *
 * @property agentId the agent this player is the body of.
 * @property username the account name the player will connect with.
 * @property uuid the uuid the server will know the player by.
 */
data class ManagedIdentity(
    val agentId: AgentId,
    val username: String,
    val uuid: UUID,
) {
    init {
        require(isConnectableUsername(username)) {
            "username '$username' is not a username a Minecraft client can connect with"
        }
    }

    /** The username in the one case the registry compares by, so a lookup cannot vary by case. */
    internal val usernameKey: String get() = usernameKeyOf(username)
}

/**
 * The set of players Loom is willing to witness, and the only way to join it.
 *
 * Thread-safe by construction rather than by discipline, because its two readers are on
 * different threads and always will be: the login refusal runs on a Netty thread during async
 * pre-login, and everything else runs on the server main thread. A registration can also
 * arrive from whichever thread starts a body process. A `HashMap` here would be a data race
 * whose symptom is an unmanaged player being denied, or a managed one being missed, under
 * exactly the concurrent load a run generates.
 *
 * Registration is idempotent for an identical identity and refused for a conflicting one. That
 * asymmetry is the point: re-registering the same body after a process restart is ordinary, and
 * quietly rebinding a username or a uuid to a different agent would silently re-file one
 * agent's evidence under another agent's name.
 *
 * The refusal is only worth anything if it cannot be raced, which per-name concurrent maps do not
 * give: three reads followed by three writes lets two conflicting registrations both pass the
 * refusal and then interleave, leaving the maps pointing at different identities for one name. A
 * lookup would then read the two halves as disagreeing and refuse the *legitimate* body as
 * `Mismatched` for the rest of the process, with nothing recorded to say why. So a registration
 * takes a lock over the whole check-and-write. It costs nothing: registering a body happens once
 * per body process and never on a hot path, while every read stays lock-free.
 *
 * A lock over the writers is only half of that, and the reader half is the same bug reached from
 * the other side: one registration writing three names in some order is a half-registration to
 * anything reading between them, so an ordinary body connecting while its own launcher registered
 * it was refused as `Mismatched` and recorded as one. The three names are therefore published as a
 * single immutable [Registrations] the lock swaps, which is what makes an interleaving unobservable
 * rather than merely unlikely.
 */
class WitnessRegistry {
    private val registering = ReentrantLock()

    @Volatile
    private var bound = Registrations()

    /**
     * Adds [identity] to the managed set.
     *
     * @throws IllegalArgumentException if any of the three names is already bound to a
     *   different identity. Nothing is registered when it throws.
     */
    fun register(identity: ManagedIdentity) {
        registering.withLock {
            val held = bound
            refuseConflict(held.byUuid[identity.uuid], identity, "uuid ${identity.uuid}")
            refuseConflict(held.byUsername[identity.usernameKey], identity, "username ${identity.username}")
            refuseConflict(held.byAgent[identity.agentId], identity, "agent ${identity.agentId}")
            bound = held.with(identity)
        }
    }

    /** The identity registered under [uuid], or null when nothing is. */
    fun byUuid(uuid: UUID): ManagedIdentity? = bound.byUuid[uuid]

    /** The identity registered under [username], compared without regard to case. */
    fun byUsername(username: String): ManagedIdentity? = bound.byUsername[usernameKeyOf(username)]

    /** The identity registered for [agentId], or null when the agent has no player body. */
    fun byAgent(agentId: AgentId): ManagedIdentity? = bound.byAgent[agentId]

    /** How many identities are managed. Zero until something registers one. */
    val size: Int get() = bound.byAgent.size

    /**
     * Every binding as one value, for a reader that has to compare two of them.
     *
     * The only way `claimOf` can ask about a uuid and a username and be sure both answers describe
     * the same instant: two lookups against the registry are two reads of [bound], and a
     * registration landing between them is a half-registration to whoever asked.
     */
    internal val registrations: Registrations get() = bound

    private fun refuseConflict(
        held: ManagedIdentity?,
        wanted: ManagedIdentity,
        name: String,
    ) {
        require(held == null || held == wanted) {
            "$name is already registered as $held and cannot be rebound to $wanted"
        }
    }
}

/**
 * The three bindings of the managed set, as one immutable value.
 *
 * A snapshot rather than three concurrent maps because the maps made a *writer* atomic and left a
 * *reader* able to see between them: the three puts land in some order, so a lookup taking one of
 * them before the next arrived read the two halves as disagreeing, which is exactly the
 * [IdentityClaim.Mismatched] refusal of a legitimate body the lock exists to rule out. Replacing the
 * whole value under the lock makes a half-registration unobservable while leaving every read
 * lock-free, since a reader either sees the registration or does not.
 */
internal class Registrations(
    val byUuid: Map<UUID, ManagedIdentity> = emptyMap(),
    val byUsername: Map<String, ManagedIdentity> = emptyMap(),
    val byAgent: Map<AgentId, ManagedIdentity> = emptyMap(),
) {
    /** This set with [identity] bound to all three of its names. */
    fun with(identity: ManagedIdentity): Registrations =
        Registrations(
            byUuid = byUuid + (identity.uuid to identity),
            byUsername = byUsername + (identity.usernameKey to identity),
            byAgent = byAgent + (identity.agentId to identity),
        )
}

/**
 * Which registration a connection is claiming to be, if any.
 *
 * The three answers are deliberately distinct, and the third is why this is a type rather than
 * a nullable lookup. A connection whose uuid and username point at the same registration is
 * that body. A connection matching neither is somebody else's business entirely. A connection
 * matching one half and not the other is a claim on a managed identity that Loom cannot
 * confirm, and treating it as either of the other two answers is a mistake with a victim: read
 * as unmanaged it walks in as an unwitnessed player wearing an agent's name, and read as
 * matched it starts filing evidence about an agent under a stranger's uuid.
 */
sealed interface IdentityClaim {
    /** Neither the uuid nor the username is registered. Not Loom's business. */
    data object Unmanaged : IdentityClaim

    /** The uuid and the username agree on one registration. */
    data class Matched(
        val identity: ManagedIdentity,
    ) : IdentityClaim

    /**
     * One half of a registration matched and the other did not.
     *
     * [identity] is the registration that was matched, so the refusal can be recorded against
     * the agent whose name was claimed rather than against nobody.
     */
    data class Mismatched(
        val identity: ManagedIdentity,
    ) : IdentityClaim
}

/**
 * What a connection's [uuid] and [username] claim, according to [registry].
 *
 * Pure, so the whole of the claim rule is testable without a server: this is the function that
 * decides whether Loom interferes with a login at all, and it must be provably incapable of
 * answering anything but [IdentityClaim.Unmanaged] for a player nobody registered.
 *
 * Both halves come from one read of the registry's bindings, because a mismatch is a statement that
 * two answers *disagree* and two answers taken at different instants can disagree about nothing more
 * than a registration having arrived in between.
 */
fun claimOf(
    registry: WitnessRegistry,
    uuid: UUID,
    username: String,
): IdentityClaim {
    val bound = registry.registrations
    val byUuid = bound.byUuid[uuid]
    val byUsername = bound.byUsername[usernameKeyOf(username)]
    return when {
        byUuid == null && byUsername == null -> IdentityClaim.Unmanaged
        byUuid != null && byUuid == byUsername -> IdentityClaim.Matched(byUuid)
        // One half matched. Whichever it was, the registration it names is the agent whose
        // identity is being claimed, so the refusal is recorded against that agent.
        else -> IdentityClaim.Mismatched(byUuid ?: requireNotNull(byUsername))
    }
}
