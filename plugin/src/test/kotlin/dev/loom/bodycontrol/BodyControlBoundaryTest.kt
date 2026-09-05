package dev.loom.bodycontrol

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Fail-the-build architecture boundary rules for the body-control contract.
 *
 * The same statement the perception/decision contract's own boundary test makes, for
 * the same reason: this package is the sanctioned surface a body process is driven
 * over, and it must stay plain data plus serialization. Encoding that as a test makes
 * it a checked property rather than a convention.
 */
class BodyControlBoundaryTest {
    private val bodyControlClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom.bodycontrol")

    @Test
    fun bodyControlDoesNotDependOnServerApi() {
        // The body process this describes is not in the server at all - it is a
        // separate Node process on a loopback socket - so a server type appearing here
        // would mean the contract had started describing the plugin's own internals.
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.bukkit..",
                "io.papermc..",
                "net.minecraft..",
                "com.destroystokyo..",
            )
            .check(bodyControlClasses)
    }

    @Test
    fun bodyControlIsStandaloneWithinLoom() {
        // Two sibling contracts that shared types would be one contract with two
        // schemas, and a change to either wire would silently reach the other. The
        // perception/decision contract carries what an agent knows and decides; this
        // one carries a body process's own lifecycle, the raw state the game reports
        // about its player, and one meal a controller named. Neither may reach the
        // other, and neither may reach a consumer.
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.loom.contract..",
                "dev.loom.plugin..",
                "dev.loom.body..",
                "dev.loom.mind..",
                "dev.loom.observe..",
                // The witness now reads this contract's own `SensedVitals` and `SensedStack`
                // to cross-check what a body claimed, which is the right direction: a
                // consumer may know the wire, and the wire may not know its consumers. Named
                // here so that stays one-way rather than becoming a cycle by accident.
                "dev.loom.witness..",
            )
            .because(
                "the body-control contract is plain data a separate process is driven " +
                    "over; it must know neither its consumers nor the other contract",
            )
            .check(bodyControlClasses)
    }
}
