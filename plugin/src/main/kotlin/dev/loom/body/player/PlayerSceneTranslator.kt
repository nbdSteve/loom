/*
 * Translation of one body-control sense report into Loom's perception types.
 */
package dev.loom.body.player

import dev.loom.body.EntityRefBinding
import dev.loom.body.InventorySlotBinding
import dev.loom.body.ThreatCandidate
import dev.loom.body.WorldClock
import dev.loom.body.blocksApart
import dev.loom.body.horizontalBearing
import dev.loom.contract.InventoryStack
import dev.loom.contract.Observation
import dev.loom.contract.SelfState
import dev.loom.contract.WorldState
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class PlayerSceneTranslator(
    private val context: PlayerBodyContext,
) {
    fun sceneOf(raw: RawSense): PlayerScene {
        context.authority.serverState.auditSense(raw.identity, raw.report)
        val subjects = subjectsOf(raw)
        val creatureBindings = context.refs.creatures.mint(subjects)
        val slotBindings = context.refs.slots.mint(raw.report.inventory.map { it.slot })
        val surroundings = surroundingsOf(raw, creatureBindings)
        return PlayerScene(
            raw = raw,
            creatureBindings = creatureBindings,
            slotBindings = slotBindings,
            entityIndex = subjects.mapIndexed { index, subject -> subject to index }.toMap(),
            surroundings = surroundings,
            candidates = candidatesOf(subjects, surroundings),
            selfState = selfStateOf(raw, slotBindings),
        )
    }

    private fun subjectsOf(raw: RawSense): List<UUID> {
        val corroborated =
            context.authority.serverState.subjectsOf(
                raw.identity,
                raw.report.entities,
            )
        val usedSubjects = mutableSetOf<UUID>()
        return raw.report.entities.indices.map { index ->
            corroborated
                .getOrNull(index)
                ?.takeIf(usedSubjects::add)
                ?: UUID.nameUUIDFromBytes(
                    "${raw.report.requestId}:$index".toByteArray(StandardCharsets.UTF_8),
                ).also(usedSubjects::add)
        }
    }

    private fun surroundingsOf(
        raw: RawSense,
        bindings: List<EntityRefBinding>,
    ): List<Observation> =
        raw.report.entities.mapIndexed { index, entity ->
            val deltaX = entity.position[0] - raw.report.position[0]
            val deltaZ = entity.position[2] - raw.report.position[2]
            Observation(
                what = entityDescriptionId(entity.what),
                distance = blocksApart(raw.report.position, entity.position),
                direction = horizontalBearing(deltaX, deltaZ),
                verticalOffset = entity.position[1] - raw.report.position[1],
                visible = entity.visible,
                ref = bindings[index].handle,
            )
        }

    private fun candidatesOf(
        subjects: List<UUID>,
        surroundings: List<Observation>,
    ): List<ThreatCandidate> =
        surroundings.mapIndexed { index, observation ->
            ThreatCandidate(
                subject = subjects[index],
                what = observation.what,
                ref = requireNotNull(observation.ref),
                distance = observation.distance,
                direction = observation.direction,
                visible = observation.visible,
            )
        }

    private fun selfStateOf(
        raw: RawSense,
        bindings: List<InventorySlotBinding>,
    ): SelfState =
        SelfState(
            health = raw.report.vitals.health,
            food = raw.report.vitals.food,
            saturation = raw.report.vitals.saturation,
            position = raw.report.position,
            inventorySummary =
                raw.report.inventory.mapIndexed { index, stack ->
                    InventoryStack(
                        ref = bindings[index].handle,
                        what = itemDescriptionId(stack.what),
                        count = stack.count.toLong(),
                    )
                },
            world =
                WorldState(
                    dayPhase = WorldClock.phaseOf(raw.report.time.timeOfDay.toLong()),
                    isDay = raw.report.time.isDay,
                    ticksUntilNightfall =
                        WorldClock.ticksUntilNightfall(raw.report.time.timeOfDay.toLong()),
                ),
        )
}

private fun entityDescriptionId(resource: String): String {
    val (namespace, path) = splitResource(resource)
    return "entity.$namespace.${path.replace('/', '.')}"
}

private fun itemDescriptionId(resource: String): String {
    val (namespace, path) = splitResource(resource)
    return "item.$namespace.${path.replace('/', '.')}"
}

private fun splitResource(resource: String): Pair<String, String> {
    val split = resource.split(':', limit = 2)
    return if (split.size == 2) split[0] to split[1] else "minecraft" to split[0]
}
