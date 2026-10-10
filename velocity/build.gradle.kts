import xalitoria.repo.kotlinx
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.shadow)
}

// Declared with api, so the proxy downloads them when the plugin loads instead of the jar carrying them (:chat:common
// leaves kaml and kotlinx.serialization to whoever packages it).
dependencies {
    implementation(project(":chat:common"))
    api(libs.kaml)
    api(kotlinx("serialization-json"))
    compileOnly(libs.velocity.api)
}

val velocityVersion = "4.2.0"
val velocityBuild = 30
val velocitySha256 = "35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8"
val runDir = layout.projectDirectory.dir("run")
val velocityJar = runDir.file("velocity-$velocityVersion-$velocityBuild.jar")

// Fetches the pinned Velocity build into run/; skipped when the file is already there.
val downloadVelocity = tasks.register("downloadVelocity") {
    outputs.file(velocityJar)
    // Locals only: the action can't reference script-level values under the configuration cache.
    val target = velocityJar.asFile
    val url = "https://fill-data.papermc.io/v1/objects/$velocitySha256/velocity-$velocityVersion-$velocityBuild.jar"
    val expected = velocitySha256
    doLast {
        if (target.exists()) return@doLast
        target.parentFile.mkdirs()
        URI.create(url).toURL().openStream().use { it.copyTo(target.outputStream()) }
        val actual = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            error("Velocity checksum mismatch: expected $expected, got $actual")
        }
    }
}

// Copies the shaded plugin into run/plugins, replacing earlier builds of it.
val installPlugin = tasks.register<Copy>("installPlugin") {
    val jarPrefix = "${rootProject.name}-chat-"
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(runDir.dir("plugins"))
    doFirst {
        runDir.dir("plugins").asFile.listFiles { f -> f.name.startsWith(jarPrefix) }?.forEach { it.delete() }
    }
}

val runProxy = tasks.register<JavaExec>("runProxy") {
    group = "run"
    description = "Runs a Velocity $velocityVersion proxy in chat/velocity/run with this plugin installed."
    dependsOn(downloadVelocity, installPlugin)
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    workingDir = runDir.asFile
    classpath = files(velocityJar)
    jvmArgs("-Xmx512M")
    standardInput = System.`in`
}

tasks {
    jar { enabled = false }
    build {
        dependsOn(shadowJar)
    }
}
