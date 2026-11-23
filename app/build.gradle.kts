import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version: name 1.<build run> (GitHub Actions run number, 1 for local builds), code 100 + build run (always rising).
val bauNummer: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val versionAbstand = 100

// The app: product flavor "oss" (dimension "ausgabe") – offline (no INTERNET permission), reports stay on the phone.

android {
    namespace = "io.github.hustlebot.ihauctionbot.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 30 // Android 11: from here on an accessibility service can take screenshots
        targetSdk = 35
        versionCode = versionAbstand + bauNummer
        versionName = "1.$bauNummer"
        // Name of the app and of its accessibility service in Android's lists.
        manifestPlaceholders["appLabel"] = "IH Auction Bot"
    }

    signingConfigs {
        // Own release key from environment variables (e.g. from GitHub secrets):
        // IH_SIGNING_KEYSTORE (path to a PKCS12 file), IH_SIGNING_PASSWORD, IH_SIGNING_ALIAS, IH_SIGNING_KEY_PASSWORD.
        val eigenerSchluessel = System.getenv("IH_SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() }
        if (eigenerSchluessel != null) {
            create("eigen") {
                storeFile = file(eigenerSchluessel)
                storePassword = System.getenv("IH_SIGNING_PASSWORD")
                keyAlias = System.getenv("IH_SIGNING_ALIAS") ?: "bot"
                keyPassword = System.getenv("IH_SIGNING_KEY_PASSWORD") ?: System.getenv("IH_SIGNING_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    flavorDimensions += "ausgabe"
    productFlavors {
        create("oss") {
            dimension = "ausgabe"
            applicationId = "io.github.hustlebot.ihauctionbot"
            // Release: only signed with the own key from the environment – otherwise the release APK stays unsigned.
            signingConfigs.findByName("eigen")?.let { signingConfig = it }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // The key comes from the flavor (see above).
        }
        // debug: Android's own debug key (emulator test).
    }

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
    // The bundled text recognition (ML Kit) was removed – the core reads the numbers itself.
    // That keeps the app at a few MB instead of ~50 MB.
    // ML Kit is back as a SECOND opinion for numbers the core cannot read – this time through
    // Google Play services ("unbundled"): Android downloads the recognition model itself, the app stays small.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Public app: FileProvider for sharing a report through Android's share sheet.
    "ossImplementation"("androidx.core:core:1.13.1")
}
