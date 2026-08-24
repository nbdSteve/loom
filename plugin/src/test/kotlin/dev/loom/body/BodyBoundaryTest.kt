/*
 * The enforceable guarantee that the body port is genuinely swappable.
 *
 * The port is only worth having if the version-fragile engine contact really is
 * confined behind it. These rules fail the build the moment that stops being true,
 * so "all NMS lives in one seam" is a checked property rather than a habit that
 * decays. They are also the maintenance-tax mitigation: a Minecraft version bump
 * can only require changes where these rules permit `net.minecraft` at all.
 */
package dev.loom.body

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The single package permitted to touch engine internals. */
private const val ADAPTER = "dev.loom.body.nms"

/** The concrete bespoke entity class. Nothing outside the adapter may name it. */
private const val BODY_ENTITY = "dev.loom.body.nms.LoomBodyEntity"

/**
 * The engine calls by which a vanilla goal could reach a Loom body.
 *
 * `addGoal` is the only way anything enters a `GoalSelector`, and `registerGoals` is the
 * only thing vanilla calls to fill one - so forbidding a call to it forbids the
 * `super.registerGoals()` that would hand a body a villager's whole behavior back in one
 * line, which is the easiest version of this mistake to make.
 */
private val WAYS_A_GOAL_COULD_GET_IN = setOf("addGoal", "registerGoals")

/** The two selector fields themselves. Reading one is how a goal would be counted, not added. */
private val SELECTOR_FIELDS = setOf("goalSelector", "targetSelector")

/**
 * The engine calls by which the villager brain could reach a Loom body.
 *
 * This, and not `registerGoals`, is what actually suppresses vanilla decision-making on a
 * Villager body. `Villager.customServerAiStep(boolean)` is the sole caller of
 * `getBrain().tick`, and the villager's entire behavior hangs off that one line - `Swim`,
 * `MoveToTargetSink`, `VillagerPanicTrigger`, professions, trading, raids. So a body that
 * never reaches `getBrain` and never delegates to the vanilla hook runs none of it, and
 * either call reappearing would hand a body its whole self back.
 */
private val WAYS_THE_BRAIN_COULD_TICK = setOf("getBrain", "customServerAiStep")

/** The two overloads of the server-AI hook, by parameter count. Missing one restores vanilla. */
private val SERVER_AI_HOOK_ARITIES = setOf(0, 1)

class BodyBoundaryTest {
    private val plugin: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom")

    @Test
    fun onlyTheBodyAdapterTouchesEngineInternals() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ADAPTER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "net.minecraft..",
                "org.bukkit.craftbukkit..",
                "com.destroystokyo..",
            )
            .because(
                "all version-fragile engine contact must stay inside $ADAPTER so a " +
                    "Minecraft version bump touches one small seam and the body flavor " +
                    "stays swappable",
            )
            .check(plugin)
    }

    @Test
    fun onlyTheBodyAdapterNamesTheConcreteBodyEntity() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ADAPTER)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(BODY_ENTITY)
            .because(
                "the concrete entity class is one body flavor's implementation " +
                    "detail; callers must depend on BodyPort instead",
            )
            .check(plugin)
    }

    @Test
    fun theBodyPortItselfIsEngineFree() {
        noClasses()
            .that()
            .resideInAPackage("dev.loom.body")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "org.bukkit..", "io.papermc..")
            .because(
                "the port's own package defines the domain vocabulary crossing the " +
                    "seam; if an engine type reaches it, the seam has moved",
            )
            .check(plugin)
    }

    @Test
    fun theAdapterIsReachedOnlyThroughThePort() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ADAPTER)
            .and()
            .resideOutsideOfPackage("dev.loom.plugin")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ADAPTER)
            .because(
                "only the plugin's wiring may name a concrete adapter; every other " +
                    "caller depends on BodyPort so the flavor can be swapped there",
            )
            .check(plugin)
    }

    @Test
    fun theContractStaysTheOnlyCurrencyCrossingThePort() {
        // The port is the sanctioned channel between domain and engine. If the
        // adapter reached into plugin wiring, the dependency would invert and the
        // port would stop being the seam.
        noClasses()
            .that()
            .resideInAPackage(ADAPTER)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.loom.plugin..")
            .check(plugin)
    }

    @Test
    fun theBodySideCannotReachCognitionAtAll() {
        // Which is what keeps `tick` incapable of thinking. Actuation runs every
        // tick and cognition is event-driven; a body that could call a mind would
        // make one inference per agent per tick reachable by an ordinary-looking
        // edit, and the contract has no per-tick Trigger to make that legible.
        // Cognition is driven from above the port instead - see AutonomousDriver.
        noClasses()
            .that()
            .resideInAPackage("dev.loom.body..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.loom.mind..")
            .because(
                "the body reports what happened and applies what was decided; it " +
                    "never decides, and BodyPort.tick must stay actuation only",
            )
            .check(plugin)
    }

    @Test
    fun everyBodyPortImplementationLivesInAnAdapterPackage() {
        classes()
            .that()
            .areAssignableTo(BodyPort::class.java)
            .and()
            .areNotInterfaces()
            .should()
            // One segment below the domain: an implementation belongs in a sibling
            // adapter package like dev.loom.body.nms, never in the domain itself.
            .resideInAPackage("dev.loom.body.*")
            .because("a body flavor is an adapter and belongs in its own adapter package under dev.loom.body")
            .check(plugin)
    }

    @Test
    @DisplayName("no goal or target selector is ever filled, so Minecraft cannot choose a goal")
    fun theSelectorsStayEmpty() {
        // Native navigation is Minecraft *finding a route*. A goal selector is Minecraft
        // *choosing a target*, which is the mind's job alone, and the two arrive through
        // the same vanilla machinery - so the line between them has to be checked rather
        // than remembered. It is checked statically because the runtime version of this
        // assertion cannot be written: constructing a body needs a real Level and a
        // bootstrapped registry. Asserting nothing anywhere can fill a selector is the
        // stronger claim anyway - it covers every body that will ever be built, not one.
        val calls =
            plugin
                .flatMap { it.methodCallsFromSelf }
                .filter { it.target.name in WAYS_A_GOAL_COULD_GET_IN }
                .map { "${it.originOwner.simpleName} calls ${it.target.name}" }
        val reads =
            plugin
                .flatMap { it.fieldAccessesFromSelf }
                .filter { it.target.name in SELECTOR_FIELDS }
                .map { "${it.originOwner.simpleName} touches ${it.target.name}" }

        assertEquals(emptyList<String>(), calls + reads)
    }

    @Test
    @DisplayName("the body still overrides registerGoals, so a future base type cannot fill the selectors")
    fun theBodyStillRefusesVanillaGoals() {
        // Worth keeping, but be honest about what it is worth: on this base type the
        // override suppresses nothing today. `Mob.registerGoals` is an empty body and
        // neither `Villager` nor `AbstractVillager` overrides it, so deleting the override
        // would change no behavior now. What it buys is a base-type change: the moment the
        // body extends something that does fill a selector, the override is already there
        // and this test says it must stay. The mechanism that actually suppresses vanilla
        // behavior on a villager is pinned by theBodyNeverTicksTheVillagerBrain.
        val body = plugin.get(BODY_ENTITY)

        assertTrue(
            body.methods.any { it.name == "registerGoals" && it.rawParameterTypes.isEmpty() },
            "$BODY_ENTITY must override registerGoals to add none",
        )
    }

    @Test
    @DisplayName("the body overrides both server-AI hooks and neither can reach the villager brain")
    fun theBodyNeverTicksTheVillagerBrain() {
        // The load-bearing half of "Minecraft may never choose a goal" on this base type.
        // Empty selectors are not enough: a villager decides through its *brain*, and one
        // `super.customServerAiStep()` would restore the lot. Both overloads must exist -
        // vanilla calls the deprecated one from `serverAiStep` and the inactive-aware one
        // from `inactiveTick`, so keeping only one leaves a live path to the brain - and
        // neither may call out to the brain or upward to the hook it replaces.
        //
        // Structural for the same reason theSelectorsStayEmpty is: the runtime version of
        // this assertion needs a real Level and a bootstrapped registry.
        val body = plugin.get(BODY_ENTITY)
        val hooks = body.methods.filter { it.name == "customServerAiStep" }

        assertEquals(
            SERVER_AI_HOOK_ARITIES,
            hooks.map { it.rawParameterTypes.size }.toSet(),
            "$BODY_ENTITY must override both customServerAiStep overloads",
        )
        assertEquals(
            emptyList<String>(),
            plugin
                .flatMap { it.methodCallsFromSelf }
                .filter { it.target.name in WAYS_THE_BRAIN_COULD_TICK }
                .map { "${it.originOwner.simpleName}.${it.origin.name} calls ${it.target.name}" },
        )
    }
}
