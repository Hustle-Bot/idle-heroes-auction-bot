package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.FilterFeld
import io.github.hustlebot.ihauctionbot.kern.KOPIE_TYPEN
import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.Meldung
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.UnlesbareZahl
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.Zeile
import kotlin.math.hypot

/**
 * Recognizes the Idle Heroes auction house on a screenshot.
 *
 * All numbers here are positions on the reference phone (a Motorola, 2712 × 1220) and were measured on real
 * screenshots from Nov 7/8 (folder testbilder/). Other image sizes are converted.
 */
object Erkenner {

    // ---------- Colors ----------
    private val BALKEN = Farbe(157, 92, 75)
    private val TITEL = Farbe(243, 209, 101)
    private val GELB = Farbe(246, 201, 81)
    private val REITER_AUS = Farbe(236, 218, 194)
    private val PFEIL = Farbe(254, 249, 154)
    private val AKTUALISIEREN_MARKT = Farbe(149, 207, 66)
    private val AKTUALISIEREN_MEINE = Farbe(172, 228, 78)
    private val X_HELL = Farbe(250, 125, 53)
    private val X_DUNKEL = Farbe(119, 32, 14)
    private val FENSTER_TITEL = Farbe(168, 108, 75)
    private val ORANGE = Farbe(255, 120, 67)
    private val X_KLEIN = Farbe(251, 131, 56)
    private val FILTER_GRUND = Farbe(31, 19, 13)
    private val SORT_GRUND_1 = Farbe(35, 21, 14)
    private val SORT_GRUND_2 = Farbe(41, 28, 21)
    private val SORT_KNOPF = Farbe(243, 235, 219)
    private val DIALOG_BEIGE = Farbe(226, 206, 173)

    // ---------- Buttons ----------
    private val MENUE_AUKTIONSMARKT = Punkt(321, 342)
    private val MENUE_BIETERMARKT = Punkt(321, 427)
    private val MENUE_MEINE_GEBOTE = Punkt(321, 488)
    private val REITER_BIETEN = Punkt(935, 228)

    /** Column where the beige row cards are searched for. */
    private const val KARTEN_X = 2170
    private const val KARTE_HOEHE = 151
    internal const val LISTE_OBEN_MARKT = 300
    private const val LISTE_OBEN_MEINE = 365

    /** The filter fields (Awakened Tier) in the filter window. */
    private val FILTER_FELDER: List<Pair<String, Punkt>> = run {
        val reihe1 = listOf(873, 994, 1115, 1235, 1356, 1477, 1597, 1718, 1839)
        val namen = KOPIE_TYPEN
        namen.mapIndexed { i, n -> n to if (i < 9) Punkt(reihe1[i], 285) else Punkt(reihe1[i - 9], 370) }
    }

    /**
     * [zweit]: Nov 11 – second text recognition (Google ML Kit in the app) for numbers our own reader cannot
     * read reliably (see [Nachlesen]). [notiz]: what it read again (for the log).
     */
    fun erkenne(original: Bild, zweit: ZweitLeser? = null, notiz: (String) -> Unit = {}): Bildschirm {
        val b = original.aufReferenz()
        var ergebnis = erkenneReferenz(b)
        if (zweit != null && (ergebnis.unlesbar.isNotEmpty() || ergebnis.ort in NACHFRAGEN_BEI)) {
            ergebnis = Nachlesen.ergaenze(ergebnis, b, original, zweit, notiz)
        }
        return if (original === b) ergebnis else umrechnen(ergebnis, original.breite, original.hoehe)
    }

    /** Here the bot also asks when our own reader was only just sure ("Current Bid", SG balance). */
    private val NACHFRAGEN_BEI = setOf(Ort.BIETEN, Ort.MARKT)

    private fun erkenneReferenz(b: Bild): Bildschirm {
        // Nov 9 (field test 1.8): the expanded panel of the IH button was in the screenshot and covered the menu –
        // then recognize NOTHING (otherwise the bot might tap the panel instead of the game).
        // Nov 11: loading screen (all dark, golden star in the middle) – just wait, tap nothing.
        // Check this first: otherwise the dark screenshot would look like the gray panel of the IH button (nothing is tapped there either).
        if (istLaden(b)) return Bildschirm(Ort.LADEN)
        if (ihFeldImBild(b)) return Bildschirm.UNBEKANNT
        // Connection lost (Nov 9): lies on top of everything, any background.
        // Nov 22 (reconnect): the yellow Confirm button in the middle is part of the check ([istVerbindungWeg]) – offer it.
        if (istVerbindungWeg(b)) return Bildschirm(Ort.VERBINDUNG_WEG, knoepfe = mapOf(Knopf.BESTAETIGEN to VERBINDUNG_CONFIRM))
        // First the secondary password window: it lies on top of the (darkened) Bid window.
        if (istZweitpasswortFenster(b)) return Bildschirm(
            Ort.ZWEITPASSWORT,
            knoepfe = mapOf(Knopf.PASSWORT_FELD to Punkt(1356, 630), Knopf.PASSWORT_OK to Punkt(1528, 804)),
        )
        if (istBietenFenster(b)) return bietenFenster(b)
        if (istBestaetigenFenster(b)) return bestaetigenFenster(b)
        if (!istAuktionshausOben(b)) return Bildschirm.UNBEKANNT
        if (istFilterFenster(b)) return filterFenster(b)
        // Nov 22 (rate limit): the real "Operated too frequently" messages lay over the sort window (report images
        // 20-42-45_bild_08 … _10, 21-46-15_bild_03 … _05) – so the message is looked for here too.
        if (istSortierFenster(b)) return Bildschirm(
            Ort.SORTIEREN,
            meldung = meldung(b),
            knoepfe = mapOf(Knopf.SORT_PREIS_AUF to Punkt(1734, 381), Knopf.SORT_RANG_AUF to Punkt(1734, 499)),
        )
        val pfeilMarkt = b.farbe(422, 427).nahe(PFEIL, 40)
        val pfeilMeine = b.farbe(422, 488).nahe(PFEIL, 40)
        val reiterLinksGelb = b.farbe(935, 200).nahe(GELB, 30)
        val reiterRechtsGelb = b.farbe(1320, 200).nahe(GELB, 30)
        val menue = mapOf(
            Knopf.MENUE_AUKTIONSMARKT to MENUE_AUKTIONSMARKT,
            Knopf.MENUE_BIETERMARKT to MENUE_BIETERMARKT,
            Knopf.MENUE_MEINE_GEBOTE to MENUE_MEINE_GEBOTE,
        )
        return when {
            pfeilMarkt && reiterLinksGelb && b.farbe(1320, 200).nahe(REITER_AUS, 30) &&
                b.farbe(1688, 231).nahe(AKTUALISIEREN_MARKT, 40) -> {
                val meldung = meldung(b)
                // Nov 9 (field test 1.9): read the rows even with a message ("Bidding successful …") – only the ones
                // under the band count as covered. For those the bot uses its remembered values and keeps bidding.
                val liste = zeilen(b, LISTE_OBEN_MARKT, meldung)
                val sg = sg(b)
                Bildschirm(
                    Ort.MARKT,
                    zeilen = liste,
                    sg = sg,
                    unlesbar = unlesbareZeilen(b, liste) + listOfNotNull(if (sg == null) unlesbar(b, SG_FELD, Schrift.SG, "sg", "oben", 18) else null),
                    meldung = meldung,
                    sortierung = sortierung(b),
                    wischWeg = Punkt(1356, 1080) to Punkt(1356, 520),
                    knoepfe = menue + mapOf(
                        Knopf.AKTUALISIEREN to Punkt(1688, 231),
                        Knopf.SORTIEREN to Punkt(1895, 231),
                        Knopf.FILTER to Punkt(2180, 231),
                        Knopf.REITER_BIETEN to REITER_BIETEN,
                    ),
                )
            }
            pfeilMarkt && reiterRechtsGelb -> Bildschirm(
                Ort.MARKT_ANZEIGE, knoepfe = menue + (Knopf.REITER_BIETEN to REITER_BIETEN),
            )
            pfeilMeine && reiterLinksGelb && b.farbe(1976, 231).nahe(AKTUALISIEREN_MEINE, 40) -> {
                val meldung = meldung(b)
                val liste = if (meldung == null) zeilen(b, LISTE_OBEN_MEINE) else emptyList()
                val zaehler = ZiffernLeser.zahl(b, ZAEHLER_FELD, Schrift.WEISS)
                Bildschirm(
                    Ort.MEINE_GEBOTE,
                    zeilen = liste,
                    sg = sg(b),
                    laufendeGebote = zaehler,
                    keineGebote = zaehler == 0 && liste.isEmpty(),
                    // Nov 11: also report unreadable prices/remaining times – then the second text recognition asks again
                    // (here, after "-7", the bot decides whether to bid again or press the X).
                    unlesbar = listOfNotNull(if (zaehler == null) unlesbar(b, ZAEHLER_FELD, Schrift.WEISS, "zaehler", "meine-gebote") else null) +
                        unlesbareZeilen(b, liste),
                    meldung = meldung,
                    knoepfe = menue + (Knopf.AKTUALISIEREN to Punkt(1976, 231)),
                )
            }
            // Nov 22 (rate limit): also over the empty auction house while the list reloads (20-42-45_bild_11, 21-46-15_bild_06).
            else -> Bildschirm(Ort.AUKTIONSHAUS_ANDERS, meldung = meldung(b), knoepfe = menue)
        }
    }

    /**
     * Is the expanded panel of the IH button in the screenshot? It is a neutral dark gray (#202124, almost opaque) –
     * a color the game (warm tones, even when darkened) does not have. A grid in the left half of the image is
     * checked; if a large part of it is in this gray, the panel is in the screenshot.
     */
    internal fun ihFeldImBild(b: Bild): Boolean {
        var grau = 0
        for (y in 300..1100 step 100) for (x in 100..1300 step 100) {
            val f = b.farbe(x, y)
            if (f.r in 25..70 && f.g in 25..70 && f.b in 25..75 &&
                maxOf(f.r, f.g, f.b) - minOf(f.r, f.g, f.b) <= 10
            ) grau++
        }
        return grau >= IH_FELD_MIN_PUNKTE
    }

    /** This many grid points (out of 117) must be in the gray of the IH panel. */
    private const val IH_FELD_MIN_PUNKTE = 20

    // ---------- Recognizing pages ----------

    private fun istAuktionshausOben(b: Bild): Boolean =
        b.farbe(700, 60).nahe(BALKEN, 30) && b.farbe(420, 62).nahe(TITEL, 35)

    /**
     * Bid window. Nov 9 (field test 1.7): recognized ONLY by parts it always has – red X at the top
     * right, brown title bar, plus the yellow "Bid" button or the beige area below it.
     * NOT by the "Buyout Price" button: it is GRAY when there is no buyout ("Buyout Price: --") –
     * recognition failed on the phone because of that, and the bot never tapped "Bid".
     * If the window is darkened (confirm or password window on top), the X does not match.
     */
    private fun istBietenFenster(b: Bild): Boolean {
        if (!b.farbe(2085, 108).nahe(X_HELL, 40) || !b.farbe(2109, 108).nahe(X_DUNKEL, 40)) return false
        val titel = listOf(1000, 1500, 1900).count { b.farbe(it, 110).nahe(FENSTER_TITEL, 35) }
        if (titel < 2) return false
        return bietenKnopf(b) != null || b.farbe(1200, 880).nahe(DIALOG_BEIGE, 30)
    }

    /**
     * The yellow "Bid" button in the Bid window: search vertically for the yellow stripe (on the phone y ≈ 637–726).
     * The search is left of the label (x = 1560); the button must also be yellow on the left and right.
     * null = not found (then the bot does not tap it).
     */
    internal fun bietenKnopf(b: Bild): Punkt? {
        var start = -1
        var bestStart = -1
        var bestEnde = -1
        for (y in BID_SUCHE_OBEN..BID_SUCHE_UNTEN) {
            val gelb = b.farbe(1560, y).nahe(GELB, 40)
            if (gelb && start < 0) start = y
            if ((!gelb || y == BID_SUCHE_UNTEN) && start >= 0) {
                val ende = if (gelb) y + 1 else y
                if (ende - start > bestEnde - bestStart) {
                    bestStart = start
                    bestEnde = ende
                }
                start = -1
            }
        }
        if (bestEnde - bestStart < 30) return null
        val mitte = (bestStart + bestEnde) / 2
        if (!b.farbe(1520, mitte).nahe(GELB, 40) || !b.farbe(1760, mitte).nahe(GELB, 40)) return null
        return Punkt(1641, mitte)
    }

    /** Area where the "Bid" button is (below it the Buyout button would start at y ≈ 887). */
    private const val BID_SUCHE_OBEN = 560
    private const val BID_SUCHE_UNTEN = 840

    /**
     * Nov 22 (buyout): the "Buyout Price" button below the Bid button (real screenshots: y ≈ 887–981, x ≈ 1488–1797).
     * It is YELLOW only when the copy can be bought out; without a buyout ("Buyout Price: --") it is gray – then null.
     * Strict: a yellow stripe of at least [SOFORT_MIN_HOEHE] px left of the label (x = 1512) inside its own search area,
     * and yellow on the left and right edge too.
     */
    internal fun sofortKnopf(b: Bild): Punkt? {
        var start = -1
        var bestStart = -1
        var bestEnde = -1
        for (y in SOFORT_SUCHE_OBEN..SOFORT_SUCHE_UNTEN) {
            val gelb = b.farbe(1512, y).nahe(GELB, 40)
            if (gelb && start < 0) start = y
            if ((!gelb || y == SOFORT_SUCHE_UNTEN) && start >= 0) {
                val ende = if (gelb) y + 1 else y
                if (ende - start > bestEnde - bestStart) {
                    bestStart = start
                    bestEnde = ende
                }
                start = -1
            }
        }
        if (bestEnde - bestStart < SOFORT_MIN_HOEHE) return null
        val mitte = (bestStart + bestEnde) / 2
        if (!b.farbe(1500, mitte).nahe(GELB, 40) || !b.farbe(1780, mitte).nahe(GELB, 40)) return null
        return Punkt(1641, mitte)
    }

    private const val SOFORT_SUCHE_OBEN = 860
    private const val SOFORT_SUCHE_UNTEN = 1010
    private const val SOFORT_MIN_HOEHE = 60

    /** Nov 22 (buyout): "Buyout Price: 28" – only the number after the colon (the colon ends at x ≈ 1462). */
    val SOFORT_PREIS_FELD = Rechteck(1464, 790, 1640, 845)

    private fun istBestaetigenFenster(b: Bild): Boolean =
        b.farbe(1060, 780).nahe(ORANGE, 40) && b.farbe(1400, 780).nahe(GELB, 35) &&
            b.farbe(1785, 301).nahe(X_KLEIN, 40)

    private fun istFilterFenster(b: Bild): Boolean =
        b.farbe(1356, 600).nahe(FILTER_GRUND, 25) && b.farbe(1167, 1018).nahe(GELB, 35) &&
            b.farbe(1440, 1000).nahe(GELB, 35)

    /**
     * Nov 9: "Enter Secondary Password". Same template as the confirm window
     * (buttons in the same places), but YELLOW on the left ("Forgot Password" instead of the orange "Cancel")
     * and a white input field in the middle. Derived from the PC screenshot – not yet checked on the phone.
     */
    private fun istZweitpasswortFenster(b: Bild): Boolean {
        fun weiss(x: Int, y: Int) = b.farbe(x, y).let { it.r > 238 && it.g > 238 && it.b > 238 }
        // Nov 11 (test against the fake game): once the password is typed, there are dots on the left of the field –
        // then not every spot is white any more. It is enough if the field is white in at least 3 of 5 spots
        // (before, all 3 spots had to be white: after typing, the bot no longer recognized the window and
        // never tapped "Verify").
        val weissePunkte = listOf(1250, 1356, 1460, 1500, 1540).count { weiss(it, 630) }
        return b.farbe(1060, 780).nahe(GELB, 35) && b.farbe(1400, 780).nahe(GELB, 35) &&
            weissePunkte >= 3 && weiss(1460, 630) &&
            b.farbe(1356, 712).nahe(DIALOG_BEIGE, 30)
    }

    /**
     * Nov 9: "Lost connection with game server." – same window template as the confirm window,
     * but only ONE yellow button exactly in the middle (where the confirm and password windows have a
     * beige gap), beige to its left and right, and an empty beige stripe above.
     * Derived from a PC screenshot – not yet checked on the phone.
     */
    /**
     * Nov 11: loading screen. The whole screen is strongly darkened (90 % of the grid points darker than
     * [LADEN_HELL_MAX]), and a golden star in a ring glows in the middle (at least [LADEN_GOLD_MIN] golden
     * pixels). Measured on the screenshot testbilder/laden-stern.jpg: brightness 46, ~7000 golden pixels; all other
     * screenshots: brightness from 122.
     */
    internal fun istLaden(b: Bild): Boolean {
        val hell = ArrayList<Int>()
        for (y in 20 until b.hoehe step 40) for (x in 20 until b.breite step 40) hell += b.farbe(x, y).hell
        hell.sort()
        if (hell[hell.size * 9 / 10] >= LADEN_HELL_MAX) return false
        var gold = 0
        var hellAnders = 0
        for (y in 460..770) for (x in 1200..1520) {
            val f = b.farbe(x, y)
            if (f.r > 200 && f.g > 140 && f.b < 90 && f.r >= f.g) gold++
            else if (f.hell > 170) hellAnders++
        }
        return gold >= LADEN_GOLD_MIN && hellAnders <= LADEN_HELL_ANDERS_MAX
    }

    private const val LADEN_HELL_MAX = 90

    /**
     * Nov 11 (error report 19:33): the "Starry Gem Exchange" window was just opening (small, rest darkened)
     * and was taken for the loading screen – it has golden borders in the middle. Measured on all real screenshots:
     * loading screen ≥ 4500 golden pixels and at most ≈ 9000 other bright ones; the window ≈ 1750 golden and ≈ 47000
     * other bright ones. So at least 3000 golden and at most 25000 other bright pixels in the middle.
     */
    private const val LADEN_GOLD_MIN = 3000
    private const val LADEN_HELL_ANDERS_MAX = 25000

    /** Nov 22: the yellow Confirm button of the "Lost connection" window (checked yellow in [istVerbindungWeg]). */
    val VERBINDUNG_CONFIRM = Punkt(1356, 795)

    private fun istVerbindungWeg(b: Bild): Boolean =
        b.farbe(1356, 795).nahe(GELB, 40) && b.farbe(1300, 795).nahe(GELB, 40) && b.farbe(1412, 795).nahe(GELB, 40) &&
            b.farbe(1090, 795).nahe(DIALOG_BEIGE, 35) && b.farbe(1622, 795).nahe(DIALOG_BEIGE, 35) &&
            b.farbe(1356, 680).nahe(DIALOG_BEIGE, 35) && b.farbe(1200, 680).nahe(DIALOG_BEIGE, 35)

    /**
     * Label of the sort button: "Price ↑" is one line (text in the middle),
     * "Awakened Tier ↑" two lines (text at the top and bottom, middle almost empty).
     */
    private fun sortierung(b: Bild): Sortierung {
        fun dunkelAnteil(y0: Int, y1: Int): Double {
            var n = 0
            var t = 0
            for (y in y0 until y1) for (x in 1800 until 1935 step 2) {
                val f = b.farbe(x, y)
                t++
                if (f.r < 120 && f.g < 90 && f.b < 70) n++
            }
            return n.toDouble() / t
        }
        val oben = dunkelAnteil(200, 214)
        val mitte = dunkelAnteil(222, 240)
        return when {
            oben > 0.04 && mitte < 0.10 -> Sortierung.RANG
            mitte > 0.15 && oben < 0.02 -> Sortierung.PREIS
            else -> Sortierung.UNBEKANNT
        }
    }

    /** How many characters are in a white price field? (Combine columns with dark points into blocks) */
    private fun feldStellen(b: Bild, x0: Int, x1: Int): Int? {
        if (b.farbe(x1 - 6, 585).hell < 200) return null // no white field
        var bloecke = 0
        var drin = false
        for (x in x0 until x1) {
            var dunkel = false
            for (y in 556 until 614) if (b.farbe(x, y).hell < 170) { dunkel = true; break }
            if (dunkel && !drin) bloecke++
            drin = dunkel
        }
        return bloecke
    }

    private fun istSortierFenster(b: Bild): Boolean =
        b.farbe(1560, 300).nahe(SORT_GRUND_1, 25) && b.farbe(1900, 1040).nahe(SORT_GRUND_2, 25) &&
            b.farbe(1600, 700).nahe(SORT_KNOPF, 25)

    private fun bietenFenster(b: Bild): Bildschirm {
        val aktuell = ZiffernLeser.zahl(b, AKTUELLES_GEBOT_FELD, Schrift.HELL)
        return Bildschirm(
            Ort.BIETEN,
            meldung = meldung(b),
            aktuellesGebot = aktuell,
            // Nov 22 (buyout): "Buyout Price: N" (null when "--" or not readable).
            sofortPreis = ZiffernLeser.zahl(b, SOFORT_PREIS_FELD, Schrift.HELL),
            // Nov 9: only offer the "Bid" button if it was really found.
            // Nov 22: the "Buyout Price" button only when it is yellow on this screenshot.
            knoepfe = listOfNotNull(
                bietenKnopf(b)?.let { Knopf.BIETEN to it },
                sofortKnopf(b)?.let { Knopf.SOFORTKAUF to it },
                Knopf.SCHLIESSEN to Punkt(2105, 108),
            ).toMap(),
            unlesbar = listOfNotNull(if (aktuell == null) unlesbar(b, AKTUELLES_GEBOT_FELD, Schrift.HELL, "aktuellesGebot", "bid-fenster") else null),
        )
    }

    /** "Current Bid: 53" – only the number after the colon. */
    val AKTUELLES_GEBOT_FELD = Rechteck(1438, 422, 1600, 480)

    private fun bestaetigenFenster(b: Bild): Bildschirm {
        val betrag = bestaetigenPreis(b)
        return Bildschirm(
            Ort.BESTAETIGEN,
            bestaetigenPreis = betrag,
            knoepfe = mapOf(
                Knopf.BESTAETIGEN to Punkt(1528, 804),
                // Nov 9: "Cancel" (1182, 804) is never tapped – the window is closed with the red X.
                Knopf.SCHLIESSEN to Punkt(1806, 301),
            ),
            // Learning image: the whole line "Starry Gems spent: 18" (the number is centered, shifted depending on its length).
            unlesbar = listOfNotNull(
                if (betrag == null) unlesbar(b, BESTAETIGEN_FELD, Schrift.BRAUN, "bestaetigen", "bestaetigen-fenster", 16) else null,
            ),
        )
    }

    /** "Bidding Items: 49" in My Biddings – only the number. */
    val ZAEHLER_FELD = Rechteck(985, 312, 1080, 360)

    /**
     * "Starry Gems spent: 18" – the number is on the right, after the colon there is a gap (≈ 20 px),
     * between the digits only ≈ 4 px. The number = all blobs on the far right that are close together.
     * EACH of them must be recognized reliably, otherwise the number counts as not readable.
     * Nov 9 (field test 1.8): before, reading simply stopped at an unknown digit – 59 became 8,
     * the bot thought the bid was wrong and tapped Cancel (three times in a row).
     */
    internal fun bestaetigenPreis(b: Bild): Int? {
        val zerlegt = ZiffernLeser.zerlege(b, BESTAETIGEN_FELD, Schrift.BRAUN, minHoehe = 16)
        val gl = zerlegt.glyphen
        if (gl.isEmpty()) return null
        val zahl = ArrayList<Glyphe>()
        for (g in gl.asReversed()) {
            val rechts = zahl.lastOrNull()
            if (rechts != null && rechts.x0 - g.x1 > ZIFFERN_LUECKE) break
            zahl += g
            if (zahl.size > 4) return null
        }
        // Nov 11: is there a broken-up piece next to/between the digits (lost digit)? Then not readable.
        if (ZiffernLeser.resteVerdaechtig(ZiffernLeser.Zerlegung(zahl.asReversed(), zerlegt.reste))) return null
        val text = StringBuilder()
        for (g in zahl.asReversed()) {
            val (z, d) = ZiffernLeser.vergleiche(g, Schrift.BRAUN) ?: return null
            if (d > ZiffernLeser.SCHWELLE) return null
            text.append(z)
        }
        return text.toString().toIntOrNull()
    }

    /** Area with "Starry Gems spent: 18" in the confirm window. */
    val BESTAETIGEN_FELD = Rechteck(1080, 596, 1700, 646)

    /** Largest gap between two digits of the same number (confirm window: ≈ 4 px; before the number ≈ 20 px). */
    private const val ZIFFERN_LUECKE = 12

    private fun filterFenster(b: Bild): Bildschirm {
        // Nov 11: the yellow border is only ≈ 5 px high (on the phone y-36 … y-32) – before, exactly ONE line
        // (y-36, the top border line) was checked; on the half-size report screenshot it missed. Now one
        // yellow point in y-38 … y-30 is enough (unselected fields have no yellow anywhere there, measured y-44 … y-28).
        fun gelbRand(x: Int, y: Int) = (y - 38..y - 30).any { istGelbRand(b.farbe(x, it)) }
        val felder = FILTER_FELDER.map { (name, p) ->
            FilterFeld(name, p, gelbRand(p.x, p.y) && gelbRand(p.x - 30, p.y))
        }
        return Bildschirm(
            Ort.FILTER,
            filterFelder = felder,
            filterMinStellen = feldStellen(b, 1025, 1282),
            filterMaxStellen = feldStellen(b, 1433, 1688),
            knoepfe = mapOf(
                Knopf.FILTER_OK to Punkt(1547, 1018),
                Knopf.FILTER_MIN to Punkt(1153, 584),
                Knopf.FILTER_MAX to Punkt(1559, 584),
            ),
        )
    }

    private fun istGelbRand(f: Farbe) = f.r > 200 && f.g > 160 && f.b < 120

    // ---------- Message (dark band in the middle) ----------

    /**
     * Searches for the dark message band: one thin bright line at the top and one at the bottom,
     * equally wide, center at y ≈ 609.
     */
    fun meldung(b: Bild): Meldung? {
        data class Linie(val y: Int, val von: Int, val bis: Int)

        fun linie(y: Int): Linie? {
            var besteVon = -1
            var besteBis = -1
            var von = -1
            var x = 520
            var luecke = 0
            var letzte = -1
            while (x <= 2200) {
                val c = b.hell(x, y)
                val ok = c > b.hell(x, y - 4) + 45 && c > b.hell(x, y + 4) + 45
                if (ok) {
                    if (von < 0) von = x
                    letzte = x
                    luecke = 0
                } else if (von >= 0) {
                    luecke += 6
                    if (luecke > 36) {
                        if (letzte - von > besteBis - besteVon) {
                            besteVon = von; besteBis = letzte
                        }
                        von = -1
                    }
                }
                x += 6
            }
            if (von >= 0 && letzte - von > besteBis - besteVon) {
                besteVon = von; besteBis = letzte
            }
            return if (besteBis - besteVon >= 400) Linie(y, besteVon, besteBis) else null
        }

        var oben: Linie? = null
        for (y in 470..560) {
            val l = linie(y) ?: continue
            if (oben == null || l.bis - l.von > oben.bis - oben.von) oben = l
        }
        oben ?: return null
        var unten: Linie? = null
        for (y in 660..750) {
            val l = linie(y) ?: continue
            if (unten == null || l.bis - l.von > unten.bis - unten.von) unten = l
        }
        unten ?: return null
        val mitte = (oben.y + unten.y) / 2
        if (mitte !in 585..635) return null
        if (kotlin.math.abs((oben.bis - oben.von) - (unten.bis - unten.von)) > 250) return null
        return Meldung(maxOf(oben.bis - oben.von, unten.bis - unten.von), yOben = oben.y, yUnten = unten.y)
    }

    // ---------- Rows ----------

    private fun istKarte(f: Farbe) = f.r > 215 && f.g > 195 && f.b > 165 && f.r - f.b in 25..70

    /** Top edges (inner side) of all fully visible cards. */
    fun kartenOben(b: Bild, listeOben: Int): List<Int> {
        val oben = ArrayList<Int>()
        var start = -1
        for (y in listeOben until b.hoehe) {
            val karte = istKarte(b.farbe(KARTEN_X, y))
            if (karte && start < 0) start = y
            if ((!karte || y == b.hoehe - 1) && start >= 0) {
                val ende = if (karte) y + 1 else y
                val h = ende - start
                if (h >= 140) {
                    // Cut off at the top (scrolled): count from the bottom.
                    val t = if (h < KARTE_HOEHE - 2 && start <= listeOben + 3) ende - KARTE_HOEHE else start
                    oben += t
                }
                start = -1
            }
        }
        return oben
    }

    fun preisFeld(t: Int) = Rechteck(1856, t + 24, 2010, t + 68)
    fun sofortFeld(t: Int) = Rechteck(1856, t + 81, 2010, t + 125)
    fun zeitFeld(t: Int) = Rechteck(1265, t + 82, 1450, t + 126)
    fun wertFeld(t: Int, nr: Int) = when (nr) {
        0 -> Rechteck(1052, t + 12, 1160, t + 48)
        1 -> Rechteck(1052, t + 56, 1160, t + 92)
        else -> Rechteck(1052, t + 101, 1160, t + 137)
    }
    val SG_FELD = Rechteck(1880, 30, 2052, 95)

    /**
     * All fully visible rows. If a message lies over the list, the rows under it count as covered
     * (without numbers – the bot uses what it remembered instead).
     */
    private fun zeilen(b: Bild, listeOben: Int, meldung: Meldung? = null): List<Zeile> =
        kartenOben(b, listeOben).map { t -> if (meldung?.verdeckt(t) == true) verdeckteZeile(t) else zeile(b, t) }

    /** Nov 9 (field test 1.9): row under the message – nothing on it is reliably readable. Tapping still works. */
    private fun verdeckteZeile(t: Int) = Zeile(
        oben = t, preis = null, sofortPreis = null, restzeitSek = null, hatRestzeit = false,
        werte = listOf(null, null, null), zeichen = Zeichen.KEINS, tippen = Punkt(1356, t + 60), verdeckt = true,
    )

    /**
     * Nov 9 (field test 1.9: the price "47" was not read, the copy was skipped): numbers that ARE there
     * (blobs in the font color) but could not be read – for the learning images in the error report.
     */
    private fun unlesbareZeilen(b: Bild, zeilen: List<Zeile>): List<UnlesbareZahl> {
        val liste = ArrayList<UnlesbareZahl>()
        for (z in zeilen) {
            if (z.verdeckt) continue
            val k = "z${z.oben}_" + (z.id ?: "unbekannt")
            if (z.preis == null) unlesbar(b, preisFeld(z.oben), Schrift.WEISS, "preis", k)?.let { liste += it }
            if (z.sofortPreis == null) unlesbar(b, sofortFeld(z.oben), Schrift.WEISS, "sofort", k, minStriche = 2)?.let { liste += it }
            if (z.hatRestzeit && z.restzeitSek == null) unlesbar(b, zeitFeld(z.oben), Schrift.GRUEN, "zeit", k)?.let { liste += it }
            z.werte.forEachIndexed { i, w ->
                if (w == null) unlesbar(b, wertFeld(z.oben, i), Schrift.ROT, "wert${i + 1}", k, 16)?.let { liste += it }
            }
        }
        return liste
    }

    /**
     * Report a field as "unreadable" – but only if it really contains digit blobs ([minStriche] of them;
     * "--" for the buyout are small dashes and do not count).
     */
    private fun unlesbar(b: Bild, r: Rechteck, schrift: Schrift, feld: String, kontext: String, minHoehe: Int = 14, minStriche: Int = 1): UnlesbareZahl? {
        val gl = ZiffernLeser.glyphen(b, r, schrift, minHoehe)
        if (gl.size < minStriche) return null
        return UnlesbareZahl(feld, r.x0, r.y0, r.x1, r.y1, kontext)
    }

    fun zeile(b: Bild, t: Int): Zeile {
        // Nov 11 (speed): find the blobs of the remaining time only ONCE (before: twice) – and from the cache.
        val zeitLesung = ZiffernLeser.lesung(b, zeitFeld(t), Schrift.GRUEN)
        val zeit = zeitLesung.gelesen?.text
        val restzeit = if (zeit != null && zeit.length == 6) {
            val h = zeit.substring(0, 2).toInt()
            val m = zeit.substring(2, 4).toInt()
            val s = zeit.substring(4, 6).toInt()
            if (m < 60 && s < 60) h * 3600 + m * 60 + s else null
        } else null
        val kreuzMitte = kreuzPunkt(b, t)
        return Zeile(
            oben = t,
            preis = ZiffernLeser.zahl(b, preisFeld(t), Schrift.WEISS),
            sofortPreis = ZiffernLeser.zahl(b, sofortFeld(t), Schrift.WEISS),
            restzeitSek = restzeit,
            hatRestzeit = zeitLesung.flecken > 0,
            werte = (0..2).map { ZiffernLeser.zahl(b, wertFeld(t, it), Schrift.ROT, minHoehe = 16) },
            zeichen = zeichen(b, t),
            ueberbotenKreuz = kreuzMitte != null,
            stufe = stufe(b, t),
            tippen = Punkt(1356, t + 60),
            kreuzPunkt = kreuzMitte,
        )
    }

    /** Clock (I am in front) or hammer (outbid) on the right of the row. */
    fun zeichen(b: Bild, t: Int): Zeichen {
        val cx = 2045
        val cy = t + 76
        var n = 0
        var ring = 0
        // Nov 11 (speed): read pixels directly instead of creating a Farbe object for each (same result).
        for (y in cy - 45..cy + 45) for (x in cx - 45..cx + 45) {
            val p = b.rgb(x, y)
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val bl = p and 0xFF
            if ((r * 30 + g * 59 + bl * 11) / 100 < 175 && r >= g && g >= bl - 10) {
                n++
                val d = hypot((x - cx).toDouble(), (y - cy).toDouble())
                if (d in 24.0..38.0) ring++
            }
        }
        if (n < 400) return Zeichen.KEINS
        return if (ring.toFloat() / n >= 0.40f) Zeichen.UHR else Zeichen.HAMMER
    }

    /** Red cross at the top right of the card (My Biddings: outbid). */
    fun kreuz(b: Bild, t: Int): Boolean = kreuzPunkt(b, t) != null

    /**
     * Nov 11: center of the red X at the top right of the card (to dismiss it) – the center of the red area.
     * null = no X. On the reference screenshots it is at about (2298, top edge + 19). If the center is not where
     * the X has to be, it counts as NOT found (then nothing is tapped either).
     */
    fun kreuzPunkt(b: Bild, t: Int): Punkt? {
        var rot = 0
        var sx = 0L
        var sy = 0L
        for (y in t - 12..t + 45) for (x in 2262..2325) {
            val p = b.rgb(x, y)
            if ((p shr 16 and 0xFF) > 200 && (p shr 8 and 0xFF) < 100 && (p and 0xFF) < 90) {
                rot++
                sx += x
                sy += y
            }
        }
        if (rot < 40) return null
        val mitte = Punkt((sx / rot).toInt(), (sy / rot).toInt())
        if (mitte.x !in KREUZ_X_MIN..KREUZ_X_MAX || mitte.y - t !in KREUZ_DY_MIN..KREUZ_DY_MAX) return null
        return mitte
    }

    /** Where the center of the red X may be (reference; x absolute, y relative to the top edge of the card). */
    private const val KREUZ_X_MIN = 2275
    private const val KREUZ_X_MAX = 2320
    private const val KREUZ_DY_MIN = 0
    private const val KREUZ_DY_MAX = 38

    /** Letter of the rank badge on the hero image, by its color: purple = C, green = B, red = A. */
    fun stufe(b: Bild, t: Int): Char? {
        var lila = 0
        var gruen = 0
        var rot = 0
        for (y in t + 64..t + 88) for (x in 794..818) {
            val f = b.farbe(x, y)
            when {
                f.b > f.g + 40 && f.r > f.g + 20 -> lila++
                f.g > f.r + 60 && f.g > f.b + 60 -> gruen++
                f.r > f.g + 90 && f.r > f.b + 70 -> rot++
            }
        }
        val max = maxOf(lila, gruen, rot)
        if (max < 60) return null
        return when (max) {
            lila -> 'C'
            gruen -> 'B'
            else -> 'A'
        }
    }

    fun sg(b: Bild): Int? = ZiffernLeser.zahl(b, SG_FELD, Schrift.SG, minHoehe = 18)

    // ---------- Converting to the real image size ----------

    private fun umrechnen(s: Bildschirm, breite: Int, hoehe: Int): Bildschirm {
        fun p(q: Punkt) = Punkt(q.x * breite / Bild.REF_BREITE, q.y * hoehe / Bild.REF_HOEHE)
        return s.copy(
            bildBreite = breite,
            bildHoehe = hoehe,
            zeilen = s.zeilen.map { it.copy(tippen = p(it.tippen), kreuzPunkt = it.kreuzPunkt?.let { k -> p(k) }) },
            knoepfe = s.knoepfe.mapValues { p(it.value) },
            filterFelder = s.filterFelder.map { it.copy(punkt = p(it.punkt)) },
            wischWeg = s.wischWeg?.let { (a, c) -> p(a) to p(c) },
            unlesbar = s.unlesbar.map {
                val a = p(Punkt(it.x0, it.y0))
                val c = p(Punkt(it.x1, it.y1))
                it.copy(x0 = a.x, y0 = a.y, x1 = c.x, y1 = c.y)
            },
        )
    }
}
