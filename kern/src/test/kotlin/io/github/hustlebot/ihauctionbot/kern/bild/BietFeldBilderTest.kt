package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import io.github.hustlebot.ihauctionbot.kern.BERLIN
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.FilterFeld
import io.github.hustlebot.ihauctionbot.kern.KOPIE_TYPEN
import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.StoppBedingung
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.Zeile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Nov 23 (review round 3: "the bot assumes the bid field holds Current Bid + 1"): the editable bid field of the Bid window
 * ("54" next to "Current Bid: 53") – read on the real screenshots, and what the bot does with it. For a field that is NOT
 * Current Bid + 1 there is no real screenshot, so those fields are put together from real digits of real screenshots
 * (bid field and filter price fields – same font).
 */
class BietFeldBilderTest {

    private val jetzt = LocalDateTime.of(2026, 11, 23, 21, 0).atZone(BERLIN).toInstant()

    @AfterEach
    fun zurueck() {
        ZiffernLeser.vorlagenQuelle = { ZiffernVorlagen.fuer(it) }
        ZiffernLeser.zwischenspeicherLeeren()
    }

    @Test
    fun `real Bid windows - the bid field reads Current Bid + 1, nothing under the -7 band`() {
        for ((name, feld) in listOf("bid-fenster" to 19, "bid-fenster-b-ohne-buyout-1" to 54, "bid-fenster-b-ohne-buyout-2" to 54, "bid-fenster-b-ohne-buyout-3" to 54)) {
            val s = Erkenner.erkenne(TestBilder.lade(name))
            assertEquals(feld, s.bietFeld, name)
            assertEquals(feld.toString(), s.bietFeldMuster, name)
            assertEquals(s.aktuellesGebot!! + 1, s.bietFeld, "$name: Current Bid + 1")
            assertTrue(s.unlesbar.none { it.feld == "bietFeld" }, "$name: ${s.unlesbar}")
        }
        // The "-7" band lies over the field: nothing is read there (and no learning image).
        val minus7 = Erkenner.erkenne(TestBilder.lade("limit-50-status-7"))
        assertNull(minus7.bietFeld)
        assertNull(minus7.bietFeldMuster)
        assertTrue(minus7.unlesbar.none { it.feld == "bietFeld" }, "${minus7.unlesbar}")
    }

    /**
     * The real digits of the field font: on the real screenshots the same digit is at most 0.001 away from its template, the
     * nearest OTHER digit at least 0.2 ([Erkenner.FELD_SCHWELLE] = 0.03 lies in between). Left out one at a time, a digit is
     * never read as another one – so the digits not learned yet (2, 3, 6, 7) show as '?', not as a wrong number.
     */
    @Test
    fun `cross-check - a digit of the bid field that was not learned is never read as another one`() {
        val proben = listOf(
            Triple(TestBilder.lade("bid-fenster"), Erkenner.BIET_FELD, "19"),
            Triple(TestBilder.lade("bid-fenster-b-ohne-buyout-1"), Erkenner.BIET_FELD, "54"),
            Triple(TestBilder.lade("filter-fenster"), ZiffernLernen.FILTER_MIN_TEXT, "10"),
            Triple(TestBilder.lade("filter-fenster"), ZiffernLernen.FILTER_MAX_TEXT, "18"),
        )
        val alle = ZiffernVorlagen.fuer(Schrift.FELD)
        assertEquals("014589", alle.map { it.zeichen }.distinct().joinToString(""), "learned digits of the field font")
        for (weg in "014589") {
            ZiffernLeser.vorlagenQuelle = { s -> if (s == Schrift.FELD) alle.filter { it.zeichen != weg } else ZiffernVorlagen.fuer(s) }
            for ((bild, feld, text) in proben) {
                val gl = ZiffernLeser.glyphen(bild, feld, Schrift.FELD)
                assertEquals(text.length, gl.size, "$text: blobs")
                gl.forEachIndexed { i, g ->
                    val (ziffer, abstand) = ZiffernLeser.vergleiche(g, Schrift.FELD)!!
                    if (text[i] == weg) {
                        assertTrue(abstand > 0.15f, "without $weg: '${text[i]}' of $text is $ziffer at $abstand – too close")
                    } else {
                        assertEquals(text[i], ziffer, "without $weg: $text")
                        assertTrue(abstand < 0.005f, "without $weg: '${text[i]}' of $text at $abstand")
                    }
                }
            }
            // The real Bid windows: the left-out digit shows as '?', the others stay.
            assertEquals("19".map { if (it == weg) '?' else it }.joinToString(""), Erkenner.bietFeldMuster(TestBilder.lade("bid-fenster")), "without $weg")
            assertEquals("54".map { if (it == weg) '?' else it }.joinToString(""), Erkenner.bietFeldMuster(TestBilder.lade("bid-fenster-b-ohne-buyout-1")), "without $weg")
        }
    }

    // ---------------- Bid windows with another number in the field (real digits, put together) ----------------

    /** A real digit: where it is on which screenshot (x range, top edge of the digit). */
    private class Ziffer(val bild: String, val x0: Int, val x1: Int, val oben: Int)

    private val ziffern = mapOf(
        '5' to Ziffer("bid-fenster-b-ohne-buyout-1", 1520, 1535, 542),
        '4' to Ziffer("bid-fenster-b-ohne-buyout-1", 1539, 1560, 542),
        '1' to Ziffer("bid-fenster", 1517, 1524, 543),
        '9' to Ziffer("bid-fenster", 1529, 1546, 543),
        '0' to Ziffer("filter-fenster", 1040, 1059, 572),
        '8' to Ziffer("filter-fenster", 1447, 1464, 571),
    )

    /**
     * The real Bid window bid-fenster-b-ohne-buyout-1 (Current Bid 53) with [text] in the bid field, made of the real digits
     * above; 'S' = a mirrored 5 (a digit shape the bot has not learned, like 2, 3, 6, 7).
     */
    private fun mitFeld(text: String): Bild {
        val basis = TestBilder.lade("bid-fenster-b-ohne-buyout-1")
        val px = basis.pixel.copyOf()
        for (y in 524..590) for (x in 1514..1700) px[y * basis.breite + x] = 0xFFFFFFFF.toInt()
        var x = 1520
        for (c in text) {
            val z = ziffern[if (c == 'S') '5' else c]!!
            val q = TestBilder.lade(z.bild)
            val breite = z.x1 - z.x0 + 1
            for (dy in -3..28) for (dx in 0 until breite) {
                val qx = if (c == 'S') z.x1 - dx else z.x0 + dx
                px[(542 + dy) * basis.breite + x + dx] = q.pixel[(z.oben + dy) * q.breite + qx]
            }
            x += breite + 4
        }
        return Bild(basis.breite, basis.hoehe, px)
    }

    @Test
    fun `put-together fields are read like the real ones - learned digits, '?' for a shape not learned`() {
        for ((text, muster) in listOf("54" to "54", "55" to "55", "59" to "59", "540" to "540", "45" to "45", "5S" to "5?", "S4" to "?4")) {
            val s = Erkenner.erkenne(mitFeld(text))
            assertEquals(Ort.BIETEN, s.ort, text)
            assertEquals(53, s.aktuellesGebot, text)
            assertEquals(muster, s.bietFeldMuster, text)
            assertEquals(muster.takeIf { '?' !in it }?.toInt(), s.bietFeld, text)
        }
    }

    /**
     * The flow of the real Bid window test (B- row for 53, rule up to 54): filter, row, then the real Bid window with [feld]
     * in the bid field. Returns the bot's action on the first steady screenshot of the window, and the log.
     */
    private fun bidFensterMit(feld: String): Triple<Ergebnis, AuktionsBot, List<String>> {
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
        val folge = ArrayDeque(listOf(markt, filter, filter, markt, markt))
        var bild: Bildschirm? = null
        val log = ArrayList<String>()
        val bot = AuktionsBot(listOf(bMinus), StoppBedingung(), { bild ?: folge.removeFirst() }, protokoll = { log += it })
        bot.schritt(jetzt)
        bot.schritt(jetzt)
        bot.eingabeErgebnis(true)
        bot.schritt(jetzt)
        bot.schritt(jetzt.plusSeconds(7))
        assertEquals("B-", bot.filterStufe)
        val zeileGetippt = bot.schritt(jetzt.plusSeconds(7)) as Ergebnis.Handeln
        assertEquals(Punkt(1356, 374), (zeileGetippt.aktion as Aktion.Tippen).punkt, "tap row 53")
        bild = Erkenner.erkenne(mitFeld(feld))
        // First image of the window: never decided on (still opening) – then the steady one.
        assertTrue(bot.schritt(jetzt.plusMillis(7500)) is Ergebnis.Warten, feld)
        return Triple(bot.schritt(jetzt.plusMillis(7900)), bot, log)
    }

    @Test
    fun `bid field higher than Current Bid + 1 - never tap Bid, close the window with the X, never + or −`() {
        // "55"/"59": one tap on "+" or more; "540": a digit more; "?5": a digit not learned and a learned one that does not fit.
        for (feld in listOf("55", "59", "540", "S5")) {
            val (e, bot, log) = bidFensterMit(feld)
            val tipp = (e as Ergebnis.Handeln).aktion as Aktion.Tippen
            assertEquals(Punkt(2105, 108), tipp.punkt, "$feld: the red X – ${tipp.grund}")
            assertTrue(tipp.grund.startsWith("close Bid window (bid field "), "$feld: ${tipp.grund}")
            assertEquals(1, bot.bietFeldFalsch, feld)
            assertTrue(log.any { it.startsWith("Bid field shows ") && it.contains("instead of 54 SG (Current Bid 53") && it.contains("never correcting") }, "$feld: $log")
            assertTrue(log.any { it.startsWith("Row 3151-2502-38: not opening again") }, "$feld: $log")
        }
    }

    @Test
    fun `bid field Current Bid + 1, only partly readable or lower - Bid as before, and the log says so`() {
        for ((feld, text) in listOf(
            "54" to "Bid for 54 SG (bid field 54 checked)",
            "5S" to "Bid for 54 SG (bid field \"5?\" fits, not fully readable)",
            "S4" to "Bid for 54 SG (bid field \"?4\" fits, not fully readable)",
            "45" to "Bid for 54 SG (bid field 45 SG, lower)",
        )) {
            val (e, bot, log) = bidFensterMit(feld)
            val tipp = (e as Ergebnis.Handeln).aktion as Aktion.Tippen
            assertEquals(text, tipp.grund, feld)
            assertEquals(1641, tipp.punkt.x, "$feld: the Bid button")
            assertEquals(0, bot.bietFeldFalsch, feld)
            assertEquals(if (feld == "54") 1 else 0, bot.bietFeldGeprueft, feld)
            if (feld == "45") assertTrue(log.any { it.startsWith("Bid field shows 45 SG, Current Bid 53 – lower than 54 SG") }, "$log")
        }
    }
}
