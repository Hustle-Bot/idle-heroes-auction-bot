# IH Auction Bot (Idle Heroes)

An open-source Android app that plays the **auction house** of the mobile game *Idle Heroes* for you:
you tell it which copies you want and how much you are willing to pay, and it bids for you, even while you sleep.

It runs **only on your phone**: no root, no emulator needed (checking the download before installing is easiest on a
computer, see below). The public build has **no internet permission at all**.

> ## DISCLAIMER - please read this first
>
> - This is an **unofficial, fan-made** project. It is **not affiliated with, endorsed by or connected to** the
>   publisher or developer of Idle Heroes in any way. All game names and images belong to their owners.
> - **Using bots or automation tools very likely violates the game's Terms of Service.** Your account **can be
>   banned or penalised**, and you could lose items, currency and progress.
> - **Use it at your own risk.** The software is provided "as is", **without any warranty** (see [LICENSE](LICENSE)).
>   The authors are not responsible for lost accounts, lost in-game currency, or anything else.
> - The bot spends **Starry Gems (SG)**, the auction-house currency, when it bids. SG can come from purchases with real
>   money, so treat the **SG budget** as a spending limit (new installs start with 500 SG) and test with a small goal first.
> - **Under 18?** Only with a parent's or guardian's OK.
> - If you are not sure, **do not use it** on an account you care about.

## What it does

- Sets the game's own **filter** (tier and min/max price), sorts the list, and **scrolls the Bidding Market** by itself.
- Opens a copy only if its **Bidding Price fits one of your rules** (for example "C-, filter 10-20 SG").
- Checks **Current Bid** in the Bid window and taps **only "Bid"**. The bid is never higher than your rule's
  maximum price + 1 SG.
- Handles the **"-7" message** (the game's limit of 50 active bids): it cleans up *My Biddings*, bids again on
  outbid rows if the price still fits, and dismisses the others with the red X. When the *Bidding Items* counter is
  below 50 again, it keeps bidding.
- Enters your **secondary password** when the game asks for it (typed in the settings once, stored encrypted).
- Deals with **loading screens, toasts and pop-ups**, and keeps a **SG budget**, an **end time** and a **target
  number of copies**. The budget is **per run, net SG spent** (bought copies plus running bids; outbid bids give their SG
  back; SG you add during the run do not raise it).
- **Never taps the purchase "+" in the top bar** and **never acts on an old screenshot**.
- **Respects the game's rate limit:** **at most one list reload every 5 s, always** (refresh, filter, sort order); after
  "Operated too frequently" it taps nothing for 5 s (longer if it repeats, stops after 5 in a row), and when there is
  nothing to bid it refreshes the list at most every 5-15 s. A message it does not know in the Bid window pauses it, and it
  never taps under it.
- **Never guesses** on a screen it does not know: it taps nothing there, and stops if the screen stays unknown
  (about 5 minutes).
- Works only while **Idle Heroes (Google Play version, `com.droidhang.ad`) is in front**; when another app comes to the
  front it taps nothing and stops after about 2 seconds.
- Can **reconnect** after a "Lost connection" message (at most 5 times per run).
- Optional **buyout** mode: **off by default**; you set a maximum buyout price per rule, and it counts toward your SG budget.
  See the risks in [GUIDE.md](GUIDE.md).
- A simple main screen with big **START** / **STOP**, **Power saving** and **overnight checks** (warns before a start if
  the phone is not charging, pauses when it gets too hot, stops when the battery is low).
- **Reports** for troubleshooting are saved on your phone (deleted after 14 days, or at once with a button); you decide if
  and where to share them.

### Fairness

The bot bids like a player who watches the list closely: it bids **in the game's normal way** (only "Bid", at most the
price you allow + 1 SG), it does **not** use any bug or hidden interface, it **slows down** when the game says
"Operated too frequently", and it does **not** pretend to be a human (no random delays, nothing hidden from the game).
Other players can still outbid it at any time. It can bid **unattended for hours**, and it is fast at the end of auctions, which is exactly why the game's Terms
of Service probably forbid it, see the disclaimer above.

## Safety summary

The public ("oss") build is designed so you can check what it does:

| Permission / access | Why | Where you can check it |
|---|---|---|
| **Accessibility service** (you switch it on yourself, after the app explained it and you tapped **Agree**) | Limited to Idle Heroes: Android only delivers the game's events (`packageNames`), and the app's own code only taps, types and keeps screenshots while the game is in front. Takes screenshots of the game, taps and swipes for you, types numbers into the game's own input fields (price filter, secondary password), shows the floating **IH** button, listens to the volume keys **only while a run is active** (emergency stop), checks which app is in front, can turn the screen off at the end | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/bedienungshilfe.xml` |
| Floating button / overlay | The IH button and panel are drawn as an *accessibility overlay*, so no "draw over other apps" permission is needed | `SchwebeFenster.kt` |
| `ACCESS_NETWORK_STATE` | Added by the Google ML Kit library only (see [SECURITY.md](SECURITY.md)); the app does not use it | merged manifest of the build |
| **No `INTERNET` permission** | The oss build explicitly removes it | `app/src/oss/AndroidManifest.xml` |

**The app never:**

- connects to the internet (no uploads, no analytics, no ads, no accounts),
- updates or installs itself (the update button only opens the GitHub releases page in your browser),
- records your screen as a video, keeps screenshots of other apps, or saves screenshots to your gallery (diagnostic
  screenshots of the game stay inside the app),
- reads text fields of other apps (it only types into the game's own fields),
- writes your password into the log, and
- taps the purchase "+" button or buys anything with real money.

Details and how to verify each claim: [SECURITY.md](SECURITY.md).

## Requirements

- **Android 11 or newer** (earlier versions cannot take screenshots through an accessibility service).
- The game **Idle Heroes** from Google Play (`com.droidhang.ad`), with the game language set to **English** (the bot
  recognises the English screens and messages).
- **Screen size:** the image recognition is calibrated for a **2712 x 1220 pixel (20:9) phone at 440 dpi**.
  Other sizes or aspect ratios **may not work** (or may stop immediately). Tell us how it went if you try one.
- A phone you can leave **plugged in** if you want to run it for hours.

## Quick start

1. **Download** the APK from the [Releases page](https://github.com/Hustle-Bot/idle-heroes-auction-bot/releases) and
   **check it** (SHA-256 and signing certificate, see [SECURITY.md](SECURITY.md#build-provenance)) before you install it.
   Android will ask you to allow installing from your browser or file manager. **Keep Play Protect on**; if it warns, read
   [GUIDE.md](GUIDE.md#1-install) first.
2. **Open the app.** It explains what the accessibility service does; tap **Agree** if you are fine with it.
3. **Switch on the accessibility service** from the app (**Open accessibility settings**) -> **IH Auction Bot**
   (on Android 13+ Android may first ask you to allow *restricted settings*, see the GUIDE). A round **IH** button appears
   over the game.
4. **Set your rules** (which copy tier, min/max price) and check the **SG budget** (500 SG on a new install), goal and end
   time.
5. **Open the game:** Auction House -> Auction Market -> **Bidding Market**. Do not set the filter yourself.
6. **Tap IH -> Start** (or the big **START** in the app). Stop it any time with **IH -> Stop**, the red **STOP** in the
   app, the power button, or volume down 3 times.

The full walk-through is in **[GUIDE.md](GUIDE.md)**.

## Documentation

- [GUIDE.md](GUIDE.md) - install, settings, stopping, running overnight, troubleshooting, FAQ
- [SECURITY.md](SECURITY.md) - permissions, what the app never does, how to verify it, how to report a vulnerability
- [AI_PROMPT.md](AI_PROMPT.md) - a ready-made prompt to paste into your own AI assistant so it can help you
- [AI_CONTEXT.md](AI_CONTEXT.md) - briefing for AI assistants (and curious developers): architecture, safety rules, testing
- [CHANGELOG.md](CHANGELOG.md)

## Building from source

The APKs in Releases are built by **GitHub Actions** from this repository (see [SECURITY.md](SECURITY.md)).
To build yourself you need JDK 17 and the Android SDK (`ANDROID_HOME` set):

```bash
./gradlew :kern:test                 # run the core tests (no Android needed)
./gradlew :app:assembleOssDebug      # debug APK of the public app
./gradlew :app:assembleOssRelease    # release APK (unsigned unless IH_SIGNING_* environment variables are set)
```

Without an Android SDK, Gradle only builds and tests the core (`:kern`).
The release APK on GitHub is `IH-Auction-Bot.apk`, built by [`.github/workflows/build.yml`](.github/workflows/build.yml).

## Project layout (short)

| Folder | What it is |
|---|---|
| `app/` | Android app (accessibility service, floating button, settings screen) |
| `kern/` | The brain: rules, bidding logic, screenshot recognition. Pure Kotlin, testable on a PC |
| `attrappe-kern/`, `attrappe/` | A **fake game** ("Attrappe") to test the bot without the real game |
| `e2e/` | Emulator test: the real app against the fake game |
| `testbilder/` | Test screenshots used by the tests |

## License

[MIT](LICENSE) - Copyright (c) 2026 Hustle-Bot contributors. No warranty.
