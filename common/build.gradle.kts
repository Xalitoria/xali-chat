import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The wire format between the Paper and Velocity plugins. It uses no platform API, so both jars bundle it, and it
// targets Java 17 because Velocity may run on an older JVM than Paper.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
