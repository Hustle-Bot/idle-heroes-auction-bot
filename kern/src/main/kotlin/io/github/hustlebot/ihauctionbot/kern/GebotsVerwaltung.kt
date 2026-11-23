package io.github.hustlebot.ihauctionbot.kern

import java.time.Duration
import java.time.Instant

/**
 * Remembers our own bids, watches the limit of 50 bids and counts the copies bought.
 *
 * Nov 7: at most 50 bids at the same time. Nov 8: "Server status code:-7" in the Bid window
 * means exactly that – then the bot waits until there is room again.
 *
 * The bot counts purchases like this (the game does not report a win, Nov 8):
 * if the remaining time of an auction is over and my bid was in front at the last look (clock),
 * it counts as won. If the hammer or the red cross showed last, it counts as lost.
 * This is an estimate – someone who outbids shortly before the end is not always seen.
 */
class GebotsVerwaltung(val limit: Int = 50) {

    companion object {
        /** Nov 11 (review): a bid without a known end counts for at most this long after the last look at it. */
        val ENDE_UNBEKANNT_MAX: Duration = Duration.ofHours(24)
    }

    enum class Stand { VORNE, UEBERBOTEN }

    class Gebot(
        val id: String,
        val kopie: String,
        var preis: Int,
        var endeUm: Instant?,
        var stand: Stand,
        var zuletztGesehen: Instant,
        /** Nov 9 (field test 1.9): when I last bid on this copy. */
        var abgegebenUm: Instant? = null,
        /** Nov 11: is the copy in My Biddings? (false = its red X was pressed) */
        var inListe: Boolean = true,
    )

    private val gebote = LinkedHashMap<String, Gebot>()

    /**
     * Nov 12 (field test: "the X does not work"): auctions that are already over according to the bookkeeping (id → end).
     * Their row sometimes stays in My Biddings for a while – the game then no longer accepts the red X.
     */
    private val beendet = LinkedHashMap<String, Instant>()

    /** When the auction [id] ended according to the bookkeeping (null = not known or still running). */
    fun beendetUm(id: String): Instant? = beendet[id]

    /** "Bidding Items: N" from My Biddings and when it was read. */
    var spielZahl: Int? = null
        private set
    var spielZahlUm: Instant? = null
        private set

    /**
     * Nov 11: roughly this many entries are in My Biddings. Every bid stays in there and counts –
     * even if it was outbid (hammer/red X do NOT lower the number) – until its red X is pressed (−1) or
     * the auction ends (−1). = "Bidding Items: N" at the last look into My Biddings (0 at the start) + every new
     * copy since then (+1; bidding again on a copy that is already in there does not count). Example: 30 bids, 5 of them
     * outbid → still 30. May be too high – then the bot just looks into My Biddings a bit earlier.
     * At [limit] it cleans up there before the game reports "-7".
     */
    var eintraege: Int = 0
        private set
    private var seitZaehlungGeboten = 0
    private var limitMeldungUm: Instant? = null

    /**
     * Nov 9 (field test 1.10/1.11): after "Server status code:-7" the bot looks ONCE into My Biddings to see when the
     * first bid ends – and only bids again from that time on. null = not checked yet.
     */
    var wiederBietenAb: Instant? = null
        private set

    /**
     * Was the limit message certain (My Biddings almost full, or the same message came again after a new attempt)?
     * Only an uncertain message may be cancelled as a false alarm by a low counter in My Biddings.
     */
    var limitSicher: Boolean = false
        private set

    var gekaufteKopien = 0
        private set
    var ausgegebenSg = 0L
        private set

    /** Nov 22 (buyout): a copy was bought outright for [preis] SG (counts like a won auction). */
    fun sofortGekauft(preis: Int) {
        gekaufteKopien++
        ausgegebenSg += preis
    }
    var verloren = 0
        private set
    var abgegebeneGebote = 0
        private set

    /**
     * Nov 9: how many times in a row I was outbid without winning anything.
     * Reset to 0 on a win and when the rank changes.
     */
    var ueberbotenInFolge = 0
        private set

    fun ueberbotenZuruecksetzen() {
        ueberbotenInFolge = 0
    }

    private fun wirdUeberboten(g: Gebot) {
        if (g.stand != Stand.UEBERBOTEN) ueberbotenInFolge++
        g.stand = Stand.UEBERBOTEN
    }

    /**
     * How many bids are running right now – only ROUGHLY (number from the game + bids placed since, otherwise own count).
     * Nov 9: only used for display now ("approx. 12 bids") – bidding decisions no longer depend on it.
     */
    val laufend: Int
        get() = spielZahl?.let { it + seitZaehlungGeboten } ?: gebote.size

    /**
     * May a new bid be placed? Nov 9: whether 50 bids are running is decided by the game alone ("-7") –
     * our own count no longer blocks anything. After "-7" only again from [wiederBietenAb].
     */
    val kannBieten: Boolean
        get() = limitMeldungUm == null

    val limitGemeldet: Boolean get() = limitMeldungUm != null

    fun gebot(id: String): Gebot? = gebote[id]

    /** A bid was confirmed (new or bid again). */
    fun gebotAbgegeben(id: String, kopie: String, preis: Int, restzeitSek: Int?, jetzt: Instant) {
        abgegebeneGebote++
        val ende = restzeitSek?.let { jetzt.plusSeconds(it.toLong()) }
        val alt = gebote[id]
        if (alt == null) {
            gebote[id] = Gebot(id, kopie, preis, ende, Stand.VORNE, jetzt, abgegebenUm = jetzt)
            seitZaehlungGeboten++
            eintraege++
        } else {
            // Nov 11: if the copy was dismissed in My Biddings, it is back in there with this bid.
            if (!alt.inListe) {
                alt.inListe = true
                eintraege++
            }
            alt.preis = preis
            alt.stand = Stand.VORNE
            alt.zuletztGesehen = jetzt
            alt.abgegebenUm = jetzt
            if (ende != null) alt.endeUm = ende
        }
    }

    /**
     * Nov 9 (field test 1.9): did I bid on this copy just now (within [GEBOT_FRISCH_MS])?
     * Then the row often still shows the old icon – it still counts as mine (in front).
     */
    fun frisch(id: String, jetzt: Instant): Boolean {
        val um = gebote[id]?.abgegebenUm ?: return false
        return Duration.between(um, jetzt).toMillis() < GEBOT_FRISCH_MS
    }

    /**
     * Nov 9: a bid did NOT arrive after all (no icon on the row) – take it out again.
     * If it was a re-bid ([warNeu] = false), the copy stays as "outbid".
     */
    fun gebotNichtAngekommen(id: String, warNeu: Boolean) {
        abgegebeneGebote = (abgegebeneGebote - 1).coerceAtLeast(0)
        if (warNeu) {
            if (gebote.remove(id) != null && seitZaehlungGeboten > 0) seitZaehlungGeboten--
            eintraege = (eintraege - 1).coerceAtLeast(0)
        } else {
            gebote[id]?.stand = Stand.UEBERBOTEN
        }
    }

    /**
     * The game reports: already 50 bids ("Server status code:-7"). After that the bot must look into My Biddings
     * to see when the first bid ends ([wiederBietenAb] is null until then). [sicher]: see [limitSicher].
     */
    fun limitMeldung(jetzt: Instant, sicher: Boolean = true) {
        limitMeldungUm = jetzt
        wiederBietenAb = null
        limitSicher = sicher
    }

    /** Nov 9: read from My Biddings – only bid again from [ab] on. */
    fun limitWartenBis(ab: Instant) {
        if (limitMeldungUm != null) wiederBietenAb = ab
    }

    /** The limit message was a false alarm (My Biddings shows clearly fewer than 50) – keep bidding right away. */
    fun limitAufheben() {
        limitMeldungUm = null
        wiederBietenAb = null
        limitSicher = false
    }

    /** Is the waiting time after "-7" over? Then the lock is lifted (true = just lifted). */
    fun limitWartezeitVorbei(jetzt: Instant): Boolean {
        val ab = wiederBietenAb ?: return false
        if (limitMeldungUm == null || jetzt.isBefore(ab)) return false
        limitAufheben()
        return true
    }

    /**
     * Nov 11 (long run: opened the wrong row in My Biddings): is one of our own auctions in My Biddings ending right now
     * (about [vorSek] s ago to [nachSek] s from now)? Then its row disappears in a moment and everything below it
     * moves up – even if it is currently above the visible part.
     */
    fun endetGleich(jetzt: Instant, vorSek: Long = 2, nachSek: Long = 4): Boolean = gebote.values.any { g ->
        val e = g.endeUm
        g.inListe && e != null && e.isAfter(jetzt.minusSeconds(vorSek)) && e.isBefore(jetzt.plusSeconds(nachSek))
    }

    /** When does the next own bid that is still running end (from our own bookkeeping)? */
    fun naechstesEnde(jetzt: Instant): Instant? =
        gebote.values.mapNotNull { it.endeUm }.filter { it.isAfter(jetzt) }.minOrNull()

    /** One of our own rows was seen (in the bidding list or in My Biddings). */
    fun gesehen(zeile: Zeile, jetzt: Instant) {
        val id = zeile.id ?: return
        val g = gebote[id] ?: return
        g.zuletztGesehen = jetzt
        zeile.restzeitSek?.let { g.endeUm = jetzt.plusSeconds(it.toLong()) }
        // Nov 9 (field test 1.9): right after my bid the screenshot may still show the old state
        // (hammer, old price) – that is not being outbid.
        if (frisch(id, jetzt) && zeile.zeichen != Zeichen.UHR) return
        zeile.preis?.let { if (it > g.preis && zeile.zeichen != Zeichen.UHR) wirdUeberboten(g) }
        when {
            zeile.ueberbotenKreuz || zeile.zeichen == Zeichen.HAMMER -> wirdUeberboten(g)
            zeile.zeichen == Zeichen.UHR -> g.stand = Stand.VORNE
        }
    }

    /**
     * My Biddings was read: take over the counter (only used for display and to check whether "-7" was a false alarm).
     * If the counter is unreadable, the bot counts by itself again (own open bids).
     * Nov 9: this does NOT lift the lock after "-7" any more – [limitWartenBis] / [limitAufheben] take care of that.
     */
    fun meineGeboteGelesen(zahl: Int?, zeilen: List<Zeile>, jetzt: Instant) {
        zeilen.forEach { gesehen(it, jetzt) }
        if (zahl != null) {
            spielZahl = zahl
            spielZahlUm = jetzt
            seitZaehlungGeboten = 0
            eintraege = zahl
        } else {
            spielZahl = null
            spielZahlUm = null
            seitZaehlungGeboten = 0
        }
    }

    /**
     * Nov 11: the red X of this copy was pressed in My Biddings – one entry less. [zaehlen] = false
     * if "Bidding Items" was read on the same screenshot (then the number is already right – do not subtract twice).
     */
    fun weggedrueckt(id: String?, zaehlen: Boolean = true) {
        if (zaehlen) eintraege = (eintraege - 1).coerceAtLeast(0)
        id?.let { gebote[it]?.inListe = false }
    }

    /**
     * Nov 11: the counter in My Biddings was unreadable, but the bot saw the whole list – then the number of
     * rows seen counts.
     */
    fun eintraegeGezaehlt(n: Int) {
        eintraege = n.coerceAtLeast(0)
    }

    /**
     * Emergency exit: if the bot does not get to My Biddings at all after "-7" (button not found …), the lock lasts at
     * most [dauer]. With a waiting time that was read ([wiederBietenAb]) that time applies, not the emergency exit.
     */
    fun limitSperreAbgelaufen(jetzt: Instant, dauer: Duration = Duration.ofMinutes(6)): Boolean {
        val um = limitMeldungUm ?: return false
        if (wiederBietenAb != null) return false
        if (Duration.between(um, jetzt) < dauer) return false
        limitAufheben()
        return true
    }

    /**
     * Evaluate auctions whose time has run out. Returns texts for the log.
     * [nachlauf]: wait this long after the end before deciding.
     */
    fun auswerten(
        jetzt: Instant,
        nachlauf: Duration = Duration.ofSeconds(20),
        /**
         * Nov 11 (long run: goal 15 → really bought only 9): SG check. If a bid where I am in front gets outbid,
         * the game gives the SG back. If the bot did not see that, there are more SG than the bookkeeping
         * expects (start − bought − tied up in front). If an auction where I was "in front" then ends, and this
         * surplus covers its price, it counts as lost. Only with a reliably read SG balance ([sgJetzt]) and only if
         * the bookkeeping knows all bids since [startSg] (otherwise pass null).
         */
        sgJetzt: Int? = null,
        startSg: Int? = null,
        /**
         * Nov 11 (long run): if there is no reliable SG balance right now ([sgJetzt] = null), wait up to [abgleichWartenMax]
         * longer before evaluating an auction where I was in front – then the check usually works after all.
         */
        abgleichWarten: Boolean = false,
        abgleichWartenMax: Duration = Duration.ofSeconds(60),
    ): List<String> {
        val texte = ArrayList<String>()
        val gebunden = gebote.values.filter { it.stand == Stand.VORNE }.sumOf { it.preis.toLong() }
        var ueberschuss = if (sgJetzt != null && startSg != null) sgJetzt - (startSg - ausgegebenSg - gebunden) else 0L
        // If that does not add up (misread, restart, SG bought …), better not use it.
        if (ueberschuss < 0 || ueberschuss > gebunden) ueberschuss = 0
        val it = gebote.values.iterator()
        while (it.hasNext()) {
            val g = it.next()
            val ende = g.endeUm
            if (ende == null) {
                // Nov 11 (review): end never read. New bids without a readable remaining time no longer exist – to be
                // safe, such a bid no longer counts after a day without a look at it (never wait forever).
                if (Duration.between(g.zuletztGesehen, jetzt) >= ENDE_UNBEKANNT_MAX) {
                    it.remove()
                    if (g.inListe) eintraege = (eintraege - 1).coerceAtLeast(0)
                    texte += "End unknown: ${g.kopie} (${g.id}) – no longer counted"
                }
                continue
            }
            if (jetzt.isBefore(ende.plus(nachlauf))) continue
            if (abgleichWarten && sgJetzt == null && g.stand == Stand.VORNE && jetzt.isBefore(ende.plus(nachlauf).plus(abgleichWartenMax))) continue
            it.remove()
            beendet[g.id] = ende
            beendet.values.removeIf { Duration.between(it, jetzt) > Duration.ofHours(1) }
            // Nov 11: auction over → the entry is no longer in My Biddings (if it was still in there). If it was already
            // over at the last look at "Bidding Items", it was no longer counted there – then do not subtract it
            // again (test against the fake game: otherwise the number was too small and "-7" came after all).
            val gezaehlt = spielZahlUm?.let { ende.isAfter(it) } ?: true
            if (g.inListe && gezaehlt) eintraege = (eintraege - 1).coerceAtLeast(0)
            if (g.stand == Stand.VORNE && ueberschuss >= g.preis) {
                ueberschuss -= g.preis
                verloren++
                texte += "Lost: ${g.kopie} (${g.id}) – last seen in front, but the ${g.preis} SG came back (outbid)"
                continue
            }
            if (g.stand == Stand.VORNE) {
                gekaufteKopien++
                ueberbotenInFolge = 0
                ausgegebenSg += g.preis
                texte += "Bought (estimated): ${g.kopie} for ${g.preis} SG (${g.id})"
            } else {
                verloren++
                texte += "Lost: ${g.kopie} (${g.id}), last outbid"
            }
        }
        return texte
    }

    /** Are our own bids running right now that end soon? (Then a look into My Biddings is worthwhile.) */
    fun offeneGebote(): Int = gebote.size

    /**
     * Nov 11 (long run): in this many running auctions I am in front (according to the last look) – they will
     * probably be bought, and only their SG come back when outbid.
     */
    fun vorneOffen(): Int = gebote.values.count { it.stand == Stand.VORNE }

    /** Nov 22 (buyout): the amounts of our bids in front – if one is outbid, exactly this amount comes back. */
    fun vornePreise(): List<Int> = gebote.values.filter { it.stand == Stand.VORNE }.map { it.preis }

    /**
     * Nov 23 (review: "the SG budget can grow"): the SG this run has used by its own bookkeeping, net – bought copies
     * (estimated, buyouts included) plus the bids in front right now. Outbid bids do not count (the game gives their SG back).
     * Needs no SG balance reading, so neither a misread nor a top-up changes it. After a restart within the same run the
     * earlier bids are missing here – then the SG balance counts (see AuktionsBot.budgetAusgegeben).
     */
    fun nettoSg(): Long = ausgegebenSg + gebote.values.filter { it.stand == Stand.VORNE }.sumOf { it.preis.toLong() }
}
