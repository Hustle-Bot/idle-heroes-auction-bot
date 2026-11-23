import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Nov 11: the fake game as an Android app – only for the emulator test on GitHub (never on the phone).
// Shows the images from :attrappe-kern (real screenshot pieces) and reacts to taps like the game.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.hustlebot.ihauctionbot.attrappe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hustlebot.ihauctionbot.attrappe"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }

    // The real test images as assets (the fake game builds its images from them).
    sourceSets["main"].assets.srcDir("../testbilder")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":kern"))
    implementation(project(":attrappe-kern"))
}
