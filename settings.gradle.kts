plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vgi-tika"

// The VGI Java SDK (farm.query:vgi, farm.query:vgirpc) resolves from Maven
// Central — see build.gradle.kts. No composite build or mavenLocal needed.
