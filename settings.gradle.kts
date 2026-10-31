// Builds this repository on its own. Inside the workspace (the Xali repositories checked out together), the workspace's
// settings.gradle.kts is used instead and this file is ignored.
//
// The plugin and the version catalog come from xali-gradle; until they are published anywhere else they are read from the
// local Maven repository: run `./gradlew publishToMavenLocal` in xali-gradle first, and the same in util, color, chat and menu
// for the modules that use their APIs.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        val kotlinVersion = providers.gradleProperty("kotlin.version").get()
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.serialization") version kotlinVersion
        kotlin("multiplatform") version kotlinVersion
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("mc.xalitoria.")) useVersion("0.1.0")
        }
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenLocal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from("mc.xalitoria:xali-catalog:0.1.0")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "chat"
include("api", "common", "paper", "velocity")
