plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":chat:common"))
    // Other plugins use the API's classes from this jar, whether or not the plugin itself does.
    implementation(project(":chat:api"))

    // Libraries the server downloads when the plugin loads (see loader.json); nothing declared here is shaded.
    api(libs.xalicraft.util)
    api(libs.bundles.database)
    api(libs.bundles.exposed)
    api(libs.jedis)

    compileOnly(libs.paper.api)
    compileOnly(libs.discordsrv)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.plan)
    compileOnly(libs.protocollib)
    compileOnly(libs.vanish.api)
    compileOnly(libs.vaultunlocked)
    testImplementation(libs.xalicraft.util)
    testImplementation(libs.paper.api)
    testImplementation(libs.plan)
}

tasks {
    jar { enabled = false }
    shadowJar {
        minimize {
            exclude(project(":chat:api"))
        }
    }
    build {
        dependsOn(shadowJar)
    }
}
