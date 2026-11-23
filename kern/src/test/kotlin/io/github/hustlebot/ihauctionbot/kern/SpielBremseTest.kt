package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 22 (review before publishing): the bot respects the game's rate limit ("Operated too frequently. Please retry in N
 * second(s).") and does not refresh the list every ~1.2 s when there is nothing to do. Bidding must still work.
 */
class SpielBremseTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 22, 21, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)

    /** "Operated too frequently" (width as on the real screenshots, no known position – covers no row). */
    private val zuOft = Meldung(1415)

    /** Lays a message over the fake game's images for a while (all screens). */
    class MitMeldung(val spiel: SpielAttrappe) : BildschirmLeser {
        var meldung: Meldung? = null
        var bis: Instant = Instant.MIN

        /** Nov 23: only on these screens (null = on every screen, like a real toast). */
        var nurAuf: Set<Ort>? = null

        /** Nov 23: the band covers the Bid button (as on the real images – then only the learned position is known). */
        var bidVerdeckt = false

        /** This many images of the bidding list show our own row (clock) with this price (a misread or a wrong bid). */
        var preisFalsch = 0
        var falscherPreis = 30

        fun zeige(m: Meldung, ms: Long) {
            meldung = m
            bis = spiel.jetzt.plusMillis(ms)
        }

        override fun lies(): Bildschirm {
            var b = spiel.lies()
            val m = meldung
            if (m != null && spiel.jetzt.isBefore(bis) && b.ort != Ort.LADEN && b.ort != Ort.UNBEKANNT && nurAuf?.contains(b.ort) != false) {
                // Like the real band it may cover the Bid button (Nov 11: "Current Bid" above stays readable).
                b = b.copy(meldung = m, knoepfe = if (bidVerdeckt && b.ort == Ort.BIETEN) b.knoepfe - Knopf.BIETEN else b.knoepfe)
            }
            if (preisFalsch > 0 && b.ort == Ort.MARKT && b.zeilen.any { it.zeichen == Zeichen.UHR }) {
                preisFalsch--
                b = b.copy(zeilen = b.zeilen.map { if (it.zeichen == Zeichen.UHR) it.copy(preis = falscherPreis) else it })
            }
            return b
        }
    }

    /** One step: carry out the action in the fake game, move the clock. Returns the result. */
    private fun schritt(bot: AuktionsBot, spiel: SpielAttrappe, aktionen: MutableList<Pair<Instant, String>>): Ergebnis {
        val e = bot.schritt(spiel.jetzt)
        when (e) {
            is Ergebnis.Handeln -> {
                aktionen += spiel.jetzt to e.aktion.grund
                spiel.ausfuehren(e.aktion)
                val a = e.aktion
                if (a is Aktion.Eingeben || a is Aktion.PasswortEingeben || (a is Aktion.Folge && a.hatEingaben)) bot.eingabeErgebnis(spiel.letzteEingabeOk)
                spiel.jetzt = spiel.jetzt.plusMillis(e.naechsterSchrittMs)
            }
            is Ergebnis.Warten -> spiel.jetzt = spiel.jetzt.plusMillis(e.ms)
            is Ergebnis.Unsicher -> spiel.jetzt = spiel.jetzt.plusMillis(e.ms)
            is Ergebnis.Fertig -> Unit
            Ergebnis.Pausiert -> spiel.jetzt = spiel.jetzt.plusSeconds(1)
        }
        return e
    }

    /** Runs until filter and sort order are set and the bot is in the sorted list (ready to bid). */
    private fun bisBereit(bot: AuktionsBot, spiel: SpielAttrappe, aktionen: MutableList<Pair<Instant, String>>) {
        repeat(200) {
            if (bot.filterStufe == "C-" && spiel.sortierung == Sortierung.RANG && spiel.ort == Ort.MARKT) return
            schritt(bot, spiel, aktionen)
        }
        error("filter/sort order not set: ${spiel.handlungen}")
    }

    @Test
    fun `operated too frequently over the list - no tap for 5 s, then the bot bids as before`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        bisBereit(bot, spiel, aktionen)
        val t0 = spiel.jetzt
        huelle.zeige(zuOft, 3000)
        repeat(100) { if (spiel.gebotePreise.isEmpty()) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(16), spiel.gebotePreise, "bids on after the pause")
        val waehrend = aktionen.filter { !it.first.isBefore(t0) && it.first.isBefore(t0.plusSeconds(5)) }
        assertTrue(waehrend.isEmpty(), "taps during the pause: $waehrend")
        assertTrue(log.contains("Game says: operated too frequently – waiting 5 s (1 of 5 in a row)"), log.joinToString("\n"))
        assertEquals(1, bot.zuOftMeldungen)
        assertEquals(0, bot.zuOftInFolge, "a bid went through – the count starts over")
    }

    @Test
    fun `operated too frequently again and again - pauses 5, 10, 20, 40 s, the 5th time in a row stops`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 3000, 1, 1, 1) // nothing to bid (ends much later than 5 minutes)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        var e: Ergebnis = Ergebnis.Pausiert
        val pausen = ArrayList<Pair<Instant, Instant>>()
        for (i in 0 until 3000) {
            val vorher = aktionen.size
            val t = spiel.jetzt
            e = schritt(bot, spiel, aktionen)
            if (e is Ergebnis.Fertig) break
            bot.zuOftPauseBis?.let { bis -> if (pausen.lastOrNull()?.second != bis) pausen += t to bis }
            // The game refuses the first tap after a pause: the message comes right after it.
            val getippt = aktionen.size > vorher
            if ((pausen.isEmpty() && aktionen.size > 3) || (getippt && pausen.isNotEmpty() && bot.zuOftPauseBis == null)) {
                huelle.zeige(zuOft, 3000)
            }
        }
        assertEquals(Ergebnis.Fertig(StoppGrund.ZU_OFT), e, log.takeLast(20).joinToString("\n"))
        for ((n, s) in listOf(5, 10, 20, 40).withIndex()) {
            assertTrue(log.contains("Game says: operated too frequently – waiting $s s (${n + 1} of 5 in a row)"), log.joinToString("\n"))
        }
        assertTrue(log.any { it.contains("5 times in a row despite pauses, stopping") })
        // Never a tap while a pause was running.
        for ((t, grund) in aktionen) {
            assertTrue(pausen.none { (von, bis) -> !t.isBefore(von) && t.isBefore(bis) }, "tap during a pause at $t: $grund")
        }
    }

    @Test
    fun `operated too frequently - calm for 2 minutes starts over at 5 s, a bid starts the count over`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 3000, 1, 1, 1)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        bisBereit(bot, spiel, aktionen)
        fun eine(): String {
            huelle.zeige(zuOft, 3000)
            val bis = spiel.jetzt.plusSeconds(4)
            while (spiel.jetzt.isBefore(bis)) schritt(bot, spiel, aktionen)
            return log.last { it.startsWith("Game says") }
        }
        assertEquals("Game says: operated too frequently – waiting 5 s (1 of 5 in a row)", eine())
        // Again 10 s after the pause: twice as long.
        val bis1 = spiel.jetzt.plusSeconds(12)
        while (spiel.jetzt.isBefore(bis1)) schritt(bot, spiel, aktionen)
        assertEquals("Game says: operated too frequently – waiting 10 s (2 of 5 in a row)", eine())
        // 2.5 minutes calm: starts over.
        val bis2 = spiel.jetzt.plusSeconds(160)
        while (spiel.jetzt.isBefore(bis2)) schritt(bot, spiel, aktionen)
        assertEquals("Game says: operated too frequently – waiting 5 s (1 of 5 in a row)", eine())
        // A copy to bid on appears: the bot bids → the count starts over (the pause length does not).
        val bis3 = spiel.jetzt.plusSeconds(8)
        while (spiel.jetzt.isBefore(bis3)) schritt(bot, spiel, aktionen)
        spiel.zeile("C-", 12, 120, 2, 2, 2)
        repeat(200) { if (spiel.gebotePreise.isEmpty()) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(13), spiel.gebotePreise)
        assertEquals(0, bot.zuOftInFolge)
        assertEquals("Game says: operated too frequently – waiting 10 s (1 of 5 in a row)", eine())
    }

    @Test
    fun `unknown message over the list - no tap for 3 s, then the bot bids through it`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        bisBereit(bot, spiel, aktionen)
        val t0 = spiel.jetzt
        huelle.zeige(Meldung(1100), 20_000) // stays longer than the pause
        huelle.nurAuf = setOf(Ort.MARKT) // Nov 23: over the Bid window it would hold the Bid back (see the tests below)
        repeat(100) { if (spiel.gebotePreise.isEmpty()) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(16), spiel.gebotePreise)
        val erste = aktionen.first { !it.first.isBefore(t0) }
        val ms = Duration.between(t0, erste.first).toMillis()
        assertTrue(ms in 3000..4000, "first tap ${ms} ms after the message: ${erste.second}")
        assertTrue(log.contains("Unknown message over the list (width 1100) – not tapping through it, waiting 3 s"), log.joinToString("\n"))
        assertEquals(0, bot.zuOftMeldungen, "a single unknown message is not \"too frequently\"")
    }

    @Test
    fun `Bidding successful and Bid Failure - the bot taps the row through them right away, Bid only once the band is gone`() {
        // Nov 23 (review round 3): over the list as before (no pause, the row is tapped through the band); in the Bid window
        // the band lies over the Bid button – Bid only after it is gone (a real toast lasts 2–3 s).
        for (m in listOf(Meldung(1224), Meldung(1542), Meldung(1530, yOben = 510, yUnten = 706))) {
            val spiel = SpielAttrappe(start)
            spiel.zeile("C-", 15, 200, 1, 1, 1)
            val huelle = MitMeldung(spiel)
            val aktionen = ArrayList<Pair<Instant, String>>()
            val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle)
            bisBereit(bot, spiel, aktionen)
            huelle.zeige(m, 2500)
            val e = schritt(bot, spiel, aktionen)
            assertTrue(e is Ergebnis.Handeln && e.aktion.grund.startsWith("bid C-"), "$m: $e")
            repeat(100) { if (spiel.gebotePreise.isEmpty()) schritt(bot, spiel, aktionen) }
            assertEquals(listOf(16), spiel.gebotePreise, "$m")
            val bid = aktionen.single { it.second.startsWith("Bid for") }
            assertTrue(!bid.first.isBefore(huelle.bis), "$m: Bid only after the band was gone (${huelle.bis}): $bid")
            assertEquals(null, bot.zuOftPauseBis)
        }
    }

    // ---------------- Nov 23 (review): unknown messages in the Bid and confirm window ----------------

    /** Bids once without any message (the bot learns where the Bid button is), then the next row is ready to bid. */
    private fun ersterBidGelernt(bot: AuktionsBot, spiel: SpielAttrappe, aktionen: MutableList<Pair<Instant, String>>) {
        bisBereit(bot, spiel, aktionen)
        repeat(200) { if (spiel.gebotePreise.isEmpty() || spiel.ort != Ort.MARKT) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(16), spiel.gebotePreise)
        assertNotNull(bot.bidKnopfGelernt)
    }

    @Test
    fun `unknown message over the Bid window - pause, never Bid under it (not even at the learned position), Bid once it is gone`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        spiel.zeile("C-", 17, 210, 2, 2, 2)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        ersterBidGelernt(bot, spiel, aktionen)
        // From now on for 8 s: an unknown band (1000 px – not "-7", not "Bidding successful", not "Bid Failure") over every
        // Bid window, exactly over the Bid button.
        huelle.nurAuf = setOf(Ort.BIETEN)
        huelle.bidVerdeckt = true
        huelle.zeige(Meldung(1000, yOben = 537, yUnten = 681), 8000)
        repeat(300) { if (spiel.gebotePreise.size < 2) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(16, 18), spiel.gebotePreise, log.takeLast(20).joinToString("\n"))
        val bid = aktionen.last { it.second.startsWith("Bid for 18") }
        assertTrue(!bid.first.isBefore(huelle.bis), "Bid only after the message was gone (${huelle.bis}): $bid")
        assertTrue(aktionen.none { it.second.contains("learned position") }, aktionen.toString())
        assertTrue(log.contains("Unknown message over the Bid window (width 1000) – not tapping through it, waiting 3 s"), log.joinToString("\n"))
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
    }

    @Test
    fun `unknown message stays over the Bid window - never Bid under it, closed with the X after a while, no stop`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = false
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        spiel.zeile("C-", 17, 210, 2, 2, 2)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        ersterBidGelernt(bot, spiel, aktionen)
        huelle.nurAuf = setOf(Ort.BIETEN)
        huelle.bidVerdeckt = true
        huelle.zeige(Meldung(1000, yOben = 537, yUnten = 681), 600_000)
        val t0 = spiel.jetzt
        var e: Ergebnis = Ergebnis.Pausiert
        while (Duration.between(t0, spiel.jetzt).toSeconds() < 60 && e !is Ergebnis.Fertig) e = schritt(bot, spiel, aktionen)
        assertTrue(e !is Ergebnis.Fertig, "$e – ${log.takeLast(10)}")
        assertEquals(listOf(16), spiel.gebotePreise, "no Bid under the message")
        assertTrue(aktionen.any { it.second == "close Bid window (unknown message)" }, aktionen.toString())
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
        // Once the message is gone, the bot bids again (row 2 is locked for 60 s after the close).
        huelle.bis = spiel.jetzt
        repeat(400) { if (spiel.gebotePreise.size < 2) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(16, 18), spiel.gebotePreise)
    }

    @Test
    fun `unknown message over the confirm window - never Confirm under it, Confirm once it is gone`() {
        val spiel = SpielAttrappe(start)
        spiel.zeile("C-", 15, 200, 1, 1, 1)
        val huelle = MitMeldung(spiel)
        val log = ArrayList<String>()
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
        bisBereit(bot, spiel, aktionen)
        huelle.nurAuf = setOf(Ort.BESTAETIGEN)
        huelle.zeige(Meldung(1000, yOben = 537, yUnten = 681), 9000)
        repeat(300) { if (spiel.gebotePreise.isEmpty()) schritt(bot, spiel, aktionen) }
        assertEquals(listOf(16), spiel.gebotePreise, log.takeLast(20).joinToString("\n"))
        val confirm = aktionen.single { it.second.startsWith("Confirm") }
        assertTrue(!confirm.first.isBefore(huelle.bis), "Confirm only after the message was gone: $confirm")
        assertTrue(log.contains("Unknown message over the confirm window (width 1000) – not tapping through it, waiting 3 s"), log.joinToString("\n"))
        assertTrue(spiel.obenGetippt.isEmpty(), "${spiel.obenGetippt}")
    }

    @Test
    fun `known messages in the Bid window - Bid Failure and Bidding successful - never Bid under them, no pause, X if they stay`() {
        // Nov 23 (review round 3: "any two-line band counts as Bid Failure"): before, Bid was tapped at its learned position
        // under these bands. Now never – the bot waits; a band that stays closes the window with the X (no pause, no stop).
        for (m in listOf(Meldung(1542, yOben = 470, yUnten = 700), Meldung(1224, yOben = 537, yUnten = 681))) {
            val spiel = SpielAttrappe(start)
            spiel.mitBestaetigen = false
            spiel.zeile("C-", 15, 200, 1, 1, 1)
            spiel.zeile("C-", 17, 210, 2, 2, 2)
            val huelle = MitMeldung(spiel)
            val aktionen = ArrayList<Pair<Instant, String>>()
            val log = ArrayList<String>()
            val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
            ersterBidGelernt(bot, spiel, aktionen)
            huelle.nurAuf = setOf(Ort.BIETEN)
            huelle.bidVerdeckt = true
            huelle.zeige(m, 600_000)
            val t0 = spiel.jetzt
            var e: Ergebnis = Ergebnis.Pausiert
            while (Duration.between(t0, spiel.jetzt).toSeconds() < 40 && e !is Ergebnis.Fertig) e = schritt(bot, spiel, aktionen)
            assertTrue(e !is Ergebnis.Fertig, "$m: $e")
            assertEquals(listOf(16), spiel.gebotePreise, "$m: never Bid under the band: $aktionen")
            assertTrue(aktionen.none { it.second.contains("learned position") }, "$m: $aktionen")
            assertTrue(aktionen.any { it.second == "close Bid window (message over the Bid button)" }, "$m: $aktionen")
            assertTrue(log.any { it.startsWith("A message has been lying over the Bid button for 10 s") }, "$m: " + log.joinToString("\n"))
            assertEquals(0, bot.zuOftMeldungen, "$m")
            assertEquals(null, bot.zuOftPauseBis, "$m")
            assertTrue(spiel.obenGetippt.isEmpty(), "$m: ${spiel.obenGetippt}")
            // Band gone → the next Bid window is bid as usual (row 2 is locked for 60 s after the close).
            huelle.bis = spiel.jetzt
            repeat(400) { if (spiel.gebotePreise.size < 2) schritt(bot, spiel, aktionen) }
            assertEquals(listOf(16, 18), spiel.gebotePreise, "$m")
        }
    }

    @Test
    fun `at most one reload of the list every 5 s, always - filter Confirm, refresh after the filter, sort order, rule change`() {
        // Nov 23 (review: "no lasting minimum gap between reloads"): two rules, the list shows a new rank only after a refresh,
        // it starts sorted by price, a copy that fits ends soon (before: no gap at all then).
        val spiel = SpielAttrappe(start)
        spiel.filterBrauchtAktualisieren = true
        spiel.meinGebot("C-", 15, 40, 1, 1, 1)
        for (i in 2..4) spiel.zeile("C-", 14 + i, 3000L + i, i, i, i)
        for (i in 5..7) spiel.zeile("B-", 40 + i, 3000L + i, i, i, i)
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus, Suchregel("B-", 10, 60, maxRestzeitMin = 5)), StoppBedingung(), spiel)
        val bis = spiel.jetzt.plusSeconds(300)
        while (spiel.jetzt.isBefore(bis)) schritt(bot, spiel, aktionen)
        val neuLaden = listOf("confirm filter", "round over – refresh", "sort by Awakened Tier", "– refresh")
        val abfragen = aktionen.filter { (_, g) -> neuLaden.any { g.startsWith(it) || g.endsWith(it) } }
        assertTrue(abfragen.count { it.second.startsWith("confirm filter") } >= 3, abfragen.toString())
        assertTrue(abfragen.any { it.second.startsWith("sort by") } && abfragen.any { it.second.startsWith("list still shows") }, abfragen.toString())
        val zuDicht = abfragen.zipWithNext().filter { (a, b) -> Duration.between(a.first, b.first).toMillis() < 5000 }
        assertTrue(zuDicht.isEmpty(), "reloads less than 5 s apart: $zuDicht")
        assertEquals(0, bot.abfrageGebremst, "the flow waits by itself – the last safety net never has to step in")
    }

    @Test
    fun `nothing to bid - refresh at most every 5 s, growing to 15 s`() {
        val spiel = SpielAttrappe(start)
        for (i in 1..3) spiel.zeile("C-", 14 + i, 3000L + i, i, i, i) // end much later than 5 minutes
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        bisBereit(bot, spiel, aktionen)
        val bis = spiel.jetzt.plusSeconds(180)
        while (spiel.jetzt.isBefore(bis)) schritt(bot, spiel, aktionen)
        val refresh = aktionen.filter { it.second.contains("refresh") }.map { it.first }
        val abstaende = refresh.zipWithNext { a, b -> Duration.between(a, b).toMillis() }
        assertTrue(abstaende.size >= 8, "$abstaende")
        assertTrue(abstaende.all { it >= 5000 }, "never faster than every 5 s: $abstaende")
        assertTrue(abstaende.takeLast(3).all { it in 15_000..16_500 }, "then every 15 s: $abstaende")
        assertTrue(bot.leerlaufGewartet >= abstaende.size, "${bot.leerlaufGewartet}")
        assertTrue(spiel.gebotePreise.isEmpty())
    }

    @Test
    fun `a copy that fits ends within a minute - refresh every 5 s, not faster, and an outbid visible row is bid again at once`() {
        val spiel = SpielAttrappe(start)
        // Our bid is in front on a copy that ends in 50 s – the bot must see at once if it is outbid.
        val meine = spiel.meinGebot("C-", 15, 50, 1, 1, 1)
        for (i in 2..3) spiel.zeile("C-", 14 + i, 3000L + i, i, i, i)
        val aktionen = ArrayList<Pair<Instant, String>>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        bisBereit(bot, spiel, aktionen)
        val bis = spiel.jetzt.plusSeconds(30)
        while (spiel.jetzt.isBefore(bis)) schritt(bot, spiel, aktionen)
        val refresh = aktionen.filter { it.second.contains("refresh") }.map { it.first }
        val abstaende = refresh.zipWithNext { a, b -> Duration.between(a, b).toMillis() }
        assertTrue(abstaende.size >= 3, "$abstaende")
        // Nov 23 (review: "no lasting minimum gap"): before, every ~1.5–6 s; now at least 5 s, but not slower than needed.
        assertTrue(abstaende.all { it in 5000..7000 }, "$abstaende")
        assertEquals(0, bot.leerlaufGewartet, "this is not the idle wait (nothing to do)")
        // Someone outbids our bid while the bot waits for the next reload: the row is visible – it bids again at once.
        val t0 = spiel.jetzt
        spiel.ueberbiete(meine)
        while (spiel.gebotePreise.isEmpty() && Duration.between(t0, spiel.jetzt).toMillis() < 10_000) schritt(bot, spiel, aktionen)
        assertEquals(listOf(17), spiel.gebotePreise, aktionen.takeLast(10).toString())
        val ms = Duration.between(t0, aktionen.first { it.second.startsWith("re-bid") }.first).toMillis()
        assertTrue(ms < 1500, "re-bid only $ms ms after being outbid")
    }

    @Test
    fun `own bid shows a price above max price + 1 on two screenshots - stop, a single misread does not`() {
        for (falsch in listOf(1, Int.MAX_VALUE)) {
            val spiel = SpielAttrappe(start)
            spiel.zeile("C-", 15, 200, 1, 1, 1)
            val huelle = MitMeldung(spiel)
            val log = ArrayList<String>()
            val aktionen = ArrayList<Pair<Instant, String>>()
            val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), huelle, protokoll = { log += it })
            var e: Ergebnis = Ergebnis.Pausiert
            for (i in 0 until 200) {
                val vorher = spiel.gebotePreise.size
                e = schritt(bot, spiel, aktionen)
                if (e is Ergebnis.Fertig) break
                if (vorher == 0 && spiel.gebotePreise.size == 1) huelle.preisFalsch = falsch // right after the bid
                if (spiel.gebotePreise.isNotEmpty() && huelle.preisFalsch == 0 && log.any { it.startsWith("Bid arrived") }) break
            }
            assertEquals(listOf(16), spiel.gebotePreise)
            if (falsch > 1) {
                assertEquals(Ergebnis.Fertig(StoppGrund.GEBOT_ZU_HOCH), e, log.joinToString("\n"))
                assertNotNull(log.firstOrNull { it.startsWith("SAFETY: our bid on 1-1-1 shows 30 SG") }, log.joinToString("\n"))
            } else {
                assertTrue(e !is Ergebnis.Fertig, "$e")
                assertTrue(log.any { it == "Bid arrived: clock on the row (1-1-1)" }, log.joinToString("\n"))
            }
        }
    }
}
