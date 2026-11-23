# Security

IH Auction Bot is an **accessibility service**. Such an app is very powerful, so you should not simply trust it:
this page lists what the **public ("oss") build** does, why it needs each permission, and **how you can verify every claim yourself**.

> Disclaimer: unofficial project, not affiliated with the game's publisher. Using bots probably violates the game's
> Terms of Service. No warranty. See [README.md](README.md).

## Permissions of the public build

| Permission / capability | Why it is needed | Declared in |
|---|---|---|
| **Accessibility service** (`BIND_ACCESSIBILITY_SERVICE`), with `canTakeScreenshot`, `canPerformGestures`, `canRetrieveWindowContent`, `canRequestFilterKeyEvents` | **Screenshots** to see the game (Android 11+), **taps and swipes** to play, **typing** into the *game's own* input fields (price filter, secondary password) through `ACTION_SET_TEXT`, the **volume keys** for the emergency stop, checking **which app is in front**, and locking the screen at the end of a run. **Limited to Idle Heroes in two layers:** Android itself only delivers the game's accessibility events
(`android:packageNames="com.droidhang.ad"`); screenshots and taps are not limited by Android, so the app's own code limits
them (no tap, no typing and no kept screenshot unless the game is in front, `SpielVorne.kt`, tested). You switch it on yourself, after the app explained it and you tapped **Agree**; without that OK the service does nothing. | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/bedienungshilfe.xml` |
| **Volume keys** | Key filtering is switched on **only while a run is active** and off again when it stops. Android hands every hardware key to such a filter; the app only looks at volume up/down, never swallows a key and never logs key codes. | `BotDienst.kt` (`onKeyEvent`, `setServiceInfo`) |
| **Floating IH button / panel** | Drawn as an *accessibility overlay* (`TYPE_ACCESSIBILITY_OVERLAY`), which needs **no** extra "draw over other apps" permission. The optional pocket-mode shield uses the same type and is visible (coloured frame and label). | `SchwebeFenster.kt`, `TaschenSchild.kt` |
| **Notifications** | **None.** | - |
| `<queries>` for `com.droidhang.ad` | Lets **START** open the game. Not a permission. | `app/src/main/AndroidManifest.xml` |
| `ACCESS_NETWORK_STATE` | Added automatically by the **Google ML Kit** library (used for a second opinion on numbers). The app's own code does not use it. | merged manifest (see below) |
| **`INTERNET`** | **Not present.** The oss manifest explicitly removes it (`tools:node="remove"`), so even a library cannot add it, and the CI build fails if it ever appears. | `app/src/oss/AndroidManifest.xml`, `.github/workflows/build.yml` |
| FileProvider (not exported) | Lets Android's share sheet read exactly one report ZIP, only when you tap *Share latest report*. | `app/src/oss/AndroidManifest.xml`, `app/src/oss/res/xml/berichte_teilen.xml` |

Not requested: contacts, SMS, phone, location, camera, microphone, storage, install packages, boot, background location.

Because the app has no `INTERNET` permission, **Android itself prevents it from sending anything over the network**.

**About ML Kit (optional, off by default):** the setting **Google text recognition (second reader for numbers)** is
**off** in this app; the bot reads the numbers itself. Only if you switch it on does the app start ML Kit (`MlKit.initialize`) and create Google's *unbundled*
ML Kit client; ML Kit's start-up provider (`MlKitInitProvider`) is removed from the public app's manifest, and the CI
build prints the merged manifest's components and fails if it comes back. Its code and model come from **Google Play services**, which has its own network access, downloads the model
on first use and, according to Google's ML Kit terms, may send usage statistics to Google; the missing `INTERNET`
permission of this app does not stop that. It only ever gets small crops of numbers from the game. Security scanners may
report it as dynamic code loading; it is Google's library, not code of this project.

## What the app never does

1. **No network access, no data collection, no analytics, no ads, no accounts.**
2. **No self-update or self-install.** The *Check for updates* button only opens
   `https://github.com/Hustle-Bot/idle-heroes-auction-bot/releases` in your browser.
3. **No uploads and no screen recording** (no video, no MediaProjection). There is no upload or self-update code in this
   repository.
4. **No reading of other apps.** The service is limited to Idle Heroes. To know which app is in front it uses only
   Android's window list (type, size, layer), the game's own window ids (learned from the game's events) and a flag of
   this app's own screen; it never reads the content of another app's window. Typing reads and fills only windows known
   to be the game's (`TextEingabe.kt`).
   **Fail closed:** the window list is checked again right before every tap or swipe; no tap, no typing and no screenshot
   unless the game is in front, and no tap on a spot covered by another app's window (`SpielVorne.punktFrei`).
5. **No screenshots of other apps.** A screenshot is kept only while Idle Heroes is alone in front (no other app, no
   notification shade, no keyboard, never this app's own screen) - see `kern/.../SpielVorne.kt`. Nothing goes to your
   gallery.
6. **The secondary password is never logged**, never put into a report, never in Android backups, and is stored
   **encrypted with Android Keystore** (AES-GCM key in the hardware-backed keystore where available). While **Show** is
   ticked, the settings screen is protected with `FLAG_SECURE`.
7. **No purchases:** the bot never taps the purchase "+" in the top bar (`TippSperre.kt`) and never acts on an old screenshot.
8. **No hidden behaviour:** no random "human-like" delays, nothing hidden from the game or from you, and it slows down when
   the game says "Operated too frequently".

## What is stored on the phone

| What | Where | How long | How to delete |
|---|---|---|---|
| Settings (rules, budget, end time) | app settings (included in Android backups) | until uninstall | uninstall |
| Your OK (consent) and first-start flag (`zustimmung.xml`) | app settings (**excluded** from backups, so a new phone asks again) | until uninstall | uninstall |
| Secondary password (optional) | encrypted, own file `geheim` (excluded from backups) | until you clear the field | clear the field, or uninstall |
| Log (`protokoll.txt`, `protokoll.alt.txt`, start time in `protokoll.beginn`) | app storage | no line older than 14 days (restarts every 7 days or at 2 MB) | **Delete reports, log and screenshots** |
| Reports (settings without password, log, a few game screenshots, phone model and Android version) | app storage (`noBackupFilesDir`) | saved **after every run**, last 5, max. 14 days | **Delete reports, log and screenshots** |
| Flight recorder (optional, off by default: last ~3 min of game screenshots) | app cache | cleared after the end-of-run report, leftovers after a crash at the next start | same button |
| Unsure screenshots of the game | app storage (`noBackupFilesDir/bilder`), max. 12 files / 40 MB | max. 14 days | same button |
| Shared report ZIP | app cache (`cache/geteilt`) | deleted on the next app start | automatic |
| Run flag (`lauf.xml`) | app settings (excluded from backups) | while a run is active | automatic |

The log goes to Android's system log (logcat) only in debug builds, never in release APKs. The accessibility service
declares `android:isAccessibilityTool="false"`: it is a game helper, not an accessibility tool for people with
disabilities.

Every screenshot that is **saved** (reports, flight recorder, unsure screenshots) is blacked out outside the game window
and under any other window on top of it (`SpielVorne.schwaerzen`). The game's window ids are kept only in memory.

Nothing of this leaves the phone unless **you** share a report (or, with **Share log only**, just the log) through
Android's share sheet.

## How to verify each claim

| Claim | What to look at |
|---|---|
| No internet | `app/src/oss/AndroidManifest.xml` removes `INTERNET`. After building, check the merged manifest: `app/build/intermediates/merged_manifests/ossRelease/` or run `aapt dump permissions app-oss-release.apk` - you should see no `INTERNET`. In Android: Settings -> Apps -> IH Auction Bot -> Permissions. |
| Only ML Kit adds `ACCESS_NETWORK_STATE` | `app/build.gradle.kts` (dependency `play-services-mlkit-text-recognition`); the oss and main manifests do not declare it. |
| No network code in our code | Search the source: `grep -rn "java.net\|HttpURLConnection\|Socket\|okhttp" app/src/main app/src/oss kern/src/main` should find nothing.  |
| What the accessibility service can do | `app/src/main/res/xml/bedienungshilfe.xml`, `BotDienst.kt` (screenshots: `takeScreenshot`, taps: `dispatchGesture`, lock screen: `GLOBAL_ACTION_LOCK_SCREEN`). |
| Typing only into the game | `TextEingabe.kt` (`vomSpiel`, `erlaubt`). |
| Password storage | `TextEingabe.kt` (`object Geheim`: `setzeZweitpasswort`, `zweitpasswort`, Android Keystore alias `ih_zweitpasswort`, own file `geheim`), `app/src/main/res/xml/sicherung.xml` and `sicherung_alt.xml` (that file is excluded from backups). |
| Password not logged | Search for the password accessors: `Geheim.zweitpasswort(` is only called to type it into the game (`BotDienst.kt`) and to show it in the settings field (`EinstellungenActivity.kt`); reports are built in `kern/.../bericht/Bericht.kt` and `app/.../BerichtSender.kt` without it, and no screenshot is kept while the password window is open. |
| No uploads / recording / self-update | Search the source with the command below the table; it finds no such code. |
| Limited to the game | `app/src/main/res/xml/bedienungshilfe.xml` (`packageNames`); `kern/.../SpielVorne.kt` (`FensterZuordnung`: which app is in front without reading other windows, which frames may be kept; tested); `KnopfSichtbarkeit.kt` (allow-list). |
| Your OK first | `EinstellungenActivity.kt` (dialog before the accessibility settings and on first start); `BotDienst.kt` does nothing until the OK is stored. |
| Update button only opens a page | `app/src/oss/kotlin/.../EinstellungenZusatz.kt`. |
| Never buys with the top bar | `kern/.../TippSperre.kt` (checked before **every** tap) and its tests (`kern/src/test/.../SicherheitUndScrollenTest.kt`). |
| Report sharing | `app/src/oss/kotlin/.../BerichtTeilen.kt` and `app/src/oss/res/xml/berichte_teilen.xml` (one ZIP, read-only). |
| Test-only receiver | `app/src/debug/AndroidManifest.xml` adds a test broadcast receiver for the emulator test. It is in the **debug** build type only, **never in release APKs**, and it requires the `DUMP` permission (only `adb shell` has it). |

Search for upload, recording, install and dynamic-code code (should find nothing):

```bash
grep -rniE "upload|MediaProjection|PackageInstaller|DexClassLoader|java\.net|HttpURLConnection" app/src kern/src
```

The main manifest (`app/src/main/AndroidManifest.xml`) declares **no** `uses-permission` at all; everything in the final
APK comes from libraries and is printed in each release's notes (see below). The new main screen, power saving and the
overnight checks use no extra permission: battery level and temperature come from Android's sticky battery broadcast,
the battery-optimization and Do Not Disturb checks only read the current state.

## Build provenance

- Release APKs are built by **GitHub Actions** from this repository (no APK is built or signed on a personal computer).
- The release notes contain the **SHA-256** of each APK. Check yours:
  `sha256sum IH-Auction-Bot.apk` (Linux/macOS) or `certutil -hashfile IH-Auction-Bot.apk SHA256` (Windows).
- APKs are signed with the project's release key, passed to the build from GitHub secrets. Updates only install over the same signature.
  **Only APKs from this repository's Releases with this signing certificate are the public build:**

  ```
  SHA-256 digest: (published here and in the release notes with the first release)
  DN: CN=Idle Heroes Auction Bot, O=Open Source
  ```

  Check it with `apksigner verify --print-certs IH-Auction-Bot.apk` (Android SDK build-tools) or `keytool -printcert -jarfile IH-Auction-Bot.apk`.
- For public releases GitHub also signs a **build provenance attestation** (this APK was built by this workflow from this
  commit). Check it with `gh attestation verify IH-Auction-Bot.apk -R Hustle-Bot/idle-heroes-auction-bot`.
- You can also build the app yourself ([README](README.md#building-from-source)) and compare behaviour.

The workflow is [`.github/workflows/build.yml`](.github/workflows/build.yml):

- Every push and pull request runs the core tests and builds an unsigned debug APK. The build **fails** if the APK asks for
  `INTERNET` or `REQUEST_INSTALL_PACKAGES`.
- A release is only made by hand from `main` (or for a `v*` tag on a commit that is on `main`). Its notes contain the
  **SHA-256** of `IH-Auction-Bot.apk`, the **signing certificate** (the build fails unless its SHA-256 digest is the one
  above) and the full **permission list** (`aapt2 dump permissions`). The checksum is also attached as
  `IH-Auction-Bot.apk.sha256`.
- The release is split in three jobs: **build** (Gradle builds an unsigned APK, no secret available, read-only token),
  **sign** (Android's `apksigner` signs it; the only job with the key, runs no project code and no Gradle) and
  **attest and publish** (may write the release, never sees the key, runs no build tools).
- Not done yet: Gradle dependency verification metadata and a pinned checksum of the Gradle distribution. Because the
  build job holds no secret, a compromised dependency could change the APK but not steal the signing key; the published
  SHA-256, certificate and provenance attestation let you check what was released.
- All actions are pinned to full commit SHAs, the Gradle wrapper JAR is checked against Gradle's published checksums, and
  the workflow token is read-only everywhere except in the publish job. Dependabot proposes updates weekly.

## Reporting a vulnerability

- Preferred: GitHub **private vulnerability reporting** ("Security" tab -> "Report a vulnerability") on
  <https://github.com/Hustle-Bot/idle-heroes-auction-bot>.
- If that is not available, open a **GitHub issue** with the label "security" **without** exploit details, and ask for a private channel.
- Do **not** post secondary passwords, screenshots with personal data, or signing keys in public issues.

We will look at reports on a best-effort basis; this is a hobby project without a security team.

## Note on the test images

Some images in `testbilder/` show an older panel of the bot with German texts such as "Bericht an Claude senden" or
"Lernaufnahme starten". They come from an early development version, which had report uploads and a learning recording
for testing; those features were removed before publishing and are **not** in this repository or in the app. The images
are only used as input for the recognition tests. Other players' names in the test images are painted over.
