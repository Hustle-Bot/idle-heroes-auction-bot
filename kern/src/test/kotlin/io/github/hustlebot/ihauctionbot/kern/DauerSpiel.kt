package io.github.hustlebot.ihauctionbot.kern

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Nov 11 (long unattended run): what happens in the rebuilt auction house over hours.
 *
 * @param ankunft     new auctions per minute (per rank)
 * @param laufzeitMin new auctions keep coming for this long (minutes)
 * @param wettbewerb  >1 = others bid less often, <1 = more often
 * @param erwarteteStopps these stop reasons are OK in this run (anything else is a bug)
 */
data class Szenario(
    val name: String,
    val regeln: List<Suchregel>,
    val stunden: Double,
    val startSg: Int = 30_000,
    val zielKopien: Int? = null,
    val endeNachMin: Long? = null,
    val sgBudget: Int? = null,
    val verbindungWegNachMin: Long? = null,
    val passwortNoetig: Boolean = true,
    val ankunft: Map<String, Double> = mapOf("C-" to 3.0, "B-" to 1.5),
    val laufzeitMin: IntRange = 20..180,
    val wettbewerb: Double = 1.0,
    /** Share of bids after which NO confirm window comes after "Bid". */
    val ohneBestaetigen: Double = 0.3,
    /** How often someone else is bidding right when the Bid window opens (price jumps). */
    val preisSprung: Double = 0.03,
    /** Loading screen/hang of the game: this often per hour (average). */
    val haengerProStunde: Double = 1.0,
    /** New game session (the secondary password is asked for again): this often per hour (average). */
    val neueSitzungProStunde: Double = 0.3,
    /** Nov 11 (review): share of bids after which the Bid window only closes after 1.5–6 s (slow network). */
    val langsamesNetz: Double = 0.05,
    /**
     * Nov 11 (field test 1.20: "scrolls clearly too far"): the list moves this much further (×) than dragged with
     * every swipe in the bidding list (1.0 = exact; plus some randomness as always).
     */
    val wischUeberschuss: Double = 1.0,
    /**
     * Nov 12 (field test 1.31): like [wischUeberschuss], but for short swipes (below 300 pixels, pushing on);
     * null = like the long ones.
     */
    val wischUeberschussKurz: Double? = null,
    /** Nov 12 (field test 1.31): spread of every swipe (0.07 = ±7 %). On the phone the swipes were accurate to ±2 %. */
    val wischStreuung: Double = 0.07,
    /**
     * Nov 12 (field test 1.31: after EVERY swipe "list is still sliding", 4–8 images): the list visibly slides on this far after
     * every swipe from 150 pixels on (a good 1 s long; ±10 pixels). 0 = only now and then (10 %, 30–120 pixels, short).
     */
    val rutschImmer: Int = 0,
    /**
     * Nov 12 (review of 1.31): this often (share of the big swipes) the list slides ADDITIONALLY [rutschAusreisserPx]
     * further – a stutter in the game, a row disappears … Even then no copy may be missing.
     */
    val rutschAusreisser: Double = 0.0,
    val rutschAusreisserPx: IntRange = 181..181,
    /**
     * Nov 11 (field test 1.20: "a message every few seconds"): this often (average gap in seconds, 0 = never)
     * a foreign message "Bid Failure: You have lost the bid …" comes – over every image, also over the Bid window.
     */
    val fremdeMeldungAlleS: Double = 0.0,
    /**
     * Nov 22 (overnight): "Lost connection with game server." again and again – every this many minutes (random in the
     * range; null = never). Confirm reconnects: loading star, then the auction house with filter and sort order reset.
     */
    val verbindungWegAlleMin: IntRange? = null,
    /** Nov 22: after Confirm the game lands on a screen the bot does not know (it must stop without a tap). */
    val nachConfirmUnbekannt: Boolean = false,
    /** Nov 22: the bot's setting – how many reconnects per run. */
    val maxNeuverbindungen: Int = 5,
    /**
     * Nov 22 (overnight): a phone notification over the top of the screen for 3–8 s, on average every this many minutes
     * (0 = never). It covers the SG balance and the buttons at the top; sometimes the whole image is not recognized.
     */
    val benachrichtigungAlleMin: Double = 0.0,
    /** Nov 22 (overnight): an unknown pop-up (daily reset …) that goes away by itself after 3–20 s, this often per hour. */
    val popupProStunde: Double = 0.0,
    /**
     * Nov 22 (overnight): the game restarts this often per hour – loading star for 25–60 s, then the auction house with
     * filter and sort order reset, and the secondary password is asked for again.
     */
    val neustartProStunde: Double = 0.0,
    /** Nov 22: after the restart the game is NOT in the auction house (main screen) – the bot must stop without a tap. */
    val neustartInsUnbekannte: Boolean = false,
    /** Nov 22 (buyout): share of the auctions with a "Buyout Price" (3–29 SG above the start price). */
    val buyoutAnteil: Double = 0.0,
    /** Nov 22 (buyout): the bot's buyout switch. */
    val buyoutAn: Boolean = false,
    /**
     * Nov 22 (rate limit): the game refuses a refresh or a sort order that comes less than this long after the previous reload
     * of the list (refresh, filter Confirm, sort order): "Operated too frequently. Please retry in N second(s)." over the
     * screen, nothing happens (0 = never). Real reports: "Awakened Tier ↑" ~2 s after the filter → "retry in 3 second(s)".
     */
    val zuOftAbstandMs: Long = 0,
    /** Nov 22 (rate limit): share of the taps on "Bid" the game refuses with "too frequently" (window stays open, no bid). */
    val zuOftBeimBieten: Double = 0.0,
    /**
     * Nov 23 (review: "the SG budget can grow"): this many minutes after the start the player tops up [aufladenSg] SG (bought in
     * the shop, or SG of a bid from before the run come back). The budget still counts only what the run spends, net.
     */
    val aufladenNachMin: Long? = null,
    val aufladenSg: Int = 0,
    /** Nov 23: the first this many images with an SG balance show a tenth of it (a digit lost: "50" for "500"), each the same. */
    val sgStartFalschBilder: Int = 0,
    /**
     * Nov 23 (coordinator): in these minutes after the start typing into the filter's price fields does not work (the bot then
     * gives them up for a while); afterwards it works again.
     */
    val eingabeKaputtMin: IntRange? = null,
    /**
     * Nov 23 (review round 3): share of the Bid windows whose bid field is 1–3 SG higher than Current Bid + 1 (a tap on "+",
     * a quirk of the game). "Bid" bids what is in the field.
     */
    val feldHoeher: Double = 0.0,
    /**
     * Nov 23: the digits of the bid field the bot has learned (from the real screenshots: 0, 1, 4, 5, 8, 9) – every other
     * digit shows as '?' to the bot, as with the real recognizer. null = every digit readable.
     */
    val feldZiffern: String? = "014589",
    val erwarteteStopps: Set<StoppGrund> = emptySet(),
)

/**
 * Nov 11 (long run): an auction house that changes over hours like the real one – new auctions come,
 * others bid (more and more often towards the end), auctions end and are won or lost, the 50 limit ("-7"),
 * the secondary password (once per game session), "Bidding successful", loading screens, unreadable numbers, half
 * scrolled lists, My Biddings with the red X, lost connection.
 *
 * The times come from the report of Nov 11 (19:39, image by image with timestamps): row → Bid window approx.
 * 0.5–0.9 s (first a transition image), Bid → confirm approx. 0.3–0.5 s, Confirm → list with "Bidding successful"
 * approx. 0.2–0.5 s, the message lies over the list for approx. 2.0–2.7 s from the bid. A new screenshot comes
 * at most every ~0.38 s.
 *
 * At the same time the game checks what the bot does: taps at the top into the purchase bar, Cancel/Buyout, bids above the
 * max price, above the SG or above the budget, taps through the message, taps after the connection was lost
 * ([verstoesse]).
 */
class DauerSpiel(seed: Long, var jetzt: Instant, private val sz: Szenario) : BildschirmLeser {

    private val rnd = Random(seed)

    /** Nov 23: own random numbers for the bid field – the other random draws stay exactly as before. */
    private val feldRnd = Random(seed * 7919 + 17)

    class Auktion(val nr: Int, val stufe: String, var preis: Int, val endeUm: Instant, val fremdMax: Int, val sofort: Int? = null) {
        val werte: List<Int> = listOf(1000 + nr % 4000, 5000 + (nr / 4000) % 4000, 20 + nr % 30)
        val id: String = werte.joinToString("-")
        var ichVorne = false
        var meinPreis = 0
        var ueberboten = false
        var inMeineListe = false
        var vorbei = false

        /** Nov 23: our last bid here came from a raised bid field the bot could not see. */
        var unerkanntErhoeht = false
    }

    // ---------------- Market ----------------

    private val alle = ArrayList<Auktion>()
    private var naechsteNr = 0
    private val naechsteAnkunft = HashMap<String, Instant>()
    private var letzterTakt: Instant = jetzt

    var sg = sz.startSg
        private set
    val startSg = sz.startSg

    /** Nov 23: when and on which rank each bid was placed (to see whether a rank's list got lost). */
    val gebotStufen = ArrayList<Pair<Instant, String>>()

    private val eingabeKaputtVon: Instant? = sz.eingabeKaputtMin?.let { jetzt.plusSeconds(it.first * 60L) }
    private val eingabeKaputtBis: Instant? = sz.eingabeKaputtMin?.let { jetzt.plusSeconds(it.last * 60L) }

    /** Nov 23: typing into the price fields does not work right now ([Szenario.eingabeKaputtMin]). */
    private fun eingabeKaputt(): Boolean =
        eingabeKaputtVon != null && !jetzt.isBefore(eingabeKaputtVon) && jetzt.isBefore(eingabeKaputtBis)

    /** Nov 23: SG topped up during the run ([Szenario.aufladenSg]) – the run's net spending is startSg + aufgeladen − sg. */
    var aufgeladen = 0
        private set
    private val aufladenUm: Instant? = sz.aufladenNachMin?.let { jetzt.plusSeconds(it * 60) }
    private var sgFalschRest = sz.sgStartFalschBilder

    /** Nov 23: what the run has spent, net (bought + tied up in bids in front; outbid bids gave their SG back). */
    val nettoAusgegeben: Int get() = startSg + aufgeladen - sg

    /** The SG balance as the image shows it (Nov 23: the first images maybe misread, see [Szenario.sgStartFalschBilder]). */
    private fun sgAnzeige(): Int? {
        val wert = lesbar(sg, 0.03) ?: return null
        if (sgFalschRest > 0) {
            sgFalschRest--
            return wert / 10
        }
        return wert
    }
    var gewonnen = 0
        private set
    var verlorenAnzahl = 0
        private set

    /** All bids the game accepted: (when, copy, amount). */
    val gebote = ArrayList<Triple<Instant, String, Int>>()
    val verstoesse = ArrayList<String>()
    val hinweise = ArrayList<String>()
    var fehltipps = 0
        private set
    var tippsImUebergang = 0
        private set
    var limitMeldungen = 0
        private set
    var bidFehlschlaege = 0
        private set
    var kreuze = 0
        private set
    var meineGeboteGeoeffnet = 0
        private set
    var ladenEreignisse = 0
        private set

    /** Nov 11 (field test 1.20): taps on a row while the message lay over the list (allowed now). */
    var tippsDurchMeldung = 0
        private set

    /** Nov 11 (scrolling): big swipes down, and how many of them showed exactly 5 new rows afterwards. */
    var hauptWischer = 0
        private set

    /** Nov 12: all swipes in the bidding list (big ones + pushing on/looking back). */
    var alleWischer = 0
        private set
    var genauFuenfNeu = 0
        private set
    val neueJeWischer = ArrayList<Int>()

    /** Index of the last row that was visible before the last big swipe (null = nothing to check). */
    private var vorWischLetzte: Int? = null
    private var listenIndex: Map<Auktion, Int> = emptyMap()

    /** Indexes of the rows on the last image of the bidding list (fully visible, also under a message). */
    private var letzteSichtbar: IntRange? = null

    /** How often a copy was opened without a bid on it arriving in between (for "ping-pong"). */
    val oeffnungenOhneGebot = HashMap<String, ArrayList<Instant>>()

    // ---------------- Screen ----------------

    private var ort = Ort.MARKT
    private var filter: String? = null
    private var gewaehlt = mutableSetOf<String>()
    private var filterMin = "10"
    private var filterMax = "99999999"
    private var sortierung = Sortierung.PREIS
    private var liste: List<Auktion> = emptyList()
    private var versatz = 0
    private var listeLaedtBis: Instant? = null

    /** Momentum after the finger is lifted: this many pixels, from when, how long. */
    private var rutschPx = 0
    private var rutschAb: Instant = jetzt
    private var rutschMs = 300L
    private var versatzMeine = 0
    private var dialog: Auktion? = null
    private var dialogSeit: Instant = jetzt
    private var dialogPreisGezeigt = 0

    /** Nov 23 (review round 3): the bid field is this much higher than Current Bid + 1 in the open Bid window (0 = normal). */
    private var dialogFeldPlus = 0

    /** Nov 23: what was in the bid field when "Bid" was tapped (the amount the game bids after the password / in Confirm). */
    private var feldBeimTipp = 0

    /** Nov 23: Bid taps with a raised bid field the bot could see on its image (a bug) / could not see (digits not learned). */
    var feldHoeherGetippt = 0
        private set
    var feldHoeherUnerkannt = 0
        private set

    /** Nov 23: Bid windows with a raised bid field; bids above the rule's highest bid that came from an unseen raised field. */
    var feldHoeherFenster = 0
        private set
    var ueberMaxUnerkannt = 0
        private set
    var selbstUeberbotenUnerkannt = 0
        private set
    private var feldUnerkanntBeimTipp = false
    private var bietenAus = Ort.MARKT
    private var bestaetigenBetrag = 0
    private var passwortOk = !sz.passwortNoetig
    private var passwortEingetragen = false
    private var toastBis: Instant? = null
    private var toast: Meldung? = null
    private var fensterToastBis: Instant? = null
    private var fensterToast: Meldung? = null
    private var uebergangBis: Instant? = null
    private var uebergangBild: (() -> Bildschirm)? = null
    private var nachUebergang: (() -> Unit)? = null
    private val verbindungWegUm: Instant? = sz.verbindungWegNachMin?.let { jetzt.plusSeconds(it * 60) }
    private var naechsterHaenger: Instant = jetzt.plusMillis(expMs(60.0 / sz.haengerProStunde.coerceAtLeast(0.01)))
    private var naechsteSitzung: Instant = jetzt.plusMillis(expMs(60.0 / sz.neueSitzungProStunde.coerceAtLeast(0.01)))
    var neueSitzungen = 0
        private set
    private var naechsteFremdeMeldung: Instant =
        if (sz.fremdeMeldungAlleS > 0) jetzt.plusMillis(expMs(sz.fremdeMeldungAlleS / 60.0)) else Instant.MAX
    var fremdeMeldungen = 0
        private set
    var letzteEingabeOk = true
        private set
    private var letzter: Bildschirm? = null

    // ---------------- Nov 22: overnight disturbances, reconnect, buyout ----------------

    /** Since when the "Lost connection" dialog is visible (recurring losses; null = not right now). */
    private var trennungSeit: Instant? = null
    private var naechsteTrennung: Instant = sz.verbindungWegAlleMin?.let { jetzt.plusSeconds(zwischen(it.first * 60L, it.last * 60L)) } ?: Instant.MAX
    var trennungen = 0
        private set
    var confirmGetippt = 0
        private set
    val confirmZeiten = ArrayList<Instant>()

    /** After Confirm: loading star until then. */
    private var nachConfirmLadenBis: Instant? = null

    /** The game is on a screen the bot does not know (for good). */
    private var imUnbekannten = false
    private var naechsteBenachrichtigung: Instant =
        if (sz.benachrichtigungAlleMin > 0) jetzt.plusMillis(expMs(sz.benachrichtigungAlleMin)) else Instant.MAX
    private var benachrichtigungBis: Instant? = null
    var benachrichtigungen = 0
        private set
    private var naechstesPopup: Instant = if (sz.popupProStunde > 0) jetzt.plusMillis(expMs(60.0 / sz.popupProStunde)) else Instant.MAX
    private var popupBis: Instant? = null
    var popups = 0
        private set
    private var naechsterNeustart: Instant = if (sz.neustartProStunde > 0) jetzt.plusMillis(expMs(60.0 / sz.neustartProStunde)) else Instant.MAX
    var neustarts = 0
        private set
    var sofortGekauft = 0
        private set
    private val buyoutGetipptIds = HashSet<String>()

    // ---------------- Nov 22: rate limit and refresh rate ----------------

    /** Taps on the refresh button that reached the game. */
    var aktualisierungen = 0
        private set

    /** All reloads of the list the bot asked for (refresh, filter Confirm, sort order) – refused ones included. */
    var listenAbfragen = 0
        private set

    /** How often the game showed "Operated too frequently". */
    var zuOftMeldungen = 0
        private set

    /** Nov 23: of those, how often because a reload of the list came too soon (the rest: refused taps on "Bid"). */
    var zuOftBeiAbfrage = 0
        private set

    /**
     * Nov 23 (review: "no lasting minimum gap between reloads"): reloads of the list (refresh, filter Confirm, sort order) the
     * bot asked for less than [ABFRAGE_MIN_MS] after the one before (refused ones included), and the shortest such gap.
     */
    var abfragenZuDicht = 0
        private set
    var kuerzesterAbfrageAbstandMs: Long? = null
        private set
    private var letzteAbfrageAngefragt: Instant? = null

    /** The last reload of the list the game accepted. */
    private var letzteAbfrage: Instant? = null

    /**
     * Nov 23 (review round 3: "navigation taps also reload lists"): taps on a menu entry (My Biddings, Bidding Market) load a
     * list too – they count for the gap between two reloads ([abfragenZuDicht]); the game itself does not refuse them here.
     */
    var navAbfragen = 0
        private set

    private fun navAbfrage() {
        navAbfragen++
        abfrageAngefragt()
    }

    /** Nov 23: every reload of the list the bot asks for – how far after the one before? */
    private fun abfrageAngefragt() {
        listenAbfragen++
        letzteAbfrageAngefragt?.let { vorher ->
            val ms = Duration.between(vorher, jetzt).toMillis()
            if (ms < ABFRAGE_MIN_MS) abfragenZuDicht++
            if (kuerzesterAbfrageAbstandMs?.let { ms < it } != false) kuerzesterAbfrageAbstandMs = ms
        }
        letzteAbfrageAngefragt = jetzt
    }

    init {
        for ((stufe, rate) in sz.ankunft) {
            val mittel = (sz.laufzeitMin.first + sz.laufzeitMin.last) / 2.0
            repeat((rate * mittel).toInt()) {
                val rest = rnd.nextLong(10, sz.laufzeitMin.last * 60L)
                neu(stufe, jetzt, jetzt.plusSeconds(rest))
            }
            naechsteAnkunft[stufe] = jetzt.plusMillis(expMs(1.0 / rate))
        }
        schnappschuss()
    }

    /** Exponentially distributed waiting time (average in minutes) in milliseconds. */
    private fun expMs(mittelMin: Double): Long = (-ln(1.0 - rnd.nextDouble()) * mittelMin * 60_000).toLong().coerceAtLeast(1)

    private fun zwischen(von: Long, bis: Long): Long = rnd.nextLong(von, bis + 1)

    private fun neu(stufe: String, um: Instant, ende: Instant) {
        val (preis, fremd) = when (stufe) {
            "C-" -> rnd.nextInt(9, 23) to rnd.nextInt(13, 27)
            "B-" -> rnd.nextInt(25, 64) to rnd.nextInt(40, 72)
            else -> rnd.nextInt(10, 100) to rnd.nextInt(10, 120)
        }
        // Nov 22: only with buyouts in the scenario a random number is drawn (the other scenarios stay exactly as before).
        val sofort = if (sz.buyoutAnteil > 0 && rnd.nextDouble() < sz.buyoutAnteil) preis + rnd.nextInt(3, 30) else null
        alle += Auktion(naechsteNr++, stufe, preis, ende, fremd, sofort)
    }

    fun restSek(a: Auktion): Long = Duration.between(jetzt, a.endeUm).seconds

    private fun welt() {
        while (!letzterTakt.plusSeconds(5).isAfter(jetzt)) {
            letzterTakt = letzterTakt.plusSeconds(5)
            for ((stufe, rate) in sz.ankunft) {
                var t = naechsteAnkunft.getValue(stufe)
                while (!t.isAfter(letzterTakt)) {
                    neu(stufe, t, t.plusSeconds(sz.laufzeitMin.first * 60L + rnd.nextLong(0, (sz.laufzeitMin.last - sz.laufzeitMin.first) * 60L + 1)))
                    t = t.plusMillis(expMs(1.0 / rate))
                }
                naechsteAnkunft[stufe] = t
            }
            for (a in alle) if (!a.vorbei && a.endeUm.isAfter(letzterTakt)) fremdTakt(a, letzterTakt)
        }
        for (a in alle) if (!a.vorbei && !a.endeUm.isAfter(jetzt)) auktionVorbei(a)
        // Nov 23: the player tops up SG during the run (once).
        if (aufladenUm != null && aufgeladen == 0 && !jetzt.isBefore(aufladenUm)) {
            sg += sz.aufladenSg
            aufgeladen = sz.aufladenSg
        }
        // Foreign message ("Bid Failure: You have lost the bid …") if no other one is visible right now.
        if (!jetzt.isBefore(naechsteFremdeMeldung)) {
            naechsteFremdeMeldung = jetzt.plusMillis(expMs(sz.fremdeMeldungAlleS / 60.0))
            if (toastJetzt() == null) {
                toast = FEHLSCHLAG
                toastBis = jetzt.plusMillis(zwischen(2000, 2600))
                fremdeMeldungen++
            }
        }
        // New game session: the secondary password is asked for again at the next bid.
        if (!jetzt.isBefore(naechsteSitzung)) {
            naechsteSitzung = jetzt.plusMillis(expMs(60.0 / sz.neueSitzungProStunde.coerceAtLeast(0.01)))
            if (sz.passwortNoetig) {
                passwortOk = false
                neueSitzungen++
            }
        }
        stoerungen()
        if (alle.size > 4000) alle.removeAll { it.vorbei && it.endeUm.isBefore(jetzt.minusSeconds(1800)) }
    }

    /** Nov 22 (overnight): lost connection, notifications, pop-ups, restarts of the game. */
    private fun stoerungen() {
        val ruhig = trennungSeit == null && !imUnbekannten && nachConfirmLadenBis?.isAfter(jetzt) != true
        if (!jetzt.isBefore(naechsteTrennung)) {
            naechsteTrennung = jetzt.plusSeconds(zwischen(sz.verbindungWegAlleMin!!.first * 60L, sz.verbindungWegAlleMin.last * 60L))
            if (ruhig) {
                trennungSeit = jetzt
                trennungen++
                // The game is frozen behind the dialog – what was about to happen does not happen any more.
                uebergangBis = null
                uebergangBild = null
                nachUebergang = null
            }
        }
        if (!jetzt.isBefore(naechsteBenachrichtigung)) {
            naechsteBenachrichtigung = jetzt.plusMillis(expMs(sz.benachrichtigungAlleMin))
            benachrichtigungBis = jetzt.plusMillis(zwischen(3000, 8000))
            benachrichtigungen++
        }
        if (!jetzt.isBefore(naechstesPopup)) {
            naechstesPopup = jetzt.plusMillis(expMs(60.0 / sz.popupProStunde))
            if (ruhig && popupBis?.isAfter(jetzt) != true) {
                popupBis = jetzt.plusMillis(zwischen(3000, 20_000))
                popups++
            }
        }
        if (!jetzt.isBefore(naechsterNeustart)) {
            naechsterNeustart = jetzt.plusMillis(expMs(60.0 / sz.neustartProStunde))
            if (ruhig) {
                neustarts++
                ladenEreignisse++
                if (sz.passwortNoetig) passwortOk = false
                uebergang(zwischen(25_000, 60_000), { Bildschirm(Ort.LADEN) }) {
                    if (sz.neustartInsUnbekannte) imUnbekannten = true else spielZurueckgesetzt()
                }
            }
        }
    }

    /** Nov 22: the auction house as after a new start – filter and sort order reset, no window open. */
    private fun spielZurueckgesetzt() {
        ort = Ort.MARKT
        dialog = null
        filter = null
        filterMin = "10"
        filterMax = "99999999"
        sortierung = Sortierung.PREIS
        toastBis = null
        fensterToastBis = null
        listeNeuLaden(zwischen(300, 900))
    }

    /** Others bid – the closer the end, the more often (up to their own max price). */
    private fun fremdTakt(a: Auktion, t: Instant) {
        if (a.preis >= a.fremdMax) return
        val rest = Duration.between(t, a.endeUm).seconds
        val mittelSek = when {
            rest > 600 -> 1500.0
            rest > 120 -> 150.0
            else -> 40.0
        } * sz.wettbewerb
        if (rnd.nextDouble() < 5.0 / mittelSek) fremdGebot(a)
    }

    private fun fremdGebot(a: Auktion) {
        if (a.ichVorne) {
            a.ichVorne = false
            a.ueberboten = true
            sg += a.meinPreis // the game gives the SG back
        }
        a.preis += GEBOTS_SCHRITT
    }

    private fun auktionVorbei(a: Auktion) {
        a.vorbei = true
        if (a.ichVorne) gewonnen++ else if (a.inMeineListe) verlorenAnzahl++
        a.inMeineListe = false
    }

    /** Own entries in My Biddings (in front or outbid, until the red X was pressed or the auction ends). */
    fun meineListe(): List<Auktion> = alle.filter { it.inMeineListe && !it.vorbei }.sortedWith(compareBy({ it.endeUm }, { it.nr }))

    /** Running auctions in which I am in front right now. */
    fun vorneOffen(): Int = alle.count { it.ichVorne && !it.vorbei }

    /**
     * Is there something right now that the bot could and should bid on? (For the stuck check: if something like that stays
     * for a long time without a bid coming, the bot is stuck.)
     */
    fun kandidat(regeln: List<Suchregel>, budgetRest: Int?): Auktion? {
        val voll = alle.count { it.inMeineListe && !it.vorbei } >= LIMIT
        return alle.firstOrNull { a ->
            val rest = restSek(a)
            !a.vorbei && !a.ichVorne && rest > 30 && (!voll || a.inMeineListe) &&
                a.preis + GEBOTS_SCHRITT <= sg && (budgetRest == null || a.preis + GEBOTS_SCHRITT <= budgetRest) &&
                regeln.any { r -> r.kopie == a.stufe && r.erlaubt(a.preis.toDouble()) && r.zeitPasst((rest - 20).toInt()) }
        }
    }

    private fun schnappschuss() {
        val lo = filterMin.toIntOrNull() ?: 0
        val hi = filterMax.toIntOrNull() ?: Int.MAX_VALUE
        val l = alle.filter { !it.vorbei && (filter == null || it.stufe == filter) && it.preis in lo..hi }
        liste = if (sortierung == Sortierung.RANG) {
            l.sortedWith(compareBy({ it.endeUm }, { it.nr }))
        } else {
            l.sortedWith(compareBy({ it.preis }, { it.nr }))
        }
        listenIndex = java.util.IdentityHashMap<Auktion, Int>().also { m -> liste.forEachIndexed { i, a -> m[a] = i } }
        vorWischLetzte = null
        versatz = 0
        rutschPx = 0
    }

    private fun listeNeuLaden(ms: Long) {
        listeLaedtBis = jetzt.plusMillis(ms)
        schnappschuss()
    }

    // ---------------- Transitions (windows open/close, loading) ----------------

    private fun uebergang(ms: Long, bild: () -> Bildschirm, dann: () -> Unit) {
        uebergangBis = jetzt.plusMillis(ms)
        uebergangBild = bild
        nachUebergang = dann
    }

    private fun uebergangPruefen() {
        while (true) {
            val bis = uebergangBis ?: return
            if (jetzt.isBefore(bis)) return
            uebergangBis = null
            uebergangBild = null
            val f = nachUebergang
            nachUebergang = null
            f?.invoke()
        }
    }

    private fun verbindungWeg(): Boolean = trennungSeit != null || verbindungWegUm?.let { !jetzt.isBefore(it) } == true

    /** Nov 22: can nothing be done in the game right now (lost connection, loading after Confirm, unknown screen)? */
    fun gesperrt(): Boolean =
        verbindungWeg() || imUnbekannten || nachConfirmLadenBis?.isAfter(jetzt) == true || popupBis?.isAfter(jetzt) == true

    /** Nov 22: is a phone notification lying over the top of the screen right now? */
    private fun benachrichtigungDa(): Boolean = benachrichtigungBis?.isAfter(jetzt) == true

    /** Nov 22: the notification covers the top middle of the screen (reference pixels). */
    private fun inBenachrichtigung(p: Punkt): Boolean = p.y < BENACHRICHTIGUNG_UNTEN && p.x in 700..2000

    // ---------------- Images ----------------

    override fun lies(): Bildschirm {
        welt()
        uebergangPruefen()
        letztesMitBenachrichtigung = benachrichtigungDa()
        val b = mitBenachrichtigung(bild())
        letzter = b
        return b
    }

    /** Nov 22: did the last image show the notification? */
    private var letztesMitBenachrichtigung = false

    /**
     * Nov 22 (overnight): a phone notification over the top: SG balance and the buttons at the top are not readable, the
     * sort button neither; sometimes the whole image is not recognized.
     */
    private fun mitBenachrichtigung(b: Bildschirm): Bildschirm {
        if (!benachrichtigungDa()) return b
        if (rnd.nextDouble() < 0.3) return Bildschirm.UNBEKANNT
        return b.copy(
            sg = null,
            sortierung = if (b.ort == Ort.MARKT) Sortierung.UNBEKANNT else b.sortierung,
            knoepfe = b.knoepfe.filterValues { !inBenachrichtigung(it) },
        )
    }

    private fun bild(): Bildschirm {
        nachConfirmLadenBis?.let { if (jetzt.isBefore(it)) return Bildschirm(Ort.LADEN) else nachConfirmLadenBis = null }
        if (verbindungWeg()) {
            if (trennungSeit == null) {
                // Fixed loss (old scenario): the dialog stays (and comes again after every Confirm).
                trennungSeit = jetzt
                trennungen++
            }
            return Bildschirm(Ort.VERBINDUNG_WEG, knoepfe = mapOf(Knopf.BESTAETIGEN to VERBINDUNG_CONFIRM))
        }
        if (imUnbekannten) return Bildschirm.UNBEKANNT
        popupBis?.let { if (jetzt.isBefore(it)) return Bildschirm.UNBEKANNT else popupBis = null }
        uebergangBild?.let { return it() }
        // The game hangs briefly (loading screen with golden star).
        if (!jetzt.isBefore(naechsterHaenger)) {
            naechsterHaenger = jetzt.plusMillis(expMs(60.0 / sz.haengerProStunde.coerceAtLeast(0.01)))
            ladenEreignisse++
            uebergang(zwischen(2000, 20_000), { Bildschirm(Ort.LADEN) }) {}
            return Bildschirm(Ort.LADEN)
        }
        if (rnd.nextDouble() < 0.002) return Bildschirm.UNBEKANNT // a single dark image
        return when (ort) {
            Ort.MARKT -> marktBild()
            Ort.BIETEN -> bietenBild()
            Ort.BESTAETIGEN -> bestaetigenBild()
            Ort.ZWEITPASSWORT -> Bildschirm(
                Ort.ZWEITPASSWORT, knoepfe = mapOf(Knopf.PASSWORT_FELD to Punkt(1356, 630), Knopf.PASSWORT_OK to Punkt(1528, 804)),
            )
            Ort.FILTER -> Bildschirm(
                Ort.FILTER,
                filterFelder = KOPIE_TYPEN.mapIndexed { i, n -> FilterFeld(n, Punkt(800 + i * 100, 285), n in gewaehlt) },
                filterMinStellen = filterMin.length,
                filterMaxStellen = filterMax.length,
                knoepfe = mapOf(Knopf.FILTER_OK to Punkt(1547, 1018), Knopf.FILTER_MIN to Punkt(1153, 584), Knopf.FILTER_MAX to Punkt(1559, 584)),
            )
            // Nov 22: like the real report images – "too frequently" lies over the sort window that stays open.
            Ort.SORTIEREN -> Bildschirm(
                Ort.SORTIEREN, meldung = toastJetzt()?.takeIf { it === ZU_OFT },
                knoepfe = mapOf(Knopf.SORT_PREIS_AUF to Punkt(1734, 381), Knopf.SORT_RANG_AUF to Punkt(1734, 499)),
            )
            Ort.MEINE_GEBOTE -> meineBild()
            else -> Bildschirm.UNBEKANNT
        }
    }

    private fun toastJetzt(): Meldung? = toast?.takeIf { toastBis?.isAfter(jetzt) == true }

    private fun lesbar(wert: Int, p: Double): Int? = if (rnd.nextDouble() < p) null else wert

    /** Offset of the list now (with momentum after the finger is lifted). */
    private fun versatzJetzt(): Int {
        if (rutschPx == 0) return versatz
        val ms = Duration.between(rutschAb, jetzt).toMillis()
        if (ms <= 0) return versatz
        if (ms >= rutschMs) {
            versatz = (versatz + rutschPx).coerceIn(0, maxVersatz())
            rutschPx = 0
            return versatz
        }
        return (versatz + (rutschPx * ms / rutschMs).toInt()).coerceIn(0, maxVersatz())
    }

    /** Is the list moving right now (momentum)? */
    fun listeRutscht(): Boolean = rutschPx != 0 && Duration.between(rutschAb, jetzt).toMillis() in 0 until rutschMs

    private fun maxVersatz(): Int = max(0, ERSTE_ZEILE_OBEN + (liste.size - 1) * ZEILEN_ABSTAND - 1059)

    private fun sichtbar(): List<Pair<Auktion, Int>> {
        val v = versatzJetzt()
        val erste = ((v - 30) / ZEILEN_ABSTAND).coerceAtLeast(0)
        val ergebnis = ArrayList<Pair<Auktion, Int>>()
        var i = erste
        while (i < liste.size) {
            val oben = ERSTE_ZEILE_OBEN + i * ZEILEN_ABSTAND - v
            if (oben > 1080) break
            if (oben >= 289) ergebnis += liste[i] to oben
            i++
        }
        return ergebnis
    }

    private fun marktBild(): Bildschirm {
        val t = toastJetzt()
        val laedt = listeLaedtBis?.isAfter(jetzt) == true
        val sicht = if (laedt) emptyList() else sichtbar()
        letzteSichtbar = sicht.mapNotNull { listenIndex[it.first] }.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }
        val zeilen = sicht.map { (a, oben) -> marktZeile(a, oben, t) }
        return Bildschirm(
            Ort.MARKT,
            zeilen = zeilen,
            sg = sgAnzeige(),
            meldung = t,
            sortierung = if (rnd.nextDouble() < 0.02) Sortierung.UNBEKANNT else sortierung,
            knoepfe = MENUE + mapOf(
                Knopf.AKTUALISIEREN to Punkt(1688, 231), Knopf.SORTIEREN to Punkt(1895, 231), Knopf.FILTER to Punkt(2180, 231),
            ),
            wischWeg = Punkt(1356, 1080) to Punkt(1356, 520),
        )
    }

    private fun marktZeile(a: Auktion, oben: Int, t: Meldung?): Zeile {
        val tippen = Punkt(1356, oben + 60)
        if (t != null && t.verdeckt(oben)) {
            return Zeile(oben, null, null, null, false, listOf(null, null, null), Zeichen.KEINS, false, null, tippen, verdeckt = true)
        }
        var zeichen = when {
            a.ichVorne -> Zeichen.UHR
            a.ueberboten && a.inMeineListe -> Zeichen.HAMMER
            else -> Zeichen.KEINS
        }
        if (zeichen != Zeichen.KEINS && rnd.nextDouble() < 0.01) zeichen = Zeichen.KEINS // icon not recognized
        val werte: MutableList<Int?> = a.werte.toMutableList()
        if (rnd.nextDouble() < 0.02) werte[rnd.nextInt(3)] = null
        val rest = if (a.vorbei) null else lesbar(restSek(a).toInt(), 0.02)
        return Zeile(oben, lesbar(a.preis, 0.02), a.sofort?.let { lesbar(it, 0.02) }, rest, !a.vorbei, werte, zeichen, false, a.stufe.first(), tippen)
    }

    private fun bietenBild(): Bildschirm {
        val a = dialog ?: return Bildschirm.UNBEKANNT
        dialogPreisGezeigt = a.preis
        val erst = Duration.between(dialogSeit, jetzt).toMillis() < 300
        val r = rnd.nextDouble()
        val akt = when {
            r < (if (erst) 0.3 else 0.1) -> null
            r < (if (erst) 0.31 else 0.11) -> a.preis / 10 // a digit got lost
            else -> a.preis
        }
        // Messages also lie over the Bid window – exactly over the Bid button (real images Nov 11: "-7" and
        // "Bid Failure: You have lost the bid …"); "Current Bid" above stays readable, the button is not recognized.
        val m = fensterToast?.takeIf { fensterToastBis?.isAfter(jetzt) == true } ?: toastJetzt()
        val knoepfe = HashMap<Knopf, Punkt>()
        if (m == null && rnd.nextDouble() >= 0.01) knoepfe[Knopf.BIETEN] = BID_KNOPF
        knoepfe[Knopf.SCHLIESSEN] = BID_X
        // Nov 22 (buyout): "Buyout Price: N" and the yellow button below Bid (only with a buyout; sometimes misread).
        var sofort: Int? = null
        if (a.sofort != null) {
            val r2 = rnd.nextDouble()
            sofort = when {
                r2 < 0.05 -> null
                r2 < 0.06 -> a.sofort / 10
                else -> a.sofort
            }
            if (rnd.nextDouble() >= 0.02) knoepfe[Knopf.SOFORTKAUF] = SOFORT_KNOPF
        }
        // Nov 23 (review round 3): the bid field – Current Bid + 1 (+ a raise), as the recognizer reads it: a band over it →
        // nothing; now and then nothing readable (more often on the first image of the window); digits not learned yet → '?'.
        // (Dark digits on white – easier than the light "Current Bid" on brown, which is unreadable on 30 % of the first images.)
        val feld = a.preis + GEBOTS_SCHRITT + dialogFeldPlus
        val muster = when {
            m != null -> null
            feldRnd.nextDouble() < (if (erst) 0.1 else 0.02) -> null
            else -> feld.toString().map { if (sz.feldZiffern == null || it in sz.feldZiffern) it else '?' }.joinToString("")
        }
        return Bildschirm(
            Ort.BIETEN, aktuellesGebot = akt, meldung = m, knoepfe = knoepfe, sofortPreis = sofort,
            bietFeld = muster?.takeIf { '?' !in it }?.toInt(), bietFeldMuster = muster,
        )
    }

    /**
     * Nov 23 (review round 3): could the bot see on [b] that the bid field is not Current Bid + 1? (A learned digit that
     * differs, or a different number of digits – the same rule as in the bot.)
     */
    private fun feldSichtbarFalsch(b: Bildschirm): Boolean {
        val muster = b.bietFeldMuster ?: return false
        val soll = ((b.aktuellesGebot ?: return false) + GEBOTS_SCHRITT).toString()
        return muster.length != soll.length || muster.indices.any { muster[it] != '?' && muster[it] != soll[it] }
    }

    private fun bestaetigenBild(): Bildschirm {
        val r = rnd.nextDouble()
        val betrag = when {
            r < 0.7 -> null // report Nov 11: almost always "not readable"
            r < 0.75 -> bestaetigenBetrag % 10
            else -> bestaetigenBetrag
        }
        return Bildschirm(
            Ort.BESTAETIGEN, bestaetigenPreis = betrag, meldung = toastJetzt(),
            knoepfe = mapOf(Knopf.BESTAETIGEN to Punkt(1528, 804), Knopf.SCHLIESSEN to BESTAETIGEN_X),
        )
    }

    private fun meineBild(): Bildschirm {
        val l = meineListe()
        val t = toastJetzt()
        versatzMeine = versatzMeine.coerceIn(0, maxVersatzMeine(l.size))
        val zeilen = l.mapIndexedNotNull { i, a ->
            val oben = MEINE_ERSTE_OBEN + i * ZEILEN_ABSTAND - versatzMeine
            if (oben !in 365..1069) return@mapIndexedNotNull null
            val tippen = Punkt(1356, oben + 60)
            if (t != null && t.verdeckt(oben)) {
                return@mapIndexedNotNull Zeile(oben, null, null, null, false, listOf(null, null, null), Zeichen.KEINS, false, null, tippen, verdeckt = true)
            }
            val x = a.ueberboten
            val werte: MutableList<Int?> = a.werte.toMutableList()
            if (rnd.nextDouble() < 0.02) werte[rnd.nextInt(3)] = null
            Zeile(
                oben, lesbar(a.preis, 0.02), null, lesbar(restSek(a).toInt(), 0.02), true, werte,
                if (x) Zeichen.KEINS else Zeichen.UHR, x, a.stufe.first(), tippen,
                kreuzPunkt = if (x) Punkt(2293, oben + 14) else null,
            )
        }
        return Bildschirm(
            Ort.MEINE_GEBOTE,
            zeilen = zeilen,
            laufendeGebote = lesbar(l.size, 0.1),
            sg = sgAnzeige(),
            meldung = t,
            keineGebote = l.isEmpty(),
            knoepfe = MENUE + (Knopf.AKTUALISIEREN to Punkt(1976, 231)),
        )
    }

    private fun maxVersatzMeine(n: Int): Int = max(0, MEINE_ERSTE_OBEN + (n - 1) * ZEILEN_ABSTAND - 1020)

    // ---------------- The bot's actions ----------------

    fun ausfuehren(a: Aktion, um: Instant) {
        jetzt = um
        welt()
        uebergangPruefen()
        handlung(a)
    }

    private fun handlung(a: Aktion) {
        when (a) {
            is Aktion.Tippen -> tippen(a.punkt)
            is Aktion.Wischen -> wischen(a)
            is Aktion.Eingeben -> {
                letzteEingabeOk = uebergangBis == null && ort == Ort.FILTER && !verbindungWeg() && rnd.nextDouble() >= 0.02 && !eingabeKaputt()
                if (letzteEingabeOk) {
                    when (a.feld) {
                        Punkt(1153, 584) -> filterMin = a.text
                        Punkt(1559, 584) -> filterMax = a.text
                        else -> letzteEingabeOk = false
                    }
                }
            }
            is Aktion.PasswortEingeben -> {
                letzteEingabeOk = uebergangBis == null && ort == Ort.ZWEITPASSWORT && !verbindungWeg()
                if (letzteEingabeOk) passwortEingetragen = true
            }
            is Aktion.Folge -> {
                var ok = true
                for (s in a.schritte) {
                    handlung(s)
                    if (s is Aktion.Eingeben && !letzteEingabeOk) ok = false
                }
                letzteEingabeOk = ok
            }
        }
    }

    /** The run reports violations right away (with the bot's last steps). */
    var beiVerstoss: (String) -> Unit = {}

    private fun verstoss(text: String) {
        verstoesse += "${uhr(jetzt)} $text"
        beiVerstoss("${uhr(jetzt)} $text")
    }

    private fun tippen(p: Punkt) {
        val b = letzter ?: return
        // Nov 22 (reconnect): Confirm of the "Lost connection" dialog – only allowed on the dialog, after waiting.
        if (verbindungWeg() && b.ort == Ort.VERBINDUNG_WEG && p == VERBINDUNG_CONFIRM && trennungSeit != null) {
            confirmGetipptAufDialog()
            return
        }
        if (imUnbekannten || popupBis?.isAfter(jetzt) == true || nachConfirmLadenBis?.isAfter(jetzt) == true) {
            // The tap goes into a screen the bot does not know (or the game is loading) – a bug if the bot saw it.
            if (b.ort == Ort.UNBEKANNT) verstoss("tap on an unknown screen ($p)") else tippsImUebergang++
            return
        }
        if (benachrichtigungDa() && inBenachrichtigung(p)) {
            // Into the notification (opens another app!) – a bug if the bot saw the notification.
            if (letztesMitBenachrichtigung) verstoss("tap into the notification $p") else tippsImUebergang++
            return
        }
        if (verbindungWeg()) {
            // Only a bug if the bot had already seen the loss (otherwise the tap only arrived 85 ms too late).
            if (b.ort == Ort.VERBINDUNG_WEG) verstoss("tap after the connection was lost ($p)") else tippsImUebergang++
            return
        }
        // Nov 22 (rate limit): never tap while "Operated too frequently" is on the image the bot looked at.
        if (b.meldung?.wieZuOft == true) verstoss("tap while \"Operated too frequently\" was on the screen ($p, ${b.ort})")
        val echt: Ort? = if (uebergangBis != null) null else ort
        // The purchase buttons ("+") are in the top bar. Only the red X of a REALLY open window is allowed there.
        if (p.y < TippSperre.OBEN_SPERRE_Y) {
            val xOk = echt != null && b.ort == echt && b.knopf(Knopf.SCHLIESSEN) == p && p == xPunkt(echt)
            if (!xOk) verstoss("tap at the top in the purchase bar $p – image showed ${b.ort}, game is ${echt ?: "in transition"}")
        }
        // Nov 22: the Buyout button only as offered on the image (yellow); whether buyout was allowed: [buyoutGetippt].
        if (b.ort == Ort.BIETEN && abs(p.x - 1641) < 150 && abs(p.y - 935) < 60 && b.knopf(Knopf.SOFORTKAUF) != p) {
            verstoss("Buyout tapped $p (button not offered)")
        }
        if (b.ort == Ort.BESTAETIGEN && abs(p.x - 1180) < 150 && abs(p.y - 804) < 70) verstoss("Cancel tapped $p")
        // Nov 11 (field test 1.20): the bot may now tap rows through the "Bidding successful" message.
        if (b.ort == Ort.MARKT && toastJetzt() != null && b.zeilen.any { it.tippen == p }) tippsDurchMeldung++
        if (echt == null || b.ort != echt) {
            // The image was already outdated (window was just opening/closing) – the tap goes into empty space.
            tippsImUebergang++
            return
        }
        val knopf = b.knoepfe.entries.firstOrNull { it.value == p }?.key
        when (echt) {
            Ort.MARKT -> marktTipp(b, p, knopf)
            Ort.FILTER -> when (knopf) {
                Knopf.FILTER_OK -> {
                    // Nov 22: a reload of the list (the game does not refuse it here, but the next one counts from it).
                    abfrageAngefragt()
                    letzteAbfrage = jetzt
                    filter = gewaehlt.singleOrNull()
                    uebergang(300, { Bildschirm.UNBEKANNT }) {
                        ort = Ort.MARKT
                        listeNeuLaden(zwischen(300, 700))
                    }
                }
                else -> {
                    val f = b.filterFelder.firstOrNull { it.punkt == p }
                    if (f == null) fehltipps++ else if (!gewaehlt.remove(f.stufe)) gewaehlt += f.stufe
                }
            }
            Ort.SORTIEREN -> when (knopf) {
                Knopf.SORT_RANG_AUF, Knopf.SORT_PREIS_AUF -> {
                    if (rnd.nextDouble() < 0.1) return // tap not accepted (field test 1.9)
                    if (abfrageAbgelehnt()) return // Nov 22: "too frequently" – the sort window stays open
                    val neu = if (knopf == Knopf.SORT_RANG_AUF) Sortierung.RANG else Sortierung.PREIS
                    uebergang(250, { Bildschirm.UNBEKANNT }) {
                        ort = Ort.MARKT
                        sortierung = neu
                        listeNeuLaden(300)
                    }
                }
                else -> fehltipps++
            }
            Ort.BIETEN -> when (if (p == BID_KNOPF) Knopf.BIETEN else if (p == BID_X) Knopf.SCHLIESSEN else knopf) {
                Knopf.BIETEN -> bidGetippt()
                Knopf.SOFORTKAUF -> buyoutGetippt()
                Knopf.SCHLIESSEN -> {
                    val zurueck = bietenAus
                    uebergang(zwischen(150, 700), { bietenBild() }) {
                        ort = zurueck
                        dialog = null
                    }
                }
                else -> fehltipps++
            }
            Ort.BESTAETIGEN -> when (knopf) {
                Knopf.BESTAETIGEN -> confirmGetippt()
                Knopf.SCHLIESSEN -> uebergang(zwischen(150, 700), { bestaetigenBild() }) { ort = Ort.BIETEN }
                else -> fehltipps++
            }
            Ort.ZWEITPASSWORT -> when (knopf) {
                Knopf.PASSWORT_OK -> if (passwortEingetragen) {
                    passwortOk = true
                    val r = rnd.nextDouble()
                    uebergang(300, { Bildschirm.UNBEKANNT }) {
                        when {
                            // Back to the Bid window, briefly a message in it (field test 1.8: looks like "-7").
                            r < 0.34 -> {
                                ort = Ort.BIETEN
                                fensterMeldung(Meldung(800), 1200)
                            }
                            r < 0.67 -> {
                                ort = Ort.BESTAETIGEN
                                bestaetigenBetrag = feldBeimTipp
                            }
                            else -> {
                                ort = Ort.BIETEN
                                gebotUndZurueck(dialog!!, feldBeimTipp)
                            }
                        }
                    }
                }
                Knopf.PASSWORT_FELD -> Unit
                else -> fehltipps++
            }
            Ort.MEINE_GEBOTE -> meineTipp(b, p, knopf)
            else -> fehltipps++
        }
    }

    private fun xPunkt(o: Ort): Punkt? = when (o) {
        Ort.BIETEN -> BID_X
        Ort.BESTAETIGEN -> BESTAETIGEN_X
        else -> null
    }

    private fun marktTipp(b: Bildschirm, p: Punkt, knopf: Knopf?) {
        when (knopf) {
            Knopf.FILTER -> uebergang(300, { Bildschirm.UNBEKANNT }) {
                ort = Ort.FILTER
                gewaehlt = filter?.let { mutableSetOf(it) } ?: mutableSetOf()
            }
            Knopf.SORTIEREN -> uebergang(zwischen(200, 400), { Bildschirm.UNBEKANNT }) { ort = Ort.SORTIEREN }
            Knopf.AKTUALISIEREN -> {
                aktualisierungen++
                if (abfrageAbgelehnt()) return // Nov 22: "too frequently" – the list stays as it is
                uebersprungenPruefen()
                listeNeuLaden(zwischen(300, 900))
            }
            Knopf.MENUE_MEINE_GEBOTE -> {
                meineGeboteGeoeffnet++
                navAbfrage() // Nov 23 (review round 3): My Biddings loads its list from the server
                val laden = rnd.nextDouble() < 0.3
                uebergang(zwischen(400, 900), { if (laden) Bildschirm(Ort.LADEN) else Bildschirm.UNBEKANNT }) {
                    ort = Ort.MEINE_GEBOTE
                    versatzMeine = 0
                }
            }
            Knopf.MENUE_BIETERMARKT, Knopf.MENUE_AUKTIONSMARKT -> navAbfrage()
            null -> {
                if (b.zeilen.none { it.tippen == p }) {
                    fehltipps++
                    return
                }
                // Which auction is under the finger NOW? (If the list is still sliding, it can be a different one.)
                val v = versatzJetzt()
                val i = Math.floorDiv(p.y - 60 + v - ERSTE_ZEILE_OBEN + ZEILEN_ABSTAND / 2, ZEILEN_ABSTAND)
                val a = liste.getOrNull(i)
                if (a == null || listeLaedtBis?.isAfter(jetzt) == true) {
                    fehltipps++
                    return
                }
                if (listeRutscht()) hinweise += "${uhr(jetzt)} row tapped while the list is still sliding"
                uebersprungenPruefen()
                oeffnen(a, Ort.MARKT)
            }
            else -> fehltipps++
        }
    }

    /**
     * Nov 11 (scrolling): if the bot acts on an image after a big swipe (tap a row, swipe on,
     * refresh), the row right after the last one seen before must be visible – otherwise something was
     * skipped.
     */
    private fun uebersprungenPruefen() {
        val vor = vorWischLetzte ?: return
        vorWischLetzte = null
        val jetztSichtbar = letzteSichtbar ?: return
        val neu = jetztSichtbar.last - vor
        neueJeWischer += jetztSichtbar.first - vor - 1
        if (jetztSichtbar.first == vor + 1 && neu == 5) genauFuenfNeu++
        if (jetztSichtbar.first > vor + 1) verstoss("${jetztSichtbar.first - vor - 1} row(s) skipped while swiping")
    }

    private fun meineTipp(b: Bildschirm, p: Punkt, knopf: Knopf?) {
        when (knopf) {
            Knopf.MENUE_BIETERMARKT -> {
                navAbfrage() // Nov 23 (review round 3): back to the Bidding Market reloads the list
                val sortZurueck = rnd.nextDouble() < 0.2
                uebergang(zwischen(400, 900), { Bildschirm.UNBEKANNT }) {
                    ort = Ort.MARKT
                    if (sortZurueck) sortierung = Sortierung.PREIS
                    listeNeuLaden(zwischen(300, 700))
                }
            }
            null -> {
                val l = meineListe()
                val x = b.zeilen.firstOrNull { it.kreuzPunkt == p }
                val idx = Math.floorDiv(p.y - (if (x != null) 14 else 60) + versatzMeine - MEINE_ERSTE_OBEN, ZEILEN_ABSTAND)
                val a = l.getOrNull(idx)
                if (a == null) {
                    fehltipps++
                    return
                }
                if (x != null) {
                    // Red X: only if there REALLY is an X at this spot NOW.
                    if (!a.ueberboten || MEINE_ERSTE_OBEN + idx * ZEILEN_ABSTAND - versatzMeine + 14 != p.y) {
                        fehltipps++
                        return
                    }
                    if (rnd.nextDouble() < 0.03) return // X does not work
                    a.inMeineListe = false
                    kreuze++
                } else if (b.zeilen.any { it.tippen == p }) {
                    oeffnen(a, Ort.MEINE_GEBOTE)
                } else {
                    fehltipps++
                }
            }
            else -> Unit
        }
    }

    private fun oeffnen(a: Auktion, aus: Ort) {
        oeffnungenOhneGebot.getOrPut(a.id) { ArrayList() } += jetzt
        val ab = jetzt
        val altesBild = letzter
        uebergang(zwischen(450, 800), { if (Duration.between(ab, jetzt).toMillis() < 150 && altesBild != null) altesBild else Bildschirm.UNBEKANNT }) {
            ort = Ort.BIETEN
            dialog = a
            dialogSeit = jetzt
            bietenAus = aus
            fensterToastBis = null
            // Nov 23: now and then the bid field is higher than Current Bid + 1 ("+" tapped, a quirk of the game).
            dialogFeldPlus = if (sz.feldHoeher > 0 && feldRnd.nextDouble() < sz.feldHoeher) feldRnd.nextInt(1, 4) else 0
            if (dialogFeldPlus > 0) feldHoeherFenster++
            if (rnd.nextDouble() < sz.preisSprung && !a.vorbei) fremdGebot(a) // someone else is bidding right now
        }
    }

    private fun fensterMeldung(m: Meldung, ms: Long) {
        fensterToast = m
        fensterToastBis = jetzt.plusMillis(ms)
    }

    /**
     * Nov 22 (rate limit): a reload of the list (refresh, sort order) – refused with "too frequently" if it comes less than
     * [Szenario.zuOftAbstandMs] after the last one the game accepted. true = refused (nothing happens).
     */
    private fun abfrageAbgelehnt(): Boolean {
        abfrageAngefragt()
        val vorher = letzteAbfrage
        if (sz.zuOftAbstandMs > 0 && vorher != null) {
            val ms = Duration.between(vorher, jetzt).toMillis()
            if (ms < sz.zuOftAbstandMs) {
                zuOftBeiAbfrage++
                zuOftZeigen(sz.zuOftAbstandMs - ms)
                return true
            }
        }
        letzteAbfrage = jetzt
        return false
    }

    /** "Operated too frequently. Please retry in N second(s)." – visible about N s (counting down), like on the real images. */
    private fun zuOftZeigen(restMs: Long) {
        zuOftMeldungen++
        val sek = ((restMs + 999) / 1000).coerceAtLeast(1)
        toast = ZU_OFT
        toastBis = jetzt.plusMillis(sek * 1000 + 500)
    }

    private fun bidGetippt() {
        val a = dialog ?: return
        if (fensterToastBis?.isAfter(jetzt) == true) return
        // Nov 22 (rate limit): the game refuses the tap – the message lies over the Bid window, the window stays open.
        // (Only drawn when the scenario uses it – the other scenarios stay exactly as before.)
        if (sz.zuOftBeimBieten > 0 && rnd.nextDouble() < sz.zuOftBeimBieten) {
            zuOftMeldungen++
            fensterMeldung(ZU_OFT, zwischen(2500, 3500))
            return
        }
        // Nov 23 (review round 3): "Bid" bids what is in the bid field. A raised field the bot could see on its image must never
        // be bid; one it could not see (digits not learned yet) is counted.
        feldBeimTipp = dialogPreisGezeigt + GEBOTS_SCHRITT + dialogFeldPlus
        feldUnerkanntBeimTipp = false
        if (dialogFeldPlus > 0) {
            val b = letzter
            if (b != null && b.ort == Ort.BIETEN && feldSichtbarFalsch(b)) {
                feldHoeherGetippt++
                verstoss("Bid tapped although the bid field showed \"${b.bietFeldMuster}\" (Current Bid ${b.aktuellesGebot}, ${a.id})")
            } else {
                feldHoeherUnerkannt++
                feldUnerkanntBeimTipp = true
            }
        }
        when {
            // Meanwhile someone else bid or the auction is over → "Bid Failure".
            a.vorbei || a.preis != dialogPreisGezeigt -> {
                bidFehlschlaege++
                fensterMeldung(FEHLSCHLAG, 1500)
            }
            !a.inMeineListe && meineListe().size >= LIMIT -> {
                limitMeldungen++
                fensterMeldung(Meldung(762), zwischen(1200, 2000))
            }
            !passwortOk -> uebergang(250, { Bildschirm.UNBEKANNT }) {
                ort = Ort.ZWEITPASSWORT
                passwortEingetragen = false
            }
            rnd.nextDouble() >= sz.ohneBestaetigen -> {
                val betrag = feldBeimTipp
                uebergang(zwischen(250, 450), { bietenBild() }) {
                    ort = Ort.BESTAETIGEN
                    bestaetigenBetrag = betrag
                }
            }
            else -> gebotUndZurueck(a, feldBeimTipp)
        }
    }

    /** Nov 22 (buyout): "Buyout Price" tapped – allowed only with buyout on, up to the rule's maximum, once per copy. */
    private fun buyoutGetippt() {
        val a = dialog ?: return
        val regel = sz.regeln.firstOrNull { it.kopie == a.stufe && it.aktiv }
        val preis = a.sofort
        if (!sz.buyoutAn) verstoss("Buyout tapped although buyout is switched off (${a.id})")
        val max = regel?.buyoutMax
        if (max == null) verstoss("Buyout without a buyout maximum in the rule (${a.id})")
        if (preis == null) {
            verstoss("Buyout tapped although the copy has no buyout (${a.id})")
            return
        }
        if (max != null && preis > max) verstoss("Buyout for $preis SG above the maximum $max (${a.id})")
        if (!buyoutGetipptIds.add(a.id)) verstoss("Buyout tapped twice for the same copy (${a.id})")
        if (fensterToastBis?.isAfter(jetzt) == true) return
        if (a.vorbei) {
            bidFehlschlaege++
            fensterMeldung(FEHLSCHLAG, 1500)
            return
        }
        if (preis > sg) {
            verstoss("Buyout $preis SG, but only $sg SG available")
            return
        }
        sz.sgBudget?.let { b ->
            val verplant = startSg + aufgeladen - (sg - preis)
            if (verplant > b) verstoss("SG budget exceeded by a buyout: planned $verplant > $b")
        }
        if (a.ichVorne) verstoss("bought out a copy on which we are in front (${a.id})")
        sg -= preis
        a.vorbei = true
        a.inMeineListe = false
        sofortGekauft++
        oeffnungenOhneGebot.remove(a.id)
        val zurueck = bietenAus
        uebergang(zwischen(150, 700), { bietenBild() }) {
            ort = zurueck
            dialog = null
        }
    }

    /** Nov 22 (reconnect): Confirm on the "Lost connection" dialog. */
    private fun confirmGetipptAufDialog() {
        val seit = trennungSeit ?: return
        val ms = Duration.between(seit, jetzt).toMillis()
        if (ms < 50_000) verstoss("Confirm of \"Lost connection\" after only ${ms / 1000} s")
        confirmGetippt++
        confirmZeiten += jetzt
        trennungSeit = null
        ladenEreignisse++
        if (verbindungWegUm != null) {
            // The connection stays gone (old scenario): loading star, then the dialog again.
            nachConfirmLadenBis = jetzt.plusMillis(zwischen(5000, 15_000))
            return
        }
        nachConfirmLadenBis = jetzt.plusMillis(zwischen(5000, 40_000))
        if (sz.nachConfirmUnbekannt) imUnbekannten = true else spielZurueckgesetzt()
    }

    private fun confirmGetippt() {
        val a = dialog ?: return
        if (a.vorbei || bestaetigenBetrag <= a.preis) {
            bidFehlschlaege++
            schliessenNachGebot(FEHLSCHLAG, 1500)
            return
        }
        gebotUndZurueck(a, bestaetigenBetrag)
    }

    private fun gebotUndZurueck(a: Auktion, betrag: Int) {
        // Nov 23: the amount from the bid field is no longer above the price (someone bid meanwhile) → "Bid Failure".
        if (betrag <= a.preis) {
            bidFehlschlaege++
            schliessenNachGebot(FEHLSCHLAG, 1500)
            return
        }
        if (platzieren(a, betrag)) schliessenNachGebot(ERFOLG, zwischen(2000, 2700)) else schliessenNachGebot(FEHLSCHLAG, 1500)
    }

    /** Window closes (the image still shows it briefly), then the list with the message; sometimes a loading screen first. */
    private fun schliessenNachGebot(m: Meldung, meldungMs: Long) {
        val zurueck = bietenAus
        val laden = rnd.nextDouble() < 0.03
        toast = m
        toastBis = jetzt.plusMillis(meldungMs)
        // Nov 11 (review): "Bidding successful" often already lies over the Bid window that is closing; and on a
        // slow mobile network the window only closes after a few seconds.
        val mitMeldung = m == ERFOLG && rnd.nextDouble() < 0.5
        val zuMs = if (rnd.nextDouble() < sz.langsamesNetz) zwischen(1500, 6000) else zwischen(150, 700)
        uebergang(zuMs, { bietenBild().let { b -> if (mitMeldung && toastBis?.isAfter(jetzt) == true) b.copy(meldung = ERFOLG) else b } }) {
            ort = zurueck
            dialog = null
            if (laden) {
                ladenEreignisse++
                uebergang(zwischen(1000, 4000), { Bildschirm(Ort.LADEN) }) {}
            }
        }
    }

    /** The game accepts a bid – and checks what the bot must never do. false = rejected. */
    private fun platzieren(a: Auktion, betrag: Int): Boolean {
        val regel = sz.regeln.firstOrNull { it.kopie == a.stufe && it.aktiv }
        if (regel == null) {
            verstoss("bid on ${a.stufe} without a rule (${a.id})")
        } else {
            if (betrag > regel.hoechstesGebot) {
                // Nov 23 (review round 3): a raised bid field the bot could NOT see (digits not learned yet) is measured, not
                // counted as a bug of the bot – the scenarios with raised fields report it ([ueberMaxUnerkannt]).
                if (feldUnerkanntBeimTipp && betrag == feldBeimTipp) {
                    ueberMaxUnerkannt++
                    hinweise += "${uhr(jetzt)} bid $betrag SG above the highest bid ${regel.hoechstesGebot} – raised bid field not readable (${a.id})"
                } else {
                    verstoss("bid $betrag SG above the highest bid ${regel.hoechstesGebot} (${a.id})")
                }
            }
            regel.maxRestzeitMin?.let { m ->
                val r = restSek(a)
                if (r > m * 60 + 10) verstoss("bid with $r s remaining, rule: only last $m min (${a.id})")
            }
        }
        if (a.ichVorne) {
            // Nov 23 (review round 3): our leading bid came from a raised bid field the bot could not see – its bookkeeping
            // holds a lower amount, so a row without an icon looks outbid. Measured like the raise itself, not a bug.
            if (a.unerkanntErhoeht) {
                selbstUeberbotenUnerkannt++
                hinweise += "${uhr(jetzt)} outbid our own leading bid (${a.id}, $betrag SG) – it came from a raised bid field not readable"
            } else {
                verstoss("outbid our own leading bid (${a.id}, $betrag SG)")
            }
        }
        if (betrag > sg) {
            verstoss("bid $betrag SG, but only $sg SG available")
            return false
        }
        a.unerkanntErhoeht = feldUnerkanntBeimTipp && betrag == feldBeimTipp
        sz.sgBudget?.let { b ->
            // Nov 23: SG topped up during the run do not count as budget (net spending of this run).
            val verplant = startSg + aufgeladen - (sg - betrag)
            if (verplant > b) verstoss("SG budget exceeded: planned $verplant > $b")
        }
        sg -= betrag
        a.preis = betrag
        a.ichVorne = true
        a.meinPreis = betrag
        a.ueberboten = false
        a.inMeineListe = true
        gebote += Triple(jetzt, a.id, betrag)
        gebotStufen += jetzt to a.stufe
        oeffnungenOhneGebot.remove(a.id)
        return true
    }

    private fun wischen(a: Aktion.Wischen) {
        if (verbindungWeg()) {
            // Nov 22: only a bug if the bot had already seen the dialog (otherwise the swipe only arrived 85 ms too late).
            if (letzter?.ort == Ort.VERBINDUNG_WEG) verstoss("swipe after the connection was lost") else tippsImUebergang++
            return
        }
        if (imUnbekannten || popupBis?.isAfter(jetzt) == true || nachConfirmLadenBis?.isAfter(jetzt) == true) {
            if (letzter?.ort == Ort.UNBEKANNT) verstoss("swipe on an unknown screen") else tippsImUebergang++
            return
        }
        if (letzter?.meldung?.wieZuOft == true) verstoss("swipe while \"Operated too frequently\" was on the screen")
        if (uebergangBis != null) {
            tippsImUebergang++
            return
        }
        val zug = a.von.y - a.nach.y // > 0: dragged up = further in the list
        when (ort) {
            Ort.MARKT -> {
                val totzone = rnd.nextInt(0, 21)
                val faktor = 1.0 - sz.wischStreuung + rnd.nextDouble() * 2 * sz.wischStreuung
                val ueberschuss = if (abs(zug) < 300) (sz.wischUeberschussKurz ?: sz.wischUeberschuss) else sz.wischUeberschuss
                val betrag = (max(0, abs(zug) - totzone) * faktor * ueberschuss).toInt()
                alleWischer++
                // Nov 11 (scrolling): a big swipe down must not skip any row.
                // Nov 12: "big" is the swipe to the next page ("further down") – a long push after
                // looking back is not a new page (the bot has already seen the rows in between).
                if (zug > 0 && a.grund.startsWith("further down")) {
                    uebersprungenPruefen()
                    vorWischLetzte = letzteSichtbar?.last
                    hauptWischer++
                }
                versatz = (versatzJetzt() + (if (zug >= 0) betrag else -betrag)).coerceIn(0, maxVersatz())
                // Sometimes the list still slides a bit after the finger is lifted (despite holding still).
                val richtung = if (zug >= 0) 1 else -1
                if (sz.rutschImmer > 0) {
                    rutschPx = if (abs(zug) >= 150) richtung * (sz.rutschImmer + rnd.nextInt(-10, 11)) else 0
                    if (zug >= 300 && rnd.nextDouble() < sz.rutschAusreisser) {
                        rutschPx += rnd.nextInt(sz.rutschAusreisserPx.first, sz.rutschAusreisserPx.last + 1)
                    }
                    rutschMs = 1200L
                } else {
                    rutschPx = if (rnd.nextDouble() < 0.1) richtung * rnd.nextInt(30, 121) else 0
                    rutschMs = 300L
                }
                rutschAb = jetzt.plusMillis(a.dauerMs + a.haltenMs)
            }
            Ort.MEINE_GEBOTE -> {
                val n = meineListe().size
                val faktor = if (zug > 0) 0.95 + rnd.nextDouble() * 0.2 else 1.0
                versatzMeine = (versatzMeine + (zug * faktor).toInt()).coerceIn(0, maxVersatzMeine(n))
            }
            else -> fehltipps++
        }
    }

    companion object {
        const val LIMIT = 50
        val MENUE = mapOf(
            Knopf.MENUE_AUKTIONSMARKT to Punkt(321, 342),
            Knopf.MENUE_BIETERMARKT to Punkt(321, 427),
            Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488),
        )
        val BID_KNOPF = Punkt(1641, 690)

        /** Nov 22: lower edge of a phone notification at the top (reference pixels). */
        const val BENACHRICHTIGUNG_UNTEN = 260
        val BID_X = Punkt(2105, 108)
        val BESTAETIGEN_X = Punkt(1806, 301)

        /** "Bidding successful. Check it in My Biddings." – band over row 2 (reference phone). */
        val ERFOLG = Meldung(1224, yOben = 537, yUnten = 681)

        /** "Bid Failure …" – wider and taller (report Nov 11: 1542 px). */
        val FEHLSCHLAG = Meldung(1542, yOben = 470, yUnten = 700)

        /** Nov 22: "Operated too frequently. Please retry in N second(s)." – one line, 1398–1428 px (real report images). */
        val ZU_OFT = Meldung(1415, yOben = 536, yUnten = 680)

        /** Nov 23 (review): two reloads of the list asked for less than this far apart count as "too close" ([abfragenZuDicht]). */
        const val ABFRAGE_MIN_MS = 5000L

        fun uhr(t: Instant): String = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(t.atZone(BERLIN))
    }
}
