// What other Paper plugins compile against to take part in XaliChat. The server provides it at run time, from the
// XaliChat plugin, so a plugin that uses it lists XaliChat as a dependency and does not bundle this.

plugins {
    `maven-publish`
}

dependencies {
    compileOnly(libs.paper.api)
}
