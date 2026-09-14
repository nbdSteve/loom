/*
 * The player body is the only production implementation of BodyPort.
 *
 * These rules make the Villager removal structural: no retired package may return,
 * no server-internal type may leak back in, and every concrete BodyPort must be the
 * Mineflayer player adapter wired by the plugin.
 */
package dev.loom.body

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

private const val PLAYER_ADAPTER = "dev.loom.body.player"
private const val PLAYER_PORT = "dev.loom.body.player.PlayerBodyPort"
private const val WIRING = "dev.loom.plugin"
private const val RETIRED_ADAPTER = "dev.loom.body.nms"

class BodyBoundaryTest {
    private val plugin: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.loom")

    @Test
    fun noRetiredBodyPackageExists() {
        noClasses()
            .should()
            .resideInAPackage("$RETIRED_ADAPTER..")
            .because("the Villager body was retired rather than hidden behind a runtime switch")
            .check(plugin)
    }

    @Test
    fun thePlayerPortIsTheOnlyConcreteBodyPort() {
        classes()
            .that()
            .areAssignableTo(BodyPort::class.java)
            .and()
            .areNotInterfaces()
            .should()
            .haveFullyQualifiedName(PLAYER_PORT)
            .because("production has exactly one body runtime: the Mineflayer player")
            .check(plugin)
    }

    @Test
    fun theDomainStaysEngineFree() {
        noClasses()
            .that()
            .resideInAPackage("dev.loom.body")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "org.bukkit..", "io.papermc..")
            .because("only domain and contract types cross BodyPort")
            .check(plugin)
    }

    @Test
    fun noProductionCodeUsesServerInternals() {
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "org.bukkit.craftbukkit..", "com.destroystokyo..")
            .because("the Mineflayer runtime needs the public server API, not NMS")
            .check(plugin)
    }

    @Test
    fun onlyWiringCanSelectThePlayerAdapter() {
        noClasses()
            .that()
            .resideOutsideOfPackage("$PLAYER_ADAPTER..")
            .and()
            .resideOutsideOfPackage("$WIRING..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$PLAYER_ADAPTER..")
            .because("runtime selection belongs in plugin wiring and has no compatibility branch")
            .check(plugin)
    }

    @Test
    fun theBodySideCannotReachCognition() {
        noClasses()
            .that()
            .resideInAPackage("dev.loom.body..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.loom.mind..")
            .because("the body applies decisions and never makes them")
            .check(plugin)
    }
}
