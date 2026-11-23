#!/usr/bin/env python3
"""Checks the results of the emulator test (bot against the fake game). Exit 1 = something is wrong."""
import re
import sys
from pathlib import Path

out = Path(sys.argv[1])
fehler = []
hinweise = []
# Nov 23: what the emulator cannot test (e.g. the volume keys) – neither passed nor failed, it needs a phone test.
grenzen = []


def lies(name):
    p = out / name
    return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""


att = lies("attrappe.log").splitlines()
prot = lies("protokoll.txt")
logcat = lies("logcat.txt")
ablauf = lies("ablauf.txt").splitlines()


def zeit_von(marke):
    for z in ablauf:
        if marke in z:
            try:
                return int(z.split()[0])
            except ValueError:
                return None
    return None


def zeilen(art):
    return [z for z in att if re.match(r"^\d+ " + art + r"\b", z)]


gebote = zeilen("GEBOT")
nach = zeilen("NACHGEBOT")
weg = zeilen("WEG")
limit7 = zeilen("LIMIT7")


def vom_test(i):
    """
    Nov 23 (run 27): is the VERBOTEN line att[i] about a tap of the TEST itself? The fake game writes the tap right
    before it ("TIPP x y ..." with the same time); the test taps with adb at x 2500, the bot never taps there.
    """
    for z in reversed(att[max(0, i - 3):i]):
        m = re.match(r"^(\d+) TIPP (\d+) (\d+)", z)
        if m:
            return m.group(1) == att[i].split()[0] and abs(int(m.group(2)) - 2500) <= 15
    return False


# Run 27: the test's own touch passed the shield and landed on the fake game's loading screen – the fake game counts
# that as "forbidden", but it was no tap of the bot. Only the bot's forbidden taps are an error.
verboten, verboten_test = [], []
for i, z in enumerate(att):
    if re.match(r"^\d+ VERBOTEN\b", z):
        (verboten_test if vom_test(i) else verboten).append(z)
print(f"Fake game: {len(att)} lines, bids {len(gebote)}, rebids {len(nach)}, removed {len(weg)}, -7 {len(limit7)}, forbidden {len(verboten)}")

if not any("BEREIT" in z for z in att):
    fehler.append("The fake game did not get ready (loading the atlas?)")
if len(gebote) < 3:
    fehler.append(f"too few bids: {len(gebote)}")
if verboten:
    fehler.append("forbidden taps:\n  " + "\n  ".join(verboten[:20]))
if verboten_test:
    hinweise.append("a tap of the TEST (not of the bot) reached a place the fake game forbids:\n  " + "\n  ".join(verboten_test[:5]))
if not any(re.match(r"^\d+ PASSWORT ok", z) for z in att):
    fehler.append("The secondary password was not accepted (text input / Verify?)")
if not weg:
    fehler.append("no red X was tapped in My Biddings (tidying up?)")


def tipps(x, y, von, bis, toleranz=15):
    """Taps at the fake game near (x, y) between the times von..bis (ms on the emulator)."""
    treffer = []
    for z in att:
        m = re.match(r"^(\d+) TIPP (\d+) (\d+)", z)
        if m and abs(int(m.group(2)) - x) <= toleranz and abs(int(m.group(3)) - y) <= toleranz:
            t = int(m.group(1))
            if (von is None or t >= von) and (bis is None or t <= bis):
                treffer.append(z)
    return treffer


def handlungen(von, bis):
    """What the bot did in the fake game (taps, swipes, bids) between von..bis."""
    if von is None:
        return []
    return [z for z in att if re.match(r"^(\d+) (TIPP|WISCH|GEBOT|NACHGEBOT)", z)
            and int(z.split()[0]) > von and (bis is None or int(z.split()[0]) < bis)]


k1, start, tasche, fremd_t = zeit_von("CHECK-1"), zeit_von("BOT START"), zeit_von("POCKET ON"), zeit_von("FOREIGN-POCKET")
notaus, k2, start2 = zeit_von("EMERGENCY-STOP"), zeit_von("CHECK-2"), zeit_von("START-2")
# Nov 23: emergency stop through the debug test control (only if the volume keys did not arrive, see emulator-test.sh).
notaus_test = zeit_von("TEST-CONTROL EMERGENCY STOP")
beruehrung, k3, ende = zeit_von("TOUCH"), zeit_von("CHECK-3"), zeit_von("TEST END")
anr = [z for z in ablauf if "ANR window" in z]
if anr:
    hinweise.append(f"{len(anr)} times \"System UI isn't responding\" removed in the emulator (not an error of the bot)")
zurueck = [z for z in att if " ZURUECK" in z]
if zurueck:
    hinweise.append(f"\"Back\" reached the fake game {len(zurueck)} times (from the test or from the bot?)")
gross = [z for z in att if " GROESSE " in z]
if gross:
    print("Fake game:", gross[-1].split(" ", 1)[1])

if k1 and not tipps(2500, 650, k1, start):
    fehler.append("The check tap before the start did not arrive (tap positions shifted in the emulator?)")

# Nov 23 (runs 21 and 24): the bot stopped by itself in the middle of the test (emulator-test.sh then wakes the screen
# and starts it again, so that the later parts still test something). Always an error – with the bot's stop reason.
eigene_stopps = [z.split("  ", 1)[-1] for z in prot.splitlines() if re.search(r"\bStop: ", z)]
for z in ablauf:
    if "BOT STOPPED BY ITSELF" in z:
        fehler.append("The bot stopped by itself " + z.split("BOT STOPPED BY ITSELF", 1)[1].strip()
                      + ("\n  " + "\n  ".join(eigene_stopps[:5]) if eigene_stopps else ""))
if "the list moved before the red X" in prot:
    hinweise.append(f"My Biddings: {prot.count('the list moved before the red X')} times the red X opened the Bid window "
                    "of another row (the list moved) – closed without a bid (new since Nov 23, before: stop)")

# 2. Pocket mode: 5 foreign taps. If one falls exactly into the strip the shield is opening for a tap of the bot right
# now, it gets through (chance). Without a shield all 5 would get through.
if fremd_t:
    fremd = tipps(2500, 700, fremd_t, fremd_t + 12000)
    if len(fremd) >= 3:
        fehler.append("Pocket mode: foreign taps got through:\n  " + "\n  ".join(fremd))
    elif fremd:
        hinweise.append(f"Pocket mode: {len(fremd)} of 5 foreign taps got through (shield open for the bot just then?)")
    danach = handlungen(fremd_t + 8000, notaus)
    print(f"Actions of the bot in pocket mode: {len(danach)}")
    if len(danach) < 5:
        fehler.append(f"Pocket mode: hardly any taps of the bot got through ({len(danach)})")

# 3. Emergency stop
if notaus:
    if re.search(r"Emergency stop: (both volume keys|volume down 3 times)", prot):
        hinweise.append("Emergency stop: the volume keys reached the accessibility service in the emulator")
    elif notaus_test and "Emergency stop: test control" in prot:
        # Nov 23: runs 9–24 never got the keys through to the accessibility service in the emulator (console and
        # sendevent tried). The emergency stop itself was triggered through the test control and is checked below.
        grenzen.append("Emergency stop with both volume keys: the emulator did not pass the keys to the accessibility "
                       "service (also not in runs 9–24) – NEEDS A PHONE TEST. The same emergency stop via the test "
                       "control was checked instead (bot stops, shield gone, the next tap arrives).")
    else:
        fehler.append("The emergency stop did not arrive (log)"
                      + (" – not even through the test control" if notaus_test else " (both volume keys)"))
    # From when the bot must be still: the emergency stop that really happened (keys, or the test control after them).
    wirksam = notaus_test or notaus
    kontrolle = tipps(2500, 650, k2, start2)
    still = [z for z in handlungen(wirksam + 3000, start2) if z not in kontrolle]
    if still:
        fehler.append("After the emergency stop the bot kept tapping:\n  " + "\n  ".join(still[:10]))
    if k2 and not tipps(2500, 650, k2, start2):
        fehler.append("After the emergency stop a tap did not arrive (shield still there?)")

# 4. A touch stops the bot
if beruehrung:
    if tipps(2500, 720, beruehrung, beruehrung + 4000):
        fehler.append("The touch during the run reached the fake game (shield missing?)")
    if "Screen touched" not in prot:
        fehler.append("The touch did not stop the bot (log)")
    if k3 and not tipps(2500, 650, k3, ende):
        fehler.append("After the touch the next tap did not arrive (shield still there?)")
    if handlungen(beruehrung + 3000, k3):
        fehler.append("After the touch the bot kept tapping")
# Only the one touch from step 4 may have stopped the bot – otherwise the bot stopped itself with one of its own taps
# (its tap landed on the shield instead of in the game).
if prot.count("Screen touched – stopping the bot") > 1:
    fehler.append("\"Screen touched\" more than once – did the bot stop itself with one of its own taps?")
for grund in ("Shield guard", "Shield could not be turned on", "could not be placed above the shield", "Idle Heroes was no longer in front"):
    if grund in prot:
        hinweise.append(f"Log: \"{grund}\"")

if "🎒 Pocket mode on" not in prot:
    fehler.append("Log: pocket mode did not turn on")
# Field test 1.30 (Android 16): taps of the bot got stuck on the shield – that is a bug of the app.
haengen = prot.count("got stuck on the shield")
if haengen:
    # Nov 23: known in the emulator since run 18 (it updates the shield's windows slowly); also seen once on Android 16.
    fehler.append(f"Shield: {haengen} times a tap of the bot got stuck on the shield (known since emulator run 18)")
if "Bot started" not in prot:
    fehler.append("Log: bot not started")
if "Recognition error" in prot:
    fehler.append("Log: recognition error")
# The bot app and the fake game: emulator-test.sh passes their application ids (another build can have its own).
pakete = sys.argv[2:] or ["io.github.hustlebot.ihauctionbot", "io.github.hustlebot.ihauctionbot.attrappe"]
for paket in pakete:
    # Only the whole name counts: the bot's id is the beginning of the fake game's id.
    if re.search(r"FATAL EXCEPTION[\s\S]{0,400}" + re.escape(paket) + r"(?![\w.])", logcat):
        fehler.append(f"Crash in {paket} (logcat)")

for h in hinweise:
    print("NOTE:", h)
for g in grenzen:
    print("EMULATOR LIMITATION – needs a phone test:", g)
for f in fehler:
    print("ERROR:", f)
grenz_text = f" ({len(grenzen)} emulator limitation(s) – needs a phone test, see above)" if grenzen else ""
print("RESULT:", ("OK" if not fehler else f"{len(fehler)} errors") + grenz_text)
sys.exit(1 if fehler else 0)
