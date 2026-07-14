plugins {
    java
    application
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

// Keep the commit gate strong: `check` (hence the pre-commit hook) runs both lanes.
tasks.named("check") {
    dependsOn(integrationTest)
}

tasks.named<JavaExec>("run") {
    // java.library.path comes from applicationDefaultJvmArgs above.
    environment("SWI_HOME_DIR", swiHome())
    environment("PATH", nativePath)
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
