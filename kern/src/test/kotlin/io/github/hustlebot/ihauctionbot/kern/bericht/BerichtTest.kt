package io.github.hustlebot.ihauctionbot.kern.bericht

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.Meldung
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.Zeile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Nov 9: error reports – names, JSON, privacy, detecting a stuck bot. */
class BerichtTest {

    @Test
    fun `folder and file names, only report files`() {
        val o = BerichtNamen.ordner(LocalDateTime.of(2026, 11, 9, 17, 53, 20))
        assertEquals("Bericht-2026-11-09_17-53-20", o)
        assertTrue(BerichtNamen.istBerichtOrdner(o))
        assertFalse(BerichtNamen.istBerichtOrdner("../Bericht-2026-11-09_17-53-20"))
        assertFalse(BerichtNamen.istBerichtOrdner("Other-2026-11-09_17-53-20"))
        val b = BerichtNamen.bildBasis(3, LocalTime.of(17, 53, 7, 120_000_000))
        assertEquals("bild_03_175307_120", b)
        assertTrue(BerichtNamen.gehoertZumBericht("$b.jpg"))
        assertTrue(BerichtNamen.gehoertZumBericht("$b.json"))
        assertTrue(BerichtNamen.gehoertZumBericht("info.txt"))
        assertTrue(BerichtNamen.gehoertZumBericht("protokoll.txt"))
        for (fremd in listOf("geheim.xml", "einstellungen.xml", "protokoll.alt.txt", "../info.txt", "bild_03_175307_120.png", "bild.jpg")) {
            assertFalse(BerichtNamen.gehoertZumBericht(fremd), fremd)
        }
    }

    @Test
    fun `JSON for recognition - with rows, buttons and special characters clean`() {
        val s = Bildschirm(
            Ort.BIETEN, sg = 5742, aktuellesGebot = 53, meldung = Meldung(1100),
            knoepfe = mapOf(Knopf.BIETEN to Punkt(1641, 681), Knopf.SCHLIESSEN to Punkt(2105, 108)),
            zeilen = listOf(Zeile(314, 53, null, 120, true, listOf(3151, null, 38), Zeichen.UHR, false, 'B', Punkt(1356, 374))),
        )
        val j = BerichtJson.bildschirm(s, LocalTime.of(17, 53, 7), "Bid for 54 SG \"fast\"\n")
        assertTrue(j.startsWith("{") && j.endsWith("}"))
        assertTrue(j.contains("\"ort\":\"BIETEN\""))
        assertTrue(j.contains("\"aktuellesGebot\":53"))
        assertTrue(j.contains("\"BIETEN\":[1641,681]"))
        assertTrue(j.contains("\"meldung\":{\"breite\":1100,\"kurz\":true}"))
        assertTrue(j.contains("\"werte\":[3151,null,38]"))
        assertTrue(j.contains("\"zeichen\":\"UHR\""))
        assertTrue(j.contains("\\\"fast\\\"\\n"), "quotes and line break escaped")
        assertEquals(j.count { it == '{' }, j.count { it == '}' })
    }

    @Test
    fun `privacy - never the password window, never shortly after typing it`() {
        assertFalse(BerichtDatenschutz.bildErlaubt(Ort.ZWEITPASSWORT, 1_000_000, 0))
        assertFalse(BerichtDatenschutz.bildErlaubt(Ort.MARKT, 1_000, 1_000 + BerichtDatenschutz.NACH_PASSWORT_MS))
        assertTrue(BerichtDatenschutz.bildErlaubt(Ort.MARKT, 70_000, 60_000))
        assertTrue(BerichtDatenschutz.bildErlaubt(Ort.BIETEN, 70_000, 0))
    }

    @Test
    fun `stuck - 45 seconds without an action on the same screen, at most every 10 minutes`() {
        val h = Haenger()
        h.start(0)
        assertFalse(h.pruefe(1_000, Ort.BIETEN, false))
        assertFalse(h.pruefe(40_000, Ort.BIETEN, false))
        assertTrue(h.pruefe(47_000, Ort.BIETEN, false), "45 s without an action")
        assertFalse(h.pruefe(100_000, Ort.BIETEN, false), "not again right away")
        assertFalse(h.pruefe(300_000, Ort.BIETEN, false), "not again within 10 minutes")
        assertTrue(h.pruefe(700_000, Ort.BIETEN, false), "again after 10 minutes")
    }

    @Test
    fun `stuck - an action, a different screen or waiting on purpose do not count`() {
        val h = Haenger()
        h.start(0)
        h.pruefe(1_000, Ort.MARKT, false)
        h.gehandelt(30_000)
        assertFalse(h.pruefe(60_000, Ort.MARKT, false), "tapped 30 s ago")
        assertFalse(h.pruefe(70_000, Ort.MEINE_GEBOTE, false), "different screen → clock starts over")
        assertFalse(h.pruefe(110_000, Ort.MEINE_GEBOTE, true), "waiting on purpose (e.g. 50 bids)")
        assertFalse(h.pruefe(150_000, Ort.MEINE_GEBOTE, false), "only 40 s since the waiting on purpose")
        assertTrue(h.pruefe(160_000, Ort.MEINE_GEBOTE, false))
    }

    @Test
    fun `image buffer - only the last ones`() {
        val p = BildPuffer<Int>(3)
        for (i in 1..5) p.dazu(i)
        assertEquals(listOf(3, 4, 5), p.alle())
        assertEquals(3, p.groesse)
        p.leeren()
        assertEquals(0, p.groesse)
    }

    @Test
    fun `info - settings without secrets, only yes or no`() {
        fun info(zusatz: List<String>) = BerichtText.info(
            zeit = LocalDateTime.of(2026, 11, 9, 17, 53, 20), grund = "by hand", version = "1.8", geraet = "motorola edge 60 fusion",
            android = "15", regeln = listOf(Suchregel("B-", 10, 60, maxRestzeitMin = 5)), zielKopien = 20, endeUm = LocalTime.of(7, 0),
            sgBudget = 2000, bildschirmAnlassen = true, bildschirmDunkel = false, hatZweitpasswort = true,
            stand = "Running · 3 of 20 copies", zusatz = zusatz,
        )
        val t = info(emptyList())
        assertTrue(t.contains("App version: 1.8"))
        assertTrue(t.contains("Rule 1: B-: filter 10–60"))
        assertTrue(t.contains("SG budget: 2000 SG"))
        assertTrue(t.contains("Secondary password saved: yes"))
        assertTrue(t.trimEnd().endsWith("Status: Running · 3 of 20 copies"))
        // Nov 22: extra lines of the build go below the secondary password, in the "Settings:" part.
        val mit = info(listOf("Extra saved: no"))
        assertTrue(mit.contains("  Secondary password saved: yes\n  Extra saved: no\n\nStatus:"), mit)
    }

    // Nov 9 (field test 1.9): learning images of unreadable numbers in the subfolder lernziffern/.
    @Test
    fun `learning images - safe names in the subfolder, nothing else`() {
        val n = BerichtNamen.lernBild(LocalTime.of(17, 53, 7, 120_000_000), "preis", "z676_3074-3169-24", null)
        assertEquals("lernziffern/175307_120_preis_z676_3074-3169-24.png", n)
        assertTrue(BerichtNamen.gehoertZumBericht(n))
        val mitErwartung = BerichtNamen.lernBild(LocalTime.of(9, 0), "aktuellesGebot", "bid-fenster", "47")
        assertTrue(mitErwartung.endsWith("_aktuellesGebot_bid-fenster_erwartet-47.png"), mitErwartung)
        // Dangerous characters are replaced – never a path upwards or into another folder.
        val boese = BerichtNamen.lernBild(LocalTime.of(9, 0), "../x", "a/../../b c", "1/2")
        assertTrue(BerichtNamen.gehoertZumBericht(boese), boese)
        assertFalse(boese.removePrefix("lernziffern/").contains('/'), boese)
        assertFalse(boese.contains(".."), boese)
        for (falsch in listOf("lernziffern/../info.txt", "lernziffern/x.jpg", "andere/x.png", "lernziffern/a/b.png", "lernziffern/..png")) {
            assertFalse(BerichtNamen.gehoertZumBericht(falsch), falsch)
        }
    }

    @Test
    fun `saved screenshots (no longer in the gallery) belong to the report, nothing else in their folder`() {
        val n = BerichtNamen.gespeichertesBild("buyout", java.time.LocalDateTime.of(2026, 11, 22, 17, 45, 1, 250_000_000))
        assertEquals("buyout_20261122_174501_250.png", n)
        assertTrue(BerichtNamen.istGespeichertesBild(n))
        assertTrue(BerichtNamen.gehoertZumBericht("gespeichert/$n"))
        assertTrue(BerichtNamen.gespeichertesBild("../x", java.time.LocalDateTime.of(2026, 1, 2, 3, 4)).startsWith("unsicher_"))
        assertFalse(BerichtNamen.gehoertZumBericht(n), "only in its subfolder")
        for (falsch in listOf("gespeichert/../info.txt", "gespeichert/x.png", "gespeichert/geheim_20261122_174501_250.png",
            "gespeichert/a/unsicher_20261122_174501_250.png", "gespeichert/unsicher_20261122_174501_250.jpg")) {
            assertFalse(BerichtNamen.gehoertZumBericht(falsch), falsch)
        }
    }

    @Test
    fun `JSON for recognition - covered rows and unreadable numbers`() {
        val s = Bildschirm(
            Ort.MARKT,
            zeilen = listOf(Zeile(495, null, null, null, false, listOf(null, null, null), Zeichen.KEINS, false, null, Punkt(1356, 555), verdeckt = true)),
            meldung = Meldung(1224, 537, 681),
            unlesbar = listOf(io.github.hustlebot.ihauctionbot.kern.UnlesbareZahl("preis", 1856, 700, 2010, 744, "z676_3074-3169-24")),
        )
        val j = BerichtJson.bildschirm(s, LocalTime.of(12, 0), null)
        assertTrue(j.contains("\"verdeckt\":true"), j)
        assertTrue(j.contains("\"unlesbar\":[{\"feld\":\"preis\",\"kontext\":\"z676_3074-3169-24\",\"bereich\":[1856,700,2010,744]}]"), j)
    }
}
