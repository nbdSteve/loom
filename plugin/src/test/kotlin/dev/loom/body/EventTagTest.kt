/*
 * The log's countable names, checked against the contract rather than against a list.
 *
 * A tag is only worth having if one question asked of a run's log and of its perceptions
 * means the same thing. So these tests do not restate the strings: they serialize the
 * contract variant and read the discriminator the wire actually carries, which is the
 * only value a query of the perception stream could ever have matched on.
 *
 * Every mapping is also asserted to be total, because `tagOf` is a `when` with no default
 * branch: a new variant will not compile until it is named, and this is where a reader
 * finds out that that is the design.
 */
package dev.loom.body

import dev.loom.contract.Bearing
import dev.loom.contract.Intent
import dev.loom.contract.LoomJson
import dev.loom.contract.Trigger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The internal tag `Trigger` is discriminated on. */
private const val TRIGGER_DISCRIMINATOR = "event"

/** The internal tag `Intent` is discriminated on. */
private const val INTENT_DISCRIMINATOR = "action"

/** Every trigger the contract has, one of each. */
private val EVERY_TRIGGER =
    listOf(
        Trigger.GoalReview,
        Trigger.Spoken(from = "agent-bo", text = "hello"),
        Trigger.Damaged(amount = 3L, source = "zombie"),
        Trigger.ThreatSighted(
            threat = "minecraft:zombie",
            ref = "e0",
            distance = 6.5,
            direction = Bearing.NORTH,
            visible = true,
            reachable = true,
        ),
        Trigger.BlockSighted(block = "minecraft:chest"),
    )

/** Every intent the contract has, one of each. */
private val EVERY_INTENT =
    listOf(
        Intent.Rest,
        Intent.Idle,
        Intent.MoveTo(onto = "b1"),
        Intent.Say(text = "hello"),
        Intent.Flee(from = "zombie"),
        Intent.Eat(ref = "i0"),
    )

/** Every outcome a body can answer an intent with, one of each. */
private val EVERY_OUTCOME =
    listOf(
        IntentOutcome.Accepted(effect = "walking"),
        IntentOutcome.Rejected(reason = "too far"),
        IntentOutcome.Unsupported(action = "flee"),
        IntentOutcome.NoSuchBody,
    )

class EventTagTest {
    @Test
    @DisplayName("a trigger's tag is the discriminator the contract puts on the wire")
    fun triggerTagsMatchTheWire() {
        for (trigger in EVERY_TRIGGER) {
            assertEquals(discriminatorOf(LoomJson.encodeToString(trigger), TRIGGER_DISCRIMINATOR), tagOf(trigger))
        }
    }

    @Test
    @DisplayName("an intent's tag is the discriminator the contract puts on the wire")
    fun intentTagsMatchTheWire() {
        for (intent in EVERY_INTENT) {
            assertEquals(discriminatorOf(LoomJson.encodeToString(intent), INTENT_DISCRIMINATOR), tagOf(intent))
        }
    }

    @Test
    @DisplayName("every trigger, intent and outcome has a tag of its own")
    fun theTagsAreTotalAndDistinct() {
        // Two variants sharing a tag would silently merge two different questions about
        // a run into one, which no amount of reading the log back would recover.
        assertEquals(EVERY_TRIGGER.size, EVERY_TRIGGER.map(::tagOf).distinct().size)
        assertEquals(EVERY_INTENT.size, EVERY_INTENT.map(::tagOf).distinct().size)
        assertEquals(EVERY_OUTCOME.size, EVERY_OUTCOME.map(::tagOf).distinct().size)
    }

    @Test
    @DisplayName("an outcome's tag says what the body did, and only that")
    fun outcomeTagsAreStable() {
        // The outcomes have no contract wire form to check against - the body's answer
        // never leaves this runtime - so these are pinned outright. They are the field a
        // run counts refusals by, and rewording one would silently zero that count.
        assertEquals(
            listOf("accepted", "rejected", "unsupported", "noSuchBody"),
            EVERY_OUTCOME.map(::tagOf),
        )
    }

    @Test
    @DisplayName("player lifecycle and control tags are stable and distinct")
    fun theClassificationTagsAreStable() {
        // These have no contract wire form and are read back from durable evidence.
        val classifications =
            listOf(
                EventTag.HAS_DIED,
                EventTag.ALREADY_EMBODIED,
                EventTag.BODY_START_FAILED,
                EventTag.BODY_START_TIMED_OUT,
                EventTag.CONTROL_UNAVAILABLE,
                EventTag.CONTROL_NOT_READY,
            )

        assertEquals(
            listOf(
                "hasDied",
                "alreadyEmbodied",
                "bodyStartFailed",
                "bodyStartTimedOut",
                "controlUnavailable",
                "controlNotReady",
            ),
            classifications,
        )
        assertEquals(
            classifications.size + EVERY_OUTCOME.size,
            (classifications + EVERY_OUTCOME.map(::tagOf)).distinct().size,
            "an outcome tag reused for a refusal would answer two questions with one count",
        )
    }

    @Test
    @DisplayName("a tag carries no payload, so two decisions of the same kind count as one kind")
    fun tagsDropThePayload() {
        assertEquals(tagOf(Intent.MoveTo("b1")), tagOf(Intent.MoveTo("b9")))
        assertEquals(tagOf(IntentOutcome.Rejected("too far")), tagOf(IntentOutcome.Rejected("no path")))
    }

    /** The value of [key] in [json], which for these unions is the variant's own name. */
    private fun discriminatorOf(
        json: String,
        key: String,
    ): String {
        val fields = Json.parseToJsonElement(json)
        return checkNotNull(fields.jsonObject[key]) { "$json carries no $key discriminator" }.jsonPrimitive.content
    }
}
