package dev.loom.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BukkitInteractionAuditorTest {
    @Test
    fun `an exact claimed server event confirms the interaction`() {
        val audit =
            interactionEventAudit(
                claimed = true,
                eventFound = true,
                exact = true,
                foundDetail = "exact vanilla effect",
                absentDetail = "absent",
            )

        assertTrue(audit.confirmed)
    }

    @Test
    fun `a mismatched event disputes either a success or a refusal`() {
        val claimed =
            interactionEventAudit(
                claimed = true,
                eventFound = true,
                exact = false,
                foundDetail = "different vanilla effect",
                absentDetail = "absent",
            )
        val refused =
            interactionEventAudit(
                claimed = false,
                eventFound = true,
                exact = false,
                foundDetail = "unexpected vanilla effect",
                absentDetail = "absent",
            )

        assertFalse(claimed.confirmed)
        assertFalse(refused.confirmed)
    }

    @Test
    fun `no server event confirms only a body refusal`() {
        val refused =
            interactionEventAudit(
                claimed = false,
                eventFound = false,
                exact = false,
                foundDetail = null,
                absentDetail = "no vanilla effect",
            )
        val claimed =
            interactionEventAudit(
                claimed = true,
                eventFound = false,
                exact = false,
                foundDetail = null,
                absentDetail = "no vanilla effect",
            )

        assertTrue(refused.confirmed)
        assertFalse(claimed.confirmed)
    }
}
