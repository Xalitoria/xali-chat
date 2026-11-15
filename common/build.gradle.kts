import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("plugin.serialization")
}

// What the Paper and Velocity plugins share: the wire format, the announcement store and the log page with the tables
// it reads. It uses no platform API (Plan's is the web server's, wherever that runs), so both jars bundle it, and it
// targets Java 17 because Velocity may run on an older JVM than Paper.
// kaml, kotlinx.serialization, Jedis, Exposed and coroutines are compileOnly: Paper's library loader supplies them
// (through util and its own dependencies), and the Velocity plugin declares its own.
dependencies {
    compileOnly(libs.kaml)
    compileOnly(libs.jedis)
    compileOnly(libs.kotlinx.serialization)
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.bundles.exposed)
    // Exposed asks for an older slf4j than the platforms bring; compiling only needs some.
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly(libs.plan) { isTransitive = false }
    testImplementation(libs.kaml)
    testImplementation(libs.jedis)
    testImplementation(libs.kotlinx.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
