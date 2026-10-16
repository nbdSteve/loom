package dev.loom.mind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class PerAgentCognitionBridgeTest {
    @Test
    fun `one agent cannot spend another agents outstanding model call budget`() {
        val delegated = mutableMapOf<String, CompletableFuture<dev.loom.contract.Decision>>()
        val bridge =
            PerAgentCognitionBridge(
                delegate =
                    CognitionBridge { seen ->
                        CompletableFuture<dev.loom.contract.Decision>().also {
                            delegated[seen.requestId] = it
                        }
                    },
                maxOutstandingPerAgent = 1,
            )
        val alpha = perception(requestId = "alpha-1", agentId = "alpha")
        val alphaFirst = bridge.submit(alpha)

        val alphaRefused = bridge.submit(perception(requestId = "alpha-2", agentId = "alpha"))
        val beta = perception(requestId = "beta-1", agentId = "beta")
        val betaFirst = bridge.submit(beta)

        assertTrue(alphaRefused.isCompletedExceptionally)
        val refusal = failureOf(assertThrows<ExecutionException> { alphaRefused.get(1, TimeUnit.SECONDS) })
        assertEquals(CognitionFailure.AgentBudgetExhausted(1), refusal)
        // The tag the journal files a refusal under, and the budget it states.
        assertEquals("agentBudgetExhausted", refusal.tag)
        assertEquals(1, (refusal as CognitionFailure.AgentBudgetExhausted).limit)
        assertEquals(1, bridge.outstandingFor("alpha"))
        assertEquals(1, bridge.outstandingFor("beta"))

        delegated.getValue(alpha.requestId).complete(decisionFor(alpha))
        assertEquals(alpha.requestId, alphaFirst.get(1, TimeUnit.SECONDS).requestId)
        assertEquals(0, bridge.outstandingFor("alpha"))

        val alphaAgain = perception(requestId = "alpha-3", agentId = "alpha")
        val admitted = bridge.submit(alphaAgain)
        delegated.getValue(alphaAgain.requestId).complete(decisionFor(alphaAgain))
        delegated.getValue(beta.requestId).complete(decisionFor(beta))
        assertEquals(alphaAgain.requestId, admitted.get(1, TimeUnit.SECONDS).requestId)
        assertEquals(beta.requestId, betaFirst.get(1, TimeUnit.SECONDS).requestId)
    }

    private fun failureOf(thrown: ExecutionException): CognitionFailure =
        (thrown.cause as CognitionUnavailableException).failure
}
