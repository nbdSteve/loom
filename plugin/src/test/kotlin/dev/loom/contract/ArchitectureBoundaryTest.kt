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
 * server API, and it may not depend on the plugin's future runtime or bridge
 * packages. Encoding that as a test makes the perception boundary a compiler-
 * checked property rather than a convention.
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
    fun contractDoesNotDependOnPluginRuntimeOrBridge() {
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "dev.loom.plugin..",
                "dev.loom.bridge..",
                "dev.loom.runtime..",
            )
            .check(contractClasses)
    }
}
