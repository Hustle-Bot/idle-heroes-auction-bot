package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.Zeile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Nov 11:
 *  - Second text recognition (ML Kit) only for numbers our own reader cannot read – checked strictly.
 *  - Faster digit recognition: cache per area (same pixels = same result).
 */
class ZweitLeserUndTempoTest {

    @BeforeEach
    fun leeren() {
        Nachlesen.zwischenspeicherLeeren()
        ZiffernLeser.zwischenspeicherLeeren()
    }

    /** An image full of noise: every area is different (important for the cache). */
    private fun rauschen(breite: Int = Bild.REF_BREITE, hoehe: Int = Bild.REF_HOEHE): Bild {
        val r = Random(7)
        return Bild(breite, hoehe, IntArray(breite * hoehe) { 0xFF000000.toInt() or r.nextInt(0xFFFFFF) })
    }

    /** Simulated second reader: answers per area top edge (in the original image) and counts the questions. */
    private class Attrappe(val antwort: (Rechteck) -> String?) : ZweitLeser {
        val gefragt = ArrayList<Rechteck>()
        override fun lies(bild: Bild, bereich: Rechteck): String? {
            gefragt += bereich
            return antwort(bereich)
        }
    }

    private fun zeile(oben: Int, preis: Int?, sofort: Int? = null, rest: Int? = 600) =
        Zeile(oben, preis, sofort, rest, true, listOf(oben, 1, 2), Zeichen.KEINS, tippen = Punkt(1356, oben + 60))

    private fun markt(vararg z: Zeile, sg: Int? = 500, unlesbar: List<io.github.hustlebot.ihauctionbot.kern.UnlesbareZahl>) =
        Bildschirm(Ort.MARKT, zeilen = z.toList(), sg = sg, unlesbar = unlesbar)

    // ---------------- Strict reading ----------------

    @Test
    fun `strict reading - only what looks exactly like the number`() {
        assertEquals(47, Nachlesen.zahl("47", 1, 9_999_999))
        assertEquals(47, Nachlesen.zahl(" 4 7 ", 1, 9_999_999))
        assertNull(Nachlesen.zahl("47a", 1, 9_999_999))
        assertNull(Nachlesen.zahl("O7", 1, 9_999_999), "letter O is not a 0")
        assertNull(Nachlesen.zahl("0", 1, 9_999_999), "below the minimum")
        assertNull(Nachlesen.zahl("", 1, 9))
        assertNull(Nachlesen.zahl(null, 1, 9))
        assertEquals(12345, Nachlesen.sg("12,345"))
        assertEquals(12345, Nachlesen.sg("12345"))
        assertEquals(1234567, Nachlesen.sg("1.234.567"))
        assertNull(Nachlesen.sg("1,23"), "wrong thousands group")
        assertNull(Nachlesen.sg("12,345 SG"))
        assertEquals(5730, Nachlesen.restzeit("01:35:30"))
        assertEquals(5730, Nachlesen.restzeit("1.35.30"))
        assertNull(Nachlesen.restzeit("01:75:30"), "75 minutes do not exist")
        assertNull(Nachlesen.restzeit("99:00:00"), "more than 48 hours")
        assertNull(Nachlesen.restzeit("01:35"))
        assertEquals(18, Nachlesen.bestaetigen("Starry Gems spent: 18"))
        assertEquals(18, Nachlesen.bestaetigen("18"))
        assertNull(Nachlesen.bestaetigen("Starry Gems spent: 1B"))
        assertNull(Nachlesen.bestaetigen("Join the bidding?"))
    }

    @Test
    fun `both readers - different means not readable`() {
        assertEquals(5, Nachlesen.vereinen(null, 5))
        assertEquals(5, Nachlesen.vereinen(5, null))
        assertEquals(5, Nachlesen.vereinen(5, 5))
        assertNull(Nachlesen.vereinen(5, 6))
        assertNull(Nachlesen.vereinen(null, null))
    }

    // ---------------- Reading again with a simulated reader ----------------

    @Test
    fun `missing price - second opinion fills it in, learning image gets the value in its name`() {
        val bild = rauschen()
        val pr = Erkenner.preisFeld(495)
        val s = markt(
            zeile(314, 30), zeile(495, null, sofort = 99),
            unlesbar = listOf(Nachlesen.unlesbar("preis", pr, "z495_495-1-2")),
        )
        val leser = Attrappe { "47" }
        val notizen = ArrayList<String>()
        val neu = Nachlesen.ergaenze(s, bild, bild, leser, { notizen += it }, knapp = { _, _, _ -> false })
        assertEquals(47, neu.zeilen[1].preis)
        assertEquals(30, neu.zeilen[0].preis, "readable row stays")
        assertEquals(1, leser.gefragt.size, "only the missing field")
        assertTrue(notizen.single().contains("price"), notizen.toString())
        assertTrue(neu.unlesbar.single().kontext.endsWith("_zweit-47"))
    }

    @Test
    fun `unsuitable answers do not count - price above buyout, letters, broken time`() {
        val bild = rauschen()
        val s = markt(
            zeile(314, null, sofort = 40), zeile(495, null), zeile(676, 25, rest = null),
            unlesbar = listOf(
                Nachlesen.unlesbar("preis", Erkenner.preisFeld(314)),
                Nachlesen.unlesbar("preis", Erkenner.preisFeld(495)),
                Nachlesen.unlesbar("zeit", Erkenner.zeitFeld(676)),
            ),
        )
        val leser = Attrappe { r ->
            when (r.y0) {
                Erkenner.preisFeld(314).y0 -> "47" // above the buyout (40) → impossible
                Erkenner.preisFeld(495).y0 -> "4O" // letter
                else -> "01:99:00" // not a time
            }
        }
        val neu = Nachlesen.ergaenze(s, bild, bild, leser, knapp = { _, _, _ -> false })
        assertNull(neu.zeilen[0].preis)
        assertNull(neu.zeilen[1].preis)
        assertNull(neu.zeilen[2].restzeitSek)
    }

    @Test
    fun `read remaining time again - bidding list and My Biddings`() {
        val bild = rauschen()
        val zr = Erkenner.zeitFeld(314)
        val s = markt(zeile(314, 30, rest = null), unlesbar = listOf(Nachlesen.unlesbar("zeit", zr)))
        val neu = Nachlesen.ergaenze(s, bild, bild, Attrappe { "00:04:10" }, knapp = { _, _, _ -> false })
        assertEquals(250, neu.zeilen[0].restzeitSek)
        // My Biddings: remaining times and counter.
        val meine = Bildschirm(
            Ort.MEINE_GEBOTE, zeilen = listOf(zeile(374, 30, rest = null)), laufendeGebote = null,
            unlesbar = listOf(Nachlesen.unlesbar("zaehler", Erkenner.ZAEHLER_FELD)),
        )
        val leser = Attrappe { r -> if (r.y0 == Erkenner.ZAEHLER_FELD.y0) "49" else "01:00:05" }
        val m = Nachlesen.ergaenze(meine, bild, bild, leser, knapp = { _, _, _ -> false })
        assertEquals(49, m.laufendeGebote)
        assertEquals(3605, m.zeilen[0].restzeitSek)
    }

    @Test
    fun `read Current Bid and confirm amount again`() {
        val bild = rauschen()
        val bid = Bildschirm(Ort.BIETEN, unlesbar = listOf(Nachlesen.unlesbar("aktuellesGebot", Erkenner.AKTUELLES_GEBOT_FELD)))
        assertEquals(53, Nachlesen.ergaenze(bid, bild, bild, Attrappe { "53" }, knapp = { _, _, _ -> false }).aktuellesGebot)
        val best = Bildschirm(Ort.BESTAETIGEN, unlesbar = listOf(Nachlesen.unlesbar("bestaetigen", Erkenner.BESTAETIGEN_FELD)))
        assertEquals(59, Nachlesen.ergaenze(best, bild, bild, Attrappe { "Starry Gems spent: 59" }, knapp = { _, _, _ -> false }).bestaetigenPreis)
    }

    @Test
    fun `own reader just barely sure and second opinion different - counts as not readable`() {
        val bild = rauschen()
        // SG: own reader 1234 (just barely), second opinion 1284 → not readable.
        val s = markt(zeile(314, 30), sg = 1234, unlesbar = emptyList())
        val notizen = ArrayList<String>()
        val neu = Nachlesen.ergaenze(s, bild, bild, Attrappe { "1,284" }, { notizen += it }, knapp = { _, _, _ -> true })
        assertNull(neu.sg)
        assertTrue(notizen.any { it.contains("not readable") }, notizen.toString())
        // Same number → stays.
        Nachlesen.zwischenspeicherLeeren()
        assertEquals(1234, Nachlesen.ergaenze(s, bild, bild, Attrappe { "1,234" }, knapp = { _, _, _ -> true }).sg)
        // Second opinion knows nothing → own value stays.
        Nachlesen.zwischenspeicherLeeren()
        assertEquals(1234, Nachlesen.ergaenze(s, bild, bild, Attrappe { null }, knapp = { _, _, _ -> true }).sg)
        // Current Bid the same way.
        Nachlesen.zwischenspeicherLeeren()
        val bid = Bildschirm(Ort.BIETEN, aktuellesGebot = 53)
        assertNull(Nachlesen.ergaenze(bid, bild, bild, Attrappe { "58" }, knapp = { _, _, _ -> true }).aktuellesGebot)
    }

    @Test
    fun `time limit and at most 6 questions per image`() {
        val bild = rauschen()
        val oben = (0 until 10).map { 314 + it * 60 }
        val s = markt(
            *oben.map { zeile(it, null) }.toTypedArray(),
            unlesbar = oben.map { Nachlesen.unlesbar("preis", Erkenner.preisFeld(it)) },
        )
        val viele = Attrappe { "20" }
        Nachlesen.ergaenze(s, bild, bild, viele, knapp = { _, _, _ -> false })
        assertEquals(Nachlesen.MAX_FRAGEN, viele.gefragt.size)
        // Every question takes 500 ms (like a time limit of 400 ms on the phone) → after 900 ms no more questions.
        Nachlesen.zwischenspeicherLeeren()
        var uhr = 0L
        val langsam = Attrappe { uhr += 500; null }
        val neu = Nachlesen.ergaenze(s, bild, bild, langsam, uhr = { uhr }, knapp = { _, _, _ -> false })
        assertEquals(2, langsam.gefragt.size)
        assertTrue(neu.zeilen.all { it.preis == null }, "time up = not readable, nothing guessed")
    }

    @Test
    fun `second text recognition missing or broken - the bot keeps running normally`() {
        val bild = rauschen()
        val s = markt(zeile(314, null), unlesbar = listOf(Nachlesen.unlesbar("preis", Erkenner.preisFeld(314))))
        val kaputt = ZweitLeser { _, _ -> throw IllegalStateException("ML Kit not installed") }
        val neu = Nachlesen.ergaenze(s, bild, bild, kaputt, knapp = { _, _, _ -> false })
        assertNull(neu.zeilen[0].preis)
    }

    @Test
    fun `same pixels - the second text recognition is not asked again`() {
        val bild = rauschen()
        val s = markt(zeile(314, null), unlesbar = listOf(Nachlesen.unlesbar("preis", Erkenner.preisFeld(314))))
        val leser = Attrappe { "33" }
        repeat(3) { assertEquals(33, Nachlesen.ergaenze(s, bild, bild, leser, knapp = { _, _, _ -> false }).zeilen[0].preis) }
        assertEquals(1, leser.gefragt.size)
    }

    @Test
    fun `area comes from the image in full resolution`() {
        // Twice as large as the reference: the field is converted too.
        val gross = rauschen(Bild.REF_BREITE * 2, Bild.REF_HOEHE * 2)
        val pr = Erkenner.preisFeld(314)
        val s = markt(zeile(314, null), unlesbar = listOf(Nachlesen.unlesbar("preis", pr)))
        val leser = Attrappe { "12" }
        Nachlesen.ergaenze(s, rauschen(), gross, leser, knapp = { _, _, _ -> false })
        assertEquals(Rechteck(pr.x0 * 2, pr.y0 * 2, pr.x1 * 2, pr.y1 * 2), leser.gefragt.single())
    }

    @Test
    fun `real images - loading screen asks nothing, and without an answer everything stays as without a second reader`() {
        // Loading screen (dark, nothing known): nothing to read again.
        val zaehler = Attrappe { null }
        val dunkel = Bild(Bild.REF_BREITE, Bild.REF_HOEHE, IntArray(Bild.REF_BREITE * Bild.REF_HOEHE) { 0xFF101018.toInt() })
        assertEquals(Ort.UNBEKANNT, Erkenner.erkenne(dunkel, zaehler).ort)
        assertTrue(zaehler.gefragt.isEmpty())
        for (name in listOf("markt-c-minus", "bid-fenster", "bestaetigen-18", "meine-gebote-43")) {
            val b = TestBilder.lade(name)
            assertEquals(Erkenner.erkenne(b), Erkenner.erkenne(b, Attrappe { null }), name)
        }
    }

    // ---------------- Speed: cache ----------------

    @Test
    fun `cache - same result, second read comes from the cache`() {
        val b = TestBilder.lade("markt-rang-uhr-1")
        val oben = Erkenner.kartenOben(b, 300)
        val feld = Erkenner.preisFeld(oben.first())
        val neuVorher = ZiffernLeser.neuGelesen
        val trefferVorher = ZiffernLeser.treffer
        val erst = ZiffernLeser.lies(b, feld, Schrift.WEISS)
        val dann = ZiffernLeser.lies(b, feld, Schrift.WEISS)
        assertEquals(erst, dann)
        assertEquals(neuVorher + 1, ZiffernLeser.neuGelesen)
        assertEquals(trefferVorher + 1, ZiffernLeser.treffer)
        // A different pixel in the field → read again.
        val px = b.pixel.copyOf()
        px[(feld.y0 + 5) * b.breite + feld.x0 + 5] = 0xFF123456.toInt()
        ZiffernLeser.lies(Bild(b.breite, b.hoehe, px), feld, Schrift.WEISS)
        assertEquals(neuVorher + 2, ZiffernLeser.neuGelesen)
    }

    @Test
    fun `cache - whole recognition stays the same (with and without cache)`() {
        for (name in listOf("markt-rang-uhr-1", "markt-c-minus", "meine-gebote-49", "bid-fenster", "markt-toast-erfolg-1")) {
            val b = TestBilder.lade(name)
            ZiffernLeser.zwischenspeicherLeeren()
            val ohne = Erkenner.erkenne(b)
            val mit = Erkenner.erkenne(b)
            assertEquals(ohne, mit, name)
        }
    }
}
