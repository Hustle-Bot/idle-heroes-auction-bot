#!/usr/bin/env bash
# Test of the REAL app in an Android emulator against the fake game (for example in a CI job with an emulator).
# Results (logs, screenshots) end up in e2e-ergebnis/.
#
# Steps (field test 1.20 – the shield now lies over the game during the whole run):
#   1. The bot runs normally (filter, bids, secondary password, tidying up My Biddings).
#   2. Pocket mode on: 5 foreign taps must NOT arrive, the bot keeps running.
#   3. Emergency stop (both volume keys): the bot stops, the shield is gone – a tap arrives again.
#      Nov 23: the console's keys never reached the accessibility service (runs 9–25); written into the input device as
#      root (sendevent) they do (run 26). If the keys do not arrive, the script says so ("EMULATOR LIMITATION – needs a
#      phone test") and triggers the same emergency stop through the debug test control, so that "bot stops, shield gone,
#      tap arrives" is still checked.
#   4. Bot restarted (normal): a foreign touch is swallowed and stops the bot; afterwards the shield is gone.
# Nov 23: if the bot stops by itself in between (runs 21 and 24), that is reported as an error, the screen is woken
# (such a stop locks it) and the bot is started again, so that the later parts still test something.
set -u
cd "$(dirname "$0")/.."
OUT="$PWD/e2e-ergebnis"
mkdir -p "$OUT"
# Which build of the app is tested: the public app "oss" and its application id.
FLAVOR_WUNSCH="${FLAVOR:-}"
FLAVOR=oss
BOT=io.github.hustlebot.ihauctionbot
APK="app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"
STEUER="$BOT/io.github.hustlebot.ihauctionbot.app.TestSteuerung"
ATT=io.github.hustlebot.ihauctionbot.attrappe
ATT_ACT="$ATT/io.github.hustlebot.ihauctionbot.attrappe.app.SpielActivity"
LAUF_MIN="${LAUF_MIN:-4}"

zeit() { adb shell date +%s%3N | tr -d '\r'; }
bild() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
notiz() { echo "$(zeit) $*" | tee -a "$OUT/ablauf.txt"; }
steuer() { adb shell am broadcast -n "$STEUER" -a "$BOT.$1" "${@:2}" | grep -v '^Broadcasting' | tee -a "$OUT/ablauf.txt"; }
# Small preview image as text in the GitHub log (the screenshots in the artifact cannot be fetched from the cloud).
vorschau() { adb exec-out screencap > "$OUT/$1.raw" 2>/dev/null && python3 e2e/vorschau.py "$OUT/$1.raw" "$1" 8; rm -f "$OUT/$1.raw"; }
# The slow emulator sometimes shows "System UI isn't responding" ON TOP of everything – then the bot only sees that
# window and no tap arrives (run 6). Remove the window with "Back" (= "Wait"); the fake game ignores "Back".
anr_weg() {
  for n in 1 2 3 4; do
    f=$(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r')
    case "$f" in
      *"Not Responding"*|*"isn't responding"*)
        notiz "ANR window removed ($1): $f"
        adb shell input keyevent KEYCODE_BACK
        sleep 3 ;;
      *) return ;;
    esac
  done
}
# Interim state into the GitHub log (if the run is aborted, something can still be seen).
lage() {
  echo "--- State: $1"
  adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp|mDisplayRotation|mRotation=" | head -4
  echo "    Fake game (process $(adb shell pidof $ATT | tr -d '\r')):"; adb shell run-as "$ATT" tail -n 6 files/attrappe.log 2>/dev/null | sed 's/^/      /'
  echo "    Bot:"; adb logcat -d -s IHBot 2>/dev/null | tail -n 10 | sed 's/^/      /'
}
# Nov 23: state of the bot from the debug test control: LAEUFT (running), PAUSE, GESTOPPT (stopped) or BEREIT, plus
# the last stop reason.
zustand() { adb shell am broadcast -n "$STEUER" -a "$BOT.TEST_STATUS" | sed -n 's/.*data="\(.*\)".*/\1/p' | tr -d '\r'; }
# Nov 23: some stops lock the screen on purpose (e.g. "unknown window"). Wake it and close the lock screen (the emulator
# has no PIN), so that the fake game is in front again.
wecken() {
  w=""
  for n in 1 2 3; do
    adb shell input keyevent KEYCODE_WAKEUP
    sleep 1
    adb shell wm dismiss-keyguard
    sleep 3
    w=$(adb shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*' | tr -d '\r')
    [ "$w" = "mWakefulness=Awake" ] && break
  done
  notiz "Screen ($1): $w, $(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/^ *//')"
}
# Nov 23 (runs 21 and 24: the bot stopped after a minute, all later parts failed with it): if the bot is no longer
# running, note it (pruefen.py reports "BOT STOPPED BY ITSELF" as an error), wake the screen and start it again.
# $2 = tasche: turn pocket mode on again.
weiter_laufen() {
  st=$(zustand)
  case "$st" in LAEUFT*|PAUSE*) return 0 ;; esac
  notiz "BOT STOPPED BY ITSELF ($1): $st"
  # Run 27: such a stop locks the screen 3 s later – wait for that, otherwise it locks again right after waking it
  # (then the restarted bot, the volume keys and CHECK-2 all ran with the screen off).
  sleep 5
  wecken "$1"
  steuer TEST_START
  notiz "RESTARTED ($1): $(zustand)"
  if [ "${2:-}" = tasche ]; then
    sleep 5
    steuer TEST_TASCHE
  fi
  sleep 15
}

adb wait-for-device
notiz "Emulator ready: $(adb shell getprop ro.build.version.release | tr -d '\r')"
# Do not show error dialogs ("… isn't responding") at all (applies from the next display change, i.e. right away).
adb shell settings put global hide_error_dialogs 1
# Run 16: with gesture navigation, a swipe of the bot that starts far down sent the fake game home (the launcher came
# to the front, the bot correctly stopped). So the test uses three-button navigation – right at the start, so that the
# system UI calms down before the test (switching it used to make it hang).
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
sleep 15
# Like the test phone (Motorola): 1220 x 2712, landscape.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell wm size 1220x2712
adb shell wm density 440
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell wm size; adb shell wm density

notiz "App build: $FLAVOR ($BOT)"
adb install -r -g "$APK" | tee -a "$OUT/ablauf.txt"
adb install -r -g attrappe/build/outputs/apk/debug/attrappe-debug.apk | tee -a "$OUT/ablauf.txt"

# Turn on the bot's accessibility service.
adb shell settings put secure enabled_accessibility_services "$BOT/io.github.hustlebot.ihauctionbot.app.BotDienst"
adb shell settings put secure accessibility_enabled 1
sleep 8
notiz "Accessibility services: $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"

# Rule as in the game test: C- 10–20, only the last 5 minutes. Pocket mode not automatic (a touch stops the bot).
# 1.31 (default "Nothing" – no shield): part 1 runs without a shield, like on a real phone. Part 2 turns on pocket
# mode during the run, part 4 sets "Bot stops" beforehand.
steuer TEST_EINSTELLEN --es regeln "C-,10,20,5" --es passwort 1234 --es beruehrung NICHTS

# Start the fake game: limit 12 entries, 9 bids are already in My Biddings (some outbid).
adb shell am start -n "$ATT_ACT" --ei limit 12 --ei start 9 --el ueberbieten 30000 --ei laden 5 --ei bestaetigen 4 --es passwort 1234 | tee -a "$OUT/ablauf.txt"
sleep 25
anr_weg "fake game started"
bild 00-attrappe
lage "fake game started"
vorschau 00-attrappe
notiz "CHECK-1 (the tap must arrive at the fake game)"
adb shell input tap 2500 650
sleep 2

# --- 1. Normal run
steuer TEST_START
notiz "BOT START"
for i in $(seq 1 $((LAUF_MIN * 2))); do
  sleep 30
  anr_weg "run"
  bild "lauf-$i"
  [ "$i" = 2 ] && vorschau lauf-2
  [ $((i % 2)) = 0 ] && lage "run after $((i / 2)) min"
done
weiter_laufen "normal run"

# --- 2. Pocket mode: foreign taps are swallowed, the bot keeps running
anr_weg "before pocket mode"
notiz "POCKET ON"
steuer TEST_TASCHE
sleep 10
bild tasche-1
vorschau tasche-1
notiz "FOREIGN-POCKET (5 taps, must NOT arrive)"
for k in 1 2 3 4 5; do adb shell input tap 2500 700; sleep 1.3; done
for i in 1 2 3 4; do
  sleep 30
  anr_weg "pocket mode"
  bild "tasche-lauf-$i"
done
lage "pocket mode"
weiter_laufen "pocket mode" tasche

# --- 3. Emergency stop: both volume keys briefly together
notiz "EMERGENCY-STOP"
# Run 27: with the screen off Android does not pass the volume keys on at all – note the state for the analysis.
notiz "Screen before the keys: $(adb shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*' | tr -d '\r')"
# Nov 23 (diagnostics): which input device has the volume keys, and does the kernel see the keys at all?
adb shell getevent -lp > "$OUT/eingabegeraete.txt" 2>&1 || true
TASTEN_GERAET=$(awk '/^add device/ {d = $NF} /KEY_VOLUMEDOWN/ {print d; exit}' "$OUT/eingabegeraete.txt" | tr -d '\r')
timeout 15 adb shell getevent -lt > "$OUT/getevent.txt" 2>&1 &
sleep 1
notaus_da() { adb logcat -d -s IHBot | grep -q "Emergency stop:"; }
# 1st way: "adb shell input keyevent" injects the keys behind the accessibility service's filter – onKeyEvent never sees
# them (runs 9/14/15). Through the emulator console they should come like the device's own buttons.
# (Run 16: the console does not know "EV_SYN:SYN_REPORT" – keys one by one, then EV_SYN with a number.)
adb emu event send EV_KEY:KEY_VOLUMEDOWN:1 EV_KEY:KEY_VOLUMEUP:1 | tee -a "$OUT/ablauf.txt"
adb emu event send EV_SYN:0:0 | tee -a "$OUT/ablauf.txt"
sleep 0.15
adb emu event send EV_KEY:KEY_VOLUMEDOWN:0 EV_KEY:KEY_VOLUMEUP:0 | tee -a "$OUT/ablauf.txt"
adb emu event send EV_SYN:0:0 | tee -a "$OUT/ablauf.txt"
sleep 4
# 2nd way (Nov 23): write the key events straight into the input device that has the volume keys (sendevent) – for
# Android they then come from that device, like a real button press. (Run 25: the console keys never reached the
# kernel – getevent saw none – and the shell may not write to the device: "Permission denied". The emulator image has
# "su", so as root.)
if ! notaus_da && [ -n "$TASTEN_GERAET" ]; then
  FOLGE="sendevent $TASTEN_GERAET 1 114 1; sendevent $TASTEN_GERAET 1 115 1; sendevent $TASTEN_GERAET 0 0 0; sleep 0.15; sendevent $TASTEN_GERAET 1 114 0; sendevent $TASTEN_GERAET 1 115 0; sendevent $TASTEN_GERAET 0 0 0"
  if adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; then
    notiz "Volume keys once more via sendevent (as root) on $TASTEN_GERAET"
    adb shell "su 0 sh -c '$FOLGE'" 2>&1 | tee -a "$OUT/ablauf.txt"
  else
    notiz "Volume keys once more via sendevent on $TASTEN_GERAET (no su)"
    adb shell "$FOLGE" 2>&1 | tee -a "$OUT/ablauf.txt"
  fi
  # Real volume keys also show Android's volume panel at the right edge – let it go away before CHECK-2.
  sleep 5
fi
if notaus_da; then
  notiz "EMERGENCY-STOP KEYS ARRIVED"
else
  # Runs 9–24: the emulator never passed the volume keys to the accessibility service. That part needs a phone test –
  # the rest of the emergency stop (bot stops, shield gone, tap arrives) is checked with the same function, triggered
  # through the debug test control. pruefen.py reports this as "EMULATOR LIMITATION", not as passed.
  notiz "EMULATOR LIMITATION – needs a phone test: the volume keys did not reach the accessibility service"
  notiz "TEST-CONTROL EMERGENCY STOP (the same emergency stop as the keys)"
  steuer TEST_NOTAUS
fi
sleep 4
anr_weg "after the emergency stop"
bild notaus
notiz "CHECK-2 (after the emergency stop the tap must arrive again)"
adb shell input tap 2500 650
sleep 3
lage "after the emergency stop"
# Review before publishing (Nov 22): key codes are no longer logged – only the emergency stop line.
echo "--- Did the keys reach the accessibility service?"; adb logcat -d -s IHBot | grep "Emergency stop" | tail -n 8
echo "--- Input device with the volume keys: ${TASTEN_GERAET:-none found}; key events the kernel saw:"
[ -n "$TASTEN_GERAET" ] && tr -d '\r' < "$OUT/eingabegeraete.txt" | grep -A1 -E " ${TASTEN_GERAET}\$" | head -n 2
grep -E "KEY_VOLUME" "$OUT/getevent.txt" | head -n 8
echo "--- Accessibility service:"; adb shell dumpsys accessibility | grep -i -E "BotDienst|filterKey|KeyEvents|capabilities" | head -n 8
# Part 4 should not depend on whether the emergency stop worked: stop the bot for sure, then restart with "Bot stops".
steuer TEST_STOPP
sleep 3

# --- 4. Restart (normal): a touch is swallowed and stops the bot
wecken "before part 4"
steuer TEST_EINSTELLEN --es regeln "C-,10,20,5" --es passwort 1234 --es beruehrung STOPPT
steuer TEST_START
notiz "START-2"
sleep 40
weiter_laufen "before the touch"
anr_weg "before the touch"
notiz "TOUCH (must NOT arrive, stops the bot)"
adb shell input tap 2500 720
sleep 5
notiz "CHECK-3 (shield gone – must arrive)"
adb shell input tap 2500 650
sleep 3
lage "after the touch"

notiz "TEST END"
steuer TEST_STOPP
sleep 5

adb shell run-as "$ATT" cat files/attrappe.log > "$OUT/attrappe.log" 2>&1 || true
adb shell run-as "$ATT" cat files/stand.txt > "$OUT/attrappe-stand.txt" 2>&1 || true
adb shell run-as "$BOT" cat files/protokoll.txt > "$OUT/protokoll.txt" 2>&1 || true
adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
echo "=== attrappe.log (start) ==="; head -n 40 "$OUT/attrappe.log"
echo "=== attrappe.log (end) ==="; tail -n 30 "$OUT/attrappe.log"
echo "=== attrappe-stand.txt ==="; cat "$OUT/attrappe-stand.txt"
echo "=== protokoll.txt: shield, pocket mode, stops, blocked taps ==="
grep -iE "shield|pocket mode|emergency stop|touched|stop|locked|test control|in front|list moved" "$OUT/protokoll.txt" | head -n 120
echo "=== protokoll.txt (end) ==="; tail -n 100 "$OUT/protokoll.txt"
# Nov 23: what the bot did right before a tap got stuck on the shield (known since run 18 – for the analysis).
echo "=== protokoll.txt: before taps that got stuck on the shield ==="
grep -B4 "got stuck on the shield" "$OUT/protokoll.txt" | grep -v "Screenshot 2712" | head -n 60
echo "=== logcat (errors) ==="
grep -E "FATAL|Attrappe.*FEHLER|lowmemorykiller|lmkd|has died|Killing [0-9]+:($BOT|$ATT)|Process ($BOT|$ATT)|SIGSEGV|navbar|home gesture" "$OUT/logcat.txt" | tail -n 40
python3 e2e/pruefen.py "$OUT" "$BOT" "$ATT"
