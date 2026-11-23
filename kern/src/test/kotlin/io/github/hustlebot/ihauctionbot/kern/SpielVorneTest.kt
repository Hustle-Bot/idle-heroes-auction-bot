package io.github.hustlebot.ihauctionbot.kern

import io.github.hustlebot.ihauctionbot.kern.SpielVorne.Art
import io.github.hustlebot.ihauctionbot.kern.SpielVorne.Fenster
import io.github.hustlebot.ihauctionbot.kern.SpielVorne.Lage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Review before publishing (Nov 22): which app is in front, and may a screenshot be kept? */
class SpielVorneTest {

    private val eigen = "io.github.hustlebot.ihauctionbot"
    private val spiel = SpielVorne.IDLE_HEROES
    private val spiele = setOf(spiel)
    private val andere = "com.example.chat"

    /** 64 dp at 440 dpi (2.75). */
    private val dunn = 176

    // A landscape phone, 2712 x 1220.
    private val spielFenster = Fenster(Art.APP, spiel, aktiv = true, fokus = true, schicht = 2, breite = 2712, hoehe = 1220)
    private val statusleiste = Fenster(Art.SYSTEM, schicht = 10, breite = 2712, hoehe = 66)
    private val navileiste = Fenster(Art.SYSTEM, schicht = 11, breite = 132, hoehe = 1220)
    private val ihKnopf = Fenster(Art.UEBERLAGERUNG, schicht = 20, breite = 150, hoehe = 150)
    private val schild = Fenster(Art.UEBERLAGERUNG, schicht = 19, breite = 226, hoehe = 1220)

    @Test
    fun `game alone with bars, IH button and shield - in front and the screenshot may be kept`() {
        val liste = listOf(statusleiste, navileiste, ihKnopf, schild, spielFenster)
        assertEquals(Lage.SPIEL, SpielVorne.lage(liste, spiele, eigen))
        assertTrue(SpielVorne.bildFrei(liste, spiele, dunn))
    }

    @Test
    fun `notification shade or heads-up over the game - still the game in front, but nothing is kept`() {
        val schatten = Fenster(Art.SYSTEM, aktiv = true, schicht = 12, breite = 2712, hoehe = 1220)
        val liste = listOf(schatten, spielFenster.copy(aktiv = false), statusleiste)
        assertEquals(Lage.SPIEL, SpielVorne.lage(liste, spiele, eigen), "the shade is no app – no stop")
        assertFalse(SpielVorne.bildFrei(liste, spiele, dunn))
        val kopf = Fenster(Art.SYSTEM, schicht = 12, breite = 1100, hoehe = 300)
        assertFalse(SpielVorne.bildFrei(listOf(spielFenster, kopf), spiele, dunn), "heads-up notification")
    }

    @Test
    fun `keyboard, floating bubble, toast or split screen - nothing is kept`() {
        val tastatur = Fenster(Art.TASTATUR, schicht = 15, breite = 2712, hoehe = 600)
        assertFalse(SpielVorne.bildFrei(listOf(spielFenster, tastatur), spiele, dunn))
        val blase = Fenster(Art.APP, andere, schicht = 14, breite = 180, hoehe = 180)
        assertEquals(Lage.SPIEL, SpielVorne.lage(listOf(blase, spielFenster), spiele, eigen))
        assertFalse(SpielVorne.bildFrei(listOf(blase, spielFenster), spiele, dunn))
        val toast = Fenster(Art.APP, eigen, schicht = 14, breite = 600, hoehe = 120)
        assertFalse(SpielVorne.bildFrei(listOf(spielFenster, toast), spiele, dunn), "also not with our own toast")
        val unbekannt = Fenster(Art.APP, null, schicht = 3, breite = 1356, hoehe = 1220)
        assertFalse(SpielVorne.bildFrei(listOf(spielFenster, unbekannt), spiele, dunn), "an app that could not be read")
    }

    @Test
    fun `another app, this app or the home screen in front`() {
        val fremd = Fenster(Art.APP, andere, aktiv = true, schicht = 3)
        assertEquals(Lage.FREMD, SpielVorne.lage(listOf(fremd, statusleiste), spiele, eigen))
        assertFalse(SpielVorne.bildFrei(listOf(fremd), spiele, dunn))
        val einstellungen = Fenster(Art.APP, eigen, aktiv = true, schicht = 3)
        assertEquals(Lage.EIGENE_APP, SpielVorne.lage(listOf(einstellungen), spiele, eigen))
        assertFalse(SpielVorne.bildFrei(listOf(einstellungen), spiele, dunn), "never our own screen")
        val system = Fenster(Art.APP, "android", aktiv = true)
        assertEquals(Lage.SYSTEM, SpielVorne.lage(listOf(system), spiele, eigen))
    }

    @Test
    fun `an app that is not on the allow-list counts as another app`() {
        val anderesIh = Fenster(Art.APP, "com.droidhang.other", aktiv = true)
        assertEquals(Lage.FREMD, SpielVorne.lage(listOf(anderesIh), spiele, eigen))
        assertFalse(SpielVorne.bildFrei(listOf(anderesIh), spiele, dunn))
        assertFalse(SpielVorne.bildFrei(listOf(spielFenster), emptySet(), dunn), "no allow-list – nothing is kept")
    }

    @Test
    fun `nothing known - unclear, and nothing is kept`() {
        assertEquals(Lage.UNKLAR, SpielVorne.lage(emptyList(), spiele, eigen))
        assertFalse(SpielVorne.bildFrei(emptyList(), spiele, dunn))
        val ohnePaket = Fenster(Art.APP, null, aktiv = true)
        assertEquals(Lage.UNKLAR, SpielVorne.lage(listOf(ohnePaket, statusleiste), spiele, eigen))
        assertEquals(Lage.UNKLAR, SpielVorne.lage(listOf(statusleiste, ihKnopf), spiele, eigen), "no app window")
    }

    @Test
    fun `the app in front - active, otherwise focused, otherwise topmost`() {
        val a = Fenster(Art.APP, "a", schicht = 1)
        val b = Fenster(Art.APP, "b", schicht = 5)
        assertEquals("b", SpielVorne.vorneApp(listOf(a, b))?.paket)
        assertEquals("a", SpielVorne.vorneApp(listOf(a.copy(fokus = true), b))?.paket)
        assertEquals("b", SpielVorne.vorneApp(listOf(a.copy(fokus = true), b.copy(aktiv = true)))?.paket)
    }

    @Test
    fun `thin bars do not cover, bigger system windows do, unknown size does not`() {
        assertFalse(SpielVorne.deckt(statusleiste, dunn))
        assertFalse(SpielVorne.deckt(navileiste, dunn))
        assertTrue(SpielVorne.deckt(Fenster(Art.SYSTEM, breite = 800, hoehe = 400), dunn))
        assertFalse(SpielVorne.deckt(Fenster(Art.SYSTEM), dunn))
    }

    @Test
    fun `leaving the game - stop about 2 s after the game was last seen, but at least 1 s after noticing`() {
        // Game last seen at 10_000, the other app noticed at 10_900 (poll every second).
        assertEquals(12_000, SpielVorne.stoppUm(fremdSeitMs = 10_900, spielZuletztMs = 10_000, frist = 2_000, mindestens = 1_000))
        // Noticed late (the check hung): still at least 1 s of tolerance.
        assertEquals(15_000, SpielVorne.stoppUm(fremdSeitMs = 14_000, spielZuletztMs = 10_000, frist = 2_000, mindestens = 1_000))
    }

    // ---------------- Review round 2 (Nov 23): windows are never asked which app they belong to ----------------

    /** Window list as the app reports it now: no app names at all, only kind, id, layer, size, active/focused. */
    private fun roh(vararg f: Fenster) = f.map { it.copy(paket = null) }

    private val spielRoh = spielFenster.copy(id = 7)
    private val fremdRoh = Fenster(Art.APP, aktiv = true, fokus = true, schicht = 3, breite = 2712, hoehe = 1220, id = 9)
    private val lang = FensterZuordnung.NEU_MS

    @Test
    fun `the game's window is learned from its own event - then it is in front and the screenshot may be kept`() {
        val z = FensterZuordnung()
        assertTrue(z.lernen(7, spiel, spiele))
        assertFalse(z.lernen(7, spiel, spiele), "known already")
        val liste = z.einordnen(roh(statusleiste, navileiste, ihKnopf, schild, spielRoh), eigeneAppOffen = false, eigen, 1_000)
        assertEquals(Lage.SPIEL, SpielVorne.lage(liste, spiele, eigen))
        assertTrue(SpielVorne.bildFrei(liste, spiele, dunn))
        assertTrue(z.istSpiel(7))
        assertTrue(z.spielBekannt())
    }

    @Test
    fun `only events of an allowed game app teach a window`() {
        val z = FensterZuordnung()
        assertFalse(z.lernen(9, andere, spiele), "an event of another app (cannot come because of packageNames) teaches nothing")
        assertFalse(z.lernen(9, null, spiele))
        assertFalse(z.lernen(-1, spiel, spiele), "no window id")
        assertFalse(z.lernen(9, spiel, emptySet()), "no allow-list")
        assertFalse(z.istSpiel(9))
        assertFalse(z.spielBekannt())
    }

    @Test
    fun `an app window that is not the game - another app, without asking it anything`() {
        val z = FensterZuordnung()
        z.lernen(7, spiel, spiele)
        // Just appeared: not judged yet (its event may still come) – unclear, nothing kept.
        val neu = z.einordnen(roh(fremdRoh, spielRoh.copy(aktiv = false, fokus = false), statusleiste), false, eigen, 1_000)
        assertEquals(Lage.UNKLAR, SpielVorne.lage(neu, spiele, eigen))
        assertFalse(SpielVorne.bildFrei(neu, spiele, dunn))
        // Still there a moment later and no event of the game came for it: another app.
        val spaeter = z.einordnen(roh(fremdRoh, spielRoh.copy(aktiv = false, fokus = false), statusleiste), false, eigen, 1_000 + lang)
        assertEquals(Lage.FREMD, SpielVorne.lage(spaeter, spiele, eigen))
        assertEquals(SpielVorne.FREMDE_APP, SpielVorne.vorneApp(spaeter)?.paket)
        assertFalse(SpielVorne.bildFrei(spaeter, spiele, dunn))
    }

    @Test
    fun `a new window of the game - unclear for a moment, then the game as soon as its event came`() {
        val z = FensterZuordnung()
        z.lernen(7, spiel, spiele)
        val dialog = Fenster(Art.APP, aktiv = true, fokus = true, schicht = 4, breite = 1200, hoehe = 600, id = 11)
        val liste = roh(dialog, spielRoh.copy(aktiv = false, fokus = false))
        assertEquals(Lage.UNKLAR, SpielVorne.lage(z.einordnen(liste, false, eigen, 5_000), spiele, eigen))
        assertTrue(z.lernen(11, spiel, spiele))
        val danach = z.einordnen(liste, false, eigen, 5_100)
        assertEquals(Lage.SPIEL, SpielVorne.lage(danach, spiele, eigen))
        assertTrue(SpielVorne.bildFrei(danach, spiele, dunn), "both windows are the game's")
    }

    @Test
    fun `a toast or bubble over the game - the game stays in front, but nothing is kept`() {
        val z = FensterZuordnung()
        z.lernen(7, spiel, spiele)
        val toast = Fenster(Art.APP, schicht = 14, breite = 600, hoehe = 120, id = 20)
        for (t in listOf(1_000L, 1_000L + lang)) {
            val liste = z.einordnen(roh(spielRoh, toast), false, eigen, t)
            assertEquals(Lage.SPIEL, SpielVorne.lage(liste, spiele, eigen))
            assertFalse(SpielVorne.bildFrei(liste, spiele, dunn))
        }
    }

    @Test
    fun `this app's screen - recognised by the app's own flag and remembered after leaving it`() {
        val z = FensterZuordnung()
        z.lernen(7, spiel, spiele)
        val einstellungen = Fenster(Art.APP, aktiv = true, fokus = true, schicht = 3, id = 30)
        val offen = z.einordnen(roh(einstellungen, statusleiste), eigeneAppOffen = true, eigen, 1_000)
        assertEquals(Lage.EIGENE_APP, SpielVorne.lage(offen, spiele, eigen))
        assertFalse(SpielVorne.bildFrei(offen, spiele, dunn), "never our own screen")
        // The screen is being left (flag off), its window is still in front for a moment: still this app, no "another app".
        val geht = z.einordnen(roh(einstellungen, statusleiste), eigeneAppOffen = false, eigen, 9_000)
        assertEquals(Lage.EIGENE_APP, SpielVorne.lage(geht, spiele, eigen))
        // The game in front while the flag is on (split screen): the game.
        val beide = z.einordnen(roh(spielRoh, einstellungen.copy(aktiv = false, fokus = false, id = 31)), true, eigen, 9_000)
        assertEquals(Lage.SPIEL, SpielVorne.lage(beide, spiele, eigen))
        assertFalse(SpielVorne.bildFrei(beide, spiele, dunn), "our screen is visible next to the game")
    }

    @Test
    fun `game not learned yet (service started while it was in front) - counts as another app, fail closed`() {
        val z = FensterZuordnung()
        z.einordnen(roh(spielRoh), false, eigen, 0)
        val liste = z.einordnen(roh(spielRoh), false, eigen, lang)
        assertEquals(Lage.FREMD, SpielVorne.lage(liste, spiele, eigen))
        assertFalse(SpielVorne.bildFrei(liste, spiele, dunn))
        // Remembered from before (same phone start, e.g. after a crash of the app): the game right away.
        val z2 = FensterZuordnung()
        z2.uebernehmen(mapOf(7 to spiel, 8 to andere), spiele)
        assertEquals(mapOf(7 to spiel), z2.spielFenster(), "only apps on the allow-list are taken over")
        assertEquals(Lage.SPIEL, SpielVorne.lage(z2.einordnen(roh(spielRoh), false, eigen, 0), spiele, eigen))
    }

    @Test
    fun `a window that went away counts as new again when it comes back`() {
        val z = FensterZuordnung()
        z.einordnen(roh(fremdRoh), false, eigen, 0)
        assertEquals(Lage.FREMD, SpielVorne.lage(z.einordnen(roh(fremdRoh), false, eigen, lang), spiele, eigen))
        z.einordnen(roh(statusleiste), false, eigen, 2 * lang)
        assertEquals(Lage.UNKLAR, SpielVorne.lage(z.einordnen(roh(fremdRoh), false, eigen, 3 * lang), spiele, eigen))
    }

    @Test
    fun `only the newest game windows are kept`() {
        val z = FensterZuordnung(maxFenster = 3)
        for (id in 1..5) z.lernen(id, spiel, spiele)
        assertEquals(listOf(3, 4, 5), z.spielFenster().keys.toList())
        assertFalse(z.istSpiel(1))
    }
}
