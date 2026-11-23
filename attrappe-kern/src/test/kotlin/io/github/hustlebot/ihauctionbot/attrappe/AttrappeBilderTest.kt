package io.github.hustlebot.ihauctionbot.attrappe

import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import io.github.hustlebot.ihauctionbot.kern.bild.Erkenner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/** Loads the real test images on the PC. */
object PcBilder : Bilder {
    private val ordner = listOf(File("../testbilder"), File("testbilder"), File("idle-heroes-bot/testbilder")).first { it.isDirectory }
    private val cache = HashMap<String, Bild>()
    override fun lade(name: String): Bild = synchronized(cache) {
        cache.getOrPut(name) {
            val img = ImageIO.read(File(ordner, if ('.' in name) name else "$name.jpg")) ?: error("Image $name missing")
            Bild(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
    }

    val atlas: Atlas by lazy { Atlas(this) }

    fun speichern(b: Bild, name: String) {
        val img = java.awt.image.BufferedImage(b.breite, b.hoehe, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, b.breite, b.hoehe, b.pixel, 0, b.breite)
        File("build/attrappe").mkdirs()
        ImageIO.write(img, "png", File("build/attrappe/$name.png"))
    }
}

/** Nov 11: does the real recognizer read the fake game's images the way they are meant? */
class AttrappeBilderTest {
    private val bauer = Bildbauer(PcBilder.atlas)

    @Test
    fun `bidding list - rows with stats, price, remaining time, clock and hammer are read correctly`() {
        val zeilen = listOf(
            Zeilenbild(listOf(1013, 2007, 11), 15, 125),
            Zeilenbild(listOf(1026, 2014, 12), 19, 3599, Zeichenart.UHR),
            Zeilenbild(listOf(1039, 2021, 13), 20, 61, Zeichenart.HAMMER),
            Zeilenbild(listOf(1468, 2793, 24), 17, 7384),
            Zeilenbild(listOf(1555, 2580, 9), 12, 4),
            Zeilenbild(listOf(1600, 2600, 30), 18, 400),
        )
        val bild = bauer.markt(zeilen, 0, meldung = false)
        PcBilder.speichern(bild, "markt")
        val s = Erkenner.erkenne(bild)
        assertEquals(Ort.MARKT, s.ort)
        assertEquals(5, s.zeilen.size, s.zeilen.joinToString("\n") { it.text() })
        s.zeilen.zip(zeilen).forEach { (z, w) ->
            assertEquals(w.werte.joinToString("-"), z.id, z.text())
            assertEquals(w.preis, z.preis, z.text())
            assertEquals(w.restSek, z.restzeitSek, z.text())
            val erwartet = when (w.zeichen) {
                Zeichenart.UHR -> Zeichen.UHR
                Zeichenart.HAMMER -> Zeichen.HAMMER
                Zeichenart.KEINS -> Zeichen.KEINS
            }
            assertEquals(erwartet, z.zeichen, z.text())
            assertEquals('C', z.stufe)
        }
        assertEquals(null, s.meldung)
        // Scrolled: half rows at the top and bottom, still correct.
        val g = Erkenner.erkenne(bauer.markt(zeilen, 250, meldung = false))
        assertTrue(g.zeilen.isNotEmpty())
        assertTrue(g.zeilen.all { z -> zeilen.any { it.werte.joinToString("-") == z.id && it.preis == z.preis } }, g.zeilen.joinToString { it.text() })
        // With the "Bidding successful" message.
        val m = Erkenner.erkenne(bauer.markt(zeilen, 0, meldung = true))
        PcBilder.speichern(bauer.markt(zeilen, 0, meldung = true), "markt-meldung")
        assertNotNull(m.meldung)
        assertTrue(m.meldung!!.wieErfolg, "${m.meldung}")
    }

    @Test
    fun `My Biddings - counter, clock, red X in the right place`() {
        val zeilen = listOf(
            Zeilenbild(listOf(1013, 2007, 11), 15, 125, Zeichenart.UHR),
            Zeilenbild(listOf(1026, 2014, 12), 21, 900, kreuz = true),
            Zeilenbild(listOf(1039, 2021, 13), 26, 61, kreuz = true),
            Zeilenbild(listOf(1468, 2793, 24), 27, 7384, Zeichenart.UHR),
        )
        for (n in listOf(0, 7, 12, 38, 49, 50)) {
            val bild = bauer.meineGebote(zeilen, 0, n)
            if (n == 50) PcBilder.speichern(bild, "meine")
            val s = Erkenner.erkenne(bild)
            assertEquals(Ort.MEINE_GEBOTE, s.ort)
            assertEquals(n, s.laufendeGebote, "counter $n")
            assertEquals(4, s.zeilen.size)
            s.zeilen.zip(zeilen).forEach { (z, w) ->
                assertEquals(w.werte.joinToString("-"), z.id)
                assertEquals(w.preis, z.preis)
                assertEquals(w.kreuz, z.ueberbotenKreuz, z.text())
                if (w.kreuz) assertNotNull(z.kreuzPunkt)
                assertEquals(if (w.zeichen == Zeichenart.UHR) Zeichen.UHR else Zeichen.KEINS, z.zeichen)
            }
        }
    }

    @Test
    fun `Bid window, -7, filter, sorting, loading`() {
        val b = Erkenner.erkenne(bauer.bieten(18, minus7 = false))
        assertEquals(Ort.BIETEN, b.ort)
        assertEquals(18, b.aktuellesGebot)
        assertNotNull(b.knopf(io.github.hustlebot.ihauctionbot.kern.Knopf.BIETEN))
        assertEquals(null, b.meldung)
        // Nov 11: all digits are there – every price is readable (the bot only bids with a readable "Current Bid").
        for (preis in listOf(10, 14, 17, 19, 20, 21, 23, 47, 59, 60, 99, 109)) {
            assertEquals(preis, Erkenner.erkenne(bauer.bieten(preis, minus7 = false)).aktuellesGebot, "Current Bid $preis")
        }
        val m = Erkenner.erkenne(bauer.bieten(16, minus7 = true))
        assertTrue(m.meldung?.kurz == true && m.meldung!!.wieLimit, "${m.meldung}")
        val f = Erkenner.erkenne(bauer.filter("10", "20"))
        assertEquals(Ort.FILTER, f.ort)
        assertEquals(2, f.filterMinStellen)
        assertEquals(2, f.filterMaxStellen)
        assertEquals(8, Erkenner.erkenne(bauer.filter("10", "99999999")).filterMaxStellen)
        assertEquals(Ort.SORTIEREN, Erkenner.erkenne(bauer.sortieren()).ort)
        val pw = Erkenner.erkenne(bauer.passwort(0))
        PcBilder.speichern(bauer.passwort(4), "passwort")
        assertEquals(Ort.ZWEITPASSWORT, pw.ort)
        assertEquals(Ort.ZWEITPASSWORT, Erkenner.erkenne(bauer.passwort(null)).ort)
        // Nov 11: still the secondary password window with a typed password (dots in the field).
        for (n in 1..8) assertEquals(Ort.ZWEITPASSWORT, Erkenner.erkenne(bauer.passwort(n)).ort, "$n chars")
        val ok = pw.knopf(io.github.hustlebot.ihauctionbot.kern.Knopf.PASSWORT_OK)!!
        assertTrue(ok.x in Bildbauer.PASSWORT_OK.x0..Bildbauer.PASSWORT_OK.x1 && ok.y in Bildbauer.PASSWORT_OK.y0..Bildbauer.PASSWORT_OK.y1, "$ok")
        val feld = pw.knopf(io.github.hustlebot.ihauctionbot.kern.Knopf.PASSWORT_FELD)!!
        assertTrue(feld.x in Bildbauer.PASSWORT_FELD.x0..Bildbauer.PASSWORT_FELD.x1 && feld.y in Bildbauer.PASSWORT_FELD.y0..Bildbauer.PASSWORT_FELD.y1, "$feld")
        assertEquals(Ort.LADEN, Erkenner.erkenne(bauer.laden()).ort)
    }
}
