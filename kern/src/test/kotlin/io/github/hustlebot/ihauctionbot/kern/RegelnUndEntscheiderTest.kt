package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegelnUndEntscheiderTest {

    private val cMinus = Suchregel("C-", 16, 18)
    private val knopf = Punkt(500, 900)

    private fun angebot(
        preis: Int,
        typ: String = "C-",
        menge: Int = 1,
        eigenes: Boolean = false,
        ueberboten: Boolean = false,
        naechster: Int? = null,
    ) = Angebot("a1", typ, menge, preis, eigenesGebot = eigenes, ueberboten = ueberboten, naechsterPreis = naechster, bietenKnopf = knopf)

    @Test
    fun `rule only fits an enabled rule with the same copy`() {
        assertTrue(cMinus.passtTyp("C-"))
        assertTrue(cMinus.passtTyp(" c- "))
        assertFalse(cMinus.passtTyp("C"))
        assertFalse(cMinus.copy(aktiv = false).passtTyp("C-"))
        assertFalse(Suchregel("C-", 20, 18).passtTyp("C-"), "min price above max price is invalid")
    }

    @Test
    fun `limits are included`() {
        assertTrue(cMinus.erlaubt(16.0))
        assertTrue(cMinus.erlaubt(18.0))
        assertFalse(cMinus.erlaubt(15.0))
        assertFalse(cMinus.erlaubt(18.5))
    }

    @Test
    fun `bids on a matching C- copy`() {
        // current price 16 → bid costs 17 → fits into 16–18
        val e = Entscheider.entscheide(angebot(16), listOf(cMinus), kannBieten = true)
        assertInstanceOf(Entscheidung.Bieten::class.java, e)
        assertEquals(17, (e as Entscheidung.Bieten).preis)
    }

    @Test
    fun `shown next price takes priority`() {
        val e = Entscheider.entscheide(angebot(16, naechster = 18), listOf(cMinus), kannBieten = true)
        assertEquals(18, (e as Entscheidung.Bieten).preis)
    }

    @Test
    fun `too cheap or too expensive is skipped`() {
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, Entscheider.entscheide(angebot(13), listOf(cMinus), true))
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, Entscheider.entscheide(angebot(19), listOf(cMinus), true))
    }

    @Test
    fun `Nov 9 - rule numbers are the filter values, bid up to max price plus 1`() {
        val bMinus = Suchregel("B-", 10, 60)
        assertEquals(61, bMinus.hoechstesGebot)
        // Row shows 60 → the bot bids 61.
        val e = Entscheider.entscheide(angebot(60, typ = "B-"), listOf(bMinus), true)
        assertEquals(61, (e as Entscheidung.Bieten).preis)
        // Outbid at 61 → bidding again would cost 62 → let go.
        val l = Entscheider.entscheide(angebot(61, typ = "B-", eigenes = true, ueberboten = true), listOf(bMinus), true)
        assertInstanceOf(Entscheidung.Loslassen::class.java, l)
        assertTrue(bMinus.gebotErlaubt(61))
        assertFalse(bMinus.gebotErlaubt(62))
        assertFalse(bMinus.gebotErlaubt(10), "bid is at least min price + 1")
    }

    @Test
    fun `other copy is skipped`() {
        val e = Entscheider.entscheide(angebot(16, typ = "C"), listOf(cMinus), true)
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, e)
    }

    @Test
    fun `example from the field - bid 18, outbid at 19, bidding again would be 20 - let go`() {
        // Filter 16–18: rows up to 18 SG, bid at most 19.
        val e = Entscheider.entscheide(angebot(19, eigenes = true, ueberboten = true), listOf(cMinus), true)
        assertInstanceOf(Entscheidung.Loslassen::class.java, e)
        val noch = Entscheider.entscheide(angebot(18, eigenes = true, ueberboten = true), listOf(cMinus), true)
        assertEquals(19, (noch as Entscheidung.Nachbieten).preis)
    }

    @Test
    fun `outbid and the new price still fits - bid again for price plus 1`() {
        val e = Entscheider.entscheide(angebot(17, eigenes = true, ueberboten = true), listOf(cMinus), true)
        assertInstanceOf(Entscheidung.Nachbieten::class.java, e)
        assertEquals(18, (e as Entscheidung.Nachbieten).preis)
    }

    @Test
    fun `I am already in front - do nothing`() {
        val e = Entscheider.entscheide(angebot(17, eigenes = true, ueberboten = false), listOf(cMinus), true)
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, e)
    }

    @Test
    fun `limit full - wait instead of bidding`() {
        val e = Entscheider.entscheide(angebot(16), listOf(cMinus), kannBieten = false)
        assertInstanceOf(Entscheidung.Warten::class.java, e)
    }

    @Test
    fun `price per copy with several copies`() {
        // 5 copies, bid 85 → 17 per copy
        val e = Entscheider.entscheide(angebot(84, menge = 5), listOf(cMinus), true)
        assertInstanceOf(Entscheidung.Bieten::class.java, e)
        // 5 copies, bid 95 → 19 per copy
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, Entscheider.entscheide(angebot(94, menge = 5), listOf(cMinus), true))
    }

    @Test
    fun `several rules - the matching one counts`() {
        val regeln = listOf(cMinus, Suchregel("C", 20, 24))
        assertInstanceOf(Entscheidung.Bieten::class.java, Entscheider.entscheide(angebot(21, typ = "C"), regeln, true))
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, Entscheider.entscheide(angebot(21, typ = "C-"), regeln, true))
    }

    // Nov 8: only auctions that end soon
    private val kurz = Suchregel("C-", 20, 24, maxRestzeitMin = 5)

    private fun mitZeit(preis: Int, restSek: Int?, eigenes: Boolean = false, ueberboten: Boolean = false) =
        angebot(preis, eigenes = eigenes, ueberboten = ueberboten).copy(restzeitSek = restSek)

    @Test
    fun `auction ends soon - the bot bids`() {
        val e = Entscheider.entscheide(mitZeit(21, 4 * 60), listOf(kurz), kannBieten = true)
        assertEquals(22, (e as Entscheidung.Bieten).preis)
        assertInstanceOf(Entscheidung.Bieten::class.java, Entscheider.entscheide(mitZeit(21, 5 * 60), listOf(kurz), true), "exactly 5 min still counts")
    }

    @Test
    fun `auction still runs for a long time - do not bid`() {
        val e = Entscheider.entscheide(mitZeit(17, 95 * 60), listOf(kurz.copy(minPreis = 10)), kannBieten = true)
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, e)
        assertTrue((e as Entscheidung.Ueberspringen).grund.contains("1 h 35 min"))
    }

    @Test
    fun `remaining time not readable - do not bid with a time limit, but do without one`() {
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, Entscheider.entscheide(mitZeit(21, null), listOf(kurz), true))
        assertInstanceOf(Entscheidung.Bieten::class.java, Entscheider.entscheide(mitZeit(21, null), listOf(kurz.copy(maxRestzeitMin = null)), true))
    }

    @Test
    fun `outbid with a long remaining time - do not let go, just wait`() {
        val e = Entscheider.entscheide(mitZeit(21, 30 * 60, eigenes = true, ueberboten = true), listOf(kurz), true)
        assertInstanceOf(Entscheidung.Ueberspringen::class.java, e)
        val spaeter = Entscheider.entscheide(mitZeit(21, 60, eigenes = true, ueberboten = true), listOf(kurz), true)
        assertEquals(22, (spaeter as Entscheidung.Nachbieten).preis)
    }

    @Test
    fun `time limit 0 is invalid`() {
        assertFalse(kurz.copy(maxRestzeitMin = 0).gueltig)
        assertTrue(kurz.gueltig)
    }
}
