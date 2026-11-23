package io.github.hustlebot.ihauctionbot.kern

import io.github.hustlebot.ihauctionbot.kern.KnopfSichtbarkeit.Fenster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nov 9: the IH button should only float over Idle Heroes (and it can be hidden). */
class KnopfSichtbarkeitTest {

    private val eigen = "io.github.hustlebot.ihauctionbot"
    private val spiel = "com.beispiel.idleheroes" // the app learns the real package by itself
    private val claude = "com.anthropic.claude"
    private val startseite = "com.motorola.launcher3"

    @Test
    fun `not learned yet - visible everywhere as before`() {
        val s = KnopfSichtbarkeit(eigen)
        assertTrue(s.sichtbar(aktiv = false), "nothing known yet")
        s.vordergrundGesehen(claude)
        assertTrue(s.sichtbar(aktiv = false), "over Claude, as long as Idle Heroes is not learned")
        assertNull(s.spielPaket)
    }

    @Test
    fun `learned - only over Idle Heroes and our own app`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel)
        s.vordergrundGesehen(spiel)
        assertTrue(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false), "not over the Claude app")
        s.vordergrundGesehen(startseite)
        assertFalse(s.sichtbar(aktiv = false), "not on the home screen")
        s.vordergrundGesehen(eigen)
        assertTrue(s.sichtbar(aktiv = false), "over our own settings, yes")
        s.vordergrundGesehen(spiel)
        assertTrue(s.sichtbar(aktiv = false))
    }

    @Test
    fun `system UI (notifications) changes nothing`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel)
        s.vordergrundGesehen(spiel)
        assertFalse(s.vordergrundGesehen("com.android.systemui"))
        assertFalse(s.vordergrundGesehen("android"))
        assertFalse(s.vordergrundGesehen(null))
        assertFalse(s.vordergrundGesehen("  "))
        assertEquals(spiel, s.vordergrund)
        assertTrue(s.sichtbar(aktiv = false), "notifications over the game: button stays")
    }

    @Test
    fun `bot running or paused or button held - always visible so the user can stop`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel)
        s.vordergrundGesehen(claude)
        assertTrue(s.sichtbar(aktiv = true), "also over Claude while the bot is running")
        assertFalse(s.ausblenden(aktiv = true), "hiding does not work while the bot is running")
        assertFalse(s.ausgeblendet)
        assertTrue(s.sichtbar(aktiv = true))
    }

    @Test
    fun `hide IH - gone until Idle Heroes comes to the front again`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel)
        s.vordergrundGesehen(spiel)
        assertTrue(s.ausblenden(aktiv = false))
        assertFalse(s.sichtbar(aktiv = false), "gone right away, also in the game")
        s.vordergrundGesehen(spiel) // the same game reports again – does not count as "back in front"
        assertFalse(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(eigen) // our own app briefly in front (e.g. a system prompt of the app) – stays hidden
        assertFalse(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(spiel)
        assertTrue(s.sichtbar(aktiv = false), "Idle Heroes in front again → button back")
        assertFalse(s.ausgeblendet)
    }

    @Test
    fun `hide IH - opening the app brings the button back`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel)
        s.vordergrundGesehen(claude)
        s.ausblenden(aktiv = false)
        s.vordergrundGesehen(eigen)
        assertFalse(s.sichtbar(aktiv = false))
        s.appGeoeffnet()
        assertTrue(s.sichtbar(aktiv = false), "visible again over the settings")
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false), "still not over Claude (only in Idle Heroes)")
    }

    @Test
    fun `hide IH without a learned game - until the app is opened`() {
        val s = KnopfSichtbarkeit(eigen)
        s.vordergrundGesehen(claude)
        s.ausblenden(aktiv = false)
        assertFalse(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(startseite)
        assertFalse(s.sichtbar(aktiv = false))
        s.appGeoeffnet()
        assertTrue(s.sichtbar(aktiv = false))
    }

    @Test
    fun `hide IH, then the button is held or the bot runs - visible anyway`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel)
        s.vordergrundGesehen(claude)
        s.ausblenden(aktiv = false)
        assertTrue(s.sichtbar(aktiv = true))
    }

    @Test
    fun `setting off - visible everywhere, hiding still works`() {
        val s = KnopfSichtbarkeit(eigen, spielPaket = spiel, nurImSpiel = false)
        s.vordergrundGesehen(claude)
        assertTrue(s.sichtbar(aktiv = false))
        s.ausblenden(aktiv = false)
        assertFalse(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(spiel)
        assertTrue(s.sichtbar(aktiv = false))
        s.nurImSpiel = true
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false), "setting on again → only in the game")
    }

    @Test
    fun `learning - only other apps, never our own or the system UI`() {
        val s = KnopfSichtbarkeit(eigen)
        assertFalse(s.lernen(eigen))
        assertFalse(s.lernen("com.android.systemui"))
        assertFalse(s.lernen(null))
        assertFalse(s.lernen(""))
        assertNull(s.spielPaket)
        assertTrue(s.lernen(spiel), "newly learned → save")
        assertEquals(spiel, s.spielPaket)
        assertEquals(spiel, s.vordergrund)
        assertFalse(s.lernen(spiel), "already known → nothing to save")
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false))
    }

    @Test
    fun `learning lifts hiding, forgetting shows it everywhere again`() {
        val s = KnopfSichtbarkeit(eigen)
        s.vordergrundGesehen(claude)
        s.ausblenden(aktiv = false)
        assertTrue(s.lernen(spiel))
        assertFalse(s.ausgeblendet)
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false))
        s.vergessen()
        assertNull(s.spielPaket)
        assertTrue(s.sichtbar(aktiv = false), "forgotten → everywhere until learned again")
    }

    @Test
    fun `saved game that is invalid counts as not learned`() {
        assertNull(KnopfSichtbarkeit(eigen, spielPaket = eigen).spielPaket)
        assertNull(KnopfSichtbarkeit(eigen, spielPaket = "com.android.systemui").spielPaket)
        assertNull(KnopfSichtbarkeit(eigen, spielPaket = "").spielPaket)
    }

    @Test
    fun `foreground from the windows - keyboard and IH button do not count`() {
        val tastatur = Fenster("com.google.android.inputmethod.latin", istApp = false, aktiv = true, schicht = 9)
        val knopf = Fenster(null, istApp = false, schicht = 10)
        val leiste = Fenster("com.android.systemui", istApp = false, schicht = 8)
        val sp = Fenster(spiel, istApp = true, aktiv = true, fokus = true, schicht = 2)
        assertEquals(spiel, KnopfSichtbarkeit.vordergrundAus(listOf(leiste, tastatur, knopf, sp)))

        // Notifications pulled down: the system bar is active – the app window is still the game.
        val spNichtAktiv = sp.copy(aktiv = false, fokus = false)
        assertEquals(spiel, KnopfSichtbarkeit.vordergrundAus(listOf(leiste.copy(aktiv = true), spNichtAktiv)))

        // Split screen: the active app window wins, otherwise the focused one, otherwise the topmost.
        val cl = Fenster(claude, istApp = true, schicht = 3)
        assertEquals(spiel, KnopfSichtbarkeit.vordergrundAus(listOf(cl, sp)))
        assertEquals(claude, KnopfSichtbarkeit.vordergrundAus(listOf(cl.copy(fokus = true), spNichtAktiv)))
        assertEquals(claude, KnopfSichtbarkeit.vordergrundAus(listOf(cl, spNichtAktiv)))

        assertNull(KnopfSichtbarkeit.vordergrundAus(listOf(tastatur, knopf)))
        assertNull(KnopfSichtbarkeit.vordergrundAus(listOf(Fenster(null, istApp = true, aktiv = true))))
        assertNull(KnopfSichtbarkeit.vordergrundAus(emptyList()))
    }

    // Review before publishing (Nov 22): allow-list of game apps (packageNames of the accessibility config).

    @Test
    fun `allow-list - only allowed apps can be learned, a saved wrong app is dropped`() {
        val liste = setOf(spiel)
        assertNull(KnopfSichtbarkeit(eigen, spielPaket = claude, erlaubteSpiele = liste).spielPaket, "saved app not on the list")
        val s = KnopfSichtbarkeit(eigen, erlaubteSpiele = liste)
        assertFalse(s.lernen(claude), "another app is never learned")
        assertNull(s.spielPaket)
        assertTrue(s.lernen(spiel))
        assertEquals(spiel, s.spielPaket)
    }

    @Test
    fun `allow-list - button only over the allowed game and this app, even before learning`() {
        val s = KnopfSichtbarkeit(eigen, erlaubteSpiele = setOf(spiel))
        assertTrue(s.sichtbar(aktiv = false), "nothing known yet: better show it")
        s.vordergrundGesehen(claude)
        assertFalse(s.sichtbar(aktiv = false), "not over another app, although nothing was learned yet")
        s.vordergrundGesehen(spiel)
        assertTrue(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(eigen)
        assertTrue(s.sichtbar(aktiv = false))
        assertTrue(s.istSpiel(spiel))
        assertFalse(s.istSpiel(claude))
        assertFalse(s.istSpiel(null))
    }

    @Test
    fun `allow-list - hidden button comes back when an allowed game comes to the front`() {
        val s = KnopfSichtbarkeit(eigen, erlaubteSpiele = setOf(spiel))
        s.vordergrundGesehen(claude)
        s.ausblenden(aktiv = false)
        s.vordergrundGesehen(startseite)
        assertFalse(s.sichtbar(aktiv = false))
        s.vordergrundGesehen(spiel)
        assertTrue(s.sichtbar(aktiv = false))
    }
}
