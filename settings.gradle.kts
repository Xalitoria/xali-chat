// Builds this repository on its own. Inside the workspace (the Xali repositories checked out together, see
// Xalitoria/xali-craft), the workspace's settings.gradle.kts is used instead and this file is ignored. This part is the same
// in every repository: `scripts/xali sync` in the workspace copies it, so change it there.
//
// The plugin and the version catalog come from xali-gradle: from maven.xali.host once they are published there, and from the
// local Maven repository otherwise (run `./gradlew publishToMavenLocal` in xali-gradle first, and the same in util, color,
// chat and menu for the modules that use their APIs).
pluginManagement {
    repositories {
        mavenLocal { content { includeGroupAndSubgroups("mc.xalitoria") } }
        maven("https://maven.xali.host/releases") { content { includeGroupAndSubgroups("mc.xalitoria") } }
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
            if (requested.id.id.startsWith("mc.xalitoria.")) useVersion("0.2.0")
        }
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenLocal { content { includeGroupAndSubgroups("mc.xalitoria") } }
        maven("https://maven.xali.host/releases") { content { includeGroupAndSubgroups("mc.xalitoria") } }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from("mc.xalitoria:xali-catalog:0.2.0")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "chat"
include("api", "common", "paper", "velocity")
