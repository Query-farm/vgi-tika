plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vgi-tika"

// Prefer the developer's local composite build of vgi-java when checked out as
// a sibling at ../../vgi-java (i.e. ~/vgi-java), otherwise the `farm.query:*`
// coordinates resolve from mavenLocal (populate it with `make publish-deps-local`
// in vgi-java, or the CI checkout below).
//
// The `farm.query:vgi-core` artifact is produced by vgi-java's `:vgi` subproject
// (its archivesName is `vgi-core`). Opt in explicitly via VGI_JAVA_COMPOSITE=1 —
// the composite build couples to vgi-java's internal project layout, so the
// default path here is the stable mavenLocal/published-artifact route.
val useComposite = System.getenv("VGI_JAVA_COMPOSITE") == "1"
val vgiJavaDir = file("../../vgi-java")
if (useComposite && vgiJavaDir.isDirectory) {
    includeBuild(vgiJavaDir) {
        dependencySubstitution {
            substitute(module("farm.query:vgi-core")).using(project(":vgi"))
        }
    }
}
