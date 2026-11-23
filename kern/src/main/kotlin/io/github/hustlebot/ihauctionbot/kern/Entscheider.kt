package io.github.hustlebot.ihauctionbot.kern

import java.util.Locale

/** What the bot wants to do with an offer. */
sealed interface Entscheidung {
    val angebot: Angebot

    /** Place a new bid. */
    data class Bieten(override val angebot: Angebot, val preis: Int, val regel: Suchregel) : Entscheidung

    /** I was outbid and bid again (current price + 1 SG). */
    data class Nachbieten(override val angebot: Angebot, val preis: Int, val regel: Suchregel) : Entscheidung

    /** I was outbid, bidding again would be too expensive – give up the offer. */
    data class Loslassen(override val angebot: Angebot, val grund: String) : Entscheidung

    /** Does not fit (other copy, wrong price, I am already in front) – do nothing. */
    data class Ueberspringen(override val angebot: Angebot, val grund: String) : Entscheidung

    /** Would fit, but 50 bids are already running – wait. */
    data class Warten(override val angebot: Angebot, val grund: String) : Entscheidung
}

/** Decides for a single offer whether to bid. Has no state of its own. */
object Entscheider {

    fun entscheide(angebot: Angebot, regeln: List<Suchregel>, kannBieten: Boolean): Entscheidung {
        val typRegeln = regeln.filter { it.passtTyp(angebot.typ) }
        if (typRegeln.isEmpty()) {
            return Entscheidung.Ueberspringen(angebot, "copy ${angebot.typ} is not wanted")
        }
        if (angebot.eigenesGebot && !angebot.ueberboten) {
            return Entscheidung.Ueberspringen(angebot, "I already have the highest bid here")
        }

        // Nov 9: what counts is the shown price (as in the game's filter); the bid is price + 1.
        val proKopie = angebot.preisProKopie(angebot.preis)
        val preisRegeln = typRegeln.filter { it.erlaubt(proKopie) }
        if (preisRegeln.isEmpty()) {
            val text = "price ${sg(proKopie)} SG does not fit the filter ${typRegeln.joinToString { "${it.minPreis}–${it.maxPreis}" }}"
            return if (angebot.eigenesGebot) {
                Entscheidung.Loslassen(angebot, "bid again: $text")
            } else {
                Entscheidung.Ueberspringen(angebot, text)
            }
        }

        // Nov 8: only bid on auctions that end soon (otherwise the bid blocks a slot for a long time).
        // Do not let go when bidding again either: the auction can still slip into the time limit later.
        val regel = preisRegeln.firstOrNull { it.zeitPasst(angebot.restzeitSek) }
        if (regel == null) {
            val grenze = preisRegeln.mapNotNull { it.maxRestzeitMin }.maxOrNull()
            val text = angebot.restzeitSek?.let { "ends only in ${dauer(it)}" } ?: "remaining time unreadable"
            return Entscheidung.Ueberspringen(angebot, "$text (rule: only last $grenze min)")
        }

        if (!kannBieten) return Entscheidung.Warten(angebot, "all allowed bids are already running")

        return if (angebot.eigenesGebot) {
            Entscheidung.Nachbieten(angebot, angebot.gebotsPreis, regel)
        } else {
            Entscheidung.Bieten(angebot, angebot.gebotsPreis, regel)
        }
    }

    /** Remaining time as text, e.g. "1 h 35 min" or "3 min 20 s". */
    private fun dauer(sek: Int): String {
        val h = sek / 3600
        val m = sek % 3600 / 60
        val s = sek % 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min $s s"
            else -> "$s s"
        }
    }

    private fun sg(wert: Double): String =
        if (wert % 1.0 == 0.0) wert.toLong().toString() else String.format(Locale.GERMANY, "%.2f", wert)
}
