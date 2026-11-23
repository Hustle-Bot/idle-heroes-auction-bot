package io.github.hustlebot.ihauctionbot.attrappe

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.GebotsVerwaltung
import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.StoppBedingung
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import io.github.hustlebot.ihauctionbot.kern.TippSperre
import io.github.hustlebot.ihauctionbot.kern.bild.Erkenner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Nov 11: the REAL bot (Erkenner + AuktionsBot) against the fake game – on the PC, without a phone.
 * The fake game builds its images from real screenshots; the bot sees them like the game and taps/swipes.
 */
class BotGegenAttrappeTest {

    private val start: Instant = LocalDateTime.of(2026, 11, 11, 21, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant()

    /** Lets the bot run against the fake game for [dauerMs]. */
    private class Lauf(val spiel: Spiel, val bot: AuktionsBot, var jetzt: Long, val passwort: String? = null) {
        /** Nov 12: called after every action (the delay model remembers the new image). */
        var nachHandlung: (Long) -> Unit = {}
        /** Nov 12: a step takes this much longer (screenshot in transit, as in the emulator). */
        var schrittDauer: () -> Long = { 0 }
        val bot_log = ArrayList<String>()
        var ende: Ergebnis? = null
        var schritte = 0

        fun bis(ms: Long, halt: () -> Boolean = { false }) {
            val ziel = jetzt + ms
            while (jetzt < ziel && ende == null && !halt()) {
                schritte++
                when (val e = bot.schritt(Instant.ofEpochMilli(jetzt))) {
                    is Ergebnis.Handeln -> {
                        ausfuehren(e.aktion)
                        nachHandlung(jetzt)
                        jetzt += e.naechsterSchrittMs + 380 + schrittDauer() // + screenshot and recognition as on the phone
                    }
                    is Ergebnis.Warten -> jetzt += maxOf(e.ms, 100) + schrittDauer()
                    is Ergebnis.Unsicher -> jetzt += maxOf(e.ms, 100) + schrittDauer()
                    is Ergebnis.Fertig -> ende = e
                    Ergebnis.Pausiert -> jetzt += 1000
                }
            }
        }

        private fun ausfuehren(a: Aktion) {
            when (a) {
                is Aktion.Tippen -> spiel.tippen(a.punkt.x, a.punkt.y, jetzt)
                is Aktion.Wischen -> spiel.wischen(a.von.y, a.nach.y, jetzt + a.dauerMs)
                is Aktion.Eingeben -> {
                    spiel.tippen(a.feld.x, a.feld.y, jetzt)
                    val feld = if (a.feld == Bildschirm(io.github.hustlebot.ihauctionbot.kern.Ort.FILTER).knopf(Knopf.FILTER_MIN)) 0 else -1
                    spiel.textSetzen(spiel.fokus.takeIf { it >= 0 } ?: feld, a.text, jetzt)
                    bot.eingabeErgebnis(true)
                }
                is Aktion.Folge -> {
                    var ok = true
                    for (s in a.schritte) {
                        when (s) {
                            is Aktion.Tippen -> spiel.tippen(s.punkt.x, s.punkt.y, jetzt)
                            is Aktion.Eingeben -> {
                                spiel.tippen(s.feld.x, s.feld.y, jetzt)
                                if (spiel.fokus < 0) ok = false else spiel.textSetzen(spiel.fokus, s.text, jetzt)
                            }
                            else -> ok = false
                        }
                        jetzt += a.abstandMs
                    }
                    if (a.hatEingaben) bot.eingabeErgebnis(ok)
                }
                is Aktion.PasswortEingeben -> {
                    spiel.tippen(a.feld.x, a.feld.y, jetzt)
                    spiel.textSetzen(Spiel.FELD_PASSWORT, passwort ?: "", jetzt)
                    bot.eingabeErgebnis(passwort != null)
                }
            }
        }
    }

    private fun lauf(
        einst: SpielEinstellung,
        regel: Suchregel = Suchregel("C-", 10, 20, maxRestzeitMin = 5),
        gv: GebotsVerwaltung = GebotsVerwaltung(),
        passwort: String? = null,
        fotoAltMs: Long = 0,
        verzug: Verzug? = null,
    ): Lauf {
        val t0 = start.toEpochMilli()
        val spiel = Spiel(Bildbauer(PcBilder.atlas), einst, t0, fremd = PcBilder.lade("transaktionen"))
        val log = ArrayList<String>()
        lateinit var l: Lauf
        // Nov 11 (emulator run 9): there the screenshot often still showed the image from 1–2 s before (the window was
        // already closed). [fotoAltMs] > 0 simulates that: the bot sees the image that was visible this many ms ago.
        val fotos = ArrayDeque<Pair<Long, io.github.hustlebot.ihauctionbot.kern.bild.Bild>>()
        // Nov 12 (emulator run 13): delay as in the emulator – the screenshot is 2–2.5 s in transit, the game draws the old
        // image for up to 2 s after a tap, and because both vary, an older image sometimes comes after a newer one.
        val zufall = java.util.Random(verzug?.seed ?: 1)
        val stand = ArrayList<Pair<Long, io.github.hustlebot.ihauctionbot.kern.bild.Bild>>()
        fun merke(t: Long) {
            stand += t to spiel.bild(t)
            while (stand.size > 2 && stand[1].first < t - 8_000) stand.removeAt(0)
        }
        fun fotoMitVerzug(t: Long, v: Verzug): io.github.hustlebot.ihauctionbot.kern.bild.Bild {
            merke(t)
            var alt = v.fotoMinMs + zufall.nextInt((v.fotoMaxMs - v.fotoMinMs + 1).toInt()) + zufall.nextInt(v.anzeigeMaxMs.toInt() + 1)
            // Now and then a clearly older image (after a newer one).
            if (zufall.nextInt(100) < v.rueckfallProzent) alt += 1000 + zufall.nextInt(2001)
            return stand.lastOrNull { it.first <= t - alt }?.second ?: stand.first().second
        }
        fun foto(t: Long): io.github.hustlebot.ihauctionbot.kern.bild.Bild {
            if (verzug != null) return fotoMitVerzug(t, verzug)
            val jetzt = spiel.bild(t)
            if (fotoAltMs <= 0) return jetzt
            fotos.addLast(t to jetzt)
            while (fotos.size > 1 && fotos[1].first <= t - fotoAltMs) fotos.removeFirst()
            return fotos.first().second
        }
        val bot = AuktionsBot(
            listOf(regel), StoppBedingung(), { Erkenner.erkenne(foto(l.jetzt)) }, gebote = gv,
            protokoll = { log += it }, hatZweitpasswort = passwort != null,
        )
        l = Lauf(spiel, bot, t0, passwort)
        if (verzug != null) {
            l.nachHandlung = { t -> merke(t) }
            l.schrittDauer = { verzug.schrittMs }
        }
        log.let { l.bot_log.addAll(it) }
        spiel.protokoll = {}
        // Take over the bot log as it goes.
        return l.also { it.bot_log.clear() }.also { lauf -> logs[lauf] = log }
    }

    private val logs = HashMap<Lauf, ArrayList<String>>()

    /** Nov 12: delay as in the emulator (see [lauf]). [schrittMs]: every step takes this much longer. */
    private data class Verzug(
        val fotoMinMs: Long, val fotoMaxMs: Long, val anzeigeMaxMs: Long, val schrittMs: Long, val rueckfallProzent: Int = 15, val seed: Long = 1,
    )

    private fun bericht(l: Lauf): String {
        val s = l.spiel
        println(
            "FAKE GAME: ${l.schritte} steps, bids ${s.gebote}, re-bids ${s.nachgebote}, dismissed ${s.weggedrueckt}, " +
                "-7 ${s.limitMeldungen}, won ${s.gewonnen}, forbidden ${s.verboten}, end ${l.ende}",
        )
        java.io.File("build/attrappe").mkdirs()
        java.io.File("build/attrappe/lauf-${System.identityHashCode(l)}.txt").writeText(
            "--- Bot ---\n" + logs.getValue(l).joinToString("\n") + "\n--- Fake game ---\n" + l.spiel.log.joinToString("\n"),
        )
        return berichtText(l)
    }

    private fun berichtText(l: Lauf): String =
        "--- Bot ---\n" + logs.getValue(l).takeLast(120).joinToString("\n") + "\n--- Fake game ---\n" + l.spiel.log.takeLast(120).joinToString("\n")

    @Test
    fun `the real bot bids in the fake game - filter, bids, no forbidden taps`() {
        val l = lauf(SpielEinstellung(limit = 50, ueberbietenAlleMs = 0, ladenJedesNteGebot = 4, bestaetigenJedesNte = 3))
        l.bis(8 * 60_000L)
        val b = bericht(l)
        assertEquals(null, l.ende, b)
        assertEquals(0, l.spiel.verboten, b)
        assertTrue(l.spiel.log.any { it.contains("FILTER 10-20") }, "filter C- 10–20 set\n$b")
        assertTrue(l.spiel.gebote >= 5, "bids: ${l.spiel.gebote}\n$b")
        assertTrue(logs.getValue(l).none { it.startsWith("Tap locked") }, b)
        assertTrue(l.spiel.log.any { it.contains("CONFIRM") }, "confirm window answered with Confirm\n$b")
    }

    @Test
    fun `50 limit - clean up My Biddings, dismiss outbid ones, then keep bidding`() {
        val gv = GebotsVerwaltung(limit = 12)
        val l = lauf(SpielEinstellung(limit = 12, startEintraege = 9, ueberbietenAlleMs = 30_000, ladenJedesNteGebot = 0), gv = gv)
        l.bis(15 * 60_000L)
        val b = bericht(l)
        assertEquals(null, l.ende, b)
        assertEquals(0, l.spiel.verboten, b)
        assertTrue(l.spiel.weggedrueckt >= 1, "dismissed: ${l.spiel.weggedrueckt}\n$b")
        assertTrue(logs.getValue(l).any { it.startsWith("My Biddings done") }, b)
        assertTrue(l.spiel.gebote >= 4, "bids: ${l.spiel.gebote}\n$b")
        // Our own count cleans up before – "-7" only comes at the start (the bot does not know other people's bids).
        assertTrue(logs.getValue(l).any { it.startsWith("About 12/12 in My Biddings") }, b)
        assertTrue(l.spiel.limitMeldungen <= 3, "-7: ${l.spiel.limitMeldungen}\n$b")
    }

    @Test
    fun `secondary password - enter it, Verify, then bidding goes on normally`() {
        val l = lauf(SpielEinstellung(passwort = "1234", ueberbietenAlleMs = 0, ladenJedesNteGebot = 0), passwort = "1234")
        l.bis(4 * 60_000L)
        val b = bericht(l)
        assertEquals(null, l.ende, b)
        assertEquals(0, l.spiel.verboten, b)
        assertTrue(l.spiel.passwortOk, b)
        assertTrue(l.spiel.gebote >= 3, "bids: ${l.spiel.gebote}\n$b")
        assertTrue(logs.getValue(l).any { it.contains("Secondary password accepted") }, b)
    }

    @Test
    fun `X opens a foreign window - the bot stops and taps nothing more`() {
        val gv = GebotsVerwaltung(limit = 10)
        val l = lauf(SpielEinstellung(limit = 10, startEintraege = 9, ueberbietenAlleMs = 0, ladenJedesNteGebot = 0, kreuzOeffnetFenster = true), gv = gv)
        l.bis(15 * 60_000L)
        val b = bericht(l)
        assertEquals(Ergebnis.Fertig(io.github.hustlebot.ihauctionbot.kern.StoppGrund.UNBEKANNT_NACH_X), l.ende, b)
        assertEquals(0, l.spiel.verboten, b)
    }

    /**
     * Nov 11 (emulator run 9, real app against the fake game): the screenshot lagged up to 2 s behind. After "Confirm"
     * in the filter the bot still saw the filter window and entered the prices again – the taps landed in the market
     * (opened a Bid window) and in the Bid window on "+"/"−"; then it tapped the red X a second time when the
     * window was already closed: at the top into the purchase bar. Even with lagging screenshots there must never be a forbidden tap.
     */
    @Test
    fun `screenshots lag 1,5 s behind - still never a forbidden tap`() {
        for (alt in listOf(800L, 1500L, 2200L)) {
            val l = lauf(
                SpielEinstellung(limit = 12, startEintraege = 9, ueberbietenAlleMs = 30_000, ladenJedesNteGebot = 5, bestaetigenJedesNte = 4, passwort = "1234"),
                gv = GebotsVerwaltung(limit = 12), passwort = "1234", fotoAltMs = alt,
            )
            l.bis(8 * 60_000L)
            val b = "screenshot $alt ms old\n" + bericht(l)
            assertEquals(0, l.spiel.verboten, b)
            assertTrue(l.spiel.gebote >= 3, "bids: ${l.spiel.gebote}\n$b")
        }
    }

    /**
     * Nov 12 (emulator run 13 with version 1.30): screenshots 2–2.5 s in transit, the game draws the old image for up to 2 s
     * after a tap, older images also come after newer ones. There: 5 forbidden taps ("+"/"−" in the Bid window,
     * second X at the top). With this delay: never a forbidden tap, and the bot still bids (mostly without a stop).
     */
    @Test
    fun `delay as in the emulator - screenshots 2 s in transit, old images also after new ones - never a forbidden tap`() {
        val fehler = ArrayList<String>()
        var durch = 0
        for (seed in 1L..6L) {
            val l = lauf(
                SpielEinstellung(limit = 12, startEintraege = 9, ueberbietenAlleMs = 30_000, ladenJedesNteGebot = 5, bestaetigenJedesNte = 4, passwort = "1234", seed = seed),
                gv = GebotsVerwaltung(limit = 12), passwort = "1234",
                verzug = Verzug(fotoMinMs = 2000, fotoMaxMs = 2500, anzeigeMaxMs = 2000, schrittMs = 1500, seed = seed),
            )
            l.bis(10 * 60_000L)
            val b = "delay, run $seed: ${l.spiel.gebote} bids in 10 min\n" + bericht(l)
            println("DELAY $seed: bids ${l.spiel.gebote} (${"%.1f".format(l.spiel.gebote / 10.0)}/min), forbidden ${l.spiel.verboten}, end ${l.ende}")
            if (l.spiel.verboten != 0) fehler += b
            if (l.ende == null && l.spiel.gebote >= 3) durch++
        }
        assertTrue(fehler.isEmpty(), fehler.joinToString("\n\n"))
        // With this much delay the bot may also stop safely once (e.g. a row in My Biddings slides away while the
        // screenshot is in transit – then the X hits a different row, and it stops without tapping further). Mostly it runs.
        assertTrue(durch >= 4, "only $durch of 6 runs without a stop")
    }

    @Test
    fun `never at the top into the purchase bar - the tap lock knows no exception except the X`() {
        // Safety net: the tap lock itself is tested in the core; here only that it exists.
        assertTrue(TippSperre.OBEN_SPERRE_Y > 0)
    }
}
