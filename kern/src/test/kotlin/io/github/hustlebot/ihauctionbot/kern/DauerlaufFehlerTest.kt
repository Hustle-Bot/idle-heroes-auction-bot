package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 11: one short test for every bug the long run ([DauerlaufTest]) found – so that it does not
 * come back.
 */
class DauerlaufFehlerTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 12, 8, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)
    private val bid = Punkt(1641, 690)
    private val bidX = Punkt(2105, 108)

    /** Run the bot and the fake [schritte] times (as in AuktionsBotTest). [nachTipp] may "push in" an image. */
    private fun laufen(
        bot: AuktionsBot,
        spiel: SpielAttrappe,
        schritte: Int,
        jeSchritt: (Ergebnis) -> Unit = {},
        bis: () -> Boolean = { false },
    ): Ergebnis {
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(schritte) {
            e = bot.schritt(spiel.jetzt)
            jeSchritt(e)
            when (val x = e) {
                is Ergebnis.Handeln -> {
                    spiel.ausfuehren(x.aktion)
                    val a = x.aktion
                    if (a is Aktion.Eingeben || a is Aktion.PasswortEingeben || (a is Aktion.Folge && a.hatEingaben)) {
                        bot.eingabeErgebnis(spiel.letzteEingabeOk)
                    }
                    spiel.jetzt = spiel.jetzt.plusMillis(maxOf(x.naechsterSchrittMs, 380))
                }
                is Ergebnis.Warten -> spiel.jetzt = spiel.jetzt.plusMillis(maxOf(x.ms, 380))
                is Ergebnis.Unsicher -> spiel.jetzt = spiel.jetzt.plusMillis(maxOf(x.ms, 380))
                is Ergebnis.Fertig -> return x
                Ergebnis.Pausiert -> spiel.jetzt = spiel.jetzt.plusSeconds(1)
            }
            if (bis()) return e
        }
        return e
    }

    private fun tipps(e: Ergebnis): Punkt? = ((e as? Ergebnis.Handeln)?.aktion as? Aktion.Tippen)?.punkt

    // ---------------- "+" tap ----------------

    @Test
    fun `after -7 the Bid window closes slowly - no second X at the top (it would hit the plus)`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 18, 280, 1, 2, 3)
        spiel.laufendeGebote = 50
        spiel.spielLimit = 50
        // After the X the game still shows the Bid window (with the message) for one image – it is closing.
        var nochmal: Bildschirm? = null
        val leser = BildschirmLeser { nochmal?.also { nochmal = null } ?: spiel.lies() }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        val xUm = ArrayList<Instant>()
        var nachX = 0
        laufen(bot, spiel, 400, jeSchritt = { e ->
            if (xUm.isNotEmpty()) nachX++
            if (tipps(e) == bidX) {
                xUm += spiel.jetzt
                if (xUm.size == 1) nochmal = spiel.letztesBild
            }
        }) { nachX >= 6 }
        assertTrue(nachX >= 6, "X was tapped")
        assertEquals(1, xUm.size, "exactly one X")
        assertTrue(spiel.obenGetippt.isEmpty(), "never into empty space at the top: ${spiel.obenGetippt}")
    }

    @Test
    fun `confirm window closes slowly after the X - no X again, no unnecessary stop`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        spiel.bestaetigenAnzeige = { 99 } // shows too much → X instead of Confirm
        var nochmal: Bildschirm? = null
        val leser = BildschirmLeser { nochmal?.let { b -> nochmal = null; b } ?: spiel.lies() }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        val bestX = Punkt(1806, 301)
        var xe = 0
        val e = laufen(bot, spiel, 120, jeSchritt = { r ->
            if (tipps(r) == bestX) {
                xe++
                nochmal = spiel.letztesBild // window still visible for one image
            }
        }) { xe >= 1 && spiel.ort == Ort.MARKT }
        assertFalse(e is Ergebnis.Fertig, "no stop: $e")
        assertEquals(1, xe, "the confirm X only once")
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    // ---------------- Bid above the max price / the SG ----------------

    @Test
    fun `Current Bid not readable and copy is at the max price - never bid blindly`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 20, 200, 1, 2, 3) // bid would be 21 = highest bid – if the price jumps, it would be too much
        spiel.mitBestaetigen = false
        spiel.currentBidAnzeige = { null }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 80) { spiel.geoeffnet.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertTrue(spiel.gebotePreise.isEmpty(), "did not bid blindly")
        assertTrue(log.any { it.contains("never bid blindly") }, log.joinToString("\n"))
    }

    @Test
    fun `Current Bid never readable, even with plenty of room - never bid blindly, lock the row`() {
        // Nov 11 (review): earlier the bot bid blindly when there was enough room. But if the list moved, maybe
        // a much more expensive copy opened (review: 26 instead of at most 21) – so never blindly any more.
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        spiel.mitBestaetigen = false
        spiel.currentBidAnzeige = { null }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 80) { spiel.geoeffnet.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertTrue(spiel.gebotePreise.isEmpty(), "${spiel.gebotePreise}")
        assertTrue(spiel.handlungen.any { it.startsWith("close Bid window (Current Bid not readable)") }, spiel.handlungen.joinToString("\n"))
    }

    @Test
    fun `Current Bid only readable on the second image - read again, then bid with the value read`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        spiel.mitBestaetigen = false
        var gelesen = 0
        spiel.currentBidAnzeige = { if (++gelesen == 1) null else it }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        val warten = ArrayList<String>()
        laufen(bot, spiel, 80, jeSchritt = { if (it is Ergebnis.Warten) warten += it.grund }) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertTrue(warten.any { it.contains("Current Bid not readable – looking again") }, warten.joinToString("\n"))
    }

    @Test
    fun `reason to close seen - a later badly read image does not turn it into a bid`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 13
        spiel.zeile("C-", 12, 200, 1, 2, 3)
        spiel.preisSprungImFenster = 1 // 13 in the window → bid 14, but only 13 SG
        spiel.mitBestaetigen = false
        var gelesen = 0
        spiel.currentBidAnzeige = { if (++gelesen == 1) it else 1 } // misread afterwards ("1")
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 40) { spiel.geoeffnet.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertTrue(spiel.gebotePreise.isEmpty(), "no bid above the SG: ${spiel.gebotePreise}")
        assertTrue(spiel.handlungen.any { it.startsWith("close Bid window (too little SG)") }, spiel.handlungen.joinToString("\n"))
    }

    // ---------------- Own bid outbid ----------------

    @Test
    fun `own copy without a recognized icon - do not bid again, only with a hammer`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3, zeichen = Zeichen.KEINS)
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("1-2-3", "C-", 14, 200, start.minusSeconds(60))
        gv.gesehen(Zeile(314, 15, null, 200, true, listOf(1, 2, 3), Zeichen.HAMMER, tippen = Punkt(1356, 374)), start.minusSeconds(30))
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, gebote = gv)
        laufen(bot, spiel, 60)
        assertTrue(spiel.geoeffnet.isEmpty(), "not opened without an icon")
        spiel.listen.getValue("C-")[0].zeichen = Zeichen.HAMMER
        laufen(bot, spiel, 60) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(16), spiel.gebotePreise, "with hammer: bid again")
    }

    @Test
    fun `Current Bid much higher than in the list - other copy, do not bid, but do not lock the row`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        spiel.preisSprungImFenster = 3 // 18 instead of 15: probably a different copy
        spiel.mitBestaetigen = false
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 40) { spiel.geoeffnet.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertTrue(spiel.gebotePreise.isEmpty())
        assertTrue(log.any { it.contains("different copy") }, log.joinToString("\n"))
        spiel.preisSprungImFenster = 0
        laufen(bot, spiel, 60) { spiel.gebotePreise.isNotEmpty() }
        assertEquals(listOf(19), spiel.gebotePreise, "opened again right after (not locked for 60 s)")
        assertTrue(Duration.between(start, spiel.jetzt).seconds < 60)
    }

    @Test
    fun `bookkeeping - is one of our own auctions in My Biddings about to end`() {
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("1-1-1", "C-", 15, 3, start)
        gv.gebotAbgegeben("2-2-2", "C-", 15, 600, start)
        assertTrue(gv.endetGleich(start), "ends in 3 s")
        assertFalse(gv.endetGleich(start.plusSeconds(10)), "already over")
        gv.weggedrueckt("1-1-1")
        assertFalse(gv.endetGleich(start), "dismissed – no longer in My Biddings")
    }

    @Test
    fun `My Biddings - an auction above the row is about to end - wait first, then tap`() {
        fun zeile(i: Int, id: Int, rest: Int, x: Boolean) = Zeile(
            MEINE_ERSTE_OBEN + i * 181, 15, null, rest, true, listOf(id, id, id), if (x) Zeichen.KEINS else Zeichen.UHR, x, 'C',
            Punkt(1356, MEINE_ERSTE_OBEN + i * 181 + 60), kreuzPunkt = if (x) Punkt(2293, MEINE_ERSTE_OBEN + i * 181 + 14) else null,
        )
        val bild = Bildschirm(
            Ort.MEINE_GEBOTE, zeilen = listOf(zeile(0, 5, 2, false), zeile(1, 7, 200, true)), laufendeGebote = 50, sg = 1000,
            knoepfe = mapOf(Knopf.MENUE_BIETERMARKT to Punkt(321, 427), Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488)),
        )
        val gv = GebotsVerwaltung()
        gv.limitMeldung(start, sicher = true)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { bild }, gebote = gv)
        var t = start
        val ergebnisse = (1..12).map { bot.schritt(t).also { t = t.plusMillis(400) } }
        val getippt = ergebnisse.mapNotNull { tipps(it) }
        assertFalse(getippt.any { it == bild.zeilen[1].tippen || it == bild.zeilen[1].kreuzPunkt }, "nothing on the row below: $getippt")
        assertTrue(ergebnisse.any { it is Ergebnis.Warten && it.grund.contains("an auction is about to end") }, ergebnisse.joinToString("\n"))
    }

    // ---------------- Counting in My Biddings ----------------

    @Test
    fun `My Biddings scrolled, counter unreadable - 3 visible rows are NOT all bids`() {
        fun zeile(oben: Int, i: Int) = Zeile(oben, 33, null, 600, true, listOf(i, i, i), Zeichen.UHR, false, 'C', Punkt(1356, oben + 60))
        val menue = mapOf(Knopf.MENUE_BIETERMARKT to Punkt(321, 427), Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488))
        val gv = GebotsVerwaltung()
        val bot = AuktionsBot(
            listOf(cMinus), StoppBedingung(),
            { Bildschirm(Ort.MEINE_GEBOTE, zeilen = listOf(zeile(450, 1), zeile(631, 2), zeile(812, 3)), laufendeGebote = null, sg = 100, knoepfe = menue) },
            gebote = gv,
        )
        bot.schritt(start)
        assertNull(gv.spielZahl, "list is not at the very top – the rows are not all of them")
    }

    // ---------------- Waiting unnecessarily / stop ----------------

    @Test
    fun `too little SG and none of our own bids in front - stop after 10 minutes instead of waiting forever`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 5
        spiel.zeile("C-", 15, 20_000, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        val e = laufen(bot, spiel, 400) { false }
        assertEquals(Ergebnis.Fertig(StoppGrund.SG_ALLE), e)
        assertTrue(StoppGrund.SG_ALLE.sperren, "screen off")
        assertTrue(Duration.between(start, spiel.jetzt).toMinutes() in 10..12)
    }

    @Test
    fun `too little SG, but one of our own bids is in front - keep waiting (SG can come back)`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 5
        spiel.zeile("C-", 15, 20_000, 1, 2, 3)
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("9-9-9", "C-", 15, 7200, start)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, gebote = gv)
        val e = laufen(bot, spiel, 60) { Duration.between(start, spiel.jetzt).toMinutes() >= 15 }
        assertFalse(e is Ergebnis.Fertig, "$e")
        assertTrue(bot.zuWenigSg)
    }

    @Test
    fun `goal - bought plus leading copies are already enough - no new bid`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("8-8-8", "C-", 15, 3600, start)
        gv.gebotAbgegeben("9-9-9", "C-", 15, 3600, start)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(zielKopien = 2), spiel, gebote = gv)
        val warten = ArrayList<String>()
        laufen(bot, spiel, 80, jeSchritt = { if (it is Ergebnis.Warten) warten += it.grund })
        assertTrue(spiel.geoeffnet.isEmpty(), "nothing opened")
        assertTrue(bot.zielVerplant)
        assertTrue(warten.any { it.startsWith("Goal planned") }, warten.distinct().joinToString("\n"))
    }

    @Test
    fun `SG check - seen in front, but the SG came back - counts as lost`() {
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("1-1-1", "C-", 20, 60, start)
        gv.gebotAbgegeben("2-2-2", "C-", 30, 60, start)
        // Start 1000 SG, both "in front" → 950 expected. But there are 970: the 20 SG of 1-1-1 came back.
        val texte = gv.auswerten(start.plusSeconds(100), sgJetzt = 970, startSg = 1000)
        assertEquals(1, gv.gekaufteKopien, texte.joinToString("\n"))
        assertEquals(1, gv.verloren)
        assertTrue(texte.any { it.contains("1-1-1") && it.contains("came back") }, texte.joinToString("\n"))
    }

    @Test
    fun `SG check - unsuitable SG balance is not used, wait briefly without a reliable balance`() {
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("1-1-1", "C-", 20, 60, start)
        assertTrue(gv.auswerten(start.plusSeconds(85), abgleichWarten = true).isEmpty(), "no reliable SG balance yet – wait")
        // Far too many SG (misread, SG bought …) → do not use, as before: in front = bought.
        gv.auswerten(start.plusSeconds(90), sgJetzt = 50_000, startSg = 1000)
        assertEquals(1, gv.gekaufteKopien)
    }

    // ---------------- Price fields ----------------

    @Test
    fun `price fields - one hiccup after successful inputs does not turn them off for good`() {
        val spiel = SpielAttrappe(start)
        val log = ArrayList<String>()
        var aussetzer = false
        val bot = AuktionsBot(
            listOf(cMinus, Suchregel("B-", 10, 60, maxRestzeitMin = 5)), StoppBedingung(), spiel,
            protokoll = { t ->
                log += t
                if (t.startsWith("Filter set to C-") && !aussetzer) {
                    aussetzer = true
                    spiel.eingabeGeht = false // at the next filter the input fails once
                }
                if (t.contains("input did not work this time")) spiel.eingabeGeht = true
            },
        )
        laufen(bot, spiel, 400) { log.any { it.startsWith("Filter set to B-") } }
        assertTrue(log.any { it.contains("input did not work this time") }, log.joinToString("\n"))
        assertEquals(true, bot.preisfelderGehen)
        assertEquals("60", spiel.filterMax, "B- with its own max price, not with 20 from C-")
    }

    // Nov 23 (coordinator): price fields given up were never tried again – a rank's list could be lost for the rest of the night.

    @Test
    fun `price fields given up - tried again after 10, 20 and 40 minutes, then they work again`() {
        val spiel = SpielAttrappe(start)
        spiel.eingabeGeht = false // from the start: typing into the price fields does not work
        for (i in 1..3) spiel.zeile("C-", 15, 9000L + i, i, i, i) // nothing to bid (ends much later than 5 minutes)
        val log = ArrayList<Pair<Instant, String>>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += spiel.jetzt to it })
        val ab = spiel.jetzt
        laufen(bot, spiel, 20_000, jeSchritt = { e -> (e as? Ergebnis.Handeln)?.let { aktionen += spiel.jetzt to it.aktion.grund } }) {
            if (log.count { it.second.startsWith("Price fields still cannot be filled in") } == 3) spiel.eingabeGeht = true
            log.any { it.second.startsWith("Price fields work again") }
        }
        fun min(t: Instant) = Duration.between(ab, t).toMillis() / 60_000.0
        val aufgegeben = log.filter { it.second.startsWith("Price fields in the filter cannot be filled in") }.map { min(it.first) }
        val versuche = log.filter { it.second.startsWith("Price fields: trying them again") }.map { min(it.first) }
        assertEquals(1, aufgegeben.size, log.joinToString("\n"))
        assertEquals(4, versuche.size, "$versuche")
        // Given up at the start; then 10, 20, 40, 40 minutes after each failed try (a few seconds for the filter itself).
        val abstaende = listOf(versuche[0] - aufgegeben[0]) + versuche.zipWithNext { a, b -> b - a }
        for ((ist, soll) in abstaende.zip(listOf(10.0, 20.0, 40.0, 40.0))) assertTrue(ist in soll..soll + 0.5, "$abstaende")
        assertEquals(true, bot.preisfelderGehen)
        assertEquals("20", spiel.filterMax)
        assertEquals(4, bot.preisfelderNeuVersuche)
        // Every try is a filter with Confirm – never less than 5 s after the reload before.
        val neuLaden = aktionen.filter { (_, g) -> g.startsWith("confirm filter") || g.endsWith("– refresh") }.map { it.first }
        assertTrue(neuLaden.zipWithNext().all { (a, b) -> Duration.between(a, b).toMillis() >= 5000 })
        assertEquals(0, bot.abfrageGebremst)
    }

    @Test
    fun `price fields given up while the filter holds the other rule's prices - tried again sooner, at most every 2 minutes`() {
        // C- 10–20 and B- 30–60 do not overlap: with B-'s prices in the game's filter, the C- list is empty.
        val spiel = SpielAttrappe(start)
        for (i in 1..3) spiel.zeile("C-", 15, 9000L + i, i, i, i)
        for (i in 4..6) spiel.zeile("B-", 40, 9000L + i, i, i, i)
        val log = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus, Suchregel("B-", 30, 60, maxRestzeitMin = 5)), StoppBedingung(), spiel, protokoll = { log += spiel.jetzt to it })
        laufen(bot, spiel, 2000) { log.any { it.second.startsWith("Filter set to B-") } }
        assertEquals("60", spiel.filterMax)
        // From now on typing fails – at the next filter (C-) the price fields are given up; the game's filter keeps 30–60.
        spiel.eingabeGeht = false
        laufen(bot, spiel, 2000) { log.any { it.second.contains("cannot be filled in") } }
        val aufgegeben = log.last().first
        laufen(bot, spiel, 5000) { Duration.between(aufgegeben, spiel.jetzt).toMinutes() >= 7 }
        val eilig = log.filter { it.second.startsWith("Price fields: trying them again now") }.map { it.first }
        assertTrue(eilig.size in 2..4, log.takeLast(30).joinToString("\n"))
        assertTrue(eilig.zipWithNext().all { (a, b) -> Duration.between(a, b).toSeconds() >= 120 }, "$eilig")
        assertTrue(log.any { it.second.contains("the game's filter holds 30–60, C- needs 10–20") }, log.joinToString("\n"))
        // Typing works again: well before the regular try (10, 20 min) the C- list is back.
        spiel.eingabeGeht = true
        val wieder = spiel.jetzt
        laufen(bot, spiel, 5000) { log.any { it.second.startsWith("Price fields work again") } }
        assertTrue(Duration.between(wieder, spiel.jetzt).toSeconds() <= 180, "${Duration.between(wieder, spiel.jetzt)}")
        assertEquals(true, bot.preisfelderGehen)
    }
}
