import mc.xalitoria.gradle.Load

plugins {
    id("mc.xalitoria.minecraft")
}

// :chat is only a parent: the Paper plugin, the Velocity plugin and the protocol they share are its subprojects,
// and they are released together under one version.
subprojects {
    group = "mc.xalitoria"
    version = "0.5.0"
}

// The plugins are described here, once, and the :paper and :velocity subprojects pick their block up.
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
    main = "mc.xalitoria.chat.velocity.XaliChatVelocity"
    author = "audizian"
    url = "https://xalitoria.com"
    description = "Relays XaliChat's announcements, global broadcasts and global announcements between servers."
    relocateSources = true
}
