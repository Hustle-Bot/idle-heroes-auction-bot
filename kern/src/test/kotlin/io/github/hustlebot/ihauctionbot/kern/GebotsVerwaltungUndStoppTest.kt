package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class GebotsVerwaltungUndStoppTest {

    private val t0: Instant = LocalDateTime.of(2026, 11, 8, 23, 0).atZone(BERLIN).toInstant()

    private fun zeile(id: String, preis: Int, zeit: Int?, zeichen: Zeichen = Zeichen.KEINS, kreuz: Boolean = false): Zeile {
        val w = id.split("-").map { it.toInt() }
        return Zeile(0, preis, null, zeit, zeit != null, w, zeichen, kreuz, 'C', Punkt(1, 1))
    }

    @Test
    fun `own count is only approximate - it never blocks bidding (Nov 9)`() {
        val v = GebotsVerwaltung()
        v.meineGeboteGelesen(48, emptyList(), t0)
        v.gebotAbgegeben("1-2-3", "C-", 21, 120, t0)
        v.gebotAbgegeben("1-2-4", "C-", 21, 120, t0)
        v.gebotAbgegeben("1-2-5", "C-", 21, 120, t0)
        assertEquals(51, v.laufend, "for display")
        assertTrue(v.kannBieten, "only \"-7\" from the game locks")
        assertFalse(v.limitGemeldet)
    }

    @Test
    fun `Nov 11 - entries in My Biddings count - new bids, bidding again, dismissing, counter`() {
        val v = GebotsVerwaltung()
        assertEquals(0, v.eintraege, "unknown at the start = 0")
        v.gebotAbgegeben("1-1-1", "B-", 31, 200, t0)
        v.gebotAbgegeben("2-2-2", "B-", 32, 200, t0)
        assertEquals(2, v.eintraege)
        v.gesehen(zeile("1-1-1", 33, 190, Zeichen.HAMMER), t0.plusSeconds(10))
        v.gebotAbgegeben("1-1-1", "B-", 34, 190, t0.plusSeconds(10))
        assertEquals(2, v.eintraege, "bidding again on a copy that is already in there does not count")
        v.meineGeboteGelesen(47, emptyList(), t0.plusSeconds(20))
        assertEquals(47, v.eintraege, "counter from My Biddings applies")
        v.weggedrueckt("2-2-2")
        assertEquals(46, v.eintraege, "X pressed – one entry less")
        v.gebotAbgegeben("2-2-2", "B-", 40, 100, t0.plusSeconds(30))
        assertEquals(47, v.eintraege, "dismissed and bid again – back in there")
        v.gebotAbgegeben("3-3-3", "B-", 31, 100, t0.plusSeconds(31))
        v.gebotNichtAngekommen("3-3-3", warNeu = true)
        assertEquals(47, v.eintraege, "not arrived does not count")
        // Auction end: 1-1-1 (ends after 190 s) and 2-2-2 (100 s after the re-bid) are out – −1 each.
        v.auswerten(t0.plusSeconds(1000))
        assertEquals(45, v.eintraege, "finished auctions drop out of My Biddings")
        v.eintraegeGezaehlt(12)
        assertEquals(12, v.eintraege)
        v.meineGeboteGelesen(null, emptyList(), t0.plusSeconds(1001))
        assertEquals(12, v.eintraege, "unreadable counter changes nothing")
    }

    @Test
    fun `Nov 11 - 30 bids, 5 of them outbid - still 30, only X or auction end count down`() {
        val v = GebotsVerwaltung()
        for (i in 1..30) v.gebotAbgegeben("$i-$i-$i", "B-", 31, 600 + i, t0)
        assertEquals(30, v.eintraege)
        // 5 outbid: with a hammer in the bidding list, with a red X in My Biddings – the number stays.
        for (i in 1..3) v.gesehen(zeile("$i-$i-$i", 32, 590, Zeichen.HAMMER), t0.plusSeconds(10))
        for (i in 4..5) v.gesehen(zeile("$i-$i-$i", 32, 590, kreuz = true), t0.plusSeconds(10))
        assertEquals(30, v.eintraege, "outbid does NOT lower the number")
        // Bidding again on two of them: already in there → no new row.
        v.gebotAbgegeben("1-1-1", "B-", 33, 590, t0.plusSeconds(11))
        v.gebotAbgegeben("4-4-4", "B-", 33, 590, t0.plusSeconds(11))
        assertEquals(30, v.eintraege, "bidding again does not count")
        // A new copy: +1.
        v.gebotAbgegeben("99-99-99", "B-", 31, 5000, t0.plusSeconds(12))
        assertEquals(31, v.eintraege)
        // Two red X pressed: −2.
        v.weggedrueckt("2-2-2")
        v.weggedrueckt("5-5-5")
        assertEquals(29, v.eintraege)
        // All first 30 auctions end: the 28 that were still in there drop out (the dismissed ones not twice).
        v.auswerten(t0.plusSeconds(700))
        assertEquals(1, v.eintraege, "only the new copy is still in there")
    }

    @Test
    fun `after -7 locked until the waiting time from My Biddings - a counter alone lifts nothing`() {
        val v = GebotsVerwaltung()
        v.limitMeldung(t0)
        assertFalse(v.kannBieten, "status -7 means: the game says full")
        assertNull(v.wiederBietenAb, "first check in My Biddings")
        v.meineGeboteGelesen(47, emptyList(), t0.plusSeconds(5))
        assertFalse(v.kannBieten, "counter does not lift the lock")
        v.limitWartenBis(t0.plusSeconds(100))
        assertFalse(v.limitWartezeitVorbei(t0.plusSeconds(99)))
        assertFalse(v.kannBieten)
        assertTrue(v.limitWartezeitVorbei(t0.plusSeconds(100)))
        assertTrue(v.kannBieten)
        assertNull(v.wiederBietenAb)
        assertFalse(v.limitWartezeitVorbei(t0.plusSeconds(200)), "only once")
    }

    @Test
    fun `lift false alarm, certain or uncertain, emergency exit after 6 minutes without waiting time`() {
        val v = GebotsVerwaltung()
        v.limitMeldung(t0, sicher = false)
        assertFalse(v.limitSicher)
        v.limitAufheben()
        assertTrue(v.kannBieten)
        v.limitMeldung(t0)
        assertTrue(v.limitSicher)
        // My Biddings did not work (no waiting time set) → emergency exit after 6 minutes.
        assertFalse(v.limitSperreAbgelaufen(t0.plusSeconds(300)))
        assertTrue(v.limitSperreAbgelaufen(t0.plusSeconds(361)))
        assertTrue(v.kannBieten)
        // With a waiting time set, the waiting time applies, not the emergency exit.
        v.limitMeldung(t0)
        v.limitWartenBis(t0.plusSeconds(500))
        assertFalse(v.limitSperreAbgelaufen(t0.plusSeconds(400)))
        assertFalse(v.kannBieten)
    }

    @Test
    fun `next end of our own bids`() {
        val v = GebotsVerwaltung()
        assertNull(v.naechstesEnde(t0))
        v.gebotAbgegeben("1-1-1", "C-", 21, 300, t0)
        v.gebotAbgegeben("2-2-2", "C-", 21, 90, t0)
        v.gebotAbgegeben("3-3-3", "C-", 21, null, t0)
        assertEquals(t0.plusSeconds(90), v.naechstesEnde(t0))
        assertEquals(t0.plusSeconds(300), v.naechstesEnde(t0.plusSeconds(95)), "already over does not count")
    }

    @Test
    fun `last in front after the end = bought, outbid = lost`() {
        val v = GebotsVerwaltung()
        v.gebotAbgegeben("1-1-1", "C-", 22, 60, t0)
        v.gebotAbgegeben("2-2-2", "C-", 23, 60, t0)
        v.gesehen(zeile("1-1-1", 22, 30, Zeichen.UHR), t0.plusSeconds(30))
        v.gesehen(zeile("2-2-2", 24, 30, Zeichen.HAMMER), t0.plusSeconds(30))
        assertTrue(v.auswerten(t0.plusSeconds(70)).isEmpty(), "decide only after the grace period")
        val texte = v.auswerten(t0.plusSeconds(85))
        assertEquals(2, texte.size)
        assertEquals(1, v.gekaufteKopien)
        assertEquals(22L, v.ausgegebenSg)
        assertEquals(1, v.verloren)
        assertTrue(v.auswerten(t0.plusSeconds(200)).isEmpty(), "every auction counts only once")
    }

    @Test
    fun `red cross in My Biddings means outbid`() {
        val v = GebotsVerwaltung()
        v.gebotAbgegeben("1-1-1", "C-", 22, 60, t0)
        v.meineGeboteGelesen(1, listOf(zeile("1-1-1", 23, 20, kreuz = true)), t0.plusSeconds(40))
        v.auswerten(t0.plusSeconds(100))
        assertEquals(0, v.gekaufteKopien)
        assertEquals(1, v.verloren)
    }

    @Test
    fun `stop at number of copies`() {
        val s = StoppBedingung(zielKopien = 800)
        assertNull(s.pruefe(799, t0, null))
        assertEquals(StoppGrund.ZIEL_ERREICHT, s.pruefe(800, t0, null))
    }

    @Test
    fun `end time before the start time means the next morning`() {
        val s = StoppBedingung(endeUm = LocalTime.of(7, 0), zone = BERLIN)
        val ende = s.endZeitpunkt(t0)!!
        assertEquals(LocalDateTime.of(2026, 11, 9, 7, 0), ende.atZone(BERLIN).toLocalDateTime())
        assertNull(s.pruefe(0, ende.minusSeconds(1), ende))
        assertEquals(StoppGrund.ZEIT_ERREICHT, s.pruefe(0, ende, ende))
    }

    @Test
    fun `end time on the same day`() {
        val s = StoppBedingung(endeUm = LocalTime.of(23, 30), zone = BERLIN)
        val start = LocalDateTime.of(2026, 11, 8, 22, 0).atZone(BERLIN).toInstant()
        assertEquals(LocalDateTime.of(2026, 11, 8, 23, 30), s.endZeitpunkt(start)!!.atZone(BERLIN).toLocalDateTime())
    }

    @Test
    fun `the end time is meant in the given time zone (on the phone the device's zone)`() {
        val start = LocalDateTime.of(2026, 11, 8, 22, 0).atZone(BERLIN).toInstant()
        val tokio = ZoneId.of("Asia/Tokyo")
        val s = StoppBedingung(endeUm = LocalTime.of(7, 0), zone = tokio)
        assertEquals(LocalTime.of(7, 0), s.endZeitpunkt(start)!!.atZone(tokio).toLocalTime())
        assertEquals(ZoneId.systemDefault(), StoppBedingung().zone)
    }

    @Test
    fun `whichever comes first - copies or end time`() {
        val s = StoppBedingung(zielKopien = 800, endeUm = LocalTime.of(7, 0), zone = BERLIN)
        val ende = s.endZeitpunkt(t0)
        val nachts = LocalDateTime.of(2026, 11, 9, 3, 0).atZone(BERLIN).toInstant()
        assertEquals(StoppGrund.ZIEL_ERREICHT, s.pruefe(800, nachts, ende))
        val morgens = LocalDateTime.of(2026, 11, 9, 7, 0).atZone(BERLIN).toInstant()
        assertEquals(StoppGrund.ZEIT_ERREICHT, s.pruefe(500, morgens, ende))
    }

    @Test
    fun `without goal and without end time it keeps running`() {
        val s = StoppBedingung()
        assertNull(s.pruefe(10_000, t0, s.endZeitpunkt(t0)))
    }

    @Test
    fun `every stop locks the phone - only a stop by hand or from outside does not`() {
        // Nov 11: lock the phone on every stop (the bot runs unattended), except by hand.
        // Stops from outside (screen off, touched, emergency stop, game not in front): the app decides about those.
        val vonAussen = setOf(
            StoppGrund.VON_HAND, StoppGrund.BILDSCHIRM_AUS, StoppGrund.BERUEHRT, StoppGrund.NOTAUS, StoppGrund.SPIEL_NICHT_VORNE,
        )
        vonAussen.forEach { assertFalse(it.sperren, "$it") }
        StoppGrund.entries.filter { it !in vonAussen }.forEach { assertTrue(it.sperren, "$it does not lock") }
    }
}
