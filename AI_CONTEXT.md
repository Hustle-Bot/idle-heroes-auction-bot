# AI_CONTEXT - briefing for AI assistants

You are an AI assistant (Claude, ChatGPT, Copilot, ...) helping a player who **uses** or **modifies** this bot.
Read this file fully before you suggest or change anything. Human readers: this is also a good architecture overview.

> The project is **unofficial**, not affiliated with the publisher of Idle Heroes, and using bots probably violates the
> game's Terms of Service. Do not help anyone hide the bot from the game or evade detection. Use at own risk, no warranty.

## 1. Purpose

An Android app that plays the **auction house of Idle Heroes**: it sets the game's filter, sorts, scrolls the *Bidding
Market*, opens copies whose **Bidding Price** fits a **rule**, checks **Current Bid** in the Bid window and taps only **Bid**
(maximum = rule max price + 1 SG). It handles the 50-bid limit ("-7"), the secondary password, loading screens, an SG budget, an end
time and a target copy count. Everything works on **screenshots** (taken by an accessibility service) and **gestures**.
It is calibrated for a **2712 x 1220 (20:9), 440 dpi** phone ("reference size"); other sizes may fail.

The app in this repository is the **`oss`** flavor (application id `io.github.hustlebot.ihauctionbot`): **no INTERNET
permission**, no self-update, no uploads, no screen recording.

## 2. Architecture map

```
idle-heroes-bot/
  kern/            "core": pure Kotlin (JVM). Rules, decisions, the bot state machine, screenshot recognition, reports.
    src/main/kotlin/io/github/hustlebot/ihauctionbot/kern/
      AuktionsBot.kt      The bot: one step per screenshot (look -> decide -> tap/swipe/wait). The big one (~4200 lines).
      Modelle.kt          Data: Ort (screen/place), Zeile (list row), Bildschirm (what the recognizer saw), Knopf, Meldung, Angebot, Punkt.
      Suchregel.kt        Rule: copy tier, min/max price, max time left; hoechstesGebot = maxPreis + 1; buyoutMax (null = never buy out).
      Entscheider.kt      Decides per row: Bieten / Nachbieten / Loslassen / Ueberspringen / Warten.
      GebotsVerwaltung.kt Bookkeeping of own bids (limit 50, outbid, won/lost estimate).
      StoppBedingung.kt   StoppGrund (stop reasons + texts) and the stop conditions (goal, time, budget, ...).
      TippSperre.kt       SAFETY LOCK checked before EVERY tap/swipe (top bar, bottom edge, steady windows only).
      TaschenSperre.kt    Pocket-mode logic (shield on/off decisions).
      KnopfSichtbarkeit.kt, ZweimalTippen.kt   Small helpers (when to show the IH button, double-tap guard).
      Projekt.kt          The public repo and its releases page ("Check for updates" only opens it in the browser).
      Json.kt             Tiny JSON text helper for the report files.
      bild/               Screenshot recognition: Erkenner.kt (recognizer), Ziffern*.kt (digit reading), Nachlesen.kt (second look), Bild.kt.
      bericht/            Reports (Bericht.kt) and the flight recorder (Flugschreiber.kt) - local only.
    src/test/...          Unit tests (see section 5).
  app/             Android app. src/main (shared), src/oss (the public flavor), src/debug (test receiver + debug service config).
    src/oss/.../Variante.kt   The few hooks where a build may add something of its own (run start/step/end, panel button,
                              notices, what happens with a finished report). In the public app they do nothing extra.
    BotDienst.kt          The accessibility service: screenshots, gestures, floating IH button, lock screen, volume-key emergency stop.
    SchwebeFenster.kt     Floating IH button + panel + the dim-screen overlay.
    TaschenSchild.kt      Touch shield (stop-on-touch / pocket mode) as accessibility overlays.
    EinstellungenActivity.kt, Einstellungen.kt   Settings screen and how settings are saved.
    TextEingabe.kt        Typing into the GAME's fields; `object Geheim` = encrypted secondary password (Android Keystore).
    BerichtSender.kt      Builds reports + flight recorder on the phone. src/oss/.../BerichtTeilen.kt = share a report as a ZIP.
    MlKitLeser.kt         Second opinion for numbers via Google Play services ML Kit.
    BotZustand.kt, Helfer.kt, Baukasten.kt   State, helpers, UI building blocks.
  attrappe-kern/   "Fake game" (Attrappe): redraws the game's screens from the real test screenshots and reacts to taps. Pure JVM tests.
  attrappe/        Android app wrapper of the fake game (for the emulator).
  e2e/             emulator-test.sh (+ pruefen.py checks results): the REAL app against the fake game in an Android emulator.
  testbilder/      Real test screenshots (reference size) used by recognition tests and the fake game. Never edit them.
```

Kotlin source package for all modules is `io.github.hustlebot.ihauctionbot.*` (`.kern`, `.app`, `.attrappe`). The oss
**application id** is `io.github.hustlebot.ihauctionbot`.

### The loop in one paragraph
`BotDienst` takes a screenshot -> `kern` recognizer (`Erkenner`) turns it into a `Bildschirm` (place `Ort`, rows `Zeile`,
buttons `Knopf`, numbers) -> `AuktionsBot` decides an `Aktion` (tap / swipe / wait / stop) -> `TippSperre` checks it ->
`BotDienst` performs the gesture -> the next screenshot confirms what happened. The bot never assumes a tap worked.

## 3. Identifiers are German - glossary

| German | English | German | English |
|---|---|---|---|
| kern | core | Attrappe | fake game (dummy) |
| Gebot | bid | Nachbieten | re-bid |
| Regel / Suchregel | rule | Ueberbieten / ueberboten | outbid |
| Kopie | copy (the item for sale) | Bieten | bidding |
| Zeile | (list) row | Loslassen | let go (do not bid) |
| Wischen | swipe | Ueberspringen | skip |
| Tippen / Tipp | tap | Warten | wait |
| Erkenner | recognizer | Sperre / TippSperre | lock / tap lock |
| Bildschirm | screen (a recognized screenshot) | Taschenmodus / TaschenSchild | pocket mode / shield |
| Bild | image / screenshot | Einstellungen | settings |
| Ort | place (which game screen) | Bedienungshilfe / BotDienst | accessibility / bot service |
| StoppGrund | stop reason | Bericht / Flugschreiber | report / flight recorder |
| Budget / Ziel | budget / goal | Protokoll | log |
| Meine Gebote | My Biddings | Zweitpasswort | secondary password |
| Restzeit | time remaining | Verbindung weg | connection lost |
| Preis / hoechstesGebot | price / highest bid | Berührung | touch |
| Meldung | toast / message | Notaus | emergency stop |
| Ladebildschirm | loading screen | Aufraeumen | clean up |

Comments in code are partly German, partly English (the user-visible texts are English).

## 4. SAFETY INVARIANTS - never break these

1. **Never buy out unless buyout is enabled AND the price is within the rule's max buyout.** By default (switch off) the bot taps only
   "Bid", never "Buyout" / "Buyout Price". A buyout counts toward the SG budget. Names: switch `AuktionsBot(buyoutAn = false)`,
   per rule `Suchregel.buyoutMax`, button `Knopf.SOFORTKAUF`, price `Bildschirm.sofortPreis`, stop `StoppGrund.BUYOUT_UNBEKANNT`
   (unknown window after "Buyout Price": tap nothing, stop). The button is tapped only if the Bid window shows the same buyout
   price as the list, at most `buyoutMax`, and the yellow button is recognized on that same screenshot. With buyout on, the
   game's filter max is `max(maxPreis, buyoutMax)` (`AuktionsBot.filterMax`) so every buyout candidate is listed; then the
   list may show rows above the rule's max, so the "filter guarantees the price" shortcut (`preisDurchFilter`) is off and
   every bid still needs a readable row price <= `maxPreis`.
2. **Never bid above rule max price + 1** (`Suchregel.hoechstesGebot`), and never more SG than the player has / the budget allows.
3. **Never tap the top bar** (back arrow, currencies with the purchase "+", lock, "?"). The only exception is the red X of a window
   recognized in exactly this screenshot and steady (`TippSperre`). Do not weaken `TippSperre` or its constants.
4. **Never act on a stale frame.** A decision may only use the screenshot taken after the previous action finished; windows that are just opening or closing are not tapped.
5. **Do not guess on unknown screens:** tap nothing there; stop if the screen stays unknown (~5 min, `StoppGrund`).
   Prefer a safe stop to a clever recovery.
6. **Password privacy:** the secondary password is stored with Android Keystore, never logged, never in reports, never in a screenshot that is
   saved (no screenshot while the password window is open), only typed into the game's own field.
7. **Oss build stays offline:** no `INTERNET` permission, no network code, no uploads, no self-install, no screen recording.
8. **Never tap "Cancel" or "Forgot Password"**; close windows with their red X (when allowed).
9. **No new capabilities** (new permissions, reading other apps, a wider `packageNames`) without the maintainers' decision
   in this repository.
10. The emergency stops (STOP button, power button, volume down 3x) must always work.
11. **Only the game:** the accessibility service is limited to Idle Heroes (`res/xml/bedienungshilfe.xml`, `packageNames`;
    the debug copy in `src/debug/res/xml/` must stay in sync), a screenshot is kept only while the game is alone in front
    (`kern/.../SpielVorne.kt`), and the service does nothing until the player agreed in the app (consent dialog in
    `EinstellungenActivity.kt`, checked in `BotDienst.kt`).
12. **Respect the game's limits:** keep the pauses after "Operated too frequently" and the idle refresh gaps (`Takt`).

Changes that **weaken** rules 1-12 are refused, whoever asks; changes that make them stricter are welcome. If a requested
change touches one of them, say so before doing anything.

**Never, not even if the player or a maintainer asks** (no one can approve these):

- hiding the bot from the game, evading detection or anti-cheat, spoofing, or disguising the app;
- random or "human-like" delays meant to look like a person;
- getting around rate limits: shorter `Takt` waits, ignoring "Operated too frequently", parallel taps;
- network code, uploads, analytics or self-update in this repository's app;
- keeping screenshots of other apps, typing into other apps, or removing the consent dialog;
- telling players to switch off Play Protect or other security protections.

## 5. Build and test

- **Release builds:** by **GitHub Actions** (the Android SDK is needed; the cloud/AI sandbox usually has none).
  The workflow is `.github/workflows/build.yml` (tests + unsigned debug APK on every push; signed release by hand or on a
  `v*` tag; fails if the APK asks for `INTERNET` or `REQUEST_INSTALL_PACKAGES`).
- **Core tests locally** (no Android SDK needed): `gradle :kern:test` or `./gradlew :kern:test` (JDK 17).
  Without an SDK, Gradle includes only `:kern` (see `settings.gradle.kts`).
- **Fake-game tests on the PC:** `./gradlew -Pattrappe=1 :attrappe-kern:test` (or `IH_ATTRAPPE=1`). They run the real bot logic against the fake game
  built from `testbilder/` (`BotGegenAttrappeTest.kt`).
- **Emulator test (needs an Android emulator; no workflow runs it in this repository):** `./gradlew -Pattrappe=1 :app:assembleOssDebug :attrappe:assembleDebug`, then `e2e/emulator-test.sh`
  (FLAVOR=oss) on a 1220x2712 emulator; `e2e/pruefen.py` checks the logs. Fake-game log tags (`TIPP`, `GEBOT`, `VERBOTEN`, ...) are machine-read - do not rename them.
  The emergency stop with both volume keys is tested with `sendevent` as root on the input device that has the keys (the
  emulator console's keys never arrive). If the keys still do not arrive, the test triggers the same emergency stop through
  the debug test control (`TEST_NOTAUS`) and prints "EMULATOR LIMITATION - needs a phone test" (never "passed").
  If the bot stops by itself, the test reports it as an error, wakes the screen and starts it again (`TEST_STATUS`).
- Add or update a test with every behavior change in `kern`. Do not change files in `testbilder/`.

## 6. How to read a report

A report is a ZIP (shared from *Settings -> Reports*) with folders like `Bericht-<date>`:
- `info.txt` - version, reason, place, settings without secrets, estimated bids.
- `protokoll.txt` - the log (see below). Start at the **end** and read upward to the first odd line.
- `bild_*.jpg` + `.json`/`erwartet-*` - the last screenshots and what the recognizer read (place `ortText`, rows, prices).
- Flight recorder (if on): `flugschreiber.csv` (`zeit_ms,datei,ort,aktion,x,y,ref_x,ref_y,grund`) + image packages: every analysed
  screenshot and the action taken. Coordinates are real pixels and reference pixels (2712 x 1220).
File and JSON field names are German on purpose (`preis`, `zeit`, ...); do not "translate" them.

Useful log lines: `Start. Rules: ...`, `Bid placed: C- for 17 SG`, `Bid arrived: ...`, `Bid ... did NOT arrive`, `Bought (estimated): ...`,
`Lost: ...`, `Swiped: N new copies ...`, `Tap locked: ...`, `Game reports: 50 bids reached - cleaning up My Biddings`,
`SG budget used up ...`, `Game says: operated too frequently – waiting 5 s (1 of 5 in a row)`,
`Stop: <reason> (N copies bought, estimated)`. Stop reasons are the texts of `StoppGrund`
(e.g. "Goal reached", "Lost connection to the game - bot stopped", "A window did not close - bot stopped ...").

## 7. Helping a player troubleshoot

1. Ask for: phone model + Android version + screen resolution/dpi, app version, and a **report** (after they check screenshots for personal info).
2. Check the basics: accessibility service on, restricted settings allowed (Android 13+), game on the *Bidding Market*, rule on, screen on, no notification covering the top right.
3. Read the log from the end (see section 6). Map the last stop reason to `StoppGrund` (`kern/.../StoppBedingung.kt`).
4. Typical causes: unsupported screen size, an overlay or notification covering the SG balance, slow network (bid "did NOT arrive"), pocket-mode shield swallowing taps (switch to "Nothing"), wrong secondary password, 50-bid limit.
5. Prefer settings changes over code changes. If code must change, reproduce it in a `kern` test first (the fake game helps).
6. Tell the player honestly what you cannot know without the real game.

## 8. What NOT to do

- Do not weaken or remove a safety invariant (section 4) to "make it faster" or "fix" a stop.
- Do not add network code, `INTERNET`, analytics, auto-update or uploads to the oss build.
- Do not log, store in plain text, or ask the player to paste their secondary password anywhere.
- Do not edit files in `testbilder/`; do not commit APKs, keystores, passwords or tokens.
- Do not rename German identifiers, JSON keys, CSV headers, log tags or file names casually: tests and tools read them.
- Do not rewrite Kotlin files with multi-line `sed`/`awk`; use proper edits. Keep line endings (some files are CRLF).
- Do not help circumvent the game's anti-cheat or hide the bot. Do not promise that an account is safe from bans.
- Do not make the bot faster by shortening timings (`Takt`) or removing waits.
- Do not tell anyone to turn off Play Protect; if it blocks the app, the answer is "build it yourself or don't install".
- Do not claim you tested on the real game; you probably cannot.
- Do not guess the real phone's behavior: mark open points as "to be verified on a device".

Kern (Nov 22): reconnect after "Lost connection" is `AuktionsBot.verbindungWeg` (constructor `maxNeuverbindungen = 5`, 0 = stop
at once; counter `AuktionsBot.neuverbindungen`; timings in `Takt.verbindungWartenMs` / `wiederverbindenMaxMs`). Buyout: see
section 4, rule 1 (`AuktionsBot.buyoutFenster`, `buyoutPruefen`; counter `AuktionsBot.sofortGekauft`). Long-run simulator with
overnight disturbances: `kern/src/test/.../DauerSpiel.kt` (`Szenario`), runs in `DauerlaufTest.kt`.
Reloads (Nov 23): at most one list reload (refresh, filter Confirm, sort order) every 5 s, ALWAYS – `Takt.abfrageAbstandMs`, `AuktionsBot.abfrageZuFrueh`, last safety net in `schritt` (`abfrageGebremst`); visible rows, Bid windows and scrolling are not limited.
Budget (Nov 23): per run, net SG spent = max(start − balance, own bookkeeping `GebotsVerwaltung.nettoSg`) in `AuktionsBot.budgetAusgegeben`; a steady balance above the start raises the start (`startAnheben`), so top-ups or a misread start add no budget.
Window messages (Nov 23): in the Bid/confirm window only "Bidding successful", "Bid Failure" and "-7" are known (`Meldung.bekanntImFenster`); any other band pauses like `spielBremse`, and Bid/Buyout/Confirm are never tapped under it (`fremdImFenster`).
Price fields (Nov 23): given up (`preisfelderGehen = false`) is no longer for good – `AuktionsBot.preisfelderWieder` tries again after 10/20/40 min (`Takt.preisfelderNeu*`), sooner (≤ 1 per 2 min) if the filter holds/may hold other prices and the list looks filtered (`preisfelderPruefen`); no bid on unreadable prices while the filter is not verified (`preisDurchFilter`).
Rate limit (Nov 22): the bot respects the game's "Operated too frequently" message and does not refresh faster than every
5 s (up to 15 s) when there is nothing to bid. Recognition: `Meldung.wieZuOft` (one-line band ~1415 px; real frames
`testbilder/berichte/20-42-45_bild_08`-`_11`, `21-46-15_bild_03`-`_06`); known toasts `Meldung.bekannt` ("Bidding successful",
two-line "Bid Failure") are tapped through as before. Pause/backoff: `AuktionsBot.spielBremse` (5 s doubling to 60 s,
`Takt.zuOft*`, unknown band over the list `Takt.fremdeMeldungPauseMs`), stop `StoppGrund.ZU_OFT` after `ZU_OFT_MAX_IN_FOLGE`.
Idle refresh: `aktualisierenWarten` (`Takt.leerlauf*`; fast while a fitting copy ends within `BALD_ENDE_SEK`). After a bid,
our own row showing a price above max + 1 twice stops the bot (`StoppGrund.GEBOT_ZU_HOCH`). Tests: `SpielBremseTest`,
`bild/ZuOftBilderTest`, long-run scenarios "quiet market", "rate limit", "rate limit for good".
App (Nov 22): the main screen (status line, START/STOP, rules with **Max buyout (SG)**, the **Buyout (beta)** checkbox,
"When to stop", "Touching the screen", collapsed "Advanced settings") is `EinstellungenActivity.kt`; settings fields are in
`Einstellungen.kt` (`stromsparen`, `buyout` -> `AuktionsBot(buyoutAn = ...)`, `Suchregel.buyoutMax`, JSON keys `buyout` and `buyoutMax`). The overnight
warnings before a start and the battery/temperature limits are `NachtCheck.kt` (`warnungen`, `AKKU_MIN_PROZENT`,
`KUEHL_C`); the pause/stop itself happens in `BotDienst.kt` (`StoppGrund.AKKU_SCHWACH`).
Buyout (Nov 23): prepared but not offered in the public app until tested in the real game (`Variante.BUYOUT_FREIGEGEBEN`
= false in oss; `kern/.../BuyoutFreigabe.kt` forces `buyoutAn = false` and strips `buyoutMax` from the run's rules).
Privacy (Nov 23, round 3): saved screenshots are blacked out outside the game and under other windows
(`SpielVorne.schwaerzen`), taps under another window are skipped (`SpielVorne.punktFrei`), the window list is read right
before every gesture (`BotDienst.vorneJetzt`), window ids only in memory, log lines expire after 14 days
(`BotZustand.protokollAufraeumen`), flight-recorder leftovers are removed (`BerichtSender.resteLoeschen`).
Privacy (Nov 22/23): `kern/.../SpielVorne.kt` (`FensterZuordnung`) decides from the window list, the game's own window
ids and the app's own-screen flag which app is in front - never by reading another app's window - and whether a frame may
be kept (game alone in front; tested); gestures, typing and screenshots fail closed (state older than 3 s = no action);
`KnopfSichtbarkeit.kt` holds the allow-list of game packages (`com.droidhang.ad`); the consent flag lives in
`zustimmung.xml` (not backed up); `MlKitLeser` is only created when the setting "Google text recognition" is on (off in
the public app); unsure screenshots are app-private (`noBackupFilesDir/bilder`, the newest go into reports under `gespeichert/`);
reports, log and screenshots can be deleted in the app and expire after 14 days (`BerichtSender.AUFBEWAHREN_MS`).
My Biddings (Nov 23): if the red X opens the Bid window of ANOTHER row (the list moved between screenshot and tap),
the bot closes that window with its X and never bids there (`AuktionsBot.bidNachKreuz`, at most `MAX_BID_NACH_KREUZ`
per clean-up, then `StoppGrund.UNBEKANNT_NACH_X` as before). Any other window after the X still stops the bot.
