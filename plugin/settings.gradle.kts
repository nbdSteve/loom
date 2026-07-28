pluginManagement {
    repositories {
        gradlePluginPortal()
        // paperweight-userdev is published to the Paper repository, not the plugin portal.
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "loom-plugin"
