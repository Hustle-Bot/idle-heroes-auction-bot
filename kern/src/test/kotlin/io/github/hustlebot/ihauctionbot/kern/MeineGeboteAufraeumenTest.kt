package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 11: after "-7" (50 bids) the bot cleans up My Biddings. Outbid bids (red X at the top right) keep counting
 * in the game until the X is pressed. If the price still fits the rule → bid again, otherwise → press X.
 * Rows with a clock stay. The whole list from top to bottom, then back to the Bidding Market.
 */
class MeineGeboteAufraeumenTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 11, 21, 0).atZone(BERLIN).toInstant()

    /** As in the field example: B- filter 10–59 → bid at most 60, without a remaining time limit. */
    private val bMinus59 = Suchregel("B-", 10, 59)

    /** Actions on an image on which nothing could be tapped (loading screen, foreign window). */
    private val verboten = ArrayList<String>()

    private fun laufen(bot: AuktionsBot, spiel: SpielAttrappe, schritte: Int, bis: (Ergebnis) -> Boolean = { false }): Ergebnis {
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(schritte) {
            e = bot.schritt(spiel.jetzt)
            val gesehen = spiel.letztesBild?.ort
            when (val x = e) {
                is Ergebnis.Handeln -> {
                    if (gesehen == Ort.LADEN || gesehen == Ort.UNBEKANNT) verboten += "${x.aktion.grund} (on $gesehen)"
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

    /**
     * 10 bids in My Biddings (game limit 10 here), sorted by price:
     * 50 clock, 55 clock, 57 X, 58 clock, 58 clock, 59 X, 60 X, 61 X, 61 X, 62 X. In the bidding list a new copy for 30.
     */
    private fun volleListe(spiel: SpielAttrappe): Map<Int, SpielAttrappe.SimZeile> {
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 10
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        val m = LinkedHashMap<Int, SpielAttrappe.SimZeile>()
        fun g(nr: Int, preis: Int, x: Boolean, rest: Long = 3000) {
            m[nr] = spiel.meinGebot("B-", preis, rest, 1000 + nr, 2000 + nr, nr, ueberboten = x, inBieterListe = false)
        }
        g(1, 50, false)
        g(2, 55, false)
        g(3, 57, true, rest = 188)
        g(4, 58, false)
        g(5, 58, false)
        g(6, 59, true)
        g(7, 60, true, rest = 697)
        g(8, 61, true)
        g(9, 61, true)
        g(10, 62, true)
        return m
    }

    @Test
    fun `after -7 - whole list from top to bottom, bid again on 57, dismiss 60 and 61, clock stays, then keep bidding`() {
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })

        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")

        // The limit came with the new bid (30 → 31) – only after that (and only once) My Biddings.
        assertTrue(log.any { it.contains("50 bids reached") }, alles)
        assertEquals(1, spiel.handlungen.count { it.startsWith("My Biddings") }, alles)
        // 57 → 58 and 59 → 60 bid again (bid ≤ 60), the expensive ones dismissed with X, clock rows never touched.
        assertEquals(listOf(58, 60, 31), spiel.gebotePreise, alles)
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
        assertEquals(spiel.weggedrueckt, spiel.kreuzGedrueckt, "every X exactly once")
        val angefasst = spiel.geoeffnet.map { it.first } + spiel.kreuzGedrueckt.map { it.werte }
        for (nr in listOf(1, 2, 4, 5)) assertFalse(m[nr]!!.werte in angefasst, "clock row $nr stays")
        assertTrue(log.any { it == "My Biddings: outbid at 57 → bid again with 58" }, alles)
        assertTrue(log.any { it.startsWith("My Biddings: outbid at 60 → dismissed (61 too expensive") }, alles)
        assertTrue(log.any { it.startsWith("My Biddings: outbid at 61 → dismissed (62 too expensive") }, alles)
        assertTrue(log.any { it.startsWith("My Biddings done: 2 bid again, 4 dismissed") }, alles)
        // Nothing tapped next to it, never at the top into the purchase bar, never on a loading image.
        assertTrue(spiel.fehlTippsMeine.isEmpty(), alles)
        assertTrue(spiel.obenGetippt.isEmpty(), alles)
        assertTrue(verboten.isEmpty(), verboten.toString())
        // Back in the Bidding Market and bidding on; the limit is lifted.
        assertFalse(bot.raeumtAuf)
        assertFalse(bot.gebote.limitGemeldet)
        assertTrue(spiel.handlungen.contains("back to the Bidding Market"), alles)
        assertTrue(spiel.handlungen.any { it.startsWith("push the list in My Biddings further") }, "the list was scrolled through")
    }

    @Test
    fun `list is further down when opened - first all the way up, then everything`() {
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        spiel.versatzMeineBeimOeffnen = 900 // then the 57 (X) is not visible
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(spiel.handlungen.any { it.startsWith("push the list in My Biddings up") }, alles)
        assertTrue(log.any { it == "My Biddings: outbid at 57 → bid again with 58" }, "the row above too\n$alles")
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
    }

    @Test
    fun `list slides too far when pushing on - one row back, no row left out`() {
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        spiel.wischFaktorMeine = 1.5
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(log.any { it.contains("slid further than expected") }, alles)
        assertEquals(listOf(58, 60, 31), spiel.gebotePreise, alles)
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
    }

    @Test
    fun `a number is read one way, then another - wait briefly, then clean up anyway`() {
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        var n = 0
        val leser = BildschirmLeser {
            spiel.lies().let { b ->
                if (b.ort != Ort.MEINE_GEBOTE) return@let b
                n++
                // The bot reads the clock row with value 1 alternately as 1 and 7.
                b.copy(zeilen = b.zeilen.map { z -> if (z.werte[2] == 1 && n % 2 == 0) z.copy(werte = listOf(z.werte[0], z.werte[1], 7)) else z })
            }
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), leser, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        laufen(bot, spiel, 3000) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
        assertTrue(spiel.gebotePreise.contains(31), alles)
    }

    @Test
    fun `after the X two rows are missing (an auction ended at the same time) - no reason to stop`() {
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        var einmal = false
        val leser = BildschirmLeser {
            spiel.lies().let { b ->
                // Right after the first X another row is missing (auction over).
                if (b.ort == Ort.MEINE_GEBOTE && spiel.kreuzGedrueckt.size == 1 && !einmal && b.zeilen.size >= 3) {
                    einmal = true
                    b.copy(zeilen = b.zeilen.dropLast(1))
                } else {
                    b
                }
            }
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), leser, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        val e = laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertFalse(e is Ergebnis.Fertig, alles)
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
    }

    @Test
    fun `X opens an unknown window - tap nothing more, stop with a request for a screenshot`() {
        val spiel = SpielAttrappe(start)
        volleListe(spiel)
        spiel.kreuzOeffnetFenster = true
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        val e = laufen(bot, spiel, 1500)
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(Ergebnis.Fertig(StoppGrund.UNBEKANNT_NACH_X), e, alles)
        assertEquals("Unknown window after X in My Biddings – please save a report and check the GUIDE", StoppGrund.UNBEKANNT_NACH_X.text)
        assertEquals(1, spiel.kreuzGedrueckt.size, alles)
        assertTrue(spiel.handlungen.last().startsWith("press red X in My Biddings"), "nothing after the X: $alles")
        assertTrue(spiel.tippsImFremden.isEmpty(), alles)
        assertTrue(verboten.isEmpty(), verboten.toString())
        // After that the bot stays stopped.
        assertEquals(Ergebnis.Fertig(StoppGrund.UNBEKANNT_NACH_X), bot.schritt(spiel.jetzt))
    }

    @Test
    fun `X opens the Bid window of another row (list moved) - close it, never bid there, clean up on`() {
        // Nov 23 (emulator runs 21 and 24): the list re-sorted just before the tap – the X landed on a row in front.
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        spiel.kreuzOeffnetBieten = 1
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        val e = laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertFalse(e is Ergebnis.Fertig, alles)
        assertEquals(1, spiel.bietenStattKreuz.size, alles)
        val fremd = spiel.bietenStattKreuz.single()
        assertEquals(m[1]!!.werte, fremd.werte, alles)
        // Closed with its X, no bid in it (the row in front keeps its price), nothing at the top except that X.
        assertEquals(1, spiel.handlungen.count { it.startsWith("close Bid window (opened by the X in My Biddings") }, alles)
        assertEquals(50, fremd.preis, alles)
        assertTrue(log.any { it.startsWith("My Biddings: the list moved before the red X") }, alles)
        // The clean-up goes on as without the slip: same bids, same rows dismissed, then bidding on.
        assertEquals(listOf(58, 60, 31), spiel.gebotePreise, alles)
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
        assertTrue(spiel.obenGetippt.isEmpty(), alles)
        assertTrue(spiel.fehlTippsMeine.isEmpty(), alles)
        assertTrue(verboten.isEmpty(), verboten.toString())
    }

    @Test
    fun `after closing that Bid window old screenshots still show it - its X is not tapped a second time`() {
        // Nov 23 (fake-game test with 2 s delay): the X of the window was tapped again on an old screenshot – into the
        // top bar of My Biddings. Old screenshots of the closed window are now waited out.
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        spiel.kreuzOeffnetBieten = 1
        spiel.altesBietenNachSchliessen = 4
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        val e = laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertFalse(e is Ergebnis.Fertig, alles)
        assertEquals(1, spiel.handlungen.count { it.startsWith("close Bid window (opened by the X in My Biddings") }, alles)
        assertTrue(log.any { it.startsWith("My Biddings: the list moved before the red X") }, alles)
        assertTrue(spiel.obenGetippt.isEmpty(), "never into the top bar: ${spiel.obenGetippt}\n$alles")
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
        assertTrue(verboten.isEmpty(), verboten.toString())
    }

    @Test
    fun `X keeps opening Bid windows of other rows - close twice, the third time stop as before`() {
        val spiel = SpielAttrappe(start)
        volleListe(spiel)
        spiel.kreuzOeffnetBieten = 99
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        val e = laufen(bot, spiel, 1500)
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(Ergebnis.Fertig(StoppGrund.UNBEKANNT_NACH_X), e, alles)
        assertEquals(MAX_BID_NACH_KREUZ + 1, spiel.bietenStattKreuz.size, alles)
        assertEquals(MAX_BID_NACH_KREUZ, spiel.handlungen.count { it.startsWith("close Bid window (opened by the X in My Biddings") }, alles)
        // Never "Bid" in such a window, nothing after the last X, never at the top into the purchase bar.
        assertTrue(spiel.bietenStattKreuz.all { it.preis == 50 }, alles)
        assertTrue(spiel.handlungen.last().startsWith("press red X in My Biddings"), "nothing after the last X: $alles")
        assertTrue(spiel.obenGetippt.isEmpty(), alles)
        assertTrue(verboten.isEmpty(), verboten.toString())
    }

    @Test
    fun `bidding again gets -7 itself - then this row is dismissed`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 4
        spiel.nachbietenBrauchtPlatz = true
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        val x = spiel.meinGebot("B-", 57, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        for (i in 2..4) spiel.meinGebot("B-", 58, 3000, i, i, i, inBieterListe = false)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 4)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(x), spiel.weggedrueckt, alles)
        assertTrue(log.any { it.contains("bidding again did not work") && it.contains("dismissed") }, alles)
        assertEquals(listOf(31), spiel.gebotePreise, "bidding again did not go through, then the new bid")
        assertTrue(verboten.isEmpty(), verboten.toString())
    }

    @Test
    fun `price in the Bid window went up while bidding again - window closed, dismiss the row`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 4
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        val x = spiel.meinGebot("B-", 57, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        for (i in 2..4) spiel.meinGebot("B-", 58, 3000, i, i, i, inBieterListe = false)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 4)), protokoll = { log += it })
        laufen(bot, spiel, 400) { bot.gebote.limitGemeldet }
        spiel.preisSprungImFenster = 5 // someone bids while the window opens: 62 > 59
        laufen(bot, spiel, 1500) { spiel.weggedrueckt.isNotEmpty() }
        // Nov 11 (long run): a jump of more than 2 SG counts as "a different copy opened" – after that as normal.
        spiel.preisSprungImFenster = 0
        laufen(bot, spiel, 1500) { spiel.gebotePreise.isNotEmpty() }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(x), spiel.weggedrueckt, alles)
        assertTrue(spiel.handlungen.any { it.startsWith("close Bid window (too expensive)") }, alles)
        assertTrue(log.any { it.contains("dismissed (too expensive)") }, alles)
        assertEquals(listOf(31), spiel.gebotePreise, "then the new bid (30 + 1)")
    }

    @Test
    fun `rule with a remaining time limit - outbid, but ends too late - dismiss instead of bidding again`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 3
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        val x = spiel.meinGebot("B-", 57, 697, 1, 1, 1, ueberboten = true, inBieterListe = false) // 00:11:37
        spiel.meinGebot("B-", 58, 3000, 2, 2, 2, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 3, 3, 3, inBieterListe = false)
        val log = ArrayList<String>()
        val regel = Suchregel("B-", 10, 59, maxRestzeitMin = 5)
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 3)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(x), spiel.weggedrueckt, alles)
        assertTrue(log.any { it.contains("dismissed (remaining time 11 min") && it.contains("only last 5 min") }, alles)
    }

    @Test
    fun `only bid again, nothing dismissed - no slot freed, wait until the earliest end`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 3
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        spiel.meinGebot("B-", 57, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        spiel.meinGebot("B-", 58, 100, 2, 2, 2, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 3, 3, 3, inBieterListe = false)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 3)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { bot.gebote.wiederBietenAb != null && spiel.ort == Ort.MARKT }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(58), spiel.gebotePreise, alles)
        assertTrue(spiel.weggedrueckt.isEmpty(), alles)
        val ab = bot.gebote.wiederBietenAb
        assertNotNull(ab, alles)
        // Earliest end: copy 2 after 100 s, plus 5 s.
        assertTrue(!ab!!.isBefore(start.plusSeconds(104)) && !ab.isAfter(start.plusSeconds(105)), "$ab\n$alles")
        assertTrue(log.any { it.startsWith("My Biddings done: 1 bid again, 0 dismissed") && it.contains("no slot freed") }, alles)
    }

    @Test
    fun `price in My Biddings not readable - do not guess, dismiss`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 3
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        val x = spiel.meinGebot("B-", 57, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 2, 2, 2, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 3, 3, 3, inBieterListe = false)
        val leser = BildschirmLeser {
            spiel.lies().let { b ->
                if (b.ort == Ort.MEINE_GEBOTE) b.copy(zeilen = b.zeilen.map { if (it.ueberbotenKreuz) it.copy(preis = null) else it }) else b
            }
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), leser, gebote = spiel.bekannt(GebotsVerwaltung(limit = 3)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(x), spiel.weggedrueckt, alles)
        assertTrue(log.any { it.contains("dismissed (price not readable") }, alles)
        assertEquals(listOf(31), spiel.gebotePreise, "nothing bid again")
    }

    @Test
    fun `copy not from our own bookkeeping - never bid again, outbid ones only dismissed`() {
        // Nov 11 (review): earlier the rule with the same rank letter applied to unknown copies. But the badge
        // only shows the letter (B), not whether B-, B or B+ – after a restart a B+ could have been bid on again
        // under the B- rule. Now: unknown → never bid again; outbid → only the X.
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 3
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        // 57 would fit the B- rule – still do not bid again, the bot does not know the copy.
        val x = spiel.meinGebot("B-", 57, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 2, 2, 2, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 3, 3, 3, inBieterListe = false)
        val log = ArrayList<String>()
        val bot = AuktionsBot(
            listOf(bMinus59, Suchregel("B", 10, 80)), StoppBedingung(), spiel, gebote = GebotsVerwaltung(limit = 3), protokoll = { log += it },
        )
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(listOf(x), spiel.weggedrueckt, alles)
        assertTrue(log.any { it.contains("dismissed (no matching rule (copy not from our own bookkeeping))") }, alles)
        assertFalse(spiel.gebotePreise.contains(58), "not bid again: ${spiel.gebotePreise}")
    }

    @Test
    fun `X does not work - press at most twice, then leave the row`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.spielLimit = 3
        spiel.kreuzWirktNicht = true
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        spiel.meinGebot("B-", 65, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 2, 2, 2, inBieterListe = false)
        spiel.meinGebot("B-", 58, 3000, 3, 3, 3, inBieterListe = false)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 3)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { bot.gebote.wiederBietenAb != null && spiel.ort == Ort.MARKT }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertEquals(MAX_KREUZ_VERSUCHE, spiel.kreuzGedrueckt.size, alles)
        assertTrue(log.any { it.contains("the X does not work") }, alles)
        assertTrue(log.any { it.startsWith("My Biddings done: 0 bid again, 0 dismissed") && it.contains("no slot freed") }, alles)
    }

    @Test
    fun `without -7 - never My Biddings, even with outbid bids`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        spiel.zeile("B-", 31, 260, 98, 98, 98)
        spiel.meinGebot("B-", 57, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel)
        laufen(bot, spiel, 600) { java.time.Duration.between(start, spiel.jetzt).toMinutes() >= 4 }
        assertTrue(spiel.gebotePreise.isNotEmpty())
        assertTrue(spiel.handlungen.none { it.startsWith("My Biddings") || it.contains("in My Biddings") }, spiel.handlungen.toString())
        assertTrue(spiel.kreuzGedrueckt.isEmpty())
    }

    // ---------------- Nov 11: own count (about 50 in My Biddings) ----------------

    @Test
    fun `own count - at about 50 in My Biddings clean up right away, without waiting for -7`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        // The game never reports "-7" here (limit 50) – the bot counts by itself: limit 3.
        for (i in 1..4) spiel.zeile("B-", 29 + i, 200L + i * 10, 90 + i, 90 + i, 90 + i)
        val x = spiel.meinGebot("B-", 70, 600, 1, 1, 1, ueberboten = true, inBieterListe = false)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 3)), protokoll = { log += it })
        laufen(bot, spiel, 1500) { bot.gebote.wiederBietenAb != null || spiel.gebotePreise.size >= 4 }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(log.any { it.startsWith("About 3/3 in My Biddings – cleaning up there") }, alles)
        assertTrue(spiel.handlungen.none { it.contains("(limit)") }, "no -7: $alles")
        assertEquals(listOf(x), spiel.weggedrueckt, alles)
        // After that the counter shows 3 (our own three) – still full: wait instead of starting a fourth copy.
        assertEquals(listOf(31, 32, 33), spiel.gebotePreise, alles)
        assertTrue(log.any { it.contains("1 dismissed · approx. 3/3 – no slot freed") }, alles)
        assertEquals(1, spiel.handlungen.count { it.startsWith("My Biddings") }, "at most one pass\n$alles")
    }

    @Test
    fun `own count too high (auctions already over) - short look, still room, keep bidding`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        spiel.meinGebot("B-", 58, 3000, 1, 1, 1, inBieterListe = false)
        val gv = spiel.bekannt(GebotsVerwaltung(limit = 10)).apply { eintraegeGezaehlt(10) }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = gv, protokoll = { log += it })
        laufen(bot, spiel, 1500) { spiel.gebotePreise.isNotEmpty() }
        laufen(bot, spiel, 3) // the bot counts the bid as soon as the list is back
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(log.any { it.contains("still room, no cleanup needed") }, alles)
        assertEquals(listOf(31), spiel.gebotePreise, alles)
        assertEquals(2, gv.eintraege, "counter (1) + new bid")
        assertTrue(spiel.handlungen.none { it.startsWith("push the list in My Biddings") }, "no pass needed\n$alles")
    }

    @Test
    fun `own count - nothing free and full - one pass, then wait about 2 minutes, never in a circle`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.zeile("B-", 30, 1000, 99, 99, 99)
        for (i in 1..3) spiel.meinGebot("B-", 58, 3000, i, i, i, inBieterListe = false)
        val gv = spiel.bekannt(GebotsVerwaltung(limit = 3)).apply { eintraegeGezaehlt(3) }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = gv, protokoll = { log += it })
        val oeffnen = ArrayList<Instant>()
        var vorher = 0
        laufen(bot, spiel, 4000) {
            val n = spiel.handlungen.count { h -> h.startsWith("My Biddings") }
            if (n > vorher) oeffnen += spiel.jetzt
            vorher = n
            java.time.Duration.between(start, spiel.jetzt).toMinutes() >= 7
        }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(spiel.gebotePreise.isEmpty(), "full – no new bid\n$alles")
        assertTrue(oeffnen.size in 2..5, "a few passes, not all the time: $oeffnen\n$alles")
        oeffnen.zipWithNext().forEach { (a, b) ->
            assertTrue(java.time.Duration.between(a, b).toSeconds() >= 110, "at least ~2 minutes apart: $a → $b")
        }
        assertTrue(log.any { it.contains("no slot freed") && it.contains("bidding again from") }, alles)
        assertTrue(spiel.kreuzGedrueckt.isEmpty() && spiel.geoeffnet.isEmpty(), "clock rows never touched")
    }

    @Test
    fun `loading screen after bidding - just wait, tap nothing`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.filter = "B"
        spiel.ladenNachBieten = 4
        spiel.zeile("B-", 30, 250, 99, 99, 99)
        spiel.zeile("B-", 31, 260, 98, 98, 98)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 400) { spiel.gebotePreise.size >= 2 }
        assertEquals(listOf(31, 32), spiel.gebotePreise)
        assertTrue(verboten.isEmpty(), verboten.toString())
        assertTrue(log.any { it.startsWith("Loading screen (golden star)") }, log.joinToString("\n"))
    }

    @Test
    fun `loading screen does not go away - never tap, uncertain after one minute`() {
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), { Bildschirm(Ort.LADEN) }, protokoll = { log += it })
        var t = start
        var unsicher = false
        repeat(200) {
            val e = bot.schritt(t)
            assertFalse(e is Ergebnis.Handeln, "$e")
            if (e is Ergebnis.Unsicher) unsicher = true
            t = t.plusMillis(
                when (e) {
                    is Ergebnis.Warten -> e.ms
                    is Ergebnis.Unsicher -> e.ms
                    else -> 1000
                },
            )
        }
        assertTrue(unsicher, "after one minute the image counts as uncertain (the app saves it)")
        assertEquals(1, log.count { it.startsWith("Loading screen (golden star)") })
    }

    @Test
    fun `X point comes from recognition - if it is missing, nothing is pressed`() {
        val gv = GebotsVerwaltung(limit = 4).apply { limitMeldung(start, sicher = true) }
        val menue = mapOf(Knopf.MENUE_BIETERMARKT to Punkt(321, 427), Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488))
        // Row with X, but without an X point found (and too expensive → would have to be dismissed).
        val z = Zeile(374, 70, null, 600, true, listOf(1, 1, 1), Zeichen.KEINS, true, 'B', Punkt(1356, 434))
        val log = ArrayList<String>()
        val bot = AuktionsBot(
            listOf(bMinus59), StoppBedingung(),
            { Bildschirm(Ort.MEINE_GEBOTE, zeilen = listOf(z), laufendeGebote = 4, sg = 1000, knoepfe = menue) },
            gebote = gv, protokoll = { log += it },
        )
        var t = start
        val getippt = ArrayList<Aktion>()
        repeat(30) {
            when (val e = bot.schritt(t)) {
                is Ergebnis.Handeln -> getippt += e.aktion
                else -> Unit
            }
            t = t.plusMillis(500)
        }
        assertTrue(getippt.none { it is Aktion.Tippen && it.punkt.x > 2000 }, getippt.toString())
        assertTrue(log.any { it.contains("red X at") && it.contains("not found exactly") }, log.joinToString("\n"))
        assertNull(getippt.filterIsInstance<Aktion.Tippen>().firstOrNull { it.grund.startsWith("press red X") })
    }

    @Test
    fun `field test 1,33 - list moves 1,2 times as far - no pushing back with the learned ratio`() {
        // Nov 12 (field test 1.33): while cleaning up, almost after every push "slid further than expected – one
        // row back". The list in My Biddings moves further than the finger drags, like the bidding list.
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        spiel.wischFaktorMeine = 1.2
        val log = ArrayList<String>()
        val bot = AuktionsBot(
            listOf(bMinus59), StoppBedingung(), spiel, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it },
            wischVerhaeltnisStart = 1.2,
        )
        laufen(bot, spiel, 1500) { spiel.gebotePreise.contains(31) }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(spiel.handlungen.any { it.startsWith("push the list in My Biddings further") }, alles)
        assertTrue(log.none { it.contains("slid further than expected") }, alles)
        // Still every row seen: the same result as without pushing back.
        assertEquals(listOf(58, 60, 31), spiel.gebotePreise, alles)
        assertEquals(listOf(m[7], m[8], m[9], m[10]).map { it!!.werte }, spiel.weggedrueckt.map { it.werte }, alles)
        assertTrue(spiel.obenGetippt.isEmpty(), alles)
    }

    @Test
    fun `field test 1,33 - auction ends in seconds - no red X (the game does not accept it)`() {
        // Nov 12 (field test 1.33): "the X does not work" on three rows – all shortly before or after the end of their auction.
        val spiel = SpielAttrappe(start)
        val m = volleListe(spiel)
        val endetGleich = m[8]!!.werte
        val leser = BildschirmLeser {
            spiel.lies().let { b ->
                if (b.ort != Ort.MEINE_GEBOTE) b else b.copy(zeilen = b.zeilen.map { z -> if (z.werte == endetGleich) z.copy(restzeitSek = 8) else z })
            }
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), leser, gebote = spiel.bekannt(GebotsVerwaltung(limit = 10)), protokoll = { log += it })
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(1500) {
            e = bot.schritt(spiel.jetzt)
            when (val x = e) {
                is Ergebnis.Handeln -> {
                    spiel.ausfuehren(x.aktion)
                    val a = x.aktion
                    if (a is Aktion.Eingeben || a is Aktion.PasswortEingeben || (a is Aktion.Folge && a.hatEingaben)) bot.eingabeErgebnis(spiel.letzteEingabeOk)
                    spiel.jetzt = spiel.jetzt.plusMillis(maxOf(x.naechsterSchrittMs, 380))
                }
                is Ergebnis.Warten -> spiel.jetzt = spiel.jetzt.plusMillis(maxOf(x.ms, 380))
                is Ergebnis.Unsicher -> spiel.jetzt = spiel.jetzt.plusMillis(maxOf(x.ms, 380))
                else -> spiel.jetzt = spiel.jetzt.plusSeconds(1)
            }
            if (spiel.gebotePreise.contains(31)) return@repeat
        }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        assertTrue(spiel.kreuzGedrueckt.none { it.werte == endetGleich }, alles)
        assertTrue(log.any { it.contains("about to end – no X") }, alles)
        // The other expensive rows keep being dismissed.
        assertTrue(spiel.kreuzGedrueckt.any { it.werte == m[9]!!.werte }, alles)
    }

    @Test
    fun `counter 47 - do not clean up, keep bidding right away - clean up only at 50, no back and forth without a bid`() {
        // Nov 12 (rule): "If it says 49 or less there, it can go back to the auction house and keep bidding. If
        // it says 50, it must first wait until others run out or I get outbid."
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.laufendeGebote = 47
        for (i in 1..8) spiel.zeile("B-", 30 + i, 60L + i * 30, 100 + i, 100 + i, 100 + i)
        val gv = GebotsVerwaltung()
        gv.meineGeboteGelesen(50, emptyList(), start) // full according to our own count
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), spiel, gebote = gv, protokoll = { log += it })
        laufen(bot, spiel, 1500) { log.any { it.contains("cleaning up:") } || java.time.Duration.between(start, spiel.jetzt).toMinutes() >= 6 }
        val alles = log.joinToString("\n") + "\n---\n" + spiel.handlungen.joinToString("\n")
        // First look: 47 → no cleanup, straight back and keep bidding.
        assertTrue(log.any { it.contains("47 bids running") && it.contains("no cleanup needed") }, alles)
        // Then 3 bids (47 → 50), then My Biddings again: now 50 → clean up.
        assertEquals(3, spiel.gebotePreise.size, alles)
        assertTrue(log.any { it.contains("50 bids running") && it.contains("cleaning up:") }, alles)
        // Never twice in a row in My Biddings without a bid in between.
        assertEquals(2, spiel.handlungen.count { it.startsWith("My Biddings") }, alles)
    }
}
