package io.github.hustlebot.ihauctionbot.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Checks for a run overnight: charger, battery optimization, Do Not Disturb – only warnings, never blockers.
 * Plus the limits for the battery during a run (too hot → pause, almost empty → stop).
 */
object NachtCheck {

    /** Above this battery temperature the bot pauses (no taps) … */
    const val HEISS_C = 45f

    /** … until it is below this again. */
    const val KUEHL_C = 40f

    /** Below this charge (and not charging) the bot stops and locks the screen. */
    const val AKKU_MIN_PROZENT = 15

    /** Review before publishing (Nov 22): warning when no SG budget is set (before a start and in the IH panel). */
    const val KEIN_BUDGET_TEXT = "No SG budget – the bot may spend all your Starry Gems."

    /** Battery state: charge in percent, charging yes/no, temperature in °C (null = unknown). */
    data class Akku(val prozent: Int?, val laedt: Boolean, val tempC: Float?)

    /** Reads the battery state from Android's battery message (ACTION_BATTERY_CHANGED). */
    fun akkuAus(i: Intent?): Akku {
        if (i == null) return Akku(null, false, null)
        val stand = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val skala = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val prozent = if (stand >= 0 && skala > 0) stand * 100 / skala else null
        val laedt = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val zehntel = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temp = if (zehntel != Int.MIN_VALUE) zehntel / 10f else null
        return Akku(prozent, laedt, temp)
    }

    /** The current battery state (the battery message is "sticky": no receiver needed for one look). */
    fun akku(ctx: Context): Akku =
        akkuAus(runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull())

    /** Does Android's battery optimization apply to this app (it may stop the app in the background)? */
    fun akkuOptimiert(ctx: Context): Boolean = runCatching {
        !(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** Is Do Not Disturb off (all notifications may pop up over the game)? */
    fun stoerenErlaubt(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    /** Warnings before a start (empty = all good). Only hints – the bot starts anyway if the player wants. */
    fun warnungen(ctx: Context): List<String> {
        val liste = ArrayList<String>()
        // The most important one first: without a budget the bot may spend every Starry Gem.
        if (EinstellungenSpeicher.laden(ctx).sgBudget == null) liste += KEIN_BUDGET_TEXT
        val akku = akku(ctx)
        if (!akku.laedt) {
            liste += "The phone is not charging" + (akku.prozent?.let { " ($it %)" } ?: "") +
                ". Plug it in for a long run – below $AKKU_MIN_PROZENT % the bot stops by itself."
        }
        if (akkuOptimiert(ctx)) {
            liste += "Battery optimization is on for this app – Android may stop it at night. " +
                "Tap \"Battery settings\" and allow it to run without limits."
        }
        if (stoerenErlaubt(ctx)) {
            liste += "Tip: turn on Do Not Disturb, so that no notification covers the game."
        }
        return liste
    }
}
