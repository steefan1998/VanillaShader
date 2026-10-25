pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One jar per Minecraft version; each version is built from the same sources, with //? comments for the differences.
// Per-version dependencies live in stonecutter.properties.toml.
stonecutter {
    create(rootProject) {
        versions("1.21.1", "26.1.2", "26.2", "26.3")
        vcsVersion = "26.1.2"
    }
}

rootProject.name = "vanillashader"
