package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 22 (overnight, owner's decisions): reconnect after "Lost connection" (wait ~60 s, Confirm, filter and sort again,
 * at most 5 times per run) and buyout ("Buyout Price" up to the rule's own maximum, switched off by default).
 */
class NeuverbindenUndBuyoutTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 22, 23, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)
    private val cBuyout = cMinus.copy(buyoutMax = 30)

    /** Lets the bot run until [bis] or [schritte] steps. Records every result. */
    private fun laufen(
        bot: AuktionsBot,
        spiel: SpielAttrappe,
        schritte: Int,
        ergebnisse: MutableList<Ergebnis>? = null,
        bis: (Ergebnis) -> Boolean = { false },
    ): Ergebnis {
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(schritte) {
            e = bot.schritt(spiel.jetzt)
            ergebnisse?.add(e)
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

    // ---------------- Reconnect ----------------

    /** A market with copies to bid on: one new one every time the bot has bid. */
    private fun markt(): SpielAttrappe {
        val spiel = SpielAttrappe(start)
        repeat(8) { spiel.zeile("C-", 12 + it % 5, 100L + it * 10, 1, 1, it + 1) }
        return spiel
    }

    @Test
    fun `lost connection - waits about 60 s, taps Confirm on the dialog, sets filter and sort again, bids on`() {
        val spiel = markt()
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 200) { spiel.gebotePreise.size >= 2 && spiel.ort == Ort.MARKT }
        assertEquals(2, spiel.gebotePreise.size)
        assertEquals("C-", spiel.filter)
        assertEquals(Sortierung.RANG, spiel.sortierung)

        val weg = spiel.jetzt
        spiel.ort = Ort.VERBINDUNG_WEG
        val vorher = spiel.handlungen.size
        laufen(bot, spiel, 400) { spiel.confirmGetipptUm.isNotEmpty() }
        assertEquals(1, spiel.confirmGetipptUm.size, "Confirm tapped once")
        assertEquals(vorher + 1, spiel.handlungen.size, "nothing but Confirm: ${spiel.handlungen.drop(vorher)}")
        val gewartet = Duration.between(weg, spiel.confirmGetipptUm.single()).toMillis()
        assertTrue(gewartet in 60_000..62_000, "waited $gewartet ms before Confirm")
        assertEquals(1, bot.neuverbindungen)

        // The game reset filter and sort order (as after a new start) – the bot sets both again and bids on.
        assertEquals(null, spiel.filter)
        laufen(bot, spiel, 300) { spiel.gebotePreise.size >= 4 }
        assertEquals(4, spiel.gebotePreise.size, "bids on after the reconnect: ${log.takeLast(15)}")
        assertEquals("C-", spiel.filter, "filter set again")
        assertEquals(Sortierung.RANG, spiel.sortierung, "sort order set again")
        assertTrue(spiel.tippsImFremden.isEmpty(), "${spiel.tippsImFremden}")
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
        assertTrue(log.any { it.contains("reconnect 1 of 5") }, "logged with the count")
    }

    @Test
    fun `lost connection - at most 5 reconnects per run, the 6th loss stops without a tap`() {
        val spiel = markt()
        repeat(40) { spiel.zeile("C-", 12 + it % 5, 200L + it * 5, 2, 2, it + 1) }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        for (n in 1..5) {
            laufen(bot, spiel, 300) { spiel.gebotePreise.size >= n && spiel.ort == Ort.MARKT }
            spiel.ort = Ort.VERBINDUNG_WEG
            laufen(bot, spiel, 400) { spiel.confirmGetipptUm.size >= n }
            assertEquals(n, bot.neuverbindungen)
        }
        laufen(bot, spiel, 300) { spiel.gebotePreise.size >= 6 && spiel.ort == Ort.MARKT }
        spiel.ort = Ort.VERBINDUNG_WEG
        val vorher = spiel.handlungen.size
        val e = laufen(bot, spiel, 50)
        assertEquals(Ergebnis.Fertig(StoppGrund.VERBINDUNG_WEG), e)
        assertEquals(vorher, spiel.handlungen.size, "no tap on the 6th dialog")
        assertEquals(5, spiel.confirmGetipptUm.size)
    }

    @Test
    fun `lost connection - after Confirm an unknown screen, taps nothing and stops after 3 minutes`() {
        val spiel = markt()
        spiel.nachConfirm = Ort.UNBEKANNT
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 200) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        spiel.ort = Ort.VERBINDUNG_WEG
        val e = laufen(bot, spiel, 2000)
        assertEquals(Ergebnis.Fertig(StoppGrund.VERBINDUNG_WEG), e)
        assertEquals(1, spiel.confirmGetipptUm.size)
        assertTrue(spiel.tippsImFremden.isEmpty(), "tapped on the unknown screen: ${spiel.tippsImFremden}")
        val nach = Duration.between(spiel.confirmGetipptUm.single(), spiel.jetzt).toMillis()
        assertTrue(nach in 180_000..183_000, "stopped $nach ms after Confirm")
    }

    @Test
    fun `lost connection - Confirm button not recognized, no tap, stop`() {
        val spiel = markt()
        spiel.verbindungKnopf = false
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 200) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        spiel.ort = Ort.VERBINDUNG_WEG
        val vorher = spiel.handlungen.size
        val e = laufen(bot, spiel, 1000)
        assertEquals(Ergebnis.Fertig(StoppGrund.VERBINDUNG_WEG), e)
        assertEquals(vorher, spiel.handlungen.size)
        assertEquals(0, bot.neuverbindungen)
    }

    @Test
    fun `lost connection - the dialog goes away by itself, filter and sort are checked again, no tap on it`() {
        val spiel = markt()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 200) { spiel.gebotePreise.isNotEmpty() && spiel.ort == Ort.MARKT }
        spiel.ort = Ort.VERBINDUNG_WEG
        laufen(bot, spiel, 20) // 10 s of the dialog
        spiel.ort = Ort.MARKT
        spiel.filter = null // the game reloaded the auction house
        spiel.sortierung = Sortierung.PREIS
        laufen(bot, spiel, 300) { spiel.gebotePreise.size >= 3 }
        assertEquals(0, spiel.confirmGetipptUm.size)
        assertEquals(0, bot.neuverbindungen, "not counted")
        assertEquals(3, spiel.gebotePreise.size)
        assertEquals("C-", spiel.filter)
        assertEquals(Sortierung.RANG, spiel.sortierung)
    }

    // ---------------- Buyout ----------------

    @Test
    fun `buyout off (default) - Buyout is never tapped, bidding exactly as before`() {
        fun lauf(mitSofort: Boolean): Pair<List<String>, SpielAttrappe> {
            val spiel = SpielAttrappe(start)
            spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = if (mitSofort) 22 else null)
            spiel.zeile("C-", 12, 120, 1, 1, 2, sofort = if (mitSofort) 18 else null)
            spiel.zeile("C-", 17, 140, 1, 1, 3)
            // Rule WITH a buyout maximum – but buyout is switched off for the bot.
            val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel)
            laufen(bot, spiel, 150) { spiel.gebotePreise.size >= 3 && spiel.ort == Ort.MARKT }
            return spiel.handlungen.toList() to spiel
        }
        val (ohne, _) = lauf(false)
        val (mit, spiel) = lauf(true)
        assertTrue(spiel.sofortGetippt.isEmpty())
        assertEquals(3, spiel.gebotePreise.size)
        assertEquals(ohne, mit, "the same actions with and without buyout prices")
    }

    @Test
    fun `buyout on - copy with buyout price at most the maximum is bought outright, counts as bought and toward the budget`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        val z = spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 28)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, protokoll = { log += it }, buyoutAn = true)
        laufen(bot, spiel, 120) { spiel.gekauft >= 1 && bot.sofortGekauft >= 1 }
        assertEquals(listOf(z), spiel.sofortGetippt)
        assertEquals(1, spiel.gekauft)
        assertEquals(972, spiel.sg)
        assertEquals(1, bot.sofortGekauft, log.takeLast(10).toString())
        assertEquals(1, bot.gebote.gekaufteKopien)
        assertEquals(28L, bot.gebote.ausgegebenSg)
        assertTrue(spiel.gebotePreise.isEmpty(), "no bid on it")
        assertTrue(log.any { it.startsWith("Bought out") }, "$log")
    }

    @Test
    fun `buyout - above the maximum, too little SG or budget - not bought, bid normally`() {
        // Above the maximum.
        run {
            val spiel = SpielAttrappe(start)
            spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 31)
            val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, buyoutAn = true)
            laufen(bot, spiel, 100) { spiel.gebotePreise.isNotEmpty() }
            assertTrue(spiel.sofortGetippt.isEmpty())
            assertEquals(listOf(16), spiel.gebotePreise)
        }
        // Too little SG for the buyout (enough for a bid).
        run {
            val spiel = SpielAttrappe(start)
            spiel.sg = 25
            spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 28)
            val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, buyoutAn = true)
            laufen(bot, spiel, 100) { spiel.gebotePreise.isNotEmpty() }
            assertTrue(spiel.sofortGetippt.isEmpty())
            assertEquals(listOf(16), spiel.gebotePreise)
        }
        // Budget: 30 SG – the first buyout (28) fits, the second one not any more.
        run {
            val spiel = SpielAttrappe(start)
            spiel.sg = 1000
            spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 28)
            spiel.zeile("C-", 15, 110, 1, 1, 2, sofort = 25)
            val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(sgBudget = 30), spiel, buyoutAn = true)
            laufen(bot, spiel, 300)
            assertEquals(1, spiel.sofortGetippt.size, "only one buyout fits into the budget")
            assertTrue(1000 - spiel.sg!! <= 30, "budget kept: ${1000 - spiel.sg!!}")
        }
    }

    @Test
    fun `buyout - window shows a different buyout price or a gray button - close with X, never Buyout or Bid from that window`() {
        for (fall in listOf("other price", "gray button", "not readable")) {
            val spiel = SpielAttrappe(start)
            spiel.sg = 1000
            spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 28)
            when (fall) {
                "other price" -> spiel.sofortAnzeige = { it + 2 }
                "gray button" -> spiel.sofortKnopfGelb = false
                else -> spiel.sofortAnzeige = { null }
            }
            val log = ArrayList<String>()
            val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, protokoll = { log += it }, buyoutAn = true)
            laufen(bot, spiel, 200) { spiel.gebotePreise.isNotEmpty() }
            assertTrue(spiel.sofortGetippt.isEmpty(), fall)
            // The buyout attempt is given up – afterwards the copy is bid on normally (a new opening of the window).
            assertEquals(listOf(16), spiel.gebotePreise, "$fall: ${log.takeLast(12)}")
            assertTrue(log.any { it.startsWith("Buyout 1-1-1:") && it.contains("no buyout") }, "$fall: $log")
            assertTrue(spiel.handlungen.any { it.startsWith("close Bid window (buyout") }, "$fall: ${spiel.handlungen}")
        }
    }

    @Test
    fun `buyout - unknown confirmation window afterwards, taps nothing, keeps the screenshot, stops with its own reason`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.nachBuyout = SpielAttrappe.NachBuyout.BESTAETIGEN
        spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 28)
        val ergebnisse = ArrayList<Ergebnis>()
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, buyoutAn = true)
        val e = laufen(bot, spiel, 100, ergebnisse)
        assertEquals(Ergebnis.Fertig(StoppGrund.BUYOUT_UNBEKANNT), e)
        assertEquals(1, spiel.sofortGetippt.size)
        assertTrue(spiel.tippsNachBuyout.isEmpty(), "${spiel.tippsNachBuyout}")
        // The screenshot of the window is marked as uncertain (the app saves it for the report) right before the stop.
        assertTrue(ergebnisse[ergebnisse.size - 2] is Ergebnis.Unsicher, "${ergebnisse.takeLast(3)}")
        assertTrue(StoppGrund.BUYOUT_UNBEKANNT.sperren)
    }

    @Test
    fun `buyout - unknown screen after Buyout that stays, stops with its own reason, no tap`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.nachBuyout = SpielAttrappe.NachBuyout.NICHTS
        spiel.zeile("C-", 15, 100, 1, 1, 1, sofort = 28)
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, buyoutAn = true)
        laufen(bot, spiel, 100) { spiel.sofortGetippt.isNotEmpty() }
        spiel.ort = Ort.UNBEKANNT
        val vorher = spiel.handlungen.size
        val e = laufen(bot, spiel, 100)
        assertEquals(Ergebnis.Fertig(StoppGrund.BUYOUT_UNBEKANNT), e)
        assertEquals(vorher, spiel.handlungen.size)
    }

    @Test
    fun `buyout - never tapped twice for the same copy`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.nachBuyout = SpielAttrappe.NachBuyout.NICHTS // Buyout has no effect, the window stays open
        spiel.zeile("C-", 15, 3000, 1, 1, 1, sofort = 28) // too long for a bid (rule: last 5 min)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, protokoll = { log += it }, buyoutAn = true)
        laufen(bot, spiel, 600)
        assertEquals(1, spiel.sofortGetippt.size, log.takeLast(20).toString())
        assertTrue(spiel.handlungen.any { it.startsWith("close Bid window (Buyout had no effect") }, "${spiel.handlungen.takeLast(10)}")
        assertEquals(0, bot.sofortGekauft)
        assertEquals(0, bot.gebote.gekaufteKopien)
    }

    @Test
    fun `buyout - SG did not drop (bought by someone else) - not counted`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 15, 3000, 1, 1, 1, sofort = 28)
        spiel.nachBuyout = SpielAttrappe.NachBuyout.KAUFEN
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, protokoll = { log += it }, buyoutAn = true)
        laufen(bot, spiel, 100) { spiel.sofortGetippt.isNotEmpty() }
        spiel.sg = 1000 // the game gives nothing for it: someone else was faster
        laufen(bot, spiel, 100)
        assertEquals(0, bot.sofortGekauft)
        assertTrue(log.any { it.contains("did not go through") }, "$log")
    }

    @Test
    fun `buyout on - filter max goes up to the buyout maximum, copies above the rule max are only bought out, never bid on`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        val teuerMitBuyout = spiel.zeile("C-", 25, 100, 1, 1, 1, sofort = 28)
        spiel.zeile("C-", 22, 110, 1, 1, 2) // above the rule max, no buyout → never opened
        spiel.zeile("C-", 15, 120, 1, 1, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, protokoll = { log += it }, buyoutAn = true)
        laufen(bot, spiel, 300) { spiel.gekauft >= 1 && spiel.gebotePreise.isNotEmpty() }
        assertEquals("30", spiel.filterMax, "filter max = buyout maximum")
        assertEquals("10", spiel.filterMin)
        assertEquals(listOf(teuerMitBuyout), spiel.sofortGetippt)
        assertEquals(listOf(16), spiel.gebotePreise, "only the copy within the rule is bid on")
        assertTrue(spiel.geoeffnet.none { it.first == listOf(1, 1, 2) }, "the 22 SG copy without buyout was opened: ${spiel.geoeffnet}")
        // Keep running: still no bid above 21 SG.
        laufen(bot, spiel, 300)
        assertTrue(spiel.gebotePreise.all { it <= cBuyout.hoechstesGebot }, "${spiel.gebotePreise}")
    }

    @Test
    fun `wider filter for buyout - an unreadable row price never counts as fitting (no bid above the max price)`() {
        // Buyout maximum 30 > max price 20: the list also shows copies up to 30 – a row without a readable price is skipped.
        run {
            val spiel = SpielAttrappe(start)
            spiel.preisUnlesbar = true
            spiel.zeile("C-", 25, 100, 1, 1, 1)
            spiel.zeile("C-", 15, 110, 1, 1, 2)
            val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, buyoutAn = true)
            laufen(bot, spiel, 300)
            assertEquals("30", spiel.filterMax)
            assertTrue(spiel.geoeffnet.isEmpty(), "opened: ${spiel.geoeffnet}")
            assertTrue(spiel.gebotePreise.isEmpty())
        }
        // The same without buyout (filter max = rule max): the filter guarantees the price – the 15 SG copy is bid on as before.
        for (bot in listOf<(SpielAttrappe) -> AuktionsBot>(
            { AuktionsBot(listOf(cBuyout), StoppBedingung(), it) }, // buyout off
            { AuktionsBot(listOf(cMinus.copy(buyoutMax = 18)), StoppBedingung(), it, buyoutAn = true) }, // buyout max below max price
        )) {
            val spiel = SpielAttrappe(start)
            spiel.preisUnlesbar = true
            spiel.zeile("C-", 25, 100, 1, 1, 1)
            spiel.zeile("C-", 15, 110, 1, 1, 2)
            laufen(bot(spiel), spiel, 300) { spiel.gebotePreise.isNotEmpty() }
            assertEquals("20", spiel.filterMax)
            assertEquals(listOf(16), spiel.gebotePreise)
        }
    }

    @Test
    fun `buyout - never on a copy where our own bid is in front, even if the clock icon is not recognized`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 15, 3000, 1, 1, 1, sofort = 28) // our bid is in front, but the row shows no icon
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("1-1-1", "C-", 15, 3000, start.minusSeconds(60))
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(), spiel, gebote = gv, buyoutAn = true)
        laufen(bot, spiel, 200)
        assertTrue(spiel.sofortGetippt.isEmpty(), "${spiel.handlungen.takeLast(10)}")
    }

    @Test
    fun `buyout - reaching the goal by buyouts stops the bot`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.zeile("C-", 15, 3000, 1, 1, 1, sofort = 28)
        spiel.zeile("C-", 15, 3000, 1, 1, 2, sofort = 26)
        val bot = AuktionsBot(listOf(cBuyout), StoppBedingung(zielKopien = 2), spiel, buyoutAn = true)
        val e = laufen(bot, spiel, 300)
        assertEquals(Ergebnis.Fertig(StoppGrund.ZIEL_ERREICHT), e)
        assertEquals(2, spiel.gekauft)
        assertEquals(946, spiel.sg)
    }
}
