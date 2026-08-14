import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.1.2"
}

group = "dev.example.tiktok"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
}

// This block wires your jar up so Lavalink can discover + load it as a plugin.
lavalinkPlugin {
    apiVersion = "4.0.0"
    // serverVersion = "4.2.0" // uncomment + pin if you want to test against a specific Lavalink release
}

dependencies {
    compileOnly("dev.arbjerg.lavalink:plugin-api:4.0.0")

    // JSON parsing for the TikTok/tikwm API responses
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // HTTP client used to talk to TikTok's resolver endpoints
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
