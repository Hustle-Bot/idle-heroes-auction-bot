package io.github.hustlebot.ihauctionbot.kern

/**
 * A search rule from the settings, e.g. "C-, filter 10–20".
 * Nov 7: the user sets the copy type and prices; there can be several rules.
 *
 * Nov 9: the two numbers are exactly what goes into the game's filter (Price Range,
 * left [minPreis], right [maxPreis]). The bot bids on rows whose shown price is
 * at most [maxPreis] – so the bid itself (price + 1) is at most [hoechstesGebot].
 * Example B- 10–60: the row shows 60 → the bot bids 61.
 */
data class Suchregel(
    val kopie: String,
    val minPreis: Int,
    val maxPreis: Int,
    val aktiv: Boolean = true,
    /**
     * Only bid if the auction ends in at most this many minutes. null = no limit.
     * Nov 8: C- with 2 h remaining sit at 18 SG at most and get outbid
     * later; the ones for up to 24 SG end after 10 s to 4 min. Long auctions would
     * only block one of the 50 bid slots.
     */
    val maxRestzeitMin: Int? = null,
    /**
     * Nov 22 (owner): buy a copy outright when its "Buyout Price" is at most this many SG (null = never buy out with
     * this rule). Only works when buyout is switched on for the bot ([AuktionsBot] `buyoutAn`). Counts toward the SG budget.
     */
    val buyoutMax: Int? = null,
) {
    /** Filled in sensibly? (copy chosen, prices not negative, min price ≤ max price, remaining time > 0, buyout > 0) */
    val gueltig: Boolean
        get() = kopie.isNotBlank() && minPreis >= 0 && maxPreis >= minPreis && (maxRestzeitMin == null || maxRestzeitMin > 0) &&
            (buyoutMax == null || buyoutMax > 0)

    /** Does the auction end soon enough? If the remaining time is unreadable and a limit is set, the bot does not bid. */
    fun zeitPasst(restzeitSek: Int?): Boolean =
        maxRestzeitMin == null || (restzeitSek != null && restzeitSek <= maxRestzeitMin * 60)

    /** Does this rule apply to the copy type? Only enabled, valid rules count. */
    fun passtTyp(typ: String): Boolean = aktiv && gueltig && typ.trim().equals(kopie.trim(), ignoreCase = true)

    /** Highest bid the bot places (shown price at most [maxPreis], plus 1 SG). */
    val hoechstesGebot: Int get() = maxPreis + GEBOTS_SCHRITT

    /** Is the shown price (Bidding Price) inside the filter range (limits included)? */
    fun erlaubt(angezeigterPreis: Double): Boolean = angezeigterPreis >= minPreis && angezeigterPreis <= maxPreis

    /** Is a bid (= shown price + 1) OK? For the Bid and confirm windows. */
    fun gebotErlaubt(gebot: Int): Boolean = gebot >= minPreis + GEBOTS_SCHRITT && gebot <= hoechstesGebot

    override fun toString(): String = "$kopie: filter $minPreis–$maxPreis, bid at most $hoechstesGebot SG" +
        (maxRestzeitMin?.let { " · only last $it min" } ?: "") +
        (buyoutMax?.let { " · buyout up to $it SG" } ?: "") +
        if (aktiv) "" else " (off)"
}
