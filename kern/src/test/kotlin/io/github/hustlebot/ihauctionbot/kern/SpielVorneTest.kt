package io.github.hustlebot.ihauctionbot.kern

import io.github.hustlebot.ihauctionbot.kern.SpielVorne.Art
import io.github.hustlebot.ihauctionbot.kern.SpielVorne.Fenster
import io.github.hustlebot.ihauctionbot.kern.SpielVorne.Lage
import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        assertFalse(SpielVorne.punktFrei(liste, spiele, 1356, 610), "no gesture either")
        // Review round 3 (Nov 23): nothing is taken over from before a restart of the app – only the game's next event
        // makes its window known again.
        assertTrue(z.spielFenster().isEmpty())
        assertTrue(z.lernen(7, spiel, spiele))
        assertEquals(Lage.SPIEL, SpielVorne.lage(z.einordnen(roh(spielRoh), false, eigen, 2 * lang), spiele, eigen))
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

    // ---------------- Review round 3 (Nov 23): overlays of other apps over the game ----------------

    private val breite = 2712
    private val hoehe = 1220

    /** Another app's chat head (an overlay – Android reports it as a system window), small enough to pass [SpielVorne.bildFrei]. */
    private val chatKopf = Fenster(Art.SYSTEM, schicht = 13, breite = 165, hoehe = 165, links = 2400, oben = 500)

    /** A caller-ID strip of another app across the top of the screen. */
    private val anruferStreifen = Fenster(Art.SYSTEM, schicht = 14, breite = 1600, hoehe = 140, links = 556, oben = 200)

    @Test
    fun `game alone on the whole screen - nothing to black out`() {
        val liste = listOf(ihKnopf, schild, spielFenster)
        assertEquals(emptyList<Rechteck>(), SpielVorne.schwaerzen(liste, spiele, breite, hoehe))
    }

    @Test
    fun `a chat head or caller-ID strip over the game - kept screenshot is black there, the rest stays`() {
        val liste = listOf(chatKopf, anruferStreifen, ihKnopf.copy(links = 2500, oben = 40), spielFenster)
        assertTrue(SpielVorne.bildFrei(liste, spiele, dunn), "small overlays pass the old check – that was the gap")
        assertEquals(
            listOf(Rechteck(2400, 500, 2565, 665), Rechteck(556, 200, 2156, 340)),
            SpielVorne.schwaerzen(liste, spiele, breite, hoehe),
            "only the two overlays – never the IH button",
        )
    }

    @Test
    fun `status bar over the game and a window partly outside - only the part over the game, plus everything outside`() {
        // The game leaves room for a navigation bar on the right (3 buttons, landscape).
        val spielSchmal = spielFenster.copy(breite = 2580)
        val navi = navileiste.copy(links = 2580)
        val oben = statusleiste
        val halb = Fenster(Art.SYSTEM, schicht = 12, breite = 200, hoehe = 100, links = 2500, oben = 700)
        val liste = listOf(oben, navi, halb, spielSchmal)
        val schwarz = SpielVorne.schwaerzen(liste, spiele, breite, hoehe)!!
        assertTrue(Rechteck(2580, 0, 2712, 1220) in schwarz, "outside the game (the navigation bar's place)")
        assertTrue(Rechteck(0, 0, 2580, 66) in schwarz, "the status bar where it lies over the game")
        assertTrue(Rechteck(2500, 700, 2580, 800) in schwarz, "only the part over the game")
        assertEquals(3, schwarz.size)
    }

    @Test
    fun `screenshot and window list do not fit together, or the game is not in front - do not save at all`() {
        val liste = listOf(chatKopf, spielFenster)
        assertNull(SpielVorne.schwaerzen(liste, spiele, hoehe, breite), "screenshot in the other orientation")
        assertNull(SpielVorne.schwaerzen(listOf(spielFenster.copy(breite = 0, hoehe = 0)), spiele, breite, hoehe), "no size")
        val fremd = Fenster(Art.APP, andere, aktiv = true, breite = breite, hoehe = hoehe)
        assertNull(SpielVorne.schwaerzen(listOf(fremd), spiele, breite, hoehe), "another app in front")
        assertNull(SpielVorne.schwaerzen(liste, spiele, 0, 0))
    }

    @Test
    fun `a gesture under another window is not sent, next to it it is`() {
        val liste = listOf(chatKopf, anruferStreifen, spielFenster, statusleiste)
        assertFalse(SpielVorne.punktFrei(liste, spiele, 2450, 550), "under the chat head")
        assertFalse(SpielVorne.punktFrei(liste, spiele, 1000, 250), "under the caller-ID strip")
        assertFalse(SpielVorne.punktFrei(liste, spiele, 1000, 30), "under the status bar")
        assertTrue(SpielVorne.punktFrei(liste, spiele, 2000, 550), "next to the chat head")
        assertTrue(SpielVorne.punktFrei(liste, spiele, 1000, 400), "below the strip")
        // The edges: the window's bounds are [links, links + breite).
        assertFalse(SpielVorne.punktFrei(liste, spiele, 2400, 500))
        assertTrue(SpielVorne.punktFrei(liste, spiele, 2565, 665))
    }

    @Test
    fun `this app's IH button and shield do not stop a gesture, the keyboard and unknown windows do`() {
        val knopf = ihKnopf.copy(links = 1300, oben = 500)
        val schildVoll = schild.copy(breite = breite, hoehe = hoehe)
        assertTrue(SpielVorne.punktFrei(listOf(knopf, schildVoll, spielFenster), spiele, 1350, 550))
        val tastatur = Fenster(Art.TASTATUR, schicht = 15, breite = breite, hoehe = 600, oben = 620)
        assertFalse(SpielVorne.punktFrei(listOf(tastatur, spielFenster), spiele, 1356, 1000))
        assertTrue(SpielVorne.punktFrei(listOf(tastatur, spielFenster), spiele, 1356, 300))
        val unbekannt = Fenster(Art.APP, null, schicht = 6, breite = 800, hoehe = 400, links = 900, oben = 400)
        assertFalse(SpielVorne.punktFrei(listOf(unbekannt, spielFenster), spiele, 1300, 600), "not known yet - fail closed")
        val anderes = Fenster(Art.ANDERES, schicht = 6, breite = 40, hoehe = hoehe, links = 1336)
        assertFalse(SpielVorne.punktFrei(listOf(anderes, spielFenster), spiele, 1350, 600), "e.g. a split-screen divider")
    }

    @Test
    fun `a second window of the game on top is fine, a window below the game does not count`() {
        val dialog = Fenster(Art.APP, spiel, schicht = 5, breite = 1200, hoehe = 600, links = 756, oben = 300)
        assertTrue(SpielVorne.punktFrei(listOf(dialog, spielFenster), spiele, 1356, 610))
        val darunter = Fenster(Art.SYSTEM, schicht = 1, breite = 400, hoehe = 400, links = 1200, oben = 400)
        assertTrue(SpielVorne.punktFrei(listOf(spielFenster, darunter), spiele, 1356, 610), "covered by the game")
        val gleich = darunter.copy(schicht = spielFenster.schicht)
        assertFalse(SpielVorne.punktFrei(listOf(spielFenster, gleich), spiele, 1356, 610), "same layer - fail closed")
    }

    @Test
    fun `no game window at the point, or the game not in front - no gesture`() {
        val spielSchmal = spielFenster.copy(breite = 2580)
        assertFalse(SpielVorne.punktFrei(listOf(spielSchmal), spiele, 2650, 600), "outside the game's window")
        val fremd = Fenster(Art.APP, andere, aktiv = true, schicht = 3, breite = breite, hoehe = hoehe)
        assertFalse(SpielVorne.punktFrei(listOf(fremd, spielFenster.copy(aktiv = false, fokus = false)), spiele, 1356, 610))
        assertFalse(SpielVorne.punktFrei(emptyList(), spiele, 1356, 610))
    }
}
