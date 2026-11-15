import mc.xalitoria.gradle.sibling

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
    api(libs.kotlinx.serialization)
    // For the log page, which reads the servers' logs from their database (see LogDatabase).
    api(libs.kotlinx.coroutines)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.bundles.database)
    compileOnly(libs.velocity.api)
    compileOnly(libs.plan) { isTransitive = false }
    compileOnly(libs.premiumvanish.api)
}

tasks {
    jar { enabled = false }
    build {
        dependsOn(shadowJar)
    }
}
