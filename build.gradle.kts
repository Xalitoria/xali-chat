import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

plugins {
    id("mc.xalitoria.minecraft")
}

subprojects {
    group = "mc.xalitoria"
    version = "0.5.0"
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
        register("ProtocolLib")
        register("Vault") { required = false }
        register("VaultUnlocked") { required = false }
        register("DiscordSRV-Ascension") { required = false }
        register("SuperVanish") { required = false }
        register("PremiumVanish") { required = false }
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
