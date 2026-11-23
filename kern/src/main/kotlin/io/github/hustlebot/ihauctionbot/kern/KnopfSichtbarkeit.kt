package io.github.hustlebot.ihauctionbot.kern

/**
 * When the floating IH button is visible.
 *
 * Nov 9: the IH button floated over EVERY app and could only be removed
 * by turning the accessibility service off. Now:
 *  - It only shows while Idle Heroes is in front. The bot learns by itself which app that is: on the first run
 *    in which it recognizes the auction house, it remembers the app that is in front at that moment ([lernen]).
 *    Until that is learned, the button is visible everywhere (as before).
 *  - It is also visible over our own app (settings) – so the user can see that it is back.
 *  - "Hide IH" hides it until Idle Heroes comes to the front again or the app is opened.
 *  - While the bot is running (also when paused) or a hook of the app holds the button, it is ALWAYS visible – the
 *    user must be able to stop at any time.
 *  - Setting "Show IH button only in Idle Heroes" (on by default). Off = everywhere, as before.
 *  - Review before publishing (Nov 22): with an allow-list of game apps ([erlaubteSpiele], from the accessibility
 *    config) only those apps count as Idle Heroes – learning is then just a check against the list, and the button
 *    shows over the allowed games even before anything was learned.
 *
 * A pure decision without Android – the app reports what is in front and asks [sichtbar].
 */
class KnopfSichtbarkeit(
    /** Package name of our own app (e.g. io.github.hustlebot.ihauctionbot) – never learned as the game. */
    private val eigenesPaket: String,
    /** Game learned earlier (from storage). */
    spielPaket: String? = null,
    /** Setting "Show IH button only in Idle Heroes". */
    var nurImSpiel: Boolean = true,
    /** Allowed game apps (allow-list). Empty = any app may be learned (as before). */
    val erlaubteSpiele: Set<String> = emptySet(),
) {

    /** The learned Idle Heroes app (null = not learned yet). */
    var spielPaket: String? = spielPaket?.takeIf { gueltig(it) }
        private set

    /** What was in front last (null = not known yet). */
    var vordergrund: String? = null
        private set

    /** Did the user tap "Hide IH" (and Idle Heroes has not come to the front since)? */
    var ausgeblendet: Boolean = false
        private set

    /**
     * An app came to the front. System UI (notifications, Android prompts) and empty values do not
     * count – then everything stays as it is. If Idle Heroes comes to the front (again), "hidden" is over.
     * Returns true if this changed something.
     */
    fun vordergrundGesehen(paket: String?): Boolean {
        val p = paket?.trim()
        if (p.isNullOrEmpty() || p in SYSTEM_PAKETE) return false
        val vorher = vordergrund
        if (p == vorher) return false
        vordergrund = p
        if (istSpiel(p)) ausgeblendet = false
        return true
    }

    /** "Hide IH" tapped. Returns false if that is not possible right now (bot running, or a hook holds the button). */
    fun ausblenden(aktiv: Boolean): Boolean {
        if (aktiv) return false
        ausgeblendet = true
        return true
    }

    /** The app was opened (settings): show the button again. */
    fun appGeoeffnet() {
        ausgeblendet = false
    }

    /**
     * The bot recognized the auction house in the screenshot, and [paket] is in front → that is Idle Heroes.
     * Our own app, system UI and empty values are never learned. Returns true if newly learned (to save it).
     */
    fun lernen(paket: String?): Boolean {
        val p = paket?.trim()
        if (p.isNullOrEmpty() || !gueltig(p)) return false
        vordergrundGesehen(p)
        if (p == spielPaket) return false
        spielPaket = p
        ausgeblendet = false
        return true
    }

    /** Forget the learned game (settings): until the next run, the button is visible everywhere again. */
    fun vergessen() {
        spielPaket = null
    }

    /**
     * Should the button be visible now?
     * [aktiv]: the bot is running or paused, or a hook holds the button → always yes.
     */
    fun sichtbar(aktiv: Boolean): Boolean {
        if (aktiv) return true
        if (ausgeblendet) return false
        if (!nurImSpiel) return true
        val vorne = vordergrund ?: return true // not known yet what is in front: better show it
        if (istSpiel(vorne) || vorne == eigenesPaket) return true
        // Not learned yet and no allow-list: everywhere, as before.
        return spielPaket == null && erlaubteSpiele.isEmpty()
    }

    /** Is [p] Idle Heroes – the learned app or one of the allowed game apps? */
    fun istSpiel(p: String?): Boolean = p != null && (p == spielPaket || p in erlaubteSpiele)

    private fun gueltig(p: String): Boolean =
        p.isNotBlank() && p != eigenesPaket && p !in SYSTEM_PAKETE && (erlaubteSpiele.isEmpty() || p in erlaubteSpiele)

    companion object {
        /** Android system UI: notifications, quick settings, system dialogs etc. */
        val SYSTEM_PAKETE = setOf("com.android.systemui", "android")

        /**
         * Which app is in front? From the windows Android reports to the accessibility service: only app windows count
         * (not the keyboard, system bar or the IH button itself); of those the active one, otherwise the focused one,
         * otherwise the topmost one. null = none with a known package.
         */
        fun vordergrundAus(fenster: List<Fenster>): String? {
            val apps = fenster.filter { it.istApp && !it.paket.isNullOrBlank() }
            return (apps.firstOrNull { it.aktiv } ?: apps.firstOrNull { it.fokus } ?: apps.maxByOrNull { it.schicht })?.paket
        }
    }

    /** A window as Android reports it to the accessibility service (only what is needed here). */
    data class Fenster(
        val paket: String?,
        /** A normal app window (not keyboard, system bar, accessibility button). */
        val istApp: Boolean,
        val aktiv: Boolean = false,
        val fokus: Boolean = false,
        val schicht: Int = 0,
    )
}
