// Idle-Heroes-Auktionsbot – zwei Teile:
//   :kern – die Logik (Regeln, Gebote, Stopp, Ablauf). Reines Kotlin, läuft und testet überall.
//   :app  – die Android-App (Bedienungshilfe, schwebender Knopf, Einstellungen).
// :app wird nur eingebunden, wenn ein Android-SDK da ist (auf GitHub ja, in der Cloud-Sitzung nicht).

rootProject.name = "idle-heroes-bot"

val androidDa: Boolean = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

dependencyResolutionManagement {
    repositories {
        if (androidDa) google()
        mavenCentral()
    }
}

include(":kern")


// 11.11.: Spiel-Attrappe für den Test im Emulator – nur mit -Pattrappe=1 (bzw. IH_ATTRAPPE=1), damit sie den
// normalen Bau der App nie stören kann.
val mitAttrappe: Boolean = providers.gradleProperty("attrappe").orNull == "1" || System.getenv("IH_ATTRAPPE") == "1"
if (mitAttrappe) include(":attrappe-kern")
if (androidDa) {
    include(":app")
    if (mitAttrappe) include(":attrappe")
} else {
    println("Kein Android-SDK gefunden – es wird nur der Kern (:kern) gebaut und getestet.")
}
