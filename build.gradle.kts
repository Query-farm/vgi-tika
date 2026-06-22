import java.util.zip.ZipFile

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
    // Some Tika parser modules use the Log4j 2 API. Without a Log4j provider,
    // Log4j's StatusLogger prints "could not find a logging provider" to
    // System.out — which corrupts the stdio Arrow-IPC transport and hangs the
    // worker. Bridge Log4j 2 -> SLF4J -> slf4j-simple (stderr) so NOTHING ever
    // writes to stdout. (slf4j-simple defaults all output to System.err.)
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.24.3")

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

// Concatenate every dependency's META-INF/services/* SPI file into a generated
// resources dir. Tika 3.x splits parsers/detectors across ~27 tika-parser-*-module
// jars that each declare the SAME service interface; shadow's mergeServiceFiles()
// alone collapsed them to a single entry (only CompositeExternalParser survived),
// which made body extraction return empty text while MIME detection still worked.
// Pre-merging into project resources is deterministic and version-agnostic.
val generatedSpiDir = layout.buildDirectory.dir("generated/spi")
val generateMergedSpi = tasks.register("generateMergedSpi") {
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    outputs.dir(generatedSpiDir)
    doLast {
        val servicesByName = linkedMapOf<String, LinkedHashSet<String>>()
        runtime.get().files.filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zf ->
                zf.entries().asSequence()
                    .filter { e ->
                        !e.isDirectory && e.name.startsWith("META-INF/services/") &&
                            e.name.removePrefix("META-INF/services/").isNotEmpty()
                    }
                    .forEach { e ->
                        val svc = e.name.removePrefix("META-INF/services/")
                        val lines = zf.getInputStream(e).bufferedReader().readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                        if (lines.isNotEmpty()) {
                            servicesByName.getOrPut(svc) { linkedSetOf() }.addAll(lines)
                        }
                    }
            }
        }
        val outRoot = generatedSpiDir.get().dir("META-INF/services").asFile
        outRoot.deleteRecursively()
        outRoot.mkdirs()
        servicesByName.forEach { (svc, impls) ->
            outRoot.resolve(svc).writeText(impls.joinToString("\n", postfix = "\n"))
        }
        logger.lifecycle("generateMergedSpi: merged ${servicesByName.size} service files "
            + "(Parser impls=${servicesByName["org.apache.tika.parser.Parser"]?.size ?: 0})")
    }
}

tasks.shadowJar {
    archiveBaseName.set("vgi-tika")
    archiveClassifier.set("all")
    dependsOn(generateMergedSpi)
    // The pre-merged SPI files (see generateMergedSpi) override the per-jar ones;
    // mergeServiceFiles() still concatenates anything they miss.
    from(generatedSpiDir)
    mergeServiceFiles()
    // PDFBox / FontBox ship overlapping resource files; keep them.
    manifest {
        attributes(
            "Main-Class" to "farm.query.vgi.tika.Main",
            "Multi-Release" to "true",
            // Arrow's off-heap MemoryUtil needs java.nio reflectively opened. Bake
            // it into the manifest so a bare `java -jar vgi-tika-all.jar` works as a
            // VGI LOCATION without the caller having to pass --add-opens.
            "Add-Opens" to "java.base/java.nio",
        )
    }
}

// Make `build` produce the fat jar.
tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

// Regenerate the committed SQL E2E fixtures (test/sql/data/*) from the same
// PDFBox/POI builders the JUnit tests use. The Makefile `test-sql` target runs
// this before haybarn-unittest so fixtures are reproducible from source rather
// than opaque committed binaries.
tasks.register<JavaExec>("generateSqlFixtures") {
    group = "verification"
    description = "Generate test/sql/data fixtures (hello.pdf, multipage.pdf, hello.docx, ...)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("farm.query.vgi.tika.SqlFixtureGenerator")
    args(layout.projectDirectory.dir("test/sql/data").asFile.absolutePath)
}
