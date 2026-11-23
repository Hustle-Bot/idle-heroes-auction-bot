package io.github.hustlebot.ihauctionbot.attrappe

import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import kotlin.random.Random

/** Nov 11: settings of the fake game (for the emulator test and the tests on the PC). */
data class SpielEinstellung(
    /** From this many entries in My Biddings on, the fake game reports "-7" (like the game at 50). */
    val limit: Int = 50,
    /** This many bids are already in My Biddings at the start (from earlier; some outbid). */
    val startEintraege: Int = 0,
    /** The bidding list keeps roughly this many copies ready (C-, 10–19 SG). */
    val kopienImMarkt: Int = 16,
    /** This often "someone else" outbids one of my bids (0 = never). */
    val ueberbietenAlleMs: Long = 25_000,
    /** After every n-th bid, briefly the loading screen (0 = never). */
    val ladenJedesNteGebot: Int = 5,
    /** The red X opens a foreign window (instead of removing the row) – the bot must stop then. */
    val kreuzOeffnetFenster: Boolean = false,
    /** Does bidding again on a bid that is already in My Biddings need a free slot? */
    val nachbietenBrauchtPlatz: Boolean = false,
    /** My Biddings is scrolled down this far when it opens. */
    val meineVersatzBeimOeffnen: Int = 0,
    /** Secondary password: the fake game asks for it on the first "Bid" (null = never). */
    val passwort: String? = null,
    /** On every n-th bid the confirm window "Join the bidding?" appears (0 = never). */
    val bestaetigenJedesNte: Int = 0,
    val seed: Long = 11,
)

/**
 * Nov 11: fake game – imitates the Idle Heroes auction house (bidding list, Bid window, filter,
 * sorting, My Biddings with the red X, "-7", loading screen, "Bidding successful" message). Reacts to taps and
 * swipes like the game and writes everything to the [log]. Forbidden taps (into the purchase bar at the top, Buyout, on the
 * loading screen …) are counted in [verboten].
 *
 * All points in reference pixels (2712 × 1220, landscape). The time comes from outside (milliseconds).
 */
class Spiel(val bauer: Bildbauer, val einst: SpielEinstellung = SpielEinstellung(), start: Long = 0, private val fremd: Bild? = null) {

    enum class Ansicht { MARKT, BIETEN, PASSWORT, BESTAETIGEN, FILTER, SORTIEREN, MEINE, FREMD }
    enum class Stand { KEINS, VORNE, UEBERBOTEN }

    class Kopie(val nr: Int, val werte: List<Int>, var preis: Int, val endeUm: Long) {
        var stand = Stand.KEINS
        var weg = false
        val id: String get() = werte.joinToString("-")
    }

    private val zufall = Random(einst.seed)
    val kopien = ArrayList<Kopie>()
    private var naechsteNr = 1

    var ansicht = Ansicht.MARKT
        private set
    private var dialog: Kopie? = null
    private var dialogAus = Ansicht.MARKT
    var versatzMarkt = 0
        private set
    var versatzMeine = 0
        private set
    var filterMin = 10
        private set
    var filterMax = 99_999_999
        private set

    /** Text in the price fields of the filter window (while it is open) and which field has the focus. */
    var filterText = arrayOf("10", "99999999")
        private set
    var fokus: Int = -1
        private set

    /** Secondary password: what is in the field and whether it was accepted yet. */
    var passwortText = ""
        private set
    var passwortOk = false
        private set

    /** The confirm window was already answered with Confirm for the current bid. */
    private var bestaetigt = false

    private var meldungBis = 0L
    private var minus7Bis = 0L
    private var ladenBis = 0L
    private var letztesUeberbieten = start

    val log = ArrayList<String>()
    var protokoll: (String) -> Unit = {}
    var verboten = 0
        private set
    var gebote = 0
        private set
    var nachgebote = 0
        private set
    var weggedrueckt = 0
        private set
    var limitMeldungen = 0
        private set
    var gewonnen = 0
        private set

    init {
        // Bids from earlier: some in front, some outbid (expensive and cheap ones), so that My Biddings has something to do.
        repeat(einst.startEintraege) { i ->
            val k = neueKopie(start, preis = 12 + (i % 9), rest = 600L + 37L * i)
            k.stand = if (i % 3 == 0) Stand.UEBERBOTEN else Stand.VORNE
            if (k.stand == Stand.UEBERBOTEN && i % 2 == 0) k.preis = 25 + i % 5 // too expensive (above the filter)
        }
        auffuellen(start)
    }

    // ---------------- State ----------------

    private fun laeuft(k: Kopie, jetzt: Long) = k.endeUm > jetzt

    /** What My Biddings shows: my bids (in front or outbid, X not pressed yet), sorted by price. */
    fun meineListe(jetzt: Long): List<Kopie> =
        kopien.filter { it.stand != Stand.KEINS && !it.weg && laeuft(it, jetzt) }.sortedWith(compareBy({ it.preis }, { it.nr }))

    fun eintraege(jetzt: Long): Int = meineListe(jetzt).size

    /** Bidding list: within the filter range, ending soon at the top ("Awakened Tier ↑"). */
    fun marktListe(jetzt: Long): List<Kopie> =
        kopien.filter { laeuft(it, jetzt) && it.preis in filterMin..filterMax }.sortedWith(compareBy({ it.endeUm }, { it.nr }))

    private fun neueKopie(jetzt: Long, preis: Int, rest: Long): Kopie {
        val nr = naechsteNr++
        val k = Kopie(nr, listOf(1000 + nr * 13 % 900, 2000 + nr * 7 % 900, 10 + nr % 30), preis, jetzt + rest * 1000)
        kopien += k
        return k
    }

    private fun auffuellen(jetzt: Long) {
        while (kopien.count { laeuft(it, jetzt) && it.stand == Stand.KEINS } < einst.kopienImMarkt) {
            neueKopie(jetzt, preis = 10 + zufall.nextInt(10), rest = 40L + zufall.nextInt(600))
        }
    }

    /** Time passes: auctions end, someone outbids me, new copies come. */
    fun weiter(jetzt: Long) {
        val vorbei = kopien.filter { !laeuft(it, jetzt) }
        for (k in vorbei) {
            if (k.stand == Stand.VORNE && !k.weg) {
                gewonnen++
                schreibe(jetzt, "GEWONNEN ${k.id} ${k.preis}")
            }
            kopien.remove(k)
        }
        if (einst.ueberbietenAlleMs > 0 && jetzt - letztesUeberbieten >= einst.ueberbietenAlleMs) {
            letztesUeberbieten = jetzt
            val vorne = kopien.filter { it.stand == Stand.VORNE && !it.weg && laeuft(it, jetzt) }
            if (vorne.isNotEmpty()) {
                val k = vorne[zufall.nextInt(vorne.size)]
                k.preis += 1 + zufall.nextInt(3)
                k.stand = Stand.UEBERBOTEN
                schreibe(jetzt, "UEBERBOTEN ${k.id} ${k.preis}")
            }
        }
        auffuellen(jetzt)
    }

    // ---------------- Image ----------------

    /** Is the loading screen showing right now? */
    fun laedt(jetzt: Long) = ladenBis > jetzt

    /** The current image. [filterSelbstMalen] = false: Android draws the price fields itself (real input fields). */
    fun bild(jetzt: Long, filterSelbstMalen: Boolean = true): Bild {
        weiter(jetzt)
        if (laedt(jetzt)) return bauer.laden()
        return when (ansicht) {
            Ansicht.MARKT -> bauer.markt(marktListe(jetzt).map { zeile(it, jetzt, imMarkt = true) }, versatzMarkt, meldungBis > jetzt)
            Ansicht.MEINE -> bauer.meineGebote(meineListe(jetzt).map { zeile(it, jetzt, imMarkt = false) }, versatzMeine, eintraege(jetzt))
            Ansicht.BIETEN -> bauer.bieten(dialog?.preis, minus7Bis > jetzt)
            Ansicht.FILTER -> if (filterSelbstMalen) bauer.filter(filterText[0], filterText[1]) else bauer.filter(null, null)
            Ansicht.PASSWORT -> bauer.passwort(if (filterSelbstMalen) passwortText.length else null)
            Ansicht.BESTAETIGEN -> bauer.atlas.bestaetigen
            Ansicht.SORTIEREN -> bauer.sortieren()
            Ansicht.FREMD -> fremd ?: bauer.laden()
        }
    }

    private fun zeile(k: Kopie, jetzt: Long, imMarkt: Boolean): Zeilenbild {
        val rest = ((k.endeUm - jetzt) / 1000).toInt()
        val zeichen = when {
            k.weg || k.stand == Stand.KEINS -> Zeichenart.KEINS
            k.stand == Stand.VORNE -> Zeichenart.UHR
            imMarkt -> Zeichenart.HAMMER
            else -> Zeichenart.KEINS
        }
        return Zeilenbild(k.werte, k.preis, rest, zeichen, kreuz = !imMarkt && k.stand == Stand.UEBERBOTEN)
    }

    // ---------------- Taps and swipes ----------------

    fun tippen(x: Int, y: Int, jetzt: Long) {
        weiter(jetzt)
        schreibe(jetzt, "TIPP $x $y ${if (laedt(jetzt)) "LADEN" else ansicht.name}")
        if (laedt(jetzt)) return verbiete(jetzt, "tap on the loading screen")
        when (ansicht) {
            Ansicht.MARKT -> markt(x, y, jetzt)
            Ansicht.BIETEN -> bietenFenster(x, y, jetzt)
            Ansicht.MEINE -> meine(x, y, jetzt)
            Ansicht.FILTER -> filterFenster(x, y, jetzt)
            Ansicht.PASSWORT -> passwortFenster(x, y, jetzt)
            Ansicht.BESTAETIGEN -> bestaetigenFenster(x, y, jetzt)
            Ansicht.SORTIEREN -> {
                if (y < OBEN) return verbiete(jetzt, "top bar (sorting)")
                ansicht = Ansicht.MARKT
                schreibe(jetzt, "ANSICHT MARKT (sorted)")
            }
            Ansicht.FREMD -> verbiete(jetzt, "tap in the foreign window after the X")
        }
    }

    /** Move the list (from y1 to y2: dragging up = further down in the list). */
    fun wischen(y1: Int, y2: Int, jetzt: Long) {
        weiter(jetzt)
        val d = y1 - y2
        schreibe(jetzt, "WISCH $d ${ansicht.name}")
        if (laedt(jetzt)) return
        when (ansicht) {
            Ansicht.MARKT -> versatzMarkt = (versatzMarkt + d).coerceIn(0, maxVersatz(marktListe(jetzt).size, Bildbauer.MARKT_ERSTE))
            Ansicht.MEINE -> versatzMeine = (versatzMeine + d).coerceIn(0, maxVersatz(meineListe(jetzt).size, Bildbauer.MEINE_ERSTE))
            else -> Unit
        }
    }

    private fun maxVersatz(n: Int, erste: Int): Int = maxOf(0, erste + (n - 1) * Bildbauer.ABSTAND + 160 - 1220)

    /** Text into a price field of the filter (0, 1) or into the secondary password field (2) – like Android ACTION_SET_TEXT. */
    fun textSetzen(feld: Int, text: String, jetzt: Long) {
        if (feld == FELD_PASSWORT) {
            if (ansicht != Ansicht.PASSWORT) return
            passwortText = text
            schreibe(jetzt, "TEXT PASSWORT (${text.length} chars)")
            return
        }
        if (ansicht != Ansicht.FILTER || feld !in 0..1) return
        filterText = filterText.copyOf().also { it[feld] = text }
        schreibe(jetzt, "TEXT ${if (feld == 0) "MIN" else "MAX"} $text")
    }

    private fun markt(x: Int, y: Int, jetzt: Long) {
        if (y < OBEN) return verbiete(jetzt, "top, in the purchase bar")
        if (menue(x, y, jetzt)) return
        when {
            nahe(x, y, 1688, 231, 55, 45) -> {
                versatzMarkt = 0
                schreibe(jetzt, "AKTUALISIERT")
            }
            nahe(x, y, 1895, 231, 75, 45) -> ansicht(Ansicht.SORTIEREN, jetzt)
            nahe(x, y, 2180, 231, 95, 45) -> {
                filterText = arrayOf(filterMin.toString(), filterMax.toString())
                fokus = -1
                ansicht(Ansicht.FILTER, jetzt)
            }
            else -> {
                val k = zeileBei(marktListe(jetzt), x, y, Bildbauer.MARKT_ERSTE, versatzMarkt, Bildbauer.MARKT_OBEN) ?: return
                oeffnen(k, Ansicht.MARKT, jetzt)
            }
        }
    }

    private fun meine(x: Int, y: Int, jetzt: Long) {
        if (y < OBEN) return verbiete(jetzt, "top, in the purchase bar (My Biddings)")
        if (menue(x, y, jetzt)) return
        if (nahe(x, y, 1976, 231, 55, 45)) {
            versatzMeine = 0
            schreibe(jetzt, "AKTUALISIERT (My Biddings)")
            return
        }
        val liste = meineListe(jetzt)
        // Red X of an outbid row?
        liste.forEachIndexed { i, k ->
            val t = Bildbauer.MEINE_ERSTE + i * Bildbauer.ABSTAND - versatzMeine
            if (k.stand == Stand.UEBERBOTEN && x in 2255..2332 && y in (t - 14)..(t + 46) && y >= Bildbauer.MEINE_OBEN) {
                if (einst.kreuzOeffnetFenster) {
                    ansicht(Ansicht.FREMD, jetzt)
                } else {
                    k.weg = true
                    weggedrueckt++
                    schreibe(jetzt, "WEG ${k.id} ${k.preis}")
                }
                return
            }
        }
        val k = zeileBei(liste, x, y, Bildbauer.MEINE_ERSTE, versatzMeine, Bildbauer.MEINE_OBEN) ?: return
        oeffnen(k, Ansicht.MEINE, jetzt)
    }

    /** Left menu: Auction Market / Bidding Market / My Biddings. */
    private fun menue(x: Int, y: Int, jetzt: Long): Boolean {
        if (x !in 140..520) return false
        when (y) {
            in 305..380 -> schreibe(jetzt, "MENUE Auction Market")
            in 395..455 -> {
                versatzMarkt = 0
                ansicht(Ansicht.MARKT, jetzt)
            }
            in 460..520 -> {
                versatzMeine = einst.meineVersatzBeimOeffnen
                ansicht(Ansicht.MEINE, jetzt)
            }
            else -> return false
        }
        return true
    }

    private fun zeileBei(liste: List<Kopie>, x: Int, y: Int, erste: Int, versatz: Int, oben: Int): Kopie? {
        if (x !in 760..2310 || y < oben) return null
        liste.forEachIndexed { i, k ->
            val t = erste + i * Bildbauer.ABSTAND - versatz
            if (y in t..(t + 151)) return k
        }
        return null
    }

    private fun oeffnen(k: Kopie, aus: Ansicht, jetzt: Long) {
        dialog = k
        dialogAus = aus
        minus7Bis = 0
        ansicht(Ansicht.BIETEN, jetzt)
        schreibe(jetzt, "OFFEN ${k.id} ${k.preis}")
    }

    private fun bietenFenster(x: Int, y: Int, jetzt: Long) {
        val k = dialog ?: return
        when {
            nahe(x, y, 2105, 108, 60, 50) -> {
                ansicht(dialogAus, jetzt)
                schreibe(jetzt, "ZU ${k.id}")
            }
            y < OBEN -> verbiete(jetzt, "top, in the purchase bar (Bid window)")
            x in 1492..1802 && y in 880..990 -> verbiete(jetzt, "Buyout Price")
            x in 1400..1900 && y in 505..605 -> verbiete(jetzt, "bid field / + / − in the Bid window")
            x in 1492..1802 && y in 630..735 -> bieten(k, jetzt)
            else -> schreibe(jetzt, "TIPP into empty space (Bid window)")
        }
    }

    private fun bieten(k: Kopie, jetzt: Long) {
        // Secondary password: on the first bid the game asks for it (until it is accepted).
        if (einst.passwort != null && !passwortOk) {
            passwortText = ""
            ansicht(Ansicht.PASSWORT, jetzt)
            return
        }
        val neu = k.stand == Stand.KEINS || k.weg
        if (eintraege(jetzt) >= einst.limit && (neu || einst.nachbietenBrauchtPlatz)) {
            minus7Bis = jetzt + 2500
            limitMeldungen++
            schreibe(jetzt, "LIMIT7 ${k.id} (${eintraege(jetzt)} entries)")
            return
        }
        // Sometimes the game also asks "Join the bidding?" (confirm window).
        val n = einst.bestaetigenJedesNte
        if (n > 0 && !bestaetigt && (gebote + nachgebote + 1) % n == 0) {
            ansicht(Ansicht.BESTAETIGEN, jetzt)
            return
        }
        bestaetigt = false
        k.preis += 1
        val warNeu = neu
        k.stand = Stand.VORNE
        k.weg = false
        if (warNeu) gebote++ else nachgebote++
        schreibe(jetzt, (if (warNeu) "GEBOT" else "NACHGEBOT") + " ${k.id} ${k.preis} (${eintraege(jetzt)} entries)")
        ansicht(dialogAus, jetzt)
        if (dialogAus == Ansicht.MARKT) meldungBis = jetzt + 3000
        val alle = gebote + nachgebote
        if (einst.ladenJedesNteGebot > 0 && alle % einst.ladenJedesNteGebot == 0) ladenBis = jetzt + 1500
    }

    private fun bestaetigenFenster(x: Int, y: Int, jetzt: Long) {
        val k = dialog ?: return
        when {
            nahe(x, y, 1806, 301, 50, 50) -> {
                // Red X: back to the Bid window (no bid).
                ansicht(Ansicht.BIETEN, jetzt)
                schreibe(jetzt, "BESTAETIGEN closed with X")
            }
            y < OBEN -> verbiete(jetzt, "top (confirm window)")
            x in 850..1330 && y in 720..860 -> verbiete(jetzt, "Cancel in the confirm window")
            x in 1380..1700 && y in 720..860 -> {
                bestaetigt = true
                schreibe(jetzt, "CONFIRM ${k.id}")
                bieten(k, jetzt)
            }
            else -> schreibe(jetzt, "TIPP into empty space (confirm window)")
        }
    }

    private fun passwortFenster(x: Int, y: Int, jetzt: Long) {
        val k = dialog ?: return
        val ok = Bildbauer.PASSWORT_OK
        val weg = Bildbauer.PASSWORT_VERGESSEN
        val feld = Bildbauer.PASSWORT_FELD
        when {
            y < OBEN -> verbiete(jetzt, "top (secondary password)")
            x in weg.x0..weg.x1 && y in weg.y0..weg.y1 -> verbiete(jetzt, "Forgot Password")
            x in feld.x0..feld.x1 && y in feld.y0..feld.y1 -> fokus = FELD_PASSWORT
            x in ok.x0..ok.x1 && y in ok.y0..ok.y1 -> {
                if (passwortText == einst.passwort) {
                    passwortOk = true
                    schreibe(jetzt, "PASSWORT ok")
                    bieten(k, jetzt) // as in the game: after Verify the bid is placed right away
                } else {
                    schreibe(jetzt, "PASSWORT falsch (${passwortText.length} chars)")
                }
            }
            else -> schreibe(jetzt, "TIPP into empty space (secondary password)")
        }
    }

    private fun filterFenster(x: Int, y: Int, jetzt: Long) {
        val min = Bildbauer.FILTER_MIN
        val max = Bildbauer.FILTER_MAX
        when {
            y < OBEN -> verbiete(jetzt, "top (filter window)")
            x in min.x0..min.x1 && y in min.y0..min.y1 -> fokus = 0
            x in max.x0..max.x1 && y in max.y0..max.y1 -> fokus = 1
            nahe(x, y, 1547, 1018, 160, 50) -> {
                filterMin = filterText[0].trim().toIntOrNull() ?: 0
                filterMax = filterText[1].trim().toIntOrNull() ?: 99_999_999
                versatzMarkt = 0
                fokus = -1
                ansicht(Ansicht.MARKT, jetzt)
                schreibe(jetzt, "FILTER $filterMin-$filterMax")
            }
            else -> schreibe(jetzt, "TIPP Filter ($x, $y)")
        }
    }

    private fun ansicht(a: Ansicht, jetzt: Long) {
        ansicht = a
        schreibe(jetzt, "ANSICHT ${a.name}")
    }

    private fun verbiete(jetzt: Long, warum: String) {
        verboten++
        schreibe(jetzt, "VERBOTEN $warum")
    }

    private fun nahe(x: Int, y: Int, px: Int, py: Int, dx: Int, dy: Int) = x in (px - dx)..(px + dx) && y in (py - dy)..(py + dy)

    private fun schreibe(jetzt: Long, text: String) {
        val zeile = "$jetzt $text"
        log += zeile
        protokoll(zeile)
    }

    companion object {
        /** Above this lies the purchase bar (back, currencies with "+", lock, "?", Retirement). */
        const val OBEN = 150

        /** Field number of the secondary password field for [textSetzen]. */
        const val FELD_PASSWORT = 2
    }
}
