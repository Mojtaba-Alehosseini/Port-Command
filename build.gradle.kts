plugins {
    java
    application
    jacoco
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        // Hard rule (CLAUDE.md §1): Java 21 LTS. PATH `java` may be 17 — the
        // toolchain pins 21 regardless and Gradle locates the matching JDK.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Without this, javac falls back to the platform default charset to read .java source
// files. On Windows that default is a single-byte charset (e.g. Windows-1252), so any
// literal non-ASCII character in source (€, ±, emoji — task 10's Assistant chat text)
// gets silently misdecoded into mojibake in the compiled class's string constants
// (confirmed by codepoint inspection: € became U+00E2 U+201A U+00AC instead of U+20AC).
// Source files on disk are UTF-8; tell javac to read them as UTF-8 explicitly.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// --- Native dependency resolution (SWI-Prolog / JADE) ----------------------
// SWI-Prolog ships jpl.jar (the JPL Java API) and the native bridge
// (jpl.dll / libjpl.so / libjpl.dylib) inside its install tree. We resolve
// them from SWI_HOME_DIR so nothing machine-specific is hard-coded.

fun osName(): String = System.getProperty("os.name").lowercase()
fun isWindows(): Boolean = osName().contains("win")
fun isMac(): Boolean = osName().contains("mac")

fun swiHome(): String =
    System.getenv("SWI_HOME_DIR") ?: when {
        isWindows() -> "C:/Program Files/swipl"
        isMac()     -> "/opt/homebrew/lib/swipl"
        else        -> "/usr/lib/swi-prolog"
    }

fun jplJar(): String = "${swiHome()}/lib/jpl.jar"

// Directory holding the native JPL bridge (+ libswipl) for java.library.path.
fun swiNativeDir(): String = when {
    isWindows() -> "${swiHome()}/bin"
    isMac()     -> "${swiHome()}/lib"
    else        -> "${swiHome()}/lib/x86_64-linux"
}

val jadeJar = "lib/jade-4.6.0.jar"

dependencies {
    // JADE 4.6.0 — not on Maven Central; staged manually in lib/ (git-ignored: license).
    implementation(files(jadeJar))
    // JPL 7 — bundled with SWI-Prolog 10; resolved from SWI_HOME_DIR.
    implementation(files(jplJar()))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // WordNet lexical semantics (task 16; PROJECT_DEFINITION.md §2.2 "Lexical semantics · WordNet").
    // extjwnl is the API; extjwnl-data-wn31 is the WordNet 3.1 database as a resource jar, which is
    // what lets Dictionary.getDefaultResourceInstance() self-configure off the classpath with no
    // external WordNet install or properties file (verified by running). The two artefacts are
    // versioned INDEPENDENTLY — 2.0.5 and 1.2 are each the latest of their own line (checked
    // against Maven Central's maven-metadata.xml, not assumed).
    implementation("net.sf.extjwnl:extjwnl:2.0.5")
    runtimeOnly("net.sf.extjwnl:extjwnl-data-wn31:1.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Mockito: test-only NegotiationEngine mock for the walk-in vessel IT (task 07; the real engine is task 15).
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("it.unige.portcommand.Main")
    // The embedded SWI-Prolog native lib must be discoverable at runtime.
    // On Windows, %SWI_HOME_DIR%\bin must also be on PATH so the OS loader can
    // resolve jpl.dll's dependency libswipl.dll (verified present in env).
    applicationDefaultJvmArgs = listOf("-Djava.library.path=${swiNativeDir()}")
}

// Make the embedded SWI-Prolog engine loadable in forked JVMs (test + run):
//  - java.library.path lets the JVM find jpl.dll
//  - native dir on PATH lets the OS loader resolve jpl.dll's dependency libswipl.dll
//  - SWI_HOME_DIR lets SWI-Prolog locate its home / boot file
// We set these explicitly because the Gradle daemon does not reliably inherit
// the interactive shell's environment.
val nativePath: String = "${swiNativeDir()}${System.getProperty("path.separator")}${System.getenv("PATH") ?: ""}"

tasks.withType<Test>().configureEach {
    // Native config applies to BOTH the unit (test) and integration (integrationTest) lanes.
    jvmArgs("-Djava.library.path=${swiNativeDir()}")
    environment("SWI_HOME_DIR", swiHome())
    environment("PATH", nativePath)
    // Task 17: structurally enforces "no test may require a display" rather than only
    // promising it — java.awt.Window/Frame/JFrame construction throws HeadlessException
    // under this flag, so a future test that accidentally tries to show a real window
    // fails loudly instead of silently depending on whatever display happens to be
    // available on the machine running `gradlew check`. EventBus's EDT dispatch
    // (SwingUtilities.invokeLater/invokeAndWait) and plain JPanel construction are NOT
    // gated by headless mode — only top-level Window/Frame realization is — so this does
    // not affect the EventBusTest / GUI panel lifecycle tests. Does NOT apply to the
    // `run` task, so `gradlew run --args="--gui"` still shows a real window normally.
    jvmArgs("-Djava.awt.headless=true")
}

// Fast lane: unit tests only.
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

// Integration lane: JADE Main Container / JPL boot tests. Forked per class so
// JADE's Runtime singleton (and the 1099/18099 port binds) can't leak across IT classes.
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests (JADE / JPL boot)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    setForkEvery(1L)
    shouldRunAfter(tasks.test)
}

// --- Coverage gate (task 25) ------------------------------------------------
// planning/25: "≥ 60% line coverage on src/main/java, EXCLUDING it.unige.portcommand.gui.**".
// Swing painting code is manually verified via the --gui visual checkpoints, so it is not
// counted; every non-GUI package is.
//
// Both lanes contribute execution data. The `jacoco` plugin instruments EVERY Test task, so
// `test` writes build/jacoco/test.exec and `integrationTest` writes build/jacoco/integrationTest.exec;
// the report and the verification read both. Measuring only the unit lane would under-count the
// agent/behaviour code that only the JADE integration tests can reach.
jacoco {
    toolVersion = "0.8.12"
}

// The GUI exclude, stated ONCE and shared by the report and the gate so they can never drift.
// NB: sourceSets.main.output.classesDirs is a collection of DIRECTORIES, so a path filter over it
// excludes nothing — the exclude has to be applied to the class-file TREE underneath them.
val coverageClassDirs: FileCollection = files(
    sourceSets.main.get().output.classesDirs.files.map { dir ->
        fileTree(dir) { exclude("it/unige/portcommand/gui/**") }
    }
)
val coverageExecData: FileCollection = fileTree(layout.buildDirectory) { include("jacoco/*.exec") }

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(coverageExecData)
    classDirectories.setFrom(coverageClassDirs)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    executionData.setFrom(coverageExecData)
    classDirectories.setFrom(coverageClassDirs)
    // JaCoCo SKIPS a limit whose ratio is NaN, so an empty classDirectories — a typo'd exclude, a
    // moved output dir — would make this gate pass while measuring nothing at all. Fail loudly
    // instead. 200 is well under the ~279 non-GUI classes and well over any plausible accident.
    doFirst {
        val counted = coverageClassDirs.files.count { it.name.endsWith(".class") }
        require(counted > 200) {
            "coverage gate would measure only $counted classes — the GUI exclude or the class output " +
                "directory is wrong, and the gate would pass vacuously."
        }
    }
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

// Keep the commit gate strong: `check` (hence the pre-commit hook) runs both lanes and the
// coverage gate.
tasks.named("check") {
    dependsOn(integrationTest, tasks.named("jacocoTestCoverageVerification"))
}

tasks.named<JavaExec>("run") {
    // java.library.path comes from applicationDefaultJvmArgs above.
    environment("SWI_HOME_DIR", swiHome())
    environment("PATH", nativePath)

    // `./gradlew run -PjadePort=11099` — the escape hatch for a machine where something else
    // owns RMI port 1099 (start.sh --jade-port / start.bat -JadePort pass this).
    // It has to be a PROJECT property forwarded as a systemProperty: a plain `-Djade.main.port=…`
    // on the gradlew command line sets it on the Gradle DAEMON, and a JavaExec fork does not
    // inherit the daemon's system properties, so the app would never see it and would silently
    // bind 1099 anyway. Same trap as the -Pjfr note below.
    (project.findProperty("jadePort") as String?)?.let { systemProperty("jade.main.port", it) }

    // --- Task 26: opt-in JVM Flight Recorder for the profiling run --------------------
    // `./gradlew run -Pjfr` (what profile.sh / profile.bat invoke) records the APPLICATION
    // JVM to logs/profile.jfr. planning/26 §26.4 sketched
    // `-Dorg.gradle.jvmargs="-XX:StartFlightRecording=..."` — that is wrong and was corrected
    // here (dated reconciliation 2026-07-27): org.gradle.jvmargs configures the Gradle DAEMON,
    // not the forked JavaExec the game runs in, so it would have profiled the build tool and
    // produced a recording with no game frames in it at all.
    //
    // -Pjfr.seconds overrides the 900 s (15-minute) default, which is the acceptance
    // criterion's session length. The recording is written on dump/exit; the JVM must be shut
    // down cleanly (close the window — the GUI's handler calls System.exit) or the file is
    // truncated, hence dumponexit.
    if (project.hasProperty("jfr")) {
        val seconds = (project.findProperty("jfr.seconds") as String?) ?: "900"
        val jfrFile = layout.projectDirectory.file("logs/profile.jfr").asFile
        jvmArgs(
            "-XX:StartFlightRecording=duration=${seconds}s,filename=${jfrFile.absolutePath}," +
                "settings=profile,dumponexit=true,name=port-command",
            "-XX:FlightRecorderOptions=stackdepth=128",
        )
        // mkdirs at EXECUTION time, not configuration time: touching the filesystem while the
        // build script is being configured breaks the configuration cache (adversarial review).
        doFirst {
            jfrFile.parentFile.mkdirs()
            logger.lifecycle("JFR: recording ${seconds}s to ${jfrFile.absolutePath}")
        }
    }
}

// Phase-0 diagnostic: resolve + verify the three native artefacts. Fails fast
// with an actionable message if any is missing.
tasks.register("printEnv") {
    group = "help"
    description = "Prints and verifies the resolved JADE jar, JPL jar, and native lib dir."
    doLast {
        val jade = file(jadeJar)
        val jpl = file(jplJar())
        val nativeDir = file(swiNativeDir())
        println("SWI_HOME_DIR : ${System.getenv("SWI_HOME_DIR") ?: "(unset -> built-in default)"}")
        println("JADE jar     : ${jade.absolutePath}  exists=${jade.exists()}")
        println("JPL  jar     : ${jpl.absolutePath}  exists=${jpl.exists()}")
        println("native dir   : ${nativeDir.absolutePath}  exists=${nativeDir.exists()}")
        val missing = listOf(jade, jpl, nativeDir).filterNot { it.exists() }.map { it.absolutePath }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing native artefact(s): ${missing.joinToString("; ")}. " +
                "Check the SWI-Prolog 10.0.2 install and that lib/jade-4.6.0.jar is staged."
            )
        }
        println("printEnv: all native artefacts present.")
    }
}
