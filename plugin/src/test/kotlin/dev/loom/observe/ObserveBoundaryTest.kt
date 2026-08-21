/*
 * "Observation cannot alter a run", stated where the build can check it.
 *
 * The behavioural half of that guarantee is `ObservatoryTest`; this is the structural
 * half, and it is the stronger of the two. A display that holds no body, no mind, and no
 * world handle cannot disturb an agent's energy, timing, decisions, or routing - not
 * because no test caught it doing so, but because there is no call it could make. Written
 * as ArchUnit rules because that property is exactly one careless import away from being
 * false, and the import would compile.
 *
 * The rules also run the other way: nothing in the body or the mind may depend on the
 * display. An operator convenience that the run had started to need would no longer be an
 * operator convenience.
 *
 * WHY THREE TYPES ARE NAMED AS WELL AS THREE PACKAGES. `dev.loom.body` cannot be banned
 * wholesale: the display genuinely reads `AgentId`, `AgentVitals`, `BodyEvent`,
 * `BodyEventRecord`, `EnergyTrend`, `EventTag`, `trendOf` and `amountOf` from it, since a
 * record is the only thing it is given. But three of that package's types are not data - a
 * `BodyPort` spawns, moves and despawns bodies, a `Metabolism` refills the very reserve the
 * criteria say observation must not alter, and a `Mortality` records a death - so they are
 * forbidden by name. Naming them is the difference between "no display has needed one yet"
 * and "no display may have one": importing `Metabolism` into `Observatory` and calling
 * `restore` from `saw` used to leave the whole suite green.
 */
package dev.loom.observe

import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import dev.loom.body.BodyPort
import dev.loom.body.Metabolism
import dev.loom.body.Mortality
import org.junit.jupiter.api.Test

/** The display's own package: engine-free, and a consumer of the record only. */
private const val OBSERVE = "dev.loom.observe"

/** The wiring package, the only place allowed to name a concrete display surface. */
private const val WIRING = "dev.loom.plugin"

class ObserveBoundaryTest {
    private val plugin: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom")

    @Test
    fun theDisplayIsEngineFree() {
        // The one piece that genuinely needs a server - BukkitObserverSink, which paints a
        // boss bar - lives in the wiring package behind the one-way ObserverSink, for the
        // same reason BukkitMainThread does.
        noClasses()
            .that()
            .resideInAPackage("$OBSERVE..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "org.bukkit..", "io.papermc..", "com.destroystokyo..")
            .because(
                "an operator display that cannot name a server type cannot change a " +
                    "world, and every line it would show stays unit-testable",
            )
            .check(plugin)
    }

    @Test
    fun theDisplayCannotReachABodyOrAMind() {
        // The point of the whole package. Attaching an observer must add no perception, no
        // decision, no tick work, and no record - so the display is given records and holds
        // nothing it could act through. One condition rather than two rules, because the
        // packages and the three named types are one intention: the display is handed data
        // and is handed nothing that does anything.
        noClasses()
            .that()
            .resideInAPackage("$OBSERVE..")
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("dev.loom.body.nms..", "dev.loom.mind..", "$WIRING..")
                    .or(belongToAnyOf(BodyPort::class.java, Metabolism::class.java, Mortality::class.java)),
            )
            .because(
                "observation is read-only by construction: the display holds no body " +
                    "port, no cognition bridge, and no world handle to disturb a run with - " +
                    "BodyPort, Metabolism and Mortality are named because they are the " +
                    "three types in the otherwise data-only dev.loom.body that act, by " +
                    "moving a body, refilling a reserve, or ending an agent",
            )
            .check(plugin)
    }

    @Test
    fun theRunDoesNotDependOnBeingWatched() {
        noClasses()
            .that()
            .resideInAnyPackage("dev.loom.body..", "dev.loom.mind..", "dev.loom.contract..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$OBSERVE..")
            .because(
                "a run must behave identically with nobody watching, which it cannot be " +
                    "trusted to do if the body or the mind can see the display at all",
            )
            .check(plugin)
    }
}
