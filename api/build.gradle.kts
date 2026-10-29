// What other Paper plugins compile against to take part in XaliChat. The server provides it at run time, from the
// XaliChat plugin, so a plugin that uses it lists XaliChat as a dependency and does not bundle this.

// Gradle tells projects apart by group and name, and `:menu:api` is also `mc.xalitoria:api`; with both on a classpath
// (a plugin that uses the two) one would stand in for the other.
group = "mc.xalitoria.chat"

dependencies {
    compileOnly(libs.paper.api)
}
