package io.github.hustlebot.ihauctionbot.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared state for the floating button, the settings and the bot.
 * Plus the log (last lines in memory, everything in protokoll.txt).
 */
object BotZustand {

    enum class Lauf { BEREIT, LAEUFT, PAUSE, GESTOPPT }

    @Volatile var lauf: Lauf = Lauf.BEREIT
    @Volatile var gekauft: Int = 0
    /**
     * Only approximate (own count) – shown as "approx.". Counts the entries in My Biddings
     * (outbid ones count until their X has been tapped), shown as "approx. N/50 in My Biddings".
     */
    @Volatile var laufendeGebote: Int = 0
    @Volatile var limit: Int = 50

    /** After "50 bids" (-7): from when bidding starts again, e.g. "14:03:25" (null = not known yet). */
    @Volatile var wiederBietenAb: String? = null
    @Volatile var ziel: Int = 0
    @Volatile var endeText: String = ""
    @Volatile var letzteMeldung: String = ""

    /** Why the bot stopped last time (stays in the panel, even while a report is being made). */
    @Volatile var stoppGrund: String? = null

    /**
     * Pocket mode wanted (IH panel or settings). The shield is only there while the bot is running
     * and the screen is on ([taschenmodusAktiv]). Off again after unlocking or stopping.
     */
    @Volatile var taschenmodusGewuenscht: Boolean = false

    /** Is the shield over the screen right now (everything locked)? */
    @Volatile var taschenmodusAktiv: Boolean = false

    /** Which rank is being searched for right now (e.g. "C-"). */
    @Volatile var sucht: String? = null

    /** Special state for the display in the floating button. */
    enum class Hinweis {
        NORMAL, LIMIT, AUFRAEUMEN, UNBEKANNT, SG_FEHLT, SG_UNLESBAR, BUDGET, PASSWORT_FEHLT, PASSWORT_FALSCH, VERBINDUNG, HITZE,

        /** Review before publishing (Nov 22): another app or this app is in front – no screenshots, no taps. */
        SPIEL_FEHLT,
    }

    /** Copies bought outright (buyout) in this run – part of [gekauft]. */
    @Volatile var sofortGekauft: Int = 0

    /** How often the bot reconnected after "Lost connection" in this run. */
    @Volatile var neuverbindungen: Int = 0

    /** E.g. " · 1 bought out · 2 reconnects" (empty while both are 0). */
    private fun extraText(): String =
        (if (sofortGekauft > 0) " · $sofortGekauft bought out" else "") +
            (if (neuverbindungen > 0) " · $neuverbindungen ${if (neuverbindungen == 1) "reconnect" else "reconnects"}" else "")

    /** Battery temperature in °C (null = unknown) – for the "too hot" pause. */
    @Volatile var akkuTemp: Float? = null

    /** What the bot recognised last (e.g. the bidding list or "unknown"). */
    @Volatile var sehe: String? = null

    /** Nothing recognised for at least 10 seconds (then a screenshot was saved). */
    @Volatile var unbekanntLange: Boolean = false

    /** SG budget (null = no limit) and how much of it is already committed. */
    @Volatile var sgBudget: Int? = null
    @Volatile var sgVerplant: Int? = null

    @Volatile var hinweis: Hinweis = Hinweis.NORMAL

    /** An extra status line from a [Variante] hook (null = none), shown in the IH panel below the status while the bot runs. */
    @Volatile var zusatzStatus: String? = null

    /**
     * Privacy review, round 2 (Nov 23): is this app's own screen open right now (EinstellungenActivity between onResume
     * and onPause)? The accessibility service recognises this app's window in the window list by it – without reading
     * any window.
     */
    @Volatile var appBildschirmOffen: Boolean = false

    private val zeilen = ArrayDeque<String>()
    private val beobachter = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var protokollDatei: File? = null

    /** Review round 2 (Nov 23): log lines also go to Android's logcat – only in a debuggable build (emulator test). */
    @Volatile private var logcat = false

    fun init(ctx: Context) {
        if (protokollDatei == null) protokollDatei = File(ctx.applicationContext.filesDir, "protokoll.txt")
        logcat = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun log(text: String) {
        val zeile = "${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}  $text"
        synchronized(zeilen) {
            zeilen.addLast(zeile)
            while (zeilen.size > 300) zeilen.removeFirst()
        }
        if (logcat) Log.i("IHBot", text)
        protokollDatei?.let { datei ->
            runCatching {
                if (datei.length() > 2_000_000) datei.renameTo(File(datei.parentFile, "protokoll.alt.txt"))
                datei.appendText(zeile + "\n")
            }
        }
        letzteMeldung = text
        melde()
    }

    fun letzteZeilen(anzahl: Int = 80): List<String> = synchronized(zeilen) { zeilen.toList().takeLast(anzahl) }

    /** Review before publishing (Nov 22): "Delete reports, log and screenshots" – the log file and the lines in memory. */
    fun protokollLoeschen() {
        synchronized(zeilen) { zeilen.clear() }
        protokollDatei?.let { datei ->
            runCatching { datei.delete() }
            runCatching { File(datei.parentFile, "protokoll.alt.txt").delete() }
        }
        letzteMeldung = ""
        melde()
    }

    /** How long the log file is right now (bookmark for the error report). */
    fun protokollLaenge(): Long = protokollDatei?.length() ?: 0L

    /**
     * The log from [position] on (error report). If the file was started anew in the meantime, all of it;
     * at most the last ~1.5 MB.
     */
    fun protokollSeit(position: Long): String = protokollAbschnitt(position).first

    /**
     * Like [protokollSeit], plus the position up to which it was read
     * (bookmark for the next part – so no line is missing and none comes twice).
     */
    fun protokollAbschnitt(position: Long): Pair<String, Long> {
        val datei = protokollDatei ?: return letzteZeilen(300).joinToString("\n") to 0L
        return runCatching {
            val laenge = datei.length()
            val ab = (if (position in 0..laenge) position else 0L).coerceAtLeast(laenge - 1_500_000)
            java.io.RandomAccessFile(datei, "r").use { f ->
                f.seek(ab)
                val bytes = ByteArray((laenge - ab).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8) to laenge
            }
        }.getOrElse { letzteZeilen(300).joinToString("\n") to (protokollDatei?.length() ?: 0L) }
    }

    fun beobachte(f: () -> Unit) {
        beobachter += f
    }

    fun vergiss(f: () -> Unit) {
        beobachter -= f
    }

    /** Tells all displays that something has changed. */
    fun melde() {
        beobachter.forEach { runCatching { it() } }
    }

    /**
     * Status in simple words, e.g.
     * "Running · 3 of 20 copies · approx. 12/50 in My Biddings · looking for C-" or
     * "Waiting: 50 bids in My Biddings – bidding again from 14:03:25".
     */
    fun klarText(): String {
        val kopien = if (ziel > 0) "$gekauft of $ziel copies" else "$gekauft copies bought"
        // The bot only counts the running bids approximately – hence "approx.".
        // It counts the entries in My Biddings (outbid ones count until their X has been tapped).
        val gebote = "approx. $laufendeGebote/$limit in My Biddings"
        val wieder = wiederBietenAb?.let { " – bidding again from $it" } ?: " – tidying up My Biddings"
        // Review before publishing (Nov 22): without a budget the bot may spend every Starry Gem – say so.
        val budget = sgBudget?.let { b -> " · ${zahl(sgVerplant ?: 0)} of ${zahl(b)} SG" } ?: OHNE_BUDGET
        val text = when (lauf) {
            Lauf.BEREIT -> "Ready – tap Start in the auction house (Bidding Market)"
            Lauf.GESTOPPT -> when (hinweis) {
                Hinweis.PASSWORT_FALSCH -> "Stopped: wrong secondary password? Please check it in the settings"
                Hinweis.VERBINDUNG -> "Lost connection to the game – bot stopped · $kopien"
                else -> "Stopped" + (stoppGrund?.let { ": $it" } ?: "") + " · $kopien"
            }
            Lauf.PAUSE -> "Paused · $kopien · $gebote$budget" + extraText()
            Lauf.LAEUFT -> when {
                hinweis == Hinweis.HITZE -> "Paused: the phone is too hot (${akkuTempText()}) – bidding again below 40 °C · $kopien"
                hinweis == Hinweis.SPIEL_FEHLT -> "Waiting: Idle Heroes is not in front – no screenshots, no taps · $kopien"
                hinweis == Hinweis.PASSWORT_FEHLT -> "Waiting: please enter the secondary password in the settings"
                hinweis == Hinweis.UNBEKANNT && unbekanntLange -> "I don't recognise the auction house – screenshot saved"
                hinweis == Hinweis.UNBEKANNT -> "Unknown screen – please go to the auction house"
                hinweis == Hinweis.SG_FEHLT -> "Waiting: not enough SG for a bid"
                hinweis == Hinweis.SG_UNLESBAR -> "Waiting: SG balance (top right) not readable – no bid"
                hinweis == Hinweis.BUDGET -> "Budget committed – waiting for running bids$budget"
                hinweis == Hinweis.AUFRAEUMEN -> "Tidying up My Biddings: rebidding or removing outbid ones · $gebote"
                hinweis == Hinweis.LIMIT -> "Waiting: $limit bids in My Biddings$wieder · $kopien"
                else -> "Running · $kopien · $gebote" + (sucht?.let { " · looking for $it" } ?: "") + budget + extraText()
            }
        }
        val aktiv = lauf == Lauf.LAEUFT || lauf == Lauf.PAUSE
        // Pocket mode – at the very top, how to unlock.
        val tasche = if (taschenmodusAktiv) "$TASCHE_TEXT\n" else ""
        val sieht = if (aktiv) sehe?.let { "\nSeeing: $it" } ?: "" else ""
        // Extra status line from a [Variante] hook (if any).
        val zusatz = if (aktiv) zusatzStatus?.let { "\n$it" } ?: "" else ""
        return tasche + text + (if (aktiv) endeText else "") + sieht + zusatz
    }

    /**
     * One status line in plain words for the main screen, e.g. "Running – bidding on B- (12 bids, 3 copies won)".
     * Special states (waiting, too hot, stopped …) as in [klarText].
     */
    fun kurzText(): String {
        val ergebnis = "$laufendeGebote bids, $gekauft ${if (gekauft == 1) "copy" else "copies"} won" + extraText()
        return when (lauf) {
            Lauf.BEREIT -> "Ready – tap START, then open the Bidding Market in Idle Heroes"
            Lauf.PAUSE -> "Paused ($ergebnis)"
            Lauf.GESTOPPT -> "Stopped" + (stoppGrund?.let { ": $it" } ?: "") + " – $gekauft ${if (gekauft == 1) "copy" else "copies"} won"
            Lauf.LAEUFT -> if (hinweis == Hinweis.NORMAL) {
                "Running – " + (sucht?.let { "bidding on $it " } ?: "") + "($ergebnis)" + (if (sgBudget == null) OHNE_BUDGET else "")
            } else {
                klarText().lineSequence().first { it != TASCHE_TEXT }
            }
        }
    }

    private fun akkuTempText(): String = akkuTemp?.let { String.format(java.util.Locale.US, "%.0f °C", it) } ?: "?"

    /** Status line in pocket mode. Field test 1.20: name all ways out. */
    const val TASCHE_TEXT = "🎒 Pocket mode on – to unlock: hold IH for 2 s, or press the power button " +
        "(emergency stop: volume down 3 times quickly, or both volume keys briefly – don't hold)"

    /** In the status while running without an SG budget. */
    private const val OHNE_BUDGET = " · ⚠ no SG budget"

    private fun zahl(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

    fun statusText(): String {
        val wort = when (lauf) {
            Lauf.BEREIT -> "Ready"
            Lauf.LAEUFT -> "Running"
            Lauf.PAUSE -> "Paused"
            Lauf.GESTOPPT -> "Stopped"
        }
        val zielText = if (ziel > 0) ziel.toString() else "∞"
        return "$wort · $gekauft/$zielText bought · approx. $laufendeGebote/$limit in My Biddings$endeText"
    }
}
