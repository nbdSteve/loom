import io.gitlab.arturbosch.detekt.Detekt
import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    // Mojang-mapped NMS workspace. Only dev.loom.body.nms is allowed to use it;
    // BodyBoundaryTest fails the build if any other package reaches for it.
    id("io.papermc.paperweight.userdev") version "1.7.7"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.loom"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Build on Corretto 21: Paper targets Java 21 and the gate toolchain stays on a
// well-supported LTS. The contract module is plain data + serialization; it must
// not depend on any server API.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // The Mojang-mapped Paper server. paperweight downloads, remaps, and
    // decompiles it, so `net.minecraft` symbols are compiled against directly
    // with no reobfuscation step (the runtime has been Mojang-mapped since 1.20.5).
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Validates the shared fixtures against contract/loom.schema.json (draft 2020-12).
    testImplementation("com.networknt:json-schema-validator:1.5.4")
    // Fail-the-build architecture boundary rules.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// --- Fail-the-build gates (commit 1). None of these may ignore failures. ---

// Kotlin: warnings are errors.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// detekt: static analysis, must fail the build.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    ignoreFailures = false
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}

// ktlint: formatting, must fail the build.
ktlint {
    ignoreFailures.set(false)
}

tasks.test {
    useJUnitPlatform()
}

// Paper's runtime has been Mojang-mapped since 1.20.5, so the plugin ships as
// compiled and is never remapped to Spigot's obfuscated names. Saying so
// explicitly keeps `reobfJar` from becoming the production artifact by default.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

// The plugin jar must carry the Kotlin stdlib and kotlinx-serialization, which
// Paper does not provide.
tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// The `check` task must run every gate so CI fails on any violation.
tasks.named("check") {
    dependsOn("detekt", "ktlintCheck")
}
