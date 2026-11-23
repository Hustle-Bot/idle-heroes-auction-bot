# User Guide - IH Auction Bot

> **Reminder:** This is an unofficial tool. Using bots probably breaks the game's Terms of Service and can get your
> account banned. You use it at your own risk. See the disclaimer in the [README](README.md).

Contents: [Install](#1-install) - [Prepare the game](#2-prepare-the-game) - [Settings](#3-settings-explained) -
[Start and stop](#4-start-and-stop) - [Touch modes](#5-touching-the-phone-while-it-runs) -
[Overnight](#6-running-overnight) - [Buyout](#7-buyout-optional-off-by-default) - [Reports](#8-reports) -
[Troubleshooting](#9-troubleshooting) - [FAQ](#10-faq)

---

## 1. Install

1. **Download the APK.** On your phone, open the
   [Releases page](https://github.com/Hustle-Bot/idle-heroes-auction-bot/releases) and download the newest `.apk` file.
   Only download it from there.
2. **Check it before installing.** Each release lists the file's **SHA-256 checksum** and the **signing certificate**.
   Compare them as described in [SECURITY.md](SECURITY.md#build-provenance). This is easiest on a computer; if you
   cannot check it yourself, ask someone you trust (for example a parent) to do it. If they do not match, or nobody could
   check them, **do not install it**.
3. **Open the file and install it.** Android may ask you to allow your browser (or file manager) to install apps.
4. **Play Protect.** **Keep Play Protect on.** It may warn that the app is "not commonly downloaded" or that it uses an
   accessibility service; that is expected for an app that is not on the Play Store. If (and only if) the checksum and
   certificate from step 2 match, you can tap **More details -> Install anyway**. If Play Protect blocks the app
   completely, do not turn Play Protect off: either [build the app yourself](README.md#building-from-source) or do not
   install it.
5. **Open the app and read the explanation.** The first time, the app explains in plain words what the accessibility
   service will do (screenshots of the game, taps, typing into the game's fields, the volume keys during a run, checking
   which app is in front), what is stored, and the risks (unofficial, ban risk, it spends Starry Gems). Tap **Agree** to
   go on, or **Not now**. Without your OK the service does nothing at all, and the app shows a card *Your OK is needed*.
6. **Android 13 or newer: "Allow restricted settings".** Android blocks accessibility services of apps that were not
   installed from an app store, because harmful apps have abused this power. Only continue if you trust this app (step 2
   matched and you read [SECURITY.md](SECURITY.md)). First try to switch the service on (step 7); when Android says the
   setting is restricted, go to **Settings -> Apps -> IH Auction Bot -> three dots (top right) -> Allow restricted
   settings**. Then go back to step 7. (In the app, **Open app info** takes you there.) To undo it later: switch the
   service off and uninstall the app.
7. **Switch on the accessibility service.** In the app tap **Open accessibility settings** (the app asks for your OK
   first if you have not given it yet) -> **IH Auction Bot** -> turn it on and confirm. A round **IH** button appears
   over Idle Heroes. You only need to do this once. In the app, the *Setup* card shows a green check mark when the service
   is on.

**Supported game version:** Idle Heroes from Google Play (`com.droidhang.ad`). The accessibility service is limited to that
app; with another version of the game the bot only waits ("Idle Heroes is not in front").

**Updates:** the app never updates itself. Tap **Check for updates (opens GitHub)** in the settings; it opens the
   releases page in your browser. Download the new APK and install it over the old one. Your settings are kept.

## 2. Prepare the game

1. Open Idle Heroes and go to the **Auction House -> Auction Market -> Bidding Market**.
2. **Do not set the filter or the sorting yourself.** The bot does that.
3. Tap the round **IH** button, then **Start**.

If you are on another page of the auction house, the bot tries to tap its way back. If it cannot recognize the
screen, it taps **nothing** and waits; if the screen stays unknown for about 5 minutes it stops and tells you
("Unknown screen - please go to the auction house").

The bot only works while Idle Heroes is in front. When another app (or this app) is in front, it taps nothing; if you
leave the game during a run, it stops after about 2 seconds.

## 3. Settings explained

Open the app **IH Auction Bot** from your launcher. Everything is saved right away; there is no Save button.
Names below are the labels in the app. The main screen shows, from top to bottom:

- a **status line** in plain words, for example *Ready - tap START, then open the Bidding Market in Idle Heroes*,
  *Running - bidding on B- (12 bids, 3 copies won)* or *Paused: the phone is too hot (46 °C) - bidding again below 40 °C*;
- **Setup needed** with the button **Open accessibility settings** (only while the accessibility service is off);
- the big green **START** and red **STOP** buttons (see [section 4](#4-start-and-stop));
- **What to buy**, **When to stop**, **Touching the screen**;
- **▸ Advanced settings** (collapsed; tap to open).

### What to buy (rules)

A **rule** says: "bid on this tier of copy, but only in this price range". Each rule row reads like
*1. B-: up to 61 SG*, with a small line *on · filter 10-60 · only the last 5 min*.

- **Which copy? (rank):** the tier the game's filter knows, from **C-** up to **SSS**.
- **Filter left: min price / Filter right: max price:** exactly the values that go into the game's *Price Range* filter.
  The bot bids on rows whose **Bidding Price is at most the max price**. Because a bid is the shown price **+ 1 SG**,
  the highest bid it will ever place is **max price + 1**.
  Example: rule *B-, 10-60* means a row at 60 SG gets a bid of 61 SG, never more.
- **Only bid if the auction ends within ... minutes:** leave empty for "any time left". A short limit (default 5 minutes)
  keeps your 50 bid slots free for auctions that are about to end.
- **Max buyout (SG):** leave empty for "never buy out". Only used when **Buyout (beta)** is on, see
  [section 7](#7-buyout-optional-off-by-default).
- Buttons: **▲ / ▼** change the order (the bot works through the rules in this order), **Off / On**, **Edit**, **Delete**,
  **+ Add rule**.
- Below the rules: the checkbox **Buyout (beta)**, **off by default**.
- Defaults after a fresh install: **B-, 10-60** and **C-, 10-20**, both "only last 5 min", no buyout.

### When to stop

- **SG budget (empty = no limit):** your **budget** of Starry Gems (SG, the auction-house currency), counted from your
  SG balance when you press START. A new install starts with **500 SG**. Without a budget, **Before you start** and the
  status line warn you (*no SG budget*), because the bot could then spend all your SG. The budget is **per run, net SG
  spent**: bids that are still running count as spent; if you are outbid, the SG come back. SG you add during the run
  (or a misread balance) do not raise it. When the budget is used up the bot waits for running bids to finish, and stops
  if none are left.
- **End time:** tap to pick a time; **No end time** clears it.
- **Copies to buy** (under Advanced settings): `0` means no limit. The bot stops when it has bought this many
  (it counts estimated wins).

The bot stops at whichever limit comes first, then turns the screen off (it does not shut the phone down).

### Touching the screen

See [section 5](#5-touching-the-phone-while-it-runs).

### Advanced settings

**How to start** - a short reminder of the steps.

**Setup** - shows whether the accessibility service is on, with **Open accessibility settings** and **Open app info**
(for "Allow restricted settings", see [Install](#1-install)).

**Secondary password** - the game's auction house secondary password protects your Starry Gems. The safest way is to
**leave this empty and type the password in the game yourself** when the game asks (the bot waits and shows a note). If
you want the bot to type it for you, you can save it here: it is stored **encrypted** (Android Keystore), never written to
the log, never included in reports and not included in Android backups, and no screenshot is kept while the game's
password window is open. Saving it means anyone who can start the bot on your unlocked phone can bid with it. Tick
**Show** only to check what you typed (screenshots of the settings are blocked while it is shown).

**Reading numbers** - **Google text recognition (second reader for numbers)**, **off** by default. The bot reads prices
and timers itself; this only adds Google's ML Kit as a second opinion for numbers it cannot read. If you switch it on,
Google Play services downloads the recognition model on first use and may send usage statistics to Google (see
[SECURITY.md](SECURITY.md)). The change applies at the next START.

**Screen and battery**

- **Power saving (recommended)** (on by default): dims the screen while the bot runs and checks the screen less often
  while it is only waiting (for example for running bids to end).
- **Keep the screen on while the bot runs (needed):** the bot sees the game through screenshots, so the screen must stay
  on. Leave this on.
- **Very dark screen while the bot runs:** dims the screen to a minimum. Recognition is not affected.
- **Open battery settings:** lets the app run without battery limits, so that Android does not stop it at night.

**Pocket mode now** - **Pocket mode on now (only for this run)**, see [section 5](#5-touching-the-phone-while-it-runs).

**IH button**

- **Show the IH button only in Idle Heroes:** the floating button hides in other apps (while the bot is running it is
  always visible).
- **Bring back the IH button (bottom left):** use this if you dragged it somewhere you cannot reach.
- **Forget the learned game:** the app remembers which allowed game app it saw last; this resets it (only Idle Heroes from
  Google Play is allowed).
- **Turn IH off completely:** switches the accessibility service off. To turn it on again, use
  Settings -> Accessibility -> IH Auction Bot.

**Updates** - shows the installed version. **Check for updates (opens GitHub)** opens the releases page. There is no
automatic download and no network access in the app.

**Reports** - **Save report** and **Share latest report**, see [section 8](#8-reports). The checkbox **Flight recorder**
(off by default) keeps the last ~3 minutes of analysed screenshots on the phone so that a report can show exactly what
happened.

**Log** - the last log lines of the bot. **Refresh log** reloads them. Common lines are explained in
[Troubleshooting](#9-troubleshooting).

## 4. Start and stop

**Start:** either tap the big green **START** in the app (it then switches to Idle Heroes; open the *Bidding Market*), or
in the game's *Bidding Market* tap the round **IH** button, then **▶ Start**.

Before a start from the app, a window **Before you start** may list warnings: the phone is not charging, battery
optimization is on for the app (button **Battery settings**), or a tip to turn on Do Not Disturb. These are only hints:
**Start anyway** starts the bot, **Cancel** goes back.

**Stop:** the big red **STOP** in the app stops the bot at once, just like **■ Stop** on the IH button.

The IH button colours: **green** = running, **yellow** = paused, **grey** = off. The panel title shows
*Bot is ready / running / paused / off*, and a status line shows progress, for example
`Running · 3 of 20 copies · approx. 12/50 in My Biddings · looking for C-`.

**Ways to stop (all of them always work):**

| Way | What happens |
|---|---|
| **IH button -> ■ Stop** | Normal stop |
| **❚❚ Pause / ▶ Resume** | Pauses; the bot looks at everything anew when resumed |
| **Power button** | Screen goes off, so the bot stops (it cannot see anything) |
| **Volume down 3 times quickly** (within 2 s) | **Emergency stop** |
| Both volume keys briefly together | Also emergency stop (do **not** hold them for 3 seconds: Android may start TalkBack) |
| Leave the game (e.g. swipe home) | The bot stops after about 2 seconds |
| Hold the **IH** button for 2 s | Leaves pocket mode (see below) |

## 5. Touching the phone while it runs

Setting **"When I touch the screen during a run:"**

- **Nothing - no shield (default).** Your touches go through to the game as normal. Safest for the bot's own taps.
  Careful: touching the game while the bot runs can confuse it. Use Stop first.
- **Bot stops (beta).** A thin invisible shield swallows your first touch and stops the bot.
- **Pocket mode (beta).** All touches are swallowed while the bot keeps running, so it can ride in your pocket.
  It only switches on once the bot has seen Idle Heroes in front, and you can see it: a thin coloured frame and the
  label *Pocket mode - hold IH 2 s*. The IH button shows a backpack icon. To get out: **hold the IH button for 2 seconds**, press the **power button**,
  or use the emergency stop (**volume down 3 times**). There is also a checkbox **Pocket mode on now (only for this run)**.

Both shields are **still being tested**. If the bot's taps do not get through or you cannot use your phone, switch back to
"Nothing". A shield only exists while the bot is really running, never after a stop, restart or reboot.

## 6. Running overnight

- **Plug in the charger.** The screen has to stay on, which drains the battery quickly.
- Leave **Power saving (recommended)** on; turn on **Very dark screen while the bot runs** to save even more.
- Turn on **Do Not Disturb**, so calls, messages and notifications do not pop up over the game (a notification on top
  of the game can hide the buttons the bot needs).
- Set **battery optimization** for IH Auction Bot (and the game) to **Unrestricted / Don't optimize**
  (Settings -> Apps -> IH Auction Bot -> Battery), otherwise Android may put the app to sleep.
- Set a **budget** and an **end time** before you go to sleep, and a sensible **goal**. Never run it without a budget.
- Do a **short test run** first (for example goal 5 copies) and watch the first 5 minutes: does it set the filter,
  sort the list, and only open copies that fit your rule?
- Keep your phone's auto-lock/"screen timeout" in mind: the bot keeps the screen on while it runs, but check that it works on your phone.
- In the morning, check the log and the status line ("Stopped: ... N copies bought").

The bot can recover from some problems on its own (loading screens, toasts, a dropped connection), but it **stops** instead
of guessing when it does not know what it sees.

**Checks before and during a long run:**

- Before a start from the app, **Before you start** warns if the phone is not charging, if battery optimization is on for
  the app, and suggests Do Not Disturb (see [section 4](#4-start-and-stop)). The same notes go into the log at every start.
- **Too hot:** above **45 °C** battery temperature the bot pauses (no taps, no screenshots) until the phone is below
  **40 °C**, then it bids again. Status: *Paused: the phone is too hot (46 °C) - bidding again below 40 °C*.
- **Battery low:** below **15 %** and not charging, the bot stops and locks the screen
  (*Battery below 15 % and not charging - bot stopped*).

**Lost connection:** when the game shows "Lost connection with game server.", the bot waits 60 seconds, then taps the yellow
**Confirm** button of that message (log: `Lost connection - tapping Confirm (reconnect 1 of 5)`). It then waits up to 3 minutes,
tapping nothing, until the auction house is back, and sets the filter and sorting again. If the game ends up somewhere else
(for example the main screen), the bot stops. It reconnects at most **5 times per run**; the 6th lost connection stops it.

## 7. Buyout (optional, off by default)

Besides bidding, the game also lets you buy a copy instantly for its **buyout price**. The bot can do this too, but:

- It is **off by default** (a global switch in the settings).
- Each rule has its **own maximum buyout price**; the bot never pays more than that.
- A buyout **counts toward your SG budget** and toward your **goal** (copies bought).
- With buyout on, the bot sets the game's filter **max price** up to your maximum buyout price (if that is higher), so all
  copies you could buy out are listed. It still **bids** only on copies up to the rule's normal max price.
- The bot only taps **Buyout Price** if the Bid window shows the same buyout price as the list and it is at most your maximum.
  Otherwise it closes the window with the red X.
- If the game shows a confirmation window after **Buyout Price** that the bot does not know yet, it taps nothing and stops
  (`Buyout: unknown confirmation window - save a report so this window can be learned`). Please save a report then.
- A buyout is **final**: you cannot be outbid, but you also cannot take it back. A misread price would be costly.
  Only turn it on if you understand this, and start with a low maximum and a small budget.

With the switch off (the default) the bot **only ever taps "Bid"**, never "Buyout".

**Where to set it:** the checkbox **Buyout (beta)** is on the main screen below the rules. The maximum is the field
**Max buyout (SG)** in each rule's dialog (**Edit** or **+ Add rule**). The rule row then reads
*B-: up to 61 SG · buyout up to 45 SG*; while the checkbox is off it adds *buyout is switched off*. During a run the status
counts buyouts and reconnects, e.g. *Running - bidding on B- (12 bids, 3 copies won · 1 bought out · 2 reconnects)*.

## 8. Reports

When something goes wrong, a **report** helps to find out why. Reports are **only stored on your phone** (the last 5).

- A report is saved **automatically after every run**, when the bot gets stuck, and when you tap the button.
- Reports, the log and saved screenshots are deleted after **14 days**. **Delete reports, log and screenshots**
  (Settings -> Reports) deletes them at once.
- **Save report:** Settings -> Reports -> **Save report** (or the **📝 Save report** button in the IH panel).
- **Share latest report:** Settings -> Reports -> **Share latest report** opens Android's share sheet with one ZIP file.
  You choose the app (mail, chat, GitHub issue attachment ...). Nothing is sent unless you do it.
- A report contains the app version, your settings **without** the password, the log, and a few screenshots with what the
  bot recognised in them. It never includes a screenshot of the password window.
- Screenshots show the game only: the bot keeps a screenshot only when Idle Heroes is alone in front (no other app, no
  notification shade, no keyboard). They may still show your **in-game name, chat or other personal information from
  the game**. **Look at them before you share, and share only with people you trust.** On a public GitHub issue
  everybody can see them.
- Screenshots the bot was unsure about stay **inside the app** (not in your gallery) and go into the next report.

## 9. Troubleshooting

| What you see | What it means / what to do |
|---|---|
| `Budget used up` / `SG budget used up: X of Y SG are tied up in bids - waiting` | Your SG budget is committed to running bids. The bot waits; when bids end (won or outbid) it continues or stops. Raise the budget if you want more. |
| `Not enough SG left for any bid - bot stopped` | Your SG balance is below the cheapest copy it would bid on. Lower the rules, or wait until SG from outbid bids come back. |
| `Waiting: SG balance (top right) not readable - no bid` | The bot cannot read your SG at the top right, so it will not bid. Check that nothing covers the corner (notifications, other overlays). |
| `Game reports: 50 bids reached - cleaning up My Biddings` | The game's "-7" message: the 50-bid limit. The bot opens *My Biddings*, bids again on outbid rows if the price fits, otherwise dismisses them with the red X, then goes back. Normal. |
| `Bidding Items` counter | The counter in *My Biddings*. Below 50 = there is room, so the bot goes back and keeps bidding. Close to 50 = it cleans up first. |
| `50 bids - bidding again from 14:03:25` | Nothing could be freed; the bot waits until the earliest running bid ends. |
| `Lost connection to the game - bot stopped` | The game lost its server connection and the bot could not get back (already 5 reconnects in this run, the Confirm button was not recognized, or the auction house did not come back within 3 minutes). Check your internet and restart the game. |
| `Buyout: unknown confirmation window - save a report so this window can be learned` | After "Buyout Price" the game showed a window the bot does not know. It tapped nothing. Finish or cancel the buyout yourself and save a report. |
| `Loading screen (golden star) - waiting, tapping nothing` | Normal. The game is loading. |
| `Game says: operated too frequently - waiting 5 s (1 of 5 in a row)` | The game's rate limit ("Operated too frequently. Please retry in N second(s)."). The bot taps nothing for 5 s (10, 20, 40, up to 60 s if it comes again within 2 minutes). It reloads the list at most every 5 s anyway. Rare = normal. |
| `The game said "Operated too frequently" 5 times in a row - bot stopped` | The game kept refusing despite the pauses. Wait a while, then start again; if it keeps happening, save a report. |
| `Unknown message over the list (width ...) - not tapping through it, waiting 3 s` | A message the bot does not know lay over the list. It waits 3 s before the next tap (longer if it repeats). If you see this often, save a report so the message can be learned. |
| `Unknown message over the Bid window (width ...)` / `... over the confirm window` | The same in the Bid or confirm window: the bot pauses and never taps Bid or Confirm under it; if it stays, it closes the window with the red X. |
| `Nothing to bid right now - next reload of the list at 23:14:05` | Normal. With nothing to bid (and nothing fitting ending within a minute) the bot refreshes only every 5-15 s. |
| `A bid shows a price above the rule's maximum - bot stopped, please save a report` | Safety stop: after a bid, your own row (clock) showed a price above max price + 1 twice. Check *My Biddings* and save a report. |
| `Idle Heroes is not in front or not recognised yet` | The bot only acts in Idle Heroes and learns the game's window from the game itself. If you switched the service on while the game was already open, switch to another app and back once (or press START in this app, which opens the game). |
| `Not clear which app is in front` / `Gesture skipped` | For a moment the bot could not confirm that Idle Heroes is in front, so it tapped nothing. If it keeps appearing, close pop-ups or notifications over the game. |
| `Unknown screen - please go to the auction house` / `I don't recognise the auction house` | The bot does not know what is on screen (it tapped nothing). Go to the Bidding Market and press Start again. A screenshot is kept in the app for the next report. |
| `Did not recognize the auction house for too long` | Same, but it kept happening, so the bot stopped. |
| `Could not set the filter` | The bot could not set rank/price in the game's filter. Make sure the game is on the Bidding Market and try again. |
| `Price fields in the filter cannot be filled in - the bot checks the prices itself; trying again at ...` | Not an error. The bot checks each row's price itself and tries the price fields again later (after 10, 20, 40 minutes, or after 2 minutes if a list looks filtered by another rule's prices). |
| `Please enter the secondary password in the settings` / `Secondary password missing` | The game asked for it. Type it in the app settings. |
| `Wrong secondary password? The game did not accept it` | Check the password in the settings. |
| `Bid ... did NOT arrive (no icon on the row) - does not count` | The game did not accept the bid (slow network?). The bot retries later; the row is ignored for 60 seconds. |
| `A window did not close - bot stopped so it does not tap anything wrong` | Safety stop. Look at the game, close the window, start again. |
| `Unknown window after X in My Biddings` | Safety stop during cleanup. Please share a report. |
| `Tap locked: ...` | The safety lock blocked a tap (for example in the top bar). Normal; it is protecting you. |
| `Screen touched - bot stopped` / `The game is no longer in front - bot stopped` | You touched the screen (with the "Bot stops" option), or the game was left. |
| `Pocket mode: the bot's taps did not get through` | Pocket mode shield problem. Switch to "Nothing", and share a report. |
| `Goal reached` / `End time reached` | Not a problem: the bot finished. |

The bot does not buy at all? Check: the rule is **On**, the price range matches what is in the market, the **time limit**
is not too short, and the SG balance is readable.

## 10. FAQ

**Is it safe for my account?** No bot is safe from a ban. This one never buys with real money and never taps the purchase "+",
but automation is probably against the game's rules. Your risk.

**Does it need internet?** The app does not. (The game, of course, does.)

**Does it flood the game with requests?** No. It reloads the list (refresh, filter, sort order) **at most once every 5 seconds,
always**, respects the game's "Operated too frequently" message (it pauses, longer each time, and stops after 5 in a row),
and when there is nothing to bid it refreshes the list at most every 5-15 seconds.

**How is the SG budget counted?** Per run, as **net SG spent**: copies bought plus bids that are still running. If you are
outbid, the SG come back and can be used again. SG you add during the run do not raise the budget.

**Does it need root, a PC or a computer?** No.

**Can I use my phone while it runs?** With the default setting your touches go to the game and can disturb the bot.
Use Stop first, or try pocket mode (beta).

**It does not work on my phone.** The recognition is calibrated for a 2712 x 1220 (20:9) screen at 440 dpi. Other layouts may fail.

**Why does Android say the app can see my screen?** That is how accessibility services work. See [SECURITY.md](SECURITY.md)
for what the app does with that power, and how to check it.

**I got a new phone (or restored a backup) and the app asks for my OK again.** That is on purpose: your OK is not
included in Android backups, so each phone asks once.

**How do I remove it?** Turn the service off (Settings -> Accessibility), then uninstall the app. Uninstalling deletes
everything the app stored.

**What are Starry Gems (SG)?** The auction-house currency of Idle Heroes. SG can come from purchases with real money, so
treat the SG budget like a spending limit.

**Where do I report problems?** On the [GitHub issues page](https://github.com/Hustle-Bot/idle-heroes-auction-bot/issues).
Attach a report if you can, after checking the screenshots for personal information.
