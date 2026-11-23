package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import io.github.hustlebot.ihauctionbot.kern.BERLIN
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.StoppBedingung
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nov 22 (rate limit, review before publishing): the game's "Operated too frequently. Please retry in N second(s)." on the
 * real report screenshots – is it recognized (and nothing else as it), and does the bot wait instead of tapping?
 */
class ZuOftBilderTest {

    /** The real screenshots with "Operated too frequently" (report 20-42-45: "retry in 3/2/1/1 s", report 21-46-15 the same). */
    private val zuOft = mapOf(
        "berichte/20-42-45_bild_08" to Ort.SORTIEREN,
        "berichte/20-42-45_bild_09" to Ort.SORTIEREN,
        "berichte/20-42-45_bild_10" to Ort.SORTIEREN,
        "berichte/20-42-45_bild_11" to Ort.AUKTIONSHAUS_ANDERS,
        "berichte/21-46-15_bild_03" to Ort.SORTIEREN,
        "berichte/21-46-15_bild_04" to Ort.SORTIEREN,
        "berichte/21-46-15_bild_05" to Ort.SORTIEREN,
        "berichte/21-46-15_bild_06" to Ort.AUKTIONSHAUS_ANDERS,
    )

    /** "Bidding successful. Check it in My Biddings." (one line, 1152–1248 px). */
    private val erfolg = listOf(
        "markt-toast-erfolg-1", "markt-toast-erfolg-2",
        "berichte/19-33-55_bild_01", "berichte/19-33-55_bild_02", "berichte/19-39-28_bild_05", "berichte/19-39-28_bild_06",
    )

    /** "Bid Failure: You have lost the bid …" (two lines) – over the list and over the Bid window. */
    private val fehlschlag = listOf(
        "toast-verloren", "berichte/19-39-28_bild_01", "berichte/19-39-28_bild_02", "berichte/19-39-28_bild_14",
    )

    @Test
    fun `operated too frequently - recognized on all 8 real screenshots`() {
        for ((name, ort) in zuOft) {
            val s = Erkenner.erkenne(TestBilder.lade(name))
            assertEquals(ort, s.ort, name)
            val m = s.meldung
            assertTrue(m != null && m.wieZuOft, "$name: $m")
            assertFalse(m!!.wieErfolg || m.wieFehlschlag || m.wieLimit || m.bekannt, "$name: $m")
        }
    }

    @Test
    fun `success, Bid Failure and -7 are never taken for operated too frequently`() {
        for (name in erfolg) {
            val m = Erkenner.erkenne(TestBilder.lade(name)).meldung
            assertTrue(m != null && m.wieErfolg && m.bekannt && !m.wieZuOft, "$name: $m")
        }
        for (name in fehlschlag) {
            val m = Erkenner.erkenne(TestBilder.lade(name)).meldung
            assertTrue(m != null && m.wieFehlschlag && m.bekannt && !m.wieZuOft, "$name: $m")
        }
        val limit = Erkenner.erkenne(TestBilder.lade("limit-50-status-7")).meldung
        assertTrue(limit != null && limit.wieLimit && !limit.wieZuOft, "$limit")
    }

    @Test
    fun `no other test image shows operated too frequently`() {
        val ordner = listOf(File("../testbilder"), File("testbilder"), File("idle-heroes-bot/testbilder")).first { it.isDirectory }
        val namen = ordner.listFiles()!!.filter { it.isFile }.map { it.nameWithoutExtension } +
            File(ordner, "berichte").listFiles()!!.map { "berichte/" + it.nameWithoutExtension }
        val gefunden = namen.filter { Erkenner.erkenne(TestBilder.lade(it)).meldung?.wieZuOft == true }.sorted()
        assertEquals(zuOft.keys.sorted(), gefunden)
    }

    /**
     * The real sequence of report 20-42-45: sort window → "Awakened Tier ↑" → "retry in 3, 2, 1, 1 s" (the last over the
     * empty auction house) → sort window again. Before Nov 22 the bot tapped "Awakened Tier ↑" again and "Auction Market"
     * on the screenshots with the message. Now it taps nothing for 5 s, then chooses the sort order once more.
     */
    @Test
    fun `real screenshots - the bot taps nothing while the game says operated too frequently, then goes on`() {
        val start: Instant = LocalDateTime.of(2026, 11, 22, 20, 42).atZone(BERLIN).toInstant()
        var name = "berichte/20-42-45_bild_07"
        val log = ArrayList<String>()
        val bot = AuktionsBot(
            listOf(Suchregel("B-", 10, 60, maxRestzeitMin = 5)), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade(name)) },
            protokoll = { log += it },
        )
        val rangAuf = Punkt(1734 / 2, 499 / 2) // the report images are half the size of the phone's screen
        fun tippt(e: Ergebnis) = ((e as? Ergebnis.Handeln)?.aktion as? Aktion.Tippen)?.punkt

        assertTrue(bot.schritt(start) is Ergebnis.Warten, "sort window is opening")
        assertEquals(rangAuf, tippt(bot.schritt(start.plusMillis(300))), "Awakened Tier ↑")
        // The game answers with the message: 3, 2, 1, 1 s (one screenshot per second), the last over the empty auction house.
        var t = start.plusMillis(1200)
        for (bild in listOf("08", "09", "10", "11")) {
            name = "berichte/20-42-45_bild_$bild"
            val e = bot.schritt(t)
            assertTrue(e is Ergebnis.Warten, "$name: $e")
            t = t.plusSeconds(1)
        }
        assertTrue(log.any { it == "Game says: operated too frequently – waiting 5 s (1 of 5 in a row)" }, log.joinToString("\n"))
        assertEquals(start.plusMillis(1200 + 5000), bot.zuOftPauseBis)
        // The sort window without the message, but the pause is not over yet: still nothing.
        name = "berichte/20-42-45_bild_07"
        while (t.isBefore(start.plusMillis(1200 + 5000))) {
            assertTrue(bot.schritt(t) is Ergebnis.Warten, "pause until ${bot.zuOftPauseBis}, now $t")
            t = t.plusMillis(400)
        }
        // After the pause (and once the screenshot no longer counts as old): "Awakened Tier ↑" once more.
        t = start.plusMillis(6500)
        assertEquals(rangAuf, tippt(bot.schritt(t)), log.joinToString("\n"))
        assertEquals(1, bot.zuOftMeldungen)
        // The message comes right back → twice as long a pause.
        name = "berichte/21-46-15_bild_03"
        assertTrue(bot.schritt(t.plusSeconds(1)) is Ergebnis.Warten)
        assertTrue(log.any { it == "Game says: operated too frequently – waiting 10 s (2 of 5 in a row)" }, log.joinToString("\n"))
    }
}
