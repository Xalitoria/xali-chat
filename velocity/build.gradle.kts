import mc.xalitoria.gradle.sibling
import mc.xalitoria.gradle.repo.kotlinx

plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

// Declared with api, so the proxy downloads them when the plugin loads instead of the jar carrying them (:chat:common
// leaves kaml and kotlinx.serialization to whoever packages it).
dependencies {
    implementation(sibling("common"))
    api(libs.kaml)
    api(libs.jedis)
    api(kotlinx("serialization-json"))
    compileOnly(libs.velocity.api)
    compileOnly(libs.premiumvanish.api)
}

tasks {
    jar { enabled = false }
    build {
        dependsOn(shadowJar)
    }
}
