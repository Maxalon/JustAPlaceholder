plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "mtg.judge"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.tukaani:xz:1.10")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.2")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("mtg.judge.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-Xss4m")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
