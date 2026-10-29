import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

plugins {
    id("mc.xalitoria.minecraft")
}

subprojects {
    group = "mc.xalitoria"
    version = "0.7.0"
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

paper {
    name = "XaliChat"
    main = "mc.xalitoria.chat.paper.XaliChat"
    prefix = "XaliChat"
    author = "audizian"
    website = "https://xalitoria.com"
    apiVersion = "26.1"
    load = Load.POSTWORLD
    foliaSupported = true
    relocateSources = true
    serverDependencies {
        register("Essentials") {
            load = RelativeLoad.AFTER
            required = false
        }
        register("SignedVelocity")
        register("PlaceholderAPI")
        register("ProtocolLib") { required = false }
        register("Vault") { required = false }
        register("VaultUnlocked") { required = false }
        register("DiscordSRV-Ascension") { required = false }
        register("SuperVanish") { required = false }
        register("PremiumVanish") { required = false }
    }
    // The dev build of ProtocolLib tracks new Minecraft versions, so it isn't pinned.
    server {
        plugin("ProtocolLib.jar", "https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar")
        plugin("PlaceholderAPI-2.12.3.jar", "https://cdn.modrinth.com/data/lKEzGugV/versions/pIvQcXW8/PlaceholderAPI-2.12.3.jar")
        plugin("VaultUnlocked-2.20.3.jar", "https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar")
        plugin("SignedVelocity-Paper-1.5.0.jar", "https://cdn.modrinth.com/data/7IbzD4Zm/versions/oEqZsNvv/SignedVelocity-Paper-1.5.0.jar")
    }
}

velocity {
    name = "XaliChat"
    id = "xalichat"
    main = "mc.xalitoria.chat.velocity.XaliChat"
    author = "audizian"
    url = "https://xalitoria.com"
    description = "Relays XaliChat's announcements, global broadcasts and global announcements between servers."
    relocateSources = true
    dependencies {
        register("premiumvanish") { required = false }
    }
}
