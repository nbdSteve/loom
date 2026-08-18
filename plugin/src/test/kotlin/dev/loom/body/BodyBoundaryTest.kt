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
import org.junit.jupiter.api.Test

/** The single package permitted to touch engine internals. */
private const val ADAPTER = "dev.loom.body.nms"

/** The concrete bespoke entity class. Nothing outside the adapter may name it. */
private const val BODY_ENTITY = "dev.loom.body.nms.LoomBodyEntity"

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
}
