package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.UnlesbareZahl
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Checks recognition on all real screenshots (values read off by hand, see TestBilder.kt). */
class ErkennerTest {

    @TestFactory
    fun `every screenshot is recognized correctly`(): List<DynamicTest> = Ablesung.alle.map { e ->
        DynamicTest.dynamicTest(e.name) {
            val s = Erkenner.erkenne(TestBilder.lade(e.name))
            assertEquals(e.ort, s.ort, "${e.name}: place")
            e.sg?.let { assertEquals(it, s.sg, "${e.name}: SG") }
            e.zaehler?.let { assertEquals(it, s.laufendeGebote, "${e.name}: Bidding Items") }
            e.aktuellesGebot?.let { assertEquals(it, s.aktuellesGebot, "${e.name}: Current Bid") }
            e.bestaetigen?.let { assertEquals(it, s.bestaetigenPreis, "${e.name}: Starry Gems spent") }
            e.bietFeld?.let { assertEquals(it, s.bietFeld, "${e.name}: bid field") }
            when (e.meldung) {
                true -> {
                    assertNotNull(s.meldung, "${e.name}: message missing")
                    assertEquals(e.meldungKurz, s.meldung!!.kurz, "${e.name}: message short (limit) / long")
                }
                false -> assertNull(s.meldung, "${e.name}: no message expected")
                null -> Unit
            }
            e.filterGewaehlt?.let { w ->
                assertEquals(w, s.filterFelder.filter { it.gewaehlt }.map { it.stufe }, "${e.name}: selected filters")
            }
            if (e.zeilen.isNotEmpty() || e.ort == Ort.MARKT || e.ort == Ort.MEINE_GEBOTE) {
                assertEquals(e.zeilen.size, s.zeilen.size, "${e.name}: number of rows ${s.zeilen.map { it.oben }}")
                e.zeilen.zip(s.zeilen).forEachIndexed { i, (w, z) ->
                    val wo = "${e.name} row ${i + 1}"
                    assertTrue(kotlin.math.abs(w.oben - z.oben) <= 3, "$wo: top ${z.oben} instead of ${w.oben}")
                    assertEquals(w.verdeckt, z.verdeckt, "$wo: covered (message on top)")
                    if (w.verdeckt) {
                        // Nothing is read under the message – the bot uses what it remembered.
                        assertNull(z.preis, "$wo: covered, no price")
                        assertNull(z.id, "$wo: covered, no ID")
                        return@forEachIndexed
                    }
                    assertEquals(w.preis, z.preis, "$wo: price")
                    assertEquals(w.sofort, z.sofortPreis, "$wo: buyout")
                    assertEquals(w.zeit != null, z.hatRestzeit, "$wo: remaining time there?")
                    if (w.zeitUnlesbar) {
                        assertNull(z.restzeitSek, "$wo: remaining time counts as not readable (yet) – do not guess")
                    } else {
                        assertEquals(w.zeit?.let { zeitInSek(it) }, z.restzeitSek, "$wo: remaining time")
                    }
                    assertEquals(w.werte, z.werte, "$wo: stats")
                    assertEquals(w.zeichen, z.zeichen, "$wo: icon")
                    assertEquals(w.kreuz, z.ueberbotenKreuz, "$wo: red cross")
                    w.stufe?.let { assertEquals(it, z.stufe, "$wo: rank badge") }
                }
            }
        }
    }

    private fun zeitInSek(t: String) = t.substring(0, 2).toInt() * 3600 + t.substring(2, 4).toInt() * 60 + t.substring(4, 6).toInt()

    /**
     * Nov 12: on the phone it said "My Biddings: counter not readable – counting by myself (6 own bids)". Here it says
     * "Bidding Items: 41" – the bot must read 41 (not count by itself). Per row: "Bidding Price" = preis,
     * "Buyout Price" = sofort, all four with a clock (I am in front), no red X.
     */
    @Test
    fun `My Biddings - Bidding Items 41 read, Bidding Price and Buyout Price not swapped`() {
        ZiffernLeser.zwischenspeicherLeeren()
        val s = Erkenner.erkenne(TestBilder.lade("meine-gebote-41"))
        assertEquals(Ort.MEINE_GEBOTE, s.ort)
        assertEquals(41, s.laufendeGebote, "Bidding Items")
        assertEquals(listOf(50, 50, 51, 51), s.zeilen.map { it.preis }, "Bidding Price")
        assertEquals(listOf(93, 103, 98, 95), s.zeilen.map { it.sofortPreis }, "Buyout Price")
        assertEquals(List(4) { Zeichen.UHR }, s.zeilen.map { it.zeichen }, "clock = own bid in front")
        assertTrue(s.zeilen.none { it.ueberbotenKreuz }, "no red X")
        assertEquals(emptyList<UnlesbareZahl>(), s.unlesbar, "nothing unreadable")
    }

    /**
     * Nov 12: bidding list with the expanded IH panel (see [ZeilenNebenIhFeld]). The image counts as unknown
     * (tap nothing) – but the rows to the right of it are read correctly: top "Bidding Price" (preis), below it
     * "Buyout Price" (sofort, "--" = null), never swapped. The images are NOT learned.
     */
    @Test
    fun `rows next to the IH panel - Bidding Price and Buyout Price never swapped`() {
        for ((name, erwartet) in ZeilenNebenIhFeld.alle) {
            ZiffernLeser.zwischenspeicherLeeren()
            val b = TestBilder.lade(name)
            assertEquals(Ort.UNBEKANNT, Erkenner.erkenne(b).ort, "$name: IH panel open – tap nothing")
            val oben = Erkenner.kartenOben(b, Erkenner.LISTE_OBEN_MARKT)
            assertEquals(erwartet.map { it.oben }, oben, "$name: rows")
            for ((w, t) in erwartet.zip(oben)) {
                val z = Erkenner.zeile(b, t)
                val wo = "$name row $t"
                assertEquals(w.preis, z.preis, "$wo: Bidding Price")
                assertEquals(w.sofort, z.sofortPreis, "$wo: Buyout Price")
                assertEquals(w.zeit?.let { zeitInSek(it) }, z.restzeitSek, "$wo: remaining time")
                assertEquals(w.werte, z.werte, "$wo: stats")
                assertEquals(Zeichen.KEINS, z.zeichen, "$wo: no own bid")
                assertEquals(false, z.ueberbotenKreuz, "$wo: no red X")
            }
        }
    }

    // Nov 9: sort order "Awakened Tier ↑", price fields in the filter, button in the sort window.
    @Test
    fun `label of the sort button`() {
        for (n in listOf("markt-c-minus", "markt-kurz-vor-ende", "markt-nach-gebot", "listen-ende", "toast-verloren")) {
            assertEquals(Sortierung.RANG, Erkenner.erkenne(TestBilder.lade(n)).sortierung, "$n: Awakened Tier")
        }
        assertEquals(Sortierung.PREIS, Erkenner.erkenne(TestBilder.lade("markt-unsortiert")).sortierung, "markt-unsortiert: Price")
    }

    @Test
    fun `filter window - price fields and their digits`() {
        val s = Erkenner.erkenne(TestBilder.lade("filter-fenster"))
        assertEquals(2, s.filterMinStellen, "left says 10")
        assertEquals(2, s.filterMaxStellen, "right says 18")
        assertNotNull(s.knopf(Knopf.FILTER_MIN))
        assertNotNull(s.knopf(Knopf.FILTER_MAX))
        assertNotNull(Erkenner.erkenne(TestBilder.lade("sortier-fenster")).knopf(Knopf.SORT_RANG_AUF))
    }

    // Nov 9: screenshot comes in portrait – still recognize it correctly, tap points in landscape.
    @Test
    fun `image rotated to portrait is recognized in both directions`() {
        val quer = TestBilder.lade("markt-c-minus")
        val normal = Erkenner.erkenne(quer)
        for (d in listOf(90, 270)) {
            val hoch = quer.gedreht(d)
            assertEquals(quer.hoehe, hoch.breite)
            val e = Ausrichtung.erkenne(hoch)
            assertEquals(Ort.MARKT, e.bildschirm.ort, "rotated by $d")
            assertEquals((360 - d) % 360, e.drehung, "rotate back by ${(360 - d) % 360}")
            assertEquals(normal.zeilen.map { it.preis }, e.bildschirm.zeilen.map { it.preis })
            assertEquals(normal.knopf(Knopf.FILTER), e.bildschirm.knopf(Knopf.FILTER), "tap points apply to landscape")
        }
        assertEquals(0, Ausrichtung.erkenne(quer).drehung, "landscape stays landscape")
    }

    @Test
    fun `rotating four times by 90 degrees gives the same image`() {
        val b = TestBilder.lade("filter-fenster")
        val r = b.gedreht(90).gedreht(90).gedreht(90).gedreht(90)
        assertTrue(b.pixel.contentEquals(r.pixel))
    }

    /**
     * Nov 9 (field test 1.7): the Bid window is recognized by fixed parts (red X, title bar,
     * Bid button) – NOT by the Buyout button (gray when there is no buyout). The Bid button is searched for.
     */
    @Test
    fun `Bid window - recognized with and without buyout, Bid button found`() {
        for (n in listOf("bid-fenster", "limit-50-status-7", "bid-fenster-b-ohne-buyout-1", "bid-fenster-b-ohne-buyout-2", "bid-fenster-b-ohne-buyout-3")) {
            val s = Erkenner.erkenne(TestBilder.lade(n))
            assertEquals(Ort.BIETEN, s.ort, n)
            val bid = s.knopf(Knopf.BIETEN)
            assertNotNull(bid, "$n: Bid button")
            assertEquals(1641, bid!!.x, n)
            assertTrue(bid.y in 637..726, "$n: Bid button y=${bid.y} is inside the yellow button")
            assertEquals(Punkt(2105, 108), s.knopf(Knopf.SCHLIESSEN), n)
        }
        // The red X with the title bar alone is not enough: confirm/password windows darken the Bid window.
        for (n in listOf("bestaetigen-17", "bestaetigen-18")) assertEquals(Ort.BESTAETIGEN, Erkenner.erkenne(TestBilder.lade(n)).ort, n)
    }

    @Test
    fun `Bid window - Buyout button gray or gone changes nothing, Bid button gone means no Bid button`() {
        val b = TestBilder.lade("bid-fenster")
        fun uebermalt(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): Bild {
            val px = b.pixel.copyOf()
            for (y in y0 until y1) for (x in x0 until x1) px[y * b.breite + x] = argb
            return Bild(b.breite, b.hoehe, px)
        }
        val grau = uebermalt(1480, 880, 1800, 990, 0xFFB3B3B3.toInt())
        assertEquals(Ort.BIETEN, Erkenner.erkenne(grau).ort, "Buyout gray")
        assertNotNull(Erkenner.erkenne(grau).knopf(Knopf.BIETEN))
        val ohneBid = uebermalt(1480, 620, 1800, 740, 0xFFE2CEAD.toInt()) // beige like the area
        val s = Erkenner.erkenne(ohneBid)
        assertEquals(Ort.BIETEN, s.ort, "window stays recognized")
        assertNull(s.knopf(Knopf.BIETEN), "no yellow button, no Bid button → the bot does not tap there")
        // Bidding list, filter and sort windows are never a Bid window.
        for (n in listOf("markt-c-minus", "filter-fenster", "sortier-fenster", "meine-gebote-49")) {
            assertTrue(Erkenner.erkenne(TestBilder.lade(n)).ort != Ort.BIETEN, n)
        }
    }

    /**
     * Nov 9 (field test 1.8: "confirm shows 8 SG" with a real 59): if a digit is unknown, the rest must NOT be
     * read alone ("8" instead of "59") – then the whole amount is "not readable".
     */
    @Test
    fun `confirm amount - one unknown digit makes the whole amount unreadable`() {
        val b = TestBilder.lade("bestaetigen-18")
        assertEquals(18, Erkenner.bestaetigenPreis(b))
        val gl = ZiffernLeser.glyphen(b, Erkenner.BESTAETIGEN_FELD, Schrift.BRAUN, minHoehe = 16)
        val eins = gl[gl.size - 2] // the "1" of "18"
        val acht = gl.last()
        // Replace the "1" with a ring (like a "0" the bot has never seen).
        val px = b.pixel.copyOf()
        val hinten = 0xFFE2CEAD.toInt()
        val braun = 0xFF6E321E.toInt()
        val x0 = acht.x0 - 4 - acht.breite
        val x1 = acht.x0 - 5
        for (y in eins.y0 - 2..eins.y1 + 2) for (x in x0 - 2..x1 + 2) px[y * b.breite + x] = hinten
        for (y in eins.y0..eins.y1) for (x in x0..x1) {
            val rand = x - x0 < 4 || x1 - x < 4 || y - eins.y0 < 4 || eins.y1 - y < 4
            if (rand) px[y * b.breite + x] = braun
        }
        val bild = Bild(b.breite, b.hoehe, px)
        assertEquals(gl.size, ZiffernLeser.glyphen(bild, Erkenner.BESTAETIGEN_FELD, Schrift.BRAUN, minHoehe = 16).size, "ring = its own digit")
        assertNull(Erkenner.bestaetigenPreis(bild), "not 8 – but unreadable")
        assertNull(Erkenner.erkenne(bild).bestaetigenPreis)
    }

    @Test
    fun `expanded IH panel in the image - image counts as unknown (bot does not tap)`() {
        // Nov 12: plus two new images from the phone with the expanded IH panel.
        val mitFeld = listOf("markt-mit-ih-feld", "markt-ih-feld-14-kopien", "markt-ih-feld-61-kopien")
        for (n in mitFeld) assertTrue(Erkenner.ihFeldImBild(TestBilder.lade(n).aufReferenz()), n)
        // Nov 11: the loading screen is so dark that it looks like the gray IH panel. So it is recognized
        // as "game loading" BEFORE that – the bot taps nothing there either.
        assertEquals(Ort.LADEN, Erkenner.erkenne(TestBilder.lade("laden-stern")).ort)
        for (e in Ablesung.alle.filter { it.name !in mitFeld && it.name != "laden-stern" }) {
            assertTrue(!Erkenner.ihFeldImBild(TestBilder.lade(e.name).aufReferenz()), e.name)
        }
    }

    @Test
    fun `no real image is taken for the secondary password window`() {
        for (e in Ablesung.alle) {
            val ort = Erkenner.erkenne(TestBilder.lade(e.name)).ort
            assertTrue(ort != Ort.ZWEITPASSWORT, e.name)
            assertTrue(ort != Ort.VERBINDUNG_WEG, "${e.name}: not 'connection lost'")
        }
    }

    @Test
    fun `unknown image size is converted`() {
        val b = TestBilder.lade("markt-c-minus")
        // Shrink to half size (like another phone) – place and prices must still be right.
        val k = Bild(b.breite / 2, b.hoehe / 2, IntArray(b.breite / 2 * (b.hoehe / 2)) { i ->
            val x = i % (b.breite / 2)
            val y = i / (b.breite / 2)
            b.argb(x * 2, y * 2)
        })
        val s = Erkenner.erkenne(k)
        assertEquals(Ort.MARKT, s.ort)
        assertEquals(5, s.zeilen.size)
        // Tap points are converted to the small size.
        assertTrue(s.zeilen.first().tippen.x < 700)
    }

    /**
     * Nov 9 (field test 1.9): a number that is there but cannot be read (here: a digit
     * painted over) comes out as "unreadable" with its position and copy – for the learning images in the error report.
     * "--" (no buyout) and numbers that were read do not show up there.
     */
    /**
     * Nov 11: the red X at the top right of outbid rows in My Biddings – its center is found (that is where the bot
     * taps to dismiss), even if the image has a different size. Rows with a clock have none.
     */
    @Test
    fun `red X in My Biddings - center found, also at half image size`() {
        val b = TestBilder.lade("meine-gebote-50-kreuze")
        val s = Erkenner.erkenne(b)
        assertEquals(4, s.zeilen.count { it.kreuzPunkt != null })
        for (z in s.zeilen) {
            val k = z.kreuzPunkt!!
            assertTrue(k.x in 2285..2305, "x ${k.x}")
            assertTrue(k.y - z.oben in 8..28, "y ${k.y} at top edge ${z.oben}")
        }
        assertTrue(Erkenner.erkenne(TestBilder.lade("meine-gebote-50-gemischt")).zeilen.drop(1).all { it.kreuzPunkt == null })
        val halb = Bild(b.breite / 2, b.hoehe / 2, IntArray(b.breite / 2 * (b.hoehe / 2)) { i ->
            b.argb((i % (b.breite / 2)) * 2, (i / (b.breite / 2)) * 2)
        })
        val h = Erkenner.erkenne(halb)
        assertEquals(Ort.MEINE_GEBOTE, h.ort)
        val k = h.zeilen.first().kreuzPunkt
        assertNotNull(k)
        assertTrue(kotlin.math.abs(k!!.x - s.zeilen.first().kreuzPunkt!!.x / 2) <= 3, "$k")
        assertTrue(kotlin.math.abs(k.y - s.zeilen.first().kreuzPunkt!!.y / 2) <= 3, "$k")
    }

    @Test
    fun `unreadable numbers are reported for learning images`() {
        val b = TestBilder.lade("markt-rang-gebote")
        assertTrue(Erkenner.erkenne(b).unlesbar.isEmpty(), "everything readable")
        val px = b.pixel.copyOf()
        // Replace the "7" in the price 47 of the third row with a white block (not a digit).
        for (y in 709..730) for (x in 1880..1893) px[y * b.breite + x] = 0xFFFFFFFF.toInt()
        val s = Erkenner.erkenne(Bild(b.breite, b.hoehe, px))
        assertNull(s.zeilen[2].preis)
        val u = s.unlesbar.single()
        assertEquals("preis", u.feld)
        assertEquals("z676_3074-3169-24", u.kontext)
        val r = Erkenner.preisFeld(676)
        assertEquals(listOf(r.x0, r.y0, r.x1, r.y1), listOf(u.x0, u.y0, u.x1, u.y1))
        // No buyout ("--") is not an unreadable number.
        assertTrue(Erkenner.erkenne(TestBilder.lade("markt-unsortiert")).unlesbar.none { it.feld == "sofort" })
    }
}
