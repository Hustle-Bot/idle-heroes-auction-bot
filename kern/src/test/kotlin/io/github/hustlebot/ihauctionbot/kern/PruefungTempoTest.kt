package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 12 (independent review of the speed changes from Nov 11: swiping measures along, bidding on through messages):
 * one test each for the two bugs the review found – so that they do not come back.
 */
class PruefungTempoTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 12, 9, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)

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

    // ---------------- 1. Swiping: the end of the list is not a measurement ----------------

    /**
     * Short list (9 copies): from the top the list can only move 703 instead of 905 pixels (78 %). Earlier that did not count as
     * the end of the list (only below 60 %), but as a measurement: after two rounds the ratio was 0.84 instead of 1.00. When
     * new auctions came in afterwards, the finger dragged too far, the estimate from the row grid was "certainly" one row
     * off – and on EVERY page a copy was missing (6, 12, 18, 24), although the log said "5 new copies".
     */
    @Test
    fun `end of the list does not distort the swipe ratio - no copy missing afterwards`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.0 // the game moves the list exactly as far as the finger
        for (i in 1..9) spiel.zeile("C-", 15, 10_000L + i, i, i, i, zeichen = Zeichen.UHR)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        var runden = 0
        laufen(bot, spiel, 600, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && e.aktion.grund.contains("round over")) runden++
        }) { runden >= 2 }
        val nachZweiRunden = bot.wischVerhaeltnis
        assertEquals(1.0, nachZweiRunden, 0.05, log.filter { it.startsWith("Swipe:") }.joinToString("\n"))
        assertTrue(log.any { it.startsWith("Swipe: list was at its end") }, "measurement at the end of the list taken back")

        // New auctions come in (the list gets long): now every copy must be fully visible once.
        for (i in 10..30) spiel.zeile("C-", 15, 10_000L + i, i, i, i, zeichen = Zeichen.UHR)
        val gesehen = HashSet<List<Int?>>()
        laufen(bot, spiel, 600, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && (e.aktion.grund.startsWith("further down") || e.aktion.grund.contains("round over"))) {
                spiel.letztesBild!!.zeilen.forEach { gesehen += it.werte }
            }
        }) { spiel.handlungen.count { it.contains("round over") } >= 3 }
        val fehlt = (1..30).map { listOf(it, it, it) }.filter { it !in gesehen }
        assertTrue(
            fehlt.isEmpty(),
            "ratio $nachZweiRunden; never seen: $fehlt\n" +
                log.filter { it.startsWith("Swipe:") || it.startsWith("Swiped") || it.startsWith("List") }.joinToString("\n"),
        )
    }

    /**
     * The app passes in the ratio from the last run (1.0), but today the list moves 1.2 times as far. Earlier
     * the start value counted as two measurements: the estimate from the row grid was "certain" right away – and was one row
     * off, a copy was missing on EVERY page. Now a short measuring swipe checks the start value first.
     */
    @Test
    fun `outdated start value from the app - no copy is skipped`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.2
        for (i in 1..40) spiel.zeile("C-", 15, 10_000L + i, i, i, i, zeichen = Zeichen.UHR)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, wischVerhaeltnisStart = 1.0)
        val gesehen = HashSet<List<Int?>>()
        laufen(bot, spiel, 900, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && (e.aktion.grund.startsWith("further down") || e.aktion.grund.contains("round over"))) {
                spiel.letztesBild!!.zeilen.forEach { gesehen += it.werte }
            }
        }) { spiel.handlungen.any { it.contains("round over") } }
        assertTrue(spiel.handlungen.any { it.contains("round over") }, "got to the end of the list")
        val fehlt = (1..40).map { listOf(it, it, it) }.filter { it !in gesehen }
        assertTrue(
            fehlt.isEmpty(),
            "ratio ${bot.wischVerhaeltnis}; never seen: $fehlt\n" +
                log.filter { it.startsWith("Swipe:") || it.startsWith("Swiped") || it.startsWith("List") }.joinToString("\n"),
        )
    }

    // ---------------- 2. SG display comes ~2 s late ----------------

    /** Like the real game (report Nov 11): the SG balance at the top right only shows a new value [lagMs] later. */
    private fun sgVerzoegert(spiel: SpielAttrappe, lagMs: Long) = object : BildschirmLeser {
        val verlauf = ArrayList<Pair<Instant, Int?>>()
        override fun lies(): Bildschirm {
            val b = spiel.lies()
            if (verlauf.lastOrNull()?.second != spiel.sg) verlauf += spiel.jetzt to spiel.sg
            val alt = verlauf.lastOrNull { !it.first.isAfter(spiel.jetzt.minusMillis(lagMs)) }?.second ?: verlauf.first().second
            return if (b.sg != null) b.copy(sg = alt) else b
        }
    }

    private fun budgetLauf(budget: Int, durchMeldung: Boolean): List<Int> {
        val spiel = SpielAttrappe(start)
        spiel.sg = 1000
        spiel.mitBestaetigen = false // as usual on the phone: right back to the list after "Bid"
        spiel.erfolgsMeldungMs = 3500 // "Bidding successful" lies over the list for 3–4 s
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.zeile("C-", 15, 120, 2, 2, 2)
        val bot = AuktionsBot(
            listOf(cMinus), StoppBedingung(sgBudget = budget), sgVerzoegert(spiel, 2000), durchMeldungTippen = durchMeldung,
        )
        laufen(bot, spiel, 200)
        return spiel.gebotePreise.toList()
    }

    /**
     * Budget 30, bids of 16 each: only ONE bid fits. Earlier the bot waited until "Bidding successful" was gone (3–4 s) –
     * by then the SG balance was already right. Since it bids on through the message, it calculated with the old SG balance and bid
     * once more right away: 32 SG with a budget of 30.
     */
    @Test
    fun `budget - SG display comes later - bidding on through the message does not exceed the budget`() {
        assertEquals(listOf(16), budgetLauf(30, durchMeldung = false), "wait for the message")
        assertEquals(listOf(16), budgetLauf(30, durchMeldung = true), "bid on through the message")
        // If two bids fit into the budget, two come (the lock does not hold anything up permanently).
        assertEquals(listOf(16, 16), budgetLauf(40, durchMeldung = true), "budget 40: two bids")
    }

    // ---------------- 3. "Bidding successful" in the Bid window: never the X ----------------

    /**
     * A Bid window with "Bidding successful" over it, without the bot wanting to bid right now (e.g. after pause → resume):
     * the window is closing by itself. Earlier the bot tapped the X at the top on a steady image of it ("no bid
     * open") – if the window closes at that moment, the tap hits the "+" underneath. Now: just wait (limited).
     */
    @Test
    fun `Bidding successful in the Bid window without our own bid - never the X, stop after 30 s`() {
        var jetzt = start
        val fenster = Bildschirm(
            Ort.BIETEN, aktuellesGebot = 15, meldung = Meldung(1224, yOben = 540, yUnten = 690),
            knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to Punkt(2105, 108)),
        )
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), { fenster })
        val tipps = ArrayList<String>()
        var e: Ergebnis = Ergebnis.Pausiert
        for (i in 1..300) {
            e = bot.schritt(jetzt)
            if (e is Ergebnis.Handeln) tipps += e.aktion.grund
            if (e is Ergebnis.Fertig) break
            jetzt = jetzt.plusMillis(380)
        }
        assertTrue(tipps.isEmpty(), "tap nothing while \"Bidding successful\" lies over it: $tipps")
        assertEquals(Ergebnis.Fertig(StoppGrund.FENSTER_KLEMMT), e, "never wait forever")
    }

    // ---------------- 4. Price of the row not readable: "Current Bid" the same twice ----------------

    /**
     * Price in the row not readable, game filter checked up to 20 – then there is no row the bot can compare "Current
     * Bid" with. Here the price has meanwhile risen to 120, and on the first steady image a digit got lost ("12").
     * Earlier the bot bid right away (the game then bids 121 – far above 21). Now "Current Bid" must be read the same
     * on two steady images in a row.
     */
    @Test
    fun `price of the row not readable - Current Bid must be read the same twice`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        spiel.preisSprungImFenster = 105 // the Bid window shows 120
        var bilder = 0
        spiel.currentBidAnzeige = { p ->
            bilder++
            when (bilder) {
                1 -> null // first image: not readable yet
                2 -> 12 // a digit got lost
                else -> p
            }
        }
        val leser = BildschirmLeser {
            val b = spiel.lies()
            if (b.ort == Ort.MARKT) b.copy(zeilen = b.zeilen.map { it.copy(preis = null) }) else b
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser, protokoll = { log += it })
        laufen(bot, spiel, 120) { spiel.gebotePreise.isNotEmpty() }
        assertTrue(spiel.gebotePreise.isEmpty(), "never bid above max price+1: ${spiel.gebotePreise}\n" + log.joinToString("\n"))
        assertTrue(spiel.geoeffnet.isNotEmpty(), "the row was opened")
    }

    // ---------------- 5a. After the swipe an image without rows ----------------

    /**
     * After a swipe two images show the bidding list without a single row (loading more), and the list slides another
     * 200 pixels in that time. Earlier two empty images counted as "list stands still" – the check after the swipe
     * was skipped, and a copy was skipped. Now: empty images do not count, afterwards the position counts as uncertain.
     */
    @Test
    fun `empty images after the swipe - the check stays, no copy is skipped`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        for (i in 1..30) spiel.zeile("C-", 15, 10_000L + i, i, i, i, zeichen = Zeichen.UHR)
        var grosse = 0
        var leer = 0
        val leser = BildschirmLeser {
            val b = spiel.lies()
            if (leer > 0 && b.ort == Ort.MARKT) {
                leer--
                b.copy(zeilen = emptyList())
            } else {
                b
            }
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser, protokoll = { log += it })
        val gesehen = HashSet<List<Int?>>()
        laufen(bot, spiel, 900, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && (e.aktion.grund.startsWith("further down") || e.aktion.grund.contains("round over"))) {
                spiel.letztesBild!!.zeilen.forEach { gesehen += it.werte }
            }
            if (e is Ergebnis.Handeln && e.aktion is Aktion.Wischen) {
                // Only on the 3rd big swipe: the list slides another 2 × 100 pixels while 2 images show no row.
                val gross = e.aktion.grund.startsWith("further down")
                if (gross) grosse++
                if (gross && grosse == 3) {
                    spiel.nachrutschBilder = 2
                    spiel.rutschPx = 100
                    leer = 2
                } else {
                    spiel.nachrutschBilder = 0
                }
            }
        }) { spiel.handlungen.any { it.contains("round over") } }
        val fehlt = (1..30).map { listOf(it, it, it) }.filter { it !in gesehen }
        assertTrue(
            fehlt.isEmpty(),
            "never seen: $fehlt\n" + log.filter { it.startsWith("Swipe:") || it.startsWith("Swiped") || it.startsWith("List") }.joinToString("\n"),
        )
    }

    // ---------------- 5b. Auction about to end: not the X ----------------

    /**
     * The copy ends in 3 seconds; in the Bid window the price is too high (the bot wants to close it). When the auction ends,
     * the window may close by itself – a tap on the X at the top would then hit the "+". Now the bot waits until
     * [Takt.fensterZuWartenMs] after the end, only then it closes (limited, no stop needed).
     */
    @Test
    fun `auction about to end - do not close the Bid window with the X at the top`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 10_000, 9, 9, 9, zeichen = Zeichen.UHR) // so that the list is not empty
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 60) // set filter and sort order
        spiel.preisSprungImFenster = 10 // 25 in the Bid window – too expensive
        spiel.zeile("C-", 15, 3, 1, 1, 1)
        val ende = spiel.jetzt.plusSeconds(3)
        val xUm = ArrayList<Instant>()
        val e = laufen(bot, spiel, 200, jeSchritt = { x ->
            if (x is Ergebnis.Handeln && x.aktion.grund.startsWith("close Bid window")) xUm += spiel.jetzt
        }) { xUm.isNotEmpty() && spiel.ort == Ort.MARKT }
        assertTrue(spiel.geoeffnet.any { it.first == listOf(1, 1, 1) }, "the row was opened\n" + log.joinToString("\n"))
        assertTrue(spiel.gebotePreise.isEmpty())
        assertTrue(xUm.isNotEmpty(), "closed afterwards after all (do not wait forever)\n" + log.joinToString("\n"))
        assertTrue(
            xUm.all { !it.isBefore(ende.plusMillis(Takt().fensterZuWartenMs)) },
            "X only ${Takt().fensterZuWartenMs / 1000} s after the end: X at $xUm, end $ende",
        )
        assertTrue(e !is Ergebnis.Fertig, "no stop: $e")
    }
}
