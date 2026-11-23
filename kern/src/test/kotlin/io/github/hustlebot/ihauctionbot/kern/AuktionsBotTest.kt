package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

/**
 * A small rebuilt auction house: it reacts to the bot's taps like the game
 * (windows open and close, filter with price fields, sort order, 50 limit, secondary password, SG …).
 * This way the whole flow can be checked without a phone.
 */
class SpielAttrappe(var jetzt: Instant) : BildschirmLeser {

    class SimZeile(
        val werte: List<Int>,
        var preis: Int,
        val endeUm: Instant,
        val stufe: String,
        var zeichen: Zeichen = Zeichen.KEINS,
        /** Auction already over, but the row is still in the list without a remaining time. */
        val ohneZeit: Boolean = false,
        /** Nov 22: "Buyout Price" (null = "--", no buyout). */
        val sofort: Int? = null,
    ) {
        /** Nov 22: bought out – gone from the list. */
        var gekauft = false
    }

    /** Nov 22 (buyout): what the game does after "Buyout Price". */
    enum class NachBuyout { KAUFEN, BESTAETIGEN, NICHTS }

    val listen = HashMap<String, MutableList<SimZeile>>()
    var ort = Ort.MARKT
    private var gewaehlt = mutableSetOf("C")
    var filter: String? = "C"

    /** What the list shows right now (after Confirm only after "refresh" if [filterBrauchtAktualisieren]). */
    private var gezeigterFilter: String? = "C"
    var filterBrauchtAktualisieren = false
    var filterMin = "10"
    var filterMax = "99999999"
    var sortierung = Sortierung.PREIS
    var oben = 0
    private var dialog: SimZeile? = null
    private var meldungKurz = false
    /** Bids of another kind (e.g. by hand) – plus my own, as long as I am in front and the auction is running. */
    var laufendeGebote = 0

    /** Does My Biddings show "Bidding Items: N" readably? */
    var zaehlerLesbar = true
    var spielLimit = 50
    private val meine = ArrayList<SimZeile>()

    /** What "Bidding Items" would show. Nov 11: outbid ones count until their red X was pressed. */
    fun biddingItems(): Int = laufendeGebote + meineListe().size

    // ---------- Nov 11: My Biddings with red X, scrolling, bidding again, dismissing ----------

    /** Bids dismissed with the red X (in the order they were pressed). */
    val weggedrueckt = ArrayList<SimZeile>()

    /** My bids as My Biddings shows them: in front (clock) or outbid (X), sorted by price. */
    fun meineListe(): List<SimZeile> =
        meine.filter { it.endeUm.isAfter(jetzt) && it !in weggedrueckt && it.zeichen != Zeichen.KEINS }.sortedBy { it.preis }

    /** A bid that is already running (e.g. placed by hand or before a restart) – also in the bidding list. */
    fun meinGebot(
        stufe: String, preis: Int, restSek: Long, vararg werte: Int, ueberboten: Boolean = false, inBieterListe: Boolean = true,
    ): SimZeile {
        val z = SimZeile(werte.toList(), preis, jetzt.plusSeconds(restSek), stufe, if (ueberboten) Zeichen.HAMMER else Zeichen.UHR)
        if (inBieterListe) listen.getOrPut(stufe) { ArrayList() } += z
        meine += z
        return z
    }

    /**
     * Nov 11 (review): in My Biddings the bot only bids again on copies from its own bookkeeping. This helper
     * records the own bids as if the bot had placed them itself in this run (outbid ones as outbid).
     * The number of entries ([GebotsVerwaltung.eintraege]) stays as it was.
     */
    fun bekannt(gv: GebotsVerwaltung = GebotsVerwaltung()): GebotsVerwaltung {
        val vorher = gv.eintraege
        val um = jetzt.minusSeconds(60)
        for (z in meine) {
            val rest = ((z.endeUm.toEpochMilli() - um.toEpochMilli()) / 1000).toInt()
            val x = z.zeichen == Zeichen.HAMMER
            gv.gebotAbgegeben(z.werte.joinToString("-"), z.stufe, if (x) z.preis - 1 else z.preis, rest, um)
            if (x) gv.gesehen(Zeile(314, z.preis, null, rest - 30, true, z.werte, Zeichen.HAMMER, tippen = Punkt(0, 0)), jetzt.minusSeconds(30))
        }
        gv.eintraegeGezaehlt(vorher)
        return gv
    }

    /** This is how far a swipe down really moves the list in My Biddings (1.5 = slides on with momentum). */
    var wischFaktorMeine = 1.0

    /** My Biddings is scrolled down this far (pixels) – and this is where the list stands when opened. */
    var versatzMeine = 0
    var versatzMeineBeimOeffnen = 0

    /** The red X opens a window the bot does not know (instead of removing the row). */
    var kreuzOeffnetFenster = false

    /** The red X does not work (row stays). */
    var kreuzWirktNicht = false

    /**
     * Nov 23 (emulator runs 21 and 24): the list moved just before the tap on the red X – the next this-many X taps land
     * on a row in front (clock) and the game opens ITS Bid window instead (the X row stays).
     */
    var kreuzOeffnetBieten = 0

    /** The rows whose Bid window opened instead of the X (see [kreuzOeffnetBieten]). */
    val bietenStattKreuz = ArrayList<SimZeile>()

    /** After such a Bid window was closed: this many screenshots still show it (old screenshots in transit). */
    var altesBietenNachSchliessen = 0
    private var altesBietenRest = 0

    /** Does bidding again on an outbid bid (from My Biddings) need a free slot? (otherwise "-7") */
    var nachbietenBrauchtPlatz = false

    /** Taps while a foreign window was open (must stay empty). */
    val tippsImFremden = ArrayList<Punkt>()

    /** Where the Bid window was opened from – the game goes back there afterwards. */
    private var bietenAus = Ort.MARKT

    /** For this many images after "Bid" the game shows the loading screen (golden star). */
    var ladenNachBieten = 0
    private var ladenRest = 0

    private fun maxVersatzMeine(): Int = max(0, MEINE_ERSTE_OBEN + (meineListe().size - 1) * 181 - 1020)

    /** Rows in My Biddings with top edge (fully visible: 365 to 1069). */
    private fun meineSichtbar(): List<Pair<SimZeile, Int>> {
        versatzMeine = versatzMeine.coerceIn(0, maxVersatzMeine())
        return meineListe().mapIndexed { i, z -> z to MEINE_ERSTE_OBEN + i * 181 - versatzMeine }.filter { it.second in 365..1069 }
    }

    /** Can the price fields be filled in (the app's text input)? */
    var eingabeGeht = true

    /** Secondary password: does the game ask for it, and is the saved one correct? */
    var passwortNoetig = false
    var passwortRichtig = true
    private var passwortOk = false

    var sg: Int? = 10_000

    /** Does the price go up while the Bid window opens (someone else bids)? */
    var preisSprungImFenster = 0

    /** Does someone outbid every bid right away? */
    var sofortUeberbieten = false
    val gebotePreise = ArrayList<Int>()
    val handlungen = ArrayList<String>()
    var letzteEingabeOk = true
    private var letzter: Bildschirm? = null

    fun zeile(
        stufe: String, preis: Int, restSek: Long, vararg werte: Int, zeichen: Zeichen = Zeichen.KEINS, ohneZeit: Boolean = false,
        sofort: Int? = null,
    ): SimZeile = SimZeile(werte.toList(), preis, jetzt.plusSeconds(restSek), stufe, zeichen, ohneZeit, sofort).also {
        listen.getOrPut(stufe) { ArrayList() } += it
    }

    // ---------- Nov 22: buyout and lost connection ----------

    /** Nov 22: the price in the rows of the bidding list is not readable. */
    var preisUnlesbar = false

    /** What the Bid window shows as "Buyout Price" (e.g. misread). */
    var sofortAnzeige: (Int) -> Int? = { it }

    /** Is the "Buyout Price" button yellow (recognized) when the copy has a buyout? */
    var sofortKnopfGelb = true
    var nachBuyout = NachBuyout.KAUFEN

    /** Copies on which the bot tapped "Buyout Price" (in order). */
    val sofortGetippt = ArrayList<SimZeile>()
    var gekauft = 0

    /** Taps after the unknown confirmation window after Buyout appeared (must stay empty). */
    val tippsNachBuyout = ArrayList<Punkt>()
    private var buyoutFenster = false

    /** "Lost connection": is the yellow Confirm button recognized? Where does the game go after Confirm? */
    var verbindungKnopf = true
    var nachConfirm = Ort.MARKT

    /** After Confirm: this many loading images, and are filter and sort order reset (like a new start of the auction house)? */
    var ladenNachConfirm = 3
    var nachConfirmZurueckgesetzt = true
    val confirmGetipptUm = ArrayList<Instant>()

    /** Sort order cannot be changed (tap in the sort window does nothing). */
    var sortierungKlemmt = false

    /** The text input reports "ok", but the field still contains the old value afterwards. */
    var eingabeOhneWirkung = false

    /** Someone outbids my bid: price +1, hammer, SG back. */
    fun ueberbiete(z: SimZeile) {
        sg = sg?.plus(z.preis)
        z.preis += 1
        z.zeichen = Zeichen.HAMMER
    }

    private fun liste(): List<SimZeile> {
        val lo = filterMin.toIntOrNull() ?: 0
        val hi = filterMax.toIntOrNull() ?: Int.MAX_VALUE
        val l = (listen[gezeigterFilter] ?: emptyList()).filter { it.endeUm.isAfter(jetzt) && !it.gekauft && it.preis in lo..hi }
        return when (sortierung) {
            Sortierung.RANG -> l.sortedBy { it.endeUm } // Nov 9: ending soon at the top
            else -> l.sortedBy { it.preis }
        }
    }

    private fun sichtbar(): List<SimZeile> = sichtbarMitOben().map { it.first }

    /** Visible rows with their top edge (reference pixels). */
    private fun sichtbarMitOben(): List<Pair<SimZeile, Int>> =
        if (!pixelGenau) {
            liste().drop(oben).take(5).mapIndexed { i, z -> z to 314 + i * 181 }
        } else {
            // Like [Erkenner.kartenOben]: at most ~11 pixels cut off at the top, at the bottom top edge up to 1080.
            liste().mapIndexed { i, z -> z to 314 + i * 181 - versatz }.filter { it.second in 289..1080 }
        }

    // ---------- Nov 11: list pixel-exact (swipe path in pixels, rows can be half covered) ----------

    /** Scroll the list pixel-exact instead of always 3 whole rows. */
    var pixelGenau = false

    /** The list is scrolled down this far (pixels). */
    var versatz = 0

    /** This is how far a swipe really moves the list (1.0 = exactly the swipe path, 1.1 = with momentum). */
    var wischFaktor = 1.0

    /** The game "swallows" this many pixels at the start of every swipe. */
    var wischTotzone = 0

    /** For this many images after the swipe the list keeps sliding (by [rutschPx] each). */
    var nachrutschBilder = 0
    var rutschPx = 40

    /** Nov 12 (field test 1.31): short swipes (below 300 pixels) move the list this many times as far (null = like long ones). */
    var wischFaktorKurz: Double? = null

    /** Nov 12: only from this much finger travel does the list keep sliding after the finger is lifted. */
    var rutschMinZug = 0

    /** Nov 12 (field test 1.33): the next big swipe moves the list this much further once (outlier). */
    var naechsterWischExtra = 0
    private var rutschRest = 0
    private var rutschRichtung = 1

    /** Did the list still move on the last image? */
    var bewegtImLetztenBild = false
        private set

    /** Rows the bot tapped while the list was still sliding (must stay empty). */
    val getipptBeimRutschen = ArrayList<Punkt>()

    private fun maxVersatz(): Int = max(0, 314 + (liste().size - 1) * 181 - 1059)

    private fun nachOben() {
        oben = 0
        versatz = 0
        rutschRest = 0
    }

    // ---------- Nov 11: "+" tap at the top (purchase buttons in the bar) ----------

    /** For this many images after "Confirm" the game still shows the Bid window that is closing by itself. */
    var bidFensterNachConfirmBilder = 0
    private var schliessendRest = 0

    /** Taps at the top in the bar (y < 150) that did NOT hit a real red X of an open window. */
    val obenGetippt = ArrayList<Punkt>()

    /** The last image shown (for tests). */
    val letztesBild: Bildschirm? get() = letzter

    private val menue = mapOf(
        Knopf.MENUE_AUKTIONSMARKT to Punkt(321, 342),
        Knopf.MENUE_BIETERMARKT to Punkt(321, 427),
        Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488),
    )

    override fun lies(): Bildschirm {
        if (ladenRest > 0) {
            ladenRest--
            return Bildschirm(Ort.LADEN).also { letzter = it }
        }
        // Slow confirm window: the bid only goes through after [confirmVerzoegerungMs].
        bestaetigtBis?.let { if (!jetzt.isBefore(it)) { bestaetigtBis = null; gebotAbgeben() } }
        bewegtImLetztenBild = false
        if (rutschRest > 0) {
            rutschRest--
            val vorher = versatz
            versatz = (versatz + rutschRichtung * rutschPx).coerceIn(0, maxVersatz())
            bewegtImLetztenBild = versatz != vorher
        }
        if (altesBietenRest > 0 && ort == Ort.MEINE_GEBOTE) {
            // Nov 23: an old screenshot of the Bid window that was just closed (the game already shows My Biddings).
            altesBietenRest--
            return Bildschirm(
                Ort.BIETEN, aktuellesGebot = dialog?.preis,
                knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to Punkt(2105, 108)),
            ).also { letzter = it }
        }
        if (schliessendRest > 0 && ort == Ort.MARKT) {
            // The Bid window is closing: the image still shows it, but the game is already in the list.
            schliessendRest--
            return Bildschirm(
                Ort.BIETEN, aktuellesGebot = dialog?.preis,
                knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to Punkt(2105, 108)),
            ).also { letzter = it }
        }
        return bild()
    }

    /** Nov 9 (field test 1.9): "Bidding successful …" lies over row 2 for this long after every bid (0 = never). */
    var erfolgsMeldungMs = 0L

    /** The message lies over row 2 from the start and always (the bot has never seen the row free). */
    var meldungImmer = false

    /** Until when the message lies over the list (set after every bid, in the test also by hand). */
    var erfolgBis: Instant? = null

    /** A tap on a row under the message does not get through (the Bid window does not open). */
    var durchMeldungTippenWirktNicht = false

    /** How often the bot tapped a row under the message. */
    var durchMeldungGetippt = 0

    /** For this long after my bid the row still shows the old icon (e.g. the hammer). */
    var zeichenVerzoegerungMs = 0L
    private var zeichenAlt: Triple<SimZeile, Zeichen, Instant>? = null

    /** Like the real game: band from y 537 to 681 (reference phone) – covers row 2. */
    private val erfolgsMeldung = Meldung(1224, yOben = 537, yUnten = 681)

    private fun bild(): Bildschirm = when (ort) {
        Ort.MARKT -> {
            val meldung = if (meldungImmer || erfolgBis?.isAfter(jetzt) == true) erfolgsMeldung else null
            Bildschirm(
            Ort.MARKT,
            meldung = meldung,
            zeilen = sichtbarMitOben().map { (z, oben) ->
                val tippen = Punkt(1356, oben + 60)
                if (meldung != null && meldung.verdeckt(oben)) {
                    // Nothing is readable under the message – tapping still works (through the message).
                    Zeile(oben, null, null, null, false, listOf(null, null, null), Zeichen.KEINS, false, null, tippen, verdeckt = true)
                } else {
                    val rest = ((z.endeUm.toEpochMilli() - jetzt.toEpochMilli()) / 1000).toInt()
                    val alt = zeichenAlt?.takeIf { it.first === z && it.third.isAfter(jetzt) }
                    Zeile(
                        oben, if (preisUnlesbar) null else z.preis, z.sofort, if (z.ohneZeit) null else rest, !z.ohneZeit, z.werte,
                        alt?.second ?: z.zeichen, false, z.stufe.first(), tippen,
                    )
                }
            },
            sg = sg,
            sortierung = sortierung,
            knoepfe = menue + mapOf(
                Knopf.AKTUALISIEREN to Punkt(1688, 231), Knopf.SORTIEREN to Punkt(1895, 231), Knopf.FILTER to Punkt(2180, 231),
            ),
            wischWeg = Punkt(1356, 1080) to Punkt(1356, 520),
            )
        }
        Ort.FILTER -> Bildschirm(
            Ort.FILTER,
            filterFelder = KOPIE_TYPEN.mapIndexed { i, n -> FilterFeld(n, Punkt(800 + i * 100, 285), n in gewaehlt) },
            filterMinStellen = filterMin.length,
            filterMaxStellen = filterMax.length,
            knoepfe = mapOf(Knopf.FILTER_OK to Punkt(1547, 1018), Knopf.FILTER_MIN to Punkt(1153, 584), Knopf.FILTER_MAX to Punkt(1559, 584)),
        )
        Ort.SORTIEREN -> Bildschirm(
            Ort.SORTIEREN,
            knoepfe = mapOf(Knopf.SORT_PREIS_AUF to Punkt(1734, 381), Knopf.SORT_RANG_AUF to Punkt(1734, 499)),
        )
        Ort.BIETEN -> Bildschirm(
            Ort.BIETEN, aktuellesGebot = currentBidAnzeige(dialog!!.preis),
            meldung = when {
                meldungKurz -> Meldung(meldungBreite)
                toastBis?.isAfter(jetzt) == true -> Meldung(toastBreite)
                else -> null
            },
            sofortPreis = dialog!!.sofort?.let(sofortAnzeige),
            knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to Punkt(2105, 108)) +
                (if (dialog!!.sofort != null && sofortKnopfGelb) mapOf(Knopf.SOFORTKAUF to SOFORT_KNOPF) else emptyMap()),
        )
        Ort.BESTAETIGEN -> Bildschirm(
            Ort.BESTAETIGEN, bestaetigenPreis = bestaetigenAnzeige(dialog!!.preis + 1),
            // Like the real window: Confirm and the red X (the bot deliberately does not know Cancel).
            knoepfe = mapOf(Knopf.BESTAETIGEN to Punkt(1528, 804), Knopf.SCHLIESSEN to Punkt(1806, 301)),
        )
        Ort.ZWEITPASSWORT -> Bildschirm(
            Ort.ZWEITPASSWORT, knoepfe = mapOf(Knopf.PASSWORT_FELD to Punkt(1356, 630), Knopf.PASSWORT_OK to Punkt(1528, 804)),
        )
        // Like the game: my bids with remaining time, sorted by price, 4 fit on the screen.
        // Nov 11: in front = clock, outbid = red X at the top right (counts until it is pressed).
        Ort.MEINE_GEBOTE -> Bildschirm(
            Ort.MEINE_GEBOTE,
            laufendeGebote = if (zaehlerLesbar) biddingItems() else null,
            zeilen = meineSichtbar().map { (z, oben) ->
                val rest = ((z.endeUm.toEpochMilli() - jetzt.toEpochMilli()) / 1000).toInt()
                val x = z.zeichen == Zeichen.HAMMER
                Zeile(
                    oben, z.preis, null, rest, true, z.werte, if (x) Zeichen.KEINS else Zeichen.UHR, x, z.stufe.first(),
                    Punkt(1356, oben + 60), kreuzPunkt = if (x) Punkt(2293, oben + 14) else null,
                )
            },
            sg = sg,
            keineGebote = biddingItems() == 0,
            knoepfe = menue + (Knopf.AKTUALISIEREN to Punkt(1976, 231)),
        )
        Ort.VERBINDUNG_WEG -> Bildschirm(
            Ort.VERBINDUNG_WEG, knoepfe = if (verbindungKnopf) mapOf(Knopf.BESTAETIGEN to VERBINDUNG_CONFIRM) else emptyMap(),
        )
        else -> Bildschirm.UNBEKANNT
    }.also { letzter = it }

    /** How many sequences (several actions without a screenshot in between) the bot sent. */
    var folgen = 0

    fun ausfuehren(a: Aktion) {
        handlungen += a.grund
        when (a) {
            is Aktion.Wischen -> if (ort == Ort.MEINE_GEBOTE) {
                val zug = a.von.y - a.nach.y
                versatzMeine = (versatzMeine + (if (zug > 0) (zug * wischFaktorMeine).toInt() else zug)).coerceIn(0, maxVersatzMeine())
            } else if (ort == Ort.MARKT) {
                if (pixelGenau) {
                    val zug = a.von.y - a.nach.y // > 0: dragged up = further in the list
                    val faktor = if (kotlin.math.abs(zug) < 300) (wischFaktorKurz ?: wischFaktor) else wischFaktor
                    var betrag = (max(0, kotlin.math.abs(zug) - wischTotzone) * faktor).toInt()
                    if (zug >= 300) {
                        betrag += naechsterWischExtra
                        naechsterWischExtra = 0
                    }
                    rutschRichtung = if (zug >= 0) 1 else -1
                    versatz = (versatz + rutschRichtung * betrag).coerceIn(0, maxVersatz())
                    rutschRest = if (kotlin.math.abs(zug) >= rutschMinZug) nachrutschBilder else 0
                } else {
                    oben = min(oben + 3, max(0, liste().size - 5))
                }
            }
            is Aktion.Tippen -> tippen(a.punkt)
            is Aktion.Eingeben -> {
                letzteEingabeOk = eingabeGeht && ort == Ort.FILTER
                if (letzteEingabeOk && !eingabeOhneWirkung) {
                    when (a.feld) {
                        Punkt(1153, 584) -> filterMin = a.text
                        Punkt(1559, 584) -> filterMax = a.text
                    }
                }
            }
            is Aktion.PasswortEingeben -> letzteEingabeOk = ort == Ort.ZWEITPASSWORT
            is Aktion.Folge -> {
                folgen++
                // Like the app: one after the other, without a new image; result = did ALL inputs work?
                var alleOk = true
                for (s in a.schritte) {
                    ausfuehren(s)
                    if (s is Aktion.Eingeben && !letzteEingabeOk) alleOk = false
                }
                letzteEingabeOk = alleOk
            }
        }
    }

    /**
     * Nov 9: after "Bid" there is usually NO confirm window any more – the bid counts right away and the game
     * goes to the list. true = old behavior with "Join the bidding? Cancel / Confirm".
     */
    var mitBestaetigen = true

    /** After "Verify" the game goes straight to the list (the bid is placed with it) instead of back to the Bid window. */
    var passwortDannListe = false

    /** The tap on "Bid" does nothing (window stays open, no bid). */
    var bietenWirktNicht = false

    /** The bid does not arrive: the window closes, but no icon appears on the row. */
    var gebotGehtVerloren = false

    /** Width of the "-7" message (measured on the reference phone: 762). */
    var meldungBreite = 762

    /** What the bot reads in the confirm window (field test 1.8: 59 was read as 8). */
    var bestaetigenAnzeige: (Int) -> Int? = { it }

    /** What the bot reads as "Current Bid". */
    var currentBidAnzeige: (Int) -> Int? = { it }

    /** The red X in the confirm window does not work. */
    var bestaetigenXKlemmt = false

    /** Nov 9 (field test 1.8): after "Verify" briefly a message in the Bid window (this long, this wide). */
    var meldungNachVerifyMs = 0L
    var toastBreite = 762
    private var toastBis: Instant? = null

    /** This often a short message comes when tapping "Bid" that is NOT the limit (bid does not go through). */
    var fremdeMeldungBeimBieten = 0

    /** After "Confirm" the confirm window stays for this long. */
    var confirmVerzoegerungMs = 0L
    private var bestaetigtBis: Instant? = null

    /** Which rows the bot opened (values of the row, price in the list, when). */
    val geoeffnet = ArrayList<Triple<List<Int>, Int, Instant>>()

    /** Nov 11: rows whose red X the bot pressed, and taps in My Biddings that hit nothing. */
    val kreuzGedrueckt = ArrayList<SimZeile>()
    val fehlTippsMeine = ArrayList<Punkt>()

    /** Place a bid the way the game does (price +1, clock, SG gone). */
    private fun gebotAbgeben() {
        val d = dialog!!
        ort = bietenAus
        ladenRest = ladenNachBieten
        if (gebotGehtVerloren) return
        if (zeichenVerzoegerungMs > 0) zeichenAlt = Triple(d, d.zeichen, jetzt.plusMillis(zeichenVerzoegerungMs))
        if (erfolgsMeldungMs > 0) erfolgBis = jetzt.plusMillis(erfolgsMeldungMs)
        d.preis += 1
        d.zeichen = Zeichen.UHR
        if (d !in meine) meine += d
        weggedrueckt.remove(d) // bid again → back in My Biddings
        sg = sg?.minus(d.preis)
        gebotePreise += d.preis
        if (sofortUeberbieten) ueberbiete(d)
    }

    private fun tippen(p: Punkt) {
        val b = letzter!!
        val knopf = b.knoepfe.entries.firstOrNull { it.value == p }?.key
        if (buyoutFenster) tippsNachBuyout += p
        // The purchase buttons ("+") are in the top bar. Only the X of a REALLY open Bid window is allowed there.
        if (p.y < 150 && !(ort == Ort.BIETEN && knopf == Knopf.SCHLIESSEN)) obenGetippt += p
        if (ort == Ort.MARKT && bewegtImLetztenBild && b.zeilen.any { it.tippen == p }) getipptBeimRutschen += p
        when (ort) {
            Ort.MARKT -> when (knopf) {
                Knopf.FILTER -> {
                    ort = Ort.FILTER
                    gewaehlt = filter?.let { mutableSetOf(it) } ?: mutableSetOf()
                }
                Knopf.SORTIEREN -> ort = Ort.SORTIEREN
                Knopf.AKTUALISIEREN -> {
                    nachOben()
                    gezeigterFilter = filter
                }
                Knopf.MENUE_MEINE_GEBOTE -> {
                    ort = Ort.MEINE_GEBOTE
                    versatzMeine = versatzMeineBeimOeffnen
                }
                else -> {
                    val i = b.zeilen.indexOfFirst { it.tippen == p }
                    if (i >= 0 && b.zeilen[i].verdeckt) durchMeldungGetippt++
                    if (i >= 0 && !(b.zeilen[i].verdeckt && durchMeldungTippenWirktNicht)) {
                        val z = sichtbar()[i]
                        geoeffnet += Triple(z.werte, z.preis, jetzt)
                        dialog = z.also { it.preis += preisSprungImFenster }
                        ort = Ort.BIETEN
                    }
                }
            }
            Ort.FILTER -> if (knopf == Knopf.FILTER_OK) {
                filter = gewaehlt.singleOrNull()
                if (!filterBrauchtAktualisieren) gezeigterFilter = filter
                nachOben()
                ort = Ort.MARKT
            } else {
                val f = b.filterFelder.first { it.punkt == p }
                if (!gewaehlt.remove(f.stufe)) gewaehlt += f.stufe
            }
            Ort.SORTIEREN -> {
                if (!sortierungKlemmt) {
                    when (knopf) {
                        Knopf.SORT_RANG_AUF -> sortierung = Sortierung.RANG
                        Knopf.SORT_PREIS_AUF -> sortierung = Sortierung.PREIS
                        else -> Unit
                    }
                }
                ort = Ort.MARKT
            }
            Ort.BIETEN -> when (knopf) {
                Knopf.BIETEN -> when {
                    bietenWirktNicht -> Unit
                    fremdeMeldungBeimBieten > 0 -> {
                        fremdeMeldungBeimBieten--
                        meldungKurz = true
                    }
                    // Nov 11: bidding again on an own bid (not dismissed yet) already has its slot.
                    biddingItems() >= spielLimit && (nachbietenBrauchtPlatz || dialog !in meineListe()) -> meldungKurz = true
                    passwortNoetig && !passwortOk -> ort = Ort.ZWEITPASSWORT
                    mitBestaetigen -> ort = Ort.BESTAETIGEN
                    else -> gebotAbgeben()
                }
                Knopf.SCHLIESSEN -> {
                    ort = bietenAus
                    meldungKurz = false
                    if (dialog in bietenStattKreuz) altesBietenRest = altesBietenNachSchliessen
                }
                Knopf.SOFORTKAUF -> {
                    val d = dialog!!
                    sofortGetippt += d
                    when (nachBuyout) {
                        NachBuyout.KAUFEN -> {
                            sg = sg?.minus(d.sofort!!)
                            d.gekauft = true
                            gekauft++
                            ort = bietenAus
                        }
                        NachBuyout.BESTAETIGEN -> {
                            buyoutFenster = true
                            ort = Ort.BESTAETIGEN
                        }
                        NachBuyout.NICHTS -> Unit
                    }
                }
                else -> Unit
            }
            Ort.VERBINDUNG_WEG -> if (knopf == Knopf.BESTAETIGEN) {
                confirmGetipptUm += jetzt
                ladenRest = ladenNachConfirm
                ort = nachConfirm
                if (nachConfirmZurueckgesetzt) {
                    filter = null
                    gezeigterFilter = null
                    filterMin = "10"
                    filterMax = "99999999"
                    sortierung = Sortierung.PREIS
                    nachOben()
                }
            } else {
                tippsImFremden += p
            }
            Ort.ZWEITPASSWORT -> if (knopf == Knopf.PASSWORT_OK && passwortRichtig) {
                passwortOk = true
                if (meldungNachVerifyMs > 0) toastBis = jetzt.plusMillis(meldungNachVerifyMs)
                if (passwortDannListe) gebotAbgeben() else ort = Ort.BIETEN
            }
            Ort.BESTAETIGEN -> when (knopf) {
                Knopf.BESTAETIGEN -> if (confirmVerzoegerungMs > 0) {
                    if (bestaetigtBis == null) bestaetigtBis = jetzt.plusMillis(confirmVerzoegerungMs)
                } else {
                    gebotAbgeben()
                    schliessendRest = bidFensterNachConfirmBilder
                }
                Knopf.SCHLIESSEN -> if (!bestaetigenXKlemmt) ort = Ort.BIETEN
                else -> Unit
            }
            Ort.MEINE_GEBOTE -> when {
                knopf == Knopf.MENUE_BIETERMARKT -> {
                    ort = Ort.MARKT
                    bietenAus = Ort.MARKT
                }
                knopf != null -> Unit
                else -> {
                    val zx = b.zeilen.indexOfFirst { it.kreuzPunkt == p }
                    val zr = b.zeilen.indexOfFirst { it.tippen == p }
                    val sicht = meineSichtbar()
                    when {
                        zx >= 0 && kreuzOeffnetBieten > 0 -> {
                            // The list moved: a row in front lies under the finger now – its Bid window opens.
                            kreuzOeffnetBieten--
                            val vorne = meineListe().firstOrNull { it.zeichen == Zeichen.UHR } ?: sicht[zx].first
                            bietenStattKreuz += vorne
                            geoeffnet += Triple(vorne.werte, vorne.preis, jetzt)
                            dialog = vorne
                            bietenAus = Ort.MEINE_GEBOTE
                            ort = Ort.BIETEN
                        }
                        zx >= 0 -> {
                            val z = sicht[zx].first
                            kreuzGedrueckt += z
                            when {
                                kreuzOeffnetFenster -> ort = Ort.UNBEKANNT
                                !kreuzWirktNicht -> weggedrueckt += z
                            }
                        }
                        zr >= 0 -> {
                            val z = sicht[zr].first
                            geoeffnet += Triple(z.werte, z.preis, jetzt)
                            dialog = z.also { it.preis += preisSprungImFenster }
                            bietenAus = Ort.MEINE_GEBOTE
                            ort = Ort.BIETEN
                        }
                        else -> fehlTippsMeine += p
                    }
                }
            }
            Ort.UNBEKANNT -> tippsImFremden += p
            else -> Unit
        }
    }
}

/** Nov 22: where the attrappe shows the yellow "Buyout Price" button and the Confirm of "Lost connection". */
val SOFORT_KNOPF = Punkt(1641, 935)
val VERBINDUNG_CONFIRM = Punkt(1356, 795)

class AuktionsBotTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 8, 23, 0).atZone(BERLIN).toInstant()

    /** Nov 9: rule numbers = filter values (C- 10–20 → rows up to 20 SG, bid at most 21). */
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)
    private val bMinus = Suchregel("B-", 10, 60, maxRestzeitMin = 5)

    /** Lets the bot run [schritte] times. Returns the last result. */
    private fun laufen(
        bot: AuktionsBot,
        spiel: SpielAttrappe,
        schritte: Int,
        jeSchritt: (vorher: Instant, meldungDa: Boolean, Ergebnis) -> Unit = { _, _, _ -> },
        bis: (Ergebnis) -> Boolean = { false },
    ): Ergebnis {
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(schritte) {
            val vorher = spiel.jetzt
            val meldungDa = spiel.ort == Ort.MARKT && (spiel.meldungImmer || spiel.erfolgBis?.isAfter(vorher) == true)
            e = bot.schritt(spiel.jetzt)
            jeSchritt(vorher, meldungDa, e)
            when (val x = e) {
                is Ergebnis.Handeln -> {
                    spiel.ausfuehren(x.aktion)
                    val a = x.aktion
                    if (a is Aktion.Eingeben || a is Aktion.PasswortEingeben || (a is Aktion.Folge && a.hatEingaben)) {
                        bot.eingabeErgebnis(spiel.letzteEingabeOk)
                    }
                    spiel.jetzt = spiel.jetzt.plusMillis(x.naechsterSchrittMs)
                }
                is Ergebnis.Warten -> spiel.jetzt = spiel.jetzt.plusMillis(x.ms)
                is Ergebnis.Unsicher -> spiel.jetzt = spiel.jetzt.plusMillis(x.ms)
                is Ergebnis.Fertig -> return x
                Ergebnis.Pausiert -> spiel.jetzt = spiel.jetzt.plusSeconds(1)
            }
            if (bis(e)) return e
        }
        return e
    }

    @Test
    fun `whole flow - filter with price fields, sort order Awakened Tier, ending soon up to max price plus 1`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 10, 7000, 1, 1, 1) // still runs for almost 2 hours → do not bid
        spiel.zeile("C-", 26, 60, 6, 6, 6) // too expensive – the game's filter does not even show it
        spiel.zeile("C-", 19, 100, 3, 3, 3)
        spiel.zeile("C-", 12, 6000, 2, 2, 2)
        spiel.zeile("C-", 17, 90, 5, 5, 5)
        spiel.zeile("C-", 20, 200, 4, 4, 4)
        spiel.zeile("B-", 55, 60, 9, 9, 9)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })

        laufen(bot, spiel, 120) { spiel.gebotePreise.size >= 3 && spiel.ort == Ort.MARKT }

        assertEquals("C-", spiel.filter, "filter switched to C-")
        assertEquals("10", spiel.filterMin, "left price field")
        assertEquals("20", spiel.filterMax, "right price field (instead of 99999999)")
        assertEquals(Sortierung.RANG, spiel.sortierung, "sorted by Awakened Tier ↑")
        assertEquals(listOf(18, 20, 21), spiel.gebotePreise, "17→18, 19→20, 20→21 (row at 20 = max price → bid 21)")
        assertEquals(3, bot.gebote.abgegebeneGebote)
        assertTrue(log.any { it.contains("Filter set to C-") })
        assertTrue(spiel.handlungen.none { it.contains("Buyout", ignoreCase = true) }, "never Buyout")
    }

    @Test
    fun `after bidding only later ending ones at the top - refresh instead of swiping on`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        for (i in 2..9) spiel.zeile("C-", 10 + i, 3000L + i, i, i, i) // only end in 50 minutes
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 120) { it is Ergebnis.Handeln && it.aktion.grund.contains("refresh") && spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertEquals(0, spiel.handlungen.count { it == "further down" }, "no swiping needed")
    }

    @Test
    fun `end of list - first swipe through, then refresh and start over at the top`() {
        val spiel = SpielAttrappe(start)
        // All end soon, but I am in front everywhere (clock) – there is nothing to do.
        for (i in 1..9) spiel.zeile("C-", 10 + i, 100L + i, i, i, i, zeichen = Zeichen.UHR)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 120) { it is Ergebnis.Handeln && it.aktion.grund.contains("round over") }
        // Nov 11 (field test 1.20): the first swipe of a run measures first ("further down (measure first)").
        assertTrue(spiel.handlungen.count { it.startsWith("further down") } >= 2, "first swipe through the list")
        assertTrue(spiel.handlungen.last().contains("refresh"))
        assertEquals(0, spiel.oben)
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `price fields cannot be filled in - go on, the bot checks the prices itself`() {
        val spiel = SpielAttrappe(start)
        spiel.eingabeGeht = false
        spiel.zeile("C-", 26, 60, 6, 6, 6) // too expensive – the game's filter lets it through (99999999)
        spiel.zeile("C-", 18, 90, 5, 5, 5)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(false, bot.preisfelderGehen)
        assertTrue(log.any { it.contains("cannot be filled in") })
        assertEquals(listOf(19), spiel.gebotePreise, "only the row up to 20, not the one for 26")
    }

    @Test
    fun `list still shows the old rank after Confirm - refreshing helps`() {
        val spiel = SpielAttrappe(start)
        spiel.filterBrauchtAktualisieren = true
        // Before, C was set (purple badge); B- is wanted (green badge).
        spiel.zeile("C", 15, 60, 7, 7, 7)
        spiel.zeile("B-", 40, 60, 1, 1, 1)
        val bot = AuktionsBot(listOf(bMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertTrue(spiel.handlungen.any { it.contains("list still shows") && it.contains("refresh") })
        assertEquals(listOf(41), spiel.gebotePreise)
    }

    @Test
    fun `input reports ok, but the field still says 99999999 - check prices ourselves`() {
        val spiel = SpielAttrappe(start)
        spiel.eingabeOhneWirkung = true
        spiel.zeile("C-", 26, 60, 6, 6, 6)
        spiel.zeile("C-", 18, 90, 5, 5, 5)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(false, bot.preisfelderGehen)
        assertTrue(log.any { it.contains("characters instead of") })
        assertEquals(listOf(19), spiel.gebotePreise)
    }

    @Test
    fun `rows without remaining time (auction over) are skipped`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 12, 30, 1, 1, 1, ohneZeit = true)
        spiel.zeile("C-", 14, 40, 2, 2, 2, ohneZeit = true)
        spiel.zeile("C-", 16, 90, 3, 3, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(17), spiel.gebotePreise, "only the row with remaining time")
    }

    @Test
    fun `sort order cannot be changed - try at most twice, then bid anyway`() {
        val spiel = SpielAttrappe(start)
        spiel.sortierungKlemmt = true
        spiel.zeile("C-", 16, 90, 3, 3, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(2, spiel.handlungen.count { it.contains("sort order to Awakened Tier") })
        assertEquals(listOf(17), spiel.gebotePreise)
    }

    @Test
    fun `connection lost - stop right away, tap nothing, screen off`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        // Nov 22: 0 reconnects = the behaviour before (stop at once).
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, maxNeuverbindungen = 0)
        laufen(bot, spiel, 3) // bot has started (filter opened …)
        val vorher = spiel.handlungen.size
        spiel.ort = Ort.VERBINDUNG_WEG
        val e = laufen(bot, spiel, 10)
        assertEquals(Ergebnis.Fertig(StoppGrund.VERBINDUNG_WEG), e)
        assertEquals(vorher, spiel.handlungen.size, "no more tapping")
        assertTrue(StoppGrund.VERBINDUNG_WEG.sperren)
        assertEquals("Lost connection to the game – bot stopped", StoppGrund.VERBINDUNG_WEG.text)
    }

    @Test
    fun `unknown screen - never tap, just wait`() {
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { Bildschirm.UNBEKANNT })
        repeat(20) {
            val e = bot.schritt(start.plusSeconds(it * 2L))
            assertFalse(e is Ergebnis.Handeln, "no tapping on an unknown image")
        }
    }

    @Test
    fun `button not recognized - do not tap`() {
        // Bidding list without a recognized filter button → uncertain instead of tapping somewhere.
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { Bildschirm(Ort.MARKT, sg = 100) })
        val e = bot.schritt(start)
        assertTrue(e is Ergebnis.Unsicher, "$e")
    }

    @Test
    fun `price in the Bid window went up - close the window instead of bidding too much`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 19, 100, 1, 2, 3)
        spiel.preisSprungImFenster = 5 // the window then shows 24 > 20
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 60)
        assertTrue(spiel.gebotePreise.isEmpty(), "nothing bid")
        assertTrue(spiel.handlungen.any { it.contains("too expensive") })
    }

    @Test
    fun `clock = I am in front - do not bid again`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 19, 100, 1, 2, 3, zeichen = Zeichen.UHR)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 40)
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `hammer = outbid - bid again up to max price plus 1`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 20, 100, 1, 2, 3, zeichen = Zeichen.HAMMER)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 60) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(21), spiel.gebotePreise)
    }

    @Test
    fun `status -7 in the Bid window = 50 bids - My Biddings once, wait, then go on`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 18, 280, 1, 2, 3)
        spiel.laufendeGebote = 50 // 50 bids by hand – the bot placed none of them itself (no remaining times)
        spiel.spielLimit = 50
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 400) { bot.gebote.limitGemeldet }
        assertTrue(bot.gebote.limitGemeldet, "limit recognized")
        assertTrue(spiel.handlungen.any { it == "close Bid window (limit)" }, spiel.handlungen.joinToString("\n"))
        // Nov 9 (field test 1.10/1.11): now – and only now – open My Biddings.
        laufen(bot, spiel, 80) { bot.gebote.wiederBietenAb != null && spiel.ort == Ort.MARKT }
        assertEquals(1, spiel.handlungen.count { it.startsWith("My Biddings") }, spiel.handlungen.joinToString("\n"))
        assertTrue(log.any { it.contains("no remaining time readable") && it.contains("bidding again from") }, log.joinToString("\n"))
        val ab = bot.gebote.wiederBietenAb!!
        val warten = java.time.Duration.between(spiel.jetzt, ab).toMillis()
        assertTrue(warten in 59_000..60_000, "without remaining time: wait 60 s ($warten ms)")
        // Until then open nothing and do not bid.
        val vorher = spiel.geoeffnet.size
        laufen(bot, spiel, 400) { !spiel.jetzt.isBefore(ab.minusSeconds(1)) }
        assertEquals(vorher, spiel.geoeffnet.size, "no row opened during the waiting time")
        // Auctions end → room again: after the waiting time it goes on.
        spiel.laufendeGebote = 30
        laufen(bot, spiel, 80) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(19), spiel.gebotePreise)
        assertFalse(spiel.geoeffnet.drop(vorher).any { it.third.isBefore(ab) }, "not before the waiting time")
        // Nov 11: our own count was still at 50 – after the waiting time a short look into My Biddings
        // (now 30, still room), then bid.
        assertEquals(2, spiel.handlungen.count { it.startsWith("My Biddings") }, spiel.handlungen.joinToString("\n"))
        assertTrue(log.any { it.contains("30 bids running") && it.contains("still room, no cleanup needed") }, log.joinToString("\n"))
    }

    @Test
    fun `after -7 wait until the earliest end from My Biddings plus 5 s`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.spielLimit = 5 // here the game only allows 5 bids at the same time
        spiel.zaehlerLesbar = false // counter unreadable, My Biddings full (4 rows) → only the remaining times count
        spiel.zeile("C-", 15, 100, 1, 1, 1) // ends first (after 100 s)
        spiel.zeile("C-", 16, 160, 2, 2, 2)
        spiel.zeile("C-", 17, 170, 3, 3, 3)
        spiel.zeile("C-", 18, 180, 4, 4, 4)
        spiel.zeile("C-", 19, 190, 5, 5, 5)
        spiel.zeile("C-", 14, 280, 6, 6, 6)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 400) { bot.gebote.wiederBietenAb != null && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16, 17, 18, 19, 20), spiel.gebotePreise, "five bids, the sixth got -7")
        assertEquals(1, spiel.handlungen.count { it.startsWith("My Biddings") }, spiel.handlungen.joinToString("\n"))
        val ab = bot.gebote.wiederBietenAb!!
        // Earliest end = copy 1 (start + 100 s), plus 5 s.
        assertTrue(!ab.isBefore(start.plusSeconds(104)) && !ab.isAfter(start.plusSeconds(105)), "$ab\n" + log.joinToString("\n"))
        assertTrue(log.any { it.contains("first bid ends at") }, log.joinToString("\n"))
        val geoeffnetVorher = spiel.geoeffnet.size
        laufen(bot, spiel, 600) { spiel.gebotePreise.size >= 6 }
        assertEquals(listOf(16, 17, 18, 19, 20, 15), spiel.gebotePreise, "after copy 1 ended there is room again")
        assertTrue(spiel.geoeffnet.drop(geoeffnetVorher).all { !it.third.isBefore(ab) }, "only opened again after the waiting time")
        val sechstes = spiel.geoeffnet.last().third
        assertTrue(java.time.Duration.between(ab, sechstes).toMillis() < 3000, "the same copy tried again right after the waiting time")
        assertEquals(1, spiel.handlungen.count { it.startsWith("My Biddings") }, log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n"))
    }

    @Test
    fun `after -7 wait at most 2 minutes in one go`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.spielLimit = 1
        spiel.zaehlerLesbar = false
        spiel.zeile("C-", 15, 500, 1, 1, 1) // my bid – only ends in a good 8 minutes
        spiel.zeile("C-", 16, 520, 2, 2, 2)
        val bot = AuktionsBot(listOf(cMinus.copy(maxRestzeitMin = 15)), StoppBedingung(), spiel)
        laufen(bot, spiel, 400) { bot.gebote.wiederBietenAb != null }
        val ab = bot.gebote.wiederBietenAb!!
        val warten = java.time.Duration.between(spiel.jetzt, ab).toMillis()
        // Nov 11: if cleaning up freed nothing – about 2 minutes until the next attempt (before: 5).
        assertTrue(warten in 119_000..120_000, "at most 2 minutes in one go ($warten ms)")
    }

    @Test
    fun `never My Biddings without -7 - not at the start, not after minutes, not even with many own bids`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        for (i in 1..12) spiel.zeile("C-", 10 + (i % 8), 30L + i * 40, i, i, i)
        spiel.laufendeGebote = 30
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 1500) { java.time.Duration.between(start, spiel.jetzt).toMinutes() >= 12 }
        assertTrue(spiel.gebotePreise.size >= 5, "bids were placed: ${spiel.gebotePreise}")
        assertTrue(spiel.handlungen.none { it.startsWith("My Biddings") }, spiel.handlungen.filter { it.startsWith("My") }.toString())
        assertFalse(bot.gebote.limitGemeldet)
    }

    @Test
    fun `second rule - filter to B- after two empty passes`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 30, 100, 1, 1, 1) // too expensive for C- (filter hides it)
        spiel.zeile("B-", 55, 100, 2, 2, 2)
        val bot = AuktionsBot(listOf(cMinus, bMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 150) { spiel.gebotePreise.isNotEmpty() }
        assertEquals("B-", spiel.filter)
        assertEquals("60", spiel.filterMax)
        assertEquals(listOf(56), spiel.gebotePreise)
    }

    @Test
    fun `outbid 5 times in a row - other rank`() {
        val spiel = SpielAttrappe(start)
        spiel.sofortUeberbieten = true
        for (i in 1..6) spiel.zeile("C-", 11, 200L + i, i, i, i)
        spiel.zeile("B-", 40, 100, 9, 9, 9)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus, bMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 300) { spiel.filter == "B-" }
        assertEquals("B-", spiel.filter)
        assertTrue(log.any { it.contains("times in a row") })
    }

    @Test
    fun `secondary password - enter it, Verify, then bidding goes on`() {
        val spiel = SpielAttrappe(start)
        spiel.passwortNoetig = true
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, hatZweitpasswort = true)
        laufen(bot, spiel, 80) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertTrue(spiel.handlungen.contains("enter secondary password"))
        assertTrue(spiel.handlungen.contains("Verify"))
        assertTrue(spiel.handlungen.none { it.contains("Forgot", ignoreCase = true) })
    }

    @Test
    fun `secondary password missing - wait with a hint, do not bid`() {
        val spiel = SpielAttrappe(start)
        spiel.passwortNoetig = true
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, hatZweitpasswort = false)
        laufen(bot, spiel, 60) { bot.passwortFehlt }
        assertTrue(bot.passwortFehlt)
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `secondary password wrong - try once, then stop`() {
        val spiel = SpielAttrappe(start)
        spiel.passwortNoetig = true
        spiel.passwortRichtig = false
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, hatZweitpasswort = true)
        val e = laufen(bot, spiel, 100)
        assertEquals(Ergebnis.Fertig(StoppGrund.ZWEITPASSWORT_FALSCH), e)
        assertEquals(1, spiel.handlungen.count { it == "Verify" }, "do not try several times")
    }

    @Test
    fun `too little SG - do not bid, wait`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 5
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 60) { bot.zuWenigSg }
        assertTrue(bot.zuWenigSg)
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    // ---------------- SG budget (Nov 9) ----------------

    @Test
    fun `budget - a bid that would exceed it is not placed`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 19, 100, 1, 1, 1)
        spiel.zeile("C-", 19, 120, 2, 2, 2)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 30), spiel)
        laufen(bot, spiel, 150)
        assertEquals(listOf(20), spiel.gebotePreise, "second bid (20) would plan 40 > 30")
        assertEquals(1000, bot.startSg)
        assertTrue(bot.budgetVerplant)
    }

    @Test
    fun `budget - refund after being outbid makes room again`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.zeile("C-", 15, 150, 2, 2, 2)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 20), spiel)
        laufen(bot, spiel, 80) { spiel.gebotePreise.size == 1 && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16), spiel.gebotePreise)
        laufen(bot, spiel, 10)
        assertEquals(1, spiel.gebotePreise.size, "budget planned (4 left)")
        // Someone outbids → 16 SG back → room again.
        spiel.ueberbiete(spiel.listen["C-"]!!.first())
        laufen(bot, spiel, 80) { spiel.gebotePreise.size >= 2 }
        assertEquals(2, spiel.gebotePreise.size)
    }

    @Test
    fun `budget - SG not readable - never bid`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = null
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 500), spiel, protokoll = { log += it })
        laufen(bot, spiel, 60)
        assertTrue(spiel.gebotePreise.isEmpty())
        assertTrue(log.any { it.contains("not readable") })
    }

    @Test
    fun `budget - done only when no bids are running any more`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 20), spiel)
        laufen(bot, spiel, 60) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        val zwischen = laufen(bot, spiel, 5)
        assertFalse(zwischen is Ergebnis.Fertig, "bid still running → wait")
        val e = laufen(bot, spiel, 200)
        assertEquals(Ergebnis.Fertig(StoppGrund.BUDGET_ERREICHT), e)
        assertTrue(StoppGrund.BUDGET_ERREICHT.sperren, "screen off afterwards")
    }

    // Nov 23 (review: "the SG budget can grow"): the budget counts what this run spends, net – a higher balance never adds to it.

    @Test
    fun `budget - SG topped up during the run do not add to the budget`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 19, 100, 1, 1, 1)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 30), spiel, protokoll = { log += it })
        laufen(bot, spiel, 80) { spiel.gebotePreise.size == 1 && spiel.ort == Ort.MARKT }
        assertEquals(listOf(20), spiel.gebotePreise)
        // The player buys 1000 SG in the shop, a new copy appears. Before: budget 30 − (1000 − 1980) = 1010 SG left.
        spiel.sg = spiel.sg!! + 1000
        spiel.zeile("C-", 19, 120, 2, 2, 2)
        laufen(bot, spiel, 150)
        assertEquals(listOf(20), spiel.gebotePreise, "20 of 30 SG spent – the next bid (20) does not fit, top-up or not")
        assertTrue(bot.budgetVerplant)
        assertEquals(20, bot.sgVerplant, "shown: 20 of 30 SG")
        assertEquals(2000, bot.startSg, "start raised by the 1000 SG that came in (balance 1980 + 20 in the running bid)")
        assertTrue(
            log.any { it == "SG balance 1980 is above the start (1000) – SG came in from outside, the budget still counts only what this run spends (start now 2000)" },
            log.joinToString("\n"),
        )
    }

    @Test
    fun `budget - start balance misread (a digit lost, the same twice) does not add to the budget`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 19, 100, 1, 1, 1)
        spiel.zeile("C-", 19, 120, 2, 2, 2)
        var bot: AuktionsBot? = null
        var danach = 2
        // Until the bot has taken its start balance (and two images more), the SG display reads "100" instead of "1000".
        val leser = BildschirmLeser {
            val b = spiel.lies()
            val sg = b.sg
            if (sg != null && (bot?.startSg == null || danach-- > 0)) b.copy(sg = sg / 10) else b
        }
        val log = ArrayList<String>()
        bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 30), leser, protokoll = { log += it })
        laufen(bot, spiel, 250)
        // Before: budget 30 − (100 − 980) = 910 SG left – both copies.
        assertEquals(listOf(20), spiel.gebotePreise, log.joinToString("\n"))
        assertEquals(1000, bot.startSg, "the real start (balance + the bid placed meanwhile)")
        assertTrue(log.any { it.startsWith("SG at start: 100 ") }, log.joinToString("\n"))
        assertTrue(log.any { it.startsWith("SG balance") && it.contains("is above the start (100)") }, log.joinToString("\n"))
    }

    @Test
    fun `budget - normal run - outbid bids give their SG back, the budget is used up net, then stop`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        spiel.zeile("C-", 15, 210, 2, 2, 2)
        spiel.zeile("C-", 15, 220, 3, 3, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 40), spiel)
        laufen(bot, spiel, 120) { spiel.gebotePreise.size == 2 && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16, 16), spiel.gebotePreise, "32 of 40 – the third (16) does not fit")
        laufen(bot, spiel, 6)
        assertEquals(2, spiel.gebotePreise.size)
        assertTrue(bot.budgetVerplant)
        // Outbid on the first one (now 17): its 16 SG come back (net 16 spent) → room again: the bot bids again on it (18).
        spiel.ueberbiete(spiel.listen["C-"]!!.first())
        laufen(bot, spiel, 120) { spiel.gebotePreise.size >= 3 }
        assertEquals(listOf(16, 16, 18), spiel.gebotePreise)
        assertEquals(34, bot.sgVerplant)
        // When the auctions are over: 2 copies bought for 34 SG, 6 left – less than the cheapest bid → budget used up.
        val e = laufen(bot, spiel, 600)
        assertEquals(Ergebnis.Fertig(StoppGrund.BUDGET_ERREICHT), e)
        assertEquals(2, bot.gebote.gekaufteKopien)
        assertEquals(966, spiel.sg)
    }

    @Test
    fun `budget - start SG is kept after a restart within the same run`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 990 // 10 SG were already planned before the restart
        spiel.zeile("C-", 19, 100, 1, 1, 1)
        spiel.zeile("C-", 19, 120, 2, 2, 2)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 30), spiel, startSg = 1000)
        laufen(bot, spiel, 150)
        assertEquals(listOf(20), spiel.gebotePreise, "20 left → only one bid of 20")
        assertEquals(1000, bot.startSg)
    }

    @Test
    fun `goal reached - auction over and last in front - stop with screen off`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 19, 60, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(zielKopien = 1), spiel)
        val e = laufen(bot, spiel, 400)
        assertEquals(Ergebnis.Fertig(StoppGrund.ZIEL_ERREICHT), e)
        assertEquals(1, bot.gebote.gekaufteKopien)
        assertEquals(20L, bot.gebote.ausgegebenSg)
    }

    @Test
    fun `nothing recognized for too long - stop without screen off`() {
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { Bildschirm.UNBEKANNT }, takt = Takt(maxUnbekanntInFolge = 3))
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(5) { e = bot.schritt(start) }
        assertEquals(Ergebnis.Fertig(StoppGrund.NICHTS_ERKANNT), e)
    }

    @Test
    fun `no rule for the filter - stop right away`() {
        val bot = AuktionsBot(listOf(Suchregel("D", 1, 5)), StoppBedingung(), { Bildschirm.UNBEKANNT })
        assertEquals(Ergebnis.Fertig(StoppGrund.KEINE_REGEL), bot.schritt(start))
    }

    @Test
    fun `foreign confirm window is closed with the red X, never with Cancel`() {
        val leser = BildschirmLeser {
            Bildschirm(Ort.BESTAETIGEN, bestaetigenPreis = 99, knoepfe = mapOf(Knopf.SCHLIESSEN to Punkt(1806, 301), Knopf.BESTAETIGEN to Punkt(1528, 804)))
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        val e = bot.schritt(start)
        assertEquals(Punkt(1806, 301), ((e as Ergebnis.Handeln).aktion as Aktion.Tippen).punkt)
    }

    // ---------------- Nov 9 (field test 1.7): bidding without a confirm window, speed ----------------

    @Test
    fun `without a confirm window - bid counts, and the clock on the row confirms it`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        spiel.zeile("C-", 17, 110, 4, 5, 6)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 120) { spiel.gebotePreise.size >= 2 && log.count { it.startsWith("Bid arrived") } >= 2 }
        assertEquals(listOf(16, 18), spiel.gebotePreise)
        assertEquals(2, bot.gebote.abgegebeneGebote)
        assertTrue(log.count { it.contains("without confirm window") } == 2, log.joinToString("\n"))
        assertEquals(2, log.count { it.startsWith("Bid arrived: clock") }, log.joinToString("\n"))
        assertTrue(spiel.handlungen.none { it.startsWith("Confirm") })
    }

    @Test
    fun `bid does not arrive (no icon on the row) - does not count`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.gebotGehtVerloren = true
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 120) { log.any { it.contains("did NOT arrive") } }
        assertTrue(log.any { it.contains("did NOT arrive") }, log.joinToString("\n"))
        assertEquals(0, bot.gebote.abgegebeneGebote, "does not count")
        assertEquals(0, bot.gebote.offeneGebote(), "not remembered as a running bid")
        assertEquals(0, bot.gebote.laufend)
    }

    @Test
    fun `Bid tap does nothing - close the window after a short time, bid does not count`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.bietenWirktNicht = true
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 80) { spiel.handlungen.lastOrNull()?.startsWith("close Bid window") == true }
        assertEquals("close Bid window (Bid had no effect)", spiel.handlungen.last())
        assertTrue(log.any { it.contains("still open") })
        assertEquals(0, bot.gebote.abgegebeneGebote)
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `secondary password, then the list right away - bid counts without a second Bid`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.passwortNoetig = true
        spiel.passwortDannListe = true
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, hatZweitpasswort = true, protokoll = { log += it })
        laufen(bot, spiel, 80) { log.any { it.startsWith("Bid arrived") } }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertEquals(1, bot.gebote.abgegebeneGebote)
        assertTrue(log.any { it.contains("after the secondary password") }, log.joinToString("\n"))
        assertEquals(1, spiel.handlungen.count { it.startsWith("Bid for") }, "Bid only once")
    }

    @Test
    fun `secondary password, then the Bid window again - Bid once more, exactly one bid`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.passwortNoetig = true
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, hatZweitpasswort = true)
        laufen(bot, spiel, 80) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        laufen(bot, spiel, 10)
        assertEquals(listOf(16), spiel.gebotePreise)
        assertEquals(1, bot.gebote.abgegebeneGebote)
        assertEquals(2, spiel.handlungen.count { it.startsWith("Bid for") }, "before and after the password")
    }

    @Test
    fun `after the password Current Bid is already at my bid - do NOT bid again`() {
        // After the password the Bid window already shows 16 (my bid) → close instead of outbidding myself.
        val schirme = ArrayDeque(
            listOf(
                Bildschirm(Ort.ZWEITPASSWORT, knoepfe = mapOf(Knopf.PASSWORT_FELD to Punkt(1356, 630), Knopf.PASSWORT_OK to Punkt(1528, 804))),
                Bildschirm(Ort.ZWEITPASSWORT, knoepfe = mapOf(Knopf.PASSWORT_FELD to Punkt(1356, 630), Knopf.PASSWORT_OK to Punkt(1528, 804))),
                Bildschirm(Ort.BIETEN, aktuellesGebot = 16, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 681), Knopf.SCHLIESSEN to Punkt(2105, 108))),
                Bildschirm(Ort.BIETEN, aktuellesGebot = 16, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 681), Knopf.SCHLIESSEN to Punkt(2105, 108))),
                Bildschirm(Ort.BIETEN, aktuellesGebot = 16, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 681), Knopf.SCHLIESSEN to Punkt(2105, 108))),
                Bildschirm(Ort.BIETEN, aktuellesGebot = 16, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 681), Knopf.SCHLIESSEN to Punkt(2105, 108))),
                Bildschirm(Ort.BIETEN, aktuellesGebot = 16, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 681), Knopf.SCHLIESSEN to Punkt(2105, 108))),
            ),
        )
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        var echt = true
        val leser = BildschirmLeser { if (echt) spiel.lies() else schirme.removeFirst() }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser, hatZweitpasswort = true)
        // Until "Bid" is tapped, with the fake; then the prepared images.
        laufen(bot, spiel, 60) { it is Ergebnis.Handeln && it.aktion.grund.startsWith("Bid for") }
        echt = false
        val pw = bot.schritt(spiel.jetzt) as Ergebnis.Handeln
        assertTrue(pw.aktion is Aktion.PasswortEingeben)
        bot.eingabeErgebnis(true)
        assertEquals("Verify", (bot.schritt(spiel.jetzt) as Ergebnis.Handeln).aktion.grund)
        // Nov 11: first image after the password window → first wait for a second image (the X is at the top).
        assertTrue(bot.schritt(spiel.jetzt.plusSeconds(1)) is Ergebnis.Warten)
        // Nov 11 (long run: "+" tap): the bid is in – the window usually closes by itself. Wait first …
        val w = bot.schritt(spiel.jetzt.plusMillis(1300))
        assertTrue(w is Ergebnis.Warten && w.grund.contains("by itself"), "$w")
        assertEquals(1, bot.gebote.abgegebeneGebote)
        assertTrue(bot.schritt(spiel.jetzt.plusMillis(2500)) is Ergebnis.Warten, "not the X yet")
        // … and only if it is still open after 10 s (Nov 11, review: slow network), close it with the red X
        // (never Bid again).
        assertTrue(bot.schritt(spiel.jetzt.plusMillis(9000)) is Ergebnis.Warten, "not the X yet after 9 s")
        val e = bot.schritt(spiel.jetzt.plusMillis(10_500)) as Ergebnis.Handeln
        assertEquals(Punkt(2105, 108), (e.aktion as Aktion.Tippen).punkt, "close, do not Bid again")
        assertEquals(1, bot.gebote.abgegebeneGebote)
    }

    @Test
    fun `Bid window without a found Bid button - never tap anywhere, then close`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        var ohneKnopf = false
        val leser = BildschirmLeser {
            val s = spiel.lies()
            if (ohneKnopf && s.ort == Ort.BIETEN) s.copy(knoepfe = s.knoepfe - Knopf.BIETEN) else s
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        laufen(bot, spiel, 60) { spiel.ort == Ort.BIETEN }
        ohneKnopf = true
        val ergebnisse = ArrayList<Ergebnis>()
        laufen(bot, spiel, 30) { ergebnisse += it; spiel.ort == Ort.MARKT }
        assertTrue(spiel.handlungen.none { it.startsWith("Bid for") }, "never Bid without a found button")
        assertTrue(ergebnisse.any { it is Ergebnis.Unsicher }, "wait first")
        assertTrue(spiel.handlungen.last().contains("Bid button not found"))
    }

    @Test
    fun `filter in one go - one sequence for rank and both price fields, then Confirm once`() {
        val spiel = SpielAttrappe(start)
        spiel.filter = "C"
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 40) { bot.filterStufe == "C-" }
        assertEquals("C-", spiel.filter)
        assertEquals("10", spiel.filterMin)
        assertEquals("20", spiel.filterMax)
        assertEquals(1, spiel.folgen, "everything in one sequence")
        assertEquals(1, spiel.handlungen.count { it.contains("confirm filter") }, "Confirm once")
        assertTrue(log.any { it.startsWith("Filter set to C-") && it.contains("⏱") }, log.joinToString("\n"))
    }

    @Test
    fun `filter in one go does not work - go on step by step`() {
        val spiel = SpielAttrappe(start)
        spiel.eingabeOhneWirkung = true // reports ok, field stays 99999999
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 60) { bot.filterStufe == "C-" }
        assertTrue(log.any { it.contains("did not fully work") }, log.joinToString("\n"))
        assertEquals(false, bot.preisfelderGehen, "price fields do not work → check prices ourselves")
        assertEquals("C-", bot.filterStufe)
    }

    @Test
    fun `timing - sort order and bid are in the log with their duration`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 120) { log.any { it.startsWith("Bid placed:") } }
        assertTrue(log.any { it.startsWith("⏱ Sorted by Awakened Tier") }, log.joinToString("\n"))
        assertTrue(log.any { it.startsWith("Bid placed:") && it.contains("⏱") }, log.joinToString("\n"))
    }

    @Test
    fun `SG balance never readable yet - no bid, reported clearly, readable afterwards - it goes on`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.sg = null
        spiel.zeile("C-", 15, 300, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 40)
        assertTrue(spiel.gebotePreise.isEmpty())
        assertTrue(bot.sgUnlesbar)
        assertEquals(1, log.count { it.startsWith("SG balance at the top right not readable") }, "report only once")
        spiel.sg = 500
        laufen(bot, spiel, 60) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertFalse(bot.sgUnlesbar)
        assertTrue(log.any { it.startsWith("SG balance readable again") })
    }

    @Test
    fun `unknown image right after a tap (window opening) - look again quickly`() {
        val schirme = ArrayDeque(
            listOf(
                Bildschirm.UNBEKANNT, // without a tap before → wait normally long
                Bildschirm(Ort.MARKT, sg = 100, knoepfe = mapOf(Knopf.FILTER to Punkt(2180, 231))),
                Bildschirm.UNBEKANNT, // right after "open filter" → again quickly
            ),
        )
        val t = Takt()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { schirme.removeFirst() }, takt = t)
        assertEquals(t.unbekanntMs, (bot.schritt(start) as Ergebnis.Unsicher).ms)
        assertTrue(bot.schritt(start) is Ergebnis.Handeln, "open filter")
        // Nov 11: shortly after an action an unknown image is "game loading" – just wait (not "uncertain").
        assertEquals(t.uebergangPollMs, (bot.schritt(start.plusMillis(500)) as Ergebnis.Warten).ms)
    }

    @Test
    fun `My Biddings - counter not readable, list not full - the rows are all bids`() {
        fun zeile(i: Int) = Zeile(374 + i * 181, 33, null, 600, true, listOf(i, i, i), Zeichen.UHR, false, 'C', Punkt(1356, 434 + i * 181))
        val menue = mapOf(Knopf.MENUE_BIETERMARKT to Punkt(321, 427), Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488))
        // Nov 11 (long run): the list is at the very top (first row at 374) – only then are few rows "all of them".
        for ((n, erwartet) in listOf(2 to 2, 4 to null)) {
            val log = ArrayList<String>()
            val gv = GebotsVerwaltung()
            val bot = AuktionsBot(
                listOf(cMinus), StoppBedingung(),
                { Bildschirm(Ort.MEINE_GEBOTE, zeilen = (0 until n).map { zeile(it) }, laufendeGebote = null, sg = 100, knoepfe = menue) },
                gebote = gv, protokoll = { log += it },
            )
            bot.schritt(start)
            assertEquals(erwartet, gv.spielZahl, "$n rows")
            assertTrue(log.any { it.contains("counter not readable") }, log.joinToString("\n"))
        }
    }

    @Test
    fun `SG briefly unreadable - the balance read last still applies`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 300, 1, 2, 3)
        var sgWeg = false
        val leser = BildschirmLeser { spiel.lies().let { if (sgWeg && it.ort == Ort.MARKT) it.copy(sg = null) else it } }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        laufen(bot, spiel, 40) { bot.filterStufe == "C-" }
        sgWeg = true
        laufen(bot, spiel, 60) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(16), spiel.gebotePreise, "single unreadable images do not block")
    }

    // ---------------- Nov 9 (field test 1.8): never Cancel, misread amounts, row lock, messages ----------------

    /** How long since a row was first opened (for the 60 s lock). */
    private fun seitErstemOeffnen(spiel: SpielAttrappe): Long =
        spiel.geoeffnet.firstOrNull()?.let { java.time.Duration.between(it.third, spiel.jetzt).toMillis() } ?: 0

    @Test
    fun `confirm amount misread (59 as 8) - no abort, no loop, Confirm`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("B-", 58, 100, 1, 2, 3)
        spiel.bestaetigenAnzeige = { if (it == 59) 8 else it } // this is how 1.8 read it
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 150) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(59), spiel.gebotePreise)
        assertEquals(1, spiel.geoeffnet.size, "row opened only once")
        assertTrue(log.any { it.contains("misread (8 SG") }, log.joinToString("\n"))
        assertTrue(spiel.handlungen.none { it.contains("with X") || it.contains("abort") || it.contains("Cancel") })
        assertEquals("Confirm 59 SG", spiel.handlungen.last { it.startsWith("Confirm") })
    }

    @Test
    fun `confirm really shows too much - red X instead of Cancel, Bid window closed, row locked for 60 s`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 18, 280, 1, 2, 3)
        spiel.bestaetigenAnzeige = { 25 } // at most 21 allowed
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 600) { seitErstemOeffnen(spiel) >= 55_000 }
        assertTrue(seitErstemOeffnen(spiel) >= 55_000)
        assertTrue(spiel.gebotePreise.isEmpty(), "nothing bid")
        assertTrue(spiel.handlungen.none { it.startsWith("Confirm") })
        val x = spiel.handlungen.indexOf("close confirm window with X (25 SG too expensive)")
        assertTrue(x >= 0, spiel.handlungen.joinToString("\n"))
        assertEquals("close Bid window (no bid open)", spiel.handlungen[x + 1])
        assertEquals(1, spiel.geoeffnet.size, "not reopened in a loop")
        // The lock is only short: after a minute the row may be tried again.
        laufen(bot, spiel, 200) { spiel.geoeffnet.size >= 2 }
        assertEquals(2, spiel.geoeffnet.size)
        assertTrue(java.time.Duration.between(spiel.geoeffnet[0].third, spiel.geoeffnet[1].third).toMillis() >= 60_000)
    }

    @Test
    fun `after a failed attempt do not open the same row again for 60 s`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.bietenWirktNicht = true
        spiel.zeile("C-", 15, 280, 1, 2, 3)
        spiel.zeile("C-", 17, 290, 4, 5, 6)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 600) { seitErstemOeffnen(spiel) >= 55_000 }
        assertTrue(seitErstemOeffnen(spiel) >= 55_000)
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6)), spiel.geoeffnet.map { it.first }, "every row exactly once")
        assertEquals(0, bot.gebote.abgegebeneGebote)
    }

    @Test
    fun `confirm window does not close with X - stop, never Cancel, never Confirm`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 18, 280, 1, 2, 3)
        spiel.bestaetigenAnzeige = { 25 }
        spiel.bestaetigenXKlemmt = true
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        val e = laufen(bot, spiel, 300)
        assertEquals(Ergebnis.Fertig(StoppGrund.FENSTER_KLEMMT), e)
        assertTrue(spiel.handlungen.none { it.startsWith("Confirm") })
        assertEquals(MAX_X_VERSUCHE, spiel.handlungen.count { it.startsWith("close confirm window with X") })
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `message right after Verify is not the limit`() {
        val spiel = SpielAttrappe(start)
        spiel.passwortNoetig = true
        spiel.laufendeGebote = 1
        spiel.meldungNachVerifyMs = 1500
        spiel.toastBreite = 762 // worst case: as wide as the real -7
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, hatZweitpasswort = true)
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertFalse(bot.gebote.limitGemeldet, log.joinToString("\n"))
        assertTrue(log.none { it.contains("50 bids reached") })
        assertEquals(listOf(16), spiel.gebotePreise)
        // Only waited until the message was gone – not closed, not reopened.
        assertTrue(spiel.handlungen.none { it.contains("limit") }, spiel.handlungen.joinToString("\n"))
        assertEquals(1, spiel.geoeffnet.size)
    }

    @Test
    fun `short message like -7, My Biddings shows only 1 bid - false alarm, keep bidding right away`() {
        val spiel = SpielAttrappe(start)
        spiel.laufendeGebote = 1
        spiel.fremdeMeldungBeimBieten = 1 // as wide as -7, but it is not
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        spiel.zeile("C-", 17, 200, 4, 5, 6)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 200) { spiel.gebotePreise.isNotEmpty() }
        assertFalse(bot.gebote.limitGemeldet)
        assertTrue(log.any { it.contains("that was not the 50 limit") }, log.joinToString("\n"))
        val x = spiel.handlungen.indexOf("close Bid window (limit)")
        assertTrue(x >= 0, spiel.handlungen.joinToString("\n"))
        assertEquals(1, spiel.handlungen.drop(x).count { it.startsWith("My Biddings") }, "checked once")
        assertEquals(listOf(16), spiel.gebotePreise, "the same row again right away – without waiting time")
    }

    @Test
    fun `false alarm, but the message comes again with the next bid - then surely the limit, wait`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.spielLimit = 2 // counter shows 2 (far below 45) – still the game says "-7"
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.zeile("C-", 16, 110, 2, 2, 2)
        spiel.zeile("C-", 17, 120, 3, 3, 3)
        spiel.zeile("C-", 18, 130, 4, 4, 4)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 400) { bot.gebote.wiederBietenAb != null && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16, 17), spiel.gebotePreise)
        assertEquals(1, log.count { it.contains("that was not the 50 limit") }, log.joinToString("\n"))
        assertTrue(bot.gebote.limitSicher, "second message after a new attempt = certain")
        assertEquals(2, spiel.handlungen.count { it.startsWith("My Biddings") }, "no back and forth")
        // Earliest end: copy 1 after 100 s → wait until then (+5 s).
        val ab = bot.gebote.wiederBietenAb!!
        assertTrue(!ab.isBefore(start.plusSeconds(104)) && !ab.isAfter(start.plusSeconds(105)), "$ab")
    }

    @Test
    fun `short message, counter unknown, form like -7 - that is the limit`() {
        val leser = BildschirmLeser {
            Bildschirm(Ort.BIETEN, aktuellesGebot = 15, meldung = Meldung(780), knoepfe = mapOf(Knopf.SCHLIESSEN to Punkt(2105, 108)))
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        // Nov 11: first image of the Bid window → do not tap the X at the top yet (window could be closing).
        assertTrue(bot.schritt(start) is Ergebnis.Warten)
        val e = bot.schritt(start.plusMillis(300))
        assertEquals(Punkt(2105, 108), ((e as Ergebnis.Handeln).aktion as Aktion.Tippen).punkt)
        assertTrue(bot.gebote.limitGemeldet)
    }

    @Test
    fun `short message, counter unknown, but a completely different form - not the limit`() {
        val leser = BildschirmLeser {
            Bildschirm(Ort.BIETEN, aktuellesGebot = 15, meldung = Meldung(1200), knoepfe = mapOf(Knopf.SCHLIESSEN to Punkt(2105, 108)))
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        bot.schritt(start)
        assertFalse(bot.gebote.limitGemeldet)
    }

    @Test
    fun `row above the max price is never tapped (even if the game's filter shows it)`() {
        val spiel = SpielAttrappe(start)
        spiel.eingabeGeht = false // price fields do not work → the list also shows rows that are too expensive
        spiel.zeile("C-", 21, 60, 1, 1, 1)
        spiel.zeile("C-", 25, 70, 2, 2, 2)
        spiel.zeile("C-", 60, 80, 3, 3, 3)
        spiel.zeile("C-", 20, 90, 4, 4, 4) // exactly the max price → bidding 21 is allowed
        spiel.zeile("C-", 99, 100, 5, 5, 5)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 400)
        assertTrue(spiel.geoeffnet.isNotEmpty())
        assertTrue(spiel.geoeffnet.all { it.second <= 20 }, "opened: ${spiel.geoeffnet.map { it.second }}")
        assertEquals(listOf(21), spiel.gebotePreise)
    }

    @Test
    fun `Current Bid read lower than in the list - counts as not readable, never bid blindly`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("B-", 53, 100, 1, 2, 3)
        spiel.currentBidAnzeige = { if (it == 53) 3 else it } // first digit lost
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 150) { spiel.geoeffnet.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertTrue(log.any { it.contains("Current Bid misread (3") }, log.joinToString("\n"))
        // Nov 11 (review): earlier "list price + 1" was bid then. Now never blindly: window closed, no bid.
        assertTrue(spiel.gebotePreise.isEmpty(), "${spiel.gebotePreise}")
        assertTrue(spiel.handlungen.any { it.startsWith("close Bid window (Current Bid not readable)") }, spiel.handlungen.joinToString("\n"))
    }

    @Test
    fun `Confirm tapped, window closes slowly - wait, do not tap the X`() {
        val spiel = SpielAttrappe(start)
        spiel.confirmVerzoegerungMs = 1500
        spiel.zeile("C-", 15, 100, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 150) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertTrue(spiel.handlungen.none { it.contains("with X") }, spiel.handlungen.joinToString("\n"))
        assertEquals(1, spiel.handlungen.count { it.startsWith("Confirm") })
    }

    // ---------------- Nov 9 (field test 1.9): "Bidding successful" message, remembered rows, just bid ----------------

    @Test
    fun `old way (switched off) - Bidding successful message, bid on a covered row from memory`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.erfolgsMeldungMs = 3500
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        spiel.zeile("C-", 16, 90, 2, 2, 2)
        spiel.zeile("C-", 17, 120, 3, 3, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, durchMeldungTippen = true)
        laufen(bot, spiel, 200) { spiel.gebotePreise.size >= 2 && spiel.ort == Ort.MARKT }
        assertTrue(spiel.geoeffnet.size >= 2, "two rows opened: ${spiel.handlungen}")
        val erste = spiel.geoeffnet[0]
        val zweite = spiel.geoeffnet[1]
        assertEquals(listOf(1, 1, 1), erste.first)
        assertEquals(listOf(2, 2, 2), zweite.first, "row 2 lay under the message – still bid using what was remembered")
        val abstand = java.time.Duration.between(erste.third, zweite.third).toMillis()
        assertTrue(abstand < 3500, "did not wait for the message to disappear ($abstand ms)")
        assertEquals(listOf(16, 17), spiel.gebotePreise.take(2))
        assertTrue(log.any { it.contains("\"Bidding successful\" message (1-1-1)") }, log.joinToString("\n"))
    }

    @Test
    fun `old way (switched off) - covered row the bot has never seen free is not tapped`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.meldungImmer = true
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        spiel.zeile("C-", 16, 210, 2, 2, 2) // always lies under the message
        spiel.zeile("C-", 17, 220, 3, 3, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, durchMeldungTippen = true)
        laufen(bot, spiel, 200) { spiel.gebotePreise.size >= 2 && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16, 18), spiel.gebotePreise, "rows 1 and 3 bid on")
        assertTrue(spiel.geoeffnet.none { it.first == listOf(2, 2, 2) }, "row 2 never opened")
    }

    @Test
    fun `remembered rows - covered, swallowed, own bid, other copy at the same position`() {
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { Bildschirm.UNBEKANNT })
        fun z(i: Int, id: Int, preis: Int, rest: Int, zeichen: Zeichen = Zeichen.KEINS) =
            Zeile(314 + i * 181, preis, null, rest, true, listOf(id, id, id), zeichen, false, 'C', Punkt(1356, 374 + i * 181))
        fun verdeckt(i: Int) =
            Zeile(314 + i * 181, null, null, null, false, listOf(null, null, null), Zeichen.KEINS, false, null, Punkt(1356, 374 + i * 181), verdeckt = true)
        val m = Meldung(1224, yOben = 537, yUnten = 681)

        val sauber = listOf(z(0, 1, 15, 100), z(1, 2, 16, 200), z(2, 3, 17, 300))
        assertEquals(sauber, bot.zeilenMitGemerktem(Bildschirm(Ort.MARKT, zeilen = sauber), start), "without a message: as read")
        assertEquals(3, bot.gemerkteZeilen)

        // 2 s later the message lies over row 2: values from memory, remaining time counted down.
        val t1 = start.plusSeconds(2)
        val mit = bot.zeilenMitGemerktem(Bildschirm(Ort.MARKT, meldung = m, zeilen = listOf(z(0, 1, 15, 98), verdeckt(1), z(2, 3, 17, 298))), t1)
        assertEquals(listOf("1-1-1", "2-2-2", "3-3-3"), mit.map { it.id })
        assertTrue(mit[1].verdeckt)
        assertEquals(16, mit[1].preis)
        assertEquals(198, mit[1].restzeitSek)
        assertEquals(Punkt(1356, 555), mit[1].tippen, "the tap goes through the message onto the row")

        // The band swallowed the card completely (no row recognized) – it still comes from memory.
        val ohneKarte = bot.zeilenMitGemerktem(Bildschirm(Ort.MARKT, meldung = m, zeilen = listOf(z(0, 1, 15, 98), z(2, 3, 17, 298))), t1)
        assertEquals(listOf("1-1-1", "2-2-2", "3-3-3"), ohneKarte.map { it.id })

        // Then bid on row 2 → the remembered icon is outdated, it counts as mine (clock).
        bot.gebote.gebotAbgegeben("2-2-2", "C-", 17, 198, t1)
        val danach = bot.zeilenMitGemerktem(Bildschirm(Ort.MARKT, meldung = m, zeilen = listOf(z(0, 1, 15, 97), verdeckt(1), z(2, 3, 17, 297))), t1.plusSeconds(1))
        assertEquals(Zeichen.UHR, danach[1].zeichen)

        // Free rows without a readable ID change nothing.
        val unlesbar = Zeile(314, null, null, null, true, listOf(null, null, null), Zeichen.KEINS, false, 'C', Punkt(1356, 374))
        val halb = bot.zeilenMitGemerktem(Bildschirm(Ort.MARKT, meldung = m, zeilen = listOf(unlesbar, verdeckt(1))), t1.plusSeconds(1))
        assertEquals(16, halb[1].preis)

        // At position 1 there is a different copy (reloaded/scrolled) → forget everything, covered row without numbers.
        val neu = bot.zeilenMitGemerktem(Bildschirm(Ort.MARKT, meldung = m, zeilen = listOf(z(0, 9, 15, 90), verdeckt(1), z(2, 3, 17, 290))), t1.plusSeconds(3))
        assertEquals(null, neu[1].preis)
        assertEquals(null, neu[1].id)
    }

    @Test
    fun `just bid again, row still shows the hammer - do not bid again right away`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeichenVerzoegerungMs = 3000
        spiel.zeile("C-", 15, 100, 1, 1, 1, zeichen = Zeichen.HAMMER) // I was outbid
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 150) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16), spiel.gebotePreise)
        val nach = spiel.jetzt
        laufen(bot, spiel, 60) { java.time.Duration.between(nach, spiel.jetzt).toMillis() > 4500 }
        assertEquals(listOf(16), spiel.gebotePreise, "bid again only once, although the row still showed the hammer for 3 s")
        assertEquals(1, spiel.geoeffnet.size)
    }

    @Test
    fun `message covers a row the bot does not know - do not swipe on, wait and then bid`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.erfolgBis = start.plusSeconds(30) // e.g. the user bid by hand shortly before the start
        spiel.zeile("C-", 15, 100, 1, 1, 1, zeichen = Zeichen.UHR) // I am already in front
        spiel.zeile("C-", 15, 110, 2, 2, 2) // lies under the message, never seen free
        spiel.zeile("C-", 15, 120, 3, 3, 3, zeichen = Zeichen.UHR)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 300) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16), spiel.gebotePreise, "row 2 bid on as soon as it is free")
        assertTrue(!spiel.jetzt.isBefore(start.plusSeconds(30)), "only after the message")
        val vorDemGebot = spiel.handlungen.takeWhile { !it.startsWith("bid") }
        assertTrue(vorDemGebot.none { it == "further down" }, "did not swipe past the covered row: $vorDemGebot")
        assertEquals(0, spiel.durchMeldungGetippt, "never tapped blindly through the message")
    }

    @Test
    fun `old way (switched off) - tapping through the message does not work, then the bot waits again`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.erfolgsMeldungMs = 3500
        spiel.durchMeldungTippenWirktNicht = true
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        spiel.zeile("C-", 16, 210, 2, 2, 2)
        spiel.zeile("C-", 17, 220, 3, 3, 3)
        spiel.zeile("C-", 18, 230, 4, 4, 4)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, durchMeldungTippen = true)
        laufen(bot, spiel, 300) { log.any { it.startsWith("Tapping through the") } }
        assertTrue(log.any { it.startsWith("Tapping through the \"Bidding successful\" message does not work") }, log.joinToString("\n"))
        assertEquals(1, spiel.durchMeldungGetippt)

        // From now on the message lies over row 2 for a long time – even after its lock (60 s) is over, the bot does not
        // tap through there any more, but waits.
        spiel.meldungImmer = true
        val ab = spiel.jetzt
        laufen(bot, spiel, 400) { java.time.Duration.between(ab, spiel.jetzt).toSeconds() > 80 }
        assertEquals(1, spiel.durchMeldungGetippt, "after the first failure never tapped through the message again")
        assertEquals(1, log.count { it.startsWith("Tapping through the") }, "reported only once")

        // Message gone → row 2 is free and gets bid on.
        spiel.meldungImmer = false
        spiel.erfolgsMeldungMs = 0
        laufen(bot, spiel, 300) { 17 in spiel.gebotePreise && spiel.ort == Ort.MARKT }
        assertEquals(setOf(16, 17, 18, 19), spiel.gebotePreise.toSet(), "all four copies bid on: ${spiel.gebotePreise}")
        assertEquals(1, spiel.durchMeldungGetippt)
    }

    // ---------------- Nov 9 (field test 1.10/1.11): message over the list → tap nothing ----------------

    // Nov 11 22:05 (field test 1.20): "In normal bidding the bot should just keep bidding, no matter which message
    // comes – except the error message (-7). And just remember where the individual buttons are." This replaces the rule
    // from Nov 9 ("with a message tap nothing, wait").
    @Test
    fun `Bidding successful message - keep bidding, every bid counted only once`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.erfolgsMeldungMs = 3500
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        spiel.zeile("C-", 16, 90, 2, 2, 2)
        spiel.zeile("C-", 17, 120, 3, 3, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        val getipptMitMeldung = ArrayList<String>()
        var gewartet = 0
        laufen(bot, spiel, 300, jeSchritt = { _, meldungDa, e ->
            if (meldungDa && e is Ergebnis.Handeln) getipptMitMeldung += e.aktion.grund
            if (meldungDa && e is Ergebnis.Warten) gewartet++
        }) { spiel.gebotePreise.size >= 3 && spiel.ort == Ort.MARKT && log.count { it.startsWith("Bid placed:") } >= 3 }
        laufen(bot, spiel, 20) // a few more images: nothing twice
        assertEquals(listOf(16, 17, 18), spiel.gebotePreise)
        assertTrue(getipptMitMeldung.isNotEmpty(), "kept bidding despite the message")
        assertTrue(spiel.obenGetippt.isEmpty(), "never tapped at the top: ${spiel.obenGetippt}")
        // No longer wait 3.5 s for the message to disappear.
        val abstaende = (1 until spiel.geoeffnet.size).map {
            java.time.Duration.between(spiel.geoeffnet[it - 1].third, spiel.geoeffnet[it].third).toMillis()
        }
        assertTrue(abstaende.any { it < 3500 }, "faster than the message: $abstaende")
        // Every bid counted exactly once – the message still standing from the previous bid does not count for the next one.
        assertEquals(3, log.count { it.startsWith("Bid placed:") }, log.joinToString("\n"))
        assertEquals(3, bot.gebote.offeneGebote(), log.joinToString("\n"))
    }

    @Test
    fun `message does not go away - bot still bids on the free row, never tapped at the top`() {
        // Nov 11 22:05: keep bidding, no matter which message (except -7). The message lies over row 2; row 1 is free.
        val spiel = SpielAttrappe(start)
        spiel.meldungImmer = true
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertTrue(spiel.obenGetippt.isEmpty(), "never tapped at the top: ${spiel.obenGetippt}")
    }

    @Test
    fun `message over the list - the bot must know what lies under it`() {
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { Bildschirm.UNBEKANNT })
        val m = Meldung(1224, yOben = 537, yUnten = 681)
        fun z(i: Int, id: Int?, verdeckt: Boolean = false) = Zeile(
            314 + i * 181, if (id == null) null else 15, null, 100, id != null, listOf(id, id, id), Zeichen.KEINS, false, 'C',
            Punkt(1356, 374 + i * 181), verdeckt = verdeckt,
        )
        fun pruefe(meldung: Meldung?, vararg zeilen: Zeile) = bot.unbekanntUnterMeldung(Bildschirm(Ort.MARKT, meldung = meldung, zeilen = zeilen.toList()), zeilen.toList())

        assertFalse(pruefe(null, z(0, 1), z(1, null, verdeckt = true), z(2, 3)), "never without a message")
        assertTrue(pruefe(m, z(0, 1), z(1, null, verdeckt = true), z(2, 3)), "covered and unknown")
        assertFalse(pruefe(m, z(0, 1), z(1, 2, verdeckt = true), z(2, 3)), "covered, but remembered")
        assertTrue(pruefe(m, z(0, 1), z(2, 3)), "card completely swallowed by the band (gap between row 1 and 3)")
        assertFalse(pruefe(m, z(0, 1)), "band below the last row (list at its end)")
        assertFalse(pruefe(Meldung(1224), z(0, 1), z(2, 3)), "message without a known position: only covered rows count")
        assertEquals(0, m.ueberlappung(676), "row 3 starts exactly at the bottom edge of the band")
        assertTrue(m.verdeckt(495))
        assertFalse(m.verdeckt(676))
    }
}
