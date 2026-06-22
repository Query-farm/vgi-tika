plugins {
    java
    application
    // Fat/shaded JAR: `./gradlew shadowJar` -> build/libs/vgi-tika-<ver>-all.jar
    id("com.gradleup.shadow") version "9.4.2"
}

group = "farm.query"
version = "0.1.0-SNAPSHOT"

repositories {
    // mavenLocal is the fallback when the composite-build sibling (../../vgi-java)
    // isn't present (CI runner, container build). Populate it with
    // `make publish-deps-local` from vgi-java.
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all,-serial,-processing",
            "-parameters", // ScalarFn reflects parameter names off @Vector/@Const/@Setting
        )
    )
    options.encoding = "UTF-8"
}

val tikaVersion = "3.3.1"

dependencies {
    // Pin the version so mavenLocal resolves when the composite-build sibling
    // isn't present. The composite-build substitution (when ../../vgi-java
    // exists) ignores the declared version.
    implementation("farm.query:vgi-core:0.1.0-SNAPSHOT")
    implementation("farm.query:vgirpc:0.1.0-SNAPSHOT")
    implementation("farm.query:vgirpc-oauth:0.1.0-SNAPSHOT")

    // Apache Tika — Apache-2.0. tika-core is the streaming Parser/Metadata API;
    // tika-parsers-standard-package pulls PDFBox, POI, jackcess, language-detect,
    // and the Tesseract OCR parser (all Apache-2.0).
    implementation("org.apache.tika:tika-core:$tikaVersion")
    implementation("org.apache.tika:tika-parsers-standard-package:$tikaVersion")
    // Language detection (Optimaize) for detect_lang.
    implementation("org.apache.tika:tika-langdetect-optimaize:$tikaVersion")

    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Generate real PDF/DOCX fixtures in tests (these ride in transitively at
    // runtime via tika-parsers-standard-package; declare them for test compile).
    testImplementation("org.apache.pdfbox:pdfbox:3.0.3")
    testImplementation("org.apache.poi:poi-ooxml:5.3.0")
}

application {
    mainClass.set("farm.query.vgi.tika.Main")
    applicationDefaultJvmArgs = listOf("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

tasks.shadowJar {
    archiveBaseName.set("vgi-tika")
    archiveClassifier.set("all")
    // Tika discovers parsers/detectors/language-profiles via
    // META-INF/services/* SPI files; many jars define the same service
    // interface, so the entries MUST be concatenated rather than overwritten.
    mergeServiceFiles()
    // PDFBox / FontBox ship overlapping resource files; keep them.
    manifest {
        attributes(
            "Main-Class" to "farm.query.vgi.tika.Main",
            "Multi-Release" to "true",
        )
    }
}

// Make `build` produce the fat jar.
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
