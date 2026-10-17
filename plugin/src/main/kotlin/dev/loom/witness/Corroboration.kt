/*
 * Whether a player body's own account of itself agrees with the server's.
 *
 * The Mineflayer body reads its vitals and its inventory from a client, and a client
 * reports what it was told. The server knows what is actually true, which is the whole
 * reason `dev.loom.witness` exists at all - so the same asymmetry that makes Paper the
 * authority on a death makes it the authority on a reading. This file is the comparison
 * and nothing else: reading the server is `dev.loom.witness.bukkit`'s job, and deciding
 * what to do about a disagreement is the caller's.
 *
 * Three things here are load-bearing rather than stylistic.
 *
 * **The claim and the observation are different types on purpose.** A claim arrives as
 * `dev.loom.bodycontrol`'s own [SensedVitals] and [SensedStack], which refuse a reading
 * the wire may not carry - that is exactly right for a claim, because a body that
 * reports twenty-one food has said something the protocol forbids. The server's reading
 * is not a claim but the truth, and forcing the truth through a bound is how a witness
 * comes to lie: a plugin can set a food level of thirty, and a witness whose reading of
 * it *throws* has stopped witnessing. So [ObservedVitals] and [ObservedStack] carry
 * whatever the server says, unbounded, and the comparison is between the two shapes.
 *
 * **A continuous vital drifts and an item does not.** The server snapshot is taken
 * immediately before the body sense request, and the client report returns after that
 * socket round trip, so health and saturation can cross one ordinary update between the
 * two readings and need a stated tolerance around the span of the server's readings from
 * before the request to when its answer is audited, and saturation is compared under
 * vanilla's own resend rule; an item's name and its count do not drift,
 * so they are compared exactly. Reading either rule as the other's gives a check that is
 * useless in one direction and a false alarm in the other. Exactly, but not as *written*:
 * the contract makes an item's namespace optional and the two sides reach the comparison
 * spelled differently, so both are normalised first - `ItemNames.kt`.
 *
 * **The comparison covers the slots the server can address, and says so.** Bukkit's
 * `PlayerInventory` numbers the armour, the main inventory, the hotbar and the offhand,
 * and does not reach the five crafting-window slots the protocol's 46-slot window
 * begins with. Those are excluded by construction through [isCorroborableSlot] rather
 * than read as agreement or as a fault, and a dispute states how many claimed stacks
 * fell outside the comparison, so "the witness did not look" never reads as "the
 * witness agreed".
 *
 * That last rule is about a whole claim as well as about one slot, which is why two more
 * states exist beside agreement and dispute. [Corroboration.NotManaged] is a claim from a
 * player no registration names, which is not compared at all. [Corroboration.PartlyChecked]
 * is a claim that was compared and agreed as far as it went, with part of it excluded - the
 * same fact a dispute states as its count, which an agreeing answer used to drop. Answering
 * either one [Corroboration.Agreed] said the server had vouched for numbers nothing had
 * looked at.
 */
package dev.loom.witness

import dev.loom.body.EventTag
import dev.loom.bodycontrol.SensedStack
import dev.loom.bodycontrol.SensedVitals
import java.util.Locale

/**
 * How far apart two readings of one continuous vital may be and still agree.
 *
 * A whole point of health is half a heart, and a whole point of saturation is a whole
 * point: the server snapshot and later client report straddle a socket round trip, so a
 * tighter bound would report ordinary regeneration as a body having lied about itself.
 */
const val VITAL_DRIFT_TOLERANCE: Double = 1.0

/**
 * How far apart two readings of the food level may be and still agree.
 *
 * Food moves in whole points at discrete moments rather than continuously, so one point
 * covers a body whose reading straddled one such moment and nothing wider.
 */
const val FOOD_DRIFT_TOLERANCE: Int = 1

/** Which of a body's vitals a [VitalFault] is about. */
enum class Vital {
    HEALTH,
    FOOD,
    SATURATION,
    ;

    /** The word a record uses for this vital. */
    val word: String get() = name.lowercase(Locale.ROOT)
}

/** One vital the body claimed and the server reads differently, with both readings. */
data class VitalFault(val vital: Vital, val claimed: Double, val observed: Double)

/** One slot the body's account of its inventory and the server's do not agree about. */
sealed interface InventoryFault {
    /** The window slot the disagreement is about. Always [isCorroborableSlot]. */
    val slot: Int

    /** The body claimed a stack the server holds nothing in. */
    data class NotHeld(override val slot: Int, val claimed: SensedStack) : InventoryFault

    /** The server holds a stack the body's account left out. */
    data class NotClaimed(override val slot: Int, val held: ObservedStack) : InventoryFault

    /** Both name the slot and disagree about the item in it or how much of it there is. */
    data class Differs(
        override val slot: Int,
        val claimed: SensedStack,
        val held: ObservedStack,
    ) : InventoryFault
}

/**
 * The server's own reading of a managed player's vitals, exactly as it reports them.
 *
 * Deliberately unbounded where [SensedVitals] is bounded: see this file's header.
 */
data class ObservedVitals(val health: Double, val food: Int, val saturation: Double)

/**
 * The server's own reading of one occupied inventory slot.
 *
 * [slot] is a protocol window slot, translated from Bukkit's own numbering by
 * [windowSlotOfBukkitSlot], and [what] is the item's registry name in the same
 * vocabulary the body reports - the game's own answer rather than a table of Loom's.
 */
data class ObservedStack(val slot: Int, val what: String, val count: Int)

/**
 * What one player body said about itself, as one thing.
 *
 * A pair rather than two arguments, and so is [ServerReading], because the two halves of a
 * cross-check have the same shape in outline: passed positionally they can be swapped, and a
 * swapped cross-check answers with the faults reversed and reads perfectly plausibly.
 */
data class BodyClaim(val vitals: SensedVitals, val inventory: List<SensedStack>)

/** What the server itself read about that player at the sense request's capture boundary. */
data class ServerReading(val vitals: ObservedVitals, val inventory: List<ObservedStack>)

/** Whether a body's account of itself stands up against the server's own reading. */
sealed interface Corroboration {
    /** Every checkable fact agreed, within the stated tolerances. Nothing is recorded. */
    data object Agreed : Corroboration

    /**
     * Nothing was compared, because no registration says this player is a body of Loom's.
     *
     * Its own state rather than [Agreed], for exactly the reason the header gives about the
     * crafting slots: "the witness did not look" must never read as "the witness agreed".
     * Answered [Agreed], an unregistered claim came back as corroborated - so a caller that
     * gates on agreement would treat a body's own numbers as server-vouched at precisely the
     * moment nothing had vouched for them, and at cutover the ordinary way to reach that is a
     * managed body whose registration has not landed yet rather than a stranger.
     *
     * Nothing is recorded for it either way, which is the same rule every statement after a
     * login follows: a player Loom was never asked about is not Loom's to have an opinion
     * about, and a record naming one would witness a stranger.
     */
    data object NotManaged : Corroboration

    /**
     * Nothing the comparison looked at disagreed, but part of the claim was never looked at.
     *
     * The third state the same rule demands, one level up from a slot: [Disputed] states its
     * [Disputed.outsideComparison] because a partly checked disagreement is not a wholly checked
     * one, and an *agreeing* cross-check dropped that count entirely - so a claim whose every
     * stack sat in the crafting window came back as [Agreed] with the whole of the inventory
     * unexamined. A body could put a stack exactly there and be told the server had vouched for
     * it, which is the gap the exclusion exists to make visible.
     *
     * Any unchecked claimed stack reaches this, not only a claim filtered away entirely: the
     * count is dropped in the mixed case for the same reason and by the same line, and one
     * condition rather than two is what stops the narrower case being re-answered [Agreed].
     *
     * Not a [Disputed] with no faults, which could not be built and would be a false accusation
     * if it could: nothing here says the body was wrong. So nothing is recorded either, on the
     * rule agreement already follows - the caller holds the count and decides. What is recorded
     * about an unchecked slot is stated where it is also a disagreement, in [detailOf].
     *
     * @param outsideComparison how many claimed stacks named a slot the server cannot be asked
     *   about. Always positive: none of them is [Agreed].
     */
    data class PartlyChecked(val outsideComparison: Int) : Corroboration {
        init {
            require(outsideComparison > 0) { "a wholly checked account is Agreed" }
        }
    }

    /**
     * At least one checkable fact did not agree.
     *
     * Cannot be empty, which is what makes [tagOf] total: a dispute names at least one
     * vital or at least one slot, so the three countable tags cover every instance.
     *
     * @param outsideComparison how many claimed stacks named a slot the server cannot be
     *   asked about, so a reader can tell a wholly checked account from a partly checked
     *   one without going back to the claim.
     */
    data class Disputed(
        val vitals: List<VitalFault>,
        val inventory: List<InventoryFault>,
        val outsideComparison: Int = 0,
    ) : Corroboration {
        init {
            require(vitals.isNotEmpty() || inventory.isNotEmpty()) {
                "a dispute must name at least one fault"
            }
            require(outsideComparison >= 0) { "outsideComparison cannot be negative" }
        }
    }
}

/**
 * Which countable tag [dispute] is filed under.
 *
 * Three values rather than one, because "the body misreported how hungry it is" and
 * "the body misreported what it is carrying" are different failures with different
 * causes, and a run has to be able to count them apart. Total by construction: a
 * [Corroboration.Disputed] with neither list populated cannot be built.
 */
fun tagOf(dispute: Corroboration.Disputed): String =
    when {
        dispute.vitals.isNotEmpty() && dispute.inventory.isNotEmpty() -> EventTag.DISPUTED_BOTH
        dispute.vitals.isNotEmpty() -> EventTag.DISPUTED_VITALS
        else -> EventTag.DISPUTED_INVENTORY
    }

/**
 * Whether the body's [claimed] account of itself agrees with the server's own reading.
 *
 * The comparison is one-directional in authority and two-directional in coverage: a
 * stack the body claimed and a stack it left out are both faults, because an account
 * that omits the food an agent is carrying is as wrong as one that invents it.
 *
 * Three answers rather than two, because the count of claimed stacks the server cannot be
 * asked about is part of every one of them: [Corroboration.Agreed] is only for an account
 * that was checked *whole*, and one carrying an uncheckable stack is
 * [Corroboration.PartlyChecked] however well the rest of it agreed.
 */
@Suppress("ReturnCount")
fun corroborate(
    claimed: SensedVitals,
    claimedInventory: List<SensedStack>,
    observed: ObservedVitals,
    observedInventory: List<ObservedStack>,
    after: ServerReading? = null,
): Corroboration {
    val vitals = vitalFaults(claimed, observed, after?.vitals ?: observed)
    val checkable = claimedInventory.filter { isCorroborableSlot(it.slot) }
    // The claim is one moment inside the round trip, so it agrees when it matches the server at
    // either end: the path rehearsal's theta picked up a stick while its sense was in flight, and
    // the claim held the stick the reading from before the request did not.
    val before = inventoryFaults(checkable, observedInventory.filter { isCorroborableSlot(it.slot) })
    val atAnswer =
        after?.let {
                reading ->
            inventoryFaults(checkable, reading.inventory.filter { isCorroborableSlot(it.slot) })
        }
    val inventory = if (before.isNotEmpty() && atAnswer?.isEmpty() == true) atAnswer else before
    val outside = claimedInventory.size - checkable.size
    if (vitals.isEmpty() && inventory.isEmpty()) {
        return if (outside == 0) Corroboration.Agreed else Corroboration.PartlyChecked(outside)
    }
    return Corroboration.Disputed(vitals = vitals, inventory = inventory, outsideComparison = outside)
}

/**
 * The per-slot comparison, over the union of the two accounts.
 *
 * The union rather than either side alone: iterating the claim would never notice a
 * stack the body left out, and iterating the server would never notice one it invented.
 * The slots are walked in order so the same disagreement always reads the same way.
 */
private fun inventoryFaults(
    claimed: List<SensedStack>,
    observed: List<ObservedStack>,
): List<InventoryFault> {
    val claimedBySlot = claimed.associateBy { it.slot }
    val observedBySlot = observed.associateBy { it.slot }
    return (claimedBySlot.keys + observedBySlot.keys).sorted().mapNotNull { slot ->
        val itsClaim = claimedBySlot[slot]
        val itsTruth = observedBySlot[slot]
        when {
            itsClaim == null && itsTruth != null -> InventoryFault.NotClaimed(slot, itsTruth)
            itsClaim != null && itsTruth == null -> InventoryFault.NotHeld(slot, itsClaim)
            itsClaim != null && itsTruth != null && !agree(itsClaim, itsTruth) ->
                InventoryFault.Differs(slot, itsClaim, itsTruth)
            else -> null
        }
    }
}

/**
 * Exact on both facts, once the two spellings of one name are made one: neither an item's
 * name nor a count drifts.
 *
 * `ItemNames.kt` owns why the two sides reach this spelled differently and what is dropped.
 * Normalised on **both** of them rather than on the claim alone, though it is a no-op on the
 * server's today: which side carries a namespace is a property of two APIs, and a comparison
 * that stripped only one of them would silently start disputing again the day the other began
 * reporting a whole key.
 */
private fun agree(
    claimed: SensedStack,
    held: ObservedStack,
): Boolean = withoutNamespace(claimed.what) == withoutNamespace(held.what) && claimed.count == held.count

/**
 * What a dispute record says, in the one place that decides it.
 *
 * Both counts stand ahead of the fault list, and that order is the whole of what makes
 * them survive: [WitnessJournal] bounds a detail at [MAX_WITNESS_DETAIL_CHARS] by cutting
 * the *tail*, and a dispute naming a whole inventory overruns that bound easily - so a
 * count written after the faults is exactly the part that gets cut. Trimming a fault costs
 * one line of a list the record already says the length of; trimming the outside count
 * turns "the witness did not look" back into "the witness agreed", which is the reading
 * the exclusion exists to prevent. Both counts are short and bounded (at most
 * [FIRST_CORROBORABLE_SLOT] stacks can be outside), so the head always fits.
 *
 * Each fault then names both readings: which of the two is wrong is not a question the
 * comparison can answer, and a detail stating only the server's would read as the witness
 * having merely taken a reading.
 */
fun detailOf(dispute: Corroboration.Disputed): String {
    val faults = dispute.vitals.map(::vitalWords) + dispute.inventory.map(::inventoryWords)
    val outside =
        if (dispute.outsideComparison == 0) {
            ""
        } else {
            ", ${dispute.outsideComparison} claimed stacks outside the comparison"
        }
    return "${faults.size} disagreed$outside: ${faults.joinToString("; ")}"
}

private fun vitalWords(fault: VitalFault): String =
    "${fault.vital.word} claimed ${twoPlaces(fault.claimed)}, server reads ${twoPlaces(fault.observed)}"

private fun inventoryWords(fault: InventoryFault): String =
    when (fault) {
        is InventoryFault.NotHeld ->
            "slot ${fault.slot} claimed ${fault.claimed.count} ${fault.claimed.what}, server holds nothing"
        is InventoryFault.NotClaimed ->
            "slot ${fault.slot} claimed nothing, server holds ${fault.held.count} ${fault.held.what}"
        is InventoryFault.Differs ->
            "slot ${fault.slot} claimed ${fault.claimed.count} ${fault.claimed.what}, " +
                "server holds ${fault.held.count} ${fault.held.what}"
    }

/**
 * Fixed to two places under [Locale.ROOT], so a record reads the same on every machine.
 *
 * Shared with `MovementCorroboration.kt` rather than restated there, because the two files
 * write the same kind of record for the same reader: a second copy would be a second place a
 * default locale could creep back into a number an operator compares across two records.
 */
internal fun twoPlaces(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
