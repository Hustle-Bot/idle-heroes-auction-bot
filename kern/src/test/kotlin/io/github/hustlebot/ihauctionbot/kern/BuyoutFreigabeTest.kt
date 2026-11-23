package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nov 23: buyout only where the build offers it (the public app does not, until it is tested in the real game). */
class BuyoutFreigabeTest {

    @Test
    fun `buyout only if the build offers it and the player switched it on`() {
        assertTrue(BuyoutFreigabe.wirksam(freigegeben = true, eingestellt = true))
        assertFalse(BuyoutFreigabe.wirksam(freigegeben = true, eingestellt = false))
        assertFalse(BuyoutFreigabe.wirksam(freigegeben = false, eingestellt = true), "saved or restored setting does not count")
        assertFalse(BuyoutFreigabe.wirksam(freigegeben = false, eingestellt = false))
    }

    @Test
    fun `the bot gets the rules without max buyout where the build does not offer it - the saved rules stay`() {
        val gespeichert = listOf(Suchregel("C-", 10, 20, buyoutMax = 30), Suchregel("B", 30, 40))
        assertEquals(gespeichert, BuyoutFreigabe.regelnFuerLauf(gespeichert, freigegeben = true))
        val lauf = BuyoutFreigabe.regelnFuerLauf(gespeichert, freigegeben = false)
        assertEquals(listOf(null, null), lauf.map { it.buyoutMax })
        assertEquals(gespeichert.map { it.copy(buyoutMax = null) }, lauf, "everything else unchanged")
        assertEquals(30, gespeichert[0].buyoutMax, "the saved rule keeps its max buyout")
        assertFalse(lauf[0].toString().contains("buyout"), "nothing about buyout in the log")
    }

    @Test
    fun `the rule text shows the max buyout only where the build offers buyout`() {
        assertEquals(" · buyout up to 15 SG", BuyoutFreigabe.regelText(15, freigegeben = true))
        assertEquals("", BuyoutFreigabe.regelText(null, freigegeben = true))
        assertEquals("", BuyoutFreigabe.regelText(15, freigegeben = false), "kept in the settings, but not shown")
    }
}
