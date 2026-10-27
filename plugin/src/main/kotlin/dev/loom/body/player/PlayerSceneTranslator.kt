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
import dev.loom.contract.Cover
import dev.loom.contract.CraftingOption
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

/** `INTERACTION_REACH_BLOCKS` in `body/src/sensing.js`, and Mineflayer's player eye height. */
private const val SIGHT_DIG_REACH_BLOCKS = 5.1
private const val SIGHT_EYE_HEIGHT_BLOCKS = 1.62

internal class PlayerSceneTranslator(
    private val context: PlayerBodyContext,
) {
    /**
     * The scene [raw] reports. An [attacker] the server named - the creature that just hurt the
     * agent - keeps its place in the scene however many offers stand nearer, and its ref is the
     * scene's [PlayerScene.attackerRef].
     */
    fun sceneOf(
        raw: RawSense,
        attacker: UUID? = null,
    ): PlayerScene {
        context.authority.serverState.auditSense(raw.identity, raw.auditSnapshot, raw.report)
        val serverSubjects = context.authority.serverState.subjectsOf(raw.identity, raw.report.entities)
        val subjects = subjectsOf(raw, serverSubjects)
        val itemSubjects = context.authority.serverState.itemSubjectsOf(raw.identity, raw.report.items)
        val scene = SceneGround(context.authority.ground, raw.report.position)
        val selected = selectedOffers(raw, subjects, itemSubjects, attacker, scene)
        val entityOffers = selected.filter { it.kind == ObservationKind.ENTITY }
        val blockOffers = selected.filter { it.kind == ObservationKind.BLOCK }
        val itemOffers = selected.filter { it.kind == ObservationKind.DROPPED_ITEM }
        val creatureBindings = context.refs.creatures.mint(entityOffers.map { requireNotNull(it.subject) })
        val blockBindings = context.refs.blocks.mint(blockOffers.map(SceneOffer::sourceIndex))
        val droppedItemBindings = context.refs.droppedItems.mint(itemOffers.map(SceneOffer::sourceIndex))
        val slotBindings = context.refs.slots.mint(raw.report.inventory.map { it.slot })
        val recipeBindings = context.refs.recipes.mint(raw.report.recipes.indices.toList())
        val refs =
            refsOf(
                entityOffers to creatureBindings.map { it.handle },
                blockOffers to blockBindings.map { it.handle },
                itemOffers to droppedItemBindings.map { it.handle },
            )
        val surroundings = selected.map { observationOf(raw, it, requireNotNull(refs[it.key])) }
        val blockRefs = blockOffers.map { it to requireNotNull(refs[it.key]) }
        val cover = sceneCover(raw, scene, blockRefs, surroundings, context.authority.solidity)
        val itemRefs = itemRefsOf(itemOffers, droppedItemBindings)
        return PlayerScene(
            raw = raw,
            creatureBindings = creatureBindings,
            blockBindings = blockBindings,
            droppedItemBindings = droppedItemBindings,
            slotBindings = slotBindings,
            recipeBindings = recipeBindings,
            entityIndex = entityOffers.associate { offer -> requireNotNull(offer.subject) to offer.sourceIndex },
            entitySubjects =
                entityOffers.mapNotNull { offer ->
                    serverSubjects.getOrNull(offer.sourceIndex)?.let { offer.sourceIndex to it }
                }.toMap(),
            droppedItemSubjects = itemOffers.mapNotNull { o -> o.subject?.let { o.sourceIndex to it } }.toMap(),
            surroundings = surroundings,
            attackerRef = refOfSubject(attacker, entityOffers, refs),
            surroundingsOmitted = omittedOf(raw, selected.size),
            perceptionAudit = perceptionAuditOf(raw, entityOffers.size, blockOffers.size, itemOffers.size),
            candidates = candidatesOf(selected, surroundings),
            selfState = selfStateOf(raw, slotBindings, recipeBindings, itemRefs, cover),
            // No walk leaves where no short walk leads out: a pit or a sealed shelter.
            explore = context.exploreFor(raw, selected, scene).takeIf { cover?.walkOut != false }.orEmpty(),
            recalled = context.recall.recalled(raw.report.position, selected),
        )
    }

    private fun selectedOffers(
        raw: RawSense,
        subjects: List<UUID>,
        itemSubjects: List<UUID?>,
        attacker: UUID?,
        scene: SceneGround,
    ): List<SceneOffer> {
        val offers = offersOf(raw, subjects, itemSubjects, scene)
        val reserved = offers.filter { attacker != null && it.subject == attacker }.map(SceneOffer::key).toSet()
        return PlayerSceneSelector.select(raw.report.position, offers, reserved)
    }

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
        scene: SceneGround,
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
        val eyes = raw.report.position.let { listOf(it[0], it[1] + SIGHT_EYE_HEIGHT_BLOCKS, it[2]) }
        val far = { block: List<Int> ->
            blocksApart(eyes, block.map { it + BLOCK_CENTER_OFFSET }) > SIGHT_DIG_REACH_BLOCKS
        }
        val blocks =
            raw.report.blocks.mapIndexed { index, block ->
                // A block sight shows beyond dig reach that no stand a walk reaches digs - a canopy log
                // seen through a gap - is reported but is no safe dig, so it grounds no goal and draws
                // no walk (r35 epsilon, zeta); dropping it would hide an offer the body made.
                val unreached =
                    context.authority.ground !== BlockGround.UNKNOWN && far(block.position) &&
                        !diggableFrom(scene.ground, block.position, scene.walkable)
                SceneOffer(
                    kind = ObservationKind.BLOCK,
                    sourceIndex = index,
                    what = blockDescriptionId(block.what),
                    position = block.position.map { it + BLOCK_CENTER_OFFSET },
                    visible = block.visible,
                    standable = scene.ground.standCell(block.position) != null,
                    cover =
                        scene.ground.standCell(block.position)
                            ?.takeIf { context.authority.ground !== BlockGround.UNKNOWN }
                            ?.let { closedFacesAt(scene.ground, it).toLong() },
                    yields = context.authority.yields.of(block.position, raw.identity).take(MAX_BLOCK_YIELDS),
                    digSafe = !unreached && digSafeAt(scene.ground, raw.report.position, block.position),
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
                    // No route, or a stall from this cell to the item's over the same ground (r34 delta).
                    routeRefused =
                        itemSubjects.getOrNull(index)
                            ?.let { context.routeRefusals.blocks(it, raw.report.position, item.position) }
                            ?.takeIf { it },
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
            cover = offer.cover,
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
        cover: Cover?,
    ): SelfState {
        val mainHand = context.authority.players.mainHandSlotOf(raw.identity)
        return SelfState(
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
                        edible = context.authority.edibility.edible(stack.what),
                        solid = context.authority.solidity.solid(stack.what),
                        held = (stack.slot == mainHand).takeIf { it },
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
            cover = cover,
        )
    }
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

/** The resource an item description names: `item.minecraft.dirt` is `minecraft:dirt`. */
internal fun resourceOfItem(description: String): String {
    val parts = description.removePrefix("item.").split('.', limit = 2)
    return if (parts.size == 2) "${parts[0]}:${parts[1].replace('.', '/')}" else description
}

private fun itemDescriptionId(resource: String): String {
    val (namespace, path) = splitResource(resource)
    return "item.$namespace.${path.replace('/', '.')}"
}

private fun splitResource(resource: String): Pair<String, String> {
    val split = resource.split(':', limit = 2)
    return if (split.size == 2) split[0] to split[1] else "minecraft" to split[0]
}

/** The ref minted for the entity offer whose server subject is [subject], if one was selected. */
private fun refOfSubject(
    subject: UUID?,
    entityOffers: List<SceneOffer>,
    refs: Map<Pair<dev.loom.contract.ObservationKind, Int>, String>,
): String? = subject?.let { creature -> entityOffers.firstOrNull { it.subject == creature }?.let { refs[it.key] } }

/** How many observations the body and the scene bound left out of a scene of [selected]. */
private fun omittedOf(
    raw: RawSense,
    selected: Int,
): Long {
    val byBody = raw.report.entitiesOmitted + raw.report.blocksOmitted + raw.report.itemsOmitted
    return byBody + raw.report.entities.size + raw.report.blocks.size + raw.report.items.size - selected
}
