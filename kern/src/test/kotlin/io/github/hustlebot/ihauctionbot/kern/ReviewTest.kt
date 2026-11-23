package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class ReviewTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 11, 21, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)

    /** Wrapper: after "Bid" it injects n images with the closing Bid window + success message. */
    class Huelle(val spiel: SpielAttrappe) : BildschirmLeser {
        var injizieren = 0
        var mitMeldung = true
        override fun lies(): Bildschirm {
            if (injizieren > 0 && spiel.ort == Ort.MARKT) {
                injizieren--
                spiel.lies() // advance the fake's time/state
                return Bildschirm(
                    Ort.BIETEN,
                    meldung = if (mitMeldung) Meldung(1224, yOben = 537, yUnten = 681) else null,
                    knoepfe = mapOf(Knopf.SCHLIESSEN to Punkt(2105, 108)),
                )
            }
            return spiel.lies()
        }
    }

    private fun laufen(bot: AuktionsBot, spiel: SpielAttrappe, schritte: Int, nachHandlung: (Aktion) -> Unit = {}, log: MutableList<String>) {
        repeat(schritte) {
            val e = bot.schritt(spiel.jetzt)
            when (e) {
                is Ergebnis.Handeln -> {
                    log += "${spiel.jetzt} ACT ${e.aktion.grund}"
                    spiel.ausfuehren(e.aktion)
                    val a = e.aktion
                    if (a is Aktion.Eingeben || a is Aktion.PasswortEingeben || (a is Aktion.Folge && a.hatEingaben)) {
                        bot.eingabeErgebnis(spiel.letzteEingabeOk)
                    }
                    nachHandlung(a)
                    spiel.jetzt = spiel.jetzt.plusMillis(e.naechsterSchrittMs)
                }
                is Ergebnis.Warten -> { log += "${spiel.jetzt} WAIT ${e.grund}"; spiel.jetzt = spiel.jetzt.plusMillis(e.ms) }
                is Ergebnis.Unsicher -> { log += "${spiel.jetzt} UNSURE ${e.grund}"; spiel.jetzt = spiel.jetzt.plusMillis(e.ms) }
                is Ergebnis.Fertig -> { log += "DONE ${e.grund}"; return }
                Ergebnis.Pausiert -> spiel.jetzt = spiel.jetzt.plusSeconds(1)
            }
        }
    }

    @Test
    fun `success message in the closing Bid window - bot taps the X at the top`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.zeile("C-", 16, 110, 2, 2, 2)
        val huelle = Huelle(spiel)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += "  log: $it" })
        laufen(bot, spiel, 80, nachHandlung = { a -> if (a.grund.startsWith("Bid for")) huelle.injizieren = 2 }, log = log)
        println(log.joinToString("\n"))
        assertTrue(spiel.obenGetippt.isEmpty(), "tapped at the top (window was already closed): ${spiel.obenGetippt}")
    }

    @Test
    fun `Bid window stays for 2,3 s after Bid (slow network) - bot taps the X at the top just before it closes`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        val huelle = Huelle(spiel)
        huelle.mitMeldung = false
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += "  log: $it" })
        var bidUm: Instant? = null
        laufen(bot, spiel, 40, nachHandlung = { a -> if (a.grund.startsWith("Bid for")) { huelle.injizieren = 8; bidUm = spiel.jetzt } }, log = log)
        println(log.take(30).joinToString("\n"))
        println("Bid at $bidUm")
        assertTrue(spiel.obenGetippt.isEmpty(), "tapped at the top: ${spiel.obenGetippt}")
    }

    @Test
    fun `Current Bid unreadable - blind bid above max price plus 1`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.preisSprungImFenster = 10 // the opened copy really costs 25 (other copy / outdated price)
        spiel.currentBidAnzeige = { null } // e.g. "25"/"30" with digits the reader does not know, ML Kit not available
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += "  log: $it" })
        laufen(bot, spiel, 30, log = log)
        println(log.take(30).joinToString("\n"))
        assertTrue(spiel.gebotePreise.all { it <= cMinus.hoechstesGebot }, "bids: ${spiel.gebotePreise}")
    }

    /** Remaining time of the rows unreadable (hatRestzeit, but restzeitSek = null). */
    class OhneZeit(val spiel: SpielAttrappe) : BildschirmLeser {
        override fun lies(): Bildschirm {
            val b = spiel.lies()
            if (b.ort != Ort.MARKT) return b
            return b.copy(zeilen = b.zeilen.map { it.copy(restzeitSek = null) })
        }
    }

    /**
     * Nov 11 (implementing the review): the bot no longer bids at all without a readable remaining time ("never guess"). So
     * no bid without an end can come about that "goal planned" would wait for forever. The original review asked for
     * "stops" here – that matched the other approach (bid anyway, estimate the end). With "do not bid" the one auction
     * ends after 30 s, the list is empty afterwards, and the bot keeps looking for new ones (no silent waiting). So the
     * check is: no bid, no "goal planned". If the remaining times stay unreadable, it stops (next test).
     */
    @Test
    fun `rule without time limit, remaining time unreadable - goal 1 reached, bot still waits forever`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 30, 1, 1, 1)
        val regel = Suchregel("C-", 10, 20) // without "only last X min"
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(regel), StoppBedingung(zielKopien = 1), OhneZeit(spiel), protokoll = { log += "  log: $it" })
        laufen(bot, spiel, 3000, log = log)
        println(log.takeLast(8).joinToString("\n"))
        println("Time: ${spiel.jetzt}, bids ${spiel.gebotePreise}, bought ${bot.gebote.gekaufteKopien}, in front ${bot.gebote.vorneOffen()}")
        assertTrue(spiel.gebotePreise.isEmpty(), "never bid without a readable remaining time: ${spiel.gebotePreise}")
        assertTrue(log.none { it.contains("Goal planned") }, "never wait for a bid without an end")
        assertTrue(bot.gebote.offeneGebote() == 0)
    }

    @Test
    fun `remaining times stay unreadable - stop with a reason after 10 minutes instead of forever`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 3600, 1, 1, 1)
        spiel.zeile("C-", 16, 3700, 2, 2, 2)
        val regel = Suchregel("C-", 10, 20)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(regel), StoppBedingung(zielKopien = 1), OhneZeit(spiel), protokoll = { log += "  log: $it" })
        laufen(bot, spiel, 5000, log = log)
        assertTrue(spiel.gebotePreise.isEmpty(), "${spiel.gebotePreise}")
        assertTrue(log.any { it == "DONE RESTZEIT_UNLESBAR" }, log.takeLast(5).joinToString("\n"))
        assertTrue(StoppGrund.RESTZEIT_UNLESBAR.sperren)
    }
}
