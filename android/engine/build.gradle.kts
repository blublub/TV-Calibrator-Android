// The calibration engine: a Kotlin port of CalibrationKit (Swift), with no Android dependencies.
// Its tests check it against the same fixtures the Swift tests use (recorded from the Python engine),
// read straight from ../../CalibrationKit so there is only one copy of them.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty(
        "fixtures",
        rootProject.projectDir.resolve("../CalibrationKit/Tests/CalibrationKitTests/Fixtures").canonicalPath,
    )
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
