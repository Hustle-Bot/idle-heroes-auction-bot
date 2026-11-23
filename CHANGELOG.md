# Changelog

Version numbers are `1.N`, where `N` is the number of the GitHub Actions run that built the release (tag `v1.N`).

## First public release

First public release of the open-source "oss" build.

- Bids in the Idle Heroes auction house (Bidding Market) following your rules (tier, min/max price, time left);
  only taps "Bid", never above rule max + 1 SG.
- Sets the game's filter and sorting, scrolls the list and measures every swipe.
- Handles the "-7" limit (50 active bids): My Biddings cleanup, rebids outbid rows if the price fits, dismisses the rest.
- Secondary password dialog (typed in the settings, stored encrypted with Android Keystore).
- Loading screens, toasts and pop-ups; SG budget, end time and target copy count.
- Safety: never taps the top bar, never acts on stale screenshots, taps nothing on unknown screens (stops after ~5 min).
- Respects the game's "Operated too frequently" rate limit (pauses 5 s, doubling up to 60 s, stops after 5 in a row) and
  refreshes the list at most every 5-15 s while there is nothing to bid.
- Review round 2: at most one list reload every 5 s, always; SG budget counted per run as net SG spent (top-ups and a misread start add nothing); an unknown message in the Bid or confirm window pauses the bot and is never tapped through; price fields that could not be filled in are tried again later, so a rank's list is no longer lost for the night.
- Touch modes: Nothing / Stop on touch (beta) / Pocket mode (beta); emergency stop with volume down 3x.
- Auto-reconnect after "Lost connection": waits 60 s, taps Confirm, waits up to 3 min for the auction house, then sets filter
  and sorting again (max 5 per run; the 6th loss stops the bot).
- Simple main screen with big START / STOP; power saving (dims the screen, checks less often while waiting); warnings
  before a start (charger, battery optimization, Do Not Disturb); pause when the phone is hotter than 45 degrees C, stop below
  15 % battery when not charging.
- Reports and flight recorder stay on the phone; share them manually. Reports, log and screenshots expire after 14 days
  and can be deleted with one button.
- Privacy and consent: the app explains the accessibility service and needs your **Agree** before the service does
  anything; the service is limited to Idle Heroes (`com.droidhang.ad`); screenshots are kept only while the game is alone
  in front and never go to the gallery; volume keys are watched only during a run; no key codes or other apps' names in
  the log; new installs start with a 500 SG budget.
- Instant buyout is prepared but not offered until it has been tested in the real game: this version only bids.
- Saved screenshots are blacked out outside the game; no tap under another app's window; the window check runs before
  every gesture; log lines older than 14 days are removed; "Share log only"; the saved secondary password is never shown
  again (Saved ✓ / Replace / Delete); warning when the screen is not about 20:9.
- **No internet permission**, no self-update, no uploads, no screen recording.
- English UI and documentation.
