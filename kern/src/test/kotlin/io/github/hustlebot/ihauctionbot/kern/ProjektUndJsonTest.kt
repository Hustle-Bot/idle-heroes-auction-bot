package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Nov 22: the releases page of the public repo and the small JSON helper of the reports. */
class ProjektUndJsonTest {

    @Test
    fun `releases page of the public repo`() {
        assertEquals("Hustle-Bot/idle-heroes-auction-bot", Projekt.OEFFENTLICHES_REPO)
        assertEquals("https://github.com/Hustle-Bot/idle-heroes-auction-bot/releases", Projekt.RELEASES_SEITE)
    }

    @Test
    fun `JSON text with quotes, line breaks and control characters`() {
        assertEquals("\"a\\\\b\\t\\u0001\"", Json.text("a\\b\t\u0001"))
        assertEquals("\"say \\\"hi\\\"\\n\\r\"", Json.text("say \"hi\"\n\r"))
        assertEquals("\"café – ok\"", Json.text("café – ok"))
    }
}
