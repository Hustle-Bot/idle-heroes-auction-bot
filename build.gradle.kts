// Gemeinsame Werkzeuge für beide Teile. Das Android-Werkzeug (AGP) wird nur geladen,
// wenn ein Android-SDK da ist – so lässt sich der Kern auch ohne Android bauen.
buildscript {
    val androidDa: Boolean = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootDir.resolve("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

    repositories {
        if (androidDa) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
        if (androidDa) classpath("com.android.tools.build:gradle:8.10.1")
    }
}
