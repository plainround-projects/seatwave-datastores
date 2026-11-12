plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Expected refusals as values with sealed error types (code guidelines, rule 47).
    implementation(libs.result4j)
    // The JDK has no Redis client; one Lettuce connection is thread-safe and serves every thread.
    implementation(libs.lettuce.core)
    // Lettuce's own dependency asks for SLF4J 1.7, which Logback 1.6 cannot serve; this pins 2.x.
    runtimeOnly(libs.slf4j.api)
    // Lettuce and Netty log through SLF4J and need a backend (src/main/resources/logback.xml).
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // The stack test waits for Redis to expire a key; Awaitility polls instead of sleeping.
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// ./gradlew check needs no Docker: AccessPatternTest and the other unit tests run on the
// in-memory model. The tests tagged "stack" need the lab Redis; scripts/stack.sh test starts it,
// runs them, and brings it down.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the lab Redis; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // The password files are read relative to the project folder.
    workingDir = projectDir
    useJUnitPlatform {
        includeTags("stack")
    }
    // Redis is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The programs (*Cli) are run by hand and their output is printed in the README and the module;
// RedisSessionStore runs only against Redis, so the stack test run measures it, not check.
val programs = listOf("**/*Cli*")
val redisOnly = programs + listOf("**/sessions/RedisSessionStore*")

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(redisOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register<JacocoReport>("stackCoverageReport") {
    group = "verification"
    description = "Writes the line coverage of the unit and stack test runs together."
    dependsOn(tasks.test, tasks.named("stackTest"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    reports {
        xml.required = true
    }
}

tasks.register<JacocoCoverageVerification>("stackCoverage") {
    group = "verification"
    description = "Checks line coverage of all main code but the programs over both test runs."
    dependsOn(tasks.named("stackCoverageReport"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// One task per program, so a learner runs one command per example (code guidelines, rule 16).
// The working folder is the project, where .secrets/redis is.
fun registerProgram(taskName: String, mainClassName: String, text: String) =
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = text
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
    }

registerProgram("accessPatterns", "io.plainround.seatwave.datastores.sessions.AccessPatternCheckCli", "Every access pattern once, with its commands (--redis: on the lab Redis)")
registerProgram("loadTest", "io.plainround.seatwave.datastores.sessions.SessionLoadTestCli", "Open-loop load test at the estimated peak (--store=model: on the model)")
registerProgram("hotPartitions", "io.plainround.seatwave.datastores.partitions.HotPartitionCli", "Key choice against 16 partitions at the on-sale peak")
registerProgram("wideColumn", "io.plainround.seatwave.datastores.history.WideColumnDemoCli", "Booking history in a wide-column model")
registerProgram("catalogDocument", "io.plainround.seatwave.datastores.catalog.CatalogDocumentCli", "The catalog document of one event")
registerProgram("sizing", "io.plainround.seatwave.datastores.estimate.SizingCli", "Section 7's sizing of the six workloads")
