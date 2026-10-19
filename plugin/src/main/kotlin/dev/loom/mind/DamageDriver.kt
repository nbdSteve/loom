/*
 * What turns server-witnessed damage into one pivotal `damaged` cognition event.
 *
 * The sibling of `ThreatDriver`: a coordinator above both ports, because the body may
 * never reach the mind. The Bukkit adapter resolves the managed agent by server uuid
 * and hands over a [DamageReport]; this class applies the bounds, captures the one
 * perception, and records exactly one `damageWitnessed` record per report.
 *
 * Main-thread only, like every other caller of `EmbodiedCognition`. Engine-free.
 */
package dev.loom.mind

import dev.loom.body.AgentId
import dev.loom.body.BodyEvent
import dev.loom.body.BodyEventLog
import dev.loom.body.BodyEventRecord
import dev.loom.body.BodyPort
import dev.loom.body.EventTag
import dev.loom.body.WitnessedPlayer
import dev.loom.contract.Perception
import dev.loom.contract.Trigger
import java.util.concurrent.CompletionStage
import kotlin.math.ceil

/**
 * How long one damage episode coalesces further damage, in server ticks.
 *
 * Vanilla's hurt-invulnerability window is 20 ticks, so a sustained source (a mob's
 * repeated blows, lava, fire) lands at most one fresh `damaged` per agent per second.
 * Damage at or after the boundary opens a new episode and is never hidden.
 */
const val DAMAGE_EPISODE_TICKS: Long = 20

/** Ceiling on the trigger's whole-point amount; vanilla `/kill` deals far more than any real blow. */
const val MAX_DAMAGE_AMOUNT: Long = 1024

/** Ceiling on the trigger's source token. */
const val MAX_DAMAGE_SOURCE_CHARS: Int = 32

/** Source used when the server's cause cannot be stated as a stable token. */
const val UNKNOWN_DAMAGE_SOURCE: String = "unknown"

private val SOURCE_TOKEN = Regex("[a-z0-9_]{1,$MAX_DAMAGE_SOURCE_CHARS}")

/**
 * One final, uncancelled damage event the server applied to a managed agent's player.
 *
 * @param finalDamage the server's final damage, before rounding.
 * @param source the vanilla registry token of the attacker or cause; sanitized again here.
 * @param playerDead whether the server already held the player dead when it was hit.
 */
data class DamageReport(
    val agentId: AgentId,
    val finalDamage: Double,
    val source: String,
    val tick: Long,
    val witness: WitnessedPlayer,
    val playerDead: Boolean = false,
    /** The server's identity for the creature that dealt it, the shooter of a projectile; `null` for a cause. */
    val attacker: String? = null,
)

/** Whole damage points for the trigger, or `null` when there is no positive damage. */
fun boundedDamageAmount(finalDamage: Double): Long? =
    if (!finalDamage.isFinite() || finalDamage <= 0.0) {
        null
    } else {
        ceil(finalDamage).toLong().coerceIn(1L, MAX_DAMAGE_AMOUNT)
    }

/** A stable lowercase token, or [UNKNOWN_DAMAGE_SOURCE]; never free text. */
fun sanitizedDamageSource(raw: String?): String = raw?.takeIf { SOURCE_TOKEN.matches(it) } ?: UNKNOWN_DAMAGE_SOURCE

/**
 * Converts witnessed damage into bounded cognition.
 *
 * @param isDead durable permadeath, consulted when the damage arrives and again when
 *   its perception does, so a death in between always wins.
 */
class DamageDriver(
    private val bodies: BodyPort,
    private val mind: EmbodiedCognition,
    private val mainThread: MainThread,
    private val isDead: (AgentId) -> Boolean,
    private val eventLog: BodyEventLog,
    private val currentTick: () -> Long,
) : AutoCloseable {
    private val episodeOpenedAt = mutableMapOf<AgentId, Long>()
    private var closed = false

    /** Handles one report on the main thread. Never waits for a body or a mind. */
    fun witnessed(report: DamageReport) {
        val amount = boundedDamageAmount(report.finalDamage) ?: return
        val trigger = Trigger.Damaged(amount, sanitizedDamageSource(report.source))
        val refusal =
            when {
                closed -> EventTag.DAMAGE_SHUTDOWN
                report.playerDead || isDead(report.agentId) -> EventTag.DAMAGE_AGENT_DEAD
                report.agentId !in bodies.liveBodies() -> EventTag.DAMAGE_BODY_UNAVAILABLE
                inEpisode(report) -> EventTag.DAMAGE_COALESCED
                else -> null
            }
        if (refusal != null) {
            record(report, trigger, refusal)
            return
        }
        episodeOpenedAt[report.agentId] = report.tick
        captured(report, trigger, bodies.perceive(report.agentId, trigger))
    }

    /** Stops new damage cognition. In-flight perceptions are recorded and dropped. */
    override fun close() {
        closed = true
    }

    private fun inEpisode(report: DamageReport): Boolean {
        val opened = episodeOpenedAt[report.agentId] ?: return false
        return report.tick - opened in 0 until DAMAGE_EPISODE_TICKS
    }

    private fun captured(
        report: DamageReport,
        trigger: Trigger.Damaged,
        perception: CompletionStage<Perception?>,
    ) {
        perception.whenComplete { captured, failure ->
            mainThread.execute {
                val refusal =
                    when {
                        closed -> EventTag.DAMAGE_SHUTDOWN
                        isDead(report.agentId) -> EventTag.DAMAGE_DIED_BEFORE_COGNITION
                        failure != null || captured == null -> EventTag.DAMAGE_NOT_PERCEIVED
                        else -> null
                    }
                if (refusal != null || captured == null) {
                    record(report, trigger, refusal ?: EventTag.DAMAGE_NOT_PERCEIVED, captured?.requestId)
                } else {
                    record(report, trigger, EventTag.DAMAGE_SUBMITTED, captured.requestId)
                    mind.submit(report.agentId, captured.classified(), report.attacker)
                }
            }
        }
    }

    private fun record(
        report: DamageReport,
        trigger: Trigger.Damaged,
        outcome: String,
        requestId: String? = null,
    ) {
        eventLog.append(
            BodyEventRecord(
                tick = currentTick(),
                agentId = report.agentId.value,
                event = BodyEvent.DAMAGE_WITNESSED,
                detail = "${trigger.amount} damage from ${trigger.source} at tick ${report.tick}",
                requestId = requestId,
                trigger = EventTag.DAMAGED,
                outcome = outcome,
                witness = report.witness,
            ),
        )
    }
}
