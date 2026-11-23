package io.github.hustlebot.ihauctionbot.kern

/**
 * Nov 23: buying out has not been tested in the real game yet. A build says whether it offers buyout at all
 * ([freigegeben]; the public app: no). Only then the player's switch "Buyout (beta)" counts – otherwise the bot never
 * buys out, whatever the saved settings say (e.g. after restoring a backup). The rules' saved max buyouts are not
 * changed; they are only not used and not shown.
 */
object BuyoutFreigabe {

    /** `buyoutAn` for [AuktionsBot]: the build offers buyout AND the player switched it on. */
    fun wirksam(freigegeben: Boolean, eingestellt: Boolean): Boolean = freigegeben && eingestellt

    /**
     * The rules as the bot gets them for a run (and as they appear in the log and in reports): without a max buyout where
     * the build does not offer buyout. Only this copy – the saved rules keep their max buyout.
     */
    fun regelnFuerLauf(regeln: List<Suchregel>, freigegeben: Boolean): List<Suchregel> =
        if (freigegeben) regeln else regeln.map { if (it.buyoutMax == null) it else it.copy(buyoutMax = null) }

    /** The part " · buyout up to N SG" of a rule's text – only where the build offers buyout. */
    fun regelText(buyoutMax: Int?, freigegeben: Boolean): String =
        if (freigegeben && buyoutMax != null) " · buyout up to $buyoutMax SG" else ""
}
