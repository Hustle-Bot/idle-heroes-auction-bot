package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import io.github.hustlebot.ihauctionbot.kern.BERLIN
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.FilterFeld
import io.github.hustlebot.ihauctionbot.kern.GebotsVerwaltung
import io.github.hustlebot.ihauctionbot.kern.KOPIE_TYPEN
import io.github.hustlebot.ihauctionbot.kern.LANGER_TIPP_MS
import io.github.hustlebot.ihauctionbot.kern.Knopf
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.StoppBedingung
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import io.github.hustlebot.ihauctionbot.kern.Zeile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Recognition + bot together on real screenshots: does the bot tap the right spots? */
class EchteBilderBotTest {

    private val jetzt = LocalDateTime.of(2026, 11, 8, 23, 0).atZone(BERLIN).toInstant()
    private val regel = Suchregel("C-", 20, 24, maxRestzeitMin = 5)

    private fun getippt(e: Ergebnis): Punkt = ((e as Ergebnis.Handeln).aktion as Aktion.Tippen).punkt

    private fun eingabe(e: Ergebnis): Aktion.Eingeben = (e as Ergebnis.Handeln).aktion as Aktion.Eingeben

    @Test
    fun `start - open filter, C- is selected, fill in price fields, confirm, sort order is already right`() {
        val bilder = ArrayDeque(
            listOf("markt-kurz-vor-ende", "filter-fenster", "filter-fenster", "markt-kurz-vor-ende", "markt-kurz-vor-ende"),
        )
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade(bilder.removeFirst())) })
        assertEquals(Punkt(2180, 231), getippt(bot.schritt(jetzt)), "filter button")
        // Nov 9: price fields – left the min price, right the max price of the rule – in ONE sequence (speed).
        val folge = (bot.schritt(jetzt) as Ergebnis.Handeln).aktion as Aktion.Folge
        val eingaben = folge.schritte.filterIsInstance<Aktion.Eingeben>()
        assertEquals(2, folge.schritte.size, "C- is already selected – only the two price fields")
        assertEquals(Punkt(1153, 584), eingaben[0].feld)
        assertEquals("20", eingaben[0].text)
        assertEquals(Punkt(1559, 584), eingaben[1].feld)
        assertEquals("24", eingaben[1].text)
        bot.eingabeErgebnis(true)
        // Checked on the next image (C- selected, two characters per field) → Confirm.
        assertEquals(Punkt(1547, 1018), getippt(bot.schritt(jetzt)), "Confirm")
        // Nov 12: the test image of the list is the same as before the filter tap – so shortly afterwards it would count as old.
        val spaeter = jetzt.plusSeconds(7)
        bot.schritt(spaeter) // list shows C (purple badge) → filter counts as set
        assertEquals("C-", bot.filterStufe)
        // Sort order is already Awakened Tier ↑ → do not change it.
        // Nov 9 (field test 1.10/1.11): NOT to My Biddings (only after "-7") – carry on right away in the list.
        val danach = bot.schritt(spaeter)
        assertTrue(danach is Ergebnis.Handeln, "$danach")
        assertFalse((danach as Ergebnis.Handeln).aktion.grund.startsWith("My Biddings"), danach.aktion.grund)
        assertTrue(danach.aktion.let { it !is Aktion.Tippen || it.punkt != Punkt(321, 488) }, "did not tap My Biddings")
    }

    @Test
    fun `list sorted by price - switch to Awakened Tier`() {
        val bilder = ArrayDeque(listOf("markt-unsortiert", "sortier-fenster", "sortier-fenster"))
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade(bilder.removeFirst())) })
        // First it wants to set the filter; here only check the sort order: sort window → "Awakened Tier ↑".
        bot.schritt(jetzt)
        // Nov 9 (field test 1.9: 4 taps needed): let it open fully first, then tap a bit longer.
        assertTrue(bot.schritt(jetzt) is Ergebnis.Warten, "let the sort window open fully first")
        val e = bot.schritt(jetzt.plusMillis(300))
        assertEquals(Punkt(1734, 499), getippt(e), "Awakened Tier ↑")
        assertEquals(LANGER_TIPP_MS, ((e as Ergebnis.Handeln).aktion as Aktion.Tippen).dauerMs, "longer tap")
    }

    @Test
    fun `status -7 in the real Bid window - remember the limit and close with X`() {
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("limit-50-status-7")) })
        // Nov 11: first image → do not tap the X at the top yet; on the second (window steady), yes.
        assertTrue(bot.schritt(jetzt) is Ergebnis.Warten)
        assertEquals(Punkt(2105, 108), getippt(bot.schritt(jetzt.plusMillis(300))))
        assertTrue(bot.gebote.limitGemeldet)
    }

    /**
     * Nov 11 (emulator test, real app): after "Confirm" the screenshot still showed the filter window for 1–2 s. The bot entered
     * the prices again – the taps landed in the market and in the Bid window. Now: wait, tap nothing.
     */
    @Test
    fun `after Confirm the screenshot still shows the filter window - tap nothing, wait`() {
        val bilder = ArrayDeque(
            listOf("markt-kurz-vor-ende", "filter-fenster", "filter-fenster", "filter-fenster", "filter-fenster", "markt-kurz-vor-ende"),
        )
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade(bilder.removeFirst())) })
        var t = jetzt
        assertEquals(Punkt(2180, 231), getippt(bot.schritt(t)), "filter button")
        assertTrue((bot.schritt(t) as Ergebnis.Handeln).aktion is Aktion.Folge)
        bot.eingabeErgebnis(true)
        assertEquals(Punkt(1547, 1018), getippt(bot.schritt(t)), "Confirm")
        // The screenshot lags behind: the filter window twice more (0.9 s and 1.8 s after Confirm) – never tap.
        repeat(2) {
            t = t.plusMillis(900)
            val e = bot.schritt(t)
            assertTrue(e is Ergebnis.Warten, "$e")
        }
        t = jetzt.plusSeconds(7) // the same list test image as before the filter tap – only after this does it not count as old
        bot.schritt(t) // list shows C → filter set
        assertEquals("C-", bot.filterStufe)
    }

    @Test
    fun `foreign confirm window (real image) - red X, never Cancel`() {
        // Nov 9 (field test 1.8): the bot never taps "Cancel" (1182, 804).
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("bestaetigen-18")) })
        assertEquals(Punkt(1806, 301), getippt(bot.schritt(jetzt)))
    }

    @Test
    fun `market with expanded IH panel (real image) - tap nothing`() {
        // Nov 9 (field test 1.8): otherwise the bot took it for "other auction house page" and tapped into the menu on the left.
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("markt-mit-ih-feld")) })
        repeat(3) { assertTrue(bot.schritt(jetzt) is Ergebnis.Unsicher) }
    }

    @Test
    fun `other auction house page - back to the Auction Market`() {
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("empfohlen")) })
        assertEquals(Punkt(321, 342), getippt(bot.schritt(jetzt)))
    }

    @Test
    fun `tab Items on Display - back to Items for Bidding`() {
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("markt-anzeige")) })
        assertEquals(Punkt(935, 228), getippt(bot.schritt(jetzt)))
    }

    /**
     * Nov 9 (field test 1.7): B- row for 53 tapped, Bid window without buyout opened – the bot did
     * NOT tap "Bid" (window not recognized). Now: rule B- filter 10–54, Current Bid 53 ≤ 54
     * → "Bid" for 54 at the position where the button was found. Both images saved by the bot itself.
     */
    @Test
    fun `real Bid window without buyout - bot taps Bid for 54`() {
        for (name in listOf("bid-fenster-b-ohne-buyout-1", "bid-fenster-b-ohne-buyout-2", "bid-fenster-b-ohne-buyout-3")) {
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
            var echtesBild: Bildschirm? = null
            val bot = AuktionsBot(listOf(bMinus), StoppBedingung(), { echtesBild ?: folge.removeFirst() })
            assertEquals(Punkt(2180, 231), getippt(bot.schritt(jetzt)), "$name: filter")
            val f = (bot.schritt(jetzt) as Ergebnis.Handeln).aktion as Aktion.Folge
            assertEquals(listOf("10", "54"), f.schritte.filterIsInstance<Aktion.Eingeben>().map { it.text })
            bot.eingabeErgebnis(true)
            assertEquals(Punkt(1547, 1018), getippt(bot.schritt(jetzt)), "$name: Confirm")
            // Nov 12: the same list as before the filter tap – only after [Takt.altesBildMs] does it no longer count as old.
            bot.schritt(jetzt.plusSeconds(7)) // filter checked (green B badges)
            assertEquals("B-", bot.filterStufe)
            // Nov 9 (field test 1.10/1.11): no detour to My Biddings – the row right away.
            val zeileGetippt = bot.schritt(jetzt.plusSeconds(7)) as Ergebnis.Handeln
            assertEquals(Punkt(1356, 374), (zeileGetippt.aktion as Aktion.Tippen).punkt, "$name: tap row 53")
            assertTrue(zeileGetippt.aktion.grund.contains("54 SG"), zeileGetippt.aktion.grund)

            // Now the real image from the phone.
            val echt = Erkenner.erkenne(TestBilder.lade(name))
            echtesBild = echt
            assertEquals(Ort.BIETEN, echt.ort, "$name: recognized as Bid window")
            assertEquals(53, echt.aktuellesGebot, "$name: Current Bid")
            // Nov 11 (speed): at the very first Bid window the bot looks twice and remembers the position of the
            // button – after that it already taps on the first image.
            assertTrue(bot.schritt(jetzt.plusMillis(7500)) is Ergebnis.Warten, "$name: first image – remember the position")
            val bid = getippt(bot.schritt(jetzt.plusMillis(7900)))
            assertEquals(1641, bid.x, "$name: Bid button")
            assertTrue(bid.y in 640..720, "$name: Bid button y=${bid.y}")
            assertEquals(bid, bot.bidKnopfGelernt, "$name: position remembered")
        }
    }

    // ---------- Nov 11: cleaning up My Biddings after "-7" (real images) ----------

    /** Bot that just got "-7" and always sees the same real image. */
    private fun aufraeumBot(bild: String, regel: Suchregel): AuktionsBot {
        val gv = GebotsVerwaltung().apply { limitMeldung(jetzt, sicher = true) }
        // Nov 11 (review): the bot only bids again on copies from its own bookkeeping – pretend the bot placed
        // the bids on the image itself in this run (outbid ones are then in there as outbid).
        val vorher = jetzt.minusSeconds(60)
        for (z in Erkenner.erkenne(TestBilder.lade(bild)).zeilen) {
            val id = z.id ?: continue
            val preis = z.preis ?: continue
            gv.gebotAbgegeben(id, regel.kopie, if (z.ueberbotenKreuz) preis - 1 else preis, z.restzeitSek?.plus(60), vorher)
            gv.gesehen(z, jetzt.minusSeconds(30))
        }
        return AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade(bild)) }, gebote = gv)
    }

    /** The first action on a row (pushing up at the start is skipped – the image stays the same). */
    private fun ersteHandlungInDerListe(bot: AuktionsBot): Aktion {
        var t = jetzt
        repeat(20) {
            val e = bot.schritt(t)
            if (e is Ergebnis.Handeln && !e.aktion.grund.startsWith("push the list in My Biddings up")) return e.aktion
            t = t.plusMillis(
                when (e) {
                    is Ergebnis.Handeln -> e.naechsterSchrittMs
                    is Ergebnis.Warten -> e.ms
                    is Ergebnis.Unsicher -> e.ms
                    else -> 500
                },
            )
        }
        error("no action")
    }

    // Example from the field: B- filter 10–59 (bid at most 60), without a remaining time limit.
    private val bMinus59 = Suchregel("B-", 10, 59)

    @Test
    fun `My Biddings real - 57 with X fits - tap the row and bid again for 58`() {
        val a = ersteHandlungInDerListe(aufraeumBot("meine-gebote-50-gemischt", bMinus59))
        assertEquals(Punkt(1356, 503), (a as Aktion.Tippen).punkt, a.grund)
        assertTrue(a.grund.startsWith("re-bid in My Biddings: B- for 58 SG"), a.grund)
    }

    @Test
    fun `My Biddings real - 60 with X is too expensive (bid 61) - press exactly the red X`() {
        val a = ersteHandlungInDerListe(aufraeumBot("meine-gebote-50-kreuze", bMinus59))
        val p = (a as Aktion.Tippen).punkt
        // On the reference screenshot the red X of the top row is around (2298, 495), radius a good 20 pixels.
        assertTrue(kotlin.math.hypot((p.x - 2298).toDouble(), (p.y - 495).toDouble()) < 12, "$p")
        assertTrue(a.grund.startsWith("press red X in My Biddings") && a.grund.contains("61 too expensive"), a.grund)
    }

    @Test
    fun `My Biddings real - with a remaining time limit of 5 min the 57 (3 min left) fits too`() {
        val a = ersteHandlungInDerListe(aufraeumBot("meine-gebote-50-gemischt", bMinus59.copy(maxRestzeitMin = 5)))
        assertEquals(Punkt(1356, 503), (a as Aktion.Tippen).punkt, a.grund)
    }

    /**
     * Nov 12: on the phone "counter not readable – counting by myself (6 own bids)". The real image shows
     * "Bidding Items: 41" – below 50 (and the limit message was not certain): the bot reads 41, does not clean up and
     * goes straight back to the Bidding Market to keep bidding.
     */
    @Test
    fun `My Biddings real - Bidding Items 41 - back to the Bidding Market and keep bidding`() {
        val gv = GebotsVerwaltung().apply { limitMeldung(jetzt, sicher = false) }
        val bot = AuktionsBot(listOf(bMinus59), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("meine-gebote-41")) }, gebote = gv)
        assertEquals(Punkt(321, 427), getippt(bot.schritt(jetzt)), "back to the Bidding Market")
        assertEquals(41, gv.spielZahl, "counter read")
        assertEquals(41, gv.eintraege, "not counted by itself")
        assertFalse(gv.limitGemeldet, "no 50 limit")
    }

    @Test
    fun `loading screen real - just wait, tap nothing`() {
        val bot = AuktionsBot(listOf(regel), StoppBedingung(), { Erkenner.erkenne(TestBilder.lade("laden-stern")) })
        var t = jetzt
        repeat(10) {
            val e = bot.schritt(t)
            assertTrue(e is Ergebnis.Warten, "$e")
            t = t.plusMillis((e as Ergebnis.Warten).ms)
        }
    }

    @Test
    fun `letters are never read as digits`() {
        // "Current Bid:" in the Bid window – the same color as the number after it.
        assertNull(ZiffernLeser.lies(TestBilder.lade("bid-fenster"), Rechteck(1205, 422, 1430, 480), Schrift.HELL))
        // "Bidding Items:" in My Biddings – white like the number.
        assertNull(ZiffernLeser.lies(TestBilder.lade("meine-gebote-49"), Rechteck(750, 312, 975, 360), Schrift.WEISS))
    }
}
