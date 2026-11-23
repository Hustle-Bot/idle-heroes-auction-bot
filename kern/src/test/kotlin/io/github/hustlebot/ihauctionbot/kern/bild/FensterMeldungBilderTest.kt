package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import io.github.hustlebot.ihauctionbot.kern.BERLIN
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.FilterFeld
import io.github.hustlebot.ihauctionbot.kern.KOPIE_TYPEN
import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.Meldung
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.StoppBedingung
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.Zeile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Nov 23 (review: "unknown messages in the Bid window are tapped through"): in the Bid and confirm window only "Bidding
 * successful", "Bid Failure" and "-7" are known; any other band pauses the bot, and Bid is never tapped under it. Checked on
 * the real screenshots of the Bid window (testbilder/) – with their real messages and with a synthetic band on top.
 */
class FensterMeldungBilderTest {

    private val jetzt = LocalDateTime.of(2026, 11, 23, 21, 0).atZone(BERLIN).toInstant()

    private fun getippt(e: Ergebnis): Punkt = ((e as Ergebnis.Handeln).aktion as Aktion.Tippen).punkt

    @Test
    fun `every real Bid or confirm window with a message shows one the bot knows - none of them pauses the bot`() {
        val ordner = listOf(File("../testbilder"), File("testbilder"), File("idle-heroes-bot/testbilder")).first { it.isDirectory }
        val namen = ordner.listFiles()!!.filter { it.isFile }.map { it.nameWithoutExtension } +
            File(ordner, "berichte").listFiles()!!.map { "berichte/" + it.nameWithoutExtension }
        val fenster = namen.map { it to Erkenner.erkenne(TestBilder.lade(it)) }.filter { it.second.ort == Ort.BIETEN || it.second.ort == Ort.BESTAETIGEN }
        val mitMeldung = fenster.filter { it.second.meldung != null }
        // The real ones: "-7" (limit-50-status-7) and "Bid Failure" over the Bid window (report 19-39-28, images 01 and 02).
        assertTrue(mitMeldung.map { it.first }.containsAll(listOf("limit-50-status-7", "berichte/19-39-28_bild_01", "berichte/19-39-28_bild_02")), "$mitMeldung")
        for ((name, b) in mitMeldung) {
            val m = b.meldung!!
            assertTrue(m.bekanntImFenster && !m.wieZuOft, "$name: $m")
        }
        assertTrue(fenster.size >= 10, "${fenster.map { it.first }}")
    }

    @Test
    fun `-7 in the real Bid window - handled exactly as before (limit, close with X), no pause`() {
        val log = ArrayList<String>()
        val bot = AuktionsBot(
            listOf(Suchregel("C-", 20, 24, maxRestzeitMin = 5)), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("limit-50-status-7")) },
            protokoll = { log += it },
        )
        assertTrue(bot.schritt(jetzt) is Ergebnis.Warten)
        assertEquals(Punkt(2105, 108), getippt(bot.schritt(jetzt.plusMillis(300))))
        assertTrue(bot.gebote.limitGemeldet)
        assertNull(bot.zuOftPauseBis)
        assertEquals(0, bot.zuOftMeldungen)
        assertTrue(log.none { it.startsWith("Unknown message") }, log.joinToString("\n"))
    }

    /**
     * The flow of the real Bid window test (EchteBilderBotTest: B- row for 53, rule up to 54): filter, row, then the real
     * Bid window – here with a synthetic unknown band (1000 px, over the Bid button) on some images.
     */
    @Test
    fun `real Bid window with an unknown band on top - pause, no tap while it is there, Bid for 54 once it is gone`() {
        val bMinus = Suchregel("B-", 10, 54, maxRestzeitMin = 5)
        val zeile = Zeile(
            oben = 314, preis = 53, sofortPreis = null, restzeitSek = 120, hatRestzeit = true,
            werte = listOf(3151, 2502, 38), zeichen = Zeichen.KEINS, stufe = 'B', tippen = Punkt(1356, 374),
        )
        val menue = mapOf(
            Knopf.MENUE_AUKTIONSMARKT to Punkt(321, 342), Knopf.MENUE_BIETERMARKT to Punkt(321, 427),
            Knopf.MENUE_MEINE_GEBOTE to Punkt(321, 488),
        )
        val markt = Bildschirm(
            Ort.MARKT, zeilen = listOf(zeile), sg = 5742, sortierung = Sortierung.RANG,
            knoepfe = menue + mapOf(Knopf.AKTUALISIEREN to Punkt(1688, 231), Knopf.SORTIEREN to Punkt(1895, 231), Knopf.FILTER to Punkt(2180, 231)),
            wischWeg = Punkt(1356, 1080) to Punkt(1356, 520),
        )
        val filter = Bildschirm(
            Ort.FILTER,
            filterFelder = KOPIE_TYPEN.mapIndexed { i, n -> FilterFeld(n, Punkt(800 + i * 100, 285), n == "B-") },
            filterMinStellen = 2, filterMaxStellen = 2,
            knoepfe = mapOf(Knopf.FILTER_OK to Punkt(1547, 1018), Knopf.FILTER_MIN to Punkt(1153, 584), Knopf.FILTER_MAX to Punkt(1559, 584)),
        )
        val echt = Erkenner.erkenne(TestBilder.lade("bid-fenster-b-ohne-buyout-1"))
        assertEquals(Ort.BIETEN, echt.ort)
        assertNull(echt.meldung)
        val bidPunkt = echt.knopf(Knopf.BIETEN)
        assertNotNull(bidPunkt)
        val fremd = echt.copy(meldung = Meldung(1000, yOben = 537, yUnten = 681), knoepfe = echt.knoepfe - Knopf.BIETEN)

        val folge = ArrayDeque(listOf(markt, filter, filter, markt, markt))
        var bild: Bildschirm? = null
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus), StoppBedingung(), { bild ?: folge.removeFirst() }, protokoll = { log += it })
        assertEquals(Punkt(2180, 231), getippt(bot.schritt(jetzt)), "filter")
        bot.schritt(jetzt)
        bot.eingabeErgebnis(true)
        assertEquals(Punkt(1547, 1018), getippt(bot.schritt(jetzt)), "Confirm")
        bot.schritt(jetzt.plusSeconds(7))
        assertEquals("B-", bot.filterStufe)
        assertEquals(Punkt(1356, 374), getippt(bot.schritt(jetzt.plusSeconds(7))), "tap row 53")

        // The real Bid window: first image, then the unknown band lies over it for 5 s (the pause is 3 s).
        bild = echt
        assertTrue(bot.schritt(jetzt.plusMillis(7500)) is Ergebnis.Warten)
        bild = fremd
        var t = jetzt.plusMillis(7900)
        while (t.isBefore(jetzt.plusMillis(12_900))) {
            val e = bot.schritt(t)
            assertTrue(e is Ergebnis.Warten, "$t: $e – ${log.takeLast(3)}")
            t = t.plusMillis(400)
        }
        assertTrue(log.contains("Unknown message over the Bid window (width 1000) – not tapping through it, waiting 3 s"), log.joinToString("\n"))
        // Gone: Bid for 54 at the button found on the real image.
        bild = echt
        val e = bot.schritt(t)
        assertEquals(bidPunkt, getippt(e), "$e – ${log.takeLast(3)}")
        assertTrue((e as Ergebnis.Handeln).aktion.grund == "Bid for 54 SG", e.aktion.grund)
    }
}
