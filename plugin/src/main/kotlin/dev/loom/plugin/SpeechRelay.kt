package dev.loom.plugin

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyPort
import dev.loom.body.player.SpeechClaims
import dev.loom.bodycontrol.SayOutcome
import dev.loom.contract.Trigger
import dev.loom.mind.EmbodiedCognition
import dev.loom.mind.MainThread
import dev.loom.witness.Heard
import dev.loom.witness.SpeechClaimed
import dev.loom.witness.SpeechWitness
import org.bukkit.plugin.Plugin

/** Endings after which an agent's pending speech has no verdict left to reach. */
private val SPEECH_ENDINGS =
    setOf(BodyEvent.BODY_DISCONNECTED, BodyEvent.DESPAWNED, BodyEvent.DIED, BodyEvent.WITNESS_REVIVED)

/**
 * The body-to-witness speech bridge. It lives here because `dev.loom.body` may not
 * depend on `dev.loom.witness`. Only a `sent` report is a claim: a refused or
 * rate-limited line put nothing on the server.
 */
internal fun sentSpeechOf(
    witness: SpeechWitness,
    main: MainThread,
    currentTick: () -> Long,
): SpeechClaims =
    SpeechClaims { claim ->
        if (claim.outcome == SayOutcome.SENT) {
            main.execute {
                witness.claimed(
                    SpeechClaimed(
                        AgentId(claim.agentId),
                        claim.requestId,
                        claim.controlRequestId,
                        claim.text,
                        currentTick(),
                    ),
                )
            }
        }
    }

/** One `Trigger.Spoken` per listener per corroborated line; nothing here replies. */
internal fun hearerOf(
    port: BodyPort,
    mind: EmbodiedCognition,
): (Heard) -> Unit =
    { heard ->
        mind.submitWhenPerceived(heard.listener, port.perceive(heard.listener, Trigger.Spoken(heard.from, heard.text)))
    }

/** Drops an agent's pending speech, on the main thread, once the record log reports its ending. */
internal class SpeechEndings(
    private val plugin: Plugin,
) {
    @Volatile
    var witness: SpeechWitness? = null

    fun watching(log: BodyEventLog): BodyEventLog =
        BodyEventLog { record ->
            log.append(record)
            if (record.event in SPEECH_ENDINGS) {
                val agent = AgentId(record.agentId)
                if (plugin.server.isPrimaryThread) {
                    witness?.forget(agent)
                } else if (plugin.isEnabled) {
                    plugin.server.scheduler.runTask(plugin, Runnable { witness?.forget(agent) })
                }
            }
        }
}
