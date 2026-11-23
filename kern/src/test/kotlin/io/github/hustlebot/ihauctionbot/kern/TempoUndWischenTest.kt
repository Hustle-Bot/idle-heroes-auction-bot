package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 11 (field test 1.20): "corrects nicely, but scrolls clearly too far", "the bot should just keep bidding,
 * no matter which message comes – except the error message (-7). And just remember where the individual buttons are."
 *
 * A) After every swipe the bot measures how far the list really moved, and then drags shorter/longer to match.
 * B) Messages (except -7) do not hold up bidding: rows from the last clear image, the Bid button at the remembered
 *    position. Never at the top into the purchase bar.
 * C) If the price of a row is not readable, but the max price is set and checked in the game's filter, the row is
 *    not skipped – the bot only bids if "Current Bid" in the Bid window is readable and small enough.
 */
class TempoUndWischenTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 12, 9, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)

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

    /** Long list in which I am in front everywhere (clock) – the bot only swipes through. */
    private fun langeListe(spiel: SpielAttrappe, n: Int) {
        for (i in 1..n) spiel.zeile("C-", 10 + i % 10, 100L + i, i, i, i, zeichen = Zeichen.UHR)
    }

    // ---------------- A) Measuring swipes ----------------

    @Test
    fun `A - list moves 1,2 to 1,4 times as far - exactly 5 new copies from the third swipe at the latest`() {
        for (faktor in listOf(1.2, 1.3, 1.4)) {
            val spiel = SpielAttrappe(start)
            spiel.pixelGenau = true
            spiel.wischFaktor = faktor
            langeListe(spiel, 42)
            val log = ArrayList<String>()
            val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
            val gesehen = HashSet<List<Int?>>()
            val wischer = ArrayList<Aktion.Wischen>()
            laufen(bot, spiel, 900, jeSchritt = { e ->
                if (e is Ergebnis.Handeln && (e.aktion.grund.startsWith("further down") || e.aktion.grund.contains("round over"))) {
                    spiel.letztesBild!!.zeilen.forEach { gesehen += it.werte }
                }
                if (e is Ergebnis.Handeln && e.aktion is Aktion.Wischen) wischer += e.aktion as Aktion.Wischen
            }) { spiel.handlungen.any { it.contains("round over") } }
            assertEquals((1..42).map { listOf(it, it, it) }.toSet(), gesehen, "$faktor: all 42 copies fully seen")
            assertEquals(faktor, bot.wischVerhaeltnis, 0.03, "$faktor: ratio learned")
            val gewischt = log.filter { it.startsWith("Swiped:") && !it.contains("end of list") }
            // The first big swipe only measures (2 rows), the second fine-tunes the ratio – after that every one fits.
            assertTrue(gewischt.size >= 6, gewischt.joinToString("\n"))
            assertTrue(
                gewischt.drop(2).all { it.startsWith("Swiped: 5 new copies") },
                "$faktor:\n" + gewischt.joinToString("\n"),
            )
            // Nov 12 (field test 1.33): the big swipe leaves the last copy seen at the top (measure exactly),
            // then ONE short push – from the third big swipe on, never look back.
            val gross = wischer.withIndex().filter { it.value.grund.startsWith("further down") }.map { it.index }
            assertTrue(gross.size >= 6, "$faktor: ${gross.size} big swipes")
            val bericht = "$faktor: " + wischer.map { "${it.grund} ${it.von.y - it.nach.y}" }.joinToString("\n")
            assertTrue(wischer.drop(gross[2]).none { it.grund.startsWith("push the list back") }, bericht)
            assertTrue(nachschiebenJeSeite(wischer).drop(2).all { it <= 1 }, bericht)
            // Log for the user: what was wanted and what the list really did.
            assertTrue(log.any { it.startsWith("Swipe: wanted") && it.contains("moved") }, log.joinToString("\n"))
        }
    }

    /**
     * Nov 12 (review: "safety first"): the app's start value counts as ONE measurement – the first big swipe
     * of every run is a short measuring swipe (checks the start value), after that at most one push on the first
     * page, and from then on every swipe fits exactly (before: no measuring at all – an outdated start value was never noticed).
     */
    @Test
    fun `A - ratio passed in by the app - first a short measuring swipe, after that every swipe fits`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.3
        langeListe(spiel, 26)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, wischVerhaeltnisStart = 1.3)
        val wischer = ArrayList<Aktion.Wischen>()
        laufen(bot, spiel, 400, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && e.aktion is Aktion.Wischen) wischer += e.aktion as Aktion.Wischen
        }) { spiel.handlungen.any { it.contains("round over") } }
        val text = wischer.map { "${it.grund} ${it.von.y - it.nach.y}" }.joinToString("\n")
        val weiter = wischer.filter { it.grund.startsWith("further down") }
        assertEquals("further down (measure first)", weiter.first().grund, text)
        assertEquals(278, weiter.first().let { it.von.y - it.nach.y }, "2 rows: 362 / 1.3 = 278 pixels of finger travel")
        assertTrue(weiter.size >= 4, text)
        // Nov 12 (field test 1.33): the last copy seen stays at the top – (905 − 221) / 1.3 ≈ 526 pixels.
        assertTrue(weiter.drop(1).all { it.grund == "further down" && it.von.y - it.nach.y in 524..528 }, "(905 − 221) / 1.3 ≈ 526:\n$text")
        // Every page: exactly one short push, never look back.
        assertTrue(nachschiebenJeSeite(wischer).all { it <= 1 }, text)
        assertTrue(wischer.none { it.grund.startsWith("push the list back") }, text)
        val gewischt = log.filter { it.startsWith("Swiped:") && !it.contains("end of list") }
        assertTrue(gewischt.size >= 4 && gewischt.all { it.startsWith("Swiped: 5 new copies") }, log.joinToString("\n"))
    }

    /** Pages after the big swipes: how often each page was pushed on (without the first page with the measuring swipe). */
    private fun nachschiebenJeSeite(wischer: List<Aktion.Wischen>): List<Int> {
        val seiten = ArrayList<Int>()
        for (w in wischer) {
            if (w.grund.startsWith("further down")) seiten += 0 else if (seiten.isNotEmpty()) seiten[seiten.size - 1]++
        }
        return seiten.drop(1)
    }

    @Test
    fun `A - field test 1,31 - list visibly slides on after every swipe - look back and push once, nothing missing`() {
        // Nov 12 (field test 1.31, report 14:44): after every big swipe "list is still sliding" (3–4 images), then
        // push back, push on and often back once more – mostly only 4 new copies, 4–5 s per page.
        // Measured: long swipes (0.9 s) move the list 1.14 times as far as dragged, short ones (0.4 s) about 1.3 times.
        // Simulated: 1.05 times while dragging (short 1.0 times) and then visibly 3 × 25 pixels of sliding on.
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.05
        spiel.wischFaktorKurz = 1.0
        spiel.nachrutschBilder = 3
        spiel.rutschPx = 25
        spiel.rutschMinZug = 150
        langeListe(spiel, 82)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, wischVerhaeltnisStart = 1.14)
        val gesehen = HashSet<List<Int?>>()
        val wischer = ArrayList<Aktion.Wischen>()
        laufen(bot, spiel, 3000, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && (e.aktion.grund.startsWith("further down") || e.aktion.grund.contains("round over"))) {
                spiel.letztesBild!!.zeilen.forEach { gesehen += it.werte }
            }
            if (e is Ergebnis.Handeln && e.aktion is Aktion.Wischen) wischer += e.aktion as Aktion.Wischen
        }) { spiel.handlungen.any { it.contains("round over") } }
        val nach = nachschiebenJeSeite(wischer)
        val gewischt = log.filter { it.startsWith("Swiped:") && !it.contains("end of list") }
        val bericht = "pushes per page: $nach · " + gewischt.count { it.startsWith("Swiped: 5 new") } + " of " +
            gewischt.size + " pages with 5 new copies\n" + wischer.joinToString("\n") { "${it.grund} ${it.von.y - it.nach.y}" }
        assertEquals((1..82).map { listOf(it, it, it) }.toSet(), gesehen, "all 82 copies fully seen\n$bericht")
        // Nov 12 (review of 1.31): after sliding on the bot always looks back (better safe than sorry). With the
        // own ratio of the short swipes ONE push is enough after that: at most 2 swipes per page (before: 3).
        val danach = nach.drop(4).dropLast(1)
        assertTrue(danach.size >= 8 && danach.all { it <= 2 }, bericht)
        assertTrue(gewischt.drop(4).count { it.startsWith("Swiped: 5 new") } >= gewischt.drop(4).size * 0.8, bericht)
    }

    @Test
    fun `A - the app's start value is limited to sensible bounds`() {
        val leer = BildschirmLeser { Bildschirm.UNBEKANNT }
        assertEquals(1.0, AuktionsBot(listOf(cMinus), StoppBedingung(), leer).wischVerhaeltnis, 0.0001)
        assertEquals(WISCH_VERHAELTNIS_MAX, AuktionsBot(listOf(cMinus), StoppBedingung(), leer, wischVerhaeltnisStart = 9.0).wischVerhaeltnis, 0.0001)
        assertEquals(WISCH_VERHAELTNIS_MIN, AuktionsBot(listOf(cMinus), StoppBedingung(), leer, wischVerhaeltnisStart = 0.1).wischVerhaeltnis, 0.0001)
    }

    // ---------------- Budget used up (Nov 12, field test 1.31) ----------------

    @Test
    fun `budget used up - say it once, do not page on, check every 25 s, keep bidding with returned SG`() {
        // Field test 1.31: budget 2500, 2473 SG were tied up in bids, the next copy cost ≥ 43 SG. The bot rightly did not
        // bid any more, but did not say so and kept paging for minutes – the user thought it was stuck.
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.mitBestaetigen = false
        // Every copy costs 16, all end within the next 5 minutes. Budget 45: after 2 bids 13 SG are still free – that
        // is not enough for any copy any more, but for the lowest price of the rule (11).
        // Nov 23: the copies end a bit later (from 3.5 minutes on), so our bid is outbid below while its auction is still running
        // (before, the fake game gave back the SG of an auction that was already over – and counted as bought).
        for (i in 1..12) spiel.zeile("C-", 15, 200L + i * 15, i, i, i)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 45), spiel, protokoll = { log += it })
        laufen(bot, spiel, 600) { log.any { it.startsWith("SG budget used up") } }
        val gebote = spiel.gebotePreise.toList()
        assertEquals(listOf(16, 16), gebote, log.joinToString("\n"))
        assertTrue(bot.budgetVerplant, "hint at the IH button (budget)")
        assertEquals(16, bot.geldFehltFuerSg)
        assertTrue(log.single { it.startsWith("SG budget used up") }.contains("32 of 45 SG are tied up in bids"), log.joinToString("\n"))

        // For 2 minutes: do not page on any more, only check at the top every ~25 s (refresh), no bid.
        val handlungenVorher = spiel.handlungen.size
        val ab = spiel.jetzt
        laufen(bot, spiel, 600) { java.time.Duration.between(ab, spiel.jetzt).toSeconds() >= 120 }
        val danach = spiel.handlungen.drop(handlungenVorher)
        assertTrue(danach.none { it.startsWith("further down") }, danach.toString())
        assertTrue(danach.count { it.contains("refresh") } in 3..6, danach.toString())
        assertEquals(gebote, spiel.gebotePreise, "no new bid")
        assertEquals(1, log.count { it.startsWith("SG budget used up") }, "said only once")

        // One of our own bids gets outbid → the SG come back → the bot keeps bidding.
        spiel.ueberbiete(spiel.listen["C-"]!!.first { it.zeichen == Zeichen.UHR })
        laufen(bot, spiel, 300) { spiel.gebotePreise.size > gebote.size }
        assertTrue(log.any { it.startsWith("SG back") }, log.takeLast(20).joinToString("\n"))
        assertTrue(spiel.gebotePreise.size > gebote.size, "bid again")
        assertTrue(spiel.obenGetippt.isEmpty())
    }

    @Test
    fun `budget used up with two rules - say it once, do not page on for the other rule either`() {
        // Nov 12 (review): with 2 rules the bot switched the rule after 2 empty rounds, forgot the waiting for money,
        // paged through the whole other list and reported it again – the 70 minutes never ran out.
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.mitBestaetigen = false
        for (i in 1..12) spiel.zeile("C-", 15, 60L + i * 15, i, i, i)
        for (i in 1..12) spiel.zeile("B-", 40, 60L + i * 15, 100 + i, 100 + i, 100 + i)
        val log = ArrayList<String>()
        val regeln = listOf(cMinus, Suchregel("B-", 10, 60, maxRestzeitMin = 5))
        val bot = AuktionsBot(regeln, StoppBedingung(sgBudget = 45), spiel, protokoll = { log += it })
        laufen(bot, spiel, 600) { log.any { it.startsWith("SG budget used up") } }
        val handlungenVorher = spiel.handlungen.size
        val ab = spiel.jetzt
        laufen(bot, spiel, 1500) { java.time.Duration.between(ab, spiel.jetzt).toSeconds() >= 240 }
        val danach = spiel.handlungen.drop(handlungenVorher)
        assertTrue(danach.any { it.startsWith("open filter for B-") }, "also looks at the other rule: $danach")
        assertTrue(danach.none { it.startsWith("further down") }, danach.toString())
        assertEquals(1, log.count { it.startsWith("SG budget used up") }, log.joinToString("\n"))
        assertEquals(listOf(16, 16), spiel.gebotePreise)
    }

    @Test
    fun `budget used up and none of our own bids in front any more - stop with a reason, lock the phone`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.mitBestaetigen = false
        for (i in 1..6) spiel.zeile("C-", 15, 60L + i * 15, i, i, i)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(sgBudget = 45), spiel, protokoll = { log += it })
        laufen(bot, spiel, 600) { log.any { it.startsWith("SG budget used up") } }
        // All auctions end (won) – nothing can come back any more.
        spiel.jetzt = spiel.jetzt.plusSeconds(300)
        val ab = spiel.jetzt
        val e = laufen(bot, spiel, 600)
        // Nov 12 (review): only when that stays so for 60 s (a misread image ends nothing).
        assertTrue(java.time.Duration.between(ab, spiel.jetzt).toSeconds() >= 60, "${java.time.Duration.between(ab, spiel.jetzt)}")
        assertEquals(Ergebnis.Fertig(StoppGrund.BUDGET_ERREICHT), e, log.takeLast(15).joinToString("\n"))
        assertTrue(log.any { it.contains("None of our own bids in front") }, log.takeLast(15).joinToString("\n"))
        assertTrue(StoppGrund.BUDGET_ERREICHT.sperren)
    }

    // ---------------- Field test 1.33 (Nov 12) ----------------

    @Test
    fun `field test 1,33 - 18 SG free, every copy costs 44 to 58 - wait right away on the first image, do not page`() {
        // Report 17:12: 2482 of 2500 SG were tied up in bids, every visible copy cost 50–58. The bot still kept paging
        // (it only noticed at the end of a whole round – and the rounds were long).
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.mitBestaetigen = false
        spiel.zeile("B-", 50, 70, 1, 1, 1) // these two are still affordable (51 each)
        spiel.zeile("B-", 50, 80, 2, 2, 2)
        for (i in 3..15) spiel.zeile("B-", 44 + (i * 3) % 15, 80L + i * 15, i, i, i)
        val log = ArrayList<String>()
        val bMinus = Suchregel("B-", 10, 58, maxRestzeitMin = 5)
        val bot = AuktionsBot(listOf(bMinus), StoppBedingung(sgBudget = 120), spiel, protokoll = { log += it })
        laufen(bot, spiel, 600) { java.time.Duration.between(start, spiel.jetzt).toSeconds() >= 120 }
        assertEquals(listOf(51, 51), spiel.gebotePreise, log.joinToString("\n") + "\n" + spiel.handlungen.joinToString("\n"))
        assertEquals(1, log.count { it.startsWith("SG budget used up: 102 of 120 SG are tied up in bids") }, log.joinToString("\n"))
        assertTrue(bot.budgetVerplant, "hint at the IH button")
        // At most the (short) measuring swipe that came before the message – never page on after that.
        val ab = log.indexOfFirst { it.startsWith("SG budget used up") }
        assertTrue(ab >= 0 && log.drop(ab).none { it.startsWith("Swiped") }, log.joinToString("\n"))
        assertTrue(spiel.handlungen.count { it.startsWith("further down") } <= 1, spiel.handlungen.toString())
    }

    @Test
    fun `field test 1,33 - a message over row 2 every few seconds - no waiting while checking, nothing missing`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.14
        langeListe(spiel, 42)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, wischVerhaeltnisStart = 1.14)
        val gesehen = HashSet<List<Int?>>()
        var pruefWarten = 0
        laufen(bot, spiel, 3000, jeSchritt = { e ->
            // Every 4 seconds a message comes for 2.5 seconds (as in the report 17:12).
            spiel.meldungImmer = java.time.Duration.between(start, spiel.jetzt).toMillis() % 4000 < 2500
            spiel.letztesBild?.zeilen?.forEach { z -> if (!z.verdeckt && z.werte.all { it != null }) gesehen += z.werte }
            if (e is Ergebnis.Warten && e.grund.contains("waiting briefly to check")) pruefWarten++
        }) { spiel.handlungen.any { it.contains("round over") } }
        assertEquals((1..42).map { listOf(it, it, it) }.toSet(), gesehen, "all 42 copies fully seen")
        assertEquals(0, pruefWarten, "did not wait for the message while checking after the swipe")
    }

    @Test
    fun `field test 1,33 - one swipe slides 250 pixels too far - ratio stays, nothing missing`() {
        // Report 17:12: "wanted 905, moved 1283" – such outliers shifted the ratio (0.93 … 1.24).
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.14
        langeListe(spiel, 42)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, wischVerhaeltnisStart = 1.14)
        val gesehen = HashSet<List<Int?>>()
        var grosse = 0
        laufen(bot, spiel, 3000, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && e.aktion.grund == "further down" && ++grosse == 4) spiel.naechsterWischExtra = 250
            spiel.letztesBild?.zeilen?.forEach { z -> if (z.werte.all { it != null }) gesehen += z.werte }
        }) { spiel.handlungen.any { it.contains("round over") } }
        assertEquals((1..42).map { listOf(it, it, it) }.toSet(), gesehen, "all 42 copies seen")
        assertTrue(log.any { it.contains("does not fit the ratio") }, log.filter { it.startsWith("Swipe:") }.joinToString("\n"))
        assertEquals(1.14, bot.wischVerhaeltnis, 0.03, log.filter { it.startsWith("Swipe:") }.joinToString("\n"))
    }

    // ---------------- B) Keep bidding despite a message ----------------

    @Test
    fun `B - -7 is recognized even if Bidding successful came with every bid before`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.erfolgsMeldungMs = 3000
        spiel.spielLimit = 3
        for (i in 1..6) spiel.zeile("C-", 10 + i, 60L + i * 20, i, i, i)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 400) { bot.gebote.limitGemeldet }
        assertTrue(bot.gebote.limitGemeldet, log.joinToString("\n"))
        assertEquals(3, spiel.gebotePreise.size, "${spiel.gebotePreise}")
        assertTrue(spiel.gebotePreise.all { it <= 21 })
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
    }

    @Test
    fun `B - message over the Bid button - bot taps at the remembered position, never at the top`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        spiel.zeile("C-", 16, 300, 2, 2, 2) // only bid once the first copy is bid on (ends later)
        spiel.zeile("C-", 17, 280, 3, 3, 3)
        // From the second Bid window on, a foreign message lies exactly over the Bid button (as on the reference screenshots):
        // "Current Bid" stays readable, the button is not recognized.
        val band = Meldung(1542, yOben = 600, yUnten = 760)
        val leser = BildschirmLeser {
            val b = spiel.lies()
            if (b.ort == Ort.BIETEN && spiel.geoeffnet.size >= 2) b.copy(meldung = band, knoepfe = b.knoepfe - Knopf.BIETEN) else b
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus.copy(maxRestzeitMin = 10)), StoppBedingung(), leser, protokoll = { log += it })
        val bidTipps = ArrayList<String>()
        laufen(bot, spiel, 300, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && e.aktion.grund.startsWith("Bid for")) bidTipps += e.aktion.grund
        }) { spiel.gebotePreise.size >= 3 }
        assertEquals(listOf(16, 17, 18), spiel.gebotePreise.sorted(), log.joinToString("\n"))
        assertEquals(Punkt(1641, 690), bot.bidKnopfGelernt)
        assertTrue(bidTipps.count { it.contains("learned position") } >= 2, bidTipps.toString())
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
    }

    @Test
    fun `B - Bid button under a message, position never seen - do not tap blindly`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        val band = Meldung(1542, yOben = 600, yUnten = 760)
        val leser = BildschirmLeser {
            val b = spiel.lies()
            if (b.ort == Ort.BIETEN) b.copy(meldung = band, knoepfe = b.knoepfe - Knopf.BIETEN) else b
        }
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser)
        val bidTipps = ArrayList<String>()
        laufen(bot, spiel, 120, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && e.aktion.grund.startsWith("Bid for")) bidTipps += e.aktion.grund
        })
        assertTrue(bidTipps.isEmpty(), bidTipps.toString())
        assertTrue(spiel.gebotePreise.isEmpty())
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
    }

    // ---------------- C) Price of the row not readable ----------------

    /** Reader with which no price in the bidding list is readable. */
    private fun ohnePreis(spiel: SpielAttrappe) = BildschirmLeser {
        val b = spiel.lies()
        if (b.ort == Ort.MARKT) b.copy(zeilen = b.zeilen.map { it.copy(preis = null) }) else b
    }

    @Test
    fun `C - price not readable, max price checked in the filter - bid anyway, never above max price+1`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        spiel.zeile("C-", 20, 90, 2, 2, 2)
        spiel.zeile("C-", 25, 120, 3, 3, 3) // too expensive – the game's filter (up to 20) does not show it at all
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), ohnePreis(spiel))
        laufen(bot, spiel, 300) { spiel.gebotePreise.size >= 2 }
        assertEquals(setOf(16, 21), spiel.gebotePreise.toSet())
        assertTrue(spiel.gebotePreise.all { it <= 21 }, "never above max price+1")
        assertTrue(spiel.obenGetippt.isEmpty())
    }

    @Test
    fun `C - price not readable and price fields do not work - skip the row`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.eingabeGeht = false // the filter max price cannot be set → the game shows too expensive ones too
        spiel.zeile("C-", 15, 60, 1, 1, 1)
        spiel.zeile("C-", 26, 90, 2, 2, 2)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), ohnePreis(spiel))
        laufen(bot, spiel, 200)
        assertEquals(false, bot.preisfelderGehen)
        assertTrue(spiel.gebotePreise.isEmpty(), "never bid without a readable price and without a filter max price: ${spiel.gebotePreise}")
        assertTrue(spiel.geoeffnet.isEmpty(), "not even opened")
    }
}
