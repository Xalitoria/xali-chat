import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import java.net.URI
import java.security.MessageDigest

plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.paperyml)
    alias(libs.plugins.shadow)
}

group = "mc.xalitoria"
version = "0.3.0"

dependencies {
    paperLibrary(libs.xalicraft.util)
    paperLibrary(libs.bundles.database)
    paperLibrary(libs.bundles.exposed)

    compileOnly(libs.paper.api)
    compileOnly(libs.discordsrv)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.protocollib)
    compileOnly(libs.vanish.api)
    compileOnly(libs.vaultunlocked)
}

paper {
    prefix = "XaliChat"
    name = "XaliChat"
    author = "audizian"
    main = "mc.xalitoria.chat.paper.XaliChat"
    loader = "mc.xalitoria.chat.paper.XaliChatLoader"
    version = project.version.toString()
    description = project.description
    website = "https://xalitoria.com"
    foliaSupported = true
    apiVersion = "26.1"
    load = BukkitPluginDescription.PluginLoadOrder.POSTWORLD
    generateLibrariesJson = true
    serverDependencies {
        register("SignedVelocity")
        register("PlaceholderAPI")
        register("ProtocolLib")
        register("Vault") {
            required = false
        }
        register("VaultUnlocked") {
            required = false
        }
        register("DiscordSRV-Ascension") {
            required = false
        }
        register("SuperVanish") {
            required = false
        }
        register("PremiumVanish") {
            required = false
        }
    }
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

// Copies the shaded plugin into run/plugins, replacing the previous build; other plugins there are left alone.
val installPlugin = tasks.register<Copy>("installPlugin") {
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(runDir.dir("plugins"))
}

// Runs Paper from run/ on the JetBrains Runtime, whose DCEVM lets a rebuilt plugin be hot-swapped with far fewer limits.
val runServer = tasks.register<JavaExec>("runServer") {
    group = "run"
    description = "Runs a Paper $paperVersion server in chat/run on the JetBrains Runtime with enhanced class redefinition."
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
    generatePaperPluginDescription {
        useDefaultCentralProxy()
    }
    shadowJar {
        archiveClassifier.set("paper")
        archiveBaseName.set(rootProject.name + "-" + project.name)
        archiveVersion.set(version.toString())
        minimize()
        // Paper's library loader supplies the stdlib; a bundled copy would load in a second, incompatible classloader.
        dependencies {
            exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
            exclude(dependency("org.jetbrains:annotations"))
        }
    }
    build {
        dependsOn(shadowJar)
    }
}