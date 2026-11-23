package io.github.hustlebot.ihauctionbot.app

import android.content.Context
import io.github.hustlebot.ihauctionbot.kern.BuyoutFreigabe
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import io.github.hustlebot.ihauctionbot.kern.WISCH_VERHAELTNIS_MAX
import io.github.hustlebot.ihauctionbot.kern.WISCH_VERHAELTNIS_MIN
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalTime

/** Everything the player sets in the app. */
data class Einstellungen(
    // C- hardly exists any more for 18 SG, so the default was 20–24 SG per copy,
    // and only auctions in their last 5 minutes (the more expensive ones end after 10 s to 4 min).
    // Default on a fresh install: first B- 10–60, then C- 10–20 (= filter values).
    // (Saved rules stay as they are – this only applies when no rules are saved.)
    val regeln: List<Suchregel> = STANDARD_REGELN,
    /** How many copies to buy. 0 = no limit. */
    val zielKopien: Int = 0,
    /** Time of day to stop. null = no time. */
    val endeUm: LocalTime? = null,
    /**
     * Spend at most this many SG (counted from the SG balance at the start). null = no limit.
     * Review before publishing (Nov 22): a fresh install starts with [STANDARD_SG_BUDGET] (saved settings stay as they are).
     */
    val sgBudget: Int? = STANDARD_SG_BUDGET,
    /** Keep the screen on while the bot is running (otherwise it cannot see the game). */
    val bildschirmAnlassen: Boolean = true,
    /** Make the screen very dark while the bot is running (saves battery). */
    val bildschirmDunkel: Boolean = false,
    /**
     * Power saving (default on): the screen is dimmed while the bot runs (like [bildschirmDunkel]), and while the bot
     * only waits (waits of 5 s or more) it looks less often.
     */
    val stromsparen: Boolean = true,
    /**
     * Buyout (beta, default off): buy a copy outright when its "Buyout Price" is at most the rule's
     * [Suchregel.buyoutMax]. Off = the bot only bids (exactly as before).
     */
    val buyout: Boolean = false,
    /**
     * The IH button used to float over every app. On = only show it over Idle Heroes (and this app);
     * while the bot is running it is always visible anyway.
     */
    val knopfNurImSpiel: Boolean = true,
    /**
     * "When I touch the screen during a run". Field test 1.30 (Nov 12: the shield swallowed the bot's taps):
     * the default is now [Beruehrung.NICHTS] – no shield. "Bot stops" and "Pocket mode" are still being tested.
     */
    val beruehrung: Beruehrung = Beruehrung.NICHTS,
    /**
     * Flight recorder – every analysed screenshot of the last 3 minutes stays on the phone and goes into the
     * report (at the end of the run, when the bot is stuck and on special events). Default: off. Only works while
     * reports are on ([Variante.berichteAn]).
     */
    val flugschreiber: Boolean = false,
    /**
     * Review round 2 (Nov 23): Google's text recognition (ML Kit through Google Play services) as a second reader for
     * numbers the bot cannot read itself. Off = it is never set up, the bot reads every number itself (bidding does not
     * need it). Default: [Variante.MLKIT_STANDARD]. Takes effect at the next start of the bot.
     */
    val mlKit: Boolean = Variante.MLKIT_STANDARD,
)

/** What a touch during the run does (saved under its name). */
enum class Beruehrung {
    /** No shield – touches reach the game as usual (default). */
    NICHTS,

    /** Shield: the first touch is swallowed and stops the bot (still being tested). */
    STOPPT,

    /** Pocket mode: all touches are swallowed (still being tested). */
    TASCHE,
}

/** SG budget on a fresh install – so that a new player does not spend all Starry Gems by accident. */
const val STANDARD_SG_BUDGET = 500

/** Suggestion for every new rule. */
val STANDARD_REGEL = Suchregel("C-", 10, 20, maxRestzeitMin = 5)

/**
 * Rules on a fresh install: the numbers are exactly the values for the game's filter.
 * A B- gives 300 CSG when retired, a C- 100 – so B- for 60 SG is as cheap as C- for 20 SG
 * and needs three times fewer bids (limit of 50).
 */
val STANDARD_REGELN: List<Suchregel> = listOf(
    Suchregel("B-", 10, 60, maxRestzeitMin = 5),
    STANDARD_REGEL,
)

/**
 * Earlier default rules that were never changed by hand (they were only saved along when the settings were opened).
 * They are switched to the new defaults – rules the player made themselves stay.
 */
private val ALTE_STANDARDS: List<List<Suchregel>> = listOf(
    listOf(Suchregel("B-", 55, 66, maxRestzeitMin = 5), Suchregel("C-", 20, 24, maxRestzeitMin = 5)),
    listOf(Suchregel("C-", 20, 24, maxRestzeitMin = 5)),
)

/** A rule as text, e.g. "C- · filter 10–20 · bid at most 21 SG · only last 5 min · buyout up to 15 SG" */
fun regelText(r: Suchregel): String =
    "${r.kopie} · filter ${r.minPreis}–${r.maxPreis} · bid at most ${r.hoechstesGebot} SG · " +
        (r.maxRestzeitMin?.let { "only last $it min" } ?: "any time left") +
        // Nov 23: the max buyout only where the build offers buyout.
        BuyoutFreigabe.regelText(r.buyoutMax, Variante.BUYOUT_FREIGEGEBEN)

/** Saves the settings on the phone. */
object EinstellungenSpeicher {
    private const val DATEI = "einstellungen"

    fun laden(ctx: Context): Einstellungen {
        val p = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val standard = Einstellungen()
        val gelesen = try {
            val json = p.getString("regeln", null)
            if (json == null) {
                standard.regeln
            } else {
                val liste = JSONArray(json)
                (0 until liste.length()).map { i ->
                    val o = liste.getJSONObject(i)
                    Suchregel(
                        o.getString("kopie"), o.getInt("min"), o.getInt("max"), o.optBoolean("aktiv", true),
                        maxRestzeitMin = if (o.has("restzeitMin")) o.getInt("restzeitMin") else null,
                        buyoutMax = if (o.has("buyoutMax")) o.getInt("buyoutMax").takeIf { it > 0 } else null,
                    )
                }
            }
        } catch (e: Exception) {
            standard.regeln
        }
        val regeln = if (gelesen in ALTE_STANDARDS) standard.regeln else gelesen
        val ende = p.getString("endeUm", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        return Einstellungen(
            regeln = regeln,
            zielKopien = p.getInt("zielKopien", 0),
            endeUm = ende,
            // Missing (fresh install) → the default budget; a saved 0 (= "no limit") stays.
            sgBudget = if (p.contains("sgBudget")) p.getInt("sgBudget", 0).takeIf { it > 0 } else standard.sgBudget,
            bildschirmAnlassen = p.getBoolean("anlassen", true),
            bildschirmDunkel = p.getBoolean("dunkel", false),
            stromsparen = p.getBoolean("stromsparen", true),
            // Missing in older installs → off.
            buyout = p.getBoolean("buyout", false),
            knopfNurImSpiel = p.getBoolean("knopfNurImSpiel", true),
            // Field test 1.30 (Nov 12): new key – if it is missing (all earlier installs), "Nothing – no shield"
            // applies once; the old value "taschenmodusAutomatisch" no longer counts.
            beruehrung = p.getString("beruehrung", null)
                ?.let { n -> Beruehrung.entries.firstOrNull { it.name == n } } ?: Beruehrung.NICHTS,
            flugschreiber = p.getBoolean("flugschreiber", false),
            // Missing (all earlier installs) → the default of this app.
            mlKit = p.getBoolean("mlKit", Variante.MLKIT_STANDARD),
        )
    }

    fun speichern(ctx: Context, e: Einstellungen) {
        val liste = JSONArray()
        e.regeln.forEach {
            liste.put(
                JSONObject()
                    .put("kopie", it.kopie)
                    .put("min", it.minPreis)
                    .put("max", it.maxPreis)
                    .put("aktiv", it.aktiv)
                    .apply { it.maxRestzeitMin?.let { m -> put("restzeitMin", m) } }
                    .apply { it.buyoutMax?.let { m -> put("buyoutMax", m) } },
            )
        }
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
            .putString("regeln", liste.toString())
            .putInt("zielKopien", e.zielKopien)
            .putString("endeUm", e.endeUm?.toString() ?: "")
            .putInt("sgBudget", e.sgBudget ?: 0)
            .putBoolean("anlassen", e.bildschirmAnlassen)
            .putBoolean("dunkel", e.bildschirmDunkel)
            .putBoolean("stromsparen", e.stromsparen)
            .putBoolean("buyout", e.buyout)
            .putBoolean("knopfNurImSpiel", e.knopfNurImSpiel)
            .putString("beruehrung", e.beruehrung.name)
            .putBoolean("flugschreiber", e.flugschreiber)
            .putBoolean("mlKit", e.mlKit)
            .apply()
    }
}

/**
 * Review before publishing (Nov 22): the player's consent to what the accessibility service does (prominent
 * disclosure, see EinstellungenActivity). Until it is stored, the service stays idle: no screenshots, no taps, no IH
 * button. [VERSION] goes up if the text changes a lot; then the player is asked again.
 *
 * Review round 2 (Nov 23): kept in its own file [DATEI] that is NOT in the backup (res/xml/sicherung*.xml) – a consent
 * restored on another phone must not count there. Older versions kept it in the settings file (part of the backup); it
 * is moved once ([umziehen]).
 */
object Zustimmung {
    private const val DATEI = "zustimmung"
    private const val SCHLUESSEL = "zustimmungVersion"
    private const val ERSTER_START = "ersterStartHinweis"

    /** Old place (settings file, in the backup) – only read once to move the consent. */
    private const val ALTE_DATEI = "einstellungen"
    private const val UMGEZOGEN = "umgezogen"

    const val VERSION = 1

    fun gegeben(ctx: Context): Boolean {
        umziehen(ctx)
        return ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getInt(SCHLUESSEL, 0) >= VERSION
    }

    fun geben(ctx: Context) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putInt(SCHLUESSEL, VERSION).putBoolean(UMGEZOGEN, true).apply()
    }

    /**
     * Once: a consent an older version stored in the settings file moves here – but only if the app was already used on
     * THIS phone (its log file exists; files are not in the backup). A consent that only came back with a backup of the
     * settings does not count. The old value is removed from the settings file either way.
     */
    @Synchronized
    private fun umziehen(ctx: Context) {
        val neu = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (neu.getBoolean(UMGEZOGEN, false)) return
        val alt = ctx.getSharedPreferences(ALTE_DATEI, Context.MODE_PRIVATE)
        val version = alt.getInt(SCHLUESSEL, 0)
        val ordner = ctx.applicationContext.filesDir
        val hierBenutzt = File(ordner, "protokoll.txt").exists() || File(ordner, "protokoll.alt.txt").exists()
        val e = neu.edit().putBoolean(UMGEZOGEN, true)
        if (version > 0 && hierBenutzt && neu.getInt(SCHLUESSEL, 0) < version) e.putInt(SCHLUESSEL, version)
        e.commit()
        if (alt.contains(SCHLUESSEL) || alt.contains(ERSTER_START)) alt.edit().remove(SCHLUESSEL).remove(ERSTER_START).apply()
    }

    /** Was the note already shown once on the first start of the app (whatever the answer was)? */
    fun ersterStartGezeigt(ctx: Context): Boolean =
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getBoolean(ERSTER_START, false)

    fun ersterStartMerken(ctx: Context) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putBoolean(ERSTER_START, true).apply()
    }
}

/**
 * Which app is Idle Heroes (package name) – the accessibility service learns it by itself during the first run.
 * Review before publishing (Nov 22): only an app on the allow-list of the accessibility config is ever learned.
 * Own file, so that saving the settings never overwrites it.
 */
object SpielSpeicher {
    private const val DATEI = "knopf"
    private const val SCHLUESSEL = "spielPaket"

    fun laden(ctx: Context): String? =
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString(SCHLUESSEL, null)?.takeIf { it.isNotBlank() }

    fun speichern(ctx: Context, paket: String?) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putString(SCHLUESSEL, paket ?: "").apply()
    }
}

/**
 * Scrolling (field test: "scrolls clearly too far"): what the bot has learned about this phone and should know again
 * right away in the next run – how far the list really moves per swipe pixel. Then even the first swipe hits exactly
 * 5 new copies. Own file, so that neither saving the settings nor the end of a run deletes it.
 */
object LernSpeicher {
    private const val DATEI = "lernwerte"
    private const val WISCHEN = "wischVerhaeltnis"

    /** Learned swipe ratio, or null (nothing learned yet / unusable value). */
    fun wischVerhaeltnis(ctx: Context): Double? {
        val p = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (!p.contains(WISCHEN)) return null
        val w = runCatching { p.getFloat(WISCHEN, 1f).toDouble() }.getOrNull() ?: return null
        return w.takeIf { it.isFinite() && it in WISCH_VERHAELTNIS_MIN..WISCH_VERHAELTNIS_MAX }
    }

    fun wischVerhaeltnisMerken(ctx: Context, wert: Double) {
        if (!wert.isFinite()) return
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
            .putFloat(WISCHEN, wert.coerceIn(WISCH_VERHAELTNIS_MIN, WISCH_VERHAELTNIS_MAX).toFloat())
            .apply()
    }
}
