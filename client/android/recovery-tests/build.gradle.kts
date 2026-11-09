plugins {
    kotlin("jvm") version "2.1.0"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.setSrcDirs(listOf(
            "../wireguard/src/main/kotlin",
            "../protocolApi/src/main/kotlin",
            "../utils/src/main/kotlin",
        ))
        kotlin.include("**/recovery/*.kt", "ProtocolState.kt", "net/PhysicalNetworkTracker.kt")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
