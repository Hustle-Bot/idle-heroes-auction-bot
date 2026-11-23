package io.github.hustlebot.ihauctionbot.kern

import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck

/**
 * Review before publishing (Nov 22): which app is in front, and may a screenshot taken right now be kept?
 *
 * The accessibility service only gets events from the allowed game apps (allow-list `packageNames` in
 * res/xml/bedienungshilfe.xml). Whether Idle Heroes is still in front is therefore checked by looking at the window list
 * Android gives the service (every second during a run, and right after every screenshot). This object only decides,
 * without Android – the app reports the windows.
 *
 * A screenshot is only kept (report, flight recorder, saved screenshots) if the game is in front and ALONE on the
 * screen: no other app, no notification shade or heads-up notification, no keyboard. The bot still looks at every
 * screenshot it takes (that is how it plays), but it never keeps one of another app.
 *
 * Privacy review, round 2 (Nov 23): the app no longer asks any window which app it belongs to. Which app windows are
 * the game's (or this app's) is decided by [FensterZuordnung] from the window list and the game's own events; every
 * other app window counts as another app ([FREMDE_APP]).
 *
 * Review round 3 (Nov 23): small overlays of other apps (chat heads, bubbles, caller-ID strips) count as system windows
 * and may lie over the game. A screenshot that is kept is blacked out outside the game and under every other window
 * ([schwaerzen]); a gesture whose point lies under another window is not sent ([punktFrei]). The window list is looked
 * at right before every gesture as well.
 */
object SpielVorne {

    /** Idle Heroes from Google Play – the app id seen in the owner's reports (fallback if Android gives no allow-list). */
    const val IDLE_HEROES = "com.droidhang.ad"

    /**
     * Review round 2 (Nov 23): [Fenster.paket] of an app window that is neither the game nor this app – another app,
     * whose name is never asked for. (No real app id can look like this: it has spaces and brackets.)
     */
    const val FREMDE_APP = "(another app)"

    /** Kind of window (from AccessibilityWindowInfo.type). */
    enum class Art {
        /** A normal app window – also floating windows, chat bubbles and toasts of apps. */
        APP,

        /** System UI: status bar, navigation bar, notification shade, heads-up notifications, system dialogs. */
        SYSTEM,

        /** The keyboard (it can show clipboard suggestions). */
        TASTATUR,

        /** Accessibility overlays: the IH button, the shield (hidden or transparent for the bot's screenshots). */
        UEBERLAGERUNG,

        /** Anything else (split-screen divider, magnification). */
        ANDERES,
    }

    /**
     * A window as Android reports it. [paket] only for app windows (null = unknown), [schicht] = z-order (higher = on
     * top), [breite]/[hoehe] = size on the screen in pixels (0 = unknown), [id] = Android's window id (-1 = unknown).
     * Review round 3 (Nov 23): [links]/[oben] = position of its top left corner on the screen in pixels (with
     * [breite]/[hoehe] the window's bounds, as AccessibilityWindowInfo.getBoundsInScreen gives them).
     */
    data class Fenster(
        val art: Art,
        val paket: String? = null,
        val aktiv: Boolean = false,
        val fokus: Boolean = false,
        val schicht: Int = 0,
        val breite: Int = 0,
        val hoehe: Int = 0,
        val id: Int = -1,
        val links: Int = 0,
        val oben: Int = 0,
    ) {
        /** The window's bounds on the screen (null = size not known). */
        val flaeche: Rechteck?
            get() = if (breite > 0 && hoehe > 0) Rechteck(links, oben, links + breite, oben + hoehe) else null

        /** Does the point ([x], [y]) lie inside the window's bounds? Unknown size = no. */
        fun enthaelt(x: Int, y: Int): Boolean =
            breite > 0 && hoehe > 0 && x >= links && x < links + breite && y >= oben && y < oben + hoehe
    }

    /** What is in front. */
    enum class Lage {
        /** An allowed game app. */
        SPIEL,

        /** This app (settings). */
        EIGENE_APP,

        /**
         * An app window of the system UI ("android", "com.android.systemui") – e.g. a system prompt. Since round 2 of the
         * review the app does not ask windows for their app any more, so such a window counts as another app ([FREMD]).
         */
        SYSTEM,

        /** Another app. */
        FREMD,

        /**
         * Not known (no app window, or the app window in front is not known yet). Decide nothing on this – no
         * screenshot is kept and no gesture goes out (fail closed).
         */
        UNKLAR,
    }

    /** The app window in front: the active one, otherwise the focused one, otherwise the topmost (null = none). */
    fun vorneApp(fenster: List<Fenster>): Fenster? {
        val apps = fenster.filter { it.art == Art.APP }
        return apps.firstOrNull { it.aktiv } ?: apps.firstOrNull { it.fokus } ?: apps.maxByOrNull { it.schicht }
    }

    /** Which app is in front? [spiele] = allowed game apps, [eigenesPaket] = this app. */
    fun lage(fenster: List<Fenster>, spiele: Set<String>, eigenesPaket: String): Lage {
        val p = vorneApp(fenster)?.paket?.trim()
        return when {
            p.isNullOrEmpty() -> Lage.UNKLAR
            p in spiele -> Lage.SPIEL
            p == eigenesPaket -> Lage.EIGENE_APP
            p in KnopfSichtbarkeit.SYSTEM_PAKETE -> Lage.SYSTEM
            else -> Lage.FREMD
        }
    }

    /**
     * May a screenshot taken right now be kept? Only if the app in front is an allowed game and nothing else is on the
     * screen:
     *  - no other app window (split screen, picture-in-picture, floating windows and bubbles, toasts, this app),
     *  - no keyboard,
     *  - no system window bigger than a thin bar: the notification shade, quick settings, a heads-up notification or a
     *    system dialog cover the game; the status and navigation bar ([minDeckendPx] or thinner) do not.
     * Accessibility overlays (IH button, shield) do not count. No windows at all (Android said nothing) → no.
     */
    fun bildFrei(fenster: List<Fenster>, spiele: Set<String>, minDeckendPx: Int): Boolean {
        val spiel = vorneApp(fenster)?.paket?.trim()
        if (spiel.isNullOrEmpty() || spiel !in spiele) return false
        return fenster.none { w ->
            when (w.art) {
                Art.APP -> w.paket?.trim() != spiel
                Art.TASTATUR -> true
                Art.SYSTEM -> deckt(w, minDeckendPx)
                Art.UEBERLAGERUNG, Art.ANDERES -> false
            }
        }
    }

    /** Is a system window more than a thin bar (both sides longer than [minDeckendPx])? Unknown size = nothing visible. */
    fun deckt(w: Fenster, minDeckendPx: Int): Boolean =
        w.breite > 0 && w.hoehe > 0 && minOf(w.breite, w.hoehe) > minDeckendPx

    /** The app windows of the game in front (the app of [vorneApp], if it is an allowed game) – empty otherwise. */
    private fun spielFenster(fenster: List<Fenster>, spiele: Set<String>): List<Fenster> {
        val spiel = vorneApp(fenster)?.paket?.trim()
        if (spiel.isNullOrEmpty() || spiel !in spiele) return emptyList()
        return fenster.filter { it.art == Art.APP && it.paket?.trim() == spiel }
    }

    /**
     * Review round 3 (Nov 23): what has to be blacked out in a screenshot before it is SAVED (report, flight recorder,
     * saved screenshots). [bildBreite] x [bildHoehe] = the screenshot, in the same pixels as the window list.
     *
     * [bildFrei] already refuses a screenshot with another app, the keyboard or a big system window. Small system
     * windows pass it, though: other apps' overlays like chat heads, bubbles or a caller-ID strip, and the status and
     * navigation bars (their icons can show which apps sent notifications). So:
     *  - everything outside the game's windows (their common bounds) is black,
     *  - every other window that overlaps the game is black where it overlaps (system windows, unknown app windows,
     *    the keyboard, anything else).
     * Accessibility overlays (this app's IH button and shield, hidden or transparent for the bot's screenshots) are left
     * as they are – the app cannot tell them from another accessibility service's overlay without reading them.
     *
     * Result: the rectangles to black out (empty = nothing), or null = it cannot be said – the game is not in front,
     * its windows have no size, or they do not fit into the screenshot (e.g. the screenshot came in another
     * orientation than the window list). Then the screenshot is not saved at all (fail closed).
     */
    fun schwaerzen(fenster: List<Fenster>, spiele: Set<String>, bildBreite: Int, bildHoehe: Int): List<Rechteck>? {
        if (bildBreite <= 0 || bildHoehe <= 0) return null
        val spielFenster = spielFenster(fenster, spiele)
        val flaechen = spielFenster.mapNotNull { it.flaeche }
        if (flaechen.isEmpty()) return null
        val links = flaechen.minOf { it.x0 }
        val oben = flaechen.minOf { it.y0 }
        val rechts = flaechen.maxOf { it.x1 }
        val unten = flaechen.maxOf { it.y1 }
        // The game's windows must lie inside the screenshot – otherwise the two do not belong together.
        if (links < 0 || oben < 0 || rechts > bildBreite || unten > bildHoehe) return null
        val spiel = Rechteck(links, oben, rechts, unten)
        val schwarz = ArrayList<Rechteck>()
        // Outside the game: up to four strips around it.
        if (spiel.y0 > 0) schwarz += Rechteck(0, 0, bildBreite, spiel.y0)
        if (spiel.y1 < bildHoehe) schwarz += Rechteck(0, spiel.y1, bildBreite, bildHoehe)
        if (spiel.x0 > 0) schwarz += Rechteck(0, spiel.y0, spiel.x0, spiel.y1)
        if (spiel.x1 < bildBreite) schwarz += Rechteck(spiel.x1, spiel.y0, bildBreite, spiel.y1)
        // Every other window over the game.
        for (w in fenster) {
            if (w.art == Art.UEBERLAGERUNG || spielFenster.any { it === w }) continue
            val f = w.flaeche ?: continue
            val x0 = maxOf(f.x0, spiel.x0)
            val y0 = maxOf(f.y0, spiel.y0)
            val x1 = minOf(f.x1, spiel.x1)
            val y1 = minOf(f.y1, spiel.y1)
            if (x0 < x1 && y0 < y1) schwarz += Rechteck(x0, y0, x1, y1)
        }
        return schwarz
    }

    /**
     * Review round 3 (Nov 23): may a gesture start at ([x], [y]) (screen pixels)? Android gives the touch to the topmost
     * window at that point – it must be a window of the game in front. Not if any other window lies on top of the game
     * there: a small system window (another app's chat head, bubble or caller-ID strip, a heads-up notification, a status
     * or navigation bar), an app window that is not the game's (or not known yet), the keyboard, anything else.
     * Accessibility overlays (this app's IH button and shield) do not count – the app lets the gesture through them
     * itself. No window of the game at that point (or the game not in front) → no (fail closed).
     */
    fun punktFrei(fenster: List<Fenster>, spiele: Set<String>, x: Int, y: Int): Boolean {
        val spielFenster = spielFenster(fenster, spiele)
        val spielOben = spielFenster.filter { it.enthaelt(x, y) }.maxOfOrNull { it.schicht } ?: return false
        return fenster.none { w ->
            w.art != Art.UEBERLAGERUNG && spielFenster.none { it === w } && w.enthaelt(x, y) && w.schicht >= spielOben
        }
    }

    /**
     * Stop when leaving the game: another app is in front since [fremdSeitMs], the game was last seen in front at
     * [spielZuletztMs]. The bot stops [frist] after the game was last seen – but never earlier than [mindestens] after the
     * other app was first noticed (a brief switch does not stop it). Result: when to stop (same clock as the inputs).
     */
    fun stoppUm(fremdSeitMs: Long, spielZuletztMs: Long, frist: Long, mindestens: Long): Long =
        maxOf(spielZuletztMs + frist, fremdSeitMs + mindestens)
}

/**
 * Privacy review, round 2 (Nov 23): which app an app window belongs to – WITHOUT asking any window what it is or shows.
 * Before, the app read the package of every app window from its root node, so it touched other apps' windows too.
 *
 *  - The game's windows are learned from the game's OWN accessibility events ([lernen]): Android sends the service
 *    events of the allowed game apps only (packageNames in res/xml/bedienungshilfe.xml), and every window of the game
 *    sends one when it is shown, shown again or gets the focus back. Android never gives a window id twice while the
 *    phone runs, so an id learned once stays right.
 *  - This app: while its own screen is open (the app says so), the app window in front is this app – remembered, so
 *    that the moment of leaving the screen does not look like "another app".
 *  - Every other app window is another app ([SpielVorne.FREMDE_APP]); nothing about it is read. Only a window that
 *    appeared just now ([NEU_MS]) stays "not known" (null) for a moment – the game's event for it may still be on its way.
 *
 * Review round 3 (Nov 23): what was learned lives only in memory. The window ids are no longer saved (fenster.xml) to be
 * taken back after a crash: whether the phone had restarted in between could only be guessed. After a restart of the
 * app the game counts as another app until it sends an event again (fail closed).
 *
 * Only the window list's own data is used (kind, id, layer, size, position, active/focused). Thread-safe.
 */
class FensterZuordnung(private val maxFenster: Int = 64) {

    companion object {
        /** An app window seen for less than this (ms) is not judged yet – the game's event for it may still come. */
        const val NEU_MS = 300L
    }

    /** Window id → game app (oldest first). */
    private val spiel = LinkedHashMap<Int, String>()

    /** Window ids of this app (seen in front while its own screen was open). */
    private val eigene = LinkedHashSet<Int>()

    /** App windows not known yet → when they were first seen. */
    private val erstGesehen = HashMap<Int, Long>()

    /**
     * An accessibility event of [paket] came from window [id]: if [paket] is an allowed game app ([spiele]), the window
     * is the game's. Returns true if it was not known before.
     */
    @Synchronized
    fun lernen(id: Int, paket: String?, spiele: Set<String>): Boolean {
        val p = paket?.trim()
        if (id < 0 || p.isNullOrEmpty() || p !in spiele) return false
        if (spiel[id] == p) return false
        spiel.remove(id)
        spiel[id] = p
        while (spiel.size > maxFenster) spiel.remove(spiel.keys.first())
        eigene.remove(id)
        erstGesehen.remove(id)
        return true
    }

    /** Is window [id] one of the game's (learned from its own events)? */
    @Synchronized
    fun istSpiel(id: Int): Boolean = id >= 0 && id in spiel

    /** Was any window of the game learned yet? */
    @Synchronized
    fun spielBekannt(): Boolean = spiel.isNotEmpty()

    /** The game's windows known right now (id → app, oldest first). Only in memory, never saved (review round 3). */
    @Synchronized
    fun spielFenster(): Map<Int, String> = LinkedHashMap(spiel)

    /**
     * The windows with [SpielVorne.Fenster.paket] filled in for app windows: the game's app, [eigenesPaket], null (not
     * known yet, see [NEU_MS]) or [SpielVorne.FREMDE_APP]. [eigeneAppOffen] = this app's screen is open right now,
     * [jetztMs] = a steady clock (e.g. SystemClock.uptimeMillis). Other windows (system, keyboard, overlays) unchanged.
     */
    @Synchronized
    fun einordnen(
        fenster: List<SpielVorne.Fenster>,
        eigeneAppOffen: Boolean,
        eigenesPaket: String,
        jetztMs: Long,
    ): List<SpielVorne.Fenster> {
        val vorne = SpielVorne.vorneApp(fenster)
        val gesehen = HashSet<Int>()
        val ergebnis = fenster.map { w ->
            if (w.art != SpielVorne.Art.APP) return@map w
            gesehen += w.id
            val bekannt = spiel[w.id] ?: if (w.id >= 0 && w.id in eigene) eigenesPaket else null
            val paket = when {
                bekannt != null -> bekannt
                eigeneAppOffen && w === vorne && w.id >= 0 -> {
                    eigene += w.id
                    while (eigene.size > maxFenster) eigene.remove(eigene.first())
                    eigenesPaket
                }
                jetztMs - erstGesehen.getOrPut(w.id) { jetztMs } < NEU_MS -> null
                else -> SpielVorne.FREMDE_APP
            }
            w.copy(paket = paket)
        }
        // Windows that are gone count as new again if they come back (their event comes again then).
        erstGesehen.keys.retainAll(gesehen)
        return ergebnis
    }
}
