/*
 * The enforceable guarantee that the mind is reached only through a port.
 *
 * These rules are the mind-body separation, stated where the build can check it.
 * The bridge is a domain concept - a perception goes out, a decision comes back -
 * and every way of actually reaching a mind is an adapter behind it. Written as
 * tests because the interesting failures are quiet ones: a transport imported
 * directly into wiring-adjacent code, or a server type creeping into the port,
 * both compile perfectly and both cost the seam.
 */
package dev.loom.mind

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** The port's own package: engine-free domain, no transport detail. */
private const val PORT = "dev.loom.mind"

/** The one transport adapter that exists today. */
private const val TCP_ADAPTER = "dev.loom.mind.tcp"

/** The wiring package, the only place allowed to name a concrete adapter. */
private const val WIRING = "dev.loom.plugin"

class MindBoundaryTest {
    private val plugin: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom")

    @Test
    fun theWholeMindSideIsEngineFree() {
        // The port *and* its adapters: a transport speaks contract bytes over some
        // channel and has no more business with a server than the port does. The one
        // piece that genuinely needs the scheduler - BukkitMainThread - lives in the
        // wiring package for exactly this reason.
        noClasses()
            .that()
            .resideInAPackage("$PORT..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "org.bukkit..", "io.papermc..", "com.destroystokyo..")
            .because(
                "the bridge is a domain concept: the whole round trip - importance, " +
                    "deadline, back pressure, applying a decision - must be testable " +
                    "with no server at all",
            )
            .check(plugin)
    }

    @Test
    fun theTransportIsReachedOnlyThroughThePort() {
        noClasses()
            .that()
            .resideOutsideOfPackage(TCP_ADAPTER)
            .and()
            .resideOutsideOfPackage(WIRING)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(TCP_ADAPTER)
            .because(
                "only $WIRING may name a concrete transport; every other caller " +
                    "depends on CognitionTransport so a different mind deployment is " +
                    "one changed wiring line",
            )
            .check(plugin)
    }

    @Test
    fun everyTransportLivesInItsOwnAdapterPackage() {
        classes()
            .that()
            .areAssignableTo(CognitionTransport::class.java)
            .and()
            .areNotInterfaces()
            .should()
            // One segment below the port: a way of reaching a mind is an adapter and
            // belongs beside dev.loom.mind.tcp, never in the port package itself.
            .resideInAPackage("dev.loom.mind.*")
            .because("a transport is an adapter and belongs in its own package under $PORT")
            .check(plugin)
    }

    @Test
    fun theTransportDoesNotReachBackIntoWiringOrTheBody() {
        // A transport's whole job is contract bytes over some channel. If it could
        // see the body or the plugin, the mind's side of the boundary would gain
        // access to world state the perception is supposed to be the only view of.
        noClasses()
            .that()
            .resideInAPackage(TCP_ADAPTER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.loom.plugin..", "dev.loom.body..")
            .because("the mind receives a bounded Perception and nothing else")
            .check(plugin)
    }

    @Test
    fun theMindNeverReachesWorldStateDirectly() {
        // The body port is the only way anything on this side touches a body, and
        // the mind's side may only ask it to apply a decided Intent. Naming a
        // concrete body adapter would let the bridge bypass the port entirely.
        noClasses()
            .that()
            .resideInAPackage("$PORT..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.loom.body.nms..")
            .because("the mind acts on a body only through BodyPort, never on one body flavor")
            .check(plugin)
    }
}
