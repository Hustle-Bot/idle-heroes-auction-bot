package io.github.hustlebot.ihauctionbot.kern

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** An action on the screen that the app carries out. */
sealed interface Aktion {
    val grund: String

    /**
     * [dauerMs]: how long the finger stays down. Nov 9 (field test 1.9: "Awakened Tier ↑" needed 4 taps):
     * a bit longer in the sort window, so the game reliably takes the tap.
     */
    data class Tippen(val punkt: Punkt, override val grund: String, val dauerMs: Long = KURZER_TIPP_MS) : Aktion

    /**
     * [haltenMs]: Nov 11 (scrolling: "always exactly 5 new copies"): hold still this long at the end before the finger
     * is lifted – then the list does not keep sliding (no momentum) and is moved by exactly the swipe distance.
     */
    data class Wischen(
        val von: Punkt,
        val nach: Punkt,
        val dauerMs: Long,
        override val grund: String,
        val haltenMs: Long = 0,
    ) : Aktion

    /**
     * Tap a field and type text into it (Nov 9: price fields in the filter).
     * Afterwards the app reports with [AuktionsBot.eingabeErgebnis] whether it worked.
     */
    data class Eingeben(val feld: Punkt, val text: String, override val grund: String) : Aktion

    /**
     * Enter the saved secondary password. The core does NOT know the password –
     * the app fetches it from its private storage itself and never writes it to the log.
     */
    data class PasswortEingeben(val feld: Punkt, override val grund: String) : Aktion

    /**
     * Nov 9 (speed): several actions quickly one after another, WITHOUT a screenshot in between –
     * e.g. in the filter window rank on/off and both price fields. Only taps and typing.
     * The app pauses [abstandMs] between two actions and then reports with
     * [AuktionsBot.eingabeErgebnis] whether ALL inputs worked. It is checked on the next screenshot.
     */
    data class Folge(val schritte: List<Aktion>, val abstandMs: Long, override val grund: String) : Aktion {
        init {
            require(schritte.isNotEmpty()) { "Sequence without actions" }
            require(schritte.all { it is Tippen || it is Eingeben }) { "Sequence: only taps and typing" }
        }

        val hatEingaben: Boolean get() = schritte.any { it is Eingeben }
    }
}

/** Result of a step. */
sealed interface Ergebnis {
    /** Carry out an action, then continue after [naechsterSchrittMs]. */
    data class Handeln(val aktion: Aktion, val naechsterSchrittMs: Long) : Ergebnis

    /** Do nothing, just wait (e.g. limit full, message on screen). */
    data class Warten(val grund: String, val ms: Long) : Ergebnis

    /** Screenshot not understood – the app saves the screenshot for improvements. */
    data class Unsicher(val grund: String, val ms: Long) : Ergebnis

    /** Done. With [StoppGrund.sperren] the app turns the screen off. */
    data class Fertig(val grund: StoppGrund) : Ergebnis

    /** Paused – do nothing. */
    data object Pausiert : Ergebnis
}


/**
 * Waiting times in milliseconds. Nov 9: speed matters (often 5 copies end within 9 seconds).
 * Nov 9 (field test 1.7: "a bit slow, especially filter and Awakened Tier"): shorter.
 * After every action the next step still checks on the new screenshot whether it worked.
 */
data class Takt(
    val nachTippenMs: Long = 450,
    /** Row tapped → Bid window opens. */
    val nachZeileMs: Long = 450,
    /** "Bid" tapped → list, password or confirm window. */
    val nachBietenMs: Long = 400,
    val nachBestaetigenMs: Long = 700,
    /**
     * Nov 11: after the swipe (finger lifted) this long until the first check screenshot (then the bot waits until the list
     * stands still). Nov 11 (speed): 200 → 100 ms – two screenshots check anyway whether it stands still.
     */
    val nachWischenMs: Long = 100,
    val nachAktualisierenMs: Long = 1200,
    /** Filter closed with Confirm → list reloads. (Nov 9, field test 1.9: 1000 → 850 ms) */
    val nachFilterOkMs: Long = 850,
    /** Sort button tapped → sort window opens. */
    val sortOeffnenMs: Long = 350,
    /**
     * Nov 9 (field test 1.9: "Awakened Tier ↑" needed 4 taps): wait this long after the first screenshot with the
     * sort window until it is fully open – only then tap.
     */
    val sortAufklappenMs: Long = 300,
    /** "Awakened Tier ↑" chosen → list is sorted again. */
    val nachSortierenMs: Long = 900,
    val nachEingabeMs: Long = 300,
    /** Pause between two actions of an [Aktion.Folge]. */
    val folgeAbstandMs: Long = 150,
    /** Nov 11 (scrolling): drag 5 rows = 905 pixels slowly (about 1 pixel per millisecond, no momentum). */
    val wischDauerMs: Long = 900,
    /** Nov 11: short extra push so the first row is exactly at the top. */
    val korrekturDauerMs: Long = 400,
    val meldungMs: Long = 1000,
    val limitVollMs: Long = 5000,
    val unbekanntMs: Long = 2000,
    val pauseMs: Long = 1000,
    /** This many "unknown" in a row, then the bot stops (150 × 2 s ≈ 5 minutes). */
    val maxUnbekanntInFolge: Int = 150,
    /**
     * Nov 9 (field test 1.10/1.11): after "-7" wait until the earliest end (from My Biddings) plus this much,
     * then try again.
     */
    val limitNachlaufMs: Long = 5000,
    /**
     * Wait at most this long in one go after "-7", then try again (if "-7" comes again: check again).
     * Nov 11: if cleaning up freed nothing, about 2 minutes until the next round (before: 5 minutes).
     */
    val limitWarteMaxMs: Long = 120_000,
    /** If My Biddings could not provide a remaining time: wait this long. */
    val limitOhneZeitMs: Long = 60_000,
    /**
     * Nov 9 (field test 1.10/1.11): message over the list → check this often whether it is gone.
     * Nov 11 (speed, report 19:39: screenshots every ~0.49 s): 400 → 300 ms, then a screenshot comes every ~0.38 s (Android
     * does not deliver screenshots faster) – the first clear screenshot is seen ~0.05 s earlier on average.
     */
    val meldungWartenMs: Long = 300,
    /** If a message lies over the list this long, the screenshot counts as uncertain (save screenshot); still no tapping. */
    val meldungHaengtMs: Long = 15_000,
    /** A window may take this long before the bot gives up on it. */
    val fensterWartenMs: Long = 3000,
    /**
     * Nov 9: after "Bid" there is usually NO confirm window any more – the game goes straight to the list
     * (or first to the secondary password). If the Bid window is still there this long afterwards, the bot closes it.
     */
    val bietenWartenMs: Long = 2000,
    /** Look for the clock on the row this long after a bid (confirms that it arrived). */
    val gebotPruefenMs: Long = 15_000,
    /** If the row still shows NO icon this long after the bid, the bid counts as not arrived. */
    val gebotOhneZeichenMs: Long = 2000,
    /**
     * Nov 9 (speed): this long after a tap an unknown screenshot is usually just a window that is
     * opening – then look again after [uebergangPollMs] instead of after [unbekanntMs].
     */
    val uebergangMs: Long = 3000,
    val uebergangPollMs: Long = 300,
    /**
     * Wait this long after "Verify" before a returning password window counts as "wrong".
     * Nov 11 (emulator test: screenshots lagged up to 2 s behind – the password wrongly counted as wrong): 2000 → 4000 ms.
     */
    val passwortPruefenMs: Long = 4000,
    /** Too little SG: wait this long, then look again. */
    val sgFehltMs: Long = 30_000,
    /**
     * Nov 9 (field test 1.8: opened the same row three times): after a cancelled or failed
     * attempt, do not open the row again for this long.
     */
    val zeileSperrenMs: Long = 60_000,
    /**
     * Nov 9 (field test 1.8: message after the secondary password read as "50 bids"): this long after "Verify"
     * a message in the Bid window is NEVER the limit – just wait.
     */
    val meldungNachVerifyMs: Long = 3000,
    /** If the short message comes again within this time after a new attempt, it counts as the limit. */
    val limitVerdachtMs: Long = 120_000,
    /**
     * Nov 11 ("+" tap at the top): the same place must have been visible this long for a window to count as "steady" –
     * only then may its red X at the top be tapped (see [TippSperre]).
     */
    val ruhigMs: Long = 250,
    /** An action was stopped by the [TippSperre]: wait this long, then look again. */
    val gesperrtMs: Long = 300,
    /**
     * Nov 11: after bidding a loading screen sometimes comes. This long after the last action an
     * unknown screenshot counts as "game loading" – the bot just waits (taps nothing, does not count it as unknown).
     */
    val ladenMs: Long = 30_000,
    val ladenPollMs: Long = 500,
    /** After the swipe: check this often whether the list stands still, and wait at most this long for it. */
    val ruhigPollMs: Long = 200,
    val ruhigWartenMaxMs: Long = 3000,
    /** Nov 11 (scrolling): hold still this long at the end of the swipe (no sliding). */
    val wischHaltenMs: Long = 250,
    /** Nov 11 (loading screen with golden star): just wait this long, after that the screenshot counts as uncertain. */
    val ladenUnsicherMs: Long = 60_000,
    /** Nov 11 (cleaning up My Biddings): red X pressed → wait this long until the row is gone. */
    val nachKreuzMs: Long = 450,
    /**
     * If the row is still there this long after the X, it is pressed again. Nov 12 (emulator: screenshots up to 4 s old –
     * the second X hit a different row): 2000 → 6000 ms. If the row goes away (normal case), this costs nothing.
     */
    val kreuzWartenMs: Long = 6000,
    /** If My Biddings shows no row for this long (and not "no bids"), the list counts as empty. */
    val leerWartenMs: Long = 1500,
    /** Clean up in My Biddings for at most this long in one go, then back to the Bidding Market. */
    val aufraeumenMaxMs: Long = 300_000,
    /** Nov 11: after a cleanup because of our own count, the next one at the earliest this much later (no loop). */
    val durchgangAbstandMs: Long = 120_000,
    /**
     * Nov 11 (long run: second X → "+"): this long after our own tap on the red X no further X is tapped at the
     * top – the window is closing right now.
     */
    val xNachFensterTippMs: Long = 4000,
    /**
     * Nov 11 (emulator test: after "Confirm" the screenshot still showed the filter window for 1–2 s): this long after
     * "Confirm" in the filter, a filter window on the screenshot counts as "closing" – type nothing, tap nothing.
     */
    val filterZuWartenMs: Long = 3000,
    /**
     * Nov 12 (emulator run 13: screenshots up to 2.5 s in transit, screenshot still old up to 2 s after the tap, sometimes even an
     * older screenshot AFTER a newer one): this long after an action, every screenshot that still looks exactly like the
     * screenshot on which the action was decided counts as "old" – the bot just waits, taps nothing. A different
     * screenshot in between does NOT end this (an old one can come again afterwards). After this time without change the tap
     * counts as lost, and things go on normally.
     */
    val altesBildMs: Long = 6000,
    /** Like [altesBildMs], after "Bid", "Confirm" and "Verify" (the game waits for the server there). */
    val altesBildFensterMs: Long = 8000,
    /**
     * Nov 11 (review: slow mobile network): after "Bid", "Confirm" or "Verify" the window closes
     * by itself as soon as the server answers. At home the list screenshot came after 0.5–0.8 s (report Nov 11); on a
     * mobile network the answer can take a few seconds. For this long afterwards the X at the top is NEVER tapped (unless
     * the game reports "-7" in the window – then it surely stays open). 10 s is more than ten times as long as usual and
     * only costs time if the window really does not close (rare).
     */
    val fensterZuWartenMs: Long = 10_000,
    /**
     * Nov 11 (review): if the window is still there this long after "Bid"/"Confirm"/"Verify" (despite the X from 10 s on),
     * something is wrong – stop (screen off, report) instead of tapping around at the top.
     */
    val fensterKlemmtMs: Long = 20_000,
    /** Nov 11 (review): if the Bid window is visible this long in one go, the bot stops (never tap X forever). */
    val bidFensterMaxMs: Long = 30_000,
    /**
     * Nov 11 (review: bid 26 blindly with a maximum of 21): NEVER bid without a readable "Current Bid". Wait this long
     * (from the tap on the row) for a screenshot on which it is readable – usually 2–3 screenshots –, then close.
     */
    val currentBidWartenMs: Long = 2000,
    /** Nov 11 (review: "never wait silently forever"): a message may stay this long, then stop. */
    val meldungStoppMs: Long = 180_000,
    /** Nov 11 (review): secondary password missing – wait this long (maybe the user enters it), then stop. */
    val passwortFehltStoppMs: Long = 300_000,
    /** Nov 11 (review): SG balance never readable – this long, then stop. */
    val sgUnlesbarStoppMs: Long = 300_000,
    /** Nov 11 (review): not a single remaining time readable in the list – this long, then stop. */
    val restzeitUnlesbarStoppMs: Long = 600_000,
    /**
     * Nov 11 (review): in My Biddings, above the row there is one whose remaining time is 0 s or unreadable (it
     * may disappear in a moment) – wait at most this long, then go on anyway.
     */
    val endetGleichWartenMaxMs: Long = 6000,
    /**
     * Nov 11 (long run): after the red X in My Biddings a different screen shows – wait this long (tap
     * nothing) before it counts as a foreign window and the bot stops (single dark screenshots do happen).
     */
    val fremdNachKreuzMs: Long = 2000,
    /**
     * Nov 23 (emulator runs 21 and 24): after the red X in My Biddings the Bid window of another row opened (the list
     * moved just before the tap). Wait at most this long for a steady window without a message, then stop as before.
     */
    val bidNachKreuzWartenMs: Long = 10_000,
    /** Nov 11 (field test 1.20): after the swipe, the check waits at most this long for a message to go away. */
    val meldungPruefenMaxMs: Long = 3000,
    /**
     * Nov 12 (review): if the bidding list shows not a single row after the swipe (it is loading more), wait at most this
     * long – then refresh and start over at the top (never go on unchecked).
     */
    val leerNachWischenMaxMs: Long = 5000,
    /**
     * Nov 11 (long run): too little SG for any bid and none of our own bids is in front any more (nothing can bring SG
     * back) – wait this long, then stop (instead of waiting for hours with the screen on).
     */
    val sgAlleStoppMs: Long = 600_000,
    /**
     * Nov 12 (field test 1.31): if the money (SG budget or SG) is not enough for any copy in the list, the bot does not
     * page further, but looks at the top of the list this often (refresh) to see whether outbid bids brought SG back.
     */
    val geldNachsehenMs: Long = 25_000,
    /** Nov 12: the bot waits at most this long for money for a copy to become free again – then stop. */
    val geldWartenMaxMs: Long = 70 * 60_000L,
    /**
     * Nov 12 (review): while waiting for money, none of our own bids may be in front for this long before the bot stops
     * (a single misread screenshot – hammer instead of clock – ends nothing).
     */
    val geldVorneLeerMs: Long = 60_000,
    /**
     * Nov 12 (field test 1.33): the bot remembers the prices of the copies it has seen this long. If the free
     * money is not enough for any of them, it waits right away (not only at the end of a long round).
     */
    val preiseMerkenMs: Long = 5 * 60_000L,
    /**
     * Nov 22 (overnight, owner's decision): after "Lost connection with game server." wait this long, then tap the
     * yellow Confirm button of the dialog (reconnect).
     */
    val verbindungWartenMs: Long = 60_000,
    /** Nov 22: if the Confirm button of that dialog is not recognized within this time after the wait, stop. */
    val verbindungConfirmMaxMs: Long = 30_000,
    /** Nov 22: after Confirm, wait at most this long for the auction house to come back (loading star, unknown screens). */
    val wiederverbindenMaxMs: Long = 180_000,
    /**
     * Nov 22 (overnight: game restart): a loading screen at least this long is probably a restart of the game – the
     * filter and the sort order are set again afterwards (as at the start).
     */
    val neustartLadenMs: Long = 25_000,
    /** Nov 22 (buyout): after the window closed, decide within this time whether the buyout went through (SG dropped). */
    val buyoutPruefenMaxMs: Long = 15_000,
    /**
     * Nov 22 (buyout): an unknown screen this long after "Buyout Price" counts as an unknown confirmation window (longer
     * than a pop-up or a notification usually lasts).
     */
    val buyoutUnbekanntMs: Long = 30_000,
    /**
     * Nov 22 (rate limit, review before publishing): after the game's "Operated too frequently. Please retry in N second(s)."
     * the bot taps nothing for this long (counted from the first screenshot with the message, and never while the message is
     * visible). If the message comes again within [zuOftRuhigMs] after the pause, the pause doubles – at most [zuOftMaxMs].
     */
    val zuOftPauseMs: Long = 5000,
    val zuOftMaxMs: Long = 60_000,
    /** Nov 22: no "too frequently" for this long after a pause – the next pause starts at [zuOftPauseMs] again, the count at 0. */
    val zuOftRuhigMs: Long = 120_000,
    /**
     * Nov 22 (rate limit): two reloads of the list at least this far apart. Real reports (20-42-45, 21-46-15): "Awakened Tier ↑"
     * ~2 s after the filter's Confirm → "retry in 3 second(s)".
     * Nov 23 (review: "no lasting minimum gap"): ALWAYS – every reload of the list (refresh, filter Confirm, sort order) comes
     * at least this long after the one before, also while a copy that fits ends soon (before: only for 10 minutes after
     * "too frequently"). Bidding on visible rows, opening a Bid window and scrolling are not limited by it.
     */
    val abfrageAbstandMs: Long = 5000,
    /**
     * Nov 23: while the bot waits for [abfrageAbstandMs] although a copy that fits ends soon (or it just bid), it looks at the
     * visible rows this often (only screenshots, no reload) – a bid outbid on a visible row is bid again at once.
     */
    val abfrageBlickMs: Long = 300,
    /**
     * Nov 23 (coordinator: "a rank's list was lost for the rest of the night"): price fields given up (they could not be filled
     * in) → try them again at a filter this long later, doubling with every failed try up to [preisfelderNeuMaxMs]
     * (10, 20, 40 min). If none comes by itself, the bot opens the filter for it (always with [abfrageAbstandMs]).
     */
    val preisfelderNeuMs: Long = 600_000,
    val preisfelderNeuMaxMs: Long = 2_400_000,
    /**
     * Nov 23: sooner, if the game's filter may hold other prices that hide this rule's copies (see
     * [AuktionsBot.preisfelderWieder]) – but at most this often.
     */
    val preisfelderEiligMs: Long = 120_000,
    /** Nov 23: a rule's list empty, or only prices outside the rule, for this long (and over a reload) → filter suspected. */
    val preisfelderVerdachtMs: Long = 10_000,
    /** Nov 22: a message over the list the bot does not know (not "Bidding successful", not "Bid Failure"): no tap for this long. */
    val fremdeMeldungPauseMs: Long = 3000,
    /**
     * Nov 22 (review: "refreshes every 1.2 s all night"): if a round found nothing to bid and no copy that fits the rules ends
     * within [BALD_ENDE_SEK], the next refresh comes at the earliest this long after the last one – growing (×1.5) up to
     * [leerlaufMaxMs] while nothing appears. A bid or a copy ending soon makes it fast again.
     */
    val leerlaufMinMs: Long = 5000,
    val leerlaufMaxMs: Long = 15_000,
    /** Nov 22: while waiting for the next refresh, look at the list this often (a copy may move into the rule's time window). */
    val leerlaufBlickMs: Long = 2000,
)

/** Nov 9: outbid this many times in a row (without a win), then switch to the next rank. */
const val MAX_UEBERBOTEN_IN_FOLGE = 5

/**
 * Nov 22 (rate limit): the game said "Operated too frequently" this many times in a row (no bid in between, each within
 * [Takt.zuOftRuhigMs] after the pause before) – despite pauses of 5, 10, 20, 40 s. Then the bot stops ([StoppGrund.ZU_OFT]):
 * something is wrong, and pressing on would only annoy the game's server.
 */
const val ZU_OFT_MAX_IN_FOLGE = 5

/**
 * Nov 22 (review: refresh rate): a copy that fits a rule (price, rule's time window) and ends within this many seconds keeps
 * the refresh fast (as before) – the bot must see at once when it is outbid there. Otherwise a round without a bid
 * waits [Takt.leerlaufMinMs] … [Takt.leerlaufMaxMs] before the next refresh (an outbid bid with more than a minute left is
 * still seen in time). Long runs (DauerlaufTest, "quiet market"): 180 s → refreshes −30 %, 60 s → −55 %, bids and copies
 * won the same.
 */
const val BALD_ENDE_SEK = 60

/** The finger stays down this long for a normal tap. */
const val KURZER_TIPP_MS = 60L

/** Two rows count as in the same place if their top edges are this close together. */
const val GLEICHE_STELLE_PX = 8

/**
 * Nov 22 (rate limit): these taps make the game reload the list from its server (refresh, filter Confirm, sort order).
 * Nov 23 (review round 3): the menu and tab taps too (Auction Market, Bidding Market, My Biddings, tab Items for Bidding) –
 * each of them loads a list, so they are under the same gap of [Takt.abfrageAbstandMs] (see [AuktionsBot.navigationZuFrueh]).
 */
private val LISTEN_ABFRAGE = setOf(
    Knopf.AKTUALISIEREN, Knopf.FILTER_OK, Knopf.SORT_RANG_AUF, Knopf.SORT_PREIS_AUF,
    Knopf.MENUE_AUKTIONSMARKT, Knopf.MENUE_BIETERMARKT, Knopf.MENUE_MEINE_GEBOTE, Knopf.REITER_BIETEN,
)

/** After these taps the list shows other rows – what was remembered (Nov 9) is forgotten. */
private val LISTE_AENDERT_SICH = setOf(
    Knopf.AKTUALISIEREN, Knopf.FILTER, Knopf.FILTER_OK, Knopf.SORTIEREN, Knopf.SORT_RANG_AUF, Knopf.SORT_PREIS_AUF,
    Knopf.MENUE_AUKTIONSMARKT, Knopf.MENUE_BIETERMARKT, Knopf.MENUE_MEINE_GEBOTE, Knopf.REITER_BIETEN,
)

/**
 * Nov 11 (long run, review): after a tap on these buttons a window closes by itself as soon as the game
 * answers (see [Takt.fensterZuWartenMs]). For our own X, [Takt.xNachFensterTippMs] applies.
 */
private val FENSTER_KNOEPFE = setOf(Knopf.BIETEN, Knopf.BESTAETIGEN, Knopf.PASSWORT_OK, Knopf.SOFORTKAUF)

/** Nov 22 (reconnect): these screens mean "the game is back in the auction house". */
private val AUKTIONSHAUS = setOf(Ort.MARKT, Ort.MEINE_GEBOTE, Ort.MARKT_ANZEIGE, Ort.AUKTIONSHAUS_ANDERS)

/**
 * Nov 22 (reconnect): windows of the auction house the bot knows how to close safely (red X, never Cancel) – after a
 * reconnect they count as "back" too. Not the secondary password window (nothing is typed without a bid of our own).
 */
private val AUKTIONSHAUS_FENSTER = setOf(Ort.BIETEN, Ort.BESTAETIGEN, Ort.FILTER, Ort.SORTIEREN)

/** Nov 9 (field test 1.9): tap a bit longer in the sort window (before, it needed 4 taps). */
const val LANGER_TIPP_MS = 110L

/**
 * Nov 9 (field test 1.10/1.11): tapping through the "Bidding successful …" message (with remembered rows)
 * made the bot restless – so it waited until the screenshot was clear again.
 * Nov 11 (field test 1.20, new rule): "It can just keep bidding, like I do: remember the prices and positions of the
 * rows from the first look and bid." A message came every few seconds, and every time the bot waited.
 * Now it keeps bidding: free rows as read, covered ones from the last clear screenshot (at most
 * [GEMERKT_MAX_MS] old, same position in the list, the free rows unchanged). In the Bid window the readable
 * "Current Bid" decides after that (never blind).
 * Nov 22 (rate limit, review before publishing): this applies only to the messages the bot knows ("Bidding successful",
 * "Bid Failure", see [Meldung.bekannt]). The game's "Operated too frequently" message stops all taps for a pause, and a
 * message over the list the bot does not know holds the next tap back for [Takt.fremdeMeldungPauseMs] (see [AuktionsBot]).
 */
const val DURCH_MELDUNG_TIPPEN = true

/** Nov 11 (field test 1.20): the clear screenshot a covered row is taken from may be at most this old. */
const val GEMERKT_MAX_MS = 5000L

/**
 * Nov 9 (field test 1.9): this long after our own bid the row often still shows the old icon
 * (e.g. the hammer) – during this time the copy counts as "mine" (in front) and is not tapped again.
 */
const val GEBOT_FRISCH_MS = 4000L

/**
 * Nov 9 (field test 1.8): a short message in the Bid window counts as the 50 limit right away if My Biddings
 * shows at least (limit − LIMIT_PUFFER) running bids. (Nov 12: cleaning up only starts at the full limit –
 * this buffer only decides whether a "-7" message is believable.)
 */
const val LIMIT_PUFFER = 5

/** The amount in the confirm window may be this much below the bid from the Bid window (otherwise misread). */
const val BETRAG_TOLERANZ = 2

/**
 * Nov 11 (long run): "Current Bid" may be this much above the price in the list (someone just bid).
 * If it is further above, probably a different copy opened (the list moved) – do not bid
 * (otherwise the bot might outbid its own bid).
 */
const val MAX_PREISSPRUNG = 2

/**
 * Nov 11 (long run): if an auction in My Biddings ends in this few seconds, its row disappears in a moment and
 * the rows below move up. Then tap nothing at or below it until the list stands still again.
 */
const val ENDET_GLEICH_SEK = 3

/**
 * Nov 12 (emulator run 13: screenshots up to 2 s old): in the Bid window "auction is ending" already applies from this many
 * seconds (estimated) – the remaining time comes from a screenshot that may itself already be a bit old.
 */
const val ENDET_GERADE_FENSTER_SEK = 5

/** Nov 11 (field test 1.20): a button must be this close to its learned position (pixels). */
const val KNOPF_STELLE_PX = 12

/**
 * Nov 12 (review): this long after our own bid the SG display at the top right may still show the old balance
 * (report Nov 11: the deduction showed after ~2 s at the latest). During this time a HIGHER balance that is read does not count.
 */
const val SG_ANZEIGE_NACH_GEBOT_MS = 3000L

/** The bot tries this often to close the confirm window with X, then it stops. */
const val MAX_X_VERSUCHE = 3

/** This many passes (to the end of the list, refreshed in between) without a bid, then the next rank. */
const val LEERE_DURCHLAEUFE = 2

/** This many rows fit fully on the screen in My Biddings (reference phone: 4). */
const val MEINE_GEBOTE_SICHTBAR = 4

/**
 * Reason when opening My Biddings – only after "-7" (Nov 9) or at about 50 by our own count (Nov 11).
 * There the outbid bids (red X) are bid on again or dismissed – otherwise they keep counting towards the 50.
 */
const val LIMIT_NACHSEHEN = "My Biddings: opening – 50 bids, bid again on outbid ones or dismiss them"

/** Nov 11: in My Biddings the first card (list at the very top) starts here (reference), then one every 181 pixels. */
const val MEINE_ERSTE_OBEN = 374

/** Swiping in My Biddings: "up" (list back to the start) starts here and drags 3 rows down. */
const val MEINE_NACH_OBEN_START_Y = 450

/** The red X of the same row is pressed at most this often, pushed on/pushed up at most this often. */
const val MAX_KREUZ_VERSUCHE = 2

/**
 * Nov 23 (emulator runs 21 and 24): the red X in My Biddings may open the Bid window of ANOTHER row (the list moved
 * between the screenshot and the tap). The bot closes such a window (never bids there) at most this often per clean-up;
 * the next time it stops as before ([StoppGrund.UNBEKANNT_NACH_X]).
 */
const val MAX_BID_NACH_KREUZ = 2

/**
 * Nov 12 (field test 1.33: "the X does not work" on 3 rows – all shortly before or after the end of their auction): if
 * an auction is over or ends in this many seconds, the bot does not press its red X (the game does not accept it
 * then, the row goes away by itself).
 */
const val KREUZ_ENDE_SEK = 15

/**
 * Nov 12 (field test 1.33: in My Biddings almost after every push "moved further than expected – one row
 * back"): afterwards the last row should not be at the very top (there was only 9 pixels of leeway), but this far below.
 */
const val MEINE_RAND_PX = 60
const val MAX_MEINE_WISCHER = 40
const val MAX_NACH_OBEN_WISCHER = 30

/**
 * Nov 11 (scrolling: "always exactly 5 new copies, not 4 and a half"): position of the rows in the bidding list
 * (reference 2712 × 1220). At the very top the first card starts at [ERSTE_ZEILE_OBEN], each further one [ZEILEN_ABSTAND] lower
 * (314, 495, 676, 857, 1038).
 */
const val ERSTE_ZEILE_OBEN = 314
const val ZEILEN_ABSTAND = 181

/** The first row may deviate this much from [ERSTE_ZEILE_OBEN] after scrolling without pushing again. */
const val AUSRICHTEN_TOLERANZ = 24

/**
 * The game may not accept swipes this short – the finger never drags shorter. If less than this is missing after the swipe,
 * the page is aligned well enough. ([ausrichtung]: if the correction would be shorter, go one whole row further back –
 * then a copy already seen is there again, better that than skipping one.)
 */
const val KORREKTUR_MIN_PX = 30

/**
 * Swiping in the list: column and start point (reference). Start at the bottom of the list, drag upwards.
 * x = 1356 like the previous swipe path (there is no button there, only the remaining time).
 * Nov 12 (emulator run 16: after a long swipe from y 1150 the home screen appeared): with gesture navigation
 * a swipe that starts at the bottom edge means "go home". So every swipe starts at least
 * [TippSperre.WISCH_RAND_UNTEN] pixels above the bottom edge (1220 − 200 = 1020; before 1150 = only 70 pixels).
 */
const val WISCH_X = 1356
const val WISCH_START_Y = 1020

/** For the correction downwards (list back a bit): start here and drag down (reference). */
const val KORREKTUR_START_Y = 600

/**
 * Nov 11 (field test 1.20): the finger never drags higher than this (the header bar is above).
 * Nov 12: 200 instead of 160 (see [TippSperre.WISCH_OBEN_MIN_Y]). So the finger drags at most 820 pixels –
 * enough for 5 rows (905 pixels of list) from a ratio of 1.11 (reference phone: 1.14 → 794 pixels).
 */
const val WISCH_ENDE_MIN_Y = TippSperre.WISCH_OBEN_MIN_Y

/** Nov 11 (field test 1.20): the very first swipe of a run (ratio never measured yet) only goes this many rows. */
const val MESS_WISCH_ZEILEN = 2

/** Nov 11 (field test 1.20): the best explanation must be better than the second best by this many pixels, otherwise look back. */
const val WISCH_SICHER_PX = 80

/** Nov 11 (field test 1.20): the bot pushes the list back this far to find a copy already seen. */
const val RUECKBLICK_PX = 272

/**
 * Nov 11 (long run): if the list moved too far, the bot pushes it back so far that the next copy is
 * about this many pixels below the topmost position – and is then satisfied as long as it is at most
 * [ZURUECK_OK_MAX_PX] below (it is fully visible; only a part at the bottom is missing then).
 */
const val ZURUECK_ZIEL_PX = 45
const val ZURUECK_OK_MAX_PX = 110

/**
 * Nov 12 (field test 1.31): from this much finger travel a swipe is "long" (slow, [Takt.wischDauerMs]), below that
 * "short" ([Takt.korrekturDauerMs]: pushing on, looking back). Short swipes move the list by a different amount than long ones
 * (report 1.31: long 1.14 times, short about 1.3 times) – the bot learns both ratios separately.
 */
const val LANGER_WISCHER_PX = 300

/** Nov 12: the bot only learns short swipes from this much finger travel (below that the game swallows part of it). */
const val KURZ_LERNEN_MIN_PX = 100

/** Nov 12: upper limit for the ratio of short swipes (lower limit as for the long ones). */
const val KURZ_VERHAELTNIS_MAX = 2.0

/**
 * Nov 12 (field test 1.33: "wanted 905, moved 1283", "wanted 886, moved 564" – the ratio jumped between 0.93
 * and 1.24): a measurement more than this much (25 %) off the learned ratio does not count – only the third
 * such one in a row (then something really changed).
 */
const val WISCH_AUSREISSER = 0.25

/**
 * Nov 12 (field test 1.33: outlier "wanted 905, moved 1283" – the bot would not have recognized that reliably from
 * the row grid, two copies would have been missed): the big swipe goes so far that the last copy already seen is still
 * visible at the very top (40 pixels below the topmost position). So after EVERY big swipe the bot measures exactly how far
 * the list moved, and then pushes the rest with a short swipe (5 new copies). Before: big swipe,
 * look back, push on (3 swipes) – now 2. If the copy is not visible, something happened: look back.
 */
const val UEBERLAPPUNG_PX = ZEILEN_ABSTAND + 40

/** Nov 11 (field test 1.20): at most this many push-on/look-back swipes per page. */
const val MAX_WISCH_SCHRITTE = 8

/**
 * Nov 12 (long run with many messages): when looking back and when pushing on in steps, a copy already known
 * should come here (top edge) – at the very top, but surely still fully visible.
 */
const val BEKANNT_OBEN_Y = ERSTE_ZEILE_OBEN + 40

/** Nov 11 (field test 1.20): limits for the learned ratio "moved / dragged". */
const val WISCH_VERHAELTNIS_MIN = 0.5
const val WISCH_VERHAELTNIS_MAX = 1.5

/** Two screenshots count as "list stands still" if no row moved more than this many pixels. */
const val STILL_TOLERANZ_PX = 2

/**
 * Nov 11: swipe so far that the row after the last fully visible one ([letzteOben]) is exactly at
 * [ERSTE_ZEILE_OBEN] – at least 1, at most 5 rows. Aligned page (last at 1038): 905 pixels.
 */
fun wischWeite(letzteOben: Int): Int =
    (letzteOben + ZEILEN_ABSTAND - ERSTE_ZEILE_OBEN).coerceIn(ZEILEN_ABSTAND, 5 * ZEILEN_ABSTAND)

/**
 * Nov 11: if the first fully visible row ([erste], reference) is not at [ERSTE_ZEILE_OBEN], there is a half-covered
 * row above it. The list is then pushed DOWN so far that exactly this row is at the very top –
 * so no copy is ever skipped (at worst one already seen is there again).
 * null = already fits. Example (field test 1.13): first row at 411 → 84 pixels down.
 */
fun ausrichtung(erste: Int?): Int? {
    if (erste == null) return null
    if (kotlin.math.abs(erste - ERSTE_ZEILE_OBEN) <= AUSRICHTEN_TOLERANZ) return null
    val weg = (ERSTE_ZEILE_OBEN - erste).mod(ZEILEN_ABSTAND)
    return if (weg < KORREKTUR_MIN_PX) weg + ZEILEN_ABSTAND else weg
}

/**
 * The bot's flow. Every call of [schritt] looks at the screen once and does
 * at most ONE action. After every action the next
 * step checks on the new screenshot whether it worked.
 *
 * Flow (Nov 9):
 *  1. Filter: tap the rank of the rule (deselect the old one), set the price fields to the min/max price of the rule
 *     (otherwise the right one says 99999999), Confirm. If the list still shows the old rank afterwards → refresh.
 *  2. Sort order "Awakened Tier ↑": so the copies that end soon are at the top.
 *  3. Bid from the top: clock = I am in front → nothing; hammer = outbid → bid again (+1); no remaining time
 *     (auction already over) → skip. Only up to the max price and never more than the SG allow.
 *     Bidding: row → Bid window (check Current Bid; Nov 23: and the bid field, never under a message band) → Bid (never
 *     Buyout) → secondary password if needed → confirm window (amount ≤ max price + 1) → Confirm.
 *  4. If only copies that end later come, or the list is at its end → refresh, start over at the top.
 *  5. Next rank (next rule) if there was nothing to bid twice in a row until the end
 *     or I was outbid 5 times in a row. After the last rule, the first one again.
 *  6. Nov 9 (field test 1.10/1.11): if a message lies over the list ("Bidding successful …"), nothing is
 *     tapped until the screenshot is clear again. My Biddings is ONLY opened after "Server status code:-7" (50 bids).
 *  7. Nov 11: there the bot goes through the whole list from top to bottom. Outbid bids (red X at the top right)
 *     keep counting in the game until the X is pressed: if the price still fits the rule → bid again, otherwise → press X
 *     (makes room). Rows with a clock stay. Then back to the Bidding Market and keep bidding. If no room
 *     was freed, it waits until the earliest end (+5 s, at most about 2 minutes). Nov 11: besides after "-7" also
 *     when our own count ([GebotsVerwaltung.eintraege]) reaches 50 – at most once every 2 minutes.
 *  8. Nov 11: loading screen (all dark, golden star) → just wait, tap nothing.
 *  9. Nov 22 (rate limit): "Operated too frequently. Please retry in N second(s)." → tap nothing for 5 s (10, 20, 40, 60 s if
 *     it comes again soon); the 5th time in a row → stop ([StoppGrund.ZU_OFT]). An unknown message over the list → no tap
 *     for 3 s. See [spielBremse]. Nov 23 (review): the same for an unknown message in the Bid or confirm window – and there
 *     nothing is ever tapped while it is visible (only the red X closes the window, after the usual waits).
 * 10. Nov 22 (review: refresh rate): a round without a bid and without a fitting copy ending within [BALD_ENDE_SEK] →
 *     the next refresh only 5 s after the last one (growing to 15 s). See [aktualisierenWarten].
 *     Nov 23 (review): at most one reload of the list (refresh, filter Confirm, sort order) every 5 s, ALWAYS
 *     ([Takt.abfrageAbstandMs], see [abfrageZuFrueh]).
 * 11. Nov 23 (review): the SG budget counts what THIS run spent, net (bought + bids in front; outbid bids give their SG
 *     back) – from the SG balance and from the bot's own bookkeeping, the larger counts (see [budgetAusgegeben]).
 */
class AuktionsBot(
    regeln: List<Suchregel>,
    private val stopp: StoppBedingung,
    private val leser: BildschirmLeser,
    val gebote: GebotsVerwaltung = GebotsVerwaltung(),
    private val takt: Takt = Takt(),
    private val protokoll: (String) -> Unit = {},
    /**
     * Is a secondary password saved? (The core does not know the password itself.)
     * The app sets this before every step – so a password entered during the run takes effect right away.
     */
    hatZweitpasswort: Boolean = false,
    /**
     * SG balance at the start of this run (for the SG budget). The app saves it so that it is kept after a
     * crash/restart within the same run; on a new run it is null (then it is read).
     */
    startSg: Int? = null,
    /** Tap through a message lying over the list? Default: see [DURCH_MELDUNG_TIPPEN]. */
    private val durchMeldungTippen: Boolean = DURCH_MELDUNG_TIPPEN,
    /**
     * Nov 11 (field test 1.20): ratio "list moved / finger dragged" from the last run (the app saves
     * [wischVerhaeltnis] after every run). null = none yet – then the bot measures on the first swipe.
     */
    wischVerhaeltnisStart: Double? = null,
    /**
     * Nov 22 (overnight, owner's decision): after "Lost connection" the bot waits [Takt.verbindungWartenMs], taps Confirm
     * and waits for the game to come back – at most this many times per run (the next loss is a stop). 0 = stop at once
     * (the behaviour before Nov 22).
     */
    private val maxNeuverbindungen: Int = 5,
    /**
     * Nov 22 (owner): buy copies outright ("Buyout Price") – for rules with [Suchregel.buyoutMax]. Default off: then
     * the bot bids exactly as before and never touches the Buyout button.
     */
    private val buyoutAn: Boolean = false,
) {
    /** What I am about to do. */
    private sealed interface Phase {
        data object Suchen : Phase
        /**
         * [schliessen]: Nov 11 (long run: bid 14 with 13 SG): a reason to close the window was already found on a
         * screenshot (only the X had to wait for a steady screenshot). That stays – a later, worse-read
         * screenshot must not turn it back into "bid after all".
         */
        data class BietenOffen(
            val ziel: Ziel,
            val seit: Instant,
            val schliessen: Schliessen? = null,
            /**
             * Nov 12 (review): "Current Bid" on the last steady screenshot – if the price in the row was not readable,
             * the bot only bids once it was read the same twice in a row.
             */
            val currentBidVorher: Int? = null,
        ) : Phase
        data class BietenGetippt(val ziel: Ziel, val seit: Instant) : Phase

        /** Nov 22 (buyout): "Buyout Price" was tapped at [seit]; [sgVorher] = SG balance just before. */
        data class BuyoutGetippt(val ziel: Ziel, val seit: Instant, val sgVorher: Int?) : Phase
        data class Bestaetigt(val ziel: Ziel, val seit: Instant) : Phase
        /**
         * Set the filter. Nov 9 (speed): first everything in ONE [Aktion.Folge] (rank on/off + both
         * price fields), then check once. If that does not work, step by step ([einzeln]).
         */
        data class FilterSetzen(
            val stufe: String,
            val oeffnen: Int = 0,
            val taps: Int = 0,
            val schritt: FilterSchritt = FilterSchritt.RANG,
            val einzeln: Boolean = false,
        ) : Phase
        data class FilterPruefen(val stufe: String, val aktualisiert: Boolean = false) : Phase
        data object Sortieren : Phase
        data class Passwort(val ziel: Ziel?, val eingegeben: Boolean, val geprueftUm: Instant?) : Phase
    }

    private enum class FilterSchritt { RANG, MIN, MAX, FERTIG, FOLGE_PRUEFEN }

    /** Nov 11 (long run): why the Bid window is closed without a bid (see [Phase.BietenOffen.schliessen]). */
    private data class Schliessen(val warum: String, val text: String?, val sperren: Boolean)

    /** The copy being bid on right now. */
    private data class Ziel(
        val id: String,
        val regel: Suchregel,
        val preis: Int,
        val restzeitSek: Int?,
        val nachbieten: Boolean,
        /** Nov 9: when tapped, the row lay under the "Bidding successful …" message (value from memory). */
        val durchMeldung: Boolean = false,
        /** Nov 11: bidding again from My Biddings (when cleaning up after "-7") – the game goes back there afterwards. */
        val ausMeineGebote: Boolean = false,
        /**
         * Nov 11 (review): [preis] comes from a readable "Current Bid" in the Bid window. Only then may the
         * confirm window trust the Bid window when the amount is unreadable.
         */
        val currentBidGelesen: Boolean = false,
        /** Nov 11 (field test 1.20): was the price in the row readable? (Otherwise only the game's filter counts.) */
        val listenpreisBekannt: Boolean = true,
        /** Nov 12 (review): when the auction ends (from the remaining time when tapped; null = unknown). */
        val endeUm: Instant? = null,
        /** Nov 22 (buyout): the row was opened to buy it outright for this many SG ("Buyout Price" in the list). */
        val buyout: Int? = null,
        /** Nov 23 (review round 3): what the bid field showed when "Bid" was tapped (for the log line "Bid placed"). */
        val bietFeld: String? = null,
    )

    /**
     * Nov 11: cleaning up My Biddings after "-7". Outbid bids (red X at the top right of the row) keep counting
     * towards the 50 in the game until the X is pressed. So the bot goes through the WHOLE list from top to bottom:
     * if the shown price still fits the rule (price ≤ max price, so bid ≤ max price + 1; remaining time, SG,
     * budget) → bid again. Otherwise → press the red X (frees a slot). Rows with a clock stay as they are.
     */
    private class Aufraeumen(
        val start: Instant,
        /** Nov 11: because of our own count (about 50 in My Biddings) – not because of "-7". */
        val durchZaehlung: Boolean = false,
    ) {
        var ersterBlick = true

        /** All copies visible in the list during this pass (for counting if the counter is unreadable). */
        val gesehen = HashSet<String>()
        val weggedrueckteIds = HashSet<String>()

        /** This many actions in this pass (before the first one, "-7" may still be a false alarm). */
        var aktionen = 0

        /** Copies that are done in this pass (bid again, dismissed or given up). */
        val fertig = HashSet<String>()

        /** Bidding again did not work here (copy → reason) – then the X is pressed. */
        val nichtNachbieten = HashMap<String, String>()
        val kreuzVersuche = HashMap<String, Int>()
        var nachgeboten = 0
        var weggedrueckt = 0

        /** When the seen bids end (for the waiting time, in case no slot is freed). */
        val enden = HashMap<String, Instant>()

        /** Is the list at the very top? (First push it up until it does not move any more.) */
        var obenGeprueft = false
        var nachObenWischer = 0
        var wischer = 0
        var amEnde = false

        /** Rows of the last screenshot – only when two screenshots in a row are the same, the list stands still. */
        var vorige: MeineSicht? = null

        /** Since when the rows stand still but are not read the same (a number is flickering). */
        var wackeltSeit: Instant? = null
        var leerSeit: Instant? = null
        var wischen: MeineWischen? = null

        /** Bidding again right now: copy and the price that was shown in My Biddings. */
        var nachbieten: Pair<String, Int>? = null

        /** Just pressed the red X. */
        var weg: KreuzTipp? = null
        var menueVersuche = 0

        /** Nov 23: how often a Bid window opened after the red X in this clean-up (see [MAX_BID_NACH_KREUZ]). */
        var bidNachKreuz = 0

        /** Nov 23: when the bot decided to close such a Bid window (old screenshots of it are not tapped, see [AuktionsBot.schritt]). */
        var bidZuUm: Instant? = null

        /** Nov 11 (review): since when "an auction is about to end" applies (wait only a short time for it). */
        var endetGleichSeit: Instant? = null
    }

    /**
     * Nov 11: what My Biddings shows on a screenshot: copy and icon (X/clock) per row, and the top edges.
     * Two screenshots are "the same" if the same rows are there and none moved more than [STILL_TOLERANZ_PX].
     */
    private class MeineSicht(val zeilen: List<String>, val oben: List<Int>) {
        fun stehtGleich(o: MeineSicht?): Boolean =
            o != null && oben.size == o.oben.size && oben.zip(o.oben).all { (x, y) -> kotlin.math.abs(x - y) <= STILL_TOLERANZ_PX }

        fun gleich(o: MeineSicht?): Boolean = stehtGleich(o) && zeilen == o!!.zeilen
    }

    /** Nov 11: swiping in My Biddings – how was the list before, and what should be visible afterwards? */
    private class MeineWischen(
        val vorher: MeineSicht,
        val vorherOben: Map<String, Int>,
        val ersteId: String?,
        val ersteOben: Int?,
        /** The last fully visible row before – it must still be visible afterwards (at the top), otherwise something is missing. */
        val letzteId: String?,
        val soll: Int,
        val nachOben: Boolean,
        val zurueck: Boolean = false,
    )

    /** Nov 11: the red X of a row was pressed ([vorher] = rows on the screenshot before). */
    private class KreuzTipp(val schluessel: String, val id: String?, val vorher: MeineSicht, val um: Instant, val text: String) {
        /** Since when something other than My Biddings is visible after the X. */
        var fremdSeit: Instant? = null
    }

    /**
     * Nov 9: after a bid, check on the row whether it arrived (clock = I am in front).
     * [warNeu]: was there already one of our own bids on this copy before (then it was a re-bid)?
     * Nov 22 (review): [hoechstens] = the rule's highest bid (max price + 1); [zuHoch] = a row price above it, read once
     * with our clock on the row (read the same a second time → stop, see [pruefeLetztesGebot]).
     */
    private data class Pruefung(
        val id: String,
        val seit: Instant,
        val warNeu: Boolean,
        val ohneZeichen: Int = 0,
        val hoechstens: Int = Int.MAX_VALUE,
        val zuHoch: Int? = null,
    )

    /** Only rules the game knows in the filter (C- to SSS). Order = priority (Nov 8). */
    val aktiveRegeln: List<Suchregel> = regeln.filter { it.aktiv && it.gueltig && it.kopie in KOPIE_TYPEN }

    @Volatile
    var pausiert: Boolean = false

    @Volatile
    private var gestoppt: StoppGrund? = null

    @Volatile
    var hatZweitpasswort: Boolean = hatZweitpasswort

    /** For display: too little SG for any bid (bot is waiting). */
    @Volatile
    var zuWenigSg: Boolean = false
        private set

    /** For display: the game wants the secondary password, but none is saved. */
    @Volatile
    var passwortFehlt: Boolean = false
        private set

    /**
     * SG balance at the start (for the budget). The app saves it on every change.
     * Nov 23 (review: "the SG budget can grow"): raised when a steady balance is ABOVE it (SG came in from outside, see
     * [startAnheben]) – a higher balance never creates extra budget.
     */
    @Volatile
    var startSg: Int? = startSg
        private set

    /**
     * Nov 11 (long run): SG check when counting purchases (see [GebotsVerwaltung.auswerten]) – only if the
     * bot read the start SG itself. After a restart within the same run it does not know the earlier bids.
     */
    private val sgAbgleich = startSg == null

    /** Since when the SG balance has been read unchanged, and when the last bid was placed (for a reliable SG check). */
    private var sgGleichSeit: Instant? = null
    private var letztesGebotUm: Instant? = null

    /** Nov 11: SG balance read last (shown in reports). */
    val letzterSgStand: Int? get() = letzteSg

    /**
     * For display: this many SG are planned since the start (spent + in running bids). Nov 23: the same number the budget
     * uses ([budgetAusgegeben]), worked out at the end of every step (the app reads it from its own thread).
     */
    @Volatile
    var sgVerplant: Int? = null
        private set

    /** For display: budget planned – the bot waits for running bids. */
    @Volatile
    var budgetVerplant: Boolean = false
        private set

    /** Nov 11: for display – bought + leading copies are enough for the goal, the bot waits. */
    @Volatile
    var zielVerplant: Boolean = false
        private set

    /** For display (Nov 9): the SG balance at the top right has never been readable – the bot does not bid. */
    @Volatile
    var sgUnlesbar: Boolean = false
        private set

    /** Result of the app's last text input (null = no answer yet). */
    @Volatile
    private var eingabe: Boolean? = null

    /**
     * Can the price fields be filled in? (null = not tried yet, false = no → check prices ourselves)
     * Nov 23: false is no longer for good – they are tried again later ([preisfelderWieder]).
     */
    var preisfelderGehen: Boolean? = null
        private set

    private var ende: Instant? = null
    private var gestartet = false
    private var unbekanntInFolge = 0
    private var phase: Phase = Phase.Suchen
    private var regelNr = 0

    /** Which rank the filter in the game is set to right now (null = I don't know → set it myself). */
    var filterStufe: String? = null
        private set
    private var filterFehlversuche = 0

    /** Nov 11 (long run): this many times in a row the price fields did not work, although they worked once before. */
    private var preisfelderFehler = 0

    /**
     * Nov 11 (field test 1.20): for which rank and max price the price fields in the game's filter were last reliably
     * entered and applied with Confirm (null = not certain).
     */
    private var filterMaxGesetzt: Pair<String, Int>? = null

    // Nov 23 (coordinator): price fields given up are tried again later – see [preisfelderWieder].

    /** Did the price fields work at least once in this run? */
    private var preisfelderGingSchon = false

    /** How long the bot waits before the next try after giving up (0 = not given up yet; 10, 20, 40 min). */
    private var preisfelderPauseMs = 0L

    /** From when the price fields are tried again (null = not given up). */
    private var preisfelderNeuAb: Instant? = null

    /** When the bot last tried them again sooner because the filter may hold other prices (at most every 2 min). */
    private var preisfelderEiligUm: Instant? = null

    /** The bot asked for a filter with a new try of the price fields (from the bidding list). */
    private var preisfelderAngefordert = false

    /** This filter tries the price fields although they were given up. */
    private var preisfelderDiesmal = false

    /** Min and max price last entered in the game's filter and confirmed (null = not known, e.g. at the start). */
    private var filterPreiseBestaetigt: Pair<Int, Int>? = null

    /**
     * An input failed since then: one of the two fields may hold something else now (long run: "10–60" confirmed, a failed
     * input left "20" in the max field – the B- list stayed empty).
     */
    private var filterPreiseUnsicher = false

    /** Since when the list of the current rule looks filtered by other prices (empty, or only prices outside the rule). */
    private var falscheListeSeit: Instant? = null

    /** For tests and the log: how often the bot tried the price fields again after giving them up. */
    var preisfelderNeuVersuche: Int = 0
        private set

    /**
     * Does the list only show copies up to the max price of [regel] because of the game's filter?
     * Nov 22 (buyout): NOT when the filter is wider than the rule (buyout maximum above the max price) – then the list also
     * shows copies above the max price, and an unreadable price must never count as "fits".
     */
    private fun preisDurchFilter(regel: Suchregel): Boolean =
        preisfelderGehen == true && filterStufe == regel.kopie && filterMax(regel) == regel.maxPreis &&
            filterMaxGesetzt == (regel.kopie to regel.maxPreis)

    /**
     * Nov 22 (coordinator): the max price entered in the game's filter. With buyout on and a buyout maximum above the rule's
     * max price, the filter goes up to the buyout maximum – a copy's current bid is never above its buyout price, so every
     * buyout candidate shows up. Bids are still only placed on rows whose price is at most the rule's max price (checked per row,
     * in the Bid window and in the confirm window).
     */
    private fun filterMax(regel: Suchregel): Int =
        if (buyoutAn && regel.buyoutMax != null) maxOf(regel.maxPreis, regel.buyoutMax) else regel.maxPreis

    private var sortierVersuche = 0
    private var vorWischen: List<String>? = null
    private val versuche = HashMap<String, Int>()
    private var letzteSg: Int? = null
    private var gebotenInDurchlauf = false
    private var leereDurchlaeufe = 0

    /** Nov 12 (field test 1.31): the cheapest bid of this round for which the budget or SG were not enough (null = none). */
    private var zuTeuerInRunde: Int? = null

    /**
     * Nov 12 (field test 1.31): the money (SG budget or SG) is not enough for any copy in the list – this is what the
     * cheapest one would need (null = the money is enough). The bot then does not page further and waits visibly (see [geldPruefen]).
     */
    @Volatile
    private var geldFehltFuer: Int? = null
    private var geldFehltDurchBudget = false
    private var geldFehltSeit: Instant? = null
    private var geldNachsehenAb: Instant? = null
    private var geldVorneLeerSeit: Instant? = null

    /** Nov 22 (reconnect): since when the "Lost connection" dialog is visible (null = not right now). */
    private var verbindungWegSeit: Instant? = null

    /** Nov 22 (reconnect): when Confirm was tapped – the bot now waits for the auction house (null = not reconnecting). */
    private var wiederverbindenSeit: Instant? = null

    /** Nov 22: how often the bot reconnected in this run (for the app and the log). */
    var neuverbindungen: Int = 0
        private set

    /** Nov 22 (buyout): copies for which the Buyout button was tapped (or the attempt was given up) – never again. */
    private val buyoutVersucht = HashSet<String>()

    /** Nov 22 (buyout): after the window closed – did the SG drop by the buyout price? */
    private class BuyoutPruefung(val ziel: Ziel, val sgVorher: Int?, val um: Instant)
    private var buyoutPruefung: BuyoutPruefung? = null

    /** Nov 22 (buyout): copies bought outright in this run. */
    var sofortGekauft: Int = 0
        private set

    /** Nov 22 (buyout): stop with this reason at the next step (the screenshot before was marked "uncertain" to be saved). */
    private var stoppNachBild: StoppGrund? = null

    // ---------------- Nov 22: the game's rate limit ("Operated too frequently") and the refresh rate ----------------

    /** Until when the bot taps nothing because of a message of the game (null = no pause). For tests and display. */
    var zuOftPauseBis: Instant? = null
        private set

    /** Since when a message that holds the bot back ("too frequently" or unknown) has been visible (null = none right now). */
    private var bremsMeldungSeit: Instant? = null

    /** When the last such message appeared (start of the last pause). */
    private var bremsEreignisUm: Instant? = null

    /** When the last pause ended (for "comes again soon" and the calm period). */
    private var pauseVorbeiUm: Instant? = null

    /** Was the current pause caused by "too frequently" (log "pause over")? */
    private var pauseZuOft = false

    /** Level of the pause: 1 = [Takt.zuOftPauseMs], 2 = twice as long … (0 = calm). */
    private var zuOftStufe = 0

    /** "Too frequently" this many times in a row without a bid in between (stop at [ZU_OFT_MAX_IN_FOLGE]). For tests and display. */
    var zuOftInFolge: Int = 0
        private set

    /** How often the game said "too frequently" in this run (unknown messages that came again soon count too). */
    var zuOftMeldungen: Int = 0
        private set

    /** When the bot last made the game reload the list ([LISTEN_ABFRAGE]: refresh, filter Confirm, sort order). */
    private var letzteAbfrageUm: Instant? = null

    /**
     * Nov 23 (review): reloads of the list held back at the very last moment because the last one was less than
     * [Takt.abfrageAbstandMs] ago (see [schritt]). The flow already waits before that – for tests this stays 0.
     */
    var abfrageGebremst: Int = 0
        private set

    /** The bot is waiting this long between two refreshes because there is nothing to do (0 = refresh fast, as before). */
    private var leerlaufMs = 0L

    /** Until when a copy that fits a rule ends soon (then refresh fast). */
    private var baldEndeBis: Instant? = null

    /** For tests and display: how many refreshes the bot held back because there was nothing to do. */
    var leerlaufGewartet: Int = 0
        private set

    /** Nov 12 (field test 1.33): what the copies seen last would cost (id → bid, when seen). */
    private val preiseGesehen = HashMap<String, Pair<Int, Instant>>()

    /** Nov 12: this many measurements in a row did not fit the learned ratio (long / short). */
    private var ausreisserInFolge = 0
    private var kurzAusreisserInFolge = 0

    /**
     * For display (Nov 12): this many SG would be needed for the cheapest copy the money is not enough for right now
     * (null = the money is enough). In addition [budgetVerplant] (budget) or [zuWenigSg] (SG) shows the hint at the IH button.
     */
    val geldFehltFuerSg: Int? get() = geldFehltFuer
    private var passwortBestaetigt = false
    private var sgUnlesbarGemeldet = false
    private var pruefung: Pruefung? = null

    /**
     * Nov 9 (field test 1.8: opened and cancelled the same row three times): rows that are NOT opened again
     * until this time – after every cancelled or failed attempt.
     */
    private val gesperrtBis = HashMap<String, Instant>()

    /** When "Verify" was last tapped – shortly after that a message in the Bid window is never the limit. */
    private var verifyUm: Instant? = null

    /** How many times "Bid" was tapped, and at which tap the last suspicious short message came. */
    private var bidTipps = 0
    private var limitVerdacht: Pair<Instant, Int>? = null

    /** This many times in a row tried to close the confirm window with the red X. */
    private var bestaetigenXInFolge = 0

    /** Nov 11 (long run): when Bid, Confirm, Verify or a red X was last tapped (see [Takt.xNachFensterTippMs]). */
    private var fensterTippUm: Instant? = null

    /** Nov 11 (long run): when the bot itself last tapped a red X (Bid or confirm window). */
    private var xTippUm: Instant? = null

    /**
     * Nov 12 (emulator run 13): what visibly changes on a screenshot because of a tap – place, message, rows
     * (values, position, price, icon), prices and fields in the windows. Without clocks and SG (those change by themselves).
     */
    private data class Abdruck(
        val ort: Ort,
        val meldung: Triple<Boolean, Boolean, Boolean>?,
        val zeilen: List<String>,
        val laufend: Int?,
        val keine: Boolean,
        val gebot: Int?,
        val bestaetigen: Int?,
        val filter: List<Boolean>,
        val stellen: Pair<Int?, Int?>,
        val sortierung: Sortierung,
    )

    private fun abdruck(b: Bildschirm) = Abdruck(
        ort = b.ort,
        meldung = b.meldung?.let { Triple(it.kurz, it.wieLimit, it.wieErfolg) },
        zeilen = b.zeilen.map { z -> "${z.id ?: "?"}@${z.oben / 15}:${z.preis}:${z.zeichen}:${z.ueberbotenKreuz}" },
        laufend = b.laufendeGebote,
        keine = b.keineGebote,
        gebot = b.aktuellesGebot,
        bestaetigen = b.bestaetigenPreis,
        filter = b.filterFelder.map { it.gewaehlt },
        stellen = b.filterMinStellen to b.filterMaxStellen,
        sortierung = b.sortierung,
    )

    /** Nov 12: screenshots on which the bot last acted – until when a screenshot that looks the same counts as "old". */
    private val alteBilder = ArrayList<Pair<Abdruck, Instant>>()

    /** Nov 12: this many times a tap was not made because the screenshot still looked old (for tests and the log). */
    var altesBildGewartet = 0
        private set

    /** Nov 11 (emulator test): when "Confirm" was last tapped in the filter (see [Takt.filterZuWartenMs]). */
    private var filterOkUm: Instant? = null

    /** Is [um] less than [Takt.xNachFensterTippMs] ago? (Then a window may be closing right now.) */
    private fun gerade(um: Instant?, jetzt: Instant): Boolean =
        um != null && Duration.between(um, jetzt).toMillis() < takt.xNachFensterTippMs

    /**
     * Nov 11 (review): after "Resume" a completely different window may be open than before the pause. What the bot
     * was about to do (tap Bid, tap X …) no longer applies – look at everything anew, continue nothing from old screenshots.
     */
    private fun nachDerPause() {
        warPausiert = false
        vorigerOrt = null
        ortSeit = null
        if (phase is Phase.BietenOffen || phase is Phase.BietenGetippt || phase is Phase.Bestaetigt || phase is Phase.Passwort) {
            phase = Phase.Suchen
        }
        nachWischen = null
        vorWischen = null
        aufraeumen?.let {
            it.vorige = null
            it.weg = null
            it.wischen = null
            it.nachbieten = null
        }
        protokoll("Resumed after the pause – looking at everything anew")
    }

    /** Nov 11 (long run): since when there has been too little SG and none of our own bids is in front any more. */
    private var sgAlleSeit: Instant? = null

    /** Nov 11 (field test 1.20): where the Bid button is in the fully open window (learned; null = not yet). */
    var bidKnopfGelernt: Punkt? = null
        private set

    /** Nov 11 (field test 1.20): since when "Bidding successful" has been visible without a break (null = not right now). */
    private var erfolgSeit: Instant? = null

    // Nov 11 (review: "never wait silently forever"): since when the bot has been waiting for something that does not come.
    private var bidMeldungSeit: Instant? = null

    /**
     * Nov 23 (review round 3): the last screenshot of a Bid window with a message band on it. While a band lies over the
     * window the bot does not tap Bid – the short waits for a readable "Current Bid" or the Bid button count only from the
     * end of the band (otherwise a single unreadable screenshot right after a band closed the window).
     */
    private var bidMeldungZuletzt: Instant? = null
    private var meineMeldungSeit: Instant? = null
    private var passwortFehltSeit: Instant? = null
    private var sgUnlesbarSeit: Instant? = null
    private var restzeitUnlesbarSeit: Instant? = null

    /** Nov 11 (review): was the bot paused in the last step? (After "Resume" look at everything anew.) */
    private var warPausiert = false

    /**
     * Nov 11 (review: a misread SG balance counted right away): the SG balance read last (raw) and the one
     * last read the same twice in a row. Higher balances only count once they were read the same twice.
     */
    private var sgRoh: Int? = null
    private var sgZweimal: Int? = null

    /** Time of the current step (for timing, Nov 9: "where does the time go?"). */
    private var uhr: Instant = Instant.EPOCH
    private var filterStart: Instant? = null
    private var sortStart: Instant? = null
    private var gebotStart: Instant? = null

    /**
     * Nov 12 (field test 1.33): SG balance when the bot tapped the row for the running bid. If the display
     * already shows the deduction before the bid is recorded, it is not subtracted again (otherwise the bot saw too little SG
     * for a few seconds and skipped affordable copies).
     */
    private var sgBeiGebotStart: Int? = null

    /** Until when an unknown screenshot counts as "window is opening" (after every action). */
    private var uebergangBis: Instant? = null

    /** Since when the current message over the list has been visible (null = none right now). */
    private var meldungSeit: Instant? = null

    /**
     * Nov 9: if the Bid window did not open after a tap THROUGH the message, the bot no longer taps through
     * the message in this run, but waits again until it is gone.
     */
    private var durchMeldungGehtNicht = false

    /** Since when the sort window has been open (Nov 9: only tap once it is fully open). */
    private var sortFensterSeit: Instant? = null

    /** Nov 11 ([TippSperre]): place of the last screenshot and since when it has been visible without a break. */
    private var vorigerOrt: Ort? = null
    private var ortSeit: Instant? = null

    /** Did the screenshot before already show the same place (for at least [Takt.ruhigMs])? Applies to the current step. */
    private var ortRuhig = false

    /** Nov 11 (loading screen): until when an unknown screenshot counts as "game loading" (after every action). */
    private var ladenBis: Instant? = null

    /** Nov 11: since when the loading screen with the golden star has been visible (null = not right now). */
    private var ladenSeit: Instant? = null

    /**
     * Nov 11 (long run): when the last loading screen was over. The time while the game is loading does not count
     * as "window does not close" – after loading, the window gets the full waiting time again.
     */
    private var ladenVorbeiUm: Instant? = null

    /** Nov 11: cleaning up My Biddings after "-7" or at about 50 (null = not right now). */
    @Volatile
    private var aufraeumen: Aufraeumen? = null

    /** For tests and display: is My Biddings being cleaned up right now? */
    val raeumtAuf: Boolean get() = aufraeumen != null

    /** Nov 11: cleaning up because of our own count only again from then on (null = any time). */
    private var naechsterDurchgangAb: Instant? = null

    /**
     * Nov 11 (scrolling): after the swipe first wait until the list stands still (two screenshots with the same
     * rows), then check whether the row AFTER the last one seen before is exactly at the top, and push on if needed.
     *
     * Nov 11 (field test 1.20: "corrects nicely, but scrolls clearly too far"): the bot keeps track of how far the
     * list really moved since the page before the big swipe ("reference") ([gewandert]). It knows this exactly when
     * a copy of the reference page is still visible; otherwise it follows from the row grid (one card every 181 pixels)
     * and the learned ratio "moved / dragged" ([wischVerhaeltnis]). If that is not certain (first swipe, the list
     * kept sliding, two possibilities almost equally good), it briefly looks back until a copy of the reference page
     * shows up again. That way no copy is ever skipped.
     */
    private data class NachWischen(
        /** From here on the finger should be lifted (swipe + holding still over). */
        val seit: Instant,
        /** Copies of the reference page and where they were (id → top edge). */
        val bezug: Map<String, Int>,
        /** Top edge of the last fully visible row of the reference page. */
        val bezugLetzte: Int,
        /** The list should move this far in total (then the row after the last one seen is at the top). */
        val ziel: Int,
        /** This is how far it moved before the last swipe (known or estimated). */
        val vorher: Double,
        /** How far the last swipe probably moved it (finger × ratio; + = up). */
        val erwartet: Double,
        /** Finger travel of the last swipe (pixels; + = dragged up). */
        val laenge: Int,
        /** Was the last swipe the big one (5 rows or measuring swipe)? Then the ratio is measured. */
        val haupt: Boolean,
        /** Copies on the screenshot right before the last swipe (for short swipes: measure exactly). */
        val davor: Map<String, Int>,
        /** How far the big swipe probably moved the list and how long it was (for the measurement afterwards). */
        val hauptVorher: Double,
        val hauptLaenge: Int,
        /** Movement of the short swipes measured exactly since the big swipe (for measuring the big one). */
        val kurzSumme: Double = 0.0,
        /** Everything measured exactly since the big swipe? (Otherwise [vorher] is only estimated.) */
        val genau: Boolean = false,
        /** Top edges of the rows on the last screenshot (null = none yet after the swipe). */
        val vorigeOben: List<Int>? = null,
        /** Did the list still move after the finger was lifted? (Then the estimate is uncertain.) */
        val nachgerutscht: Boolean = false,
        /** This many swipes (pushing on, looking back) since the big one. */
        val schritte: Int = 0,
        /** Was the estimate before the last swipe certain? */
        val vorigeSicher: Boolean = false,
        /** Has the movement of the big swipe been measured yet (ratio learned)? */
        val hauptGemessen: Boolean = false,
        /** Were all short swipes since the big one measured exactly (against the screenshot before)? */
        val kurzGenau: Boolean = true,
        /** Since when a message has been covering rows during the check (null = not right now). */
        val meldungSeit: Instant? = null,
        /**
         * Nov 12: could the big swipe not drag far enough (finger at most up to [WISCH_ENDE_MIN_Y])? Then it
         * is enough if the next copy is fully visible – one is missing at the bottom, the next page makes up for it.
         */
        val gekappt: Boolean = false,
        /**
         * Nov 12 (long run with many messages): the last screenshot on which it was certain how far the list had
         * moved – its copies (id → top edge) and the distance. If one of them shows up again, the bot measures exactly
         * (before, it looked back up to three times after an uncertain push).
         */
        val anker: Map<String, Int> = emptyMap(),
        val ankerGewandert: Double = 0.0,
        /**
         * Nov 12 (review): ratio and number of measurements BEFORE measuring the big swipe. If it turns out afterwards
         * that the list was at its end, the measurement is taken back (see [nachWischenPruefen]).
         */
        val vorMessung: Pair<Double, Int>? = null,
    )

    private var nachWischen: NachWischen? = null

    /**
     * Nov 12 (field test 1.31): ratio "moved / dragged" of the SHORT swipes (pushing on, looking back);
     * null = not measured yet (then [wischVerhaeltnis] applies).
     */
    var wischVerhaeltnisKurz: Double? = null
        private set


    /**
     * Nov 11 (field test 1.20): how far the list really moves in the bidding list, divided by the
     * finger travel (1.0 = exact; 1.3 = 30 % too far). The bot learns more with every big swipe. The app may
     * save the value and pass it in on the next run ([AuktionsBot] parameter `wischVerhaeltnisStart`).
     */
    var wischVerhaeltnis: Double = wischVerhaeltnisStart?.coerceIn(WISCH_VERHAELTNIS_MIN, WISCH_VERHAELTNIS_MAX) ?: 1.0
        private set

    /**
     * Nov 12 (review: "safety first"): a start value from the app counts as ONE measurement (before: as two). It
     * comes from an earlier run – if it is no longer right (e.g. 1.0 instead of 1.2), the estimate from the row grid was
     * "certainly" one row off, and a copy was missing on EVERY page. That is why the first big swipe of every run is
     * a short measuring swipe (2 rows, then a copy of this page is still visible) – it checks the start value exactly.
     */
    private val wischGemessenStart = if (wischVerhaeltnisStart != null) 1 else 0

    /**
     * This many times the ratio has been measured (0 = never; a start value from the app counts as 1). As long as nothing
     * has been measured in this run yet ([wischGemessenStart]), the big swipe is a measuring swipe.
     */
    private var wischGemessen = wischGemessenStart

    /** For tests and display: this many times the [TippSperre] stopped an action. */
    var gesperrteTipps: Int = 0
        private set

    /** Time at which the bot stops because of the end time (null = no end time). */
    val endZeitpunkt: Instant? get() = ende

    val aktuelleRegel: Suchregel? get() = aktiveRegeln.getOrNull(regelNr)

    /**
     * Nov 9 (field test 1.9): what the bot wants to bid right now (null = nothing). The app writes it into the name
     * of the learning images ("Current Bid" should show bid − 1, the confirm window the bid).
     */
    val erwartetesGebot: Int? get() = zielVon(phase)?.preis

    fun stoppeVonHand() {
        gestoppt = StoppGrund.VON_HAND
    }

    /** The app reports whether the text input ([Aktion.Eingeben], [Aktion.PasswortEingeben]) worked. */
    fun eingabeErgebnis(ok: Boolean) {
        eingabe = ok
    }

    fun schritt(jetzt: Instant): Ergebnis {
        val e = schrittIntern(jetzt)
        sgVerplant = letzteSg?.let { budgetAusgegeben(it) }
        return e
    }

    private fun schrittIntern(jetzt: Instant): Ergebnis {
        uhr = jetzt
        gestoppt?.let { return Ergebnis.Fertig(it) }
        if (!gestartet) {
            gestartet = true
            ende = stopp.endZeitpunkt(jetzt)
            protokoll("Start. Rules: ${aktiveRegeln.joinToString("; ")}")
            if (aktiveRegeln.isEmpty()) return beenden(StoppGrund.KEINE_REGEL)
            phase = Phase.FilterSetzen(aktiveRegeln.first().kopie)
        }
        pruefeStopp(jetzt)?.let { return it }
        stoppNachBild?.let { return beenden(it) }
        if (pausiert) {
            warPausiert = true
            return Ergebnis.Pausiert
        }
        if (warPausiert) nachDerPause()

        val bild = leser.lies()

        // Nov 11 (TippSperre): is the same place already visible on the screenshot before (and for a little while)?
        ortRuhig = bild.ort == vorigerOrt && ortSeit?.let { Duration.between(it, jetzt).toMillis() >= takt.ruhigMs } == true
        if (bild.ort != vorigerOrt) {
            vorigerOrt = bild.ort
            ortSeit = jetzt
        }

        // Nov 9: "Lost connection with game server." → (back then) stop, tap nothing, screen off.
        // Nov 22 (overnight, owner's decision): now first wait, then tap Confirm and wait for the game (see [verbindungWeg]).
        if (bild.ort == Ort.VERBINDUNG_WEG) return verbindungWeg(bild, jetzt)
        verbindungWegSeit?.let {
            // The dialog went away without our Confirm (the game reconnected by itself, or a single misread screenshot):
            // treat it like a reconnect (not counted) – wait for the auction house, then filter and sort as at the start.
            protokoll("\"Lost connection\" is gone without Confirm – waiting for the auction house")
            verbindungWegSeit = null
            if (wiederverbindenSeit == null) wiederverbindenSeit = jetzt
        }
        wiederverbindenSeit?.let { seit ->
            if (bild.ort in AUKTIONSHAUS || bild.ort in AUKTIONSHAUS_FENSTER) {
                protokoll("Game is back after ${dauer(seit, jetzt)} (reconnect $neuverbindungen of $maxNeuverbindungen) – setting filter and sort again")
                wiederverbindenSeit = null
                wieBeimStart()
            } else {
                if (Duration.between(seit, jetzt).toMillis() >= takt.wiederverbindenMaxMs) {
                    protokoll("The auction house did not come back within ${takt.wiederverbindenMaxMs / 60_000} min after Confirm (${bild.ort.text}) – stopping, tapping nothing")
                    return beenden(StoppGrund.VERBINDUNG_WEG)
                }
                // Loading star, unknown screens, anything else: tap nothing, just wait.
                return Ergebnis.Warten("Reconnecting – waiting for the auction house, tapping nothing", takt.ladenPollMs)
            }
        }

        // Nov 11 (field test 1.20): since when "Bidding successful" has been visible without a break.
        if (bild.meldung?.wieErfolg == true) {
            if (erfolgSeit == null) erfolgSeit = jetzt
        } else if (bild.ort != Ort.LADEN && bild.ort != Ort.UNBEKANNT) {
            erfolgSeit = null
        }
        // Nov 11 (long run: stop "message for minutes" right after My Biddings): a screenshot without any message –
        // even in another window – means: the message from before is gone. Otherwise the time in My Biddings counted too.
        if (bild.meldung == null && bild.ort != Ort.LADEN && bild.ort != Ort.UNBEKANNT) meldungSeit = null

        // Nov 11 (review): if the list (or My Biddings) is visible, the window from before is closed – the lock
        // for the X only applies to the window in which Bid/Confirm/Verify was tapped.
        // Nov 12 (emulator run 13: second X 1.9 s after the first, at the top into the purchase bar): the lock after our
        // own X ([xTippUm]) NO longer ends when the list was visible in between – the next screenshot can be an
        // older one on which the window is still open.
        if (bild.ort == Ort.MARKT || bild.ort == Ort.MEINE_GEBOTE) {
            fensterTippUm = null
        }

        // Nov 11 (review: never tap around in the Bid window forever): normally it is closed again after a few seconds.
        // Nov 22 (rate limit): a pause because of "too frequently" does not count (the bot tapped nothing meanwhile).
        if (bild.ort == Ort.BIETEN && ortSeit?.let { Duration.between(nachPause(it), jetzt).toMillis() >= takt.bidFensterMaxMs } == true) {
            protokoll("Bid window has been open for ${takt.bidFensterMaxMs / 1000} s – stopping, tapping nothing more")
            return beenden(StoppGrund.FENSTER_KLEMMT)
        }

        // Nov 12 (emulator run 13, real app): a screenshot that still looks exactly like a screenshot on which the bot just
        // tapped is probably OLD (screenshot in transit, game has not redrawn yet). Nothing is decided on it
        // and nothing is tapped – otherwise e.g. a tap on a row hit the already open Bid window ("+").
        // Just wait. A different screenshot in between does not end this (an older one can come again afterwards). An old
        // screenshot is not a "foreign window after the X" either (emulator: wrong stop).
        alteBilder.removeAll { !jetzt.isBefore(it.second) }
        val abdruck = abdruck(bild)
        if (alteBilder.any { it.first == abdruck }) {
            altesBildGewartet++
            return Ergebnis.Warten("Screenshot still shows the state before the last tap – waiting, tapping nothing", takt.ruhigMs)
        }

        // Nov 11: right after the red X in My Biddings, My Biddings MUST come again. If a different window comes,
        // nothing more is tapped – the bot stops (a report with the screenshot is created).
        // Nov 23 (emulator runs 21 and 24): a Bid window instead (the list moved before the tap) is known – see [bidNachKreuz].
        // After its X: for [Takt.bidNachKreuzWartenMs] every Bid window screenshot without a bid in progress may be an old
        // one (screenshots in transit, an older one after a newer one) – tap nothing on it. A planned re-bid is not held up.
        aufraeumen?.let { a ->
            val zu = a.bidZuUm ?: return@let
            if (Duration.between(zu, jetzt).toMillis() >= takt.bidNachKreuzWartenMs) {
                a.bidZuUm = null
                bidNachKreuzSchliessen = false
            } else if (bild.ort == Ort.BIETEN && phase == Phase.Suchen) {
                return Ergebnis.Warten("Bid window is closing (X tapped) – tapping nothing on it", takt.ruhigMs)
            }
        }
        aufraeumen?.weg?.let { k ->
            if (bild.ort == Ort.BIETEN && phase == Phase.Suchen) {
                // null = close it: the X is tapped below in [bietenFenster] (tap lock, stale screenshots – as every X).
                bidNachKreuz(bild, k, jetzt)?.let { return it }
            } else if (bild.ort != Ort.MEINE_GEBOTE && bild.ort != Ort.LADEN) {
                return nachKreuzFremd(bild, k, jetzt)
            } else {
                k.fremdSeit = null
            }
        }

        // Nov 11 (long run): if a screenshot without the list comes after the swipe (dark, loading screen …), the bot does
        // not know whether the list kept sliding in the meantime → the position then counts as uncertain (better look back).
        if (bild.ort != Ort.MARKT) nachWischen?.let { if (!it.nachgerutscht) nachWischen = it.copy(nachgerutscht = true) }

        // Nov 11: loading screen (all dark, golden star) → just wait, tap nothing.
        if (bild.ort == Ort.LADEN) return ladebildschirm(jetzt)
        if (ladenSeit != null) {
            protokoll("Loading screen over after ${dauer(ladenSeit!!, jetzt)}")
            // Nov 22 (overnight): a long loading screen is probably a restart of the game – then set filter and sort again.
            if (Duration.between(ladenSeit, jetzt).toMillis() >= takt.neustartLadenMs && bild.ort in AUKTIONSHAUS) {
                protokoll("Long loading screen (restart of the game?) – setting filter and sort again")
                wieBeimStart()
            }
            ladenSeit = null
            ladenVorbeiUm = jetzt
        }

        if (bild.ort == Ort.UNBEKANNT) {
            // Shortly after a tap: probably a window is opening → look again right away.
            val gleich = uebergangBis?.let { jetzt.isBefore(it) } == true
            // Nov 22 (buyout): an unknown window that stays after "Buyout Price" is probably a confirmation the bot does not
            // know yet – tap nothing, keep this screenshot for the report, stop (longer than a notification lasts).
            if (phase is Phase.BuyoutGetippt && ortSeit?.let { Duration.between(it, jetzt).toMillis() >= takt.buyoutUnbekanntMs } == true) {
                buyoutUnbekannt(bild)?.let { return it }
            }
            // Nov 11: after bidding a loading screen sometimes comes – until [Takt.ladenMs] after the last
            // action just wait (tap nothing, do not count as "unknown"), after that as before.
            if (ladenBis?.let { jetzt.isBefore(it) } == true) {
                return Ergebnis.Warten(
                    if (gleich) "Screen is changing – looking again right away" else "Game is loading – waiting, tapping nothing",
                    if (gleich) takt.uebergangPollMs else takt.ladenPollMs,
                )
            }
            unbekanntInFolge++
            if (unbekanntInFolge >= takt.maxUnbekanntInFolge) return beenden(StoppGrund.NICHTS_ERKANNT)
            return Ergebnis.Unsicher(
                if (gleich) "Screen is changing – looking again right away" else "I do not recognize the auction house",
                if (gleich) takt.uebergangPollMs else takt.unbekanntMs,
            )
        }
        unbekanntInFolge = 0
        if (bild.ort != Ort.ZWEITPASSWORT) passwortFehlt = false

        // Nov 22 (rate limit): "Operated too frequently" (or an unknown message over the list) → pause, tap nothing.
        spielBremse(bild, jetzt)?.let { return it }

        val ergebnis = ortHandeln(bild, jetzt)
        if (ergebnis is Ergebnis.Handeln) {
            // Nov 11: EVERY action goes through the tap lock (never tap into the purchase bar at the top).
            TippSperre.pruefe(ergebnis.aktion, bild, ortRuhig)?.let { warum ->
                gesperrteTipps++
                protokoll("Tap locked: ${ergebnis.aktion.grund} ($warum)")
                return Ergebnis.Warten("Tap locked – looking again first", takt.gesperrtMs)
            }
            val tipp = (ergebnis.aktion as? Aktion.Tippen)?.punkt
            // Nov 23 (review: "no lasting minimum gap between reloads"): the last safety net – a tap that reloads the list
            // (refresh, filter Confirm, sort order) never comes less than [Takt.abfrageAbstandMs] after the one before. The flow
            // already waits before that ([abfrageZuFrueh]); this only catches a path that forgot it.
            if (tipp != null && LISTEN_ABFRAGE.any { bild.knopf(it) == tipp }) {
                val ab = naechsteAbfrageAb()
                if (ab != null && jetzt.isBefore(ab)) {
                    abfrageGebremst++
                    protokoll("Reload held back: ${ergebnis.aktion.grund} (at most one reload of the list every ${takt.abfrageAbstandMs / 1000} s)")
                    return Ergebnis.Warten("Reload of the list too early – waiting", minOf(Duration.between(jetzt, ab).toMillis(), takt.leerlaufBlickMs))
                }
            }
            // Nov 11 (long run: "+" tap after the secondary password): after Bid/Confirm/Verify the window may be
            // closing by itself right now, even if it was still visible on two screenshots. Then do NOT tap the X at the top.
            // Nov 11 (review): this applies for [Takt.fensterZuWartenMs] (slow network) and with EVERY message –
            // only the "-7" message shows for sure that the window stays open ("Bidding successful" means: it closes).
            // After our own X a short lock always applies: the window is closing right now.
            val obenX = tipp != null && tipp == bild.knopf(Knopf.SCHLIESSEN) && TippSperre.imObenBalken(tipp, bild)
            val nachFensterTipp = fensterTippUm?.let { Duration.between(it, jetzt).toMillis() < takt.fensterZuWartenMs } == true
            if (obenX && (gerade(xTippUm, jetzt) || (bild.meldung?.wieLimit != true && nachFensterTipp))) {
                gesperrteTipps++
                protokoll("Tap locked: ${ergebnis.aktion.grund} (window may be closing by itself)")
                return Ergebnis.Warten("Window may be closing – not tapping the X yet", takt.ruhigMs)
            }
            if (tipp != null && (FENSTER_KNOEPFE.any { bild.knopf(it) == tipp } || (bild.ort == Ort.BIETEN && tipp == bidKnopfGelernt))) {
                fensterTippUm = jetzt
            }
            if (tipp != null && tipp == bild.knopf(Knopf.SCHLIESSEN)) xTippUm = jetzt
            if (tipp != null && bild.ort == Ort.FILTER && tipp == bild.knopf(Knopf.FILTER_OK)) filterOkUm = jetzt
            // Nov 22 (rate limit): when the game last reloaded the list because of us (refresh, filter Confirm, sort order).
            if (tipp != null && LISTEN_ABFRAGE.any { bild.knopf(it) == tipp }) letzteAbfrageUm = jetzt
            val fensterKnopf = tipp != null && (FENSTER_KNOEPFE.any { bild.knopf(it) == tipp } ||
                (bild.ort == Ort.BIETEN && tipp == bidKnopfGelernt))
            // Only after taps: after a swipe the bot checks anyway whether the list stands still; pure text input
            // (price fields, secondary password) does not change the window – the next screenshot may look the same.
            val nurText = when (val a = ergebnis.aktion) {
                is Aktion.Eingeben, is Aktion.PasswortEingeben -> true
                is Aktion.Folge -> a.schritte.all { it is Aktion.Eingeben }
                else -> false
            }
            if (ergebnis.aktion !is Aktion.Wischen && !nurText) {
                alteBilder += abdruck to jetzt.plusMillis(if (fensterKnopf) takt.altesBildFensterMs else takt.altesBildMs)
            }
            uebergangBis = jetzt.plusMillis(takt.uebergangMs)
            ladenBis = jetzt.plusMillis(takt.ladenMs)
        }
        return ergebnis
    }

    /**
     * Nov 11: loading screen with the golden star – just wait. If it lasts very long, the screenshot is "uncertain"
     * (the app saves it), but still nothing is tapped.
     */
    private fun ladebildschirm(jetzt: Instant): Ergebnis {
        val seit = ladenSeit ?: jetzt.also {
            ladenSeit = it
            protokoll("Loading screen (golden star) – waiting, tapping nothing")
        }
        if (Duration.between(seit, jetzt).toMillis() >= takt.ladenUnsicherMs) {
            unbekanntInFolge++
            if (unbekanntInFolge >= takt.maxUnbekanntInFolge) return beenden(StoppGrund.NICHTS_ERKANNT)
            return Ergebnis.Unsicher("Loading screen does not go away – tapping nothing", takt.unbekanntMs)
        }
        return Ergebnis.Warten("Game is loading (golden star) – waiting, tapping nothing", takt.ladenPollMs)
    }

    /**
     * Nov 22 (rate limit, review before publishing: "the bot ignores the game's rate-limit messages"). The game shows
     * "Operated too frequently. Please retry in N second(s)." when it refuses a tap ([Meldung.wieZuOft]: recognized by its
     * band – one line, ~1415 px wide; real screenshots in testbilder/berichte). Then the bot taps NOTHING: not while the
     * message is visible, and not before the pause is over – 5 s, doubled (10, 20, 40, at most 60 s) if the message comes again
     * within [Takt.zuOftRuhigMs] after the pause before; after such a calm time it starts at 5 s again. The
     * [ZU_OFT_MAX_IN_FOLGE]th time in a row (no bid in between) → stop ([StoppGrund.ZU_OFT]). (The list is reloaded at most
     * every [Takt.abfrageAbstandMs] anyway – Nov 23, always.)
     *
     * A message over the list that the bot does not know (not "Bidding successful", not "Bid Failure" – those keep working as
     * before) → no tap for [Takt.fremdeMeldungPauseMs] ("slow down"); if one comes again soon after a pause, it counts like
     * "too frequently" (stronger pause). Nov 23 (review): the same in the Bid and confirm window ([fremdImFenster]; there
     * "-7" is known too and handled as before) – and there Bid, Buyout and Confirm are never tapped while it is visible.
     * null = no pause, carry on.
     */
    private fun spielBremse(bild: Bildschirm, jetzt: Instant): Ergebnis? {
        val m = bild.meldung
        val zuOft = m?.wieZuOft == true
        val fremd = !zuOft && m != null && ((bild.ort == Ort.MARKT && !m.bekannt) || fremdImFenster(bild))
        if (zuOft || fremd) {
            val seit = bremsMeldungSeit ?: jetzt.also { bremsMeldungSeit = it }
            // A NEW message: none was visible on the screenshot before and no pause is running (a band that fades out and
            // flickers is the same message). An unknown message that turns out to be "too frequently" counts as new.
            val neu = (seit == jetzt && zuOftPauseBis == null) || (zuOft && zuOftPauseBis != null && !pauseZuOft)
            if (neu) bremsEreignis(m!!, zuOft, jetzt, meldungOrt(bild.ort))?.let { return it }
            // Never wait silently forever: a message that does not go away for minutes → stop (as everywhere else).
            if (Duration.between(seit, jetzt).toMillis() >= takt.meldungStoppMs) {
                protokoll("The message (width ${m!!.breite}) has not gone away for ${takt.meldungStoppMs / 60_000} minutes – stopping")
                return beenden(StoppGrund.MELDUNG_HAENGT)
            }
        } else {
            bremsMeldungSeit = null
        }
        val bis = zuOftPauseBis
        val laeuft = bis != null && jetzt.isBefore(bis)
        // "Too frequently" still visible: the game is still counting down – never tap, even if the pause is over.
        if (zuOft || laeuft) {
            val ms = if (laeuft) minOf(Duration.between(jetzt, bis).toMillis(), 1000L) else takt.meldungWartenMs
            return Ergebnis.Warten(
                if (pauseZuOft) "Game says: operated too frequently – pause, tapping nothing" + (if (laeuft) " until ${uhrzeit(bis!!)}" else "")
                else "Unknown message ${meldungOrt(bild.ort)} – waiting briefly before the next tap",
                ms.coerceAtLeast(1),
            )
        }
        if (bis != null) {
            zuOftPauseBis = null
            pauseVorbeiUm = jetzt
            if (pauseZuOft) protokoll("Pause over (\"too frequently\") – carrying on, reloading the list at most every ${takt.abfrageAbstandMs / 1000} s")
        }
        // Nov 23 (review): an unknown message in the Bid or confirm window that is still there after the pause is not tapped
        // through – [bietenFenster] and [bestaetigenFenster] never tap Bid, Buyout or Confirm while it is visible.
        return null
    }

    /**
     * Nov 23 (review: "unknown messages in the Bid window are tapped through"): in the Bid or confirm window a message band the
     * bot does not know – not "Bidding successful", not "Bid Failure", not the "-7" form ([Meldung.bekanntImFenster]) and not
     * "too frequently" (that one has its own pause). Then the bot pauses as for an unknown message over the list
     * ([spielBremse], same backoff) and never taps Bid, Buyout or Confirm while it is visible.
     */
    private fun fremdImFenster(bild: Bildschirm): Boolean {
        val m = bild.meldung ?: return false
        return (bild.ort == Ort.BIETEN || bild.ort == Ort.BESTAETIGEN) && !m.wieZuOft && !m.bekanntImFenster
    }

    /** Nov 23: where an unknown message lies, for the log ("over the list", "over the Bid window", …). */
    private fun meldungOrt(ort: Ort): String = when (ort) {
        Ort.BIETEN -> "over the Bid window"
        Ort.BESTAETIGEN -> "over the confirm window"
        else -> "over the list"
    }

    /** Nov 22: a new message that holds the bot back (see [spielBremse]). null = pause set, otherwise stop. */
    private fun bremsEreignis(m: Meldung, zuOft: Boolean, jetzt: Instant, wo: String = "over the list"): Ergebnis? {
        // Soon after the last pause? Otherwise it has been calm long enough: pauses and count start over.
        val bald = pauseVorbeiUm?.let { Duration.between(it, jetzt).toMillis() < takt.zuOftRuhigMs } == true
        if (!bald) {
            zuOftStufe = 0
            zuOftInFolge = 0
        }
        bremsEreignisUm = jetzt
        if (!zuOft && !bald) {
            zuOftPauseBis = jetzt.plusMillis(takt.fremdeMeldungPauseMs)
            pauseZuOft = false
            protokoll("Unknown message $wo (width ${m.breite}) – not tapping through it, waiting ${takt.fremdeMeldungPauseMs / 1000} s")
            return null
        }
        zuOftStufe++
        zuOftInFolge++
        zuOftMeldungen++
        if (zuOftInFolge >= ZU_OFT_MAX_IN_FOLGE) {
            protokoll("Game says: operated too frequently – $zuOftInFolge times in a row despite pauses, stopping")
            return beenden(StoppGrund.ZU_OFT)
        }
        val ms = minOf(takt.zuOftPauseMs shl (zuOftStufe - 1).coerceAtMost(10), takt.zuOftMaxMs)
        val bis = jetzt.plusMillis(ms)
        zuOftPauseBis = zuOftPauseBis?.takeIf { it.isAfter(bis) } ?: bis
        pauseZuOft = true
        protokoll(
            (if (zuOft) "Game says: operated too frequently" else "Unknown message $wo again (width ${m.breite}) – treating it as \"too frequently\"") +
                " – waiting ${ms / 1000} s ($zuOftInFolge of $ZU_OFT_MAX_IN_FOLGE in a row)",
        )
        return null
    }

    /** Nov 22 (rate limit): did the game hold the bot back with a message after [seit] (e.g. after the tap on a row or on Bid)? */
    private fun gebremstNach(seit: Instant): Boolean = bremsEreignisUm?.let { !it.isBefore(seit) } == true

    /**
     * Nov 22 (rate limit): the last reload of the list ([LISTEN_ABFRAGE]) must be at least [abstandMs] ago.
     * Nov 23 (review: "no lasting minimum gap"): ALWAYS at least [Takt.abfrageAbstandMs] (before: only for 10 minutes after
     * "too frequently"). null = reload now; otherwise wait and look again after at most [blickMs] (the visible rows are still
     * bid on meanwhile – that is no reload).
     */
    private fun abfrageZuFrueh(jetzt: Instant, abstandMs: Long, grund: String, blickMs: Long = takt.leerlaufBlickMs): Ergebnis? {
        val ab = naechsteAbfrageAb(abstandMs) ?: return null
        if (!jetzt.isBefore(ab)) return null
        return Ergebnis.Warten("$grund – next reload of the list at ${uhrzeit(ab)}", minOf(Duration.between(jetzt, ab).toMillis(), blickMs).coerceAtLeast(1))
    }

    /** Nov 23: from when the next reload of the list is allowed (null = right away – there was none yet). */
    private fun naechsteAbfrageAb(abstandMs: Long = 0): Instant? =
        letzteAbfrageUm?.plusMillis(maxOf(abstandMs, takt.abfrageAbstandMs))

    /**
     * Nov 23 (review round 3: "navigation taps also reload lists"): a tap on a menu entry or a tab loads a list from the
     * game's server like the refresh button – so it waits for the same gap ([Takt.abfrageAbstandMs] after the last reload).
     * Nothing is tapped meanwhile; the bot looks again every [Takt.abfrageBlickMs]. null = tap now.
     */
    private fun navigationZuFrueh(jetzt: Instant, wohin: String): Ergebnis? =
        abfrageZuFrueh(jetzt, takt.abfrageAbstandMs, "$wohin: waiting for the gap between two reloads of a list", takt.abfrageBlickMs)

    /**
     * Nov 22 (review: "the bot refreshes the list every ~1.2 s all night"): at the end of a round – if it placed no bid and no
     * copy that fits a rule ends within [BALD_ENDE_SEK] (see [baldEndeMerken]) – the next refresh comes at the earliest
     * [Takt.leerlaufMinMs] after the last reload, growing ×1.5 with every such round up to [Takt.leerlaufMaxMs]. While it waits,
     * the bot keeps looking at the list (a copy may move into the rule's time window – then it bids at once).
     * With a bid or a copy ending soon it refreshes as fast as allowed: Nov 23 (review) at most every [Takt.abfrageAbstandMs]
     * after the last reload – meanwhile it looks at the visible rows every [Takt.abfrageBlickMs]. null = refresh now.
     */
    private fun aktualisierenWarten(jetzt: Instant): Ergebnis? {
        val leer = !gebotenInDurchlauf && baldEndeBis?.isAfter(jetzt) != true
        leerlaufMs = if (!leer) 0 else if (leerlaufMs == 0L) takt.leerlaufMinMs else leerlaufMs
        val warten = abfrageZuFrueh(
            jetzt, leerlaufMs,
            if (leer) "Nothing to bid right now" else "At most one reload of the list every ${takt.abfrageAbstandMs / 1000} s",
            if (leer) takt.leerlaufBlickMs else takt.abfrageBlickMs,
        ) ?: return null
        if (!rundeWartet && leer) leerlaufGewartet++
        rundeWartet = true
        return warten
    }

    /** Nov 22: the round is over, the bot waits for the next refresh (do not swipe on meanwhile, see [marktWeiter]). */
    private var rundeWartet = false

    /**
     * Nov 22 (review: refresh rate): a copy in the list fits [regel] (price, the rule's time window) and ends within
     * [BALD_ENDE_SEK] – also if our bid is in front there (it can be outbid any moment). Until it ends, the bot refreshes
     * fast (as before).
     */
    private fun baldEndeMerken(zeilen: List<Zeile>, regel: Suchregel, jetzt: Instant) {
        for (z in zeilen) {
            val rest = z.restzeitSek ?: continue
            if (!z.hatRestzeit || rest <= 0 || rest > BALD_ENDE_SEK || !regel.zeitPasst(rest)) continue
            val preis = z.preis ?: if (preisDurchFilter(regel)) regel.maxPreis else continue
            if (!regel.erlaubt(preis.toDouble())) continue
            val ende = jetzt.plusSeconds(rest.toLong())
            if (baldEndeBis?.isAfter(ende) != true) baldEndeBis = ende
        }
    }

    /**
     * Nov 22 (overnight, owner's decision): "Lost connection with game server." Wait [Takt.verbindungWartenMs], then tap
     * the yellow Confirm button – only if it is recognized on THIS screenshot and the dialog is steady. Then wait for the
     * auction house (see [schritt]). At most [maxNeuverbindungen] times per run; 0 = stop at once (as before).
     */
    private fun verbindungWeg(bild: Bildschirm, jetzt: Instant): Ergebnis {
        if (maxNeuverbindungen <= 0) return beenden(StoppGrund.VERBINDUNG_WEG)
        if (neuverbindungen >= maxNeuverbindungen) {
            protokoll("Lost connection again – already $neuverbindungen reconnects in this run, stopping")
            return beenden(StoppGrund.VERBINDUNG_WEG)
        }
        val seit = verbindungWegSeit ?: jetzt.also {
            verbindungWegSeit = it
            protokoll("Lost connection to the game server – waiting ${takt.verbindungWartenMs / 1000} s, then Confirm (reconnect ${neuverbindungen + 1} of $maxNeuverbindungen)")
        }
        val ms = Duration.between(seit, jetzt).toMillis()
        if (ms < takt.verbindungWartenMs) {
            return Ergebnis.Warten("Lost connection – waiting before tapping Confirm", takt.ladenPollMs)
        }
        val confirm = bild.knopf(Knopf.BESTAETIGEN)
        if (confirm == null || !ortRuhig) {
            if (ms >= takt.verbindungWartenMs + takt.verbindungConfirmMaxMs) {
                protokoll("Lost connection – the Confirm button was not recognized, stopping")
                return beenden(StoppGrund.VERBINDUNG_WEG)
            }
            return Ergebnis.Warten("Lost connection – Confirm button not recognized yet", takt.ruhigMs)
        }
        val tipp = Aktion.Tippen(confirm, "Confirm (reconnect ${neuverbindungen + 1} of $maxNeuverbindungen)")
        // Like every tap: through the tap lock (never into the purchase bar at the top).
        TippSperre.pruefe(tipp, bild, ortRuhig)?.let { warum ->
            gesperrteTipps++
            protokoll("Tap locked: ${tipp.grund} ($warum)")
            return Ergebnis.Warten("Tap locked – looking again first", takt.gesperrtMs)
        }
        neuverbindungen++
        verbindungWegSeit = null
        wiederverbindenSeit = jetzt
        protokoll("Lost connection – tapping Confirm (reconnect $neuverbindungen of $maxNeuverbindungen)")
        return Ergebnis.Handeln(tipp, takt.nachTippenMs)
    }

    /** Nov 22 (reconnect, restart): as at the start – set filter and sort order again, forget windows and lists in progress. */
    private fun wieBeimStart() {
        val regel = aktuelleRegel ?: aktiveRegeln.firstOrNull() ?: return
        when (val p = phase) {
            is Phase.BuyoutGetippt -> protokoll("Buyout ${p.ziel.id}: unknown whether it went through (connection/game was gone)")
            is Phase.BietenGetippt, is Phase.Bestaetigt, is Phase.Passwort ->
                protokoll("A bid was in progress – the list shows later whether it counts")
            else -> Unit
        }
        buyoutPruefung?.let { protokoll("Buyout ${it.ziel.id}: unknown whether it went through (connection/game was gone)") }
        buyoutPruefung = null
        phase = Phase.FilterSetzen(regel.kopie)
        filterStufe = null
        filterMaxGesetzt = null
        // Nov 23: after a restart or reconnect the game's filter may hold anything (reset or as before).
        filterPreiseBestaetigt = null
        filterPreiseUnsicher = true
        falscheListeSeit = null
        // My Biddings: like after a pause – what was seen there before no longer counts.
        aufraeumen?.let {
            it.vorige = null
            it.weg = null
            it.wischen = null
            it.nachbieten = null
        }
        nachWischen = null
        vorWischen = null
        sortierVersuche = 0
        fensterTippUm = null
        alteBilder.clear()
        gemerktVergessen()
        rundeWartet = false
    }

    /**
     * Nov 11: after the red X in My Biddings something else is visible (a window the bot does not know).
     * Wait one screenshot (maybe just a transition) – if it is still there: tap NOTHING and stop.
     */
    private fun nachKreuzFremd(bild: Bildschirm, k: KreuzTipp, jetzt: Instant): Ergebnis {
        val seit = k.fremdSeit ?: jetzt.also { k.fremdSeit = it }
        // Nov 11 (long run: two dark screenshots in a row ended the run): wait until [Takt.fremdNachKreuzMs]
        // – nothing is tapped meanwhile. If My Biddings comes back, it goes on.
        if (Duration.between(seit, jetzt).toMillis() < takt.fremdNachKreuzMs) {
            return Ergebnis.Warten("A different screen after the X – looking again, tapping nothing", takt.ruhigMs)
        }
        protokoll("After the red X in My Biddings an unknown window appeared (${bild.ort.text}, ${k.id ?: "?"}) – tapping nothing more")
        return beenden(StoppGrund.UNBEKANNT_NACH_X)
    }

    /**
     * Nov 23 (emulator runs 21 and 24, fake game): between the screenshot and the tap on the red X the list in My Biddings
     * moved (another row was outbid and the list re-sorted, or an auction above ended). The tap landed on a row that is
     * NOT outbid, and the game opened its Bid window. That window is known: the bot never bids in it. It waits for a
     * steady window without a message, then lets [bietenFenster] close it with its red X (in phase [Phase.Suchen]: the
     * same way as every Bid window without a bid – tap lock, stale screenshots), and looks at My Biddings again
     * ([Aufraeumen.bidZuUm]: for [Takt.bidNachKreuzWartenMs] afterwards nothing is tapped on a Bid window without a bid –
     * it may be an old screenshot). Before, the bot stopped here (and locked the screen) after a minute of bidding.
     * Only while no bid is in progress ([Phase.Suchen]), at most [MAX_BID_NACH_KREUZ] times per clean-up; if the window
     * does not settle within [Takt.bidNachKreuzWartenMs], or it happens again, the bot stops as before.
     * Result: null = close it now (continue with [bietenFenster]), otherwise wait or stop.
     */
    private fun bidNachKreuz(bild: Bildschirm, k: KreuzTipp, jetzt: Instant): Ergebnis? {
        val a = aufraeumen ?: return nachKreuzFremd(bild, k, jetzt)
        val seit = k.fremdSeit ?: jetzt.also { k.fremdSeit = it }
        if (a.bidNachKreuz >= MAX_BID_NACH_KREUZ) {
            protokoll("After the red X in My Biddings a Bid window opened again (${k.id ?: "?"}) – tapping nothing more")
            return beenden(StoppGrund.UNBEKANNT_NACH_X)
        }
        if (!ortRuhig || bild.meldung != null || bild.knopf(Knopf.SCHLIESSEN) == null) {
            if (Duration.between(seit, jetzt).toMillis() < takt.bidNachKreuzWartenMs) {
                return Ergebnis.Warten("Bid window after the X – letting it settle, tapping nothing", takt.ruhigMs)
            }
            protokoll("After the red X in My Biddings a Bid window stayed unclear (${k.id ?: "?"}) – tapping nothing more")
            return beenden(StoppGrund.UNBEKANNT_NACH_X)
        }
        a.bidNachKreuz++
        a.weg = null
        a.vorige = null
        a.bidZuUm = jetzt
        bidNachKreuzSchliessen = true
        protokoll("My Biddings: the list moved before the red X (${k.id ?: "?"}) – a Bid window opened instead; closing it, no bid")
        return null
    }

    /** Nov 23: the next "close Bid window" closes the window the red X opened by mistake (only for the log text). */
    private var bidNachKreuzSchliessen = false

    private fun ortHandeln(bild: Bildschirm, jetzt: Instant): Ergebnis {
        if (bild.ort != Ort.MARKT) nachWischen = null
        if (bild.ort != Ort.MEINE_GEBOTE) aufraeumen?.vorige = null
        if (bild.ort != Ort.BESTAETIGEN) bestaetigenXInFolge = 0
        if (bild.ort != Ort.SORTIEREN) sortFensterSeit = null
        return when (bild.ort) {
            Ort.MARKT -> markt(bild, jetzt)
            Ort.BIETEN -> bietenFenster(bild, jetzt)
            Ort.BESTAETIGEN -> bestaetigenFenster(bild, jetzt)
            Ort.ZWEITPASSWORT -> zweitpasswort(bild, jetzt)
            Ort.MEINE_GEBOTE -> meineGebote(bild, jetzt)
            Ort.FILTER -> filterFenster(bild)
            Ort.SORTIEREN -> sortierFenster(bild, jetzt)
            Ort.MARKT_ANZEIGE -> navigationZuFrueh(jetzt, "Tab Items for Bidding")
                ?: tippe(bild, Knopf.REITER_BIETEN, "tab Items for Bidding", takt.nachTippenMs)
            Ort.AUKTIONSHAUS_ANDERS -> navigationZuFrueh(jetzt, "To the Auction Market")
                ?: tippe(bild, Knopf.MENUE_AUKTIONSMARKT, "to the Auction Market", takt.nachTippenMs)
            Ort.UNBEKANNT -> Ergebnis.Unsicher("unknown", takt.unbekanntMs)
            Ort.LADEN -> Ergebnis.Warten("Game is loading – waiting, tapping nothing", takt.ladenPollMs)
            Ort.VERBINDUNG_WEG -> beenden(StoppGrund.VERBINDUNG_WEG)
        }
    }

    /**
     * Sort window: choose "Awakened Tier ↑". Nov 9 (field test 1.9: 4 taps, 3 s): first wait briefly until
     * the window is fully open, then tap a bit longer – checked on the next screenshot.
     */
    private fun sortierFenster(bild: Bildschirm, jetzt: Instant): Ergebnis {
        phase = Phase.Suchen
        if (sortStart == null) sortStart = jetzt
        val seit = sortFensterSeit ?: jetzt.also { sortFensterSeit = it }
        val offen = Duration.between(seit, jetzt).toMillis()
        if (offen < takt.sortAufklappenMs) return Ergebnis.Warten("sort window is opening", takt.sortAufklappenMs - offen)
        // Nov 22 (rate limit, real reports 20-42-45 and 21-46-15): "Awakened Tier ↑" about 2 s after the filter's Confirm →
        // "Operated too frequently. Please retry in 3 second(s)." So the sort order waits until [Takt.abfrageAbstandMs] after
        // the last reload of the list (the window stays open meanwhile, nothing is tapped).
        abfrageZuFrueh(jetzt, takt.abfrageAbstandMs, "Sort window open, waiting a moment")?.let { return it }
        return tippe(
            bild, Knopf.SORT_RANG_AUF, "sort by Awakened Tier ↑ (ending soon at the top)", takt.nachSortierenMs, LANGER_TIPP_MS,
        )
    }

    // ---------------- Bidding list ----------------

    private fun markt(bild: Bildschirm, jetzt: Instant): Ergebnis {
        if (bild.meldung == null) meldungSeit = null else if (meldungSeit == null) meldungSeit = jetzt
        val regel = aktuelleRegel ?: return beenden(StoppGrund.KEINE_REGEL)
        bild.sg?.let { sgGelesen(it, jetzt) }
        buyoutPruefen(bild, jetzt)

        // 1. What a change of window means (without tapping) – this also applies when a message lies over the list.
        zurueckInDerListe(jetzt)?.let { return it }

        // Nov 22 (buyout): until it is clear whether the buyout went through, no new bid – otherwise our own bids would
        // change the SG balance in the meantime (at most [Takt.buyoutPruefenMaxMs]).
        if (buyoutPruefung != null) return Ergebnis.Warten("Buyout – checking whether the SG balance dropped", takt.ruhigMs)

        // 2. Nov 9 (field test 1.10/1.11): if a message lies over the list ("Bidding successful …" or another
        // one), NOTHING is tapped – wait until the screenshot is clear again. The message does confirm the bid, though.
        bild.meldung?.let { m ->
            if (!durchMeldungTippen) return meldungAbwarten(bild, m, jetzt)
            // Nov 11 (field test 1.20): the bot keeps bidding – but a message that stays for minutes is not normal.
            if (meldungSeit?.let { Duration.between(it, jetzt).toMillis() >= takt.meldungStoppMs } == true) {
                protokoll("A message has been lying over the list for ${takt.meldungStoppMs / 60_000} minutes – stopping")
                return beenden(StoppGrund.MELDUNG_HAENGT)
            }
        }

        // 3. What should be tapped next.
        when (val p = phase) {
            is Phase.FilterSetzen -> {
                // Nov 23 (review): the filter's Confirm reloads the list – open the filter only when that reload is allowed.
                filterNochNicht(jetzt)?.let { return it }
                filterSitzung(p.stufe, jetzt)
                phase = p.copy(oeffnen = p.oeffnen + 1, taps = 0, schritt = FilterSchritt.RANG)
                if (p.oeffnen >= 4) return beenden(StoppGrund.FILTER_GEHT_NICHT)
                return tippe(bild, Knopf.FILTER, "open filter for ${p.stufe}", takt.nachTippenMs)
            }
            is Phase.FilterPruefen -> return filterPruefen(bild, p)
            Phase.Sortieren -> return tippe(bild, Knopf.SORTIEREN, "open sort order", takt.sortOeffnenMs)
            else -> Unit
        }
        return marktWeiter(bild, regel, jetzt)
    }

    /**
     * What a change of window means (without tapping): the game is back from a window in a list – in the
     * bidding list or (Nov 11, bidding again while cleaning up) in My Biddings. null = continue the flow.
     */
    private fun zurueckInDerListe(jetzt: Instant): Ergebnis? {
        when (val p = phase) {
            // Nov 12 (review): the bot wanted to close the Bid window (without a bid), but it closed by itself
            // (e.g. auction over, see [bietenFenster]) – fine, tap nothing more.
            is Phase.BietenOffen -> if (p.schliessen != null) {
                protokoll("Bid window closed by itself (${p.ziel.id}) – no bid (${p.schliessen.warum})")
                if (p.schliessen.sperren) sperren(p.ziel.id, jetzt, p.schliessen.warum)
                if (p.ziel.ausMeineGebote) aufraeumen?.nichtNachbieten?.putIfAbsent(p.ziel.id, p.schliessen.warum)
                phase = Phase.Suchen
            } else if (Duration.between(p.seit, jetzt).toMillis() < takt.fensterWartenMs) {
                return Ergebnis.Warten("waiting for the Bid window", 300)
            } else {
                // Nov 22 (rate limit): if the game held the bot back after the tap ("too frequently"), that was the reason.
                val gebremst = gebremstNach(p.seit)
                protokoll("Bid window did not open (${p.ziel.id}) – moving on" + if (gebremst) " (the game said \"too frequently\")" else "")
                sperren(p.ziel.id, jetzt, "Bid window did not open")
                if (p.ziel.durchMeldung && !durchMeldungGehtNicht && !gebremst) {
                    durchMeldungGehtNicht = true
                    protokoll("Tapping through the \"Bidding successful\" message does not work – from now on the bot waits again until it is gone")
                }
                phase = Phase.Suchen
            }
            // Nov 9: after "Bid" the game usually goes straight back to the list (no confirm window).
            // Then the bid counts as placed – afterwards the row is checked for the clock.
            is Phase.BietenGetippt -> {
                gebotRegistrieren(p.ziel, p.ziel.preis, jetzt, " – without confirm window")
                phase = Phase.Suchen
            }
            // Nov 22 (buyout): the window closed after "Buyout Price" – whether it went through shows in the SG balance.
            is Phase.BuyoutGetippt -> {
                val preis = p.ziel.buyout ?: 0
                // Like a bid: subtract it right away (the display shows it ~2 s later) – unless it already shows it.
                letzteSg = letzteSg?.let { if (p.sgVorher != null && it <= p.sgVorher - preis) it else it - preis }
                letztesGebotUm = jetzt
                buyoutPruefung = BuyoutPruefung(p.ziel, p.sgVorher, jetzt)
                phase = Phase.Suchen
            }
            // After "Verify" straight to the list: password accepted, the bid was placed with it.
            is Phase.Passwort -> {
                if (p.ziel != null && p.geprueftUm != null) {
                    passwortGeklappt()
                    gebotRegistrieren(p.ziel, p.ziel.preis, jetzt, " – after the secondary password")
                }
                phase = Phase.Suchen
            }
            is Phase.Bestaetigt -> phase = Phase.Suchen
            else -> Unit
        }
        return null
    }

    /**
     * Nov 22 (buyout): after the Bid window closed – did the SG balance drop by the buyout price? Only with an SG balance
     * read the same twice, a few seconds after the tap (the display is ~2 s late). Bought → counts like a won copy.
     */
    private fun buyoutPruefen(bild: Bildschirm, jetzt: Instant) {
        val bp = buyoutPruefung ?: return
        val preis = bp.ziel.buyout ?: return run { buyoutPruefung = null }
        val alt = Duration.between(bp.um, jetzt).toMillis()
        val sg = bild.sg
        if (sg != null && sg == sgZweimal && bp.sgVorher != null && alt >= SG_ANZEIGE_NACH_GEBOT_MS) {
            val weg = bp.sgVorher - sg
            // Long run: one of our bids in front can be outbid in the meantime – then exactly its amount comes back.
            // That is taken into account (one or two refunds), but only if it is unambiguous.
            val rueck = rueckzahlungMoeglich()
            val gekauftMitRueck = weg != preis && rueck(preis - weg)
            val nichtMitRueck = weg != 0 && rueck(-weg)
            when {
                weg == preis || (gekauftMitRueck && !nichtMitRueck) -> {
                    gebote.sofortGekauft(preis)
                    sofortGekauft++
                    gebotenInDurchlauf = true
                    protokoll(
                        "Bought out: ${bp.ziel.regel.kopie} for $preis SG (${bp.ziel.id})" +
                            if (weg != preis) " – ${preis - weg} SG came back for an outbid bid meanwhile" else "",
                    )
                }
                weg == 0 || (nichtMitRueck && !gekauftMitRueck) ->
                    protokoll("Buyout did not go through (${bp.ziel.id}) – SG balance unchanged" + if (weg != 0) " (apart from a refund)" else "")
                else -> protokoll("Buyout ${bp.ziel.id}: SG balance changed by $weg instead of $preis – not counted (unclear)")
            }
            buyoutPruefung = null
        } else if (alt >= takt.buyoutPruefenMaxMs) {
            protokoll("Buyout ${bp.ziel.id}: no steady SG balance after the buyout – not counted (unclear)")
            buyoutPruefung = null
        }
    }

    /** Nov 22 (buyout): can [betrag] SG be the refund of one or two of our bids in front? */
    private fun rueckzahlungMoeglich(): (Int) -> Boolean {
        val preise = gebote.vornePreise()
        return { betrag ->
            betrag > 0 && (betrag in preise || preise.indices.any { i -> (i + 1 until preise.size).any { j -> preise[i] + preise[j] == betrag } })
        }
    }

    /** Bidding list without an open window: align, check filter/sort order, 50 limit, then bid or move on. */
    private fun marktWeiter(bild: Bildschirm, regel: Suchregel, jetzt: Instant): Ergebnis {
        // Nov 11 (scrolling): after the swipe first wait until the list stands still, and align it if
        // needed – tap nothing before that (earlier the bot bid on a screenshot while the list was still sliding).
        nachWischen?.let { w -> nachWischenPruefen(bild, w, jetzt)?.let { return it } }

        // Nov 11: shortly after a tap (refresh, filter, sort) the list is sometimes still empty –
        // that is not the end of the list, it is still loading.
        if (bild.zeilen.isEmpty() && uebergangBis?.let { jetzt.isBefore(it) } == true) {
            return Ergebnis.Warten("List is loading", takt.uebergangPollMs)
        }

        // Does the list suddenly show a different rank (badge color)? Then set the filter again.
        val buchstabe = regel.kopie.first()
        if (filterStufe != null && buchstabe in "CBA" && bild.zeilen.any { it.stufe != null && it.stufe != buchstabe }) {
            protokoll("List no longer shows ${regel.kopie} – setting the filter again")
            filterStufe = null
        }
        // Nov 23 (coordinator): price fields given up – a list filtered by other prices, or the regular new try, sets the
        // filter again (with a new try of the price fields, always with the 5 s gap between reloads).
        preisfelderPruefen(bild, regel, jetzt)
        if (filterStufe != regel.kopie) return filterOeffnen(bild, regel.kopie)

        // Nov 9: sort order "Awakened Tier ↑" (ending soon at the top).
        if (bild.sortierung == Sortierung.RANG) {
            sortierVersuche = 0
            sortStart?.let { protokoll("⏱ Sorted by Awakened Tier ↑ in ${dauer(it, jetzt)}") }
            sortStart = null
        }
        if (bild.sortierung == Sortierung.PREIS && sortierVersuche < 2) {
            sortierVersuche++
            phase = Phase.Sortieren
            if (sortStart == null) sortStart = jetzt
            return tippe(bild, Knopf.SORTIEREN, "change sort order to Awakened Tier ↑", takt.sortOeffnenMs)
        }

        // Nov 9 (field test 1.9): only free (really read) rows count for "seen" and for checking
        // the last bid – remembered rows under the message show an old icon.
        val frei = bild.zeilen.filter { !it.verdeckt }
        val zeilen = zeilenMitGemerktem(bild, jetzt)
        frei.forEach { gebote.gesehen(it, jetzt) }
        bild.meldung?.let { erfolgsMeldung(it, jetzt) }
        pruefeLetztesGebot(frei, jetzt)
        // Nov 22 (review): our own bid shows a price above the rule's highest bid → stop at once, tap nothing more.
        stoppNachBild?.let { return beenden(it) }
        // Nov 22 (refresh rate): copies that fit the rule and end soon keep the refresh fast.
        baldEndeMerken(zeilen, regel, jetzt)

        // Nov 9 (field test 1.10/1.11): 50 bids ("-7") – only then look into My Biddings to see when the first
        // bid ends, and do not bid until then.
        limitBehandeln(bild, jetzt)?.let { return it }

        // Nov 9: outbid all the time → other rank.
        if (aktiveRegeln.size > 1 && gebote.ueberbotenInFolge >= MAX_UEBERBOTEN_IN_FOLGE) {
            protokoll("Outbid ${gebote.ueberbotenInFolge} times in a row on ${regel.kopie} – other rank")
            return naechsteRegel(bild)
        }

        // Nov 9: never bid more than the SG available – and not at all without a readable SG balance.
        // Nov 9: the SG balance read last is used – a single unreadable screenshot blocks nothing.
        // Only if it was NEVER readable, the bot does not bid (and that is stated clearly in the log and at the IH button).
        // Nov 11 (review): higher balances only count once they were read the same twice (see [sgGelesen]).
        val sg = letzteSg
        sgUnlesbar = sg == null
        if (sg == null) {
            if (!sgUnlesbarGemeldet) protokoll("SG balance at the top right not readable – the bot only bids once it can read it")
            sgUnlesbarGemeldet = true
            // Nov 11 (review): do not wait forever.
            val seit = sgUnlesbarSeit ?: jetzt.also { sgUnlesbarSeit = it }
            if (Duration.between(seit, jetzt).toMillis() >= takt.sgUnlesbarStoppMs) return beenden(StoppGrund.SG_UNLESBAR)
            return Ergebnis.Warten("SG balance not readable – no bid", takt.unbekanntMs)
        }
        sgUnlesbarSeit = null
        if (sgUnlesbarGemeldet) protokoll("SG balance readable again: $sg")
        sgUnlesbarGemeldet = false
        if (startSg == null) {
            startSg = sg
            stopp.sgBudget?.let { protokoll("SG at start: $sg – spending at most $it SG") }
        }
        startAnheben(jetzt)
        // Nov 11 (review): stop because of SG (too little, budget) only with an SG balance that was read the same twice
        // – a single screenshot misread (too low) holds up bidding, but ends nothing.
        val sgSicher = sg == sgZweimal
        val billigstes = aktiveRegeln.minOf { it.minPreis + GEBOTS_SCHRITT }
        val unterMindest = sg < billigstes
        // Nov 12 (field test 1.31): also show when the SG are not enough for any copy in the list (see [geldPruefen]).
        zuWenigSg = unterMindest || (geldFehltFuer != null && !geldFehltDurchBudget)
        if (unterMindest) {
            // Nov 11 (long run: waited 5 hours with 1 SG): SG only come back when one of our own bids gets
            // outbid. If none is in front any more, the bot does not wait forever (screen on, battery), but stops.
            if (gebote.vorneOffen() > 0 || !sgSicher) {
                sgAlleSeit = null
            } else {
                val seit = sgAlleSeit ?: jetzt.also { sgAlleSeit = it }
                if (Duration.between(seit, jetzt).toMillis() >= takt.sgAlleStoppMs) return beenden(StoppGrund.SG_ALLE)
            }
            return Ergebnis.Warten("Too little SG ($sg) – the bot is waiting", takt.sgFehltMs)
        }
        sgAlleSeit = null

        // Nov 9: SG budget. Planned = start SG − current SG (running bids count too,
        // outbid ones the game gives back). If the rest is not enough for any bid: wait as long as
        // bids are running (refunds make room again); if none are running any more → done.
        val rest = budgetRest(sg)
        val budgetUnterMindest = rest != null && rest < billigstes
        budgetVerplant = budgetUnterMindest || (geldFehltFuer != null && geldFehltDurchBudget)
        if (budgetUnterMindest) {
            // Nov 9: without My Biddings – our own bids show whether any are still running (refunds possible).
            if (gebote.offeneGebote() > 0 || !sgSicher) return Ergebnis.Warten("Budget planned – waiting for running bids", takt.limitVollMs)
            return beenden(StoppGrund.BUDGET_ERREICHT)
        }

        // Nov 11 (long run: goal 15, bought 21 at the end): bids where I am in front usually become
        // purchases. If bought + leading copies already reach the goal, no new bid – wait until they are
        // decided (if one gets outbid, it goes on; if it is enough, the bot stops).
        val ziel = stopp.zielKopien?.takeIf { it > 0 }
        zielVerplant = ziel != null && gebote.gekaufteKopien + gebote.vorneOffen() >= ziel
        if (zielVerplant) {
            return Ergebnis.Warten("Goal planned (${gebote.gekaufteKopien} bought, ${gebote.vorneOffen()} in front) – waiting for running bids", takt.limitVollMs)
        }

        // Nov 12 (field test 1.33: 18 SG free, every copy cost 50–58 – the bot still kept paging for minutes,
        // because it only noticed at the end of a whole round): remember the prices of the copies of the last minutes. If the
        // free money is not enough for any of them, wait right away (it only holds up bidding – if SG come back, it goes on).
        for (z in frei) {
            val id = z.id ?: continue
            val p = z.preis ?: continue
            if (z.zeichen == Zeichen.UHR || !z.hatRestzeit) continue // mine (in front) or already over
            // Nov 22: with a wider filter (buyout) the list also shows copies above the max price – the bot does not bid on them.
            if (p > regel.maxPreis) continue
            preiseGesehen[id] = (p + GEBOTS_SCHRITT) to jetzt
        }
        preiseGesehen.values.removeIf { Duration.between(it.second, jetzt).toMillis() > takt.preiseMerkenMs }
        // Only with a reliable SG balance (read the same twice, no bid in transit right now) – otherwise a balance shortly
        // after the bid looks too low.
        val gebotGerade = letztesGebotUm?.let { Duration.between(it, jetzt).toMillis() < SG_ANZEIGE_NACH_GEBOT_MS } == true
        if (geldFehltFuer == null && sgSicher && !gebotGerade) {
            val billigst = preiseGesehen.values.minOfOrNull { it.first }
            if (billigst != null && minOf(sg, rest ?: Int.MAX_VALUE) < billigst) geldFehlt(billigst, sg, rest, jetzt)
        }

        // Nov 12 (field test 1.31): the money is not enough for any copy (see [geldPruefen]) – are SG free again? Can
        // anything come back at all? Otherwise do not wait forever.
        geldFehltFuer?.let { grenze ->
            if (gebote.vorneOffen() > 0) geldVorneLeerSeit = null
            val frei = minOf(sg, rest ?: Int.MAX_VALUE)
            val grund = if (geldFehltDurchBudget) StoppGrund.BUDGET_ERREICHT else StoppGrund.SG_ALLE
            if (frei >= grenze) {
                protokoll("SG back: $frei SG free (the cheapest copy needs $grenze) – bidding on")
                geldWiederDa()
            } else if (sgSicher && gebote.vorneOffen() == 0 &&
                Duration.between(geldVorneLeerSeit ?: jetzt.also { geldVorneLeerSeit = it }, jetzt).toMillis() >= takt.geldVorneLeerMs
            ) {
                protokoll("None of our own bids in front for ${takt.geldVorneLeerMs / 1000} s – no more SG will come back, stopping")
                return beenden(grund)
            } else if (geldFehltSeit?.let { Duration.between(it, jetzt).toMillis() >= takt.geldWartenMaxMs } == true) {
                protokoll("The money has not been enough for any copy for ${takt.geldWartenMaxMs / 60_000} minutes – stopping")
                return beenden(grund)
            }
        }

        // Nov 11 (review): if not a single remaining time in the list is readable for minutes, the bot cannot do anything
        // (it does not bid without a remaining time) – then stop with a reason instead of paging on forever.
        val mitZeit = frei.filter { it.hatRestzeit }
        if (mitZeit.any { it.restzeitSek != null }) {
            restzeitUnlesbarSeit = null
        } else if (mitZeit.isNotEmpty()) {
            val seit = restzeitUnlesbarSeit ?: jetzt.also { restzeitUnlesbarSeit = it }
            if (Duration.between(seit, jetzt).toMillis() >= takt.restzeitUnlesbarStoppMs) {
                protokoll("No remaining time readable for ${takt.restzeitUnlesbarStoppMs / 60_000} minutes – stopping")
                return beenden(StoppGrund.RESTZEIT_UNLESBAR)
            }
        }

        gesperrtBis.values.removeIf { !it.isAfter(jetzt) }
        // Nov 22 (owner): buy out first – a row whose "Buyout Price" is readable and at most the rule's buyout maximum.
        // Only free rows (not under a message), never twice the same copy, only with enough SG and budget.
        // Only with a steady SG balance (read the same twice, no bid of ours in transit) – afterwards the drop by the buyout
        // price shows whether it went through.
        if (buyoutAn && regel.buyoutMax != null && buyoutPruefung == null && sgSicher && !gebotGerade) {
            for (z in frei) {
                val id = z.id ?: continue
                val sofort = z.sofortPreis ?: continue
                if (!z.hatRestzeit || z.zeichen == Zeichen.UHR) continue
                if (id in buyoutVersucht || id in gesperrtBis) continue
                // Never on a copy where our own bid is (or may be) in front – also when the clock icon was not recognized
                // (long run: icon missing on one screenshot). Outbid ones (hammer) may be bought out.
                val eigen = gebote.gebot(id)
                if (eigen != null && (eigen.stand == GebotsVerwaltung.Stand.VORNE || gebote.frisch(id, jetzt) ||
                        (eigen.inListe && z.zeichen != Zeichen.HAMMER))
                ) {
                    continue
                }
                if (sofort > regel.buyoutMax || sofort > sg || (rest != null && sofort > rest)) continue
                phase = Phase.BietenOffen(
                    Ziel(
                        id, regel, sofort, z.restzeitSek, nachbieten = false, listenpreisBekannt = z.preis != null, buyout = sofort,
                        endeUm = z.restzeitSek?.let { jetzt.plusSeconds(it.toLong()) },
                    ),
                    jetzt,
                )
                gebotStart = jetzt
                return Ergebnis.Handeln(Aktion.Tippen(z.tippen, "buyout ${regel.kopie} for $sofort SG (${z.text()})"), takt.nachZeileMs)
            }
        }

        var spaeterNurNoch = false
        for (z in zeilen) {
            if (!z.hatRestzeit) continue // auction already over (stays in the list for a short while)
            // Nov 11 (review: goal waited forever): remaining time not readable → do not bid (never guess). Without an end
            // the bookkeeping can never evaluate the auction.
            if (z.restzeitSek == null) continue
            if (z.zeichen == Zeichen.UHR) continue // I am already in front
            val id = z.id ?: continue
            // Nov 9 (field test 1.8): just cancelled/failed → do not open again right away.
            if (id in gesperrtBis) continue
            // Nov 11 (field test 1.20): the game's filter has the max price of this rule – then the list only shows
            // copies up to this price, and an unreadable price in the row does not hold things up. Bids are still only placed
            // with a readable "Current Bid" in the Bid window (at most the max price).
            val preisGelesen = z.preis
            if (preisGelesen == null && !preisDurchFilter(regel)) continue
            val preis = preisGelesen ?: regel.maxPreis
            val eigen = gebote.gebot(id)
            // Nov 9 (field test 1.9): just bid → this is mine (in front), even if the row still shows the
            // old icon – do not tap it again.
            if (eigen != null && gebote.frisch(id, jetzt)) continue
            // Nov 11 (test against the fake game: "-7" came again and again): if by our own count 50 are already in
            // My Biddings and cleaning up is not allowed right now, do not start a NEW copy (would only get "-7") –
            // bidding again on our own bids goes on (they are already in there).
            if ((eigen == null || !eigen.inListe) && !gebote.limitGemeldet && gebote.eintraege >= gebote.limit) continue
            // Nov 11 (long run: own bid outbid): a copy that is in My Biddings always has an icon in the game
            // (clock or hammer). If it is missing, it was not recognized – then do not bid again (I could
            // be in front), but wait for a screenshot on which the icon is visible.
            if (eigen != null && eigen.inListe && z.zeichen == Zeichen.KEINS && !z.verdeckt) continue
            if (z.verdeckt && durchMeldungGehtNicht) continue
            val angebot = Angebot(
                id = id,
                typ = regel.kopie,
                preis = preis,
                restzeitSek = z.restzeitSek,
                eigenesGebot = z.zeichen != Zeichen.KEINS || eigen != null,
                ueberboten = z.zeichen == Zeichen.HAMMER || eigen?.stand == GebotsVerwaltung.Stand.UEBERBOTEN,
                bietenKnopf = z.tippen,
            )
            if (regel.maxRestzeitMin != null && z.restzeitSek != null && z.restzeitSek > regel.maxRestzeitMin * 60) {
                // Sorted by Awakened Tier ↑: everything after this ends even later.
                spaeterNurNoch = true
                break
            }
            when (val e = Entscheider.entscheide(angebot, listOf(regel), gebote.kannBieten)) {
                is Entscheidung.Bieten, is Entscheidung.Nachbieten -> {
                    val nach = e is Entscheidung.Nachbieten
                    val preisNeu = if (e is Entscheidung.Bieten) e.preis else (e as Entscheidung.Nachbieten).preis
                    // If the money is not enough (SG or budget): remember it – if it is not enough for ANY copy of the round, the bot says so
                    // (Nov 12, see [geldPruefen]).
                    if (preisNeu > sg || (rest != null && preisNeu > rest)) {
                        zuTeuerInRunde = minOf(zuTeuerInRunde ?: preisNeu, preisNeu)
                        continue
                    }
                    if (geldFehltFuer != null) geldWiederDa()
                    val n = versuche.getOrDefault(id, 0)
                    if (n >= 3) continue
                    versuche[id] = n + 1
                    phase = Phase.BietenOffen(
                        Ziel(
                            id, regel, preisNeu, z.restzeitSek, nach, durchMeldung = z.verdeckt, listenpreisBekannt = preisGelesen != null,
                            endeUm = jetzt.plusSeconds(z.restzeitSek.toLong()),
                        ),
                        jetzt,
                    )
                    gebotStart = jetzt
                    sgBeiGebotStart = letzteSg
                    return Ergebnis.Handeln(
                        Aktion.Tippen(z.tippen, (if (nach) "re-bid" else "bid") + " ${regel.kopie} for $preisNeu SG (${z.text()})"),
                        takt.nachZeileMs,
                    )
                }
                is Entscheidung.Warten ->
                    return Ergebnis.Warten("50 bids reached – waiting until some end", takt.limitVollMs)
                is Entscheidung.Loslassen, is Entscheidung.Ueberspringen -> Unit
            }
        }

        // Nov 9: if a row the bot does not know lies under the message (or it may not tap through),
        // do NOT swipe on or refresh – otherwise this copy would be skipped. Wait until the message is gone.
        // Nov 12 (field test 1.33): check more often (0.3 s instead of 1 s) – as soon as the message is gone, it goes on.
        if (unbekanntUnterMeldung(bild, zeilen)) return Ergebnis.Warten("Message covers a row – waiting briefly", takt.meldungWartenMs)

        // Nov 12 (field test 1.31: the bot paged through the list for minutes without saying why it did not bid):
        // if the money is not enough for any copy, do not page on – stay at the top and refresh every [Takt.geldNachsehenMs].
        if (geldFehltFuer != null) {
            val ab = geldNachsehenAb
            if (ab != null && jetzt.isBefore(ab)) {
                return Ergebnis.Warten(
                    "Money not enough for any copy – looking again at ${uhrzeit(ab)}",
                    minOf(Duration.between(jetzt, ab).toMillis(), takt.limitVollMs),
                )
            }
            geldNachsehenAb = jetzt.plusMillis(takt.geldNachsehenMs)
            return rundeBeenden(bild, jetzt)
        }

        // Nov 12 (field test 1.33): just bid, and according to the (still provisional) SG balance the money is not enough
        // for any copy seen? First wait for the reliable balance (a few seconds at most), then decide – do not
        // page on before that.
        val billigstGesehen = preiseGesehen.values.minOfOrNull { it.first }
        if (geldFehltFuer == null && gebotGerade && billigstGesehen != null && minOf(sg, rest ?: Int.MAX_VALUE) < billigstGesehen) {
            return Ergebnis.Warten("Money may not be enough for any copy – waiting for the SG balance", takt.ruhigMs)
        }

        val sig = zeilen.map { it.id ?: "${it.preis}/${it.oben}" }
        val listeZuEnde = zeilen.isEmpty() || (vorWischen != null && sig == vorWischen)
        // Nov 22: while the bot waits for the next refresh (nothing to do), it only looks – it does not swipe on.
        if (spaeterNurNoch || listeZuEnde || rundeWartet) {
            geldPruefen(sg, rest, jetzt)
            return rundeBeenden(bild, jetzt)
        }

        vorWischen = sig
        return fuenfZeilenWeiter(bild, zeilen, jetzt)
    }

    /**
     * Nov 11 (field test 1.13: "after the 2nd scroll 3 of 12 copies skipped", "always exactly 5 new"):
     * swipe so far that the row AFTER the last fully visible one is exactly at the top ([ERSTE_ZEILE_OBEN]) – on
     * a cleanly aligned page that is 5 rows (905 pixels). Drag slowly and hold still at the end,
     * so the list does not keep sliding with momentum.
     * Nov 11 (field test 1.20: "scrolls clearly too far"): the finger travel is the target divided by the learned
     * ratio ([wischVerhaeltnis]). If the bot has never measured it, it first swipes only 2 rows (then a copy
     * of this page is surely still visible, and it measures exactly) and pushes the rest afterwards.
     */
    private fun fuenfZeilenWeiter(bild: Bildschirm, zeilen: List<Zeile>, jetzt: Instant): Ergebnis {
        val letzteOben = zeilen.maxOfOrNull { it.oben } ?: (ERSTE_ZEILE_OBEN + 4 * ZEILEN_ABSTAND)
        val ziel = wischWeite(letzteOben)
        // Nov 12: measuring swipe as long as the ratio has not been measured in THIS run (even with a start value from the app).
        val messen = wischGemessen <= wischGemessenStart
        // Nov 12 (field test 1.33): not quite as far – the last copy seen stays visible at the top (see [UEBERLAPPUNG_PX]).
        // If the last row is not readable, the last READABLE one stays at the top (the bot can only measure exactly on it).
        val letzteMitId = zeilen.filter { it.id != null }.maxOfOrNull { it.oben }
        val ueberlappung = UEBERLAPPUNG_PX + (if (letzteMitId != null) letzteOben - letzteMitId else 0)
        val soll = when {
            messen -> minOf(ziel, MESS_WISCH_ZEILEN * ZEILEN_ABSTAND)
            ziel - ueberlappung >= KORREKTUR_MIN_PX -> ziel - ueberlappung
            else -> ziel
        }
        // The big swipe is always a long (slow) one – the short measuring swipe too.
        val laenge = fingerweg(soll.toDouble(), lang = true)
        val bezug = zeilen.mapNotNull { z -> z.id?.let { it to z.oben } }.toMap()
        gemerktVergessen() // after scrolling, other rows are shown
        val (ergebnis, seit) = listeWischen(bild, laenge, if (messen) "further down (measure first)" else "further down", jetzt, lang = true)
        nachWischen = NachWischen(
            seit = seit, bezug = bezug, bezugLetzte = letzteOben, ziel = ziel, vorher = 0.0,
            erwartet = bewegung(laenge, lang = true), laenge = laenge, haupt = true, davor = bezug,
            hauptVorher = 0.0, hauptLaenge = laenge, genau = true, anker = bezug, ankerGewandert = 0.0,
            gekappt = !messen && soll == ziel && bewegung(laenge, lang = true) < ziel - KORREKTUR_MIN_PX,
        )
        return ergebnis
    }

    /** Nov 12: ratio of the short swipes (until it is measured, the one of the long swipes). */
    private fun kurzVerhaeltnis(): Double = wischVerhaeltnisKurz ?: wischVerhaeltnis

    /** Is a swipe with [laenge] pixels of finger travel a long (slow) one? */
    private fun istLang(laenge: Int): Boolean = abs(laenge) >= LANGER_WISCHER_PX

    /** How far a swipe with [laenge] pixels of finger travel probably moves the list (+ = up/further). */
    private fun bewegung(laenge: Int, lang: Boolean = istLang(laenge)): Double =
        laenge * (if (lang) wischVerhaeltnis else kurzVerhaeltnis())

    /**
     * Finger travel for [px] pixels of list movement (+ = up/further, − = back) with the learned ratio.
     * Nov 12 (field test 1.31): short swipes with their own ratio ([wischVerhaeltnisKurz]).
     */
    private fun fingerweg(px: Double, lang: Boolean = false): Int {
        val kurz = (px / kurzVerhaeltnis()).roundToInt()
        val roh = if (!lang && !istLang(kurz)) {
            kurz
        } else {
            // Long swipe: at least long enough to be dragged as a long (slow) one.
            val l = (px / wischVerhaeltnis).roundToInt()
            if (!lang && !istLang(l)) (if (l >= 0) LANGER_WISCHER_PX else -LANGER_WISCHER_PX) else l
        }
        return if (roh >= 0) roh.coerceIn(KORREKTUR_MIN_PX, WISCH_START_Y - WISCH_ENDE_MIN_Y) else roh.coerceIn(-(WISCH_START_Y - KORREKTUR_START_Y), -KORREKTUR_MIN_PX)
    }

    /** Move the list by [laenge] with the finger (+ = drag up). Returns the action and when the finger is lifted. */
    private fun listeWischen(bild: Bildschirm, laenge: Int, grund: String, jetzt: Instant, lang: Boolean = istLang(laenge)): Pair<Ergebnis, Instant> {
        val vonY = if (laenge > 0) WISCH_START_Y else KORREKTUR_START_Y
        val dauer = if (lang) takt.wischDauerMs else takt.korrekturDauerMs
        val aktion = Aktion.Wischen(
            bild.ausReferenz(Punkt(WISCH_X, vonY)), bild.ausReferenz(Punkt(WISCH_X, vonY - laenge)), dauer, grund,
            haltenMs = takt.wischHaltenMs,
        )
        return Ergebnis.Handeln(aktion, dauer + takt.wischHaltenMs + takt.nachWischenMs) to jetzt.plusMillis(dauer + takt.wischHaltenMs)
    }

    /**
     * Nov 11: after the swipe (and after pushing on) – does the list stand still? Is the row after the last one
     * seen at the top? null = all good, go on bidding; otherwise wait, look back or push on.
     */
    private fun nachWischenPruefen(bild: Bildschirm, w: NachWischen, jetzt: Instant): Ergebnis? {
        // Nov 12 (review): a screenshot without any rows (the list is loading more …) says nothing about where the list is.
        // Earlier two empty screenshots counted as "stands still" – the check was skipped, and if the list kept sliding in the
        // meantime, a copy was missing. Now: empty screenshots do not count, afterwards the position counts as uncertain (look back).
        // If the list stays empty for too long: refresh and start over at the top.
        if (bild.zeilen.isEmpty()) {
            if (Duration.between(w.seit, jetzt).toMillis() >= takt.leerNachWischenMaxMs) {
                protokoll("List without rows for ${takt.leerNachWischenMaxMs / 1000} s after the swipe – refreshing and starting over at the top")
                nachWischen = null
                return rundeBeenden(bild, jetzt)
            }
            nachWischen = w.copy(vorigeOben = null, nachgerutscht = true)
            return Ergebnis.Warten("List without rows after the swipe – waiting", takt.ruhigPollMs)
        }
        val oben = bild.zeilen.map { it.oben }
        val still = w.vorigeOben != null && oben.size == w.vorigeOben.size &&
            oben.zip(w.vorigeOben).all { (a, b) -> abs(a - b) <= STILL_TOLERANZ_PX }
        if (!still) {
            if (w.vorigeOben == null || Duration.between(w.seit, jetzt).toMillis() < takt.ruhigWartenMaxMs) {
                // If a second screenshot looks different from the first, the list kept sliding after the finger was lifted.
                nachWischen = w.copy(vorigeOben = oben, nachgerutscht = w.nachgerutscht || w.vorigeOben != null)
                return Ergebnis.Warten("List is still sliding – waiting until it stands still", takt.ruhigPollMs)
            }
            // Nov 11 (long run): do not give up (earlier: went on unchecked – then a copy was sometimes missing),
            // but check with this screenshot – as if the list kept sliding (so: uncertain).
            protokoll("List does not settle after the swipe – checking with this screenshot")
            return nachWischenPruefen(bild, w.copy(vorigeOben = oben, nachgerutscht = true), jetzt)
        }
        val ids = bild.zeilen.mapNotNull { z -> z.id?.let { it to z.oben } }.toMap()

        // 1. How far has the list moved since the reference page?
        val ausBezug = ids.keys.filter { it in w.bezug }.map { (w.bezug.getValue(it) - ids.getValue(it)).toDouble() }
        val ausDavor = ids.keys.filter { it in w.davor }.map { (w.davor.getValue(it) - ids.getValue(it)).toDouble() }
        val gewandert: Double
        val genau: Boolean
        val sicher: Boolean
        val ausAnker = ids.keys.filter { it in w.anker }.map { w.ankerGewandert + (w.anker.getValue(it) - ids.getValue(it)) }
        if (ausBezug.isNotEmpty()) {
            gewandert = ausBezug.average()
            genau = true
            sicher = true
        } else if (ausAnker.isNotEmpty()) {
            gewandert = ausAnker.average()
            genau = true
            sicher = true
        } else if (!w.haupt && ausDavor.isNotEmpty()) {
            gewandert = w.vorher + ausDavor.average()
            genau = w.genau
            sicher = w.genau || w.vorigeSicher
        } else {
            // From the row grid: the topmost fully visible card is the j-th after the last one of the reference page.
            val erste = bild.zeilen.minBy { it.oben }
            val basis = (w.bezugLetzte + ZEILEN_ABSTAND - erste.oben).toDouble()
            val schaetzung = w.vorher + w.erwartet
            val jVon = if (erste.id == null) -4 else 0
            val kandidaten = (jVon..14).map { basis + it * ZEILEN_ABSTAND }.sortedBy { abs(it - schaetzung) }
            gewandert = kandidaten[0]
            val vorsprung = abs(kandidaten[1] - schaetzung) - abs(kandidaten[0] - schaetzung)
            genau = false
            // Certain only after two exact measurements (the first one – short – is a bit too small because of the "dead"
            // start of every swipe).
            // Nov 12 (review of 1.31): if the list kept sliding, NEVER estimate from the row grid – not even
            // if the sliding is otherwise even. If it slides one row further once (stutter, an auction
            // disappears), the wrong row fits just as well, and a copy is missing. Better look back then.
            // Nov 12 (field test 1.33): never after a BIG swipe – a copy already seen should be visible
            // (see [UEBERLAPPUNG_PX]); if it is missing, the list slid further than ever expected (outlier, 380 pixels).
            sicher = !w.haupt && wischGemessen >= 2 && !w.nachgerutscht && vorsprung >= WISCH_SICHER_PX && w.vorigeSicher
        }
        // Movement of the last (short) swipe: exact compared with the screenshot right before, otherwise only estimated.
        val kurzGemessen = !w.haupt && ausDavor.isNotEmpty()
        // Nov 11 (field test 1.20): if a message covers rows and the position is not exactly known, first wait briefly for a
        // clear screenshot (the bot needs the copies for checking) – do not push on blindly.
        // Nov 12 (field test 1.33: after almost every swipe 5× "waiting briefly to check"): only wait if fewer than 2 copies
        // are readable because of the message – or if, after the big swipe, it covers exactly the spot where
        // the last copy seen should be (see [UEBERLAPPUNG_PX]; otherwise it would have to look back). Otherwise the
        // bot checks with the free rows.
        val ueberlappVerdeckt = w.haupt && bild.zeilen.any { it.verdeckt && it.oben < ERSTE_ZEILE_OBEN + UEBERLAPPUNG_PX - ZEILEN_ABSTAND + 90 }
        if (!genau && bild.meldung != null && (ids.size < 2 || ueberlappVerdeckt)) {
            val seit = w.meldungSeit ?: jetzt
            if (Duration.between(seit, jetzt).toMillis() < takt.meldungPruefenMaxMs) {
                nachWischen = w.copy(meldungSeit = seit)
                return Ergebnis.Warten("Message covers rows – waiting briefly to check", takt.meldungWartenMs)
            }
        }
        val zuwachs = if (kurzGemessen) ausDavor.average() else gewandert - w.vorher
        val kurzSumme = if (w.haupt) w.kurzSumme else w.kurzSumme + zuwachs

        val kurzGenau = w.kurzGenau && (w.haupt || kurzGemessen)

        // 2. Learn the ratio: how far did the big swipe move the list? Only measured EXACTLY (a copy of the
        // reference page is visible) – an estimate from the row grid would only confirm a wrong assumption.
        var amEnde = false
        var vorMessung = w.vorMessung
        if (!w.hauptGemessen && genau && kurzGenau) {
            val bewegtHaupt = gewandert - kurzSumme - w.hauptVorher
            val gewollt = w.hauptLaenge * wischVerhaeltnis
            val m = bewegtHaupt / w.hauptLaenge
            if (bewegtHaupt < 0.6 * gewollt) {
                amEnde = true // the list did not get that far – end of the list
            } else if (w.hauptLaenge >= 150 && m in 0.5..1.7 && wischGemessen >= 2 && abs(m / wischVerhaeltnis - 1) > WISCH_AUSREISSER &&
                ausreisserInFolge < 2
            ) {
                ausreisserInFolge++
                protokoll(
                    "Swipe: wanted ${gewollt.roundToInt()}, moved ${bewegtHaupt.roundToInt()} pixels – does not fit the ratio " +
                        String.format(java.util.Locale.GERMANY, "%.2f", wischVerhaeltnis) + ", does not count",
                )
            } else if (w.hauptLaenge >= 150 && m in 0.5..1.7) {
                ausreisserInFolge = 0
                val alt = wischVerhaeltnis
                vorMessung = alt to wischGemessen
                // The first measurement counts fully, the second (a long swipe) counts a lot, after that it is only smoothed.
                val gewicht = when (wischGemessen) {
                    0 -> 1.0
                    1 -> 0.7
                    else -> 0.3
                }
                wischVerhaeltnis = (alt + (m - alt) * gewicht).coerceIn(WISCH_VERHAELTNIS_MIN, WISCH_VERHAELTNIS_MAX)
                wischGemessen++
                protokoll(
                    "Swipe: wanted ${gewollt.roundToInt()}, moved ${bewegtHaupt.roundToInt()} pixels · ratio now " +
                        String.format(java.util.Locale.GERMANY, "%.2f", wischVerhaeltnis),
                )
            }
        }
        val hauptGemessen = w.hauptGemessen || (genau && kurzGenau)
        // A push downwards that (measured exactly) moved almost nothing: that is the end of the list too.
        if (!w.haupt && w.laenge > 0 && ausDavor.isNotEmpty() && abs(zuwachs) < 0.3 * abs(w.erwartet)) {
            amEnde = true
            // Nov 12 (review: after two rounds to the end of the list one copy was missing on every page): if the big
            // swipe only did not get quite as far because of the end of the list (60–99 %), that was no measurement of the ratio.
            // Before, it was learned (e.g. 1.00 → 0.84); after that the finger dragged too far, the estimate from the
            // row grid was "certainly" one row off, and one copy per page was skipped. Now: take it back.
            vorMessung?.let { (verhaeltnis, anzahl) ->
                protokoll(
                    "Swipe: list was at its end – measurement does not count, ratio back to " +
                        String.format(java.util.Locale.GERMANY, "%.2f", verhaeltnis),
                )
                wischVerhaeltnis = verhaeltnis
                wischGemessen = anzahl
            }
            vorMessung = null
        }

        // Nov 12 (field test 1.31: after looking back the bot pushed ~100 pixels too far): short swipes move the
        // list by a different amount than long ones – measured exactly (against the screenshot before) the bot learns their own ratio.
        // Nov 12 (review): do not learn at the end of the list – if a push downwards does not get as far as expected
        // (below 80 %), that may be because of the end of the list; then it does not count (otherwise the ratio gets too small).
        val nachUntenGebremst = w.laenge > 0 && (amEnde || abs(zuwachs) < 0.8 * abs(w.erwartet))
        if (kurzGemessen && !istLang(w.laenge) && abs(w.laenge) >= KURZ_LERNEN_MIN_PX && !nachUntenGebremst) {
            val m = zuwachs / w.laenge
            val bisher = wischVerhaeltnisKurz
            // Nov 12 (field test 1.33): outliers (more than 25 % off) do not count – only the third one in a row.
            val ausreisser = bisher != null && abs(m / bisher - 1) > WISCH_AUSREISSER && kurzAusreisserInFolge < 2
            if (ausreisser) kurzAusreisserInFolge++
            if (!ausreisser && m in WISCH_VERHAELTNIS_MIN..KURZ_VERHAELTNIS_MAX) {
                kurzAusreisserInFolge = 0
                val alt = wischVerhaeltnisKurz
                wischVerhaeltnisKurz = if (alt == null) m else alt + (m - alt) * 0.5
                protokoll(
                    "Short swipe: dragged ${abs(w.laenge)}, moved ${abs(zuwachs).roundToInt()} pixels · short ratio now " +
                        String.format(java.util.Locale.GERMANY, "%.2f", wischVerhaeltnisKurz),
                )
            }
        }

        // 3. Where is the row after the last one seen? c > 0: push further up, c < 0: back.
        var c = w.ziel - gewandert
        // If the page looks aligned, that only counts if it is certain how far the list moved – otherwise
        // exactly one whole row could be missing (list kept sliding after the finger was lifted …).
        // Nov 11 (long run): after a step back (or after several attempts) it is enough if the next
        // copy is fully visible – slightly too short is no problem (then one copy less is visible at the bottom).
        // Otherwise the bot pushes back and forth, because every small swipe is a little inaccurate.
        val zurueckGewesen = !w.haupt && w.laenge < 0
        val obenGrenze = if (zurueckGewesen || w.schritte >= 3 || w.gekappt) ZURUECK_OK_MAX_PX else KORREKTUR_MIN_PX
        val nichtsVerdeckt = (genau || sicher) && c >= -AUSRICHTEN_TOLERANZ
        val fertig = (amEnde && c >= -AUSRICHTEN_TOLERANZ) || (nichtsVerdeckt && c < obenGrenze)
        if (!fertig && w.schritte >= MAX_WISCH_SCHRITTE) {
            nachWischen = null
            if ((genau || sicher) && c >= -AUSRICHTEN_TOLERANZ) {
                protokoll("List does not get exactly there after ${w.schritte} attempts – but the next copy is visible, carrying on")
                return null
            }
            // Not certain that nothing was skipped → better start over at the top.
            protokoll("List does not get exactly there after ${w.schritte} attempts – refreshing and starting over at the top")
            return rundeBeenden(bild, jetzt)
        } else if (!fertig) {
            val grund: String
            if (!genau && !sicher) {
                // Uncertain how far the list moved → look back until a copy of the reference page shows up.
                // Nov 12 (long run): so far that the lowest copy of the last exact screenshot is visible at the top
                // again – not just 272 pixels (if the only readable copy was at the very top, it looked back up to three times).
                val ankerUnten = w.anker.values.maxOrNull()
                val noetig = if (ankerUnten != null) (gewandert - w.ankerGewandert) - (ankerUnten - BEKANNT_OBEN_Y) else 0.0
                c = -maxOf(RUECKBLICK_PX.toDouble(), noetig)
                grund = "push the list back (check that no copy was skipped)"
            } else {
                // Nov 11 (long run): too far → back so far that the next copy is a bit below the topmost
                // position (earlier one whole row extra back – then it went back and forth).
                if (c < 0) c -= ZURUECK_ZIEL_PX
                // Nov 12 (long run): far down only so far that the lowest readable copy of this screenshot stays visible
                // at the top (then the bot measures the next step exactly) – the rest in the next step.
                val untenBekannt = ids.values.maxOrNull()
                if (c > 0 && untenBekannt != null && untenBekannt - BEKANNT_OBEN_Y >= KORREKTUR_MIN_PX) {
                    c = minOf(c, (untenBekannt - BEKANNT_OBEN_Y).toDouble())
                }
                grund = "align the list (whole rows)"
                protokoll("List is ${(-c).roundToInt()} pixels off the target – pushing on")
            }
            val laenge = fingerweg(c)
            val (ergebnis, seit) = listeWischen(bild, laenge, grund, jetzt)
            nachWischen = w.copy(
                seit = seit, vorher = gewandert, erwartet = bewegung(laenge), laenge = laenge, haupt = false,
                davor = ids, kurzSumme = kurzSumme, kurzGenau = kurzGenau, genau = genau, vorigeSicher = sicher, vorigeOben = null,
                meldungSeit = null,
                anker = if (genau && ids.isNotEmpty()) ids else w.anker, ankerGewandert = if (genau && ids.isNotEmpty()) gewandert else w.ankerGewandert,
                nachgerutscht = false, schritte = w.schritte + 1, hauptGemessen = hauptGemessen, vorMessung = vorMessung,
            )
            return ergebnis
        }
        nachWischen = null
        val doppelt = ids.keys.filter { it in w.bezug }
        val neu = ids.size - doppelt.size
        val unlesbar = bild.zeilen.size - ids.size
        protokoll(
            "Swiped: $neu new copies" + (if (doppelt.isNotEmpty()) ", ${doppelt.size} already seen" else "") +
                (if (unlesbar > 0) ", $unlesbar not readable" else "") +
                (if (amEnde) " (end of list)" else "") +
                (oben.minOrNull()?.let { " · first row at $it" } ?: "") +
                " · wanted ${w.ziel}, moved ${gewandert.roundToInt()}",
        )
        return null
    }

    /**
     * Nov 9 (field test 1.10/1.11): message over the list → tap nothing. Only evaluate what works without tapping
     * (look at the free rows, take the message as confirmation of the bid) and wait until the screenshot is clear.
     * If the message does not go away for a long time, the screenshot is "uncertain" (the app saves it) – still nothing is tapped.
     */
    private fun meldungAbwarten(bild: Bildschirm, meldung: Meldung, jetzt: Instant): Ergebnis {
        val frei = bild.zeilen.filter { !it.verdeckt }
        frei.forEach { gebote.gesehen(it, jetzt) }
        erfolgsMeldung(meldung, jetzt)
        pruefeLetztesGebot(frei, jetzt)
        val seit = meldungSeit ?: jetzt
        // Nov 11 (review): never wait forever – stop after a few minutes (tap nothing).
        if (Duration.between(seit, jetzt).toMillis() >= takt.meldungStoppMs) {
            protokoll("A message has been lying over the list for ${takt.meldungStoppMs / 60_000} minutes – stopping")
            return beenden(StoppGrund.MELDUNG_HAENGT)
        }
        if (Duration.between(seit, jetzt).toMillis() >= takt.meldungHaengtMs) {
            return Ergebnis.Unsicher("Message over the list does not go away – tapping nothing", takt.unbekanntMs)
        }
        return Ergebnis.Warten("Message over the list – waiting until the screen is clear again", takt.meldungWartenMs)
    }

    /**
     * Nov 9 (field test 1.10/1.11): the game reports the 50 limit ("-7"). Nov 11: after that – or when our own
     * count says about 50 entries in My Biddings – open My Biddings and clean up ([meineGebote]):
     * bid again on outbid ones or dismiss them with the X. If no slot was freed, the bot does not bid until the earliest end
     * + [Takt.limitNachlaufMs] (at most about 2 minutes). null = nothing to do.
     */
    private fun limitBehandeln(bild: Bildschirm, jetzt: Instant): Ergebnis? {
        if (gebote.limitWartezeitVorbei(jetzt)) {
            protokoll("Waiting time after \"50 bids\" over – trying to bid again")
            // Nov 11: if our own count is still at full, look into My Biddings right away (maybe one has ended).
            naechsterDurchgangAb = null
        }
        if (gebote.limitSperreAbgelaufen(jetzt)) protokoll("My Biddings did not work – 50 lock lifted after 6 minutes, trying to bid again")
        val ab = gebote.wiederBietenAb
        if (gebote.limitGemeldet && ab != null) {
            aufraeumen = null
            val rest = Duration.between(jetzt, ab).toMillis().coerceAtLeast(200)
            return Ergebnis.Warten("50 bids in My Biddings – bidding again from ${uhrzeit(ab)}", minOf(rest, takt.limitVollMs))
        }
        if (!gebote.limitGemeldet) {
            // A pass because of "-7" is over when the limit is lifted.
            if (aufraeumen?.durchZaehlung == false) aufraeumen = null
            if (aufraeumen == null) {
                // Nov 11: own count – if there are already about 50 entries in My Biddings, clean up right away
                // (do not wait for "-7"). At most once every [Takt.durchgangAbstandMs].
                if (gebote.eintraege < gebote.limit) return null
                if (naechsterDurchgangAb?.let { jetzt.isBefore(it) } == true) return null
                aufraeumen = Aufraeumen(jetzt, durchZaehlung = true)
                protokoll("About ${gebote.eintraege}/${gebote.limit} in My Biddings – cleaning up there before the game reports \"-7\"")
            }
        }
        // Nov 23 (review round 3): the tap on "My Biddings" loads a list too – at most one reload every 5 s.
        navigationZuFrueh(jetzt, "To My Biddings")?.let { return it }
        val a = aufraeumen ?: Aufraeumen(jetzt).also { aufraeumen = it }
        a.menueVersuche++
        if (a.menueVersuche > 3) {
            // My Biddings does not open (or the game keeps jumping back) – then do not keep trying.
            aufraeumen = null
            naechsterDurchgangAb = jetzt.plusMillis(takt.durchgangAbstandMs)
            if (!gebote.limitGemeldet) {
                protokoll("Could not open My Biddings – bidding on (the game will report \"-7\")")
                return null
            }
            val bis = jetzt.plusMillis(takt.limitOhneZeitMs)
            gebote.limitWartenBis(bis)
            protokoll("Could not clean up My Biddings – bidding again from ${uhrzeit(bis)}")
            return Ergebnis.Warten("50 bids – bidding again from ${uhrzeit(bis)}", takt.limitVollMs)
        }
        // When the bot gets back to My Biddings, the list may be somewhere else: look for the start again.
        a.obenGeprueft = false
        a.amEnde = false
        a.wischen = null
        a.vorige = null
        return tippe(bild, Knopf.MENUE_MEINE_GEBOTE, LIMIT_NACHSEHEN, takt.nachTippenMs)
    }

    /** Time of day for the log, e.g. "21:47:05" (time zone of the stop condition = the device's). */
    private fun uhrzeit(t: Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(t.atZone(stopp.zone))

    /** Nothing left to do at the top or list at its end: refresh – or after empty passes, the next rank. */
    /**
     * Nov 12 (field test 1.31: budget 2500, 2473 SG were tied up in bids, the next copy cost at least 43 SG –
     * the bot rightly did not bid any more, but did not say so and kept paging for minutes; the user thought it was stuck):
     * at the end of a round without a bid in which at least one copy failed only because of the money, the bot says so ONCE,
     * shows it at the IH button ([budgetVerplant] or [zuWenigSg]) and stops paging: it stays at the top of the list and checks every
     * [Takt.geldNachsehenMs] whether outbid bids brought SG back. If none of our own bids is in front any more (then
     * nothing can come back), it stops ([StoppGrund.BUDGET_ERREICHT] or [StoppGrund.SG_ALLE]).
     */
    private fun geldPruefen(sg: Int, rest: Int?, jetzt: Instant) {
        val knapp = zuTeuerInRunde ?: return
        if (gebotenInDurchlauf || geldFehltFuer != null) return
        geldFehlt(knapp, sg, rest, jetzt)
    }

    /** The money is not enough for any copy (the cheapest would need [knapp] SG): say it once, show it, stop paging. */
    private fun geldFehlt(knapp: Int, sg: Int, rest: Int?, jetzt: Instant) {
        geldFehltDurchBudget = rest != null && rest < sg
        geldFehltSeit = jetzt
        geldNachsehenAb = jetzt.plusMillis(takt.geldNachsehenMs)
        geldFehltFuer = knapp
        if (geldFehltDurchBudget) budgetVerplant = true else zuWenigSg = true // show it at the IH button right away
        val budget = stopp.sgBudget
        if (geldFehltDurchBudget && budget != null && rest != null) {
            protokoll(
                "SG budget used up: ${budget - rest} of $budget SG are tied up in bids – waiting until outbid bids bring SG " +
                    "back (the cheapest copy needs $knapp SG, $rest are free)",
            )
        } else {
            protokoll("SG not enough for any copy: $sg SG available, the cheapest needs $knapp SG – waiting until outbid bids bring SG back")
        }
    }

    private fun geldWiederDa() {
        geldFehltFuer = null
        geldFehltSeit = null
        geldNachsehenAb = null
        geldVorneLeerSeit = null
    }

    private fun rundeBeenden(bild: Bildschirm, jetzt: Instant): Ergebnis {
        // Nov 22 (review: refresh rate, rate limit): nothing to do → not faster than every 5–15 s (see [aktualisierenWarten]).
        aktualisierenWarten(jetzt)?.let { return it }
        rundeWartet = false
        // The next round without anything to do waits a bit longer (×1.5, at most [Takt.leerlaufMaxMs]).
        if (leerlaufMs > 0) leerlaufMs = minOf(leerlaufMs * 3 / 2, takt.leerlaufMaxMs)
        versuche.clear()
        zuTeuerInRunde = null
        vorWischen = null
        nachWischen = null
        leereDurchlaeufe = if (gebotenInDurchlauf) 0 else leereDurchlaeufe + 1
        gebotenInDurchlauf = false
        if (aktiveRegeln.size > 1 && leereDurchlaeufe >= LEERE_DURCHLAEUFE) {
            protokoll("Nothing to bid on ${aktuelleRegel?.kopie} right now – other rank")
            return naechsteRegel(bild)
        }
        return tippe(bild, Knopf.AKTUALISIEREN, "round over – refresh", takt.nachAktualisierenMs)
    }

    private fun naechsteRegel(bild: Bildschirm): Ergebnis {
        // Nov 23 (review): the next rule needs the filter (its Confirm reloads the list) – wait for the gap first.
        filterNochNicht(uhr)?.let { return it }
        regelNr = (regelNr + 1) % aktiveRegeln.size
        // Nov 12 (review): if the bot is waiting for money, that stays (one message, the 70 minutes keep counting) –
        // it only also looks at the top for the other rule. If a copy is affordable there, it bids (then it is over).
        zuTeuerInRunde = null
        leereDurchlaeufe = 0
        gebotenInDurchlauf = false
        gebote.ueberbotenZuruecksetzen()
        versuche.clear()
        vorWischen = null
        nachWischen = null
        val naechste = aktiveRegeln[regelNr]
        protokoll("Moving on with rule $naechste")
        return filterOeffnen(bild, naechste.kopie)
    }

    /**
     * Nov 23 (review: at most one reload every 5 s, always): the filter is closed with Confirm, and that reloads the list. While
     * the filter window is open nothing else reloads it – so the filter is only opened once a reload is allowed again; then its
     * Confirm can never come too early. null = open now.
     */
    private fun filterNochNicht(jetzt: Instant): Ergebnis? =
        abfrageZuFrueh(jetzt, takt.abfrageAbstandMs, "Filter: waiting for the gap between two reloads of the list", takt.abfrageBlickMs)

    private fun filterOeffnen(bild: Bildschirm, stufe: String): Ergebnis {
        filterNochNicht(uhr)?.let { return it }
        filterSitzung(stufe, uhr)
        phase = Phase.FilterSetzen(stufe)
        preiseGesehen.clear() // other copies, other prices
        filterMaxGesetzt = null
        if (filterStart == null) filterStart = uhr
        return tippe(bild, Knopf.FILTER, "open filter for $stufe", takt.nachTippenMs)
    }

    // ---------------- Filter ----------------

    private fun filterFenster(bild: Bildschirm): Ergebnis {
        // Nov 11 (emulator test, real app: the screenshot lagged 1–2 s behind): right after "Confirm" the screenshot may still
        // show the filter window although it is already closed. Then do NOT enter the values again – otherwise the taps on the price field
        // landed in the market (Bid window opened) and in the Bid window on "+"/"−". Wait first; if it is still there
        // afterwards (steady), it really is open.
        if (phase is Phase.FilterPruefen) {
            val ok = filterOkUm
            if ((ok != null && Duration.between(ok, uhr).toMillis() < takt.filterZuWartenMs) || !ortRuhig) {
                return Ergebnis.Warten("Filter is closing (Confirm tapped) – waiting, tapping nothing", takt.ruhigMs)
            }
        }
        val p = phase as? Phase.FilterSetzen ?: Phase.FilterSetzen(aktuelleRegel?.kopie ?: return beenden(StoppGrund.KEINE_REGEL))
        phase = p
        if (filterStart == null) filterStart = uhr
        val regel = aktiveRegeln.firstOrNull { it.kopie == p.stufe } ?: aktuelleRegel ?: return beenden(StoppGrund.KEINE_REGEL)

        val felder = bild.filterFelder
        if (felder.isEmpty()) return Ergebnis.Unsicher("Filter window without fields", takt.unbekanntMs)

        // Nov 9 (speed): first everything in one go, then check once.
        if (!p.einzeln) return filterInEinemRutsch(bild, p, regel)

        // Step by step – 1. rank: select exactly the wanted one, deselect all others.
        val falsch = felder.firstOrNull { it.gewaehlt != (it.stufe == p.stufe) }
        if (falsch != null) {
            if (p.taps > 30) return beenden(StoppGrund.FILTER_GEHT_NICHT)
            phase = p.copy(taps = p.taps + 1)
            val was = if (falsch.gewaehlt) "deselect" else "select"
            return Ergebnis.Handeln(Aktion.Tippen(falsch.punkt, "Filter: ${falsch.stufe} $was"), takt.nachTippenMs)
        }

        // 2. Price fields (Nov 9): left the min price, right the max price of the rule.
        if (preisEingeben()) {
            when (p.schritt) {
                FilterSchritt.RANG, FilterSchritt.FOLGE_PRUEFEN -> {
                    eingabe = null
                    phase = p.copy(schritt = FilterSchritt.MIN)
                    return eingeben(bild, Knopf.FILTER_MIN, regel.minPreis, "Filter: min price ${regel.minPreis}")
                }
                FilterSchritt.MIN -> {
                    if (!eingabeGeklappt(bild.filterMinStellen, regel.minPreis)) return preisfelderGehenNicht(bild, p)
                    phase = p.copy(schritt = FilterSchritt.MAX)
                    return eingeben(bild, Knopf.FILTER_MAX, filterMax(regel), "Filter: max price ${filterMax(regel)}")
                }
                FilterSchritt.MAX -> {
                    if (!eingabeGeklappt(bild.filterMaxStellen, filterMax(regel))) return preisfelderGehenNicht(bild, p)
                    preisfelderGeklappt(p.stufe, regel)
                    phase = p.copy(schritt = FilterSchritt.FERTIG)
                }
                FilterSchritt.FERTIG -> Unit
            }
        }

        // 3. Confirm.
        phase = Phase.FilterPruefen(p.stufe)
        return tippe(bild, Knopf.FILTER_OK, "confirm filter ${p.stufe} ${regel.minPreis}–${filterMax(regel)}", takt.nachFilterOkMs)
    }

    /**
     * Nov 9: rank on/off and both price fields in ONE sequence (no screenshot in between), then check on the
     * next screenshot: if rank and price fields are right, Confirm is tapped. Otherwise step by step.
     */
    private fun filterInEinemRutsch(bild: Bildschirm, p: Phase.FilterSetzen, regel: Suchregel): Ergebnis {
        val felder = bild.filterFelder
        when (p.schritt) {
            FilterSchritt.FOLGE_PRUEFEN -> {
                val rangOk = felder.all { it.gewaehlt == (it.stufe == p.stufe) }
                if (preisEingeben()) {
                    val ergebnis = eingabe
                    eingabe = null
                    if (ergebnis == false && rangOk) return preisfelderGehenNicht(bild, p)
                    val stellenOk = stellenPassen(bild.filterMinStellen, regel.minPreis) &&
                        stellenPassen(bild.filterMaxStellen, filterMax(regel))
                    if (rangOk && ergebnis == true && stellenOk) {
                        preisfelderGeklappt(p.stufe, regel)
                        phase = Phase.FilterPruefen(p.stufe)
                        return tippe(bild, Knopf.FILTER_OK, "confirm filter ${p.stufe} ${regel.minPreis}–${filterMax(regel)}", takt.nachFilterOkMs)
                    }
                    protokoll(
                        "Filter in one go did not fully work (" +
                            (if (!rangOk) "rank" else if (ergebnis != true) "input without answer" else "price fields show ${bild.filterMinStellen}/${bild.filterMaxStellen} characters") +
                            ") – now step by step",
                    )
                } else {
                    if (rangOk) {
                        phase = Phase.FilterPruefen(p.stufe)
                        return tippe(bild, Knopf.FILTER_OK, "confirm filter ${p.stufe} (without price fields)", takt.nachFilterOkMs)
                    }
                    protokoll("Filter in one go: rank not right yet – now step by step")
                }
                phase = p.copy(einzeln = true, schritt = FilterSchritt.RANG)
                return filterFenster(bild)
            }
            else -> {
                if (p.taps > 30) return beenden(StoppGrund.FILTER_GEHT_NICHT)
                val taps = felder.filter { it.gewaehlt != (it.stufe == p.stufe) }
                    .map { Aktion.Tippen(it.punkt, "Filter: ${it.stufe} " + if (it.gewaehlt) "deselect" else "select") }
                val eingaben = if (!preisEingeben()) {
                    emptyList()
                } else {
                    val min = bild.knopf(Knopf.FILTER_MIN)
                    val max = bild.knopf(Knopf.FILTER_MAX)
                    if (min == null || max == null) {
                        phase = p.copy(einzeln = true)
                        return filterFenster(bild)
                    }
                    listOf(
                        Aktion.Eingeben(min, regel.minPreis.toString(), "Filter: min price ${regel.minPreis}"),
                        Aktion.Eingeben(max, filterMax(regel).toString(), "Filter: max price ${filterMax(regel)}"),
                    )
                }
                val alle = taps + eingaben
                if (alle.isEmpty()) {
                    phase = Phase.FilterPruefen(p.stufe)
                    return tippe(bild, Knopf.FILTER_OK, "confirm filter ${p.stufe}", takt.nachFilterOkMs)
                }
                eingabe = null
                phase = p.copy(schritt = FilterSchritt.FOLGE_PRUEFEN, taps = p.taps + taps.size)
                return Ergebnis.Handeln(
                    Aktion.Folge(alle, takt.folgeAbstandMs, "set filter ${p.stufe} ${regel.minPreis}–${filterMax(regel)}"),
                    takt.nachEingabeMs,
                )
            }
        }
    }

    /** Are there as many characters in the price field as expected? (+1 for the cursor; not readable = OK) */
    private fun stellenPassen(stellen: Int?, zahl: Int): Boolean {
        val erwartet = zahl.toString().length
        return stellen == null || stellen == erwartet || stellen == erwartet + 1
    }

    private fun eingeben(bild: Bildschirm, knopf: Knopf, zahl: Int, grund: String): Ergebnis {
        val feld = bild.knopf(knopf) ?: return Ergebnis.Unsicher("Field $knopf not found", takt.unbekanntMs)
        return Ergebnis.Handeln(Aktion.Eingeben(feld, zahl.toString(), grund), takt.nachEingabeMs)
    }

    /** Did the app report "worked" – and is (if readable) roughly the right number of digits in the field? */
    private fun eingabeGeklappt(stellen: Int?, zahl: Int): Boolean {
        val ok = eingabe == true
        eingabe = null
        if (!ok) return false
        val erwartet = zahl.toString().length
        if (stellen != null && stellen != erwartet && stellen != erwartet + 1) { // +1: cursor
            protokoll("Price field shows $stellen characters instead of $erwartet – input did not take")
            return false
        }
        return true
    }

    /**
     * Price fields do not work: remember it, log it and carry on with the old behavior (the bot checks every row's price itself).
     * Nov 23 (coordinator): not for good any more – tried again after 10, 20, 40 min, or sooner ([preisfelderWieder]).
     */
    private fun preisfelderGehenNicht(bild: Bildschirm, p: Phase.FilterSetzen): Ergebnis {
        // Nov 23: a failed input may have gone through in one field (long run: "20" in the max field while the bot thought
        // "60") – from now on the bot does not know for sure which prices the game's filter holds.
        filterPreiseUnsicher = true
        // Nov 11 (long run: never bid on B- again after that): if the price fields already worked in this run, it was
        // just a hiccup – try again (at most 2 times in a row). Otherwise the old prices stayed in the
        // game's filter (e.g. 10–20 from C-), and the list for B- would always be empty from now on.
        if (preisfelderGehen == true && preisfelderFehler < 2) {
            preisfelderFehler++
            protokoll("Price fields: input did not work this time – again (step by step)")
            eingabe = null
            phase = p.copy(schritt = FilterSchritt.RANG, einzeln = true, taps = p.taps + 1)
            return filterFenster(bild)
        }
        val nochmal = preisfelderGehen == false // a new try after giving up did not work either
        preisfelderGehen = false
        preisfelderDiesmal = false
        filterMaxGesetzt = null
        preisfelderPauseMs = if (preisfelderPauseMs == 0L) takt.preisfelderNeuMs else minOf(preisfelderPauseMs * 2, takt.preisfelderNeuMaxMs)
        val ab = uhr.plusMillis(preisfelderPauseMs)
        preisfelderNeuAb = ab
        protokoll(
            (if (nochmal) "Price fields still cannot be filled in" else "Price fields in the filter cannot be filled in") +
                " – the bot checks the prices itself; trying again at ${uhrzeit(ab)} (sooner if a list looks filtered by other prices)",
        )
        phase = Phase.FilterPruefen(p.stufe)
        return tippe(bild, Knopf.FILTER_OK, "confirm filter ${p.stufe} (without price fields)", takt.nachFilterOkMs)
    }

    /** Nov 23: does this filter enter the price fields? (Always while they work; after giving up only when tried again.) */
    private fun preisEingeben(): Boolean = preisfelderGehen != false || preisfelderDiesmal

    /** The price fields were entered and checked – the game's filter holds this rule's prices (Confirm comes next). */
    private fun preisfelderGeklappt(stufe: String, regel: Suchregel) {
        if (preisfelderGehen == null) protokoll("Price fields can be filled in")
        if (preisfelderGehen == false) protokoll("Price fields work again – the filter holds ${regel.minPreis}–${filterMax(regel)} again")
        preisfelderGehen = true
        preisfelderGingSchon = true
        preisfelderFehler = 0
        preisfelderPauseMs = 0
        preisfelderNeuAb = null
        preisfelderDiesmal = false
        preisfelderAngefordert = false
        filterMaxGesetzt = stufe to filterMax(regel)
        filterPreiseBestaetigt = regel.minPreis to filterMax(regel)
        filterPreiseUnsicher = false
        falscheListeSeit = null
    }

    /**
     * Nov 23: the filter is opened for [stufe] (the 5 s gap between reloads is already kept). While the price fields are given
     * up, decide whether this filter tries them again ([preisfelderWieder]); asked for from the bidding list, it does.
     */
    private fun filterSitzung(stufe: String, jetzt: Instant) {
        falscheListeSeit = null
        if (preisfelderGehen != false) {
            preisfelderDiesmal = false
            preisfelderAngefordert = false
            return
        }
        if (preisfelderDiesmal) return // the same try (the filter is opened once more)
        val regel = aktiveRegeln.firstOrNull { it.kopie == stufe } ?: return
        preisfelderDiesmal = preisfelderAngefordert || preisfelderWieder(regel, jetzt, listeFalsch = false)
        preisfelderAngefordert = false
    }

    /**
     * Nov 23 (coordinator: "the bot must not lose a rank's list for the rest of the night"): the price fields were given up
     * – try them again now? Every try is a filter, so the 5 s gap between reloads is always kept ([filterNochNicht]).
     *  - Regularly: [Takt.preisfelderNeuMs] after giving up, doubling with every failed try up to [Takt.preisfelderNeuMaxMs]
     *    (10, 20, 40 min).
     *  - Sooner, at most every [Takt.preisfelderEiligMs], if the game's filter holds prices that hide this rule's copies: the
     *    prices last confirmed do not cover the rule (switching to a rule with another range), or the rule's list looks
     *    filtered ([listeFalsch]: empty or only prices outside the rule, see [preisfelderPruefen]) while the filter is known
     *    or suspected to hold other prices.
     * The bids are not affected: while the filter is not verified, a bid always needs a readable row price ≤ max ([preisDurchFilter]).
     */
    private fun preisfelderWieder(regel: Suchregel, jetzt: Instant, listeFalsch: Boolean): Boolean {
        if (preisfelderGehen != false) return false
        val ab = preisfelderNeuAb
        if (ab != null && !jetzt.isBefore(ab)) {
            preisfelderNeuVersuche++
            protokoll("Price fields: trying them again (given up ${preisfelderPauseMs / 60_000} min ago)")
            return true
        }
        val bestaetigt = filterPreiseBestaetigt
        val regelPreise = regel.minPreis to filterMax(regel)
        val grund = when {
            bestaetigt != null && (bestaetigt.first > regelPreise.first || bestaetigt.second < regelPreise.second) ->
                "the game's filter holds ${bestaetigt.first}–${bestaetigt.second}, ${regel.kopie} needs ${regelPreise.first}–${regelPreise.second}"
            listeFalsch && preisfelderGingSchon && (filterPreiseUnsicher || bestaetigt != regelPreise) ->
                "the list for ${regel.kopie} looks filtered by other prices"
            else -> return false
        }
        if (preisfelderEiligUm?.let { Duration.between(it, jetzt).toMillis() < takt.preisfelderEiligMs } == true) return false
        preisfelderEiligUm = jetzt
        preisfelderNeuVersuche++
        protokoll("Price fields: trying them again now – $grund")
        return true
    }

    /**
     * Nov 23: in the bidding list while the price fields are given up – does the rule's list look filtered by other prices
     * (empty, or only prices outside the rule), for [Takt.preisfelderVerdachtMs] and across a reload? Or is the regular try
     * due? Then ask for a filter with a new try (the list is set again, with the 5 s gap). true = asked for.
     */
    private fun preisfelderPruefen(bild: Bildschirm, regel: Suchregel, jetzt: Instant): Boolean {
        if (preisfelderGehen != false || filterStufe != regel.kopie || preisfelderAngefordert) return false
        val preise = bild.zeilen.filter { !it.verdeckt }.mapNotNull { it.preis }
        val falsch = when {
            bild.zeilen.isEmpty() -> true
            preise.isEmpty() -> null // nothing readable – no conclusion
            else -> preise.none { it in regel.minPreis..filterMax(regel) }
        }
        when (falsch) {
            true -> if (falscheListeSeit == null) falscheListeSeit = jetzt
            false -> falscheListeSeit = null
            null -> Unit
        }
        val seit = falscheListeSeit
        val lange = seit != null && Duration.between(seit, jetzt).toMillis() >= takt.preisfelderVerdachtMs &&
            letzteAbfrageUm?.isAfter(seit) == true
        val faellig = preisfelderNeuAb?.let { !jetzt.isBefore(it) } == true
        if (!lange && !faellig) return false
        if (!preisfelderWieder(regel, jetzt, listeFalsch = lange)) return false
        preisfelderAngefordert = true
        filterStufe = null
        return true
    }

    /** After the filter: does the list really show the new rank? (Badge color: C purple, B green, A red) */
    private fun filterPruefen(bild: Bildschirm, p: Phase.FilterPruefen): Ergebnis {
        val buchstabe = p.stufe.first()
        val gesehen = bild.zeilen.mapNotNull { it.stufe }
        val passt = buchstabe !in "CBA" || gesehen.all { it == buchstabe }
        if (!passt) {
            if (!p.aktualisiert) {
                // Nov 22 (rate limit): not right after the filter's Confirm – the game may refuse it ("too frequently");
                // maybe the list shows the new rank by itself meanwhile.
                abfrageZuFrueh(uhr, takt.abfrageAbstandMs, "List still shows ${gesehen.distinct()}")?.let { return it }
                // Nov 9: after Confirm the list sometimes still shows the old rank – refreshing helps.
                phase = p.copy(aktualisiert = true)
                return tippe(bild, Knopf.AKTUALISIEREN, "list still shows ${gesehen.distinct()} – refresh", takt.nachAktualisierenMs)
            }
            // Nov 23 (review): opening the filter again only once a reload is allowed (its Confirm reloads the list) – and
            // only then does this count as a failed attempt (the list may show the new rank by itself meanwhile).
            filterNochNicht(uhr)?.let { return it }
            filterFehlversuche++
            protokoll("Filter ${p.stufe}: list still shows ${gesehen.distinct()} – again")
            if (filterFehlversuche > 2) return beenden(StoppGrund.FILTER_GEHT_NICHT)
            return filterOeffnen(bild, p.stufe)
        }
        filterFehlversuche = 0
        filterStufe = p.stufe
        sortierVersuche = 0
        vorWischen = null
        phase = Phase.Suchen
        protokoll("Filter set to ${p.stufe}" + (filterStart?.let { " · ⏱ ${dauer(it, uhr)}" } ?: ""))
        filterStart = null
        return Ergebnis.Warten("Filter ${p.stufe} set", 150)
    }

    // ---------------- Bidding ----------------

    private fun bietenFenster(bild: Bildschirm, jetzt: Instant): Ergebnis {
        // Nov 11 ("+" next to the Starry Gems): the red X of the Bid window is in the purchase bar at the top. A
        // Bid window that has only just become visible (or is only visible briefly) is therefore ONLY used for the planned "Bid"
        // – for everything else (X, message) first wait for a second screenshot. If the window is closing, it is
        // gone on the next screenshot, and nothing was tapped.
        val meldung = bild.meldung
        // Nov 11 (field test 1.20, new rule): "Just keep bidding, no matter which message – except the error message
        // (-7)." Only the message in its exact form counts as "-7" (and not right after "Verify").
        val nachVerify = verifyUm?.let { Duration.between(it, jetzt).toMillis() < takt.meldungNachVerifyMs } == true
        val limitMeldung = meldung != null && meldung.kurz && meldung.wieLimit && !nachVerify
        if (!ortRuhig && (phase !is Phase.BietenOffen || limitMeldung)) {
            return Ergebnis.Warten("Bid window is opening or closing – waiting briefly", takt.ruhigMs)
        }
        // Nov 11 (long run: second X on a window that was already closing → "+"): just tapped the X ourselves –
        // the window is closing. Do not evaluate again and tap nothing until it is gone.
        if (phase == Phase.Suchen && gerade(xTippUm, jetzt)) {
            return Ergebnis.Warten("Bid window is closing (X tapped) – waiting", takt.ruhigMs)
        }
        if (meldung == null) bidMeldungSeit = null else bidMeldungZuletzt = jetzt
        // Nov 11 (field test 1.20): learn the position of the Bid button (steady window, nothing on top).
        if (meldung == null && ortRuhig) bild.knopf(Knopf.BIETEN)?.let { bidKnopfGelernt = it }
        if (meldung != null) {
            // Nov 11 (review: message stayed in the window): never wait forever.
            val seit = bidMeldungSeit ?: jetzt.also { bidMeldungSeit = it }
            if (Duration.between(seit, jetzt).toMillis() >= takt.meldungStoppMs) {
                protokoll("Message in the Bid window has not gone away for ${takt.meldungStoppMs / 60_000} minutes – stopping")
                return beenden(StoppGrund.MELDUNG_HAENGT)
            }
            // The "-7" message: 50 bids reached – then it cannot bid any more (clean up My Biddings).
            if (limitMeldung) return kurzeMeldung(bild, meldung, jetzt)
            // Nov 11 (review: "+" tap): a NEW "Bidding successful" over a Bid window that is closing –
            // the bid went through. Remember it and just wait until the window is gone. NEVER tap.
            if (meldung.wieErfolg) erfolgImFenster(jetzt)?.let { return it }
            // Nov 12 (review: "+" tap): "Bidding successful" over a Bid window in which the bot does NOT want to bid
            // right now (after pause → resume, old message after the bid …) – the window is closing by itself. Never tap the X,
            // just wait. Limited: Bid window at most [Takt.bidFensterMaxMs], message at most [Takt.meldungStoppMs].
            if (meldung.wieErfolg && phase !is Phase.BietenOffen) {
                return Ergebnis.Warten("\"Bidding successful\" – the Bid window closes by itself, tapping nothing", takt.meldungWartenMs)
            }
            // All other messages (outbid, "Bid Failure" of another copy, message after Verify …): the checks below run as
            // without a message (closing for "too expensive" etc. still works), but Bid is not tapped until the band is gone
            // (Nov 23, review round 3). The X at the top still stays locked as long as the window could be closing.
        }

        // After the secondary password the Bid window is back → "Bid" once more.
        (phase as? Phase.Passwort)?.let { pw ->
            passwortGeklappt()
            val ziel = pw.ziel
            val akt = bild.aktuellesGebot
            // Safety: if "Current Bid" is already at my bid, it went through with the password –
            // then do NOT bid again (otherwise I would outbid myself).
            if (ziel != null && akt != null && akt >= ziel.preis) {
                gebotRegistrieren(ziel, ziel.preis, jetzt, " – after the secondary password (Current Bid $akt)")
                // Nov 11 (long run: "+" tap): the window now usually closes by itself – just wait as after "Confirm"
                // (see [Takt.fensterZuWartenMs]); counted from "Verify".
                phase = Phase.Bestaetigt(ziel, pw.geprueftUm ?: jetzt)
                return Ergebnis.Warten("Bid is in – Bid window will close by itself", takt.ruhigMs)
            }
            phase = ziel?.let { Phase.BietenOffen(it, jetzt) } ?: Phase.Suchen
        }

        return when (val p = phase) {
            is Phase.BietenOffen -> if (p.ziel.buyout != null) buyoutFenster(bild, p, jetzt) else {
                // Nov 9 (field test 1.8): Current Bid cannot be below the price in the list (prices
                // only go up). Read smaller = a digit got lost → counts as NOT readable.
                val listenpreis: Int? = if (p.ziel.listenpreisBekannt) p.ziel.preis - GEBOTS_SCHRITT else null
                val gelesen = bild.aktuellesGebot
                val akt = gelesen?.takeIf { listenpreis == null || it >= listenpreis }
                if (gelesen != null && akt == null) {
                    protokoll("Current Bid misread ($gelesen, the list showed $listenpreis) – counts as not readable")
                }
                val sg = letzteSg
                // Nov 11: close (red X at the top) only when the window is steady – otherwise wait one screenshot.
                // Nov 11 (long run): the decision "close" then stays (see [Phase.BietenOffen.schliessen]).
                // Nov 12 (review: "+" tap): if the auction is ending right now (at most [ENDET_GLEICH_SEK] s remaining) or has
                // just ended, the window may close by itself – a tap on the X at the top would then hit the "+" below it.
                // Then do not close, but wait: if the window closes, all is well (see [zurueckInDerListe]); if it is
                // still there [Takt.fensterZuWartenMs] after the end, it does not close by itself – then use the X after all.
                // The remaining time is rounded down to whole seconds – so the end can be up to 1 s later.
                val endetGerade = p.ziel.endeUm?.plusSeconds(1)?.let { ende ->
                    Duration.between(jetzt, ende).toMillis() <= ENDET_GERADE_FENSTER_SEK * 1000L && jetzt.isBefore(ende.plusMillis(takt.fensterZuWartenMs))
                } == true
                fun zu(warum: String, text: String?, sperren: Boolean = true): Ergebnis {
                    if (!ortRuhig || endetGerade) {
                        phase = p.copy(schliessen = Schliessen(warum, text, sperren))
                        return Ergebnis.Warten(
                            if (endetGerade) "Auction is ending – Bid window may close by itself, not tapping the X"
                            else "Let the Bid window settle first, then close it",
                            takt.ruhigMs,
                        )
                    }
                    text?.let { protokoll(it) }
                    return ohneGebotSchliessen(bild, p.ziel, jetzt, warum, sperren)
                }
                p.schliessen?.let { z -> return zu(z.warum, z.text, z.sperren) }
                // Nov 23 (review round 3): the bot does not tap Bid while a band lies over the window – the short waits below
                // count from the window's opening, or from the end of the last band in it if that came later.
                val wartenAb = bidMeldungZuletzt?.takeIf { it.isAfter(p.seit) } ?: p.seit
                // Nov 11 (review: bid 26 blindly with a maximum of 21): NEVER bid blindly. Without a readable "Current Bid"
                // the bot does not know what the game is bidding (the list may have moved – then a more expensive copy
                // opened). Read again for a few screenshots, otherwise close and do not open the row for 60 s.
                if (akt == null) {
                    if (Duration.between(wartenAb, jetzt).toMillis() < takt.currentBidWartenMs) {
                        // Nov 12: "the same twice in a row" (below) starts over after an unreadable screenshot.
                        phase = p.copy(currentBidVorher = null)
                        return Ergebnis.Warten("Current Bid not readable – looking again", takt.ruhigMs)
                    }
                    return zu("Current Bid not readable", "Current Bid not readable – no bid (never bid blindly)")
                }
                val naechstes = akt + GEBOTS_SCHRITT
                when {
                    !p.ziel.regel.erlaubt(akt.toDouble()) ->
                        zu("too expensive", "Price is now $akt – does not fit ${p.ziel.regel}")
                    // Nov 11 (long run: own bid outbid): much more expensive than in the list → probably a
                    // different copy opened (list moved). The planned row itself is fine – do not lock it.
                    listenpreis != null && akt > listenpreis + MAX_PREISSPRUNG ->
                        zu("other copy?", "Current Bid $akt, the list showed $listenpreis – probably a different copy, not bidding", sperren = false)
                    sg == null -> zu("SG balance not readable", "SG balance not readable – no bid")
                    naechstes > sg -> zu("too little SG", "Bid $naechstes SG, but only $sg SG available – not bidding")
                    budgetRest(sg)?.let { naechstes > it } == true ->
                        zu("budget", "Bid $naechstes SG no longer fits the SG budget – not bidding")
                    // Nov 11: bidding again while cleaning up My Biddings despite "-7" (the outbid bid already has its slot).
                    !gebote.kannBieten && !p.ziel.ausMeineGebote -> zu("limit full", null)
                    // Nov 23 (review: "unknown messages in the Bid window are tapped through"): a message the bot does not know
                    // lies over the window (the pause in [spielBremse] is over) – NEVER tap Bid under it, not even at the learned
                    // position. Wait until it is gone; if it stays, close the window with the red X (that is not under it).
                    fremdImFenster(bild) ->
                        if (fremdeMeldungBleibt(jetzt)) {
                            zu("unknown message", "Unknown message over the Bid window does not go away – closing it, no bid")
                        } else {
                            Ergebnis.Warten("Unknown message over the Bid window – not tapping Bid under it", takt.meldungWartenMs)
                        }
                    // Nov 12 (review): if the price in the row was not readable (only the game's filter says "up to the
                    // max price"), the comparison with the list is missing – a lost digit ("12" instead of "120") would not be
                    // noticed. Then only bid once "Current Bid" was read the same on two steady screenshots in a row
                    // (at most the max price, see above). If that does not work within [Takt.currentBidWartenMs] × 2: close.
                    !p.ziel.listenpreisBekannt && (!ortRuhig || p.currentBidVorher != akt) -> {
                        if (ortRuhig) phase = p.copy(currentBidVorher = akt)
                        if (Duration.between(wartenAb, jetzt).toMillis() >= 2 * takt.currentBidWartenMs) {
                            zu("Current Bid not certain", "Current Bid not read the same twice (price in the row unreadable) – no bid")
                        } else {
                            Ergebnis.Warten("Price in the row was not readable – reading Current Bid a second time", takt.ruhigMs)
                        }
                    }
                    // Nov 23 (review round 3: "messages are told apart only by the size of their band"): any two-line band counted
                    // as the known "Bid Failure", and Bid was then tapped at its learned position UNDER the band. Now Bid is never
                    // tapped while a message band lies over the window (every band there covers the Bid button) – wait until it is
                    // gone; if it stays [Takt.fensterZuWartenMs], close the window with the red X (that is not under it). "-7",
                    // "Bidding successful" and unknown bands are handled above as before.
                    meldung != null ->
                        // Counted in THIS window only (a band from an earlier Bid window does not count).
                        if (bidMeldungSeit?.let { Duration.between(nachPause(maxOf(it, p.seit)), jetzt).toMillis() >= takt.fensterZuWartenMs } == true) {
                            zu("message over the Bid button", "A message has been lying over the Bid button for ${takt.fensterZuWartenMs / 1000} s – closing, no bid")
                        } else {
                            Ergebnis.Warten("Message over the Bid window – not tapping Bid under it, waiting until it is gone", takt.meldungWartenMs)
                        }
                    // Nov 11 (field test 1.20: "remember where the buttons are"): first screenshot of the window and the button
                    // is NOT at its learned position (still opening) – wait one screenshot.
                    !ortRuhig && bild.knopf(Knopf.BIETEN) != null && !anGelernterStelle(bild.knopf(Knopf.BIETEN)!!) ->
                        Ergebnis.Warten("Bid window is still opening – Bid button not at its position yet", takt.ruhigMs)
                    // Nov 9: only tap the "Bid" button if it was found on THIS screenshot (Nov 23: never at a learned position).
                    bild.knopf(Knopf.BIETEN) == null -> {
                        if (Duration.between(wartenAb, jetzt).toMillis() < takt.fensterWartenMs) {
                            Ergebnis.Unsicher("Bid window without a Bid button found – waiting", 300)
                        } else {
                            zu("Bid button not found", "Bid button not found in the Bid window – closing")
                        }
                    }
                    // Nov 23 (review round 3: "the bot assumes the bid field holds Current Bid + 1"): the field is editable – a tap
                    // on "+" or a quirk of the game could raise it, and "Bid" bids what is IN the field. Read on THIS screenshot:
                    //  - nothing at all readable on the first image of the window (normally the digits are there, as learned
                    //    digits or '?') – look once more on a steady screenshot before tapping without the check;
                    //  - higher than Current Bid + 1, above the rule's highest bid, or a learned digit that does not fit
                    //    ([bietFeldUrteil]) – never Bid and never correct it with + or −: on a steady screenshot the window is
                    //    closed with the red X;
                    //  - otherwise Bid, the log says what the field showed ([bietFeldPruefen]).
                    bild.bietFeldMuster == null && !ortRuhig ->
                        Ergebnis.Warten("Bid field not readable on this screenshot – looking again on a steady one", takt.ruhigMs)
                    bietFeldUrteil(bild.bietFeldMuster, naechstes, p.ziel.regel) == FeldUrteil.ZU_HOCH && !ortRuhig ->
                        Ergebnis.Warten("Bid field shows ${feldAnzeige(bild.bietFeldMuster)} instead of $naechstes – looking again on a steady screenshot", takt.ruhigMs)
                    bietFeldUrteil(bild.bietFeldMuster, naechstes, p.ziel.regel) == FeldUrteil.ZU_HOCH -> {
                        bietFeldFalsch++
                        zu(
                            "bid field ${feldAnzeige(bild.bietFeldMuster)}",
                            "Bid field shows ${feldAnzeige(bild.bietFeldMuster)} instead of $naechstes SG (Current Bid $akt, at most " +
                                "${p.ziel.regel.hoechstesGebot} SG) – not tapping Bid, closing the window (never correcting the field with + or −)",
                        )
                    }
                    else -> {
                        val feldText = bietFeldPruefen(bild.bietFeldMuster, akt, naechstes, p.ziel.regel)
                        phase = Phase.BietenGetippt(p.ziel.copy(preis = naechstes, currentBidGelesen = true, bietFeld = feldText), jetzt)
                        bidTipps++
                        // Nov 9: always only "Bid", never "Buyout Price".
                        tippe(bild, Knopf.BIETEN, "Bid for $naechstes SG ($feldText)", takt.nachBietenMs)
                    }
                }
            }
            // Nov 9: after "Bid" there is usually no confirm window – the game goes straight to the list.
            // Nov 11 (review: slow network): if the Bid window is still there afterwards, it may close in a moment
            // (the server answers late). Close only after [Takt.fensterZuWartenMs] and only on a steady screenshot.
            is Phase.BietenGetippt -> nachFensterTipp(p.seit, jetzt, "Bid tapped") {
                protokoll("Bid window still open ${takt.fensterZuWartenMs / 1000} s after tapping Bid – closing, bid does not count")
                ohneGebotSchliessen(bild, p.ziel, jetzt, if (gebremstNach(p.seit)) "Bid refused: \"too frequently\"" else "Bid had no effect")
            }
            // Nov 22 (buyout): never tap Buyout (or Bid) again – if the window stays open, close it like after Bid.
            is Phase.BuyoutGetippt -> nachFensterTipp(p.seit, jetzt, "Buyout tapped") {
                protokoll("Bid window still open ${takt.fensterZuWartenMs / 1000} s after Buyout – closing, buyout probably did not go through")
                ohneGebotSchliessen(bild, p.ziel, jetzt, "Buyout had no effect", zeileSperren = false)
            }
            // Nov 11 (cause of the "+" tap): right after "Confirm" a screenshot often still shows the Bid window
            // – it is closing BY ITSELF. Earlier the bot tapped the X here; when the tap arrived, the
            // window was gone and the "+" next to the Starry Gems was underneath. Now: wait long (see above).
            is Phase.Bestaetigt -> nachFensterTipp(p.seit, jetzt, "Confirm tapped") {
                phase = Phase.Suchen
                tippe(bild, Knopf.SCHLIESSEN, "close Bid window (still open after Confirm)", takt.nachTippenMs)
            }
            else -> {
                phase = Phase.Suchen
                val grund = if (bidNachKreuzSchliessen) "close Bid window (opened by the X in My Biddings – the list had moved)" else "close Bid window (no bid open)"
                bidNachKreuzSchliessen = false
                tippe(bild, Knopf.SCHLIESSEN, grund, takt.nachTippenMs)
            }
        }
    }

    /**
     * Nov 22 (owner): Bid window of a row opened for buyout. The "Buyout Price" button is tapped only if, on a steady
     * screenshot, the buyout price in the window reads the SAME as in the list, is at most the rule's buyout maximum, SG and
     * budget are enough, and the yellow button is recognized on THIS screenshot. Otherwise the window is closed (never Bid).
     */
    private fun buyoutFenster(bild: Bildschirm, p: Phase.BietenOffen, jetzt: Instant): Ergebnis {
        val ziel = p.ziel
        val erwartet = ziel.buyout ?: return ohneGebotSchliessen(bild, ziel, jetzt, "buyout without price", zeileSperren = false)
        fun schliessen(warum: String): Ergebnis {
            buyoutVersucht += ziel.id
            if (!ortRuhig) {
                phase = p.copy(schliessen = Schliessen("buyout: $warum", null, false))
                return Ergebnis.Warten("Let the Bid window settle first, then close it", takt.ruhigMs)
            }
            protokoll("Buyout ${ziel.id}: $warum – closing the window, no buyout")
            return ohneGebotSchliessen(bild, ziel, jetzt, "buyout: $warum", zeileSperren = false)
        }
        p.schliessen?.let { z -> return schliessen(z.warum.removePrefix("buyout: ")) }
        val max = ziel.regel.buyoutMax
        if (!buyoutAn || max == null) return schliessen("buyout is switched off")
        if (!ortRuhig) return Ergebnis.Warten("Bid window is opening – checking the buyout price on a steady screenshot", takt.ruhigMs)
        val gelesen = bild.sofortPreis
        val knopf = bild.knopf(Knopf.SOFORTKAUF)
        // A message over the window ("Bid Failure", "-7" …): something is going on – not now.
        if (gelesen != erwartet || knopf == null || bild.meldung != null) {
            if (Duration.between(p.seit, jetzt).toMillis() < takt.currentBidWartenMs) {
                return Ergebnis.Warten("Buyout price or button not certain yet – looking again", takt.ruhigMs)
            }
            return schliessen(
                when {
                    gelesen == null -> "buyout price not readable"
                    gelesen != erwartet -> "buyout price $gelesen, the list showed $erwartet"
                    knopf == null -> "Buyout button not recognized (gray?)"
                    else -> "a message lies over the window"
                },
            )
        }
        val sg = letzteSg
        when {
            gelesen > max -> return schliessen("buyout price $gelesen above the maximum $max")
            sg == null -> return schliessen("SG balance not readable")
            gelesen > sg -> return schliessen("only $sg SG available")
            budgetRest(sg)?.let { gelesen > it } == true -> return schliessen("SG budget")
        }
        buyoutVersucht += ziel.id
        phase = Phase.BuyoutGetippt(ziel, jetzt, sg)
        protokoll("Buyout: tapping \"Buyout Price\" for $gelesen SG (${ziel.id})")
        return Ergebnis.Handeln(Aktion.Tippen(knopf, "Buyout for $gelesen SG (${ziel.id})"), takt.nachBietenMs)
    }

    /**
     * From when a window should close after the tap at [seit]: if a loading screen came afterwards, only from its end.
     * Nov 22 (rate limit): likewise after a pause because of "too frequently" (see [nachPause]).
     */
    private fun nachLaden(seit: Instant): Instant = nachPause(ladenVorbeiUm?.takeIf { it.isAfter(seit) } ?: seit)

    /**
     * Nov 22 (rate limit): [seit], or the end of a pause because of a message of the game if that came later (a pause that is
     * still running lies in the future – then no waiting time has run out yet). The bot tapped nothing during the pause.
     */
    private fun nachPause(seit: Instant): Instant = (zuOftPauseBis ?: pauseVorbeiUm)?.takeIf { it.isAfter(seit) } ?: seit

    /**
     * Nov 23 (review): the unknown message in the Bid or confirm window has stayed [Takt.fensterZuWartenMs] after its pause –
     * then the window is closed with the red X (never a tap under the message). Toasts usually go away after 2–4 s.
     */
    private fun fremdeMeldungBleibt(jetzt: Instant): Boolean =
        bremsMeldungSeit?.let { Duration.between(nachPause(it), jetzt).toMillis() >= takt.fensterZuWartenMs } == true

    /** Nov 23 (review round 3): what the bid field says about the planned bid (Current Bid + 1), see [bietFeldUrteil]. */
    private enum class FeldUrteil { GEPRUEFT, PASST_TEILWEISE, UNLESBAR, NIEDRIGER, ZU_HOCH }

    /**
     * Nov 23 (review round 3): compares the bid field ([muster], digit by digit, '?' = digit not learned yet) with the planned
     * bid [naechstes] = Current Bid + 1. ZU_HOCH ("Bid" would or could bid more than planned) when the field has more digits,
     * when even its smallest possible value is above [naechstes] or the rule's highest bid, or when a learned digit differs and
     * it is not certain that the field is lower. Unlearned digits are counted as 0 (smallest) and 9 (largest).
     */
    private fun bietFeldUrteil(muster: String?, naechstes: Int, regel: Suchregel): FeldUrteil {
        if (muster == null) return FeldUrteil.UNLESBAR
        val soll = naechstes.toString()
        if (muster.length != soll.length) return if (muster.length > soll.length) FeldUrteil.ZU_HOCH else FeldUrteil.NIEDRIGER
        val min = muster.replace('?', '0').toLong()
        val max = muster.replace('?', '9').toLong()
        val passt = muster.indices.all { muster[it] == '?' || muster[it] == soll[it] }
        return when {
            min > naechstes || min > regel.hoechstesGebot -> FeldUrteil.ZU_HOCH
            passt -> if ('?' in muster) FeldUrteil.PASST_TEILWEISE else FeldUrteil.GEPRUEFT
            max < naechstes -> FeldUrteil.NIEDRIGER
            else -> FeldUrteil.ZU_HOCH
        }
    }

    /** The bid field for the log: "56 SG", or "\"5?\" (? = digit not learned yet)". */
    private fun feldAnzeige(muster: String?): String =
        if (muster == null) "?" else if ('?' in muster) "\"$muster\" (? = digit not learned yet)" else "$muster SG"

    /**
     * Nov 23 (review round 3): the bid field on the screenshot on which "Bid" is tapped (never [FeldUrteil.ZU_HOCH]) – text for
     * the log and the counters.
     *  - Current Bid + 1: checked.
     *  - Not readable, or only partly ("5?" fits 53): tap as before – "Current Bid" was checked (at most the rule's max
     *    price). The digits 2, 3, 6 and 7 of this font are not learned yet (about 2 of 3 prices); refusing would stop most bids
     *    (measured on the real Bid windows and in the long run, see DauerlaufTest). The crop goes into the learning images.
     *  - Lower than Current Bid + 1: tap as before – "Bid" can then never bid more than planned (maybe a first bid on a copy
     *    without bids; to be checked on a phone). Logged.
     */
    private fun bietFeldPruefen(muster: String?, akt: Int, naechstes: Int, regel: Suchregel): String =
        when (bietFeldUrteil(muster, naechstes, regel)) {
            FeldUrteil.GEPRUEFT -> {
                bietFeldGeprueft++
                "bid field $muster checked"
            }
            FeldUrteil.PASST_TEILWEISE -> {
                bietFeldUngeprueft++
                "bid field \"$muster\" fits, not fully readable"
            }
            FeldUrteil.NIEDRIGER -> {
                bietFeldUngeprueft++
                protokoll("Bid field shows ${feldAnzeige(muster)}, Current Bid $akt – lower than $naechstes SG: tapping Bid, it cannot bid more than planned")
                "bid field ${feldAnzeige(muster)}, lower"
            }
            else -> {
                bietFeldUngeprueft++
                "bid field not readable"
            }
        }

    /** Nov 23 (review round 3): Bid taps with the bid field read as Current Bid + 1 / not checked (unreadable or lower). */
    var bietFeldGeprueft: Int = 0
        private set
    var bietFeldUngeprueft: Int = 0
        private set

    /** Nov 23 (review round 3): Bid windows closed because the bid field showed more than Current Bid + 1. */
    var bietFeldFalsch: Int = 0
        private set

    /** Nov 11 (field test 1.20): is the Bid button at its learned position (window fully open)? */
    private fun anGelernterStelle(p: Punkt): Boolean {
        val g = bidKnopfGelernt ?: return false
        return abs(p.x - g.x) <= KNOPF_STELLE_PX && abs(p.y - g.y) <= KNOPF_STELLE_PX
    }

    /**
     * Nov 11 (review: slow network → X hit the "+"): after "Bid", "Confirm" or "Verify" ([seit]) the
     * window closes by itself as soon as the game answers. Until [Takt.fensterZuWartenMs] just wait; after that close it
     * on a steady screenshot ([schliessen], X); if it is still there after [Takt.fensterKlemmtMs]: stop, tap nothing more.
     */
    private fun nachFensterTipp(seit: Instant, jetzt: Instant, was: String, schliessen: () -> Ergebnis): Ergebnis {
        val ms = Duration.between(nachLaden(seit), jetzt).toMillis()
        if (ms >= takt.fensterKlemmtMs) {
            protokoll("$was – the window is still open after ${ms / 1000} s – stopping, tapping nothing more")
            return beenden(StoppGrund.FENSTER_KLEMMT)
        }
        if (ms < takt.fensterZuWartenMs || !ortRuhig) {
            return Ergebnis.Warten("$was – waiting until the window closes by itself", takt.meldungWartenMs)
        }
        return schliessen()
    }

    /**
     * Nov 11 (review): "Bidding successful" in the (closing) Bid window. If a bid was just in transit, it
     * arrived: remember it (if not done yet) and wait until the window closes by itself. Never tap.
     */
    private fun erfolgImFenster(jetzt: Instant): Ergebnis? {
        // Nov 11 (field test 1.20): because the bot now keeps bidding through messages, the message from the previous bid
        // may still be visible. Only a message that appeared AFTER the tap on Bid/Verify belongs to this bid.
        val seit = erfolgSeit
        when (val p = phase) {
            is Phase.BietenGetippt -> if (seit != null && !seit.isBefore(p.seit)) {
                gebotRegistrieren(p.ziel, p.ziel.preis, jetzt, " – \"Bidding successful\" message in the Bid window")
                phase = Phase.Bestaetigt(p.ziel, p.seit)
            } else {
                return null
            }
            is Phase.Passwort -> if (p.ziel != null && p.geprueftUm != null && seit != null && !seit.isBefore(p.geprueftUm)) {
                passwortGeklappt()
                gebotRegistrieren(p.ziel, p.ziel.preis, jetzt, " – after the secondary password, \"Bidding successful\" message")
                phase = Phase.Bestaetigt(p.ziel, p.geprueftUm)
            } else {
                return null
            }
            is Phase.Bestaetigt -> Unit
            else -> return null
        }
        return Ergebnis.Warten("\"Bidding successful\" – the Bid window closes by itself, tapping nothing", takt.meldungWartenMs)
    }

    /**
     * Short message in the Bid window: "Server status code:-7" = already 50 bids (Nov 8).
     *
     * Nov 9 (field test 1.8: a message after the secondary password counted as "50 bids", although My Biddings
     * showed 1 bid): not every short message is the limit. It is only believed if
     *  - My Biddings shows almost full (from 45 running bids), or
     *  - it is as wide as the real "-7" message ([Meldung.wieLimit]) AND the counter is not known
     *    or it comes again after a new attempt (new "Bid").
     * Otherwise: close the window with X, lock the row briefly and try once more.
     *
     * Nov 9 (field test 1.10/1.11): if it counts as the limit, the bot then (and ONLY then) opens My Biddings. If
     * the counter there shows clearly fewer than 50 and the message was not certain, it was a false alarm – keep bidding.
     */
    private fun kurzeMeldung(bild: Bildschirm, meldung: Meldung, jetzt: Instant): Ergebnis {
        val ziel = zielVon(phase)
        val zahl = gebote.spielZahl
        val fastVoll = zahl != null && gebote.laufend >= gebote.limit - LIMIT_PUFFER
        val verdacht = limitVerdacht
        val neuerVersuch = verdacht == null || bidTipps > verdacht.second
        val wieder = verdacht != null && bidTipps > verdacht.second &&
            Duration.between(verdacht.first, jetzt).toMillis() <= takt.limitVerdachtMs
        // Nov 11 (review): ONLY a message in the form of the real "-7" message counts as "-7".
        val sicher = meldung.wieLimit && (fastVoll || wieder)
        val echt = sicher || (meldung.wieLimit && zahl == null)
        phase = Phase.Suchen
        // Remember when/at which Bid the message came – if it comes again after a new attempt, it is certain.
        limitVerdacht = jetzt to bidTipps
        // Nov 11: when bidding again in My Biddings, try only once – after that the row is dismissed.
        if (ziel?.ausMeineGebote == true) {
            aufraeumen?.nichtNachbieten?.putIfAbsent(ziel.id, "bidding again did not work: message in the Bid window" + if (echt) " (-7)" else "")
        }
        if (echt) {
            gebote.limitMeldung(jetzt, sicher)
            protokoll("Game reports: 50 bids reached – cleaning up My Biddings (bid again on outbid ones or dismiss them)")
            // No 60 s lock for the row: after the waiting time (or a false alarm) exactly this row should be next again.
            return tippe(bild, Knopf.SCHLIESSEN, "close Bid window (limit)", takt.nachTippenMs)
        }
        if (neuerVersuch) {
            protokoll(
                "Short message in the Bid window (width ${meldung.breite}), but " +
                    (if (zahl != null) "My Biddings only shows ${gebote.laufend} bids" else "it does not look like \"-7\"") +
                    " – no limit. Trying again",
            )
            ziel?.let { sperren(it.id, jetzt, "message in the Bid window") }
        }
        return tippe(bild, Knopf.SCHLIESSEN, "close Bid window (message, no limit)", takt.nachTippenMs)
    }

    /**
     * Nov 22 (buyout): after "Buyout Price" a confirmation window came – its flow is not known yet. Tap NOTHING; mark this
     * screenshot as uncertain (the app saves it for the report) and stop at the next step.
     */
    private fun buyoutUnbekannt(bild: Bildschirm): Ergebnis? {
        val p = phase as? Phase.BuyoutGetippt ?: return null
        protokoll("Buyout ${p.ziel.id}: unknown window after \"Buyout Price\" (${bild.ort.text}) – tapping nothing, stopping")
        stoppNachBild = StoppGrund.BUYOUT_UNBEKANNT
        return Ergebnis.Unsicher("Buyout: unknown confirmation window – saving this screenshot, tapping nothing", takt.ruhigMs)
    }

    /**
     * Nov 9 (field test 1.8): NEVER "Cancel" in the confirm window ("Decline after Bid does nothing there").
     * If the amount really does not fit, the red X at the top right closes the window (then the X of the Bid window).
     */
    private fun bestaetigenFenster(bild: Bildschirm, jetzt: Instant): Ergebnis {
        buyoutUnbekannt(bild)?.let { return it }
        (phase as? Phase.Passwort)?.let { pw ->
            passwortGeklappt()
            pw.ziel?.let { phase = Phase.BietenGetippt(it, jetzt) }
        }
        // Just tapped "Confirm", the window is just not closed yet → wait, do NOT tap the X.
        // Nov 11 (review: slow network): wait long (see [Takt.fensterZuWartenMs]), stop after 20 s.
        (phase as? Phase.Bestaetigt)?.let { b ->
            val ms = Duration.between(nachLaden(b.seit), jetzt).toMillis()
            if (ms >= takt.fensterKlemmtMs) {
                protokoll("Confirm window is still open after ${ms / 1000} s – stopping, tapping nothing more")
                return beenden(StoppGrund.FENSTER_KLEMMT)
            }
            if (ms < takt.fensterZuWartenMs || !ortRuhig) {
                return Ergebnis.Warten("Confirm tapped – waiting until the window closes", takt.meldungWartenMs)
            }
        }
        val p = phase as? Phase.BietenGetippt
        if (p == null) {
            // Nov 11 (long run): just tapped the X ourselves – the window is still closing. Do not tap again
            // (otherwise every further attempt counts, and the bot stops with "window stuck" for no reason).
            if (phase == Phase.Suchen && gerade(xTippUm, jetzt)) {
                return Ergebnis.Warten("Confirm window is closing (X tapped) – waiting", takt.ruhigMs)
            }
            phase = Phase.Suchen
            return bestaetigenSchliessen(bild, "not mine")
        }
        // Nov 9 (field test 1.8: "confirm shows 8 SG" with a real 59): an amount that cannot be right
        // (fewer digits, below the minimum bid or far below the bid from the Bid window) counts as NOT readable.
        val gelesen = bild.bestaetigenPreis
        val betrag = gelesen?.takeIf { betragPlausibel(it, p.ziel) }
        if (gelesen != null && betrag == null) {
            protokoll("Amount in the confirm window misread ($gelesen SG, expected ${p.ziel.preis} SG) – counts as not readable")
        }
        val sg = letzteSg
        val rest = sg?.let { budgetRest(it) }
        if (betrag != null && (!p.ziel.regel.gebotErlaubt(betrag) || (sg != null && betrag > sg) || (rest != null && betrag > rest))) {
            protokoll("Confirm shows $betrag SG – does not fit (at most ${p.ziel.regel.hoechstesGebot} SG${sg?.let { ", $it SG available" } ?: ""}), closing with X")
            sperren(p.ziel.id, jetzt, "$betrag SG too expensive")
            if (p.ziel.ausMeineGebote) aufraeumen?.nichtNachbieten?.putIfAbsent(p.ziel.id, "$betrag too expensive")
            phase = Phase.Suchen
            return bestaetigenSchliessen(bild, "$betrag SG too expensive")
        }
        // Nov 11 (review): only trust the Bid window if "Current Bid" was readable there – otherwise never confirm
        // (close with the red X, never Cancel).
        if (betrag == null && !p.ziel.currentBidGelesen) {
            protokoll("Amount in the confirm window not readable and Current Bid was not either – not confirming, closing with X")
            sperren(p.ziel.id, jetzt, "amount not readable")
            if (p.ziel.ausMeineGebote) aufraeumen?.nichtNachbieten?.putIfAbsent(p.ziel.id, "amount not readable")
            phase = Phase.Suchen
            return bestaetigenSchliessen(bild, "amount not readable")
        }
        // Nov 23 (review): a message the bot does not know lies over the confirm window – NEVER tap Confirm then. Wait until it
        // is gone; if it stays, close the window with the red X (never Cancel).
        if (fremdImFenster(bild)) {
            if (!fremdeMeldungBleibt(jetzt)) {
                return Ergebnis.Warten("Unknown message over the confirm window – not tapping Confirm", takt.meldungWartenMs)
            }
            protokoll("Unknown message over the confirm window does not go away – not confirming, closing with X")
            sperren(p.ziel.id, jetzt, "unknown message")
            if (p.ziel.ausMeineGebote) aufraeumen?.nichtNachbieten?.putIfAbsent(p.ziel.id, "unknown message")
            phase = Phase.Suchen
            return bestaetigenSchliessen(bild, "unknown message")
        }
        val preis = betrag ?: p.ziel.preis
        if (betrag == null) protokoll("Amount in the confirm window not readable – trusting the Bid window ($preis SG)")
        val knopf = bild.knopf(Knopf.BESTAETIGEN) ?: return Ergebnis.Unsicher("Confirm button not found", takt.unbekanntMs)
        // [gebotRegistrieren] subtracts the SG right away by itself (Nov 12).
        gebotRegistrieren(p.ziel, preis, jetzt, "")
        phase = Phase.Bestaetigt(p.ziel.copy(preis = preis), jetzt)
        return Ergebnis.Handeln(Aktion.Tippen(knopf, "Confirm $preis SG"), takt.nachBestaetigenMs)
    }

    /**
     * Can the amount read in the confirm window be right? The bid from the Bid window is expected.
     * More than expected is possible (someone bid in the meantime) – the upper limit checks that afterwards.
     */
    private fun betragPlausibel(betrag: Int, ziel: Ziel): Boolean =
        betrag.toString().length >= ziel.preis.toString().length &&
            betrag >= ziel.regel.minPreis + GEBOTS_SCHRITT &&
            betrag >= ziel.preis - BETRAG_TOLERANZ

    /** Close the confirm window with the red X – never with "Cancel". If it is stuck, the bot stops. */
    private fun bestaetigenSchliessen(bild: Bildschirm, warum: String): Ergebnis {
        bestaetigenXInFolge++
        if (bestaetigenXInFolge > MAX_X_VERSUCHE) {
            protokoll("Confirm window does not close with X – bot stops so it does not tap anything wrong")
            return beenden(StoppGrund.FENSTER_KLEMMT)
        }
        return tippe(bild, Knopf.SCHLIESSEN, "close confirm window with X ($warum)", takt.nachTippenMs)
    }

    /** Close the Bid window without a bid using the red X and lock the row briefly (no back and forth). */
    private fun ohneGebotSchliessen(bild: Bildschirm, ziel: Ziel, jetzt: Instant, warum: String, zeileSperren: Boolean = true): Ergebnis {
        phase = Phase.Suchen
        if (zeileSperren) sperren(ziel.id, jetzt, warum)
        if (ziel.ausMeineGebote) aufraeumen?.nichtNachbieten?.putIfAbsent(ziel.id, warum)
        return tippe(bild, Knopf.SCHLIESSEN, "close Bid window ($warum)", takt.nachTippenMs)
    }

    /** Nov 9 (field test 1.8): do not open this row again for [Takt.zeileSperrenMs]. */
    private fun sperren(id: String, jetzt: Instant, warum: String) {
        gesperrtBis[id] = jetzt.plusMillis(takt.zeileSperrenMs)
        protokoll("Row $id: not opening again for ${takt.zeileSperrenMs / 1000} s ($warum)")
    }

    private fun zielVon(p: Phase): Ziel? = when (p) {
        is Phase.BietenOffen -> p.ziel
        is Phase.BietenGetippt -> p.ziel
        is Phase.Passwort -> p.ziel
        is Phase.Bestaetigt -> p.ziel
        else -> null
    }

    /** A bid was placed: remember it, log the time, and have the row checked (clock?). */
    private fun gebotRegistrieren(ziel: Ziel, preis: Int, jetzt: Instant, wie: String) {
        val warNeu = gebote.gebot(ziel.id) == null
        letztesGebotUm = jetzt
        // Nov 12 (review: budget exceeded): the SG display at the top right only shows the deduction ~2 s later. Since
        // the bot keeps bidding through "Bidding successful" (instead of waiting 3–4 s), it kept bidding with the old
        // SG balance during that time – with budget 30 and bids of 16, 2 bids went through (32 SG). So: subtract the bid
        // ourselves right away (on EVERY bid, also without a confirm window) – higher displayed values only count after [SG_ANZEIGE_NACH_GEBOT_MS].
        val vorher = sgBeiGebotStart
        // Nov 12 (review before 1.34): only if the display dropped by EXACTLY this bid, it has already been subtracted.
        // In every unclear case (e.g. another bid was deducted at the same time) subtract – better assume too little SG
        // for a moment than too much (otherwise the bot could bid over the budget for 2–3 s).
        letzteSg = letzteSg?.let { if (vorher != null && it == vorher - preis) it else it - preis }
        sgBeiGebotStart = null
        sgGleichSeit = jetzt
        gebote.gebotAbgegeben(ziel.id, ziel.regel.kopie, preis, ziel.restzeitSek, jetzt)
        gebotenInDurchlauf = true
        pruefung = Pruefung(ziel.id, jetzt, warNeu, hoechstens = ziel.regel.hoechstesGebot)
        // Nov 22 (rate limit): a bid went through – the "too frequently" count starts over.
        zuOftInFolge = 0
        protokoll(
            (if (ziel.nachbieten) "Re-bid placed" else "Bid placed") + ": ${ziel.regel.kopie} for $preis SG (${ziel.id})$wie" +
                (ziel.bietFeld?.let { " · $it" } ?: "") + (gebotStart?.let { " · ⏱ ${dauer(it, jetzt)}" } ?: ""),
        )
        gebotStart = null
        // Nov 11: bid again while cleaning up My Biddings.
        if (ziel.ausMeineGebote) aufraeumen?.let { a ->
            protokoll("My Biddings: outbid at ${a.nachbieten?.second ?: "?"} → bid again with $preis")
            a.nachbieten = null
            a.fertig += ziel.id
            a.nachgeboten++
            a.amEnde = false // the list may have moved meanwhile – check the end again
        }
    }

    /**
     * Nov 9: did the last bid arrive? On the row: clock = yes (I am in front), hammer = yes, but outbid right
     * away, no icon (after [Takt.gebotOhneZeichenMs]) = no → does not count. No longer visible → leave it open.
     */
    private fun pruefeLetztesGebot(freieZeilen: List<Zeile>, jetzt: Instant) {
        val pr = pruefung ?: return
        val seit = Duration.between(pr.seit, jetzt).toMillis()
        val z = freieZeilen.firstOrNull { it.id == pr.id }
        when {
            // Nov 22 (review): our clock on the row, but the row's price is above the rule's highest bid (max price + 1) –
            // then a bid went higher than allowed (e.g. "Current Bid" misread). Read the same on two screenshots → stop
            // loudly (report), never bid on. A single misread price only waits for the next screenshot.
            z != null && z.zeichen == Zeichen.UHR && z.preis != null && z.preis > pr.hoechstens -> {
                if (pr.zuHoch == z.preis) {
                    protokoll(
                        "SAFETY: our bid on ${pr.id} shows ${z.preis} SG – above the rule's highest bid ${pr.hoechstens} SG. " +
                            "Stopping – please save a report",
                    )
                    pruefung = null
                    stoppNachBild = StoppGrund.GEBOT_ZU_HOCH
                } else {
                    pruefung = pr.copy(zuHoch = z.preis)
                }
            }
            z != null && z.zeichen == Zeichen.UHR -> {
                protokoll("Bid arrived: clock on the row (${pr.id})")
                pruefung = null
            }
            z != null && z.zeichen == Zeichen.HAMMER -> {
                protokoll("Bid ${pr.id} arrived, but outbid right away (hammer)")
                pruefung = null
            }
            // Nov 11 (long run, review): a single screenshot without a recognized icon is not enough – the icon is sometimes
            // not recognized. Only on two screenshots without an icon does the bid count as not arrived (otherwise a bid
            // where I am in front would drop out of the bookkeeping, and the bot would later outbid itself).
            z != null && z.hatRestzeit && seit >= takt.gebotOhneZeichenMs && pr.ohneZeichen < 1 -> {
                pruefung = pr.copy(ohneZeichen = pr.ohneZeichen + 1)
            }
            z != null && z.hatRestzeit && seit >= takt.gebotOhneZeichenMs -> {
                protokoll(
                    if (z.ueberbotenKreuz) "Bid ${pr.id} still shows the red X in My Biddings – does not count"
                    else "Bid ${pr.id} did NOT arrive (no icon on the row) – does not count",
                )
                gebote.gebotNichtAngekommen(pr.id, pr.warNeu)
                sperren(pr.id, jetzt, "bid did not arrive")
                pruefung = null
            }
            seit > takt.gebotPruefenMs -> pruefung = null
        }
    }

    /** Duration as text, e.g. "3,4 s" (German decimal comma; for timing in the log). */
    private fun dauer(von: Instant, bis: Instant): String =
        String.format(java.util.Locale.GERMANY, "%.1f s", Duration.between(von, bis).toMillis() / 1000.0)

    // ---------------- Secondary password (Nov 9) ----------------

    private fun zweitpasswort(bild: Bildschirm, jetzt: Instant): Ergebnis {
        buyoutUnbekannt(bild)?.let { return it }
        if (!hatZweitpasswort) {
            passwortFehlt = true
            // Nov 11 (review): do not wait forever – stop after 5 minutes (lock phone, report).
            val seit = passwortFehltSeit ?: jetzt.also { passwortFehltSeit = it }
            if (Duration.between(seit, jetzt).toMillis() >= takt.passwortFehltStoppMs) return beenden(StoppGrund.ZWEITPASSWORT_FEHLT)
            return Ergebnis.Warten("Please enter the secondary password in the settings", 5000)
        }
        passwortFehltSeit = null
        val p = phase as? Phase.Passwort
        when {
            p == null -> {
                // Just tapped "Bid" → enter the password. Remember the target so the bot can continue bidding afterwards.
                val ziel = when (val alt = phase) {
                    is Phase.BietenGetippt -> alt.ziel
                    is Phase.BietenOffen -> alt.ziel
                    else -> null
                }
                eingabe = null
                phase = Phase.Passwort(ziel, eingegeben = true, geprueftUm = null)
                val feld = bild.knopf(Knopf.PASSWORT_FELD) ?: return Ergebnis.Unsicher("Password field not found", takt.unbekanntMs)
                return Ergebnis.Handeln(Aktion.PasswortEingeben(feld, "enter secondary password"), takt.nachEingabeMs)
            }
            p.geprueftUm == null -> {
                val ok = eingabe == true
                eingabe = null
                if (!ok) return beenden(StoppGrund.ZWEITPASSWORT_EINGABE)
                phase = p.copy(geprueftUm = jetzt)
                verifyUm = jetzt
                // Never "Forgot Password" – only "Verify".
                return tippe(bild, Knopf.PASSWORT_OK, "Verify", takt.nachTippenMs)
            }
            Duration.between(p.geprueftUm, jetzt).toMillis() < takt.passwortPruefenMs ->
                return Ergebnis.Warten("waiting for the secondary password to be checked", 400)
            // The window is (still) there again after "Verify" → wrong. Do not try again.
            else -> return beenden(StoppGrund.ZWEITPASSWORT_FALSCH)
        }
    }

    private fun passwortGeklappt() {
        if (!passwortBestaetigt) protokoll("Secondary password accepted")
        passwortBestaetigt = true
    }

    // ---------------- My Biddings ----------------

    /**
     * My Biddings. The bot only comes here after "-7" (Nov 9) or at about 50 by its own count (Nov 11)
     * and then cleans up – see [Aufraeumen]. If it is here for another reason (e.g. opened by hand), it only reads
     * and goes back.
     */
    private fun meineGebote(bild: Bildschirm, jetzt: Instant): Ergebnis {
        // Back from the Bid window (bidding again while cleaning up)? First evaluate what the change of window means.
        zurueckInDerListe(jetzt)?.let { return it }
        aufraeumen?.let { a ->
            val nb = a.nachbieten
            if (nb != null && zielVon(phase) == null) {
                // Back in My Biddings, but no bid placed → this row gets dismissed.
                a.nachbieten = null
                a.amEnde = false
                a.nichtNachbieten.putIfAbsent(nb.first, "bidding again did not work")
                protokoll("My Biddings: bidding again on ${nb.first} did not work (${a.nichtNachbieten[nb.first]})")
            }
        }
        if (bild.meldung != null) {
            // Nov 11 (review): never wait forever.
            val seit = meineMeldungSeit ?: jetzt.also { meineMeldungSeit = it }
            if (Duration.between(seit, jetzt).toMillis() >= takt.meldungStoppMs) {
                protokoll("My Biddings: a message has not gone away for ${takt.meldungStoppMs / 60_000} minutes – stopping")
                return beenden(StoppGrund.MELDUNG_HAENGT)
            }
            return Ergebnis.Warten("Message on screen – waiting briefly", takt.meldungMs)
        }
        meineMeldungSeit = null
        val a0 = aufraeumen
        val zuerst = a0 == null || a0.ersterBlick
        // Nov 9 (field test: "? bids running"): if "Bidding Items: N" is not readable, but the list is not full
        // (fewer rows than fit), the visible rows are ALL the bids. Otherwise the bot counts by itself.
        val gelesen = bild.laufendeGebote
        // Nov 11 (long run: "3 bids" instead of 50): fewer rows than fit only means "that is all of them"
        // if the list is at the very top and nothing lies above it – further down often only 3 rows are fully visible.
        val ganzOben = bild.zeilen.firstOrNull()?.let { kotlin.math.abs(it.oben - MEINE_ERSTE_OBEN) <= 10 } == true &&
            bild.meldung == null && bild.zeilen.none { it.verdeckt }
        val zahl = gelesen ?: bild.zeilen.size.takeIf { ganzOben && it in 1 until MEINE_GEBOTE_SICHTBAR }
        if (zuerst) {
            bild.zeilen.size.takeIf { gelesen == null }?.let { n ->
                protokoll(
                    "My Biddings: counter not readable – " +
                        if (zahl != null) "counting the rows: $n" else "counting by myself (${gebote.offeneGebote()} own bids)",
                )
            }
        }
        gebote.meineGeboteGelesen(zahl, bild.zeilen, jetzt)
        bild.sg?.let { sgGelesen(it, jetzt) }
        pruefeLetztesGebot(bild.zeilen.filter { !it.verdeckt }, jetzt)
        stoppNachBild?.let { return beenden(it) }
        val stand = "My Biddings: ${zahl ?: "?"} bids running" + (bild.sg?.let { " · $it SG" } ?: "")

        // Nov 11: after "-7" the bot cleans up (even if it is already here).
        val a = a0 ?: if (gebote.limitGemeldet && gebote.wiederBietenAb == null) Aufraeumen(jetzt).also { aufraeumen = it } else null
        if (a == null) {
            // Nov 23 (review round 3): wait for the gap between two reloads first (then the line is logged only once).
            navigationZuFrueh(jetzt, "Back to the Bidding Market")?.let { return it }
            protokoll(stand)
            return zurueckZumMarkt(bild, jetzt)
        }
        // Nov 9: if the counter shows fewer than 50 and "-7" was not certain, it was a false alarm.
        // Nov 11: the same if the bot is only here because of its own count – then there is still room.
        // Nov 12 (rule): "If it says 49 or less there, it can go back to the auction house and keep bidding. If
        // it says 50, it must first wait until others run out or I get outbid." So: counter below 50 → no
        // cleanup, go straight back (before, it already cleaned up from 45 – 1.5–2 minutes every few bids). Our own count
        // is at the value read afterwards; so the next look only comes once bids were placed again (no loop).
        if (a.aktionen == 0 && zahl != null && zahl < gebote.limit && (a.durchZaehlung || !gebote.limitSicher)) {
            val warLimit = gebote.limitGemeldet
            gebote.limitAufheben()
            aufraeumen = null
            naechsterDurchgangAb = null
            protokoll(
                "$stand – " + if (warLimit) "that was not the 50 limit, bidding on" else "still room, no cleanup needed – bidding on",
            )
            return zurueckZumMarkt(bild, jetzt)
        }
        if (a.ersterBlick) {
            a.ersterBlick = false
            protokoll("$stand – cleaning up: bid again on outbid ones (red X) or dismiss them")
        }
        return aufraeumenSchritt(bild, a, jetzt)
    }

    /**
     * Nov 11: one step of cleaning up. The bot only taps when the list stands still (two screenshots with the same
     * rows) – and the X only on a row that was recognized with an X on EXACTLY this screenshot.
     */
    private fun aufraeumenSchritt(bild: Bildschirm, a: Aufraeumen, jetzt: Instant): Ergebnis {
        val zeilen = bild.zeilen.filter { !it.verdeckt }
        for (z in zeilen) {
            val id = z.id ?: continue
            a.gesehen += id
            val sek = z.restzeitSek ?: continue
            if (z.hatRestzeit && sek > 0) a.enden[id] = jetzt.plusSeconds(sek.toLong())
        }
        if (Duration.between(a.start, jetzt).toMillis() > takt.aufraeumenMaxMs) {
            protokoll("My Biddings: cleanup takes too long – stopping here")
            return aufraeumenFertig(bild, jetzt)
        }
        val sig = MeineSicht(zeilen.map { "${it.id ?: "?"}:" + if (it.ueberbotenKreuz) "X" else it.zeichen.name }, zeilen.map { it.oben })
        val vorige = a.vorige
        a.vorige = sig
        if (!sig.stehtGleich(vorige)) {
            a.wackeltSeit = null
            return Ergebnis.Warten("My Biddings: waiting until the list stands still", takt.ruhigPollMs)
        }
        if (!sig.gleich(vorige)) {
            // The rows stand still, but a number is read one way, then another: wait briefly, then carry on anyway.
            val seit = a.wackeltSeit ?: jetzt.also { a.wackeltSeit = it }
            if (Duration.between(seit, jetzt).toMillis() < takt.ruhigWartenMaxMs) {
                return Ergebnis.Warten("My Biddings: rows not reliably read yet", takt.ruhigPollMs)
            }
        }
        a.wackeltSeit = null
        if (zeilen.isEmpty() && !bild.keineGebote) {
            val seit = a.leerSeit ?: jetzt.also { a.leerSeit = it }
            if (Duration.between(seit, jetzt).toMillis() < takt.leerWartenMs) {
                return Ergebnis.Warten("My Biddings: list is still loading", takt.ruhigPollMs)
            }
        } else {
            a.leerSeit = null
        }

        a.weg?.let { k -> kreuzPruefen(zeilen, sig, a, k, jetzt, zaehlerGelesen = bild.laufendeGebote != null)?.let { return it } }
        a.wischen?.let { w ->
            a.wischen = null
            meineWischenPruefen(bild, zeilen, sig, a, w)?.let { return it }
        }
        // First to the start of the list (the whole list should get its turn).
        if (!a.obenGeprueft) {
            if (zeilen.isEmpty()) a.obenGeprueft = true else return meineNachOben(bild, zeilen, sig, a)
        }
        // From top to bottom: the first outbid row that has not had its turn in this pass yet.
        for (z in zeilen) {
            if (!z.ueberbotenKreuz) continue
            val key = schluessel(z)
            if (key in a.fertig) continue
            if ((a.kreuzVersuche[key] ?: 0) >= MAX_KREUZ_VERSUCHE) continue
            // Nov 11 (long run: wrong row opened): if an auction at or above this row is about to end (also
            // one that is just above the screen – our own bookkeeping knows that), it disappears and everything
            // below it moves up – the tap would hit a different copy. Wait briefly.
            // Nov 11 (review): also with 0 s or an unreadable remaining time at or above the row (it may disappear
            // in a moment) – but at most for [Takt.endetGleichWartenMaxMs], so that a display that stays put blocks nothing.
            val sicherGleich = gebote.endetGleich(jetzt) ||
                zeilen.any { it.oben <= z.oben && it.hatRestzeit && (it.restzeitSek ?: Int.MAX_VALUE) in 1..ENDET_GLEICH_SEK }
            val vielleichtGleich = zeilen.any { it.oben <= z.oben && it.hatRestzeit && (it.restzeitSek == null || it.restzeitSek <= 0) }
            if (sicherGleich) {
                // Surely ends in a few seconds – that resolves itself by waiting.
                a.endetGleichSeit = null
                a.vorige = null
                return Ergebnis.Warten("My Biddings: an auction is about to end – the list will move up, waiting briefly", 1000)
            }
            if (!vielleichtGleich) {
                a.endetGleichSeit = null
            } else {
                val seit = a.endetGleichSeit ?: jetzt.also { a.endetGleichSeit = it }
                if (Duration.between(seit, jetzt).toMillis() < takt.endetGleichWartenMaxMs) {
                    a.vorige = null
                    return Ergebnis.Warten("My Biddings: an auction may be about to end (0 s or unreadable) – waiting briefly", 1000)
                }
            }
            return meineZeile(bild, z, key, sig, a, jetzt)
        }
        if (a.amEnde || zeilen.isEmpty()) return aufraeumenFertig(bild, jetzt)
        if (a.wischer >= MAX_MEINE_WISCHER) {
            protokoll("My Biddings: end of list not reached after $MAX_MEINE_WISCHER swipes – stopping here")
            return aufraeumenFertig(bild, jetzt)
        }
        return meineWeiter(bild, zeilen, sig, a)
    }

    /** How a row in My Biddings is recognized again (without readable values: by its position). */
    private fun schluessel(z: Zeile): String = z.id ?: "row@${z.oben}"

    /**
     * Nov 11: which rule applies to a row in My Biddings? From our own bookkeeping (which copy type
     * the bot bid on). null = no matching rule.
     * Nov 11 (review): if the bot does not know the copy (e.g. after a restart), it does NOT bid again – from the
     * badge it only recognizes the letter (B), not whether it is B-, B or B+. Outbid → only press the X.
     */
    private fun regelFuerMeineZeile(z: Zeile): Suchregel? {
        val kopie = z.id?.let { gebote.gebot(it)?.kopie } ?: return null
        return aktiveRegeln.firstOrNull { it.kopie == kopie }
    }

    /** Nov 11: outbid row – bid again if everything fits, otherwise press the red X. */
    private fun meineZeile(bild: Bildschirm, z: Zeile, key: String, sig: MeineSicht, a: Aufraeumen, jetzt: Instant): Ergebnis {
        val id = z.id
        val preis = z.preis
        val gebot = preis?.plus(GEBOTS_SCHRITT)
        val regel = regelFuerMeineZeile(z)
        val sg = letzteSg
        val warum: String? = when {
            id == null -> "copy not readable"
            a.nichtNachbieten.containsKey(id) -> a.nichtNachbieten.getValue(id)
            regel == null -> "no matching rule (copy not from our own bookkeeping)"
            preis == null || gebot == null -> "price not readable"
            !z.hatRestzeit -> "auction over"
            // Nov 11 (review): never bid again without a readable remaining time (end unknown).
            z.restzeitSek == null -> "remaining time not readable"
            preis > regel.maxPreis -> "$gebot too expensive, at most ${regel.hoechstesGebot}"
            !regel.erlaubt(preis.toDouble()) -> "price $preis below the filter ${regel.minPreis}"
            !regel.zeitPasst(z.restzeitSek) ->
                z.restzeitSek?.let { "remaining time ${dauerText(it)}, rule: only last ${regel.maxRestzeitMin} min" } ?: "remaining time not readable"
            sg == null -> "SG balance not readable"
            gebot > sg -> "only $sg SG available"
            budgetRest(sg)?.let { gebot > it } == true -> "SG budget"
            stopp.zielKopien?.takeIf { it > 0 }?.let { gebote.gekaufteKopien + gebote.vorneOffen() >= it } == true -> "goal planned"
            else -> null
        }
        a.aktionen++
        a.vorige = null
        if (warum == null && id != null && regel != null && preis != null && gebot != null) {
            a.nachbieten = id to preis
            phase = Phase.BietenOffen(
                Ziel(
                    id, regel, gebot, z.restzeitSek, nachbieten = true, ausMeineGebote = true,
                    endeUm = z.restzeitSek?.let { jetzt.plusSeconds(it.toLong()) },
                ),
                jetzt,
            )
            gebotStart = jetzt
            sgBeiGebotStart = letzteSg
            return Ergebnis.Handeln(
                Aktion.Tippen(z.tippen, "re-bid in My Biddings: ${regel.kopie} for $gebot SG (${z.text()})"),
                takt.nachZeileMs,
            )
        }
        // Nov 12 (field test 1.33: "the X does not work" – all three rows were shortly before or after the end of their
        // auction): over or about to end → no X, the row goes away by itself.
        val endeUm = id?.let { gebote.gebot(it)?.endeUm ?: gebote.beendetUm(it) ?: a.enden[it] }
        // Nov 12 (review before 1.35): if the remaining time of the row is readable, ONLY it counts. Our own bookkeeping (ends
        // remembered for up to 1 h) only if the time is not readable – otherwise a wrongly remembered end could block the X of an
        // outbid row for up to an hour.
        val endetGleich = !z.hatRestzeit || (
            z.restzeitSek?.let { it <= KREUZ_ENDE_SEK }
                ?: (endeUm != null && !endeUm.isAfter(jetzt.plusSeconds(KREUZ_ENDE_SEK.toLong())))
            )
        if (endetGleich) {
            a.fertig += key
            protokoll("My Biddings: ${z.text()} is over or about to end – no X (the row goes away by itself)")
            return Ergebnis.Warten("Auction ending – no X", takt.ruhigPollMs)
        }
        val punkt = z.kreuzPunkt
        if (punkt == null) {
            a.fertig += key
            protokoll("My Biddings: red X at ${z.text()} not found exactly – leaving the row")
            return Ergebnis.Warten("red X not found exactly", takt.ruhigPollMs)
        }
        a.kreuzVersuche[key] = (a.kreuzVersuche[key] ?: 0) + 1
        a.weg = KreuzTipp(key, id, sig, jetzt, "outbid at ${preis ?: "?"} → dismissed ($warum) · ${id ?: "?"}")
        return Ergebnis.Handeln(Aktion.Tippen(punkt, "press red X in My Biddings (${z.text()}: $warum)"), takt.nachKreuzMs)
    }

    /** Nov 11: after the X – is the row gone? null = yes (or given up), continue the flow. */
    private fun kreuzPruefen(
        zeilen: List<Zeile>,
        sig: MeineSicht,
        a: Aufraeumen,
        k: KreuzTipp,
        jetzt: Instant,
        zaehlerGelesen: Boolean,
    ): Ergebnis? {
        // Nov 11 (test against the fake game): if several rows are missing after the X, usually an auction ended at the
        // same time – that is NOT a foreign window (before, the bot wrongly stopped here). The bot recognizes a window over
        // My Biddings by the screenshot no longer being My Biddings (then it stops, see schritt).
        if (sig.oben.size <= k.vorher.oben.size - 2) {
            protokoll("My Biddings: after the X ${k.vorher.oben.size - sig.oben.size} rows fewer (auction over?)")
        }
        val nochDa = if (k.id != null) zeilen.any { it.id == k.id && it.ueberbotenKreuz } else sig.gleich(k.vorher)
        if (!nochDa) {
            a.weg = null
            a.weggedrueckt++
            // If "Bidding Items" was read on this screenshot, the number is already right (do not subtract twice).
            gebote.weggedrueckt(k.id, zaehlen = !zaehlerGelesen)
            k.id?.let {
                a.fertig += it
                a.weggedrueckteIds += it
            }
            protokoll("My Biddings: ${k.text}")
            return null
        }
        if (Duration.between(k.um, jetzt).toMillis() < takt.kreuzWartenMs) {
            return Ergebnis.Warten("X pressed – waiting until the row is gone", takt.ruhigPollMs)
        }
        a.weg = null
        if ((a.kreuzVersuche[k.schluessel] ?: 0) >= MAX_KREUZ_VERSUCHE) {
            a.fertig += k.schluessel
            protokoll("My Biddings: the X does not work (${k.id ?: "?"}) – leaving the row")
        } else {
            protokoll("My Biddings: row ${k.id ?: "?"} is still there after the X – again")
        }
        return null
    }

    /** Nov 11: drag the list in My Biddings 3 rows down (back towards the start). */
    private fun meineNachOben(bild: Bildschirm, zeilen: List<Zeile>, sig: MeineSicht, a: Aufraeumen): Ergebnis {
        a.nachObenWischer++
        a.aktionen++
        a.vorige = null
        val weg = 3 * ZEILEN_ABSTAND
        a.wischen = MeineWischen(
            vorher = sig, vorherOben = emptyMap(), ersteId = zeilen.first().id, ersteOben = zeilen.first().oben,
            letzteId = null, soll = weg, nachOben = true,
        )
        return meineWischen(bild, MEINE_NACH_OBEN_START_Y, MEINE_NACH_OBEN_START_Y + weg, "push the list in My Biddings up (look for the start)")
    }

    /**
     * Nov 11: push the list so far that the last fully visible row is at the very top afterwards – it is
     * then visible once more (that way the bot notices that no row was skipped).
     */
    private fun meineWeiter(bild: Bildschirm, zeilen: List<Zeile>, sig: MeineSicht, a: Aufraeumen): Ergebnis {
        val letzte = zeilen.last()
        // Nov 12 (field test 1.33): with [MEINE_RAND_PX] of leeway and the ratio learned in the bidding list
        // (the same list in the game – it moves 1.14 to 1.3 times as far as the finger drags).
        val weg = (letzte.oben - MEINE_ERSTE_OBEN - MEINE_RAND_PX).coerceIn(ZEILEN_ABSTAND - MEINE_RAND_PX, 3 * ZEILEN_ABSTAND)
        a.wischer++
        a.aktionen++
        a.vorige = null
        a.wischen = MeineWischen(
            vorher = sig, vorherOben = zeilen.mapNotNull { z -> z.id?.let { it to z.oben } }.toMap(),
            ersteId = zeilen.first().id, ersteOben = zeilen.first().oben, letzteId = letzte.id, soll = weg, nachOben = false,
        )
        return listeWischen(bild, fingerweg(weg.toDouble()), "push the list in My Biddings further", uhr).first
    }

    private fun meineWischen(bild: Bildschirm, vonY: Int, nachY: Int, grund: String): Ergebnis {
        val weg = kotlin.math.abs(nachY - vonY)
        val dauer = (weg.toLong() * takt.wischDauerMs / (5 * ZEILEN_ABSTAND)).coerceAtLeast(300)
        return Ergebnis.Handeln(
            Aktion.Wischen(
                bild.ausReferenz(Punkt(WISCH_X, vonY)), bild.ausReferenz(Punkt(WISCH_X, nachY)), dauer, grund,
                haltenMs = takt.wischHaltenMs,
            ),
            dauer + takt.wischHaltenMs + takt.nachWischenMs,
        )
    }

    /** Nov 11: after swiping in My Biddings (the list already stands still). null = continue the flow. */
    private fun meineWischenPruefen(bild: Bildschirm, zeilen: List<Zeile>, sig: MeineSicht, a: Aufraeumen, w: MeineWischen): Ergebnis? {
        if (w.nachOben) {
            val erste = zeilen.firstOrNull()
            val nichtBewegt = sig.gleich(w.vorher) ||
                (erste?.id != null && erste.id == w.ersteId && w.ersteOben != null && kotlin.math.abs(erste.oben - w.ersteOben) <= STILL_TOLERANZ_PX)
            if (nichtBewegt) {
                a.obenGeprueft = true
                if (a.nachObenWischer > 1) protokoll("My Biddings: list is now at the very top")
                return null
            }
            if (a.nachObenWischer >= MAX_NACH_OBEN_WISCHER) {
                a.obenGeprueft = true
                protokoll("My Biddings: start of the list not found – starting here")
                return null
            }
            return meineNachOben(bild, zeilen, sig, a)
        }
        val ids = zeilen.mapNotNull { z -> z.id?.let { it to z.oben } }.toMap()
        if (w.zurueck) {
            if (w.letzteId != null && w.letzteId !in ids) protokoll("My Biddings: connection not found even after pushing back – carrying on")
            return null
        }
        if (sig.gleich(w.vorher)) {
            a.amEnde = true // list did not move → end reached
            return null
        }
        if (w.letzteId != null && w.letzteId !in ids) {
            // Slid further than expected: the last row of the previous page is gone → one row back.
            protokoll("My Biddings: list slid further than expected – pushing one row back")
            a.aktionen++
            a.vorige = null
            a.wischen = MeineWischen(sig, ids, zeilen.firstOrNull()?.id, zeilen.firstOrNull()?.oben, w.letzteId, 0, nachOben = false, zurueck = true)
            return listeWischen(bild, fingerweg(-ZEILEN_ABSTAND.toDouble()), "push the list in My Biddings one row back", uhr).first
        }
        val doppelt = ids.keys.filter { it in w.vorherOben }
        val bewegt = doppelt.map { w.vorherOben.getValue(it) - ids.getValue(it) }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        if (bewegt != null && bewegt < w.soll - ZEILEN_ABSTAND / 3) a.amEnde = true
        return null
    }

    /**
     * Nov 11: the whole list is done. If there is room afterwards (counter below 50), keep bidding right away.
     * Otherwise as before: do not bid until the earliest end of a bid (+5 s, at most 5 minutes).
     */
    private fun aufraeumenFertig(bild: Bildschirm, jetzt: Instant): Ergebnis {
        val a = aufraeumen ?: return zurueckZumMarkt(bild, jetzt)
        aufraeumen = null
        // Counter not readable, but the whole list was seen → the rows seen count.
        val ganz = a.amEnde || bild.zeilen.isEmpty()
        if (gebote.spielZahl == null && ganz && a.gesehen.isNotEmpty()) {
            gebote.eintraegeGezaehlt((a.gesehen - a.weggedrueckteIds).size)
        }
        val text = "My Biddings done: ${a.nachgeboten} bid again, ${a.weggedrueckt} dismissed · approx. ${gebote.eintraege}/${gebote.limit}"
        // Nov 11 (test against the fake game): whether there is room is told by the counter AFTER cleaning up ("Bidding Items",
        // otherwise our own count) – a pressed X alone is not enough if it stays full. After "-7" without a
        // single X it counts as full, no matter what the counter says (otherwise "-7" → cleanup → "-7" … in a circle).
        val voll = gebote.eintraege >= gebote.limit || (!a.durchZaehlung && a.weggedrueckt == 0)
        // At most ONE pass, then back. If there is still room afterwards, the next one may come right away when it is full
        // again; if it stays full, at the earliest in about 2 minutes (no loop).
        naechsterDurchgangAb = if (voll) jetzt.plusMillis(takt.durchgangAbstandMs) else null
        when {
            !voll -> {
                gebote.limitAufheben()
                protokoll("$text – " + if (a.weggedrueckt > 0) "room again, bidding on" else "still room, bidding on")
            }
            else -> {
                // No slot freed: wait until the earliest end of a bid (+5 s), at most about 2 minutes.
                val ende = (a.enden.values.filter { it.isAfter(jetzt) } + listOfNotNull(gebote.naechstesEnde(jetzt))).minOrNull()
                val ab = if (ende == null) {
                    jetzt.plusMillis(minOf(takt.limitOhneZeitMs, takt.limitWarteMaxMs))
                } else {
                    val wunsch = ende.plusMillis(takt.limitNachlaufMs)
                    val frueh = jetzt.plusMillis(takt.limitNachlaufMs)
                    val spaet = jetzt.plusMillis(takt.limitWarteMaxMs)
                    if (wunsch.isBefore(frueh)) frueh else if (wunsch.isAfter(spaet)) spaet else wunsch
                }
                if (!gebote.limitGemeldet) gebote.limitMeldung(jetzt, sicher = true) // own count: full
                gebote.limitWartenBis(ab)
                protokoll(
                    "$text – no slot freed · " + (ende?.let { "first bid ends at ${uhrzeit(it)}" } ?: "no remaining time readable") +
                        " – bidding again from ${uhrzeit(ab)}",
                )
            }
        }
        return zurueckZumMarkt(bild, jetzt)
    }

    /**
     * Back to the Bidding Market (the bidding list checks filter and sort order there on every screenshot).
     * Nov 23 (review round 3): the menu tap reloads the bidding list – at most one reload every 5 s ([navigationZuFrueh]).
     */
    private fun zurueckZumMarkt(bild: Bildschirm, jetzt: Instant): Ergebnis {
        phase = Phase.Suchen
        vorWischen = null
        nachWischen = null
        navigationZuFrueh(jetzt, "Back to the Bidding Market")?.let { return it }
        return tippe(bild, Knopf.MENUE_BIETERMARKT, "back to the Bidding Market", takt.nachTippenMs)
    }

    // ---------------- Helpers ----------------

    /**
     * Nov 23 (review: "the SG budget can grow"): a steady balance ABOVE the start cannot come from this run's own bids (their
     * refunds bring it back to the start at most) – SG came in from outside: topped up during the run, SG of bids from before
     * the run, or the start balance was misread too low ("50" for "500", the same twice). Then the start is raised to what the
     * balance would have been at the start with these SG: the balance now plus what this run has used by its own bookkeeping
     * ([GebotsVerwaltung.nettoSg]). So the extra SG never become budget, and the SG check when counting purchases
     * ([GebotsVerwaltung.auswerten]) keeps working. Only a balance read the same for a while, not right after our own bid
     * ([sgStabil]).
     */
    private fun startAnheben(jetzt: Instant) {
        val start = startSg ?: return
        val stabil = sgStabil(jetzt) ?: return
        if (stabil <= start) {
            kontoZuletzt = start.toLong() - stabil
            return
        }
        // Nov 23 (review round 3, found in the long runs: with a top-up the budget was overshot by up to ~60 SG on 6 of 16
        // seeds – the bookkeeping had counted a won copy as "lost, SG came back"): what the balance showed as spent just
        // before (start − the last steady balance at or below the start) counts too, if it is more. Stricter, never looser.
        val ausgegeben = maxOf(gebote.nettoSg(), kontoZuletzt ?: 0L)
        val neu = (stabil + ausgegeben).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        kontoZuletzt = null
        stopp.sgBudget?.let {
            protokoll("SG balance $stabil is above the start ($start) – SG came in from outside, the budget still counts only what this run spends (start now $neu)")
        }
        startSg = neu
    }

    /** Nov 23: start − the last steady SG balance at or below the start (what the balance showed as spent; see [startAnheben]). */
    private var kontoZuletzt: Long? = null

    /** How many SG of the budget are still free (null = no budget set). */
    private fun budgetRest(sg: Int): Int? {
        val budget = stopp.sgBudget ?: return null
        val ausgegeben = budgetAusgegeben(sg) ?: return 0 // without start SG, risk nothing
        return budget - ausgegeben
    }

    /**
     * Nov 9: the SG this run has used for the budget – bought copies plus SG tied up in running bids (outbid bids give their
     * SG back, so the budget means "net SG spent"). null = start balance not known yet.
     *
     * Nov 23 (review: "the SG budget can grow"): from two independent sources, the LARGER one counts:
     *  - the SG balance: start − now, where the start is raised when SG come in from outside (see [startAnheben]) – a top-up
     *    or a start balance misread too low adds nothing;
     *  - the bot's own bookkeeping ([GebotsVerwaltung.nettoSg]): bought (estimated) + bids in front right now – no balance
     *    reading at all, so a misread or a top-up cannot make it smaller.
     * A gross sum of all bids placed is NOT used: overnight most bids are outbid and their SG come back (long runs: 2.5 to 2.8
     * times the net amount) – such a cap would stop the bot when only about 40 % of the budget is really spent.
     */
    private fun budgetAusgegeben(sg: Int): Int? {
        val start = startSg ?: return null
        val konto = start.toLong() - sg
        return maxOf(konto, gebote.nettoSg(), 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun tippe(bild: Bildschirm, knopf: Knopf, grund: String, ms: Long, dauerMs: Long = KURZER_TIPP_MS): Ergebnis {
        val p = bild.knopf(knopf) ?: return Ergebnis.Unsicher("Button $knopf not found ($grund)", takt.unbekanntMs)
        // Nov 9 (field test 1.9): afterwards the list shows other rows – what was remembered no longer applies.
        // Nov 22: and a new round starts (no longer waiting for the refresh).
        if (knopf in LISTE_AENDERT_SICH) {
            gemerktVergessen()
            rundeWartet = false
        }
        return Ergebnis.Handeln(Aktion.Tippen(p, grund, dauerMs), ms)
    }

    // ---------------- Remembered rows (Nov 9, field test 1.9) ----------------

    /**
     * "Bidding successful. Check it in My Biddings." lies over the list for 3–4 s after every bid. Instead of waiting,
     * the bot remembers the rows of the page (from screenshots without a message, or from the rows that are free) and uses
     * what it remembered for covered rows – it taps through the message (that works, as tested in the field).
     * Everything is forgotten when scrolling, refreshing, filtering/sorting and switching menus, and as soon as a
     * different copy is at a position than remembered.
     */
    private class Gemerkt(val zeile: Zeile, val endeUm: Instant?, val um: Instant)

    private val gemerkt = ArrayList<Gemerkt>()

    private fun gemerktAn(oben: Int): Gemerkt? = gemerkt.firstOrNull { kotlin.math.abs(it.zeile.oben - oben) <= GLEICHE_STELLE_PX }

    private fun gemerktVergessen() = gemerkt.clear()

    /** Nov 11 (field test 1.20): does what was remembered come from a clear screenshot at most [GEMERKT_MAX_MS] old? */
    private fun frischGemerkt(g: Gemerkt, jetzt: Instant): Boolean = Duration.between(g.um, jetzt).toMillis() <= GEMERKT_MAX_MS

    /** For tests and display: how many rows are remembered right now. */
    val gemerkteZeilen: Int get() = gemerkt.size

    /** Rows for bidding: free rows as read, covered ones (or ones swallowed by the band) from memory. */
    internal fun zeilenMitGemerktem(bild: Bildschirm, jetzt: Instant): List<Zeile> {
        val frei = bild.zeilen.filter { !it.verdeckt }
        if (gemerkt.isNotEmpty()) {
            // Is a different copy at a position than remembered (scrolled, reloaded)? Then forget everything.
            val anders = frei.any { z -> val g = gemerktAn(z.oben); z.id != null && g != null && g.zeile.id != null && g.zeile.id != z.id }
            val passt = frei.any { z -> val g = gemerktAn(z.oben); z.id != null && g != null && g.zeile.id == z.id }
            if (anders || (!passt && frei.any { it.id != null })) gemerktVergessen()
        }
        val meldung = bild.meldung
        if (meldung == null) {
            // Clean screenshot: remember exactly these rows.
            gemerkt.clear()
            frei.filter { it.id != null }.forEach { gemerkt += Gemerkt(it, it.restzeitSek?.let { s -> jetzt.plusSeconds(s.toLong()) }, jetzt) }
            return bild.zeilen
        }
        // Refresh free, readable rows.
        for (z in frei) {
            if (z.id == null) continue
            gemerkt.removeIf { kotlin.math.abs(it.zeile.oben - z.oben) <= GLEICHE_STELLE_PX }
            gemerkt += Gemerkt(z, z.restzeitSek?.let { jetzt.plusSeconds(it.toLong()) }, jetzt)
        }
        val ergebnis = ArrayList<Zeile>()
        for (z in bild.zeilen) {
            if (!z.verdeckt) {
                ergebnis += z
                continue
            }
            ergebnis += gemerktAn(z.oben)?.takeIf { frischGemerkt(it, jetzt) }?.let { ausGemerktem(it, z, jetzt) } ?: z
        }
        // Rows whose card the band swallowed completely (no card recognized).
        for (g in gemerkt) {
            if (!frischGemerkt(g, jetzt)) continue
            if (meldung.verdeckt(g.zeile.oben) && ergebnis.none { kotlin.math.abs(it.oben - g.zeile.oben) <= GLEICHE_STELLE_PX }) {
                ergebnis += ausGemerktem(g, null, jetzt)
            }
        }
        return ergebnis.sortedBy { it.oben }
    }

    /**
     * Nov 9: does the message cover something the bot cannot evaluate? That is the case for a covered row
     * without remembered values, for a card the band swallowed completely (rows above and below, but none
     * touches the band), and for every covered row if tapping through did not work in this run.
     */
    internal fun unbekanntUnterMeldung(bild: Bildschirm, zeilen: List<Zeile>): Boolean {
        val m = bild.meldung ?: return false
        if (zeilen.any { it.verdeckt && (it.id == null || durchMeldungGehtNicht) }) return true
        if (m.yUnten <= m.yOben) return false
        val darueber = zeilen.any { it.oben + Meldung.KARTE_UNTEN <= m.yOben }
        val darunter = zeilen.any { it.oben + Meldung.KARTE_OBEN >= m.yUnten }
        val beruehrt = zeilen.any { m.ueberlappung(it.oben) > 0 }
        return darueber && darunter && !beruehrt
    }

    /**
     * Remembered row for now: remaining time counted down from the remembered end; if I have bid on it since then,
     * it is "mine" (clock) – the remembered icon (e.g. hammer) is outdated then.
     */
    private fun ausGemerktem(g: Gemerkt, live: Zeile?, jetzt: Instant): Zeile {
        val rest = g.endeUm?.let { ((it.toEpochMilli() - jetzt.toEpochMilli()) / 1000).toInt() }
        val id = g.zeile.id
        val seitdemGeboten = id != null && gebote.gebot(id)?.abgegebenUm?.isAfter(g.um) == true
        return g.zeile.copy(
            restzeitSek = rest?.coerceAtLeast(0),
            hatRestzeit = g.zeile.hatRestzeit && (rest == null || rest > 0),
            zeichen = if (seitdemGeboten) Zeichen.UHR else g.zeile.zeichen,
            tippen = live?.tippen ?: g.zeile.tippen,
            verdeckt = true,
        )
    }

    /**
     * Nov 9 (field test 1.9): the "Bidding successful …" message confirms the last bid – but only if
     * it appeared AFTER the bid (a message left over from the previous bid does not count).
     */
    private fun erfolgsMeldung(meldung: Meldung, jetzt: Instant) {
        if (!meldung.wieErfolg) return
        val pr = pruefung ?: return
        val seit = meldungSeit ?: return
        if (seit.isBefore(pr.seit)) return
        protokoll("Bid arrived: \"Bidding successful\" message (${pr.id})")
        pruefung = null
    }

    /** An SG balance was read. */
    private fun sgGelesen(sg: Int, jetzt: Instant) {
        // Nov 11 (review: a single misread SG balance counted right away): the first balance and every HIGHER
        // balance only counts once it was read the same twice in a row. A lower one counts right away (careful:
        // this way the bot rather bids too little than too much). So it always checks with the smaller of the last values.
        val zweimal = sgRoh == sg
        sgRoh = sg
        if (zweimal) sgZweimal = sg
        val alt = letzteSg
        // Nov 12: shortly after our own bid the display often still shows the old, HIGHER balance (the deduction
        // only comes ~2 s later) – then the balance we subtracted ourselves applies. Lower balances count right away as always.
        val gebotFrisch = letztesGebotUm?.let { Duration.between(it, jetzt).toMillis() < SG_ANZEIGE_NACH_GEBOT_MS } == true
        val neu = when {
            alt == null -> if (zweimal) sg else null
            gebotFrisch && sg > alt -> alt
            sg <= alt -> sg
            zweimal -> sg
            else -> alt
        }
        if (neu != letzteSg) sgGleichSeit = jetzt
        letzteSg = neu
    }

    /**
     * Nov 11 (long run): the SG balance for the check – only if it has been read the same for 1.5 s and no bid of our
     * own has been in transit for 2.5 s (otherwise the display may still show the old balance; in the report from Nov 11
     * the new balance showed after ~2 s at the latest).
     */
    private fun stabilesSg(jetzt: Instant): Int? = if (sgAbgleich) sgStabil(jetzt) else null

    /**
     * The SG balance, if it has been read the same for 1.5 s and no bid of our own has been in transit for 2.5 s (see
     * [stabilesSg]). Nov 23: also for raising the start of the budget ([startAnheben]).
     */
    private fun sgStabil(jetzt: Instant): Int? {
        val seit = sgGleichSeit ?: return null
        if (Duration.between(seit, jetzt).toMillis() < 1500) return null
        if (letztesGebotUm?.let { Duration.between(it, jetzt).toMillis() < 2500 } == true) return null
        return letzteSg
    }

    private fun pruefeStopp(jetzt: Instant): Ergebnis? {
        gebote.auswerten(jetzt, sgJetzt = stabilesSg(jetzt), startSg = startSg, abgleichWarten = sgAbgleich && startSg != null)
            .forEach(protokoll)
        return stopp.pruefe(gebote.gekaufteKopien, jetzt, ende)?.let { beenden(it) }
    }

    private fun beenden(grund: StoppGrund): Ergebnis {
        if (gestoppt == null) protokoll("Stop: ${grund.text} (${gebote.gekaufteKopien} copies bought, estimated)")
        gestoppt = grund
        return Ergebnis.Fertig(grund)
    }
}
