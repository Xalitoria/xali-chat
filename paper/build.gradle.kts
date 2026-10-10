import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":chat:common"))

    // Libraries the server downloads when the plugin loads (see loader.json); nothing declared here is shaded.
    api(libs.xalicraft.util)
    api(libs.bundles.database)
    api(libs.bundles.exposed)

    compileOnly(libs.paper.api)
    compileOnly(libs.discordsrv)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.protocollib)
    compileOnly(libs.vanish.api)
    compileOnly(libs.vaultunlocked)
    testImplementation(libs.xalicraft.util)
    testImplementation(libs.paper.api)
}

val paperVersion = "26.2"
val paperBuild = 129
val paperSha256 = "b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083"
val runDir = layout.projectDirectory.dir("run")
val paperJar = runDir.file("paper-$paperVersion-$paperBuild.jar")

// Fetches the pinned Paper build into run/; skipped when the file is already there.
val downloadPaper = tasks.register("downloadPaper") {
    outputs.file(paperJar)
    // Locals only: the action can't reference script-level values under the configuration cache.
    val target = paperJar.asFile
    val eula = runDir.file("eula.txt").asFile
    val url = "https://fill-data.papermc.io/v1/objects/$paperSha256/paper-$paperVersion-$paperBuild.jar"
    val expected = paperSha256
    doLast {
        eula.parentFile.mkdirs()
        eula.writeText("eula=true\n")
        if (target.exists()) return@doLast
        URI.create(url).toURL().openStream().use { it.copyTo(target.outputStream()) }
        val actual = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            error("Paper checksum mismatch: expected $expected, got $actual")
        }
    }
}

// Dependencies the plugin declares, fetched once into run/plugins (unpinned: the dev build tracks new Minecraft versions).
val dependencyPlugins = mapOf(
    "ProtocolLib.jar" to "https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar",
    "PlaceholderAPI-2.12.3.jar" to "https://cdn.modrinth.com/data/lKEzGugV/versions/pIvQcXW8/PlaceholderAPI-2.12.3.jar",
    "VaultUnlocked-2.20.3.jar" to "https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar",
    "SignedVelocity-Paper-1.5.0.jar" to "https://cdn.modrinth.com/data/7IbzD4Zm/versions/oEqZsNvv/SignedVelocity-Paper-1.5.0.jar",
)
val downloadDependencies = tasks.register("downloadDependencies") {
    val pluginsDir = runDir.dir("plugins").asFile
    val downloads = dependencyPlugins
    doLast {
        pluginsDir.mkdirs()
        downloads.forEach { (name, url) ->
            val target = pluginsDir.resolve(name)
            if (!target.exists()) URI.create(url).toURL().openStream().use { it.copyTo(target.outputStream()) }
        }
    }
}

// Installs the shaded plugin into run/plugins. A previous runServer instance may still be running (and holding
// its jar open) when this reruns, so the file is written under a temp name and moved into place atomically:
// an open ZipFile keeps reading the old (unlinked) inode's bytes rather than a half-old-half-new mix, avoiding
// a NoClassDefFoundError for a class the old process hadn't lazily loaded yet (e.g. a suspend fun's continuation)
// once it does. Jars from earlier versions are removed so Paper never sees two XaliChat plugins.
val installPlugin = tasks.register("installPlugin") {
    val source = tasks.shadowJar.flatMap { it.archiveFile }
    val pluginsDir = runDir.dir("plugins")
    val jarPrefix = "${rootProject.name}-chat-"
    inputs.file(source)
    outputs.dir(pluginsDir)
    doLast {
        val sourceFile = source.get().asFile
        val targetDir = pluginsDir.asFile.apply { mkdirs() }
        targetDir.listFiles { f -> f.name.startsWith(jarPrefix) && f != sourceFile }
            ?.forEach { it.delete() }
        val target = targetDir.resolve(sourceFile.name)
        val temp = File.createTempFile("${sourceFile.name}.", ".tmp", targetDir)
        sourceFile.copyTo(temp, overwrite = true)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

// Runs Paper from run/ on the JetBrains Runtime, whose DCEVM lets a rebuilt plugin be hot-swapped with far fewer limits.
val runServer = tasks.register<JavaExec>("runServer") {
    group = "run"
    description = "Runs a Paper $paperVersion server in chat/paper/run on the JetBrains Runtime with enhanced class redefinition."
    dependsOn(downloadPaper, downloadDependencies, installPlugin)
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.JETBRAINS
    }
    workingDir = runDir.asFile
    classpath = files(paperJar)
    jvmArgs("-Xmx2G", "-XX:+AllowEnhancedClassRedefinition", "-Dcom.mojang.eula.agree=true",
        // Attach IntelliJ (Remote JVM Debug, port 5005) to hot-swap rebuilt classes into the running server.
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
    args("--nogui")
    standardInput = System.`in`
}

tasks {
    jar { enabled = false }
    shadowJar {
        minimize()
    }
    build {
        dependsOn(shadowJar)
    }
}
