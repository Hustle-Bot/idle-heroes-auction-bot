package io.github.hustlebot.ihauctionbot.kern.bericht

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.StoppGrund
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nov 11: flight recorder – every run recorded (images + what the bot did). */
class FlugschreiberTest {

    private fun e(ms: Long, bytes: Long = 100_000, bild: Boolean = true) =
        FlugEintrag(ms, if (bild) FlugschreiberNamen.bildBasis(ms) else null, if (bild) bytes else 0, "MARKT", "WARTEN")

    @Test
    fun `ring - only the last 3 minutes, newest win, order old to new`() {
        val alle = (0L..400_000L step 400).map { e(it) }
        val b = FlugschreiberRegeln.behalten(alle)
        assertEquals(400_000L, b.last().zeitMs)
        assertTrue(b.first().zeitMs >= 400_000L - FlugschreiberRegeln.MAX_ALTER_MS)
        assertEquals(b.sortedBy { it.zeitMs }, b)
        // 40 MB limit: at 1 MB per image at most 40 images.
        val gross = (0L..60_000L step 400).map { e(it, bytes = 1024L * 1024) }
        val g = FlugschreiberRegeln.behalten(gross)
        assertEquals(40, g.size)
        assertEquals(60_000L, g.last().zeitMs)
    }

    @Test
    fun `report - short only the last minute up to 8 MB, otherwise 3 minutes up to 25 MB, a further limit only the list`() {
        val alle = (0L..180_000L step 400).map { e(it, bytes = 100_000) }
        val kurz = FlugschreiberRegeln.fuerBericht(alle, kurz = true)
        assertTrue(kurz.first().zeitMs >= 180_000L - FlugschreiberRegeln.KURZ_ALTER_MS)
        assertTrue(kurz.sumOf { it.bytes } <= FlugschreiberRegeln.KURZ_MAX_BYTES)
        val lang = FlugschreiberRegeln.fuerBericht(alle, kurz = false)
        assertEquals(alle.size, lang.size, "all lines of the 3 minutes")
        assertTrue(lang.sumOf { it.bytes } <= FlugschreiberRegeln.MAX_BERICHT_BYTES)
        assertEquals(180_000L, lang.last { it.datei != null }.zeitMs, "the newest images")
        assertTrue(lang.first().datei == null, "the oldest only as a line")
        assertEquals(lang, FlugschreiberRegeln.fuerBericht(alle, kurz = false, grenze = Long.MAX_VALUE))
        // A further limit: only what fits – 0: only the list.
        val wenig = FlugschreiberRegeln.fuerBericht(alle, kurz = false, grenze = 1_000_000)
        assertTrue(wenig.sumOf { it.bytes } <= 1_000_000)
        val nichts = FlugschreiberRegeln.fuerBericht(alle, kurz = false, grenze = 0)
        assertTrue(nichts.all { it.datei == null && it.bytes == 0L })
        assertEquals(alle.size, nichts.size)
    }

    @Test
    fun `names - only the CSV in the report folder, the images in the subfolder`() {
        assertEquals("f_000012750", FlugschreiberNamen.bildBasis(12_750))
        assertTrue(FlugschreiberNamen.gehoertDazu(FlugschreiberNamen.CSV))
        assertFalse(FlugschreiberNamen.gehoertDazu("f_000012750.jpg"), "images only in the subfolder")
        assertTrue(FlugschreiberNamen.istBildDatei("f_000012750.jpg"))
        assertTrue(FlugschreiberNamen.istBildDatei("f_000012750.json"))
        for (falsch in listOf("../f_000012750.jpg", "f_12750.jpg", "f_000012750.png", "geheim.properties")) {
            assertFalse(FlugschreiberNamen.istBildDatei(falsch), falsch)
            assertFalse(FlugschreiberNamen.gehoertDazu(falsch), falsch)
        }
    }

    @Test
    fun `CSV - header, lines old to new, comma and quotes safe`() {
        val csv = FlugschreiberRegeln.csv(
            listOf(
                FlugEintrag(800, null, 0, "ZWEITPASSWORT", "WARTEN", grund = "privacy: no image"),
                FlugEintrag(400, "f_000000400", 10, "MARKT", "TIPPEN", 100, 200, 200, 400, "row \"B-\", 61 SG\nnew"),
            ),
        )
        val zeilen = csv.trimEnd().lines()
        assertEquals(FlugschreiberRegeln.CSV_KOPF, zeilen[0])
        assertEquals("400,f_000000400.jpg,MARKT,TIPPEN,100,200,200,400,\"row \"\"B-\"\", 61 SG new\"", zeilen[1])
        assertEquals("800,,ZWEITPASSWORT,WARTEN,,,,,privacy: no image", zeilen[2])
    }

    @Test
    fun `line - points on the screen and in reference pixels, swipe with target, never a password`() {
        val halb = Bildschirm(Ort.MARKT, bildBreite = 1356, bildHoehe = 610)
        val t = FlugschreiberRegeln.zeile(1000, "f_000001000", 5, halb, Ergebnis.Handeln(Aktion.Tippen(Punkt(678, 305), "bid on row 1"), 450))
        assertEquals("TIPPEN", t.aktion)
        assertEquals(678, t.x)
        assertEquals(1356, t.refX)
        assertEquals(610, t.refY)
        assertTrue(t.grund.contains("bid on row 1") && t.grund.contains("450 ms"))

        val w = FlugschreiberRegeln.zeile(
            2000, null, 0, halb,
            Ergebnis.Handeln(Aktion.Wischen(Punkt(678, 540), Punkt(678, 260), 900, "further down", haltenMs = 250), 1250),
        )
        assertEquals("WISCHEN", w.aktion)
        assertTrue(w.grund.contains("678/260 (ref 1356/520)"), w.grund)

        val pw = FlugschreiberRegeln.zeile(3000, null, 0, Bildschirm(Ort.ZWEITPASSWORT), Ergebnis.Handeln(Aktion.PasswortEingeben(Punkt(1356, 630), "enter secondary password"), 300))
        assertEquals("ZWEITPASSWORT", pw.aktion)
        assertNull(pw.datei)

        val warten = FlugschreiberRegeln.zeile(4000, null, 0, null, Ergebnis.Warten("message over the list", 300))
        assertEquals("NICHT_GELESEN", warten.ort)
        assertEquals("message over the list · 300 ms", warten.grund)
        assertEquals("STOPP", FlugschreiberRegeln.zeile(5000, null, 0, null, Ergebnis.Fertig(StoppGrund.ZEIT_ERREICHT)).aktion)
        assertEquals("KEIN_ERGEBNIS", FlugschreiberRegeln.zeile(6000, null, 0, null, null).aktion)
    }

    @Test
    fun `events - at most once in 10 minutes, ones that did not start do not count`() {
        val b = FlugEreignisBremse()
        assertTrue(b.erlaubt(0))
        assertFalse(b.erlaubt(60_000))
        assertFalse(b.erlaubt(599_999))
        assertTrue(b.erlaubt(600_000))
        b.zuruecknehmen(600_000)
        assertTrue(b.erlaubt(600_001), "the last one did not even start")
    }
}
