/*
 * Translation of one body-control sense report into Loom's perception types.
 */
package dev.loom.body.player

import dev.loom.body.InventorySlotBinding
import dev.loom.body.PerceptionAudit
import dev.loom.body.PerceptionKindAudit
import dev.loom.body.PerceptionWorldClockAudit
import dev.loom.body.ThreatCandidate
import dev.loom.body.WorldClock
import dev.loom.body.WorldTargetRefBinding
import dev.loom.body.blocksApart
import dev.loom.body.horizontalBearing
import dev.loom.body.huntsPlayers
import dev.loom.contract.CraftingOption
import dev.loom.contract.ExploreTarget
import dev.loom.contract.InventoryStack
import dev.loom.contract.MAX_BLOCK_YIELDS
import dev.loom.contract.MAX_OWN_DROPS
import dev.loom.contract.Observation
import dev.loom.contract.ObservationKind
import dev.loom.contract.OwnDrop
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
        val selected = selectedOffers(raw, subjects, itemSubjects)
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
            perceptionAudit = perceptionAuditOf(raw, entityOffers.size, blockOffers.size, itemOffers.size),
            candidates = candidatesOf(selected, surroundings),
            selfState = selfStateOf(raw, slotBindings, recipeBindings, itemRefsOf(itemOffers, droppedItemBindings)),
            explore = exploreOf(context.authority.ground, raw.report.position),
        )
    }

    private fun selectedOffers(
        raw: RawSense,
        subjects: List<UUID>,
        itemSubjects: List<UUID?>,
    ): List<SceneOffer> =
        PlayerSceneSelector.select(
            raw.report.position,
            offersOf(raw, subjects, itemSubjects),
        )

    private fun perceptionAuditOf(
        raw: RawSense,
        selectedEntities: Int,
        selectedBlocks: Int,
        selectedItems: Int,
    ): PerceptionAudit =
        PerceptionAudit(
            worldClock =
                PerceptionWorldClockAudit(
                    timeOfDay = raw.report.time.timeOfDay,
                    day = raw.report.time.day,
                    isDay = raw.report.time.isDay,
                ),
            entities =
                kindAudit(
                    rawCount = raw.report.entities.size,
                    bodyOmitted = raw.report.entitiesOmitted,
                    selectedCount = selectedEntities,
                ),
            blocks =
                kindAudit(
                    rawCount = raw.report.blocks.size,
                    bodyOmitted = raw.report.blocksOmitted,
                    selectedCount = selectedBlocks,
                ),
            droppedItems =
                kindAudit(
                    rawCount = raw.report.items.size,
                    bodyOmitted = raw.report.itemsOmitted,
                    selectedCount = selectedItems,
                ),
        )

    private fun kindAudit(
        rawCount: Int,
        bodyOmitted: Long,
        selectedCount: Int,
    ): PerceptionKindAudit =
        PerceptionKindAudit(
            rawBodyListCount = rawCount.toLong(),
            bodyReportedOmittedCount = bodyOmitted,
            jvmSelectedCount = selectedCount.toLong(),
            jvmCutOmittedCount = (rawCount - selectedCount).toLong(),
        )

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
                    standable = context.authority.ground.standCell(block.position) != null,
                    yields = context.authority.yields.of(block.position, raw.identity).take(MAX_BLOCK_YIELDS),
                    digSafe = digSafeAt(context.authority.ground, raw.report.position, block.position),
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
                    routeRefused = itemSubjects.getOrNull(index)?.let { it in context.routeRefusals }?.takeIf { it },
                )
            }
        return entities + blocks + items
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
            standable = offer.standable,
            yields = offer.yields,
            digSafe = offer.digSafe,
            routeRefused = offer.routeRefused,
            hunts = huntsPlayers(offer.what).takeIf { it && offer.kind == ObservationKind.ENTITY },
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
        itemRefs: Map<UUID, String>,
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
            ownDrops = ownDropsOf(context.authority.ownDrops.of(raw.identity), raw.report.position, itemRefs),
            world =
                WorldState(
                    dayPhase = WorldClock.phaseOf(raw.report.time.timeOfDay.toLong()),
                    isDay = raw.report.time.isDay,
                    ticksUntilNightfall =
                        WorldClock.ticksUntilNightfall(raw.report.time.timeOfDay.toLong()),
                ),
        )
}

/** Each offered dropped item's server entity, by the ref this perception gave it. */
private fun itemRefsOf(
    offers: List<SceneOffer>,
    bindings: List<WorldTargetRefBinding>,
): Map<UUID, String> =
    offers.zip(bindings).mapNotNull {
            (offer, binding) ->
        offer.subject?.let { it to binding.handle }
    }.toMap()

/**
 * What the server saw an agent's own digs drop, a lying one placed from [position], where it
 * stands now, and named by the ref [itemRefs] gives the observation of its server entity, when
 * this perception offers one.
 */
internal fun ownDropsOf(
    readings: List<OwnDropReading>,
    position: List<Double>,
    itemRefs: Map<UUID, String> = emptyMap(),
): List<OwnDrop> =
    readings.take(MAX_OWN_DROPS).map { drop ->
        val at = drop.position
        OwnDrop(
            requestId = drop.requestId,
            what = drop.what,
            count = drop.count,
            fate = drop.fate,
            distance = at?.let { blocksApart(position, it) },
            direction = at?.let { horizontalBearing(it[0] - position[0], it[2] - position[2]) },
            verticalOffset = at?.let { it[1] - position[1] },
            ref = drop.entity?.takeIf { at != null }?.let(itemRefs::get),
        )
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

/** How far along each compass axis an exploration cell lies: past the ~5 blocks a body sees. */
internal const val EXPLORE_REACH_BLOCKS = 12

/** The same along each axis of a diagonal bearing: about twelve blocks off in all. */
private const val EXPLORE_DIAGONAL_BLOCKS = 8

/** How far above and below the feet an exploration cell's ground is looked for. */
private const val EXPLORE_RISE_BLOCKS = 3
private const val EXPLORE_FALL_BLOCKS = 4

/** The eight compass steps, in the order their refs are minted: north first, clockwise. */
private val COMPASS =
    listOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)

/**
 * The cells at the edge of sight an agent at [position] can walk to, one per compass bearing:
 * the highest cell the server's [ground] says a body can stand in, [EXPLORE_REACH_BLOCKS] off
 * along that bearing, and within a few blocks of the feet. They state only where a body can
 * stand, never what lies there, so an agent that sees nothing worth doing can look further
 * (r17: eta idled three reviews with a dead bush 5.4 blocks off, just past what its body saw).
 */
internal fun exploreOf(
    ground: BlockGround,
    position: List<Double>,
): List<Pair<ExploreTarget, List<Int>>> {
    val feet = position.map { kotlin.math.floor(it).toInt() }
    return COMPASS.mapNotNull { (dx, dz) ->
        // A diagonal step is shortened so every cell lies about as far off.
        val along = if (dx != 0 && dz != 0) EXPLORE_DIAGONAL_BLOCKS else EXPLORE_REACH_BLOCKS
        val x = feet[0] + dx * along
        val z = feet[2] + dz * along
        (feet[1] + EXPLORE_RISE_BLOCKS downTo feet[1] - EXPLORE_FALL_BLOCKS)
            .firstNotNullOfOrNull { y -> ground.standCell(listOf(x, y - 1, z))?.takeIf { it[1] == y } }
    }.mapIndexed { index, cell ->
        val centre = listOf(cell[0] + BLOCK_CENTER_OFFSET, cell[1].toDouble(), cell[2] + BLOCK_CENTER_OFFSET)
        ExploreTarget(
            ref = "x${index + 1}",
            distance = blocksApart(position, centre),
            direction = horizontalBearing(centre[0] - position[0], centre[2] - position[2]),
            verticalOffset = centre[1] - position[1],
        ) to cell
    }
}
