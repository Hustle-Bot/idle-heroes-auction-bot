import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Nov 11: fake game (pure Kotlin) – builds images like the real auction house from the test images and
// reacts to taps like the game. Used by the tests here (bot against the fake game on the PC) and by the
// Android fake game (test in the emulator). Does NOT belong in the bot's app.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":kern"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The app uses the device's time zone; the tests run with German time (as the test pictures were taken).
    systemProperty("user.timezone", "Europe/Berlin")
    maxHeapSize = "2g"
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
