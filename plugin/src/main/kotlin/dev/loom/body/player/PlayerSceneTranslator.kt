/*
 * Translation of one body-control sense report into Loom's perception types.
 */
package dev.loom.body.player

import dev.loom.body.InventorySlotBinding
import dev.loom.body.PERCEPTION_MAX_OBSERVATIONS
import dev.loom.body.ThreatCandidate
import dev.loom.body.WorldClock
import dev.loom.body.WorldTargetRefBinding
import dev.loom.body.blocksApart
import dev.loom.body.horizontalBearing
import dev.loom.contract.CraftingOption
import dev.loom.contract.InventoryStack
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.SelfState
import dev.loom.contract.WorldState
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val BLOCK_CENTER_OFFSET = 0.5

internal class PlayerSceneTranslator(
    private val context: PlayerBodyContext,
) {
    fun sceneOf(raw: RawSense): PlayerScene {
        context.authority.serverState.auditSense(raw.identity, raw.auditSnapshot, raw.report)
        val serverSubjects =
            context.authority.serverState.subjectsOf(
                raw.identity,
                raw.report.entities,
            )
        val subjects = subjectsOf(raw, serverSubjects)
        val itemSubjects = context.authority.serverState.itemSubjectsOf(raw.identity, raw.report.items)
        val selected = offersOf(raw, subjects, itemSubjects).take(PERCEPTION_MAX_OBSERVATIONS)
        val entityOffers = selected.filter { it.kind == ObservationKind.ENTITY }
        val blockOffers = selected.filter { it.kind == ObservationKind.BLOCK }
        val itemOffers = selected.filter { it.kind == ObservationKind.DROPPED_ITEM }
        val creatureBindings =
            context.refs.creatures.mint(entityOffers.map { requireNotNull(it.subject) })
        val blockBindings = context.refs.blocks.mint(blockOffers.map(SceneOffer::sourceIndex))
        val droppedItemBindings =
            context.refs.droppedItems.mint(itemOffers.map(SceneOffer::sourceIndex))
        val slotBindings = context.refs.slots.mint(raw.report.inventory.map { it.slot })
        val recipeBindings = context.refs.recipes.mint(raw.report.recipes.indices.toList())
        val refs =
            refsOf(
                entityOffers to creatureBindings.map { it.handle },
                blockOffers to blockBindings.map { it.handle },
                itemOffers to droppedItemBindings.map { it.handle },
            )
        val surroundings = selected.map { observationOf(raw, it, requireNotNull(refs[it.key])) }
        val omittedByBody =
            raw.report.entitiesOmitted + raw.report.blocksOmitted + raw.report.itemsOmitted
        val omittedBySceneBound =
            raw.report.entities.size + raw.report.blocks.size + raw.report.items.size - selected.size
        return PlayerScene(
            raw = raw,
            creatureBindings = creatureBindings,
            blockBindings = blockBindings,
            droppedItemBindings = droppedItemBindings,
            slotBindings = slotBindings,
            recipeBindings = recipeBindings,
            entityIndex =
                entityOffers.associate { offer ->
                    requireNotNull(offer.subject) to offer.sourceIndex
                },
            entitySubjects =
                entityOffers.mapNotNull { offer ->
                    serverSubjects.getOrNull(offer.sourceIndex)?.let { offer.sourceIndex to it }
                }.toMap(),
            droppedItemSubjects =
                itemOffers.mapNotNull { offer ->
                    offer.subject?.let { offer.sourceIndex to it }
                }.toMap(),
            surroundings = surroundings,
            surroundingsOmitted = omittedByBody + omittedBySceneBound,
            candidates = candidatesOf(selected, surroundings),
            selfState = selfStateOf(raw, slotBindings, recipeBindings),
        )
    }

    private fun refsOf(vararg groups: Pair<List<SceneOffer>, List<String>>): Map<Pair<ObservationKind, Int>, String> =
        buildMap {
            groups.forEach { (offers, handles) ->
                offers.zip(handles).forEach { (offer, handle) ->
                    put(offer.key, handle)
                }
            }
        }

    private fun subjectsOf(
        raw: RawSense,
        corroborated: List<UUID?>,
    ): List<UUID> {
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

    private fun offersOf(
        raw: RawSense,
        subjects: List<UUID>,
        itemSubjects: List<UUID?>,
    ): List<SceneOffer> {
        val entities =
            raw.report.entities.mapIndexed { index, entity ->
                SceneOffer(
                    kind = ObservationKind.ENTITY,
                    sourceIndex = index,
                    what = entityDescriptionId(entity.what),
                    position = entity.position,
                    visible = entity.visible,
                    subject = subjects[index],
                )
            }
        val blocks =
            raw.report.blocks.mapIndexed { index, block ->
                SceneOffer(
                    kind = ObservationKind.BLOCK,
                    sourceIndex = index,
                    what = blockDescriptionId(block.what),
                    position = block.position.map { it + BLOCK_CENTER_OFFSET },
                    visible = block.visible,
                )
            }
        val items =
            raw.report.items.mapIndexed { index, item ->
                SceneOffer(
                    kind = ObservationKind.DROPPED_ITEM,
                    sourceIndex = index,
                    what = itemDescriptionId(item.what),
                    position = item.position,
                    visible = item.visible,
                    subject = itemSubjects.getOrNull(index),
                    count = item.count.toLong(),
                )
            }
        return (entities + blocks + items).sortedWith(
            compareBy<SceneOffer> { blocksApart(raw.report.position, it.position) }
                .thenBy { it.kind.ordinal }
                .thenBy(SceneOffer::sourceIndex),
        )
    }

    private fun observationOf(
        raw: RawSense,
        offer: SceneOffer,
        ref: String,
    ): Observation {
        val deltaX = offer.position[0] - raw.report.position[0]
        val deltaZ = offer.position[2] - raw.report.position[2]
        return Observation(
            kind = offer.kind,
            what = offer.what,
            distance = blocksApart(raw.report.position, offer.position),
            direction = horizontalBearing(deltaX, deltaZ),
            verticalOffset = offer.position[1] - raw.report.position[1],
            visible = offer.visible,
            ref = ref,
            count = offer.count,
        )
    }

    private fun candidatesOf(
        offers: List<SceneOffer>,
        surroundings: List<Observation>,
    ): List<ThreatCandidate> =
        offers.zip(surroundings).mapNotNull { (offer, observation) ->
            if (offer.kind != ObservationKind.ENTITY) return@mapNotNull null
            val subject = offer.subject ?: return@mapNotNull null
            ThreatCandidate(
                subject = subject,
                what = observation.what,
                ref = observation.ref,
                distance = observation.distance,
                direction = observation.direction,
                visible = observation.visible,
            )
        }

    private fun selfStateOf(
        raw: RawSense,
        bindings: List<InventorySlotBinding>,
        recipeBindings: List<WorldTargetRefBinding>,
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
            craftingOptions =
                raw.report.recipes.mapIndexed { index, recipe ->
                    CraftingOption(
                        ref = recipeBindings[index].handle,
                        what = itemDescriptionId(recipe.what),
                        resultCount = recipe.resultCount.toLong(),
                        maxCrafts = recipe.maxCrafts.toLong(),
                        requiresTable = recipe.requiresTable,
                    )
                },
            craftingOptionsOmitted = raw.report.recipesOmitted,
            world =
                WorldState(
                    dayPhase = WorldClock.phaseOf(raw.report.time.timeOfDay.toLong()),
                    isDay = raw.report.time.isDay,
                    ticksUntilNightfall =
                        WorldClock.ticksUntilNightfall(raw.report.time.timeOfDay.toLong()),
                ),
        )
}

private data class SceneOffer(
    val kind: ObservationKind,
    val sourceIndex: Int,
    val what: String,
    val position: List<Double>,
    val visible: Boolean,
    val subject: UUID? = null,
    val count: Long? = null,
) {
    val key: Pair<ObservationKind, Int> = kind to sourceIndex
}

private fun entityDescriptionId(resource: String): String {
    val (namespace, path) = splitResource(resource)
    return "entity.$namespace.${path.replace('/', '.')}"
}

private fun blockDescriptionId(resource: String): String {
    val (namespace, path) = splitResource(resource)
    return "block.$namespace.${path.replace('/', '.')}"
}

private fun itemDescriptionId(resource: String): String {
    val (namespace, path) = splitResource(resource)
    return "item.$namespace.${path.replace('/', '.')}"
}

private fun splitResource(resource: String): Pair<String, String> {
    val split = resource.split(':', limit = 2)
    return if (split.size == 2) split[0] to split[1] else "minecraft" to split[0]
}
