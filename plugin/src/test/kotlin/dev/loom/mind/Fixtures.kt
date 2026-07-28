package dev.loom.mind

import dev.loom.contract.Decision
import dev.loom.contract.DecisionImportance
import dev.loom.contract.Intent
import dev.loom.contract.Perception
import dev.loom.contract.Provenance
import dev.loom.contract.SelfState
import dev.loom.contract.Trigger

/** A perception with everything a test does not care about already filled in. */
fun perception(
    requestId: String = "req-1",
    agentId: String = "agent-alpha",
    trigger: Trigger = Trigger.GoalReview,
    importance: DecisionImportance = DecisionImportance.ROUTINE,
): Perception =
    Perception(
        requestId = requestId,
        agentId = agentId,
        trigger = trigger,
        importance = importance,
        capturedTick = 100L,
        selfState = SelfState(health = 20.0, position = listOf(1.0, 64.0, 2.0), inventorySummary = emptyList()),
        surroundings = emptyList(),
    )

/** The decision a cooperative fake mind answers [perception] with. */
fun decisionFor(
    perception: Perception,
    intent: Intent = Intent.Idle,
): Decision =
    Decision(
        requestId = perception.requestId,
        agentId = perception.agentId,
        intent = intent,
        reasoning = "test",
        provenance =
            Provenance(
                tier = perception.importance,
                model = "test-model",
                promptVersion = "test-v1",
            ),
    )
