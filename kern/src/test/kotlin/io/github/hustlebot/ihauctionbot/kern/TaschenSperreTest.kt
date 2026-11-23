package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nov 11: pocket mode – unlock only by holding for 3 s AND then tapping "Unlock" within 5 s. */
class TaschenSperreTest {

    private fun gesperrt() = TaschenSperre().apply { einschalten() }

    @Test
    fun `off - nothing happens, pressing the button changes nothing`() {
        val s = TaschenSperre()
        assertFalse(s.an)
        s.knopfGedrueckt(0)
        s.pruefe(10_000)
        assertEquals(TaschenSperre.Zustand.AUS, s.zustand)
        assertFalse(s.entsperrenGetippt(10_000), "nothing to unlock")
    }

    @Test
    fun `hold 3 s, then tap Unlock within 5 s - unlocked`() {
        val s = gesperrt()
        s.knopfGedrueckt(1_000)
        assertFalse(s.zeigtEntsperren(3_999), "only after 3 seconds")
        assertTrue(s.zeigtEntsperren(4_000), "after holding for 3 seconds Unlock appears")
        s.knopfLosgelassen(4_200)
        assertTrue(s.zeigtEntsperren(4_200), "released after 3 s – Unlock stays")
        assertTrue(s.entsperrenGetippt(8_500))
        assertEquals(TaschenSperre.Zustand.AUS, s.zustand)
        assertFalse(s.an)
    }

    @Test
    fun `released after the time is up without a check in between - still counts as held for 3 s`() {
        val s = gesperrt()
        s.knopfGedrueckt(0)
        s.knopfLosgelassen(3_500)
        assertEquals(TaschenSperre.Zustand.FRAGT, s.zustand)
        assertTrue(s.entsperrenGetippt(4_000))
    }

    @Test
    fun `held too briefly - stays locked, tapping Unlock does not help`() {
        val s = gesperrt()
        s.knopfGedrueckt(0)
        s.knopfLosgelassen(2_900)
        assertEquals(TaschenSperre.Zustand.GESPERRT, s.zustand)
        assertFalse(s.zeigtEntsperren(10_000))
        assertFalse(s.entsperrenGetippt(3_100))
        assertTrue(s.an)
    }

    @Test
    fun `Unlock too late (after 5 s) - locked again, start over`() {
        val s = gesperrt()
        s.knopfGedrueckt(0)
        assertTrue(s.zeigtEntsperren(3_000))
        s.knopfLosgelassen(3_100)
        assertFalse(s.zeigtEntsperren(8_001), "Unlock disappears after 5 seconds")
        assertFalse(s.entsperrenGetippt(8_100))
        assertEquals(TaschenSperre.Zustand.GESPERRT, s.zustand)
        // Starting over works again.
        s.knopfGedrueckt(10_000)
        s.pruefe(13_000)
        assertTrue(s.entsperrenGetippt(13_500))
    }

    @Test
    fun `only tapping Unlock (without holding) or only normal taps - stays locked`() {
        val s = gesperrt()
        assertFalse(s.entsperrenGetippt(100))
        // Many short taps on the IH button (as in the pocket).
        var t = 0L
        repeat(50) {
            s.knopfGedrueckt(t)
            s.knopfLosgelassen(t + 200)
            t += 500
        }
        assertEquals(TaschenSperre.Zustand.GESPERRT, s.zustand)
        assertFalse(s.entsperrenGetippt(t))
    }

    @Test
    fun `slipped while holding - does not count`() {
        val s = gesperrt()
        s.knopfGedrueckt(0)
        s.knopfVerrutscht(1_000)
        assertFalse(s.zeigtEntsperren(5_000))
        s.knopfLosgelassen(5_000)
        assertEquals(TaschenSperre.Zustand.GESPERRT, s.zustand)
    }

    @Test
    fun `while Unlock is visible, pressing again changes nothing`() {
        val s = gesperrt()
        s.knopfGedrueckt(0)
        s.pruefe(3_000)
        s.knopfGedrueckt(4_000)
        s.knopfLosgelassen(4_100)
        assertEquals(TaschenSperre.Zustand.FRAGT, s.zustand)
        assertTrue(s.entsperrenGetippt(7_000))
    }

    @Test
    fun `turning on after unlocking - locked again`() {
        val s = gesperrt()
        s.knopfGedrueckt(0)
        s.pruefe(3_000)
        assertTrue(s.entsperrenGetippt(3_500))
        s.einschalten()
        assertEquals(TaschenSperre.Zustand.GESPERRT, s.zustand)
        assertFalse(s.entsperrenGetippt(3_600))
    }
}

/** Nov 11: open the shield briefly for the bot's gestures and then close it again safely. */
class SchildDurchlassTest {

    @Test
    fun `one gesture - open, close, locked again`() {
        val d = SchildDurchlass()
        val (id, jetztAuf) = d.auf()
        assertTrue(jetztAuf)
        assertTrue(d.durchlaessig)
        assertTrue(d.zu(id), "last gesture done → lock")
        assertFalse(d.durchlaessig)
        assertFalse(d.zu(id), "twice (report + emergency) does no harm")
    }

    @Test
    fun `overlapping gestures - lock only when all are done`() {
        val d = SchildDurchlass()
        val (a, aAuf) = d.auf()
        val (b, bAuf) = d.auf()
        assertTrue(aAuf)
        assertFalse(bAuf, "already open")
        assertFalse(d.zu(a), "b is still running")
        assertTrue(d.durchlaessig)
        assertTrue(d.zu(b))
        assertFalse(d.durchlaessig)
    }

    @Test
    fun `stuck gestures - lead time gets longer, stop after 5 times`() {
        val d = SchildDurchlass()
        assertEquals(60L, d.vorlaufMs)
        // Nov 12 (field test 1.30): after the gesture first 250 ms, longer when a tap got stuck.
        assertEquals(250L, d.nachlaufMs)
        assertFalse(d.verschluckt())
        assertEquals(120L, d.vorlaufMs)
        assertEquals(500L, d.nachlaufMs)
        assertFalse(d.verschluckt())
        assertFalse(d.verschluckt())
        assertEquals(SchildDurchlass.MAX_VORLAUF_MS, d.vorlaufMs)
        assertEquals(SchildDurchlass.MAX_NACHLAUF_MS, d.nachlaufMs)
        assertFalse(d.verschluckt())
        assertTrue(d.verschluckt(), "five times – the taps are not getting through")
    }

    @Test
    fun `forget - nothing open any more`() {
        val d = SchildDurchlass()
        val (id, _) = d.auf()
        d.vergessen()
        assertFalse(d.durchlaessig)
        assertFalse(d.zu(id))
    }
}
