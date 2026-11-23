package io.github.hustlebot.ihauctionbot.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import java.io.File

/**
 * This app ("oss") has no internet access. Reports and the flight recorder stay in the app's private storage; the
 * player can share a report himself (Settings → Reports → "Share latest report", Android's share sheet).
 *
 * Small hooks the shared code calls (battery settings, IH panel, run start/step/end, reports). Here they do exactly
 * what is written below – most of them nothing.
 */
object Variante {

    // ---------------- Text recognition ----------------

    /**
     * Review round 2 (Nov 23): Google's text recognition (ML Kit through Google Play services) as a second reader for
     * numbers – off unless the player turns it on (Advanced settings). Off, it is never set up; the bot reads every
     * number itself.
     */
    const val MLKIT_STANDARD = false

    // ---------------- Battery ----------------

    /**
     * "Battery settings": Android's list of apps with battery optimization (there the player can exempt this app),
     * otherwise the battery saver settings. The app does not ask for any extra permission for this.
     */
    fun akkuEinstellungen(act: Activity) {
        val wege = listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        for (w in wege) if (runCatching { act.startActivity(w) }.isSuccess) return
    }

    // ---------------- IH button, panel and main screen ----------------

    /** Why the IH button must stay on screen right now (text for the player); null = nothing holds it (always here). */
    fun knopfSperre(): String? = null

    /** Extra button in the floating IH panel ([zuklappen] closes the panel); null = none. */
    fun panelKnopf(ctx: Context, zuklappen: () -> Unit): View? = null

    /** Notice in the floating IH panel (shown while the bot is not running); null = none. */
    fun panelHinweis(): String? = null

    /** Notice at the top of the main screen; null = none. */
    fun bannerText(): String? = null

    /** [f] is called when something these hooks report changes (here: never). */
    fun beobachte(f: () -> Unit) = Unit

    fun vergiss(f: () -> Unit) = Unit

    /** Data note in the "Before you use IH Auction Bot" text. */
    const val DATEN_TEXT = "This app has no internet access: nothing leaves your phone unless you share a report yourself."

    // ---------------- Privacy ----------------

    /**
     * The secondary password window is open or the password is about to be typed: nothing may take a screenshot for
     * [ms] milliseconds. (The bot's own screenshots are protected by BotDienst/BerichtDatenschutz; here nothing else
     * takes any.)
     */
    fun passwortSchutz(ms: Long) = Unit

    // ---------------- Runs ----------------

    /** A run begins; its log starts at [protokollStart] (bookmark in the log file). */
    fun laufBeginnt(ctx: Context, protokollStart: Long) = Unit

    /** After every step of a running bot (main thread, must be cheap). [ort] = what was recognised last. */
    fun nachSchritt(ctx: Context, bot: AuktionsBot, ort: String?) = Unit

    /** The run is over (the report at the end of the run comes anyway). */
    fun laufEnde() = Unit

    // ---------------- Reports: only on the phone ----------------

    /** Text of the report button in the floating IH panel. */
    const val BERICHT_KNOPF = "📝  Save report"

    /** Reports are always saved locally (at most a few, see BerichtSender). */
    fun berichteAn(ctx: Context): Boolean = true

    const val BERICHTE_AUS_TEXT = "Reports are off."

    /** Extra lines for info.txt (never a secret value): none. */
    fun infoZeilen(ctx: Context): List<String> = emptyList()

    /** At most this many bytes of flight recorder screenshots in the next report, besides the usual limits: no extra limit. */
    fun flugGrenze(ctx: Context): Long = Long.MAX_VALUE

    /** A finished report stays on the phone. Returns the text for the log. */
    fun berichtAbliefern(ctx: Context, ordner: File, grund: String, bilder: Int, flugBilder: Int, flugBytes: Long): String =
        "Report saved on the phone (${ordner.name}" + (if (flugBilder > 0) ", flight recorder: $flugBilder screenshots" else "") +
            ") – share it under Settings → Reports if you need help."
}
