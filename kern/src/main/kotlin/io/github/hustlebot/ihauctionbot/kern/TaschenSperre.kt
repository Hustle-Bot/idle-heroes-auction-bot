package io.github.hustlebot.ihauctionbot.kern

/**
 * Nov 11: pocket mode – the bot runs unattended in a backpack. A transparent shield over the whole
 * screen swallows every touch from outside (the bot's taps still get through, see the app).
 *
 * Unlocking takes TWO steps, so that no touch in the pocket can manage it:
 *  1. hold the IH button for [haltenMs] (without slipping),
 *  2. then tap the big "Unlock" button within [bestaetigenMs].
 * If step 2 does not come in time, everything is locked again. A normal tap on the IH button does nothing.
 *
 * Times in milliseconds (e.g. System.currentTimeMillis()). The app calls [pruefe] after [haltenMs] and after
 * [bestaetigenMs], so that the "Unlock" button appears and disappears on time.
 */
class TaschenSperre(val haltenMs: Long = HALTEN_MS, val bestaetigenMs: Long = BESTAETIGEN_MS) {

    enum class Zustand {
        /** Pocket mode off – everything as usual. */
        AUS,

        /** Locked – touches are swallowed, the IH button opens nothing. */
        GESPERRT,

        /** The IH button is being held right now (not yet for [haltenMs]). */
        HAELT,

        /** Held long enough – now the "Unlock" button is visible (for at most [bestaetigenMs]). */
        FRAGT,
    }

    var zustand: Zustand = Zustand.AUS
        private set

    private var haltenSeit = 0L
    private var fragtSeit = 0L

    /** Is pocket mode on (locked, no matter in which step)? */
    val an: Boolean get() = zustand != Zustand.AUS

    fun einschalten() {
        zustand = Zustand.GESPERRT
    }

    fun ausschalten() {
        zustand = Zustand.AUS
    }

    /** Finger on the IH button. */
    fun knopfGedrueckt(jetzt: Long) {
        pruefe(jetzt)
        if (zustand == Zustand.GESPERRT) {
            zustand = Zustand.HAELT
            haltenSeit = jetzt
        }
    }

    /** The finger slipped on the IH button – that does not count as holding. */
    fun knopfVerrutscht(jetzt: Long) {
        pruefe(jetzt)
        if (zustand == Zustand.HAELT) zustand = Zustand.GESPERRT
    }

    /** Finger lifted from the IH button. Held too briefly → stays locked. */
    fun knopfLosgelassen(jetzt: Long) {
        pruefe(jetzt)
        if (zustand == Zustand.HAELT) zustand = Zustand.GESPERRT
    }

    /** Check the times: held long enough → [Zustand.FRAGT]; not confirmed for too long → [Zustand.GESPERRT] again. */
    fun pruefe(jetzt: Long): Zustand {
        if (zustand == Zustand.HAELT && jetzt - haltenSeit >= haltenMs) {
            zustand = Zustand.FRAGT
            fragtSeit = jetzt
        }
        if (zustand == Zustand.FRAGT && jetzt - fragtSeit > bestaetigenMs) zustand = Zustand.GESPERRT
        return zustand
    }

    /** Should the big "Unlock" button be visible right now? */
    fun zeigtEntsperren(jetzt: Long): Boolean = pruefe(jetzt) == Zustand.FRAGT

    /** "Unlock" tapped. true = unlocked now (pocket mode off); otherwise everything stays as it is. */
    fun entsperrenGetippt(jetzt: Long): Boolean {
        if (pruefe(jetzt) != Zustand.FRAGT) return false
        zustand = Zustand.AUS
        return true
    }

    companion object {
        /** Nov 11: hold the IH button for 3 seconds … */
        const val HALTEN_MS = 3_000L

        /** … then tap "Unlock" within 5 seconds. */
        const val BESTAETIGEN_MS = 5_000L
    }
}

/**
 * Nov 11 (pocket mode): for each gesture of the bot the shield is made passable for a moment – first wait [vorlaufMs]
 * (until Android has really made the window passable), then the gesture, then lock again. Several gestures
 * can overlap (e.g. swipe + hold still); the shield is locked only when ALL of them are done.
 *
 * If a touch still arrives at the shield while a gesture should go through, it got stuck there: then the
 * lead time gets longer. If that happens [MAX_VERSCHLUCKT] times, the bot's taps are not getting through – the app
 * stops the bot (better to stop than to keep running blind).
 */
class SchildDurchlass {
    private var naechste = 1L
    private val laufend = HashSet<Long>()

    var vorlaufMs: Long = START_VORLAUF_MS
        private set

    /**
     * Nov 12 (field test 1.30 on Android 16 and emulator run 15: taps got stuck on the shield, even when it was fully
     * open, and a longer lead time did not help): Android reports "gesture done" before the touch has reached the
     * window – if the shield was already closed again by then, the tap landed on it. The shield stays passable this
     * long after the report; if a tap still gets stuck, longer.
     */
    var nachlaufMs: Long = START_NACHLAUF_MS
        private set

    var verschluckt: Int = 0
        private set

    /** Is the shield open for the bot's gestures right now? */
    val durchlaessig: Boolean get() = laufend.isNotEmpty()

    /** A gesture starts. Result: its number and whether the shield must be made passable NOW. */
    fun auf(): Pair<Long, Boolean> {
        val id = naechste++
        laufend += id
        return id to (laufend.size == 1)
    }

    /** Gesture done (or emergency time over). true = the shield must lock again NOW. Calling it twice does no harm. */
    fun zu(id: Long): Boolean = laufend.remove(id) && laufend.isEmpty()

    /** A touch arrived at the shield while a gesture should go through. true = too often – stop the bot. */
    fun verschluckt(): Boolean {
        verschluckt++
        vorlaufMs = (vorlaufMs * 2).coerceAtMost(MAX_VORLAUF_MS)
        nachlaufMs = (nachlaufMs * 2).coerceAtMost(MAX_NACHLAUF_MS)
        return verschluckt >= MAX_VERSCHLUCKT
    }

    /** Shield gone (pocket mode off): nothing open any more. */
    fun vergessen() {
        laufend.clear()
    }

    companion object {
        const val START_VORLAUF_MS = 60L
        const val MAX_VORLAUF_MS = 400L

        /** Keep it passable this long after the gesture (Nov 11: "~50 ms"; Nov 12: 50 → 250 ms, grows up to 1.2 s). */
        const val START_NACHLAUF_MS = 250L
        const val MAX_NACHLAUF_MS = 1_200L

        /** Emergency: if no report about the gesture comes, lock again at the latest this long after it ended. */
        const val NOTFALL_MS = 2_000L
        const val MAX_VERSCHLUCKT = 5
    }
}
