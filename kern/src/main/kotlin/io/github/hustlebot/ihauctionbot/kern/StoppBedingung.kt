package io.github.hustlebot.ihauctionbot.kern

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Why the bot stopped. [sperren] = turn the screen off afterwards.
 * Nov 11: the phone is locked on EVERY stop, except a manual stop (then the user is right there anyway).
 */
enum class StoppGrund(val text: String, val sperren: Boolean) {
    ZIEL_ERREICHT("Goal reached – bought enough copies", true),
    ZEIT_ERREICHT("End time reached", true),
    VON_HAND("Stopped by hand", false),

    // Nov 11 (pocket mode): stopped from outside – here the app itself decides whether to lock the phone.

    /** The screen went off (power button). */
    BILDSCHIRM_AUS("Screen went off (power button) – bot stopped", false),

    /** Someone touched the screen (shield in pocket mode). */
    BERUEHRT("Screen touched – bot stopped", false),

    /** Emergency stop: both volume buttons pressed at the same time. */
    NOTAUS("Emergency stop (both volume buttons) – bot stopped", false),

    /** The game is no longer in front (another app or the home screen is showing). */
    SPIEL_NICHT_VORNE("The game is no longer in front – bot stopped", false),

    NICHTS_ERKANNT("Did not recognize the auction house for too long", true),
    KEINE_REGEL("No matching search rule (only C- to SSS can be filtered)", true),
    FILTER_GEHT_NICHT("Could not set the filter", true),

    /** Nov 9: the secondary password window came back after "Verify" – do not try again. */
    /** Nov 9: "spend at most X SG" reached, no bids are running any more. */
    BUDGET_ERREICHT("SG budget spent", true),

    ZWEITPASSWORT_FALSCH("Wrong secondary password? The game did not accept it", true),
    ZWEITPASSWORT_EINGABE("Could not enter the secondary password", true),

    /** Nov 9: "Lost connection with game server." → just stop (tap nothing), screen off. */
    VERBINDUNG_WEG("Lost connection to the game – bot stopped", true),

    /**
     * Nov 22 (buyout): after tapping "Buyout Price" a confirmation window appeared that the bot does not know yet. It taps
     * nothing and stops; the report keeps the screenshot so the window can be learned.
     */
    BUYOUT_UNBEKANNT("Buyout: unknown confirmation window – save a report so this window can be learned", true),

    /** Nov 9 (field test 1.8): the confirm window did not close with the red X (the bot never taps Cancel). */
    FENSTER_KLEMMT("A window did not close – bot stopped so it does not tap anything wrong", true),

    /**
     * Nov 11: after the red X in My Biddings a window appeared that the bot does not know – it taps nothing more
     * and stops (a report with the screenshot is created automatically).
     */
    UNBEKANNT_NACH_X("Unknown window after X in My Biddings – please save a report and check the GUIDE", true),

    /**
     * Nov 11 (pocket mode): the bot's taps got stuck on the shield several times (they did not reach the game) –
     * better to stop than to keep running blind.
     */
    TASCHENMODUS_TIPPS("Pocket mode: the bot's taps did not get through – bot stopped, please save a report and check the GUIDE", true),

    /**
     * Nov 11 (long run): too little SG for any bid, and none of our own bids is in front any more (nothing can bring
     * SG back) – instead of waiting for hours, the bot stops after 10 minutes (screen off).
     */
    SG_ALLE("Not enough SG left for any bid – bot stopped", true),

    // Nov 11 (review: "never wait silently forever"): if the bot waits too long for something, it stops with a reason.
    // The phone is locked and a report is created automatically.

    /** The game asks for the secondary password, but none is saved in the app (waited 5 minutes). */
    ZWEITPASSWORT_FEHLT("Secondary password missing – please enter it in the settings", true),

    /** A message stayed on top of the list or in the window for minutes (nothing tapped). */
    MELDUNG_HAENGT("A message did not go away for minutes – bot stopped", true),

    /** The SG balance at the top right was unreadable for minutes – the bot does not bid without it. */
    SG_UNLESBAR("SG balance unreadable for minutes – bot stopped", true),

    /** The remaining times in the list were unreadable for minutes – the bot does not bid without a remaining time. */
    RESTZEIT_UNLESBAR("Remaining times unreadable for minutes – bot stopped", true),

    /**
     * Nov 22 (rate limit): the game said "Operated too frequently" 5 times in a row (no bid in between), although the bot
     * paused 5, 10, 20 and 40 s – it stops instead of pressing on.
     */
    ZU_OFT("The game said \"Operated too frequently\" 5 times in a row – bot stopped", true),

    /**
     * Nov 22 (review): after a bid, our own row (clock) showed a price above the rule's highest bid (max price + 1) on two
     * screenshots – something went wrong (e.g. a misread number). Stop and keep the screenshots for the report.
     */
    GEBOT_ZU_HOCH("A bid shows a price above the rule's maximum – bot stopped, please save a report", true),

    /** Overnight check (app): the battery is almost empty and the phone is not charging – stop and lock the screen. */
    AKKU_SCHWACH("Battery below 15 % and not charging – bot stopped", true),
}

/**
 * Nov 7: the bot stops when the number of copies OR the end time is reached –
 * whichever comes first.
 *
 * @param zielKopien How many copies to buy (null = no limit).
 * @param endeUm     Time of day to stop (null = none). If it is before the start time, the next day is meant.
 * @param sgBudget   Nov 9: spend at most this many SG, counted from the SG balance at start
 *                   (null = no limit). Running bids count too; after being outbid the SG come back.
 *                   The bot checks this before every bid (copies, time, budget – whichever comes first).
 * @param zone       Time zone for [endeUm] and the times in the log: the device's time zone (tests pass a fixed one).
 */
class StoppBedingung(
    val zielKopien: Int? = null,
    val endeUm: LocalTime? = null,
    val sgBudget: Int? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The exact time to stop, counted from the start. */
    fun endZeitpunkt(start: Instant): Instant? {
        val ende = endeUm ?: return null
        val s = start.atZone(zone)
        var e = s.with(ende)
        if (!e.isAfter(s)) e = e.plusDays(1)
        return e.toInstant()
    }

    /** Returns the reason to stop, or null if the bot goes on. */
    fun pruefe(gekauft: Int, jetzt: Instant, ende: Instant?): StoppGrund? {
        if (zielKopien != null && zielKopien > 0 && gekauft >= zielKopien) return StoppGrund.ZIEL_ERREICHT
        if (ende != null && !jetzt.isBefore(ende)) return StoppGrund.ZEIT_ERREICHT
        return null
    }
}
