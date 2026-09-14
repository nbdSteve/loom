/*
 * "The witness judges; the server reports", stated where the build can check it.
 *
 * Every permadeath rule the acceptance criteria are about - exactly one death per agent, a
 * tombstone durable before a death is final, a dead identity refused after a restart - is decided
 * in `dev.loom.witness` and nowhere else, and this is what keeps that true as the package grows.
 * The one file that meets a running server is `dev.loom.witness.bukkit`, and it translates rather
 * than decides: five Bukkit events in, one verdict out. Written as ArchUnit rules because a
 * `Player` parameter added to `PlayerWitness` to reach "just one more field" would compile, and
 * the whole suite would stay green while the rules stopped being provable at a desk.
 *
 * The rules also run the other way. Nothing in the body, the mind, the contract or the display may
 * depend on the witness: it is a second consumer of the append-only record, exactly as the display
 * is, and a run that had come to need its witness would be a run whose behaviour depended on who
 * was watching it.
 *
 * WHY TWO TYPES ARE NAMED AS WELL AS THREE PACKAGES. `dev.loom.body` cannot be banned wholesale -
 * the witness genuinely reads `AgentId`, `BodyEvent`, `BodyEventLog`, `BodyEventRecord`,
 * `WitnessedPlayer` and `EventTag` from it, and it deliberately *reuses* `Mortality`, which is the
 * one type in Loom that states the permadeath rule. What it may not have is `BodyPort`,
 * which spawns and moves bodies. A witness is evidence and authorisation, never actuation.
 */
package dev.loom.witness

import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import dev.loom.body.BodyPort
import org.junit.jupiter.api.Test

/** The witness's own package: engine-free, and where every permadeath decision is taken. */
private const val WITNESS = "dev.loom.witness"

/** The one adapter, and the only part of the witness allowed to name a server type. */
private const val ADAPTER = "dev.loom.witness.bukkit"

/** The wiring package, the only place allowed to name a concrete adapter. */
private const val WIRING = "dev.loom.plugin"

class WitnessBoundaryTest {
    private val plugin: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom")

    @Test
    fun theJudgementIsEngineFree() {
        // Scoped to the exact package rather than to the tree, because the adapter beneath it is
        // the piece that is supposed to name Bukkit types. Everything above it - who is managed,
        // whether a login is admitted, whether a death is the first - answers without a server,
        // which is what lets a concurrent login race and an unreadable ledger be tested at all.
        noClasses()
            .that()
            .resideInAPackage(WITNESS)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "org.bukkit..", "io.papermc..", "com.destroystokyo..")
            .because(
                "a witness whose rules cannot name a server type is a witness whose rules " +
                    "can be proven at a desk, including the ones a real server only reaches " +
                    "by going wrong",
            )
            .check(plugin)
    }

    @Test
    fun theWitnessCannotActOnABodyOrAMind() {
        noClasses()
            .that()
            .resideInAPackage("$WITNESS..")
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("dev.loom.mind..", "dev.loom.observe..", "$WIRING..")
                    .or(belongToAnyOf(BodyPort::class.java)),
            )
            .because(
                "the witness records what the server did and decides who may connect; it " +
                    "spawns nothing and moves nothing, so BodyPort is the acting type it " +
                    "may not hold - while " +
                    "Mortality is deliberately absent from that list, being the permadeath " +
                    "rule this package reuses rather than restates",
            )
            .check(plugin)
    }

    @Test
    fun theRunDoesNotDependOnBeingWitnessed() {
        noClasses()
            .that()
            .resideInAnyPackage("dev.loom.body..", "dev.loom.mind..", "dev.loom.contract..", "dev.loom.observe..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$WITNESS..")
            .because(
                "the witness is a second consumer of the append-only record, so a run must " +
                    "behave identically whether or not one is present - which it cannot be " +
                    "trusted to do if the body, the mind or the display can see it at all",
            )
            .check(plugin)
    }

    @Test
    fun onlyTheWiringNamesTheWitnessAdapter() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ADAPTER)
            .and()
            .resideOutsideOfPackage(WIRING)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ADAPTER)
            .because(
                "which Bukkit events a server-authoritative witness has to hear is the " +
                    "adapter's business, and a caller that named it would be a caller with an " +
                    "opinion about that",
            )
            .check(plugin)
    }
}
