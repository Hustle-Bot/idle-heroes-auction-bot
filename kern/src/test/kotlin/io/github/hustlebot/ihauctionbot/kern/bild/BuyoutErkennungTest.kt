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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Nov 22 (buyout): the "Buyout Price: N" field and the "Buyout Price" button in the Bid window, checked on the real
 * screenshots. With a buyout the button is yellow (y ≈ 887–981); without one it is gray and the field shows "--".
 */
class BuyoutErkennungTest {

    @Test
    fun `Bid window with buyout - price 28 read, yellow Buyout button found below the Bid button`() {
        val b = Erkenner.erkenne(TestBilder.lade("bid-fenster"))
        assertEquals(Ort.BIETEN, b.ort)
        assertEquals(28, b.sofortPreis)
        assertNotNull(b.knopf(Knopf.SOFORTKAUF), "Buyout button")
        val knopf = b.knopf(Knopf.SOFORTKAUF)!!
        assertEquals(1641, knopf.x)
        assertTrue(knopf.y in 900..970, "Buyout button y=${knopf.y}")
        val bid = b.knopf(Knopf.BIETEN)!!
        assertTrue(bid.y < 800 && knopf.y - bid.y > 150, "Bid ${bid.y}, Buyout ${knopf.y}")
        // The Current Bid field is unchanged.
        assertEquals(18, b.aktuellesGebot)
    }

    @Test
    fun `Bid window without buyout - gray button, no buyout price, Bid works as before`() {
        for (name in listOf("bid-fenster-b-ohne-buyout-1", "bid-fenster-b-ohne-buyout-2", "bid-fenster-b-ohne-buyout-3")) {
            val b = Erkenner.erkenne(TestBilder.lade(name))
            assertEquals(Ort.BIETEN, b.ort, name)
            assertNull(b.knopf(Knopf.SOFORTKAUF), "$name: gray button is not offered")
            assertNull(b.sofortPreis, "$name: \"--\" is not a price")
            assertNotNull(b.knopf(Knopf.BIETEN), name)
        }
    }

    @Test
    fun `only Bid windows offer a Buyout button`() {
        for (name in listOf("markt-c-minus", "bestaetigen-17", "filter-fenster", "meine-gebote-43", "laden", "sortier-fenster")) {
            val b = Erkenner.erkenne(TestBilder.lade(name))
            assertNull(b.knopf(Knopf.SOFORTKAUF), name)
        }
        // The "-7" screenshot is a Bid window of a copy WITH buyout: the button is yellow there (the toast lies over Bid).
        val limit = Erkenner.erkenne(TestBilder.lade("limit-50-status-7"))
        assertEquals(Ort.BIETEN, limit.ort)
        assertNotNull(limit.knopf(Knopf.SOFORTKAUF))
    }

    // ---------- Nov 22: recognition + bot together on the real Bid windows ----------

    private val jetzt = LocalDateTime.of(2026, 11, 22, 23, 0).atZone(BERLIN).toInstant()

    private fun getippt(e: Ergebnis): Punkt = ((e as Ergebnis.Handeln).aktion as Aktion.Tippen).punkt

    /**
     * Bidding list (synthetic: one row with [preis] and buyout [sofort]) → filter → back in the list → the bot opens the row;
     * then the real screenshot [bildName] as the Bid window. Returns the row tap and the bot's results on the Bid window.
     */
    private fun lauf(regel: Suchregel, preis: Int, sofort: Int?, stufe: Char, bildName: String): Pair<Ergebnis.Handeln, List<Ergebnis>> {
        val zeile = Zeile(
            oben = 314, preis = preis, sofortPreis = sofort, restzeitSek = 120, hatRestzeit = true,
            werte = listOf(3151, 2502, 38), zeichen = Zeichen.KEINS, stufe = stufe, tippen = Punkt(1356, 374),
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
            filterFelder = KOPIE_TYPEN.mapIndexed { i, n -> FilterFeld(n, Punkt(800 + i * 100, 285), n == regel.kopie) },
            filterMinStellen = regel.minPreis.toString().length, filterMaxStellen = regel.maxPreis.toString().length,
            knoepfe = mapOf(Knopf.FILTER_OK to Punkt(1547, 1018), Knopf.FILTER_MIN to Punkt(1153, 584), Knopf.FILTER_MAX to Punkt(1559, 584)),
        )
        val folge = ArrayDeque(listOf(markt, filter, filter, markt, markt))
        var echtesBild: Bildschirm? = null
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { echtesBild ?: folge.removeFirst() }, buyoutAn = true)
        assertEquals(Punkt(2180, 231), getippt(bot.schritt(jetzt)), "filter")
        bot.schritt(jetzt)
        bot.eingabeErgebnis(true)
        assertEquals(Punkt(1547, 1018), getippt(bot.schritt(jetzt)), "Confirm")
        bot.schritt(jetzt.plusSeconds(7))
        assertEquals(regel.kopie, bot.filterStufe)
        val zeileGetippt = bot.schritt(jetzt.plusSeconds(7)) as Ergebnis.Handeln
        assertEquals(Punkt(1356, 374), (zeileGetippt.aktion as Aktion.Tippen).punkt, "tap the row")
        echtesBild = Erkenner.erkenne(TestBilder.lade(bildName))
        val danach = ArrayList<Ergebnis>()
        for (i in 0 until 12) {
            val e = bot.schritt(jetzt.plusMillis(7500L + i * 400L))
            danach += e
            if (e is Ergebnis.Handeln) break // after a tap the game would show something else
        }
        return zeileGetippt to danach
    }

    @Test
    fun `real Bid window with buyout 28 - buyout on, maximum 30 - the bot taps the yellow Buyout button, never Bid`() {
        val (zeile, danach) = lauf(Suchregel("C-", 10, 24, maxRestzeitMin = 5, buyoutMax = 30), 18, 28, 'C', "bid-fenster")
        assertTrue(zeile.aktion.grund.startsWith("buyout"), zeile.aktion.grund)
        val tipp = danach.last() as Ergebnis.Handeln
        val p = getippt(tipp)
        assertEquals(1641, p.x)
        assertTrue(p.y in 900..970, "Buyout button y=${p.y}")
        assertTrue(tipp.aktion.grund.startsWith("Buyout for 28 SG"), tipp.aktion.grund)
    }

    @Test
    fun `real Bid window with buyout 28 - maximum 27 - normal bid, the Buyout button is not touched`() {
        val (zeile, danach) = lauf(Suchregel("C-", 10, 24, maxRestzeitMin = 5, buyoutMax = 27), 18, 28, 'C', "bid-fenster")
        assertTrue(zeile.aktion.grund.startsWith("bid"), zeile.aktion.grund)
        val p = getippt(danach.last())
        assertEquals(1641, p.x)
        assertTrue(p.y in 640..720, "Bid button y=${p.y}")
    }

    @Test
    fun `real Bid window without buyout (gray) - list said buyout - closed with the X, no Buyout, no Bid`() {
        for (name in listOf("bid-fenster-b-ohne-buyout-1", "bid-fenster-b-ohne-buyout-2", "bid-fenster-b-ohne-buyout-3")) {
            val (zeile, danach) = lauf(Suchregel("B-", 10, 54, maxRestzeitMin = 5, buyoutMax = 60), 53, 55, 'B', name)
            assertTrue(zeile.aktion.grund.startsWith("buyout"), zeile.aktion.grund)
            assertEquals(Punkt(2105, 108), getippt(danach.last()), "$name: red X – $danach")
        }
    }
}
