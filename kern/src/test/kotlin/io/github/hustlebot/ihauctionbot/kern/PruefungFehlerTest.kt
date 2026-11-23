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
 * Nov 11 (independent review of the core): one test each for the review points that are not already in
 * [ReviewTest] – so that they do not come back.
 */
class PruefungFehlerTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 12, 9, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)
    private val bidX = Punkt(2105, 108)
    private val menue = mapOf(Knopf.MENUE_BIETERMARKT to Punkt(321, 427), Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488))

    /** Let the bot and the fake run; [bis] ends earlier. Returns the last result. */
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

    /** Let the bot run with fixed images (time moves on). */
    private fun laufenMit(bot: AuktionsBot, schritte: Int, jeSchritt: (Ergebnis, Instant) -> Unit = { _, _ -> }): Pair<Ergebnis, Instant> {
        var t = start
        var e: Ergebnis = Ergebnis.Pausiert
        repeat(schritte) {
            e = bot.schritt(t)
            jeSchritt(e, t)
            if (e is Ergebnis.Fertig) return e to t
            t = t.plusMillis(
                maxOf(
                    380L,
                    when (val x = e) {
                        is Ergebnis.Handeln -> x.naechsterSchrittMs
                        is Ergebnis.Warten -> x.ms
                        is Ergebnis.Unsicher -> x.ms
                        else -> 1000
                    },
                ),
            )
        }
        return e to t
    }

    private fun tipp(e: Ergebnis): Punkt? = ((e as? Ergebnis.Handeln)?.aktion as? Aktion.Tippen)?.punkt

    // ---------------- 1: "Bidding successful" in the closing window ----------------

    @Test
    fun `Bidding successful in the closing Bid window - bid counts, nothing tapped`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        var nachBid = 0
        val leser = BildschirmLeser {
            if (nachBid > 0 && spiel.ort == Ort.MARKT) {
                nachBid--
                spiel.lies()
                Bildschirm(Ort.BIETEN, meldung = Meldung(1224, yOben = 537, yUnten = 681), knoepfe = mapOf(Knopf.SCHLIESSEN to bidX))
            } else {
                spiel.lies()
            }
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser, protokoll = { log += it })
        laufen(bot, spiel, 80, jeSchritt = { e ->
            if ((e as? Ergebnis.Handeln)?.aktion?.grund?.startsWith("Bid for") == true) nachBid = 4
        }) { spiel.gebotePreise.isNotEmpty() && nachBid == 0 && spiel.ort == Ort.MARKT }
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
        assertEquals(1, bot.gebote.abgegebeneGebote, log.joinToString("\n"))
        assertTrue(log.any { it.contains("\"Bidding successful\" message in the Bid window") }, log.joinToString("\n"))
    }

    @Test
    fun `short message that does not look like -7 is never the limit - even if My Biddings is almost full`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 18, 280, 1, 2, 3)
        spiel.laufendeGebote = 50
        spiel.spielLimit = 50
        spiel.meldungBreite = 1100 // short (< 1350), but not in the form of the "-7" message
        val gv = GebotsVerwaltung()
        gv.meineGeboteGelesen(48, emptyList(), start) // almost full according to the counter
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, gebote = gv)
        laufen(bot, spiel, 120) { spiel.handlungen.any { it.startsWith("close Bid window") } }
        assertFalse(bot.gebote.limitGemeldet, "no -7")
        // Nov 11 22:05: other messages do not hold the bot up – the window is closed normally (after the waiting time).
        assertTrue(spiel.handlungen.any { it.startsWith("close Bid window") }, spiel.handlungen.joinToString("\n"))
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
    }

    // ---------------- 2: slow network after Confirm ----------------

    @Test
    fun `after Confirm the Bid window stays for 8 s - never tap the X at the top`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        var nachConfirm = 0
        val leser = BildschirmLeser {
            if (nachConfirm > 0 && spiel.ort == Ort.MARKT) {
                nachConfirm--
                spiel.lies()
                Bildschirm(Ort.BIETEN, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to bidX))
            } else {
                spiel.lies()
            }
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        laufen(bot, spiel, 120, jeSchritt = { e ->
            if ((e as? Ergebnis.Handeln)?.aktion?.grund?.startsWith("Confirm") == true) nachConfirm = 20 // ~7.6 s of images
        }) { spiel.gebotePreise.isNotEmpty() && nachConfirm == 0 && spiel.ort == Ort.MARKT }
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
        assertEquals(listOf(16), spiel.gebotePreise)
    }

    @Test
    fun `Bid window never closes after Bid - stop after 20 s, never tap into empty space at the top`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.bietenWirktNicht = true // the window simply stays open
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        var bidUm: Instant? = null
        var xUm: Instant? = null
        val e = laufen(bot, spiel, 400, jeSchritt = { r ->
            if ((r as? Ergebnis.Handeln)?.aktion?.grund?.startsWith("Bid for") == true && bidUm == null) bidUm = spiel.jetzt
            if (tipp(r) == bidX && xUm == null) xUm = spiel.jetzt
        })
        // The X may only come after 10 s (here it closes the window – the fake obeys the X).
        assertTrue(xUm != null && Duration.between(bidUm, xUm).toMillis() >= 10_000, "X after ${Duration.between(bidUm, xUm ?: bidUm).toMillis()} ms")
        assertTrue(spiel.gebotePreise.isEmpty(), "$e")
        assertTrue(spiel.obenGetippt.isEmpty())
    }

    @Test
    fun `Bid window stays open even after the X - stop with a reason, no endless X`() {
        val bild = Bildschirm(Ort.BIETEN, aktuellesGebot = 15, knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to bidX))
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { bild })
        var xe = 0
        val (e, um) = laufenMit(bot, 400) { r, _ -> if (tipp(r) == bidX) xe++ }
        assertEquals(Ergebnis.Fertig(StoppGrund.FENSTER_KLEMMT), e)
        assertTrue(Duration.between(start, um).seconds in 25..35, "${Duration.between(start, um).seconds} s")
        assertTrue(xe <= 20, "$xe times X")
    }

    // ---------------- 5: SG balance ----------------

    @Test
    fun `SG - first balance and higher balances only count after two equal readings, lower ones right away`() {
        val folge = ArrayDeque(listOf(100, 100, 9999, 100, 5000, 5000, 40))
        val erwartet = listOf(null, 100, 100, 100, 100, 5000, 40)
        var sg = 0
        // My Biddings (without cleanup): the bot reads the SG balance there and goes back to the market.
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), {
            sg = folge.removeFirst()
            Bildschirm(Ort.MEINE_GEBOTE, sg = sg, laufendeGebote = 3, knoepfe = menue)
        })
        var t = start
        for (soll in erwartet) {
            bot.schritt(t)
            assertEquals(soll, bot.letzterSgStand, "after reading $sg")
            // Nov 12: images that look the same shortly after a tap count as old – gap larger than [Takt.altesBildMs].
            t = t.plusSeconds(7)
        }
    }

    @Test
    fun `SG - start SG for the budget only after two equal readings`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        var erste = true
        val leser = BildschirmLeser {
            val b = spiel.lies()
            if (erste && b.ort == Ort.MARKT) {
                erste = false
                b.copy(sg = 99_999) // misread once (one digit too many)
            } else {
                b
            }
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 100), leser)
        laufen(bot, spiel, 60) { bot.startSg != null }
        assertEquals(10_000, bot.startSg)
    }

    // ---------------- 6: no endless waiting ----------------

    @Test
    fun `secondary password missing - stop with a reason after 5 minutes`() {
        val bild = Bildschirm(Ort.ZWEITPASSWORT, knoepfe = mapOf(Knopf.PASSWORT_FELD to Punkt(1356, 630), Knopf.PASSWORT_OK to Punkt(1528, 804)))
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { bild }, hatZweitpasswort = false)
        val (e, um) = laufenMit(bot, 200)
        assertEquals(Ergebnis.Fertig(StoppGrund.ZWEITPASSWORT_FEHLT), e)
        assertTrue(Duration.between(start, um).toMinutes() in 5..6)
        assertTrue(StoppGrund.ZWEITPASSWORT_FEHLT.sperren)
    }

    @Test
    fun `message over the list never goes away - stop after 3 minutes, never tapped at the top`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 2000, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 60) { spiel.ort == Ort.MARKT && bot.filterStufe != null }
        spiel.meldungImmer = true
        val vorher = spiel.handlungen.size
        val ab = spiel.jetzt
        val e = laufen(bot, spiel, 2000)
        assertEquals(Ergebnis.Fertig(StoppGrund.MELDUNG_HAENGT), e)
        assertTrue(Duration.between(ab, spiel.jetzt).toMinutes() in 2..4)
        // Nov 11 22:05: the bot keeps working despite the message (refresh …) – but never at the top into the purchase bar.
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
        assertTrue(spiel.gebotePreise.isEmpty(), "the copy only ends later – no bid")
        assertTrue(spiel.handlungen.drop(vorher).none { it.contains("Bid") }, spiel.handlungen.drop(vorher).toString())
    }

    @Test
    fun `message in My Biddings never goes away - stop after 3 minutes`() {
        val bild = Bildschirm(Ort.MEINE_GEBOTE, meldung = Meldung(1224), laufendeGebote = 3, sg = 500, knoepfe = menue)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { bild })
        val (e, um) = laufenMit(bot, 400)
        assertEquals(Ergebnis.Fertig(StoppGrund.MELDUNG_HAENGT), e)
        assertTrue(Duration.between(start, um).toMinutes() in 2..4)
    }

    @Test
    fun `SG balance never readable - stop with a reason after 5 minutes`() {
        val spiel = SpielAttrappe(start)
        spiel.sg = null
        spiel.zeile("C-", 15, 2000, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        val e = laufen(bot, spiel, 1000)
        assertEquals(Ergebnis.Fertig(StoppGrund.SG_UNLESBAR), e)
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    // ---------------- 7: smaller points ----------------

    @Test
    fun `after pause and resume - no Bid on a window that was planned before the pause`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 200, 1, 2, 3)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 80) { spiel.geoeffnet.isNotEmpty() }
        // Row tapped – now pause, and "Resume" while the Bid window is open.
        bot.pausiert = true
        assertEquals(Ergebnis.Pausiert, bot.schritt(spiel.jetzt))
        bot.pausiert = false
        spiel.jetzt = spiel.jetzt.plusSeconds(30)
        val e = bot.schritt(spiel.jetzt)
        assertFalse(e is Ergebnis.Handeln, "after resume look again first: $e")
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `My Biddings - row above with 0 s or unreadable remaining time - wait briefly, then carry on anyway`() {
        fun zeile(i: Int, id: Int, rest: Int?, x: Boolean) = Zeile(
            MEINE_ERSTE_OBEN + i * 181, 15, null, rest, true, listOf(id, id, id), if (x) Zeichen.KEINS else Zeichen.UHR, x, 'C',
            Punkt(1356, MEINE_ERSTE_OBEN + i * 181 + 60), kreuzPunkt = if (x) Punkt(2293, MEINE_ERSTE_OBEN + i * 181 + 14) else null,
        )
        for (oben in listOf<Int?>(0, null)) {
            val bild = Bildschirm(
                Ort.MEINE_GEBOTE, zeilen = listOf(zeile(0, 5, oben, false), zeile(1, 7, 200, true)), laufendeGebote = 50, sg = 1000,
                knoepfe = menue,
            )
            val gv = GebotsVerwaltung()
            gv.limitMeldung(start, sicher = true)
            val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { bild }, gebote = gv)
            var erstesTippen: Instant? = null
            var gewartet = false
            laufenMit(bot, 60) { r, t ->
                if (r is Ergebnis.Warten && r.grund.contains("may be about to end")) gewartet = true
                if (r is Ergebnis.Handeln && r.aktion.grund.startsWith("press red X") && erstesTippen == null) erstesTippen = t
            }
            assertTrue(gewartet, "remaining time $oben: wait first")
            assertTrue(erstesTippen != null, "remaining time $oben: then carry on anyway (not forever)")
        }
    }

    @Test
    fun `bid without a known end no longer counts after a day without a look`() {
        val gv = GebotsVerwaltung()
        gv.gebotAbgegeben("1-1-1", "C-", 15, null, start)
        assertEquals(1, gv.vorneOffen())
        assertTrue(gv.auswerten(start.plus(Duration.ofHours(23))).isEmpty())
        val texte = gv.auswerten(start.plus(Duration.ofHours(25)))
        assertEquals(0, gv.vorneOffen(), texte.toString())
        assertEquals(0, gv.gekaufteKopien)
        assertNull(gv.gebot("1-1-1"))
    }
}
