package dev.loom.contract

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Fail-the-build architecture boundary rules for the contract module.
 *
 * The contract is the sanctioned surface the body and mind exchange. It must
 * stay plain data plus serialization: it may not reach into any Minecraft/Paper
 * server API, and it may not depend on the packages that use it - the body, the
 * mind, or the plugin's wiring. Encoding that as a test makes the perception
 * boundary a checked property rather than a convention.
 */
class ArchitectureBoundaryTest {
    private val contractClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom.contract")

    @Test
    fun contractDoesNotDependOnServerApi() {
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.bukkit..",
                "io.papermc..",
                "net.minecraft..",
                "com.destroystokyo..",
            )
            .check(contractClasses)
    }

    @Test
    fun contractDoesNotDependOnItsConsumers() {
        // The dependency runs one way: body and mind both depend on the contract,
        // and the contract knows about neither. If that inverted, the two runtimes'
        // shared vocabulary would start carrying one side's concerns.
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.loom.plugin..",
                "dev.loom.body..",
                "dev.loom.mind..",
                "dev.loom.observe..",
            )
            .because(
                "the contract is the currency both sides exchange; it must not know " +
                    "who spends it",
            )
            .check(contractClasses)
    }
}
