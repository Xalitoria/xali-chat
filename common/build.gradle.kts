import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import xalitoria.repo.kotlinx

plugins {
    kotlin("plugin.serialization")
}

// What the Paper and Velocity plugins share: the wire format and the announcement store. It uses no platform API, so
// both jars bundle it, and it targets Java 17 because Velocity may run on an older JVM than Paper.
// kaml and kotlinx.serialization are compileOnly: Paper's library loader supplies them (through util), and the
// Velocity plugin bundles its own.
dependencies {
    compileOnly(libs.kaml)
    compileOnly(kotlinx("serialization-json"))
    testImplementation(libs.kaml)
    testImplementation(kotlinx("serialization-json"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
