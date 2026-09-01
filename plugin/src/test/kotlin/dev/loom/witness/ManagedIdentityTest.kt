/*
 * Who Loom will say anything about, and who it will not.
 *
 * The acceptance criterion these tests are for is the one with a victim outside the run: an
 * unrelated player must never be recorded and never be denied. That is a property of the *lookup*
 * rather than of a check somewhere, so it is tested as one - including against the heuristic
 * somebody will eventually be tempted to add, which is why a name that looks exactly like a Loom
 * agent's has a test of its own saying it is a stranger.
 *
 * The refusal of a conflicting registration is tested under contention as well as in sequence,
 * because the sequential case is not the one that fails. Registrations arrive from whichever thread
 * starts a body process, and a refusal that can be raced leaves the three maps disagreeing - after
 * which the legitimate body is the one refused, for the rest of the process, with nothing recorded.
 */
package dev.loom.witness

import dev.loom.body.AgentId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How many conflicting registrations race. Well past anything real, to make the race likely. */
private const val RACING_REGISTRATIONS = 16

/** How many readers hammer one arriving registration, how hard, and over how many fresh registries. */
private const val RACING_READERS = 4
private const val READS_PER_RACING_READER = 2_000
private const val RACED_REGISTRATIONS = 24

class ManagedIdentityTest {
    private val scout = managed("agent-scout", "loomscout1")

    @Test
    fun anEmptyRegistryManagesNobody() {
        val registry = WitnessRegistry()

        assertEquals(0, registry.size, "a fresh registry must manage nobody, which is what makes the witness inert")
        assertNull(registry.byUuid(scout.uuid))
        assertNull(registry.byUsername(scout.username))
        assertNull(registry.byAgent(scout.agentId))
        assertEquals(IdentityClaim.Unmanaged, claimOf(registry, scout.uuid, scout.username))
    }

    @Test
    fun aRegisteredIdentityIsFoundByEveryNameItWasRegisteredUnder() {
        val registry = registryOf(scout)

        assertEquals(scout, registry.byUuid(scout.uuid))
        assertEquals(scout, registry.byUsername(scout.username))
        assertEquals(scout, registry.byAgent(scout.agentId))
        assertEquals(IdentityClaim.Matched(scout), claimOf(registry, scout.uuid, scout.username))
    }

    @Test
    fun aUsernameIsMatchedWithoutRegardToCase() {
        val registry = registryOf(scout)

        // Minecraft treats a username as case-insensitive at the door, so a login differing only
        // in case is the same account - and reading it as a different one would refuse Loom's own
        // body as a mismatched claim on itself.
        assertEquals(scout, registry.byUsername("LoomScout1"))
        assertEquals(IdentityClaim.Matched(scout), claimOf(registry, scout.uuid, "LOOMSCOUT1"))
    }

    @Test
    fun aNameThatLooksLikeALoomAgentIsAStrangerUntilItIsRegistered() {
        val registry = registryOf(scout)
        val impostor = "loomscout2"

        // The whole reason membership is explicit. Nothing about this name or its uuid tells
        // Loom apart from a human who picked it first, so the only safe answer is silence.
        assertNull(registry.byUsername(impostor))
        assertEquals(
            IdentityClaim.Unmanaged,
            claimOf(registry, offlineUuid(impostor), impostor),
            "a prefix is not a registration, and treating one as a registration would deny a stranger",
        )
    }

    @Test
    fun registeringTheSameIdentityAgainIsNotAConflict() {
        val registry = registryOf(scout)

        // A body process restarting and re-registering itself is ordinary, and refusing it would
        // make a restart into an outage.
        registry.register(scout)
        registry.register(managed("agent-scout", "loomscout1"))

        assertEquals(1, registry.size)
        assertEquals(scout, registry.byAgent(scout.agentId))
    }

    @Test
    fun eachOfTheThreeNamesRefusesBeingReboundToAnotherIdentity() {
        val sameUsername = ManagedIdentity(AgentId("agent-other"), scout.username, UUID.randomUUID())
        val sameUuid = ManagedIdentity(AgentId("agent-other"), "loomother1", scout.uuid)
        val sameAgent = managed("agent-scout", "loomother2")

        for (conflict in listOf(sameUsername, sameUuid, sameAgent)) {
            val registry = registryOf(scout)

            assertFailsWith<IllegalArgumentException>("$conflict must not be allowed to displace $scout") {
                registry.register(conflict)
            }
            // Nothing was rebound: a partial registration would file one agent's evidence under
            // another agent's name, which is worse than the refusal it replaced.
            assertEquals(1, registry.size)
            assertEquals(scout, registry.byUuid(scout.uuid))
            assertEquals(scout, registry.byUsername(scout.username))
            assertEquals(scout, registry.byAgent(scout.agentId))
        }
    }

    @Test
    fun aUsernameNoClientCouldConnectWithIsRefusedAtRegistration() {
        // Too short, too long, and two characters the game does not allow. A registration that can
        // never match a login is a body Loom would witness nothing about while believing otherwise.
        for (impossible in listOf("ab", "loomscoutttttttttt", "loom:scout", "loom scout")) {
            assertFailsWith<IllegalArgumentException>("'$impossible' cannot be a Minecraft username") {
                ManagedIdentity(AgentId("agent-scout"), impossible, offlineUuid(impossible))
            }
        }
    }

    @Test
    fun halfARegistrationIsAMismatchAndNamesTheAgentItClaimed() {
        val registry = registryOf(scout)

        // The right name from the wrong account, and the right account under the wrong name. Both
        // are claims Loom cannot confirm, and both must name the agent whose identity was claimed
        // so the refusal is recorded against it rather than against nobody.
        assertEquals(
            IdentityClaim.Mismatched(scout),
            claimOf(registry, UUID.randomUUID(), scout.username),
        )
        assertEquals(
            IdentityClaim.Mismatched(scout),
            claimOf(registry, scout.uuid, "somebodyelse"),
        )
    }

    @Test
    fun conflictingRegistrationsArrivingAtOnceLeaveTheNamesAgreeingAndTheWinnerUsable() {
        val registry = WitnessRegistry()
        // Every one of them wants scout's username, and each is a different agent on a different
        // account. Registrations arrive from whichever thread starts a body process, so two of these
        // can be inside `register` at the same instant - and a check followed by three separate
        // writes lets both pass the refusal and then interleave, leaving `byUuid` on one identity and
        // `byUsername` on another. `claimOf` reads that as a mismatch and refuses the *legitimate*
        // body for the rest of the process.
        val claimants =
            (0 until RACING_REGISTRATIONS).map {
                ManagedIdentity(AgentId("agent-$it"), scout.username, offlineUuid("loomclaimant$it"))
            }

        val outcomes =
            inTandem(RACING_REGISTRATIONS, name = "register") { index ->
                runCatching { registry.register(claimants[index]) }.isSuccess
            }

        assertEquals(1, outcomes.count { it }, "exactly one of a set of conflicting registrations may win")
        assertEquals(1, registry.size, "$outcomes")
        val winner = assertNotNull(registry.byUsername(scout.username))
        assertEquals(
            IdentityClaim.Matched(winner),
            claimOf(registry, winner.uuid, winner.username),
            "the registration that won must still resolve as itself rather than as a mismatch",
        )
        for (loser in claimants - winner) {
            assertNull(registry.byUuid(loser.uuid), "$loser lost and must be bound to nothing at all")
            assertNull(registry.byAgent(loser.agentId))
        }
    }

    @Test
    fun aReaderRacingOneRegistrationNeverSeesHalfOfIt() {
        val seen = mutableListOf<IdentityClaim>()

        repeat(RACED_REGISTRATIONS) {
            // A fresh registry each round, because only a registration that is actually arriving can
            // be caught half arrived: re-registering an identity already bound rewrites the same
            // values and so opens no window at all.
            val registry = WitnessRegistry()

            val answers =
                inTandem(RACING_READERS + 1, name = "claiming") { index ->
                    if (index == 0) {
                        registry.register(scout)
                        emptyList()
                    } else {
                        (0 until READS_PER_RACING_READER).map { claimOf(registry, scout.uuid, scout.username) }
                    }
                }

            seen += answers.flatten()
            // Out of the race, so this is the state and not a reading of it: the registration landed.
            assertEquals(IdentityClaim.Matched(scout), claimOf(registry, scout.uuid, scout.username))
        }

        // Every observation, not only the last: a registration that writes its three names one at a
        // time is a half-registration to anything reading between them, and `Mismatched` there
        // refuses the legitimate body - the very body whose own launcher was registering it - and
        // records a refusal about it. Either the registration is visible to a reader or it is not.
        val honest = setOf(IdentityClaim.Unmanaged, IdentityClaim.Matched(scout))
        for (claim in seen) {
            assertTrue(claim in honest, "a reader must never see half a registration: $claim")
        }
        assertTrue(
            seen.contains(IdentityClaim.Matched(scout)),
            "and the readers must have overlapped the registration at least once, or this proves nothing",
        )
    }

    @Test
    fun twoManagedIdentitiesDoNotBecomeEachOthersMismatch() {
        val other = managed("agent-digger", "loomdigger1")
        val registry = registryOf(scout, other)

        assertEquals(IdentityClaim.Matched(scout), claimOf(registry, scout.uuid, scout.username))
        assertEquals(IdentityClaim.Matched(other), claimOf(registry, other.uuid, other.username))
        // Two registered halves that belong to different registrations is still a mismatch, and it
        // is reported against the uuid's owner - the account that actually connected.
        assertEquals(IdentityClaim.Mismatched(scout), claimOf(registry, scout.uuid, other.username))
    }
}
