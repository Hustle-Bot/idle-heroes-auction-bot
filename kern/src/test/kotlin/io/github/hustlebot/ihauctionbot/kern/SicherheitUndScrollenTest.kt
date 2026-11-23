package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 11 (field test 1.13):
 *  - The bot tapped the "+" next to the Starry Gems at the top (purchase window!) → tap lock at the top.
 *  - After bidding a loading screen sometimes came → wait, tap nothing.
 *  - After the 2nd scroll 3 of 12 copies were skipped → swipe exactly 5 rows, align, check.
 */
class SicherheitUndScrollenTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 11, 21, 0).atZone(BERLIN).toInstant()
    private val cMinus = Suchregel("C-", 10, 20, maxRestzeitMin = 5)

    private fun laufen(
        bot: AuktionsBot,
        spiel: SpielAttrappe,
        schritte: Int,
        jeSchritt: (Ergebnis) -> Unit = {},
        bis: (Ergebnis) -> Boolean = { false },
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

    // ---------------- Tap lock ----------------

    private val bidFenster = Bildschirm(
        Ort.BIETEN, aktuellesGebot = 15,
        knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 690), Knopf.SCHLIESSEN to Punkt(2105, 108)),
    )

    @Test
    fun `tap lock - never in the top bar, except the red X of a steady window`() {
        val markt = Bildschirm(Ort.MARKT)
        // The "+" next to the Starry Gems (top right) and the back arrow (top left).
        assertNotNull(TippSperre.pruefe(Aktion.Tippen(Punkt(2105, 108), "x"), markt, ortRuhig = true))
        assertNotNull(TippSperre.pruefe(Aktion.Tippen(Punkt(60, 60), "back"), markt, ortRuhig = true))
        // In the middle of the list: allowed.
        assertNull(TippSperre.pruefe(Aktion.Tippen(Punkt(1356, 374), "row"), markt, ortRuhig = false))
        // Buttons below the bar (refresh, filter): allowed.
        assertNull(TippSperre.pruefe(Aktion.Tippen(Punkt(2180, 231), "Filter"), markt, ortRuhig = false))
        // Red X in the Bid window: only if the window is already steady.
        assertNull(TippSperre.pruefe(Aktion.Tippen(Punkt(2105, 108), "X"), bidFenster, ortRuhig = true))
        val warum = TippSperre.pruefe(Aktion.Tippen(Punkt(2105, 108), "X"), bidFenster, ortRuhig = false)
        assertTrue(warum!!.contains("not steady"), warum)
        // Another point at the top of the Bid window (not the X that was found): locked.
        assertNotNull(TippSperre.pruefe(Aktion.Tippen(Punkt(1950, 60), "+"), bidFenster, ortRuhig = true))
        // Outside the screen: locked.
        assertNotNull(TippSperre.pruefe(Aktion.Tippen(Punkt(3000, 500), "x"), markt, ortRuhig = true))
    }

    @Test
    fun `tap lock - swipes, typing and sequences are checked too`() {
        val markt = Bildschirm(Ort.MARKT)
        assertNull(TippSperre.pruefe(Aktion.Wischen(Punkt(1356, 1020), Punkt(1356, 245), 900, "further"), markt, false))
        assertNotNull(TippSperre.pruefe(Aktion.Wischen(Punkt(1356, 1020), Punkt(1356, 100), 900, "too far"), markt, false))
        // Nov 12 (emulator run 16): never start in the bottom gesture edge (otherwise "go home") and never above y 200.
        assertNotNull(TippSperre.pruefe(Aktion.Wischen(Punkt(1356, 1150), Punkt(1356, 245), 900, "at the bottom edge"), markt, false))
        assertNotNull(TippSperre.pruefe(Aktion.Wischen(Punkt(1356, 1020), Punkt(1356, 180), 900, "too high"), markt, false))
        assertNotNull(TippSperre.pruefe(Aktion.Wischen(Punkt(1356, 120), Punkt(1356, 700), 900, "from the top"), markt, false))
        assertNotNull(TippSperre.pruefe(Aktion.Eingeben(Punkt(1153, 90), "10", "field"), markt, false))
        val folge = Aktion.Folge(
            listOf(Aktion.Tippen(Punkt(800, 285), "C-"), Aktion.Tippen(Punkt(2300, 50), "+")), 150, "Filter",
        )
        assertNotNull(TippSperre.pruefe(folge, Bildschirm(Ort.FILTER), true))
    }

    @Test
    fun `tap lock - converts smaller screenshots to the reference`() {
        // Half resolution (1356 × 610): y 70 → reference 140 (in the bar), y 80 → 160 (below it).
        val klein = Bildschirm(Ort.MARKT, bildBreite = 1356, bildHoehe = 610)
        assertNotNull(TippSperre.pruefe(Aktion.Tippen(Punkt(1000, 70), "x"), klein, true))
        assertNull(TippSperre.pruefe(Aktion.Tippen(Punkt(1000, 80), "x"), klein, true))
    }

    @Test
    fun `bot - a button wrongly recognized in the top bar is not tapped and shows up in the log`() {
        // The filter button was (wrongly) recognized at the top in the purchase bar.
        val leser = BildschirmLeser { Bildschirm(Ort.MARKT, sg = 500, knoepfe = mapOf(Knopf.FILTER to Punkt(2180, 100))) }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser, protokoll = { log += it })
        val e = bot.schritt(start)
        assertTrue(e is Ergebnis.Warten, "$e")
        assertEquals(1, bot.gesperrteTipps)
        assertTrue(log.any { it.startsWith("Tap locked: open filter") && it.contains("top") }, log.joinToString("\n"))
    }

    @Test
    fun `regression plus tap - Bid window closes by itself after Confirm, bot does NOT tap the X at the top`() {
        // This is how it was on Nov 11: Confirm → on the next images still the (closing) Bid window → X tapped →
        // the window was already gone, underneath was the "+" next to the Starry Gems.
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = true
        spiel.bidFensterNachConfirmBilder = 3
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        spiel.zeile("C-", 16, 110, 2, 2, 2)
        spiel.zeile("C-", 17, 120, 3, 3, 3)
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it })
        laufen(bot, spiel, 300) { spiel.gebotePreise.size >= 3 && spiel.ort == Ort.MARKT }
        assertEquals(listOf(16, 17, 18), spiel.gebotePreise)
        assertTrue(spiel.obenGetippt.isEmpty(), "tapped at the top: ${spiel.obenGetippt}")
        assertTrue(spiel.handlungen.none { it.startsWith("close Bid window") }, spiel.handlungen.joinToString("\n"))
    }

    @Test
    fun `Bid window really stays open after Confirm - close with X only after the waiting time and when steady`() {
        val spiel = SpielAttrappe(start)
        spiel.mitBestaetigen = true
        spiel.bidFensterNachConfirmBilder = 1000 // never closes
        spiel.zeile("C-", 15, 100, 1, 1, 1)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        var confirmUm: Instant? = null
        var xUm: Instant? = null
        laufen(bot, spiel, 300, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && e.aktion.grund.startsWith("Confirm")) confirmUm = spiel.jetzt
            if (e is Ergebnis.Handeln && e.aktion.grund.startsWith("close Bid window")) xUm = spiel.jetzt
        }) { xUm != null }
        assertNotNull(xUm, "closes in the end after all")
        assertTrue(java.time.Duration.between(confirmUm, xUm).toMillis() >= Takt().fensterWartenMs)
    }

    // ---------------- Loading screen ----------------

    @Test
    fun `loading screen after an action - just wait up to 30 seconds, tap nothing`() {
        var n = 0
        val leser = BildschirmLeser {
            n++
            if (n == 1) Bildschirm(Ort.MARKT, sg = 500, knoepfe = mapOf(Knopf.FILTER to Punkt(2180, 231))) else Bildschirm.UNBEKANNT
        }
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), leser, protokoll = { log += it })
        assertTrue(bot.schritt(start) is Ergebnis.Handeln, "open filter")
        var t = start.plusMillis(500)
        while (t.isBefore(start.plusMillis(29_500))) {
            val e = bot.schritt(t)
            assertTrue(e is Ergebnis.Warten, "$t: $e")
            t = t.plusMillis((e as Ergebnis.Warten).ms)
        }
        // After 30 seconds as before: "uncertain" (save the image), but still tap nothing.
        assertTrue(bot.schritt(start.plusSeconds(31)) is Ergebnis.Unsicher)
    }

    // ---------------- Scrolling ----------------

    @Test
    fun `swipe distance and alignment - numbers`() {
        assertEquals(905, wischWeite(1038), "clean page: exactly 5 rows")
        assertEquals(853, wischWeite(986), "field test: last row at 986")
        assertEquals(181, wischWeite(100), "at least 1 row")
        assertEquals(905, wischWeite(2000), "at most 5 rows")
        assertNull(ausrichtung(314))
        assertNull(ausrichtung(330))
        assertNull(ausrichtung(null))
        assertEquals(84, ausrichtung(411), "field test 1.13: first row at 411 → bring back the half row above it")
        assertEquals(24 + 181, ausrichtung(471), "swipe too short → one whole row more back")
        assertEquals(141, ausrichtung(354))
    }

    /** Rows that were seen as a whole page (on the image on which the bot swipes on or stops). */
    private fun seitenSammeln(spiel: SpielAttrappe, bot: AuktionsBot, schritte: Int): Pair<Set<List<Int?>>, List<Aktion.Wischen>> {
        val gesehen = HashSet<List<Int?>>()
        val wischer = ArrayList<Aktion.Wischen>()
        laufen(bot, spiel, schritte, jeSchritt = { e ->
            if (e is Ergebnis.Handeln && (e.aktion.grund.startsWith("further down") || e.aktion.grund.contains("round over"))) {
                spiel.letztesBild!!.zeilen.forEach { gesehen += it.werte }
            }
            if (e is Ergebnis.Handeln && e.aktion is Aktion.Wischen) wischer += e.aktion as Aktion.Wischen
        }) { it is Ergebnis.Handeln && it.aktion.grund.contains("round over") }
        return gesehen to wischer
    }

    private fun langeListe(spiel: SpielAttrappe, n: Int) {
        // I am in front everywhere (clock) – nothing to bid, the bot only swipes through the list.
        for (i in 1..n) spiel.zeile("C-", 10 + i % 10, 100L + i, i, i, i, zeichen = Zeichen.UHR)
    }

    @Test
    fun `scrolling - exactly 5 rows per swipe, no copy skipped`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        // Nov 12: as on the reference phone – the list moves 1.14 times as far as the finger drags.
        spiel.wischFaktor = 1.14
        langeListe(spiel, 17)
        val log = ArrayList<String>()
        // Nov 11 (field test 1.20): the app already knows the ratio "moved / dragged" from the last run.
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel, protokoll = { log += it }, wischVerhaeltnisStart = 1.14)
        val (gesehen, wischer) = seitenSammeln(spiel, bot, 400)
        assertEquals((1..17).map { listOf(it, it, it) }.toSet(), gesehen, "all 17 copies fully seen")
        val weiter = wischer.filter { it.grund == "further down" }
        // Nov 12 (field test 1.33): the big swipe leaves the last copy seen at the top (measure exactly) –
        // (905 − 221) / 1.14 ≈ 600 pixels of finger –, then a short swipe pushes the rest (5 new copies).
        val finger = weiter.first().von.y - weiter.first().nach.y
        assertTrue(finger in 595..605, "(905 − 221) / 1.14 = about 600 pixels of finger ($finger)")
        // Nov 12 (emulator run 16): no swipe starts in the bottom gesture edge, none reaches above y 200.
        assertTrue(wischer.all { maxOf(it.von.y, it.nach.y) <= 1020 && minOf(it.von.y, it.nach.y) >= 200 }, wischer.toString())
        assertTrue(weiter.all { it.haltenMs > 0 }, "hold still at the end (no momentum)")
        // Nov 12 (review): the app's start value is first checked with a short measuring swipe – after that the
        // rest is pushed once. Nov 12 (field test 1.33): every page at most ONE short push, never look back.
        assertEquals("further down (measure first)", wischer.first { it.grund.startsWith("further down") }.grund)
        val zweiter = wischer.indexOf(weiter.first())
        assertEquals(1, wischer.take(zweiter).count { it.grund.startsWith("align the list") }, "first page: push the rest once")
        assertTrue(wischer.none { it.grund.startsWith("push the list back") }, "clean list: never look back")
        assertTrue(log.count { it.startsWith("Swiped: 5 new copies") } >= 2, log.joinToString("\n"))
    }

    @Test
    fun `scrolling - list keeps sliding with momentum - wait first, then align, nothing skipped`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischFaktor = 1.1
        spiel.nachrutschBilder = 1
        langeListe(spiel, 17)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        val (gesehen, wischer) = seitenSammeln(spiel, bot, 600)
        assertEquals((1..17).map { listOf(it, it, it) }.toSet(), gesehen, "all 17 copies fully seen")
        assertTrue(wischer.any { it.grund.startsWith("align the list") }, "pushed on")
        // Nov 11 (field test 1.20: "scrolls clearly too far"): the bot notices that the list moves further than
        // dragged (1.1 times plus 40 pixels of momentum), and then drags the finger less: (905 − 221 − 40) / 1.1 ≈ 585
        // (Nov 12: the last copy seen stays at the top).
        val weiter = wischer.filter { it.grund == "further down" }
        assertTrue(weiter.isNotEmpty() && weiter.last().let { it.von.y - it.nach.y } in 540..620, weiter.map { it.von.y - it.nach.y }.toString())
        assertTrue(bot.wischVerhaeltnis in 1.1..1.25, "ratio ${bot.wischVerhaeltnis}")
    }

    @Test
    fun `scrolling - game swallows the start of the swipe - nothing skipped`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.wischTotzone = 40
        langeListe(spiel, 17)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        val (gesehen, _) = seitenSammeln(spiel, bot, 600)
        assertEquals((1..17).map { listOf(it, it, it) }.toSet(), gesehen)
    }

    @Test
    fun `scrolling - never tap a row while bidding as long as the list is still sliding`() {
        val spiel = SpielAttrappe(start)
        spiel.pixelGenau = true
        spiel.mitBestaetigen = false
        spiel.nachrutschBilder = 2
        spiel.rutschPx = 20
        for (i in 1..12) spiel.zeile("C-", 10 + i % 8, 100L + i * 5, i, i, i)
        val bot = AuktionsBot(listOf(cMinus), StoppBedingung(), spiel)
        laufen(bot, spiel, 1500) { spiel.gebotePreise.size >= 12 }
        assertTrue(spiel.getipptBeimRutschen.isEmpty(), "tapped while sliding: ${spiel.getipptBeimRutschen}")
        assertEquals(12, spiel.gebotePreise.size, "bid on all 12 copies (none skipped)")
        assertTrue(spiel.obenGetippt.isEmpty())
    }
}
