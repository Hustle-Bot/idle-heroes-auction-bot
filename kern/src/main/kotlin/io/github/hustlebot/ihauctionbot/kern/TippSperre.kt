package io.github.hustlebot.ihauctionbot.kern

import io.github.hustlebot.ihauctionbot.kern.bild.Bild

/**
 * Nov 11 (field test 1.12/1.13): the bot tapped the green "+" next to the Starry Gems at the top – that opens
 * a PURCHASE window and must never happen. Cause: right after "Confirm" one screenshot still showed the Bid window
 * (it was just closing by itself); the bot wanted to close it with the red X at the top right – when the tap arrived,
 * the window was already gone and the "+" button was underneath.
 *
 * That is why this lock checks EVERY action before the app carries it out:
 *  - Nothing is ever tapped or swiped in the top bar (back arrow, currencies with "+", lock, "?", Retirement).
 *  - The only exception: the red X of a window (Bid, confirm, secondary password, filter) that recognition found in
 *    EXACTLY this screenshot – and only if the same window was already visible on the screenshot before
 *    ("steady"). A window that is just opening or closing is never tapped at the top.
 */
object TippSperre {

    /** The game's top bar reaches down to here (reference 2712 × 1220, from the top). */
    const val OBEN_SPERRE_Y = 150

    /**
     * Nov 12 (emulator run 16: after a swipe from y 1150 the home screen appeared): with gesture navigation a
     * swipe that starts in the bottom edge (about 48 dp, ~130 pixels on the phone) means "go home". No swipe starts or
     * ends lower than this many pixels above the bottom edge (reference 1220) – and none higher than [WISCH_OBEN_MIN_Y].
     */
    const val WISCH_RAND_UNTEN = 200
    const val WISCH_OBEN_MIN_Y = 200

    /** Window with a red X that may be tapped at the top (when steady). */
    val FENSTER_MIT_X: Set<Ort> = setOf(Ort.BIETEN, Ort.BESTAETIGEN, Ort.ZWEITPASSWORT, Ort.FILTER)

    /**
     * null = allowed, otherwise the reason for the lock.
     * [ortRuhig]: the screenshot before showed the same place (and has done so for a little while).
     */
    fun pruefe(aktion: Aktion, bild: Bildschirm, ortRuhig: Boolean): String? = when (aktion) {
        is Aktion.Tippen -> punkt(aktion.punkt, bild, ortRuhig, istX = bild.knopf(Knopf.SCHLIESSEN) == aktion.punkt)
        is Aktion.Wischen -> wischRand(aktion.von, bild) ?: wischRand(aktion.nach, bild)
            ?: punkt(aktion.von, bild, false, false) ?: punkt(aktion.nach, bild, false, false)
        is Aktion.Eingeben -> punkt(aktion.feld, bild, false, false)
        is Aktion.PasswortEingeben -> punkt(aktion.feld, bild, false, false)
        is Aktion.Folge -> aktion.schritte.firstNotNullOfOrNull { pruefe(it, bild, ortRuhig) }
    }

    /** Nov 12: never swipe in the bottom gesture edge or in the top area (point in screenshot pixels). */
    private fun wischRand(p: Punkt, bild: Bildschirm): String? {
        val y = p.y * Bild.REF_HOEHE / bild.bildHoehe
        if (y > Bild.REF_HOEHE - WISCH_RAND_UNTEN) return "swipe too low (gesture edge: would leave the game)"
        // Nov 12 (review before 1.33): converting between reference and screenshot and back rounds down – on phones
        // whose screenshot is not 1220 high, reference 200 would otherwise become 199, and every long swipe would be locked.
        if (y < WISCH_OBEN_MIN_Y - RUNDUNG_PX) return "swipe too high"
        return null
    }

    /** Leeway for rounding down when converting (pixels, reference). */
    private const val RUNDUNG_PX = 2

    /** Is the point in the top bar? (Point in screenshot pixels.) */
    fun imObenBalken(p: Punkt, bild: Bildschirm): Boolean = p.y * Bild.REF_HOEHE / bild.bildHoehe < OBEN_SPERRE_Y

    private fun punkt(p: Punkt, bild: Bildschirm, ortRuhig: Boolean, istX: Boolean): String? {
        if (p.x < 0 || p.y < 0 || p.x >= bild.bildBreite || p.y >= bild.bildHoehe) return "outside the screen"
        if (!imObenBalken(p, bild)) return null
        if (istX && bild.ort in FENSTER_MIT_X) {
            return if (ortRuhig) null else "top: red X, but the window is not steady yet (maybe it is just closing)"
        }
        return "top bar with the purchase buttons"
    }
}
