package io.github.hustlebot.ihauctionbot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.AuktionsBot
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.FensterZuordnung
import io.github.hustlebot.ihauctionbot.kern.KnopfSichtbarkeit
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Punkt
import io.github.hustlebot.ihauctionbot.kern.SchildDurchlass
import io.github.hustlebot.ihauctionbot.kern.SpielVorne
import io.github.hustlebot.ihauctionbot.kern.StoppBedingung
import io.github.hustlebot.ihauctionbot.kern.StoppGrund
import io.github.hustlebot.ihauctionbot.kern.bericht.BerichtDatenschutz
import io.github.hustlebot.ihauctionbot.kern.bericht.Haenger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The accessibility service. It
 *  - shows the floating button over the game (only over Idle Heroes, see [KnopfSichtbarkeit]),
 *  - takes a screenshot on every step and asks the bot what to do,
 *  - taps/swipes for the bot,
 *  - turns the screen off at the end (goal or time reached).
 *
 * Review before publishing (Nov 22): it does nothing at all until the player agreed in the app ([Zustimmung]). Android
 * sends it events only from the allowed game apps (allow-list, packageNames in res/xml/bedienungshilfe.xml); which app
 * is in front is polled ([vordergrundTakt]). Screenshots are only kept while the game is alone in front, the volume keys
 * are only looked at during a run.
 *
 * Privacy review, round 2 (Nov 23): which app is in front is decided WITHOUT reading any window: the game's windows are
 * learned from the game's own events, this app's screen tells by itself ([BotZustand.appBildschirmOffen]), every other
 * app window counts as another app ([FensterZuordnung]). Gestures, text input and screenshots only while a recent check
 * (at most [VORNE_FRISCH_MS] old) says the game is in front – otherwise nothing (fail closed).
 */
class BotDienst : AccessibilityService() {

    companion object {
        /** Is the accessibility service running right now? (for the display in the settings) */
        @Volatile
        var instanz: BotDienst? = null
            private set

        /** This long after the last window event, check which app is in front. */
        private const val VORDERGRUND_PAUSE_MS = 150L

        /** Review: if no answer to a screenshot comes for this long, carry on anyway. */
        private const val FOTO_NOTFALL_MS = 20_000L

        /** Review: the watchdog checks this often whether the steps are still running … */
        private const val WACHHUND_MS = 15_000L

        /** … and kicks them off again if no step came for this long and none is scheduled. */
        private const val STILLSTAND_MS = 90_000L

        /** Review: if a gesture's path lies on the IH button, wait this long until it lets taps through. */
        private const val KNOPF_VORLAUF_MS = SchildDurchlass.START_VORLAUF_MS

        /** Another app than Idle Heroes in front: the bot stops this long after Idle Heroes was last seen in front … */
        private const val FREMD_VORNE_MS = 2_000L

        /** … but at least this long after the other app was first noticed (a brief switch does not stop it). */
        private const val FREMD_MINDESTENS_MS = 1_000L

        /** Review before publishing (Nov 22): which app is in front is checked this often during a run … */
        private const val VORNE_TAKT_LAUF_MS = 1_000L

        /** … and this often while no bot runs but the IH button is shown only over the game. */
        private const val VORNE_TAKT_RUHE_MS = 2_500L

        /** A result of that check counts this long (after that: not known). */
        private const val VORNE_FRISCH_MS = 3_000L

        /**
         * Review round 2 (Nov 23): an app window that just appeared is judged after [FensterZuordnung.NEU_MS] (the game's
         * event for it may still come) – check again this soon.
         */
        private const val NEUES_FENSTER_NOCHMAL_MS = FensterZuordnung.NEU_MS + 50L

        /** Review round 2 (Nov 23): not known what is in front – the next step looks again this soon (no screenshot until then). */
        private const val UNKLAR_WARTEN_MS = 250L

        /** … and after this long a line in the log and the panel says why the bot waits. */
        private const val UNKLAR_MELDEN_MS = 5_000L

        /** System windows thicker than this (dp) cover the game (notification shade, heads-up); bars do not. */
        private const val DECKEND_AB_DP = 64

        /** After a crash or an update in the middle of a run: lock the screen only if the last step was at most this long ago. */
        private const val SPERREN_NACH_SCHRITT_MS = 120_000L

        /** The time of the last step is saved at most this often. */
        private const val SCHRITT_MERKEN_MS = 20_000L

        /** The visible frame of the shield: wait this long after hiding it before a screenshot. */
        private const val RAHMEN_WEG_MS = 120L

        /** Field test 1.20: hold the IH button this long to end pocket mode. */
        private const val TASCHE_HALTEN_MS = 2_000L

        /** Emergency stop: both volume keys count as "together" if at most this far apart. */
        private const val NOTAUS_MS = 500L

        /**
         * Review (if Android's accessibility shortcut is set up, "both keys together" may never arrive):
         * emergency stop also with volume down 3 times within this time.
         */
        private const val LEISER_3X_MS = 2_000L

        /** Review: if a finger is on the IH button, check this often whether the gesture may go out now … */
        private const val KNOPF_WARTEN_MS = 100L

        /** … but a touch whose release never came counts at most this long. */
        private const val KNOPF_GEHALTEN_MAX_MS = 10_000L

        /** Review: this many gestures cancelled in a row (touch in between) → stop in pocket mode. */
        private const val MAX_ABGEBROCHEN = 5

        /**
         * Shield guard: if the main thread does not respond for this long, the app is killed (then the shield is surely
         * gone). Review: 30 s – queries to Android (windows, fields) may take up to 5 s each.
         */
        // Final check: 15 s instead of 30 s – while the main thread hangs, the emergency exits do not work.
        // The slow query "which app is in front" now runs in its own thread.
        private const val HAUPT_HAENGT_MS = 15_000L

        /** Shield guard: if no step came for this long (and none is scheduled), the shield is removed. */
        private const val SCHLEIFE_STILL_MS = 20_000L

        /** Power saving: waits of at least this long … */
        private const val STROMSPAREN_AB_MS = 5_000L

        /** … are stretched to this (fewer screenshots while the bot only waits, e.g. for 50 bids or the budget). */
        private const val STROMSPAREN_WARTEN_MS = 15_000L

        /** Set up the crash guard only once per app process. */
        @Volatile private var absturzWacheDa = false

        private const val TASCHE_AN_TEXT =
            "🎒 Pocket mode on – touches are swallowed. Way out: hold the IH button for 2 s, power button, " +
                "volume down 3 times or both volume keys briefly together (don't hold)"
        private const val STOPP_AN_TEXT = "Shield on: touching the screen stops the bot (the IH button works normally)"
    }

    private val haupt = Handler(Looper.getMainLooper())
    private val bildArbeiter = Executors.newSingleThreadExecutor()
    /** Reads the screenshots (core recognition). */
    private val leser = SpielLeser()

    /**
     * Review round 2 (Nov 23): the second text recognition (Google ML Kit) for numbers the core cannot read – only created
     * when the setting is on at a start (null = never set up).
     */
    private var mlKit: MlKitLeser? = null

    /**
     * Review: text only into fields of Idle Heroes. Review before publishing (Nov 22): only into the allowed game apps
     * (allow-list) – never "the app that is in front". Review round 2 (Nov 23): only windows known to be the game's are
     * read at all.
     */
    private val textEingabe by lazy { TextEingabe(this, haupt, { spielPakete }, { zuordnung.istSpiel(it) }) }
    private var fenster: SchwebeFenster? = null
    private var bot: AuktionsBot? = null
    private val schrittAufgabe = Runnable { schritt() }
    private var gemerkterStartSg: Int? = null

    /** Scrolling: swipe ratio this run started with (only what the run learned on top is remembered). */
    private var wischStart: Double? = null

    // Diagnostics (the bot once did nothing).
    @Volatile private var diagnoseGespeichert = false
    @Volatile private var letzteDiagnoseZeile = 0L
    @Volatile private var unbekanntSeit: Long? = null
    @Volatile private var unbekanntBildGespeichert = false

    /** After a password input, save no screenshots for one minute (privacy). */
    @Volatile private var keineBilderBis = 0L

    /**
     * Review (privacy): may a screenshot with this screen be saved? [ort] must come from EXACTLY the screenshot in
     * question (see [SpielLeser.frisch]) – null (not read) means no.
     */
    private fun bilderErlaubt(ort: Ort?): Boolean =
        ort != null && BerichtDatenschutz.bildErlaubt(ort, System.currentTimeMillis(), keineBilderBis)

    /** Review: when a step last ran (for the watchdog). */
    private var letzterSchrittUm = 0L

    /**
     * Review ("bot stood still forever after Pause → Resume"): watchdog. If a run is active but no step came for
     * [STILLSTAND_MS] and none is scheduled, the next step is kicked off (with a log line).
     * Nothing can run twice: whatever is on its way (screenshot, gesture, text) has its own emergency times, which are
     * much shorter.
     */
    private val wachhund: Runnable = object : Runnable {
        override fun run() {
            if (bot == null || !laufAktiv()) return
            val still = System.currentTimeMillis() - letzterSchrittUm
            if (still > STILLSTAND_MS && !haupt.hasCallbacks(schrittAufgabe)) {
                BotZustand.log("The bot stood still for ${still / 1000} s (no step scheduled) – continuing now")
                letzterSchrittUm = System.currentTimeMillis()
                plane(0)
            }
            haupt.postDelayed(this, WACHHUND_MS)
        }
    }

    /**
     * Field test ("Screenshot failed (error 3)"): Android only allows screenshots of the accessibility service
     * with a minimum interval. The bot waits at least this long between two screenshots.
     */
    private val minFotoAbstandMs = 380L
    @Volatile private var letztesFotoUm = 0L
    @Volatile private var zuSchnellGemeldet = false

    /** How long the last screenshot + recognition took (for the log: where does the time go?). */
    @Volatile private var letzteFotoDauerMs = 0L

    /** Is the bot stuck? Then send an error report automatically. */
    private val haenger = Haenger()

    // ---------------- IH button only over Idle Heroes (version 1.11) ----------------

    /** Decides when the IH button is visible (rules and tests in the core). */
    private var knopfSicht: KnopfSichtbarkeit? = null

    /**
     * Was Idle Heroes (an allowed game app) in front in this run? Only from then on the bot stops when another app comes
     * to the front, and only then a shield is put up.
     */
    @Volatile private var spielGeprueft = false

    /** Window events often come in bursts – only check shortly afterwards what is in front. */
    private val vordergrundAufgabe = Runnable { vordergrundPruefen() }

    /**
     * Review before publishing (Nov 22): the allowed game apps (allow-list) – packageNames of the accessibility config
     * (res/xml/bedienungshilfe.xml; the debug build adds the fake game). Android sends events only from these apps,
     * text goes only into them, and screenshots are only kept while one of them is alone in front.
     */
    @Volatile private var spielPakete: Set<String> = setOf(SpielVorne.IDLE_HEROES)

    /** For the settings (START opens the game): the allowed game apps. */
    val spielApps: Set<String> get() = spielPakete

    /** Did the player agree in the app ([Zustimmung])? Until then the service does nothing. */
    private var zugestimmt = false

    /** Last result of "which app is in front?" and when (SystemClock.uptimeMillis). */
    @Volatile private var vorneLage = SpielVorne.Lage.UNKLAR
    @Volatile private var vorneLageUm = 0L

    /** May a screenshot taken now be kept (game alone in front)? Last result and when (SystemClock.uptimeMillis). */
    @Volatile private var letzteFreigabe = false
    @Volatile private var letzteFreigabeUm = 0L

    /** When Idle Heroes was last seen in front in this run (System.currentTimeMillis, 0 = not yet). */
    private var spielZuletztVorne = 0L

    /**
     * Privacy review, round 2 (Nov 23): which app windows are the game's (learned from its own events) or this app's –
     * every other one is another app. No window is ever asked which app it belongs to.
     */
    private val zuordnung = FensterZuordnung()

    /** Since when it is not known what is in front during a run (SystemClock.uptimeMillis, 0 = known). */
    private var unklarSeit = 0L

    /** System windows thicker than this (pixels) cover the game. */
    private val deckendPx by lazy { (DECKEND_AB_DP * resources.displayMetrics.density).toInt() }

    /** Is the bot waiting right now because Idle Heroes is not in front (logged once)? */
    private var wartetAufSpiel = false

    /** When the time of the last step was saved last (for the screen lock after a crash). */
    private var schrittGemerktUm = 0L

    /** Is the key filter (volume keys) on right now? null = not set yet. */
    private var tastenFilterAn: Boolean? = null

    /**
     * Review before publishing (Nov 22): Android only sends events of the game apps, so whether another app came to
     * the front is checked by a light timer: every second during a run, every 2.5 s while the IH button is shown only
     * over the game, otherwise not at all (the game's own events say when it comes to the front).
     */
    private val vordergrundTakt: Runnable = object : Runnable {
        override fun run() {
            val ms = taktMs()
            if (ms <= 0L) return
            vordergrundPruefen()
            haupt.postDelayed(this, ms)
        }
    }

    /**
     * Review: "which app is in front?" asks Android for the window list – that can take a moment. So it runs in its own
     * thread; the result arrives in the main thread. (Review round 2, Nov 23: no window's content is asked any more.)
     */
    private val vordergrundFaden = Executors.newSingleThreadExecutor()
    private var vordergrundUnterwegs = false
    private var vordergrundNochmal = false

    /** During a run it is not checked what is in front (does not disturb the bot) – once afterwards. */
    private var vordergrundVeraltet = false

    /** Bot, pause or a [Variante] hook ([Variante.beobachte]) changed → check the button again (in the main thread). */
    private val zustandGeaendert: () -> Unit = { haupt.post { knopfPruefen() } }

    // ---------------- Shield during the run ----------------

    /**
     * The transparent shield over the whole screen (null = none right now). Field test 1.20 ("could not get back into
     * the phone"): it is ONLY there while the bot is really running (not when paused, never after a stop), and it
     * remembers nothing – after a restart of the app or the phone there is never a shield.
     *  - Normal ("touch stops the bot"): the first touch from outside is swallowed, then the bot stops
     *    and the shield is gone right away.
     *  - Pocket mode: touches are swallowed. Way out: hold the IH button for 2 s, power button (stops the bot), both
     *    volume keys briefly together (emergency stop), or Idle Heroes is no longer in front.
     * The IH button always lies on top and stays usable.
     */
    private var schild: TaschenSchild? = null

    /** Is the current shield in pocket mode (swallows everything)? Otherwise a touch stops the bot. */
    private var schildTasche = false

    /**
     * Field test 1.30 (the shield swallowed the bot's taps): is the shield "a touch stops the bot" used in this run?
     * Only with the setting "Bot stops" (still being tested) – the default is no shield.
     */
    private var beruehrtStoppt = false

    /** No more shield in this run (the shield failed, or the shield guard removed it). */
    private var schildGesperrt = false

    /** Is the screen on? */
    @Volatile private var bildschirmAn = true

    /** Field test 1.20: pocket mode – hold the IH button for [TASCHE_HALTEN_MS], then it is off. */
    private val taschenEntsperren = Runnable { taschenEntsperrt() }

    /** Since when another app than Idle Heroes is in front (0 = not), and whether pocket mode was on then. */
    private var fremdVorneSeit = 0L
    private var fremdWarTasche = false
    private val vordergrundWache = Runnable { vordergrundHolen { vordergrundAuswerten(it) } }

    /** Is this app in front right now (settings)? Then there is no shield – the player is using it. */
    private var eigeneAppVorne = false

    // Shield guard (own thread, also runs when the main thread hangs). Times in SystemClock.uptimeMillis.
    /** Since when the shield is there (0 = none). */
    @Volatile private var schildSeit = 0L

    /** When the main thread last reported "I'm alive" (heartbeat, while the shield is there). */
    @Volatile private var hauptLebt = 0L

    /** When a step last ran – and when the next one is scheduled. */
    @Volatile private var schrittLief = 0L
    @Volatile private var schrittGeplant = 0L

    /** End of the run according to the time limit (System.currentTimeMillis, 0 = no limit). */
    @Volatile private var laufEndeMs = 0L
    private var schildWache: Thread? = null
    private val herzschlag: Runnable = object : Runnable {
        override fun run() {
            hauptLebt = SystemClock.uptimeMillis()
            if (schild != null) haupt.postDelayed(this, 1_000)
        }
    }

    /** Emergency stop: when the volume keys were last pressed (time of the key event). */
    private var leiserGedrueckt = Long.MIN_VALUE / 2
    private var lauterGedrueckt = Long.MIN_VALUE / 2

    /** The last (at most 3) presses of volume down – for "volume down 3 times". */
    private val leiserReihe = ArrayDeque<Long>()

    private val bildschirmEmpfaenger = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    bildschirmAn = false
                    bildschirmAus()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> bildschirmAn = true
            }
            // Review: also check the IH button again (it is gone on the lock screen). Review before publishing (Nov 22):
            // and once what is in front (the light timer pauses while the screen is off).
            haupt.post {
                knopfPruefen()
                if (bildschirmAn) vordergrundPruefen()
            }
        }
    }

    // ---------------- Overnight checks: battery (see NachtCheck) ----------------

    /** Too hot: no screenshots, no taps until the battery is cool again (main thread only). */
    private var hitzePause = false

    /** Power saving for this run (setting "Power saving"). */
    private var stromsparen = false

    /** Battery messages from Android (charge, charging, temperature) – checked in the main thread. */
    private val akkuEmpfaenger = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val akku = NachtCheck.akkuAus(i)
            haupt.post { akkuPruefen(akku) }
        }
    }

    /**
     * During a run: below [NachtCheck.AKKU_MIN_PROZENT] % and not charging → stop and lock the screen; hotter than
     * [NachtCheck.HEISS_C] °C → pause bidding (no taps) until it is below [NachtCheck.KUEHL_C] °C again.
     */
    private fun akkuPruefen(akku: NachtCheck.Akku) {
        BotZustand.akkuTemp = akku.tempC
        if (!laufAktiv()) {
            hitzePause = false
            return
        }
        val prozent = akku.prozent
        if (prozent != null && prozent < NachtCheck.AKKU_MIN_PROZENT && !akku.laedt) {
            BotZustand.log("Battery at $prozent % and not charging – stopping the bot")
            bot?.stoppeVonHand()
            beenden(StoppGrund.AKKU_SCHWACH)
            return
        }
        val t = akku.tempC ?: return
        val grad = String.format(java.util.Locale.US, "%.1f °C", t)
        if (!hitzePause && t > NachtCheck.HEISS_C) {
            hitzePause = true
            BotZustand.log("The phone is too hot ($grad) – bidding paused (no taps) until it is below ${NachtCheck.KUEHL_C.toInt()} °C")
            BotZustand.melde()
        } else if (hitzePause && t < NachtCheck.KUEHL_C) {
            hitzePause = false
            BotZustand.log("The phone has cooled down ($grad) – bidding again")
            val b = bot
            if (b != null && BotZustand.lauf == BotZustand.Lauf.LAEUFT) {
                b.pausiert = false
                if (haupt.hasCallbacks(schrittAufgabe)) plane(0)
            }
            BotZustand.melde()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanz = this
        // Review round 2 (Nov 23): moves a consent of an older version once – before anything is logged (see Zustimmung).
        Zustimmung.gegeben(this)
        BotZustand.init(this)
        absturzWacheEinrichten()
        // Encrypt a secondary password saved in plain text by an older version (once).
        runCatching { Geheim.migrieren(this) }
        // Review before publishing (Nov 22): the allow-list of game apps = packageNames of the accessibility config.
        spielPakete = runCatching { serviceInfo?.packageNames?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: setOf(SpielVorne.IDLE_HEROES)
        // The volume keys are only looked at during a run.
        tastenFilter(false)
        // Review before publishing (Nov 22): without the player's consent (shown in the app before the accessibility
        // settings) the service stays idle: no IH button, no screenshots, no taps.
        if (!Zustimmung.gegeben(this)) {
            BotZustand.log("Accessibility service connected – it does nothing until you agree in the app")
            BotZustand.melde()
            runCatching {
                Toast.makeText(this, "IH Auction Bot: open the app and tap \"Agree\" first – until then it does nothing.", Toast.LENGTH_LONG).show()
            }
            return
        }
        aktivieren()
    }

    /** The player agreed in the app (or the emulator test did): from now on the service works. */
    fun zustimmungGeaendert() {
        if (Looper.myLooper() == Looper.getMainLooper()) zustimmungPruefen() else haupt.post { zustimmungPruefen() }
    }

    private fun zustimmungPruefen() {
        if (zugestimmt || instanz !== this || !Zustimmung.gegeben(this)) return
        aktivieren()
    }

    /** Everything the service does – only after the consent ([Zustimmung]). */
    private fun aktivieren() {
        if (zugestimmt) return
        zugestimmt = true
        BotZustand.log("Accessibility service connected")
        // Review round 2 (Nov 23): the game's windows known from before (only if the phone has not restarted since),
        // e.g. after a crash or an update of the app while the game stayed in front.
        runCatching { zuordnung.uebernehmen(FensterSpeicher.laden(this), spielPakete) }
        knopfSicht = KnopfSichtbarkeit(
            eigenesPaket = packageName,
            spielPaket = SpielSpeicher.laden(this),
            nurImSpiel = EinstellungenSpeicher.laden(this).knopfNurImSpiel,
            erlaubteSpiele = spielPakete,
        )
        fenster = SchwebeFenster(this).also { it.zeigen() }
        // Pocket mode: notice when the screen goes off/on.
        bildschirmAn = runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bildschirmEmpfaenger, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(bildschirmEmpfaenger, filter)
        }.onFailure { BotZustand.log("Pocket mode: screen events not available (${it.message})") }
        // Overnight checks: battery charge and temperature (a system message, delivered at once and on every change).
        runCatching {
            val akkuFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(akkuEmpfaenger, akkuFilter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(akkuEmpfaenger, akkuFilter)
        }.onFailure { BotZustand.log("Battery messages not available (${it.message})") }
        BotZustand.beobachte(zustandGeaendert)
        Variante.beobachte(zustandGeaendert)
        haupt.post(vordergrundAufgabe)
        taktPlanen()
        // Review: if a run was still active when (re)connecting, the app crashed in the middle of the run or was killed
        // by Android. The bot is off now, the shield gone, the game unprotected – so lock the screen once.
        // Review before publishing (Nov 22): only if the last step was less than 2 minutes ago AND Idle Heroes is in
        // front – not after a reboot or an update hours later.
        if (LaufSpeicher.neustartMitOffenemLauf(this)) {
            val vorMs = System.currentTimeMillis() - LaufSpeicher.letzterSchritt(this)
            if (vorMs !in 0L..SPERREN_NACH_SCHRITT_MS) {
                BotZustand.log("A run was still open (last step more than 2 min ago) – the bot is off, the screen is not locked")
            } else {
                vordergrundHolen { blick ->
                    if (blick.lage == SpielVorne.Lage.SPIEL && !laufAktiv() && !handyGesperrt()) {
                        BotZustand.log("The app was killed in the middle of a run (crash or Android) – the bot is off, locking the screen")
                        BotZustand.letzteMeldung = "The bot was killed in the middle of a run – screen locked"
                        BotZustand.melde()
                        haupt.postDelayed({
                            if (!laufAktiv()) {
                                val ok = performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                                BotZustand.log(if (ok) "Screen turned off" else "Turning the screen off failed")
                            }
                        }, 500)
                    } else {
                        BotZustand.log("The app was killed in the middle of a run – the bot is off (Idle Heroes is not in front, the screen is not locked)")
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only window changes matter (which app is in front?) – otherwise do nothing. Review before publishing (Nov 22):
        // Android only sends them from the allowed game apps (packageNames); nothing at all without the consent.
        if (!zugestimmt) return
        val e = event ?: return
        // Privacy review, round 2 (Nov 23): the window an event of the game comes from is the game's – that is how the
        // service knows the game's windows without asking any window what it is. Only the window id and the event's app
        // are used (and only apps of the allow-list count).
        val neu = zuordnung.lernen(e.windowId, e.packageName?.toString(), spielPakete)
        if (neu) runCatching { FensterSpeicher.merken(this, zuordnung.spielFenster()) }
        val typ = e.eventType
        if (!neu && typ != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && typ != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        haupt.removeCallbacks(vordergrundAufgabe)
        haupt.postDelayed(vordergrundAufgabe, VORDERGRUND_PAUSE_MS)
    }

    /** Pocket mode safety: if the accessibility service is interrupted, pocket mode is off. */
    override fun onInterrupt() {
        taschenAus("accessibility service interrupted")
    }

    /**
     * Emergency stop: both volume keys briefly together (the second at most [NOTAUS_MS] after the first) →
     * bot stops, shield gone, pocket mode off. Pressing the second key already counts – don't hold (holding for 3 s
     * otherwise turns on Android's accessibility shortcut, e.g. TalkBack). Review: if this shortcut is set up, Android
     * may swallow "both together" – that is why volume down 3 times within [LEISER_3X_MS] works too.
     * The keys still work completely normally: no key is ever swallowed here (always false).
     *
     * Review before publishing (Nov 22): Android only sends keys while a run is active (the key filter is switched on
     * only then, see [tastenFilter]). Only the two volume keys are looked at; every other key is ignored right away.
     * No key is ever logged – only the line "Emergency stop: …".
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return false
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (!zugestimmt || (!laufAktiv() && schild == null)) return false
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return false
        val zeit = e.eventTime
        when (code) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                leiserGedrueckt = zeit
                leiserReihe.addLast(zeit)
                while (leiserReihe.size > 3) leiserReihe.removeFirst()
                if (zeit - lauterGedrueckt in 0L..NOTAUS_MS) {
                    notaus("both volume keys")
                } else if (leiserReihe.size == 3 && zeit - leiserReihe.first() in 0L..LEISER_3X_MS) {
                    notaus("volume down 3 times")
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                lauterGedrueckt = zeit
                if (zeit - leiserGedrueckt in 0L..NOTAUS_MS) notaus("both volume keys")
            }
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        aufraeumen()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        aufraeumen()
        super.onDestroy()
    }

    private fun aufraeumen() {
        haupt.removeCallbacks(schrittAufgabe)
        haupt.removeCallbacks(vordergrundAufgabe)
        haupt.removeCallbacks(wachhund)
        haupt.removeCallbacks(vordergrundWache)
        haupt.removeCallbacks(vordergrundTakt)
        zugestimmt = false
        runCatching { vordergrundFaden.shutdown() }
        // Review: if the accessibility service is turned off in the middle of a run (settings), that is not a crash –
        // do not lock when it is turned on again. (The starting SG stays saved for the budget.)
        if (laufAktiv()) LaufSpeicher.neustartNichtMelden(this)
        // Always remove the shield too (the shield guard also ends as soon as instanz is no longer this service).
        BotZustand.taschenmodusGewuenscht = false
        schildWeg()
        runCatching { unregisterReceiver(bildschirmEmpfaenger) }
        runCatching { unregisterReceiver(akkuEmpfaenger) }
        BotZustand.vergiss(zustandGeaendert)
        Variante.vergiss(zustandGeaendert)
        bot?.stoppeVonHand()
        bot = null
        fenster?.entfernen()
        fenster = null
        instanz = null
        if (BotZustand.lauf == BotZustand.Lauf.LAEUFT || BotZustand.lauf == BotZustand.Lauf.PAUSE) {
            BotZustand.lauf = BotZustand.Lauf.GESTOPPT
        }
    }

    // ---------------- IH button: when visible? ----------------

    private fun laufAktiv(): Boolean = BotZustand.lauf == BotZustand.Lauf.LAEUFT || BotZustand.lauf == BotZustand.Lauf.PAUSE

    /** Does a [Variante] hook hold the IH button right now ([Variante.knopfSperre])? */
    private fun knopfGesperrt(): Boolean = Variante.knopfSperre() != null

    /** Is the bot running (also paused) or does a hook hold the button? Then the button is always visible. */
    private fun knopfMussBleiben(): Boolean = laufAktiv() || knopfGesperrt()

    /** Show or put away the button – depending on what is in front. Only call from the main thread. */
    private fun knopfPruefen() {
        // Review before publishing (Nov 22): volume keys only during a run; the light timer only while needed.
        tastenFilter(laufAktiv())
        taktPlanen()
        val sicht = knopfSicht
        if (sicht == null) {
            schildPruefen()
            return
        }
        val bleiben = knopfMussBleiben()
        if (!bleiben && vordergrundVeraltet) {
            // Run just ended (or the hook let go of the button): first check what is in front now (this checks here again).
            vordergrundVeraltet = false
            haupt.removeCallbacks(vordergrundAufgabe)
            haupt.post(vordergrundAufgabe)
        }
        // "Hide" also works while the bot is running (it is stopped first) – just not
        // while a hook holds the button ([Variante.knopfSperre]).
        // In pocket mode the IH button always stays (for unlocking).
        // Review: on the lock screen it is gone (otherwise "Start" could be tapped there) – unless the
        // bot is running (stopping always works).
        val gesperrtOhneLauf = !bleiben && schild == null && handyGesperrt()
        fenster?.sichtbarkeit(!gesperrtOhneLauf && sicht.sichtbar(bleiben || schild != null), ausblendenErlaubt = !knopfGesperrt())
        schildPruefen()
    }

    // ---------------- Shield (field test 1.20: "safe pocket mode") ----------------

    private fun handyGesperrt(): Boolean =
        runCatching { (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }.getOrDefault(false)

    /**
     * Shield there or gone? Only there if the bot is really running (not paused), the screen is on and the phone is not
     * locked, no other app is in front and the IH button lies safely above the shield (otherwise there is none). Normal
     * or pocket mode depending on [BotZustand.taschenmodusGewuenscht]. Only call from the main thread.
     */
    private fun schildPruefen() {
        val f = fenster
        val s = schild
        if (f == null || !f.istGezeigt || bot == null || BotZustand.lauf != BotZustand.Lauf.LAEUFT || schildGesperrt ||
            !bildschirmAn || fremdVorneSeit != 0L || eigeneAppVorne || handyGesperrt()
        ) {
            if (s != null) schildWeg()
            return
        }
        val tasche = BotZustand.taschenmodusGewuenscht
        // Field test 1.30: without pocket mode there is only a shield if "Bot stops" is set.
        if (!tasche && !beruehrtStoppt) {
            if (s != null) schildWeg()
            return
        }
        if (s != null) {
            if (tasche != schildTasche) {
                schildTasche = tasche
                s.hinweis(tasche)
                BotZustand.taschenmodusAktiv = tasche
                if (tasche) f.zuklappen()
                BotZustand.log(if (tasche) TASCHE_AN_TEXT else STOPP_AN_TEXT)
                BotZustand.melde()
            }
            return
        }
        // Review before publishing (Nov 22): a new shield only once Idle Heroes is confirmed in front in this run.
        if (!spielGeprueft || vorneLageFrisch() != SpielVorne.Lage.SPIEL) return
        // Review: if a gesture of the bot is on its way without a shield right now, no new shield – its finger could
        // otherwise land on the fresh shield and stop the bot. As soon as it is over, this is checked again.
        if (gestenUnterwegs > 0) return
        val neu = TaschenSchild(this, gesteHaengt = { schildGesteHaengt() }, beruehrt = { schildBeruehrt() })
        neu.hinweis(tasche)
        if (!neu.zeigen()) {
            schildGesperrt = true
            BotZustand.taschenmodusGewuenscht = false
            BotZustand.letzteMeldung = "Shield could not be turned on"
            BotZustand.melde()
            return
        }
        // The IH button must lie visibly and usably ABOVE the shield – if that fails, there is no shield.
        if (!f.ueberSchild()) {
            runCatching { neu.entfernen() }
            schildGesperrt = true
            BotZustand.taschenmodusGewuenscht = false
            BotZustand.log("IH button could not be placed above the shield – no shield in this run")
            knopfNeu()
            return
        }
        schild = neu
        schildTasche = tasche
        val jetzt = SystemClock.uptimeMillis()
        hauptLebt = jetzt
        schildSeit = jetzt
        haupt.removeCallbacks(herzschlag)
        haupt.post(herzschlag)
        schildWacheStarten()
        BotZustand.taschenmodusAktiv = tasche
        if (tasche) f.zuklappen()
        BotZustand.log(if (tasche) TASCHE_AN_TEXT else STOPP_AN_TEXT)
        BotZustand.melde()
    }

    private fun schildWeg() {
        val s = schild ?: return
        schild = null
        schildSeit = 0L
        haupt.removeCallbacks(taschenEntsperren)
        haupt.removeCallbacks(herzschlag)
        runCatching { s.entfernen() }
        BotZustand.taschenmodusAktiv = false
        BotZustand.log(if (schildTasche) "🎒 Pocket mode off – shield removed" else "Shield removed")
        BotZustand.melde()
    }

    /** The IH button is no longer there (e.g. it could not be brought to the front): create it anew. */
    private fun knopfNeu() {
        runCatching { fenster?.entfernen() }
        fenster = runCatching { SchwebeFenster(this).also { it.zeigen() } }
            .onFailure { BotZustand.log("IH button could not be created anew: ${it.message}") }
            .getOrNull()
    }

    /** Pocket mode off (the shield stays while the bot is running – then a touch stops the bot). */
    private fun taschenAus(warum: String) {
        if (!BotZustand.taschenmodusGewuenscht && !(schild != null && schildTasche)) return
        BotZustand.taschenmodusGewuenscht = false
        BotZustand.log("🎒 Pocket mode off ($warum)")
        schildPruefen()
    }

    /** Pocket mode safety: shield gone right away and not again in this run (shield guard). */
    private fun schildNotAus(warum: String) {
        schildGesperrt = true
        BotZustand.taschenmodusGewuenscht = false
        if (schild != null) BotZustand.log("Shield guard: shield removed – $warum")
        schildWeg()
    }

    /**
     * Field test 1.20: screen off (power button) → pocket mode off for good, shield gone, and a running bot stops
     * (without the screen it sees nothing). None of this comes back by itself when the screen is turned on.
     */
    private fun bildschirmAus() {
        val warTasche = BotZustand.taschenmodusGewuenscht
        BotZustand.taschenmodusGewuenscht = false
        schildWeg()
        if (laufAktiv()) {
            BotZustand.log("Screen turned off" + (if (warTasche) " – pocket mode off" else "") + " – stopping the bot")
            bot?.stoppeVonHand()
            beenden(StoppGrund.BILDSCHIRM_AUS)
        }
    }

    /** Emergency stop ([wie]: both volume keys or volume down 3 times): bot stops (without locking), shield gone, pocket mode off. */
    private fun notaus(wie: String) {
        leiserGedrueckt = Long.MIN_VALUE / 2
        lauterGedrueckt = Long.MIN_VALUE / 2
        leiserReihe.clear()
        if (!laufAktiv() && schild == null && !BotZustand.taschenmodusGewuenscht) return
        BotZustand.log("Emergency stop: $wie – shield removed, pocket mode off")
        BotZustand.taschenmodusGewuenscht = false
        schildWeg()
        if (laufAktiv()) {
            bot?.stoppeVonHand()
            beenden(StoppGrund.NOTAUS)
        }
    }

    /**
     * Nov 23: only for the emulator test (TestSteuerung exists in the debug build only, main thread): the same emergency
     * stop as the volume keys. The emulator does not pass the keys to the accessibility service – the key path itself
     * needs a phone test.
     */
    fun notausTest() = notaus("test control")

    /**
     * Normal mode: someone touched the screen (not the bot). The touch was swallowed; now the bot stops
     * (without locking – the player is there after all), and the shield is gone right away.
     */
    private fun schildBeruehrt() {
        if (schild == null || schildTasche) return
        schildWeg()
        if (laufAktiv()) {
            BotZustand.log("Screen touched – stopping the bot")
            bot?.stoppeVonHand()
            beenden(StoppGrund.BERUEHRT)
        }
    }

    /**
     * Pocket mode safety: guard in its own thread – independent of the core and the main thread.
     *  - If the main thread does not respond for [HAUPT_HAENGT_MS] while the shield is there: kill the app (then Android
     *    surely removes the shield; when the accessibility service restarts, the screen is locked once).
     *  - If no step came for [SCHLEIFE_STILL_MS] (and none is scheduled), or the end time passed 1 minute ago:
     *    shield gone and not again in this run.
     */
    private fun schildWacheStarten() {
        if (schildWache?.isAlive == true) return
        val dienst = this
        val t = Thread(Runnable {
            while (instanz === dienst) {
                try {
                    Thread.sleep(1_000)
                } catch (e: InterruptedException) {
                    return@Runnable
                }
                if (schildSeit == 0L) continue
                val jetzt = SystemClock.uptimeMillis()
                val stumm = jetzt - hauptLebt
                if (stumm > HAUPT_HAENGT_MS) {
                    runCatching { BotZustand.log("Shield guard: the app has not responded for ${stumm / 1000} s – killing it so that the shield is gone") }
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Runnable
                }
                val still = jetzt - maxOf(schrittLief, schrittGeplant) > SCHLEIFE_STILL_MS
                val ende = laufEndeMs
                val nachEnde = ende > 0L && System.currentTimeMillis() > ende + 60_000L
                if (still || nachEnde) {
                    haupt.post {
                        schildNotAus(if (still) "the bot has not made a step for ${SCHLEIFE_STILL_MS / 1000} s" else "the end time passed 1 minute ago")
                    }
                }
            }
        }, "IH-ShieldGuard")
        t.isDaemon = true
        schildWache = t
        t.start()
    }

    /**
     * Pocket mode safety: if the app crashes, first remove the shield (in the main thread this works right away; in
     * another thread Android removes it together with the killed app), then carry on as always (Android kills the app).
     */
    private fun absturzWacheEinrichten() {
        if (absturzWacheDa) return
        absturzWacheDa = true
        val vorher = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { faden, fehler ->
            val d = instanz
            if (d != null && d.schildSeit != 0L) {
                // Review: if there was a shield, do NOT take the normal path – Android's crash dialog can wait for
                // minutes, and the shield would lie on top of it. Remove the shield right away (only works in the main
                // thread), then kill the app – then Android removes everything else too. (Android still logs the crash.)
                runCatching { if (Looper.myLooper() == Looper.getMainLooper()) d.schildSofortWeg() }
                runCatching { BotZustand.log("Crash (${fehler.javaClass.simpleName}) – shield removed, killing the app") }
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
            runCatching { BotZustand.log("Crash (${fehler.javaClass.simpleName})") }
            if (vorher != null) {
                vorher.uncaughtException(faden, fehler)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    /**
     * Only for the crash: shield gone IMMEDIATELY (removeViewImmediate), without log and display. Only when the windows
     * are really gone does the shield count as gone – otherwise the shield guard kills the app.
     */
    private fun schildSofortWeg() {
        val s = schild ?: return
        if (s.sofortEntfernen()) {
            schild = null
            schildSeit = 0L
        }
    }

    /** For the IH button: is the pocket mode shield there right now (a normal tap does nothing, only holding for 2 s)? */
    fun taschenGesperrt(): Boolean = schild != null && schildTasche

    /** IH panel: "🎒 Pocket mode" tapped (on/off, as long as it is not locking yet). */
    fun taschenmodusUmschalten() {
        if (taschenGesperrt()) return // locked – off only via the ways out
        taschenmodusSetzen(!BotZustand.taschenmodusGewuenscht)
    }

    /** Settings / IH panel: pocket mode for this run yes/no. Takes effect while the bot is running. */
    fun taschenmodusSetzen(an: Boolean) {
        if (!an && taschenGesperrt()) return // locked – off only via the ways out
        BotZustand.taschenmodusGewuenscht = an
        BotZustand.log(
            if (!an) "🎒 Pocket mode off" else if (laufAktiv()) "🎒 Pocket mode is turning on" else "🎒 Pocket mode: turns on at the start",
        )
        haupt.post { schildPruefen() }
        BotZustand.melde()
    }

    /** IH button pressed in pocket mode: after [TASCHE_HALTEN_MS] pocket mode is off (unless released before). */
    fun taschenKnopfGedrueckt() {
        haupt.removeCallbacks(taschenEntsperren)
        haupt.postDelayed(taschenEntsperren, TASCHE_HALTEN_MS)
    }

    fun taschenKnopfVerrutscht() {
        haupt.removeCallbacks(taschenEntsperren)
    }

    fun taschenKnopfLosgelassen() {
        haupt.removeCallbacks(taschenEntsperren)
    }

    private fun taschenEntsperrt() {
        if (!taschenGesperrt()) return
        taschenAus("IH button held for 2 s")
        fenster?.aufklappen()
    }

    /** A gesture of the bot got stuck on the shield: longer lead time; too often → stop the bot. */
    private fun schildGesteHaengt() {
        // Field test 1.30: this tap did NOT arrive in the game – remember it (e.g. then write no text).
        gesteHaengenGeblieben = true
        val s = schild ?: return
        val zuOft = s.durchlass.verschluckt()
        BotZustand.log(
            "🎒 Shield: a tap of the bot got stuck on the shield (#${s.durchlass.verschluckt}) – " +
                "now waiting ${s.durchlass.vorlaufMs} ms before it taps",
        )
        if (zuOft && laufAktiv()) {
            bot?.stoppeVonHand()
            beenden(StoppGrund.TASCHENMODUS_TIPPS)
        }
    }

    /**
     * "If Idle Heroes is not open, the bot turns itself off": if another app is in front during the run (not Idle
     * Heroes, no system window, not this app), the shield is gone IMMEDIATELY (the phone has to work there) and pocket
     * mode is off. If it stays like that, the bot stops about [FREMD_VORNE_MS] after Idle Heroes was last seen in front
     * (at least [FREMD_MINDESTENS_MS] after the other app was noticed). The screen is only locked if pocket mode was on –
     * otherwise the player is using the phone right now.
     * Review before publishing (Nov 22): [lage] comes from polling the window list (Android no longer sends events of
     * other apps). Review round 2 (Nov 23): the other app's name is not even known (no window is asked what it is).
     */
    private fun spielVorneWache(lage: SpielVorne.Lage) {
        haupt.removeCallbacks(vordergrundWache)
        if (BotZustand.lauf != BotZustand.Lauf.LAEUFT) {
            fremdVorneSeit = 0L
            eigeneAppVorne = false
            return
        }
        if (lage == SpielVorne.Lage.UNKLAR) {
            // Review: Android said nothing just now – if the check is running, look again soon.
            if (fremdVorneSeit != 0L) haupt.postDelayed(vordergrundWache, 500)
            return
        }
        // This app in front (settings from the IH panel): no shield while it is in front – and no stop.
        val eigene = lage == SpielVorne.Lage.EIGENE_APP
        if (eigene != eigeneAppVorne) {
            eigeneAppVorne = eigene
            schildPruefen()
        }
        if (lage != SpielVorne.Lage.FREMD) {
            if (fremdVorneSeit != 0L) {
                fremdVorneSeit = 0L
                BotZustand.log("Idle Heroes is in front again – the bot carries on")
                schildPruefen()
            }
            return
        }
        // Only once Idle Heroes was in front in THIS run – starting in the settings and then opening the game must work.
        if (!spielGeprueft) return
        val jetzt = System.currentTimeMillis()
        if (fremdVorneSeit == 0L) {
            fremdVorneSeit = jetzt
            fremdWarTasche = BotZustand.taschenmodusGewuenscht || taschenGesperrt()
            BotZustand.taschenmodusGewuenscht = false
            schildWeg()
            BotZustand.log("Another app is in front – shield removed; if it stays like that, the bot stops in about ${FREMD_VORNE_MS / 1000} s")
        }
        val noch = SpielVorne.stoppUm(fremdVorneSeit, spielZuletztVorne, FREMD_VORNE_MS, FREMD_MINDESTENS_MS) - jetzt
        if (noch > 0) {
            haupt.postDelayed(vordergrundWache, noch)
            return
        }
        fremdVorneSeit = 0L
        bot?.stoppeVonHand()
        beenden(
            StoppGrund.SPIEL_NICHT_VORNE,
            sperrenErzwingen = fremdWarTasche,
            text = "Idle Heroes was no longer in front (another app) – bot stopped",
        )
    }

    private fun vordergrundPruefen() {
        if (knopfSicht == null) return
        // Review: query in its own thread; if one is already running, exactly once more afterwards.
        if (vordergrundUnterwegs) {
            vordergrundNochmal = true
            return
        }
        vordergrundUnterwegs = true
        vordergrundHolen { blick ->
            vordergrundUnterwegs = false
            vordergrundAuswerten(blick)
            if (vordergrundNochmal) {
                vordergrundNochmal = false
                vordergrundPruefen()
            }
        }
    }

    /**
     * Result of "what is in front?" (main thread) – from the regular check or the one right after a screenshot.
     * Review round 2 (Nov 23): an app window that just appeared is not judged yet ([Blick.unbekannt]) – then look again
     * shortly (the game's event for it may still come).
     */
    private fun vordergrundAuswerten(blick: Blick) {
        val sicht = knopfSicht ?: return
        vorneLage = blick.lage
        vorneLageUm = SystemClock.uptimeMillis()
        if (blick.unbekannt && !haupt.hasCallbacks(vordergrundAufgabe)) haupt.postDelayed(vordergrundAufgabe, NEUES_FENSTER_NOCHMAL_MS)
        if (blick.lage == SpielVorne.Lage.SPIEL) spielVorneGesehen(sicht, blick.paket)
        if (knopfMussBleiben()) {
            // Bot running (or a hook holds the button): the button stays anyway – just check again once later.
            vordergrundVeraltet = true
            // But check whether Idle Heroes is still in front (otherwise the bot stops, see [spielVorneWache]).
            if (BotZustand.lauf == BotZustand.Lauf.LAEUFT || fremdVorneSeit != 0L) spielVorneWache(blick.lage)
            knopfPruefen()
            return
        }
        sicht.vordergrundGesehen(blick.paket)
        knopfPruefen()
    }

    /**
     * An allowed game app is in front: during a run that confirms the game for this run (shield, stop when leaving it);
     * the app is remembered for the settings ("Idle Heroes recognised") – only ever an app of the allow-list.
     */
    private fun spielVorneGesehen(sicht: KnopfSichtbarkeit, paket: String?) {
        if (BotZustand.lauf == BotZustand.Lauf.LAEUFT) {
            spielZuletztVorne = System.currentTimeMillis()
            spielGeprueft = true
        }
        if (paket != null && sicht.lernen(paket)) {
            SpielSpeicher.speichern(this, paket)
            BotZustand.log("IH button: Idle Heroes is the app \"$paket\"")
        }
    }

    /** The last result of "which app is in front?", if it is recent – otherwise [SpielVorne.Lage.UNKLAR]. */
    private fun vorneLageFrisch(): SpielVorne.Lage =
        if (SystemClock.uptimeMillis() - vorneLageUm <= VORNE_FRISCH_MS) vorneLage else SpielVorne.Lage.UNKLAR

    /** How often to check which app is in front right now (0 = not now). */
    private fun taktMs(): Long {
        val f = fenster
        return when {
            !zugestimmt || !bildschirmAn -> 0L
            knopfMussBleiben() -> VORNE_TAKT_LAUF_MS
            f != null && f.istSichtbar && knopfSicht?.nurImSpiel == true -> VORNE_TAKT_RUHE_MS
            else -> 0L
        }
    }

    private fun taktPlanen() {
        val ms = taktMs()
        if (ms > 0L && !haupt.hasCallbacks(vordergrundTakt)) haupt.postDelayed(vordergrundTakt, ms)
    }

    /** Review: check in its own thread which app is in front; [weiter] gets the result in the main thread. */
    private fun vordergrundHolen(weiter: (Blick) -> Unit) {
        try {
            vordergrundFaden.execute {
                val blick = runCatching { umsehen() }.getOrElse { Blick(SpielVorne.Lage.UNKLAR, null, false, false) }
                haupt.post { weiter(blick) }
            }
        } catch (e: Exception) {
            // The accessibility service is shutting down – then it does not matter.
        }
    }

    /**
     * What Android's window list says: which app is in front ([lage], [paket] = the game's app, this app,
     * [SpielVorne.FREMDE_APP] or null – never another app's name), may a screenshot taken now be kept ([frei]), and did an
     * app window appear just now that is not judged yet ([unbekannt]).
     */
    private class Blick(val lage: SpielVorne.Lage, val paket: String?, val frei: Boolean, val unbekannt: Boolean)

    /**
     * Review before publishing (Nov 22): look at the windows (any thread). Review round 2 (Nov 23): only the window
     * list's own data – no window is asked which app it belongs to (see [FensterZuordnung]).
     */
    private fun umsehen(): Blick {
        val liste = fensterListe()
        if (liste == null) {
            // No window list: nothing is known – no screenshot is kept, no gesture goes out (fail closed).
            freigabeMerken(false)
            return Blick(SpielVorne.Lage.UNKLAR, null, frei = false, unbekannt = false)
        }
        val frei = SpielVorne.bildFrei(liste, spielPakete, deckendPx)
        freigabeMerken(frei)
        return Blick(
            lage = SpielVorne.lage(liste, spielPakete, packageName),
            paket = SpielVorne.vorneApp(liste)?.paket,
            frei = frei,
            unbekannt = liste.any { it.art == SpielVorne.Art.APP && it.paket == null },
        )
    }

    private fun freigabeMerken(frei: Boolean) {
        letzteFreigabe = frei
        letzteFreigabeUm = SystemClock.uptimeMillis()
    }

    /**
     * The windows on the screen as the core needs them (null = Android gave none). Review round 2 (Nov 23): only kind,
     * id, layer, size and active/focused from the list itself; which app an app window belongs to comes from
     * [zuordnung] (the game's events, this app's own screen) – never from the window.
     */
    private fun fensterListe(): List<SpielVorne.Fenster>? {
        val alle = runCatching { windows }.getOrNull()
        if (alle.isNullOrEmpty()) return null
        val r = Rect()
        val roh = alle.map { w ->
            val art = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> SpielVorne.Art.APP
                AccessibilityWindowInfo.TYPE_SYSTEM -> SpielVorne.Art.SYSTEM
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> SpielVorne.Art.TASTATUR
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> SpielVorne.Art.UEBERLAGERUNG
                else -> SpielVorne.Art.ANDERES
            }
            r.setEmpty()
            runCatching { w.getBoundsInScreen(r) }
            SpielVorne.Fenster(
                art = art,
                aktiv = w.isActive,
                fokus = w.isFocused,
                schicht = w.layer,
                breite = r.width(),
                hoehe = r.height(),
                id = w.id,
            )
        }
        return zuordnung.einordnen(roh, BotZustand.appBildschirmOffen, packageName, SystemClock.uptimeMillis())
    }

    /**
     * Review before publishing (Nov 22): the volume keys are only looked at while a run is active – the key filter
     * (flagRequestFilterKeyEvents) is switched on at the start and off again afterwards.
     */
    private fun tastenFilter(an: Boolean) {
        if (tastenFilterAn == an) return
        val info = runCatching { serviceInfo }.getOrNull() ?: return
        info.flags = if (an) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        if (runCatching { serviceInfo = info }.isSuccess) tastenFilterAn = an
        if (!an) {
            leiserGedrueckt = Long.MIN_VALUE / 2
            lauterGedrueckt = Long.MIN_VALUE / 2
            leiserReihe.clear()
        }
    }

    /**
     * "✕ Hide" tapped on the panel. If the bot is running, it is stopped cleanly first (the button already asked
     * for two taps for that). Does not work while a hook holds the button ([Variante.knopfSperre], with its text).
     */
    fun knopfAusblenden() {
        val sicht = knopfSicht ?: return
        Variante.knopfSperre()?.let { grund ->
            Toast.makeText(this, grund, Toast.LENGTH_LONG).show()
            return
        }
        if (laufAktiv()) {
            BotZustand.log("\"Hide\" tapped – stopping the bot first")
            stoppenVonHand()
        }
        if (!sicht.ausblenden(knopfMussBleiben())) {
            Toast.makeText(this, "While the bot is running, the IH button stays", Toast.LENGTH_LONG).show()
            return
        }
        BotZustand.log("IH button hidden – it comes back when Idle Heroes comes to the front or the app is opened")
        Toast.makeText(
            this,
            "IH button hidden. It comes back when you open Idle Heroes – or the app \"IH Auction Bot\".",
            Toast.LENGTH_LONG,
        ).show()
        knopfPruefen()
    }

    /**
     * "Turn IH off completely" – the accessibility service turns itself off, the button is gone completely.
     * If the bot is running, it is stopped cleanly first (run ended, report is sent). Back on:
     * phone settings → Accessibility → IH Auction Bot.
     */
    fun ganzAusschalten() {
        val liefLauf = laufAktiv()
        if (liefLauf) {
            BotZustand.log("\"Turn IH off completely\" – stopping the bot first")
            stoppenVonHand()
        }
        BotZustand.log("IH turned off completely. To turn it back on: phone settings → Accessibility → IH Auction Bot")
        Toast.makeText(
            this,
            "IH is completely off. To turn it back on: phone settings → Accessibility → IH Auction Bot.",
            Toast.LENGTH_LONG,
        ).show()
        fenster?.entfernen()
        fenster = null
        // Wait briefly so that the stop is cleanly through (run saved, report on its way).
        haupt.postDelayed({
            runCatching { disableSelf() }.onFailure {
                // Failed (should never happen): show the button again, so that the player is not left without a button.
                BotZustand.log("Turning off failed: ${it.message}")
                if (fenster == null && instanz === this) fenster = SchwebeFenster(this).also { f -> f.zeigen() }
            }
        }, if (liefLauf) 1500L else 200L)
    }

    /** The app (settings) was opened: show the hidden button again. */
    fun appGeoeffnet() {
        knopfSicht?.appGeoeffnet()
        haupt.post(vordergrundAufgabe)
        // Button off the screen? Bring it back when the app is opened.
        haupt.post { fenster?.bildschirmGeaendert() }
    }

    /** Review round 2 (Nov 23): the app's screen was left ([BotZustand.appBildschirmOffen] is off) – look what is in front now. */
    fun appVerlassen() {
        haupt.post(vordergrundAufgabe)
    }

    /** Emergency button in the settings – IH button back to its starting position. */
    fun knopfZurueckholen() {
        haupt.post { fenster?.zurueckInDieEcke() }
    }

    /** After rotating (portrait ↔ landscape) keep the IH button on the screen. */
    override fun onConfigurationChanged(neu: android.content.res.Configuration) {
        super.onConfigurationChanged(neu)
        haupt.post { fenster?.bildschirmGeaendert() }
    }

    /** Setting "Show the IH button only in Idle Heroes" changed. */
    fun knopfEinstellungGeaendert(nurImSpiel: Boolean) {
        knopfSicht?.nurImSpiel = nurImSpiel
        haupt.post { knopfPruefen() }
    }

    /** Forget the learned game (settings). */
    fun spielVergessen() {
        knopfSicht?.vergessen()
        SpielSpeicher.speichern(this, null)
        BotZustand.log("IH button: learned game forgotten – the button is visible everywhere until the bot runs again")
        haupt.post { knopfPruefen() }
    }

    /** For the settings: which app has been learned as Idle Heroes (null = none yet). */
    val gelerntesSpiel: String? get() = knopfSicht?.spielPaket

    // ---------------- Control from the floating button ----------------

    fun starten() {
        if (BotZustand.lauf == BotZustand.Lauf.LAEUFT || BotZustand.lauf == BotZustand.Lauf.PAUSE) return
        // Review before publishing (Nov 22): nothing without the player's consent.
        if (!zugestimmt) {
            BotZustand.log("Open the app and agree first – until then the bot does nothing")
            BotZustand.melde()
            return
        }
        val einst = EinstellungenSpeicher.laden(this)
        if (einst.regeln.none { it.aktiv && it.gueltig }) {
            BotZustand.log("No search rule turned on – please create one in the settings")
            BotZustand.letzteMeldung = "No rule turned on – please create a rule in the settings"
            BotZustand.melde()
            return
        }
        // If a run was not ended with Stop (app crashed/restarted),
        // it continues with the same starting SG – otherwise a new run begins.
        val offenerStart = LaufSpeicher.offenerLaufStartSg(this)
        if (offenerStart != null && einst.sgBudget != null) BotZustand.log("Run continues – SG at the start was $offenerStart")
        // Scrolling: how far the list moves per swipe pixel on this phone – from the last runs.
        val wischGelernt = LernSpeicher.wischVerhaeltnis(this)
        wischStart = wischGelernt
        if (wischGelernt != null) BotZustand.log("Swiping: learned ratio ${"%.2f".format(wischGelernt)} from earlier runs")
        // Review round 2 (Nov 23): Google's text recognition only with the setting on – otherwise it is not set up at all
        // (and closed if an earlier run used it); the core reads every number itself.
        if (einst.mlKit) {
            leser.zweit = mlKit ?: MlKitLeser().also { mlKit = it }
        } else {
            leser.zweit = null
            mlKit?.schliessen()
            mlKit = null
        }
        val neu = AuktionsBot(
            regeln = einst.regeln,
            stopp = StoppBedingung(einst.zielKopien.takeIf { it > 0 }, einst.endeUm, einst.sgBudget),
            leser = leser,
            protokoll = BotZustand::log,
            hatZweitpasswort = Geheim.hatZweitpasswort(this),
            startSg = offenerStart,
            wischVerhaeltnisStart = wischGelernt,
            // Overnight: after "Lost connection" tap Confirm and carry on – at most 5 times per run.
            maxNeuverbindungen = 5,
            // Buyout (beta): only with the setting on and only for rules with a max buyout.
            buyoutAn = einst.buyout,
        )
        LaufSpeicher.begonnen(this, offenerStart)
        gemerkterStartSg = offenerStart
        BotZustand.sgBudget = einst.sgBudget
        BotZustand.sgVerplant = null
        bot = neu
        BotZustand.ziel = einst.zielKopien
        BotZustand.gekauft = 0
        BotZustand.sofortGekauft = 0
        BotZustand.neuverbindungen = 0
        BotZustand.laufendeGebote = 0
        BotZustand.stoppGrund = null
        BotZustand.wiederBietenAb = null
        BotZustand.limit = neu.gebote.limit
        BotZustand.endeText = einst.endeUm?.let { " · ends $it" } ?: ""
        BotZustand.hinweis = BotZustand.Hinweis.NORMAL
        BotZustand.sucht = neu.aktuelleRegel?.kopie
        BotZustand.lauf = BotZustand.Lauf.LAEUFT
        // Power saving: the screen is dimmed like with "very dark screen".
        stromsparen = einst.stromsparen
        hitzePause = false
        fenster?.bildschirm(anlassen = einst.bildschirmAnlassen, dunkel = einst.bildschirmDunkel || einst.stromsparen)
        diagnoseGespeichert = false
        spielGeprueft = false
        spielZuletztVorne = 0L
        wartetAufSpiel = false
        schrittGemerktUm = 0L
        unbekanntSeit = null
        unbekanntBildGespeichert = false
        zuSchnellGemeldet = false
        // Flight recorder (every screenshot of the last 3 minutes, see BerichtSender) – apply the setting.
        // This also calls the hook Variante.laufBeginnt.
        BerichtSender.laufBeginnt(this, einst.flugschreiber)
        flugGesperrteTipps = 0
        flugLimit = false
        flugUnbekannt = false
        haenger.start(System.currentTimeMillis())
        // Close the open panel after the start, so that it does not cover anything.
        fenster?.zuklappen()
        BotZustand.log("Bot started")
        // Overnight checks: only notes (the bot runs anyway). The battery limits apply right away.
        NachtCheck.warnungen(this).forEach { BotZustand.log("Note: $it") }
        // Review before publishing (Nov 22): started without an SG budget (e.g. from the IH button) – say so in the panel
        // too (the status line shows "⚠ no SG budget" for the whole run).
        if (einst.sgBudget == null) {
            BotZustand.letzteMeldung = "⚠ " + NachtCheck.KEIN_BUDGET_TEXT
            BotZustand.melde()
        }
        haupt.post { akkuPruefen(NachtCheck.akku(this)) }
        // Pocket mode at the start (setting "When I touch the screen: pocket mode") – or requested before in the
        // IH panel. Field test 1.30: "Bot stops" only if set that way – the default is no shield.
        fremdVorneSeit = 0L
        eigeneAppVorne = false
        unklarSeit = 0L
        abgebrocheneInFolge = 0
        schildGesperrt = false
        laufEndeMs = 0L
        schrittLief = SystemClock.uptimeMillis()
        schrittGeplant = schrittLief
        if (einst.beruehrung == Beruehrung.TASCHE) BotZustand.taschenmodusGewuenscht = true
        beruehrtStoppt = einst.beruehrung == Beruehrung.STOPPT
        schildPruefen()
        // Review round 2 (Nov 23): look what is in front right now, before the first step – the step only takes a
        // screenshot once a fresh check says the game is in front (until then it looks again every 250 ms).
        vordergrundPruefen()
        haupt.removeCallbacks(schrittAufgabe)
        haupt.post(schrittAufgabe)
        // Review: watchdog on (kicks off the steps if they ever stand still).
        letzterSchrittUm = System.currentTimeMillis()
        haupt.removeCallbacks(wachhund)
        haupt.postDelayed(wachhund, WACHHUND_MS)
        // Emergency stop with the volume keys from now on; check right away which app is in front.
        tastenFilter(true)
        taktPlanen()
        haupt.post(vordergrundAufgabe)
    }

    fun pauseUmschalten() {
        val b = bot ?: return
        when (BotZustand.lauf) {
            BotZustand.Lauf.LAEUFT -> {
                b.pausiert = true
                BotZustand.lauf = BotZustand.Lauf.PAUSE
                BotZustand.log("Paused")
            }
            BotZustand.Lauf.PAUSE -> {
                b.pausiert = false
                BotZustand.lauf = BotZustand.Lauf.LAEUFT
                BotZustand.log("Resumed")
                // Field test 1.8: the panel covered the left menu – close it when resuming.
                fenster?.zuklappen()
                // Review: if the next step is waiting (pause checks every second), go on right away.
                // If something is on its way right now (screenshot, gesture), it schedules by itself – then kick off nothing twice.
                if (haupt.hasCallbacks(schrittAufgabe)) plane(0)
            }
            else -> Unit
        }
    }

    fun stoppenVonHand() {
        val b = bot ?: return
        if (BotZustand.lauf != BotZustand.Lauf.LAEUFT && BotZustand.lauf != BotZustand.Lauf.PAUSE) return
        b.stoppeVonHand()
        beenden(StoppGrund.VON_HAND)
    }

    fun oeffneEinstellungen() {
        startActivity(Intent(this, EinstellungenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Error report by hand (button in the IH panel). */
    fun berichtSenden() {
        BerichtSender.sende(this, "by hand (IH button)", automatisch = false)
    }

    // ---------------- One step ----------------

    private fun plane(ms: Long) {
        // Shield guard: when the next step is scheduled.
        schrittGeplant = SystemClock.uptimeMillis() + ms
        haupt.removeCallbacks(schrittAufgabe)
        haupt.postDelayed(schrittAufgabe, ms)
    }

    private fun schritt() {
        val b = bot ?: return
        letzterSchrittUm = System.currentTimeMillis()
        schrittLief = SystemClock.uptimeMillis()
        // Review before publishing (Nov 22): for the screen lock after a crash – when the bot last made a step.
        if (laufAktiv() && letzterSchrittUm - schrittGemerktUm >= SCHRITT_MERKEN_MS) {
            schrittGemerktUm = letzterSchrittUm
            LaufSpeicher.schrittMerken(this, letzterSchrittUm)
        }
        when (BotZustand.lauf) {
            BotZustand.Lauf.PAUSE -> {
                // Also check during the pause whether the stop time has been reached.
                verarbeite(b, synchronized(b) { b.schritt(Instant.now()) }, null)
                return
            }
            BotZustand.Lauf.LAEUFT -> if (hitzePause) {
                // Too hot: no screenshot, no tap – only check the stop conditions (like in the pause).
                b.pausiert = true
                verarbeite(b, synchronized(b) { b.schritt(Instant.now()) }, null)
                return
            }
            else -> return
        }
        // Keep the minimum interval between two screenshots (otherwise Android refuses: error 3).
        val noch = minFotoAbstandMs - (System.currentTimeMillis() - letztesFotoUm)
        if (noch > 0) {
            plane(noch)
            return
        }
        // Review before publishing (Nov 22): while another app or this app is in front, the bot takes no screenshot at
        // all and taps nothing – it waits until Idle Heroes is back (checked every second; it stops after about 2 s
        // once the game had been in front in this run, see [spielVorneWache]).
        // Review round 2 (Nov 23, fail closed): a screenshot only if a recent check (at most 3 s old) says the game is in
        // front. Not known (no check yet, the check is late, a window just appeared): ask again right away and look again
        // in [UNKLAR_WARTEN_MS] – normally the answer is there long before, so the bot carries on as usual.
        val lage = vorneLageFrisch()
        if (lage == SpielVorne.Lage.UNKLAR) {
            vordergrundPruefen()
            val jetzt = SystemClock.uptimeMillis()
            if (unklarSeit == 0L) unklarSeit = jetzt
            if (jetzt - unklarSeit >= UNKLAR_MELDEN_MS && !wartetAufSpiel) {
                wartetAufSpiel = true
                BotZustand.log("Not clear which app is in front – the bot waits (no screenshots, no taps)")
                BotZustand.hinweis = BotZustand.Hinweis.SPIEL_FEHLT
                BotZustand.melde()
            }
            plane(UNKLAR_WARTEN_MS)
            return
        }
        unklarSeit = 0L
        if (lage != SpielVorne.Lage.SPIEL) {
            if (!wartetAufSpiel) {
                wartetAufSpiel = true
                BotZustand.log(
                    when {
                        lage == SpielVorne.Lage.EIGENE_APP ->
                            "This app is in front – the bot waits (no screenshots, no taps) until Idle Heroes is back"
                        // Review round 2 (Nov 23): the game's window is only known once it has sent an event (e.g. the
                        // service was turned on while the game was already open).
                        !zuordnung.spielBekannt() ->
                            "Idle Heroes is not in front or not recognised yet – the bot waits (no screenshots, no taps). " +
                                "Open Idle Heroes; if it is already open, switch to another app and back once"
                        else ->
                            "Another app is in front – the bot waits (no screenshots, no taps) until Idle Heroes (Google Play) is in front"
                    },
                )
            }
            BotZustand.hinweis = BotZustand.Hinweis.SPIEL_FEHLT
            BotZustand.melde()
            // Waiting on purpose – not "stuck" (no automatic report because of the wait).
            haenger.pruefe(System.currentTimeMillis(), leser.zuletzt?.ort, wartetAbsichtlich = true)
            plane(1000)
            return
        }
        if (wartetAufSpiel) {
            wartetAufSpiel = false
            if (BotZustand.hinweis == BotZustand.Hinweis.SPIEL_FEHLT) BotZustand.hinweis = BotZustand.Hinweis.NORMAL
        }
        // If the panel of the IH button is open, hide it briefly for the screenshot (it could cover something).
        // Field test 1.8 (the panel was still in the screenshot): wait longer until it is really gone.
        // Review before publishing (Nov 22): the same for the visible frame of the shield (a shorter wait).
        val versteckt = fenster?.fuerFotoVerstecken() == true
        val rahmenWeg = schild?.fuerFotoVerstecken() == true
        when {
            versteckt -> haupt.postDelayed({ foto(b) }, 200)
            rahmenWeg -> haupt.postDelayed({ foto(b) }, RAHMEN_WEG_MS)
            else -> foto(b)
        }
    }

    /** After a screenshot: the IH panel and the shield's frame are visible again. */
    private fun nachFoto() {
        fenster?.nachFotoZeigen()
        schild?.nachFotoZeigen()
    }

    private fun foto(b: AuktionsBot) {
        val fotoStart = System.currentTimeMillis()
        letztesFotoUm = fotoStart
        // Review before publishing (Nov 22): a screenshot is only kept if Idle Heroes was alone in front right before it
        // (last check) AND right after it (checked below).
        val freiVorher = letzteFreigabe && SystemClock.uptimeMillis() - letzteFreigabeUm <= VORNE_FRISCH_MS
        // Review: whichever comes first – Android's answer or the emergency time – schedules the next step; the other
        // is only cleaned up. So the bot never stands still, and two steps never run side by side.
        val erledigt = AtomicBoolean(false)
        val notfall = Runnable {
            if (!erledigt.compareAndSet(false, true)) return@Runnable
            nachFoto()
            BotZustand.log("Screenshot: no answer from Android – trying again")
            if (bot === b) plane(1500)
        }
        haupt.postDelayed(notfall, FOTO_NOTFALL_MS)
        val rueckruf = object : TakeScreenshotCallback {
            override fun onSuccess(bildschirmFoto: ScreenshotResult) {
                if (erledigt.get()) {
                    runCatching { bildschirmFoto.hardwareBuffer.close() }
                    return
                }
                // Review before publishing (Nov 22): what is on the screen right after the screenshot – may it be kept, and
                // is Idle Heroes still in front? (Review round 2, Nov 23: only the window list, no window is asked anything.)
                val blick = runCatching { umsehen() }.getOrNull()
                val frei = freiVorher && blick?.frei == true
                haupt.post {
                    nachFoto()
                    if (blick != null) vordergrundAuswerten(blick)
                }
                var farbraum = "?"
                val bild: Bitmap? = try {
                    farbraum = bildschirmFoto.colorSpace.name
                    val hw = Bitmap.wrapHardwareBuffer(bildschirmFoto.hardwareBuffer, bildschirmFoto.colorSpace)
                    val kopie = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    // Convert to sRGB – the recognition is tuned to sRGB colours.
                    kopie?.let { k -> inSrgb(k).also { if (it !== k) k.recycle() } }
                } catch (e: Exception) {
                    BotZustand.log("Converting the screenshot failed: ${e.message}")
                    null
                } finally {
                    bildschirmFoto.hardwareBuffer.close()
                }
                // Recognising and deciding in the background (analysing the screenshot takes a while),
                // tapping/swiping then in the main thread.
                var bildNr = -1L
                val ergebnis = if (bot === b && BotZustand.lauf == BotZustand.Lauf.LAEUFT) {
                    try {
                        b.hatZweitpasswort = Geheim.hatZweitpasswort(this@BotDienst)
                        synchronized(b) {
                            bildNr = leser.neuesBild(bild)
                            b.schritt(Instant.now())
                        }
                    } catch (e: Exception) {
                        BotZustand.log("Recognition error: ${e.message}")
                        null
                    }
                } else null
                letzteFotoDauerMs = System.currentTimeMillis() - fotoStart
                // Review (privacy): only what the recognition saw in EXACTLY this screenshot counts. If the bot did not
                // read the screenshot at all (e.g. stop time reached), it is not saved either – otherwise it could be
                // saved with the permission of the screenshot before (e.g. a secondary password window).
                val schirm = leser.zuletzt.takeIf { leser.frisch(bildNr) }
                // Review before publishing (Nov 22): and only while Idle Heroes is alone in front (no other app, no
                // notification shade, no keyboard, never this app).
                val erlaubt = bilderErlaubt(schirm?.ort) && frei
                // Remember for the error report – never with the secondary password window, never shortly after the input.
                // Flight recorder: EVERY analysed screenshot – without permission only the line, without the screenshot.
                if (bot === b && bildNr >= 0) {
                    // Field test 1.9: plus learning images of unreadable numbers from exactly the screenshot seen.
                    BerichtSender.merke(
                        bild, schirm, erlaubt, ergebnis?.let { beschreibe(it) },
                        if (erlaubt) leser.gesehen else null, b.erwartetesGebot, ergebnis,
                    )
                }
                diagnose(bild, farbraum, schirm, erlaubt)
                haupt.post {
                    if (!erledigt.compareAndSet(false, true)) return@post
                    haupt.removeCallbacks(notfall)
                    if (bot !== b) return@post
                    // Review ("bot stood still forever after Pause → Resume"): if the pause came while the screenshot was
                    // on its way, keep checking every second during the pause (otherwise nobody would schedule anything).
                    if (BotZustand.lauf == BotZustand.Lauf.PAUSE) {
                        plane(1000)
                        return@post
                    }
                    if (BotZustand.lauf != BotZustand.Lauf.LAEUFT) return@post
                    if (ergebnis == null) {
                        plane(1500)
                        return@post
                    }
                    verarbeite(b, ergebnis, bild.takeIf { erlaubt })
                }
            }

            override fun onFailure(fehler: Int) {
                haupt.post {
                    if (!erledigt.compareAndSet(false, true)) return@post
                    haupt.removeCallbacks(notfall)
                    nachFoto()
                    if (bot !== b) return@post
                    if (fehler == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        // Too fast in a row – wait briefly and try again (only logged once).
                        if (!zuSchnellGemeldet) BotZustand.log("Screenshots too fast in a row – waiting briefly")
                        zuSchnellGemeldet = true
                        plane(minFotoAbstandMs)
                        return@post
                    }
                    BotZustand.log("Screenshot failed (error $fehler)")
                    plane(1500)
                }
            }
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, bildArbeiter, rueckruf)
        } catch (e: Exception) {
            // Review: should never happen – then like an error from Android (continue in 1.5 s).
            BotZustand.log("Screenshot failed: ${e.message}")
            if (erledigt.compareAndSet(false, true)) {
                haupt.removeCallbacks(notfall)
                nachFoto()
                if (bot === b) plane(1500)
            }
        }
    }

    /** What the bot did with this screenshot – short, for the error report. */
    private fun beschreibe(e: Ergebnis): String = when (e) {
        is Ergebnis.Handeln -> "Act: ${e.aktion.grund} (next in ${e.naechsterSchrittMs} ms)"
        is Ergebnis.Warten -> "Wait: ${e.grund} (${e.ms} ms)"
        is Ergebnis.Unsicher -> "Unsure: ${e.grund} (${e.ms} ms)"
        is Ergebnis.Fertig -> "Done: ${e.grund.text}"
        Ergebnis.Pausiert -> "Paused"
    }

    /**
     * What does the bot see? At the start ONE diagnostic image (exactly what the recognition sees),
     * about every 3 seconds a line in the log, and the last recognised screen for the IH button.
     * Never while the secondary password window is open. Review before publishing (Nov 22): saved only inside the app
     * ([BildSpeicher]), and only if the game was alone in front ([erlaubt]).
     */
    private fun diagnose(bild: Bitmap?, farbraum: String, s: Bildschirm?, erlaubt: Boolean) {
        // Review: [s] comes from exactly this screenshot (null = not read), [erlaubt] = the screenshot may be saved.
        val gesehen = leser.gesehen
        BotZustand.sehe = (s ?: leser.zuletzt)?.ort?.text
        val jetzt = System.currentTimeMillis()
        if (s == null || bild == null) return
        if (jetzt - letzteDiagnoseZeile >= 3000) {
            letzteDiagnoseZeile = jetzt
            BotZustand.log(
                "Screenshot ${bild.width}×${bild.height}, colour space $farbraum → sRGB" +
                    (if (leser.drehung != 0) ", rotated ${leser.drehung}°" else "") +
                    ", recognised: ${s.ort.text}, rows: ${s.zeilen.size}" + (s.sg?.let { ", SG $it" } ?: "") +
                    // Where does the time go? Screenshot + recognition of this step.
                    ", ⏱ screenshot+recognition $letzteFotoDauerMs ms (recognition ${leser.letzteErkennungMs} ms)",
            )
        }
        if (!erlaubt) return
        if (!diagnoseGespeichert && gesehen != null) {
            diagnoseGespeichert = true
            BildSpeicher.speichereUnsicher(this, alsBitmap(gesehen), "diagnostics at the start", sofort = true, vorsilbe = "diagnose")
        }
        if (s.ort == Ort.UNBEKANNT) {
            val seit = unbekanntSeit ?: jetzt.also { unbekanntSeit = it }
            if (jetzt - seit >= 10_000 && !unbekanntBildGespeichert && gesehen != null) {
                unbekanntBildGespeichert = true
                BildSpeicher.speichereUnsicher(this, alsBitmap(gesehen), "nothing recognised for 10 seconds", sofort = true)
            }
            BotZustand.unbekanntLange = jetzt - seit >= 10_000
        } else {
            unbekanntSeit = null
            unbekanntBildGespeichert = false
            BotZustand.unbekanntLange = false
        }
    }

    private fun alsBitmap(b: io.github.hustlebot.ihauctionbot.kern.bild.Bild): Bitmap =
        Bitmap.createBitmap(b.pixel, b.breite, b.hoehe, Bitmap.Config.ARGB_8888)

    private fun verarbeite(b: AuktionsBot, ergebnis: Ergebnis, bild: Bitmap?) {
        // Privacy: while the secondary password window is open, no screenshot may be taken or kept.
        if (leser.zuletzt?.ort == Ort.ZWEITPASSWORT) Variante.passwortSchutz(3_000)
        BotZustand.gekauft = b.gebote.gekaufteKopien
        BotZustand.sofortGekauft = b.sofortGekauft
        BotZustand.neuverbindungen = b.neuverbindungen
        BotZustand.laufendeGebote = b.gebote.eintraege
        BotZustand.sucht = b.aktuelleRegel?.kopie
        BotZustand.sgVerplant = b.sgVerplant
        b.startSg?.let { s ->
            if (s != gemerkterStartSg) {
                gemerkterStartSg = s
                LaufSpeicher.startSgMerken(this, s)
            }
        }
        // Say clearly in the floating button what the bot is waiting for right now.
        BotZustand.hinweis = when {
            hitzePause && ergebnis !is Ergebnis.Fertig -> BotZustand.Hinweis.HITZE
            ergebnis is Ergebnis.Fertig && ergebnis.grund == StoppGrund.ZWEITPASSWORT_FALSCH -> BotZustand.Hinweis.PASSWORT_FALSCH
            ergebnis is Ergebnis.Fertig && ergebnis.grund == StoppGrund.VERBINDUNG_WEG -> BotZustand.Hinweis.VERBINDUNG
            b.passwortFehlt -> BotZustand.Hinweis.PASSWORT_FEHLT
            ergebnis is Ergebnis.Unsicher && leser.zuletzt?.ort == Ort.UNBEKANNT -> BotZustand.Hinweis.UNBEKANNT
            b.sgUnlesbar -> BotZustand.Hinweis.SG_UNLESBAR
            b.zuWenigSg -> BotZustand.Hinweis.SG_FEHLT
            b.budgetVerplant -> BotZustand.Hinweis.BUDGET
            // "50 bids" only if the game reported it ("-7") – the own count is only approximate.
            // Tidy up My Biddings (after "-7" or at about 50 according to the own count).
            b.raeumtAuf -> BotZustand.Hinweis.AUFRAEUMEN
            b.gebote.limitGemeldet -> BotZustand.Hinweis.LIMIT
            else -> BotZustand.Hinweis.NORMAL
        }
        // Times in the device's time zone.
        BotZustand.wiederBietenAb = b.gebote.wiederBietenAb?.let { DateTimeFormatter.ofPattern("HH:mm:ss").format(it.atZone(ZoneId.systemDefault())) }
        b.endZeitpunkt?.let { BotZustand.endeText = " · ends " + DateTimeFormatter.ofPattern("HH:mm").format(it.atZone(ZoneId.systemDefault())) }
        // Shield guard: if the end time passed 1 minute ago, the shield surely goes away.
        laufEndeMs = b.endZeitpunkt?.toEpochMilli() ?: 0L

        // Is the bot stuck (45 s without action on the same screen, without a good reason)? → report.
        val jetztMs = System.currentTimeMillis()
        if (ergebnis is Ergebnis.Handeln) haenger.gehandelt(jetztMs)
        val absichtlich = BotZustand.lauf == BotZustand.Lauf.PAUSE || BotZustand.hinweis in setOf(
            BotZustand.Hinweis.LIMIT, BotZustand.Hinweis.BUDGET, BotZustand.Hinweis.SG_FEHLT, BotZustand.Hinweis.PASSWORT_FEHLT,
            BotZustand.Hinweis.AUFRAEUMEN,
        )
        if (ergebnis !is Ergebnis.Fertig && haenger.pruefe(jetztMs, leser.zuletzt?.ort, absichtlich)) {
            BerichtSender.sende(
                this,
                "Bot stuck for 45 s on: ${leser.zuletzt?.ort?.text ?: "?"} – ${BotZustand.letzteMeldung}",
                automatisch = true,
                // Review (mobile data): flight recorder only the last minute.
                flugKurz = true,
            )
        }
        when (ergebnis) {
            // Review: the next step is only scheduled when the gesture is really over – and the core's waiting time
            // counts from the moment it started (in pocket mode a bit later than otherwise).
            is Ergebnis.Handeln -> fuehreAus(b, ergebnis.aktion) { gestartetUm -> plane(nachGeste(gestartetUm, ergebnis.naechsterSchrittMs)) }
            is Ergebnis.Warten -> {
                BotZustand.letzteMeldung = ergebnis.grund
                // Power saving: while the bot only waits (5 s or more), it looks less often – no screenshots in between.
                plane(if (stromsparen && ergebnis.ms >= STROMSPAREN_AB_MS) maxOf(ergebnis.ms, STROMSPAREN_WARTEN_MS) else ergebnis.ms)
            }
            is Ergebnis.Unsicher -> {
                BotZustand.letzteMeldung = ergebnis.grund
                // Screenshots with the secondary password window are never saved.
                // Review: [bild] is only set if exactly this screenshot may be saved.
                // Buyout: an unknown window after "Buyout Price" – this screenshot must be kept (saved in the app right away,
                // report at the stop right after), so that the window can be learned.
                val buyoutFenster = ergebnis.grund.startsWith("Buyout:")
                if (bild != null && bilderErlaubt(leser.zuletzt?.ort)) {
                    BildSpeicher.speichereUnsicher(this, bild, ergebnis.grund, sofort = buyoutFenster, vorsilbe = if (buyoutFenster) "buyout" else "unsicher")
                }
                plane(ergebnis.ms)
            }
            is Ergebnis.Fertig -> beenden(ergebnis.grund)
            Ergebnis.Pausiert -> plane(if (hitzePause) 5_000 else 1000)
        }
        // Hook after a step (cheap, never slows the bot down; here it does nothing).
        if (ergebnis !is Ergebnis.Fertig && bot === b) Variante.nachSchritt(this, b, leser.zuletzt?.ort?.text)
        if (ergebnis !is Ergebnis.Fertig && bot === b) flugEreignisse(b)
        BotZustand.melde()
    }

    // Flight recorder: state at the last step – an event is when any of it changes.
    private var flugGesperrteTipps = 0
    private var flugLimit = false
    private var flugUnbekannt = false

    /**
     * Flight recorder: special events → report with flight recorder (at most every 10 minutes, see
     * [BerichtSender.flugEreignis]): "tap blocked", 10 s unknown screen, the game reports 50 bids ("-7").
     * (Stops – also "unknown window after X" and pocket mode – send the report at the end of the run anyway.)
     */
    private fun flugEreignisse(b: AuktionsBot) {
        val gesperrt = b.gesperrteTipps
        val limit = b.gebote.limitGemeldet
        val unbekannt = BotZustand.unbekanntLange
        val ereignis = when {
            gesperrt > flugGesperrteTipps -> "tap blocked"
            limit && !flugLimit -> "the game reports 50 bids (-7)"
            unbekannt && !flugUnbekannt -> "10 s unknown screen"
            else -> null
        }
        flugGesperrteTipps = gesperrt
        flugLimit = limit
        flugUnbekannt = unbekannt
        ereignis?.let { BerichtSender.flugEreignis(this, it) }
    }

    /**
     * Review: how long until the next step? The core counts [ms] from the moment the gesture starts.
     * [gestartetUm] = 0: there was no gesture (or it failed) – then [ms] from now.
     */
    private fun nachGeste(gestartetUm: Long, ms: Long): Long =
        if (gestartetUm <= 0L) ms else (gestartetUm + ms - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Carries out an action and then calls [weiter] – review: only when it is really over (for text inputs,
     * when the text is in). [weiter] gets when the gesture started (0 = count from now).
     */
    private fun fuehreAus(b: AuktionsBot, aktion: Aktion, weiter: (gestartetUm: Long) -> Unit) {
        when (aktion) {
            // Field test 1.9: the duration comes from the core (a bit longer in the sort window).
            is Aktion.Tippen -> tippen(b, aktion.punkt, aktion.dauerMs, aktion.grund, weiter)
            is Aktion.Wischen -> {
                if (aktion.haltenMs > 0) {
                    wischenUndHalten(b, aktion, weiter)
                } else {
                    wischen(b, aktion.von, aktion.nach, aktion.dauerMs, aktion.grund, weiter)
                }
            }
            // Tap the price field and write the number into it.
            // Review: the field is only searched once the tap on the field is finished.
            is Aktion.Eingeben -> tippen(b, aktion.feld, 60L, aktion.grund) { gestartetUm ->
                if (gestartetUm <= 0L) {
                    // The field was not tapped (stop, pause, failed) – then write nothing either.
                    if (bot === b) b.eingabeErgebnis(false)
                    weiter(0L)
                    return@tippen
                }
                textEingabe.schreibe(aktion.text, pruefen = true, nochErlaubt = { darfGeste(b) }, getippt = aktion.feld) { ok ->
                    BotZustand.log("${aktion.grund}: " + if (ok) "entered" else "failed")
                    if (bot === b) b.eingabeErgebnis(ok)
                    weiter(0L)
                }
            }
            // Speed: several actions without a screenshot in between (filter: rank + both price fields).
            is Aktion.Folge -> fuehreFolgeAus(b, aktion, 0, true) { weiter(0L) }
            // Secondary password – only comes from private storage, never appears in the log.
            is Aktion.PasswortEingeben -> {
                val pw = Geheim.zweitpasswort(this)
                if (pw == null) {
                    b.eingabeErgebnis(false)
                    weiter(0L)
                    return
                }
                keineBilderBis = System.currentTimeMillis() + BerichtDatenschutz.NACH_PASSWORT_MS
                // Privacy: no screenshot briefly before typing, so the digits are never in a screenshot.
                Variante.passwortSchutz(5_000)
                tippen(b, aktion.feld, 60L, aktion.grund) { gestartetUm ->
                    if (gestartetUm <= 0L) {
                        if (bot === b) b.eingabeErgebnis(false)
                        weiter(0L)
                        return@tippen
                    }
                    // Review: the blocking times from now on (in pocket mode the tap came a bit later).
                    keineBilderBis = System.currentTimeMillis() + BerichtDatenschutz.NACH_PASSWORT_MS
                    Variante.passwortSchutz(5_000)
                    textEingabe.schreibe(pw, pruefen = false, passwort = true, nochErlaubt = { darfGeste(b) }, getippt = aktion.feld) { ok ->
                        BotZustand.log("Secondary password " + if (ok) "entered" else "could not be entered")
                        if (bot === b) b.eingabeErgebnis(ok)
                        weiter(0L)
                    }
                }
            }
        }
    }

    /**
     * An [Aktion.Folge] one after another: tap, then a pause of [Aktion.Folge.abstandMs]; input = tap the field,
     * write the text, then pause. At the end tell the bot whether ALL inputs worked, and continue.
     * If the bot is stopped or paused on the way, the sequence is cancelled.
     * Review: the pause counts from the end of each gesture.
     */
    private fun fuehreFolgeAus(b: AuktionsBot, folge: Aktion.Folge, i: Int, alleOk: Boolean, weiter: () -> Unit) {
        if (bot !== b) return
        if (BotZustand.lauf != BotZustand.Lauf.LAEUFT) {
            // Pause/stop in the middle of the sequence: skip the rest, the next step checks the screenshot again.
            if (folge.hatEingaben) b.eingabeErgebnis(false)
            weiter()
            return
        }
        if (i >= folge.schritte.size) {
            if (folge.hatEingaben) b.eingabeErgebnis(alleOk)
            weiter()
            return
        }
        when (val s = folge.schritte[i]) {
            is Aktion.Tippen -> tippen(b, s.punkt, s.dauerMs, s.grund) { _ ->
                haupt.postDelayed({ fuehreFolgeAus(b, folge, i + 1, alleOk, weiter) }, folge.abstandMs)
            }
            is Aktion.Eingeben -> tippen(b, s.feld, 60L, s.grund) { gestartetUm ->
                if (gestartetUm <= 0L) {
                    haupt.postDelayed({ fuehreFolgeAus(b, folge, i + 1, false, weiter) }, folge.abstandMs)
                    return@tippen
                }
                textEingabe.schreibe(s.text, pruefen = true, nochErlaubt = { darfGeste(b) }, getippt = s.feld) { ok ->
                    BotZustand.log("${s.grund}: " + if (ok) "entered" else "failed")
                    haupt.postDelayed({ fuehreFolgeAus(b, folge, i + 1, alleOk && ok, weiter) }, folge.abstandMs)
                }
            }
            // Other actions do not belong in a sequence (the core does not allow them at all).
            else -> fuehreFolgeAus(b, folge, i + 1, false, weiter)
        }
    }

    /** Area of a gesture in screen pixels (for the hole in the shield and the IH button). */
    private fun bereich(a: Punkt, c: Punkt): Rect =
        Rect(minOf(a.x, c.x), minOf(a.y, c.y), maxOf(a.x, c.x) + 1, maxOf(a.y, c.y) + 1)

    /**
     * Android's callback for a gesture: completed or cancelled – both mean "over". [fertig] gets whether it was
     * cancelled (review: a real touch cancels a gesture of the bot).
     */
    private fun rueckruf(fertig: (abgebrochen: Boolean) -> Unit) = object : GestureResultCallback() {
        override fun onCompleted(d: GestureDescription?) = fertig(false)
        override fun onCancelled(d: GestureDescription?) = fertig(true)
    }

    /** A tap at [p] (finger down for [dauer] ms). [danach] as in [gesteAusfuehren]. */
    private fun tippen(b: AuktionsBot, p: Punkt, dauer: Long, grund: String, danach: (gestartetUm: Long) -> Unit) {
        val x = p.x.toFloat()
        val y = p.y.toFloat()
        gesteAusfuehren(b, bereich(p, p), listOf(x to y), dauer, grund, { fertig ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, dauer))
                .build()
            dispatchGesture(g, rueckruf(fertig), null)
        }, danach)
    }

    /** A swipe from [von] to [nach] in [dauer] ms. */
    private fun wischen(b: AuktionsBot, von: Punkt, nach: Punkt, dauer: Long, grund: String, danach: (gestartetUm: Long) -> Unit) {
        val vonX = von.x.toFloat()
        val vonY = von.y.toFloat()
        gesteAusfuehren(b, bereich(von, nach), listOf(vonX to vonY), dauer, grund, { fertig ->
            val pfad = Path().apply {
                moveTo(vonX, vonY)
                lineTo(nach.x.toFloat(), nach.y.toFloat())
            }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(pfad, 0, dauer)).build()
            dispatchGesture(g, rueckruf(fertig), null)
        }, danach)
    }

    /**
     * Scrolling ("always exactly 5 new copies"): drag slowly and at the end hold the finger still for
     * [Aktion.Wischen.haltenMs] before it is released – then the list has no momentum and stops exactly.
     * Android: first stroke with "will continue", then a still stroke at the same position.
     * Holding still belongs to the same gesture and is still sent after a stop – the finger has to come up.
     */
    private fun wischenUndHalten(b: AuktionsBot, a: Aktion.Wischen, danach: (gestartetUm: Long) -> Unit) {
        val vonX = a.von.x.toFloat()
        val vonY = a.von.y.toFloat()
        val nachX = a.nach.x.toFloat()
        val nachY = a.nach.y.toFloat()
        gesteAusfuehren(b, bereich(a.von, a.nach), listOf(vonX to vonY), a.dauerMs + a.haltenMs, a.grund, { fertig ->
            val strich = GestureDescription.StrokeDescription(
                Path().apply {
                    moveTo(vonX, vonY)
                    lineTo(nachX, nachY)
                },
                0,
                a.dauerMs,
                true,
            )
            dispatchGesture(
                GestureDescription.Builder().addStroke(strich).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        try {
                            val halten = strich.continueStroke(Path().apply { moveTo(nachX, nachY) }, 0, a.haltenMs, false)
                            val ok2 = dispatchGesture(GestureDescription.Builder().addStroke(halten).build(), rueckruf(fertig), null)
                            if (!ok2) {
                                BotZustand.log("Holding still after the swipe failed: ${a.grund}")
                                fertig(false)
                            }
                        } catch (e: Exception) {
                            BotZustand.log("Holding still after the swipe failed: ${e.message}")
                            fertig(false)
                        }
                    }

                    override fun onCancelled(g: GestureDescription?) = fertig(true)
                },
                null,
            )
        }, danach)
    }

    /**
     * May the bot send a gesture (or type text) right now? (Never after a stop or during the pause.) Review before
     * publishing (Nov 22): also never while another app or this app is in front – the tap or the text would land there.
     * Review round 2 (Nov 23, fail closed): ONLY while a recent check (at most [VORNE_FRISCH_MS] old) says the game is in
     * front – not when it is unclear. (The check runs every second during a run and after every screenshot.)
     */
    private fun darfGeste(b: AuktionsBot): Boolean =
        bot === b && BotZustand.lauf == BotZustand.Lauf.LAEUFT && spielSicherVorne()

    private fun spielSicherVorne(): Boolean = fremdVorneSeit == 0L && vorneLageFrisch() == SpielVorne.Lage.SPIEL

    /** Review round 2 (Nov 23): a gesture of the running bot was skipped only because the game was not confirmed in front – say so. */
    private fun gesteNichtVorne(b: AuktionsBot, grund: String) {
        if (bot === b && BotZustand.lauf == BotZustand.Lauf.LAEUFT && !spielSicherVorne()) {
            BotZustand.log("Gesture skipped – Idle Heroes is not confirmed in front right now ($grund)")
        }
    }

    // ---------------- Review: gestures and real touches ----------------

    /** How many gestures of the bot are on their way right now (from scheduling until "over"). While some are on their way, no new shield comes. */
    private var gestenUnterwegs = 0

    /** Since when a finger is on the IH window (SystemClock.uptimeMillis, 0 = none). */
    @Volatile private var knopfBeruehrtSeit = 0L

    /** Field test 1.30: did the current gesture get stuck on the shield (so it did not arrive in the game)? */
    private var gesteHaengenGeblieben = false

    /** Gestures in a row that Android cancelled (a real touch came in between). */
    private var abgebrocheneInFolge = 0

    /**
     * From the IH window: finger down (true) or released (false). Android cancels a real touch as soon as the bot
     * sends a gesture – that is why the bot waits while a finger is on the IH button or the panel (holding IH for 2 s,
     * tapping "Stop" …).
     */
    fun knopfBeruehrt(an: Boolean) {
        knopfBeruehrtSeit = if (an) SystemClock.uptimeMillis() else 0L
    }

    private fun knopfGehalten(): Boolean {
        val seit = knopfBeruehrtSeit
        return seit != 0L && SystemClock.uptimeMillis() - seit < KNOPF_GEHALTEN_MAX_MS
    }

    /** A gesture is over: cancelled (real touch in between) or completed. In pocket mode: too often → stop. */
    private fun gesteVorbei(abgebrochen: Boolean, grund: String) {
        if (!abgebrochen) {
            abgebrocheneInFolge = 0
            return
        }
        abgebrocheneInFolge++
        BotZustand.log("Gesture cancelled – a touch came in between (#$abgebrocheneInFolge in a row): $grund")
        if (abgebrocheneInFolge >= MAX_ABGEBROCHEN && taschenGesperrt() && laufAktiv()) {
            BotZustand.log("🎒 Pocket mode: $MAX_ABGEBROCHEN gestures cancelled in a row – stopping the bot")
            bot?.stoppeVonHand()
            beenden(StoppGrund.TASCHENMODUS_TIPPS)
        }
    }

    /**
     * Review: EVERY gesture of the bot goes through here.
     *  - If a finger is on the IH window, it waits (every [KNOPF_WARTEN_MS]) – otherwise Android would cancel the touch.
     *  - If the path ([bereich]) lies on the IH button, the button lets all taps through for the gesture.
     *  - Shield: it opens (if possible only a strip around the path) and closes again afterwards.
     *  - Right before sending it is checked again whether the bot is still running – after a stop no gesture goes out.
     *  - [senden] sends the gesture to Android (true = accepted) and calls "fertig(abgebrochen)" when Android reports.
     *  - [danach] comes EXACTLY ONCE when everything is over (also on errors, at the latest after the emergency time),
     *    with the time the gesture started (0 = not at all).
     */
    private fun gesteAusfuehren(
        b: AuktionsBot,
        bereich: Rect,
        startPunkte: List<Pair<Float, Float>>,
        dauerMs: Long,
        grund: String,
        senden: (fertig: (abgebrochen: Boolean) -> Unit) -> Boolean,
        danach: (gestartetUm: Long) -> Unit,
    ) {
        var vorbei = false
        var gestartetUm = 0L
        var knopfDurchlaessig = false
        var notfall: Runnable? = null
        gestenUnterwegs++
        fun ende() {
            if (vorbei) return
            vorbei = true
            gestenUnterwegs = (gestenUnterwegs - 1).coerceAtLeast(0)
            notfall?.let { haupt.removeCallbacks(it) }
            if (knopfDurchlaessig) fenster?.nachGesteZeigen()
            danach(gestartetUm)
            // Was a shield waiting for no gesture to be on its way any more? (see [schildPruefen])
            if (gestenUnterwegs == 0 && schild == null) schildPruefen()
        }
        notfall = Runnable {
            BotZustand.log("No answer from Android for the gesture – carrying on anyway ($grund)")
            ende()
        }.also { haupt.postDelayed(it, SchildDurchlass.MAX_VORLAUF_MS + KNOPF_VORLAUF_MS + dauerMs + SchildDurchlass.NOTFALL_MS) }
        lateinit var los: (offenFertig: () -> Unit) -> Unit
        los = los@{ offenFertig ->
            if (vorbei || !darfGeste(b)) {
                if (!vorbei) gesteNichtVorne(b, grund)
                offenFertig()
                ende()
                return@los
            }
            // The finger only came onto the IH window during the lead time: do NOT send the gesture, close the shield
            // again right away and skip the gesture (final check: before, the open strip stayed open for up to 2.4 s).
            // The core sees in the next screenshot that nothing happened, and plans again.
            if (knopfGehalten()) {
                BotZustand.log("Finger on the IH button – gesture skipped ($grund)")
                offenFertig()
                ende()
                return@los
            }
            gestartetUm = System.currentTimeMillis()
            gesteHaengenGeblieben = false
            val angenommen = try {
                senden { abgebrochen ->
                    offenFertig()
                    gesteVorbei(abgebrochen, grund)
                    // Field test 1.30 (the tap on the max price field got stuck on the shield, the text ended up in the
                    // min price field): if the gesture did not arrive in the game (cancelled or stuck on the shield),
                    // it counts as "did not happen" – then, for example, no text is written either.
                    if (abgebrochen || gesteHaengenGeblieben) gestartetUm = 0L
                    ende()
                }
            } catch (e: Exception) {
                BotZustand.log("Gesture failed: ${e.message} ($grund)")
                false
            }
            if (!angenommen) {
                BotZustand.log("Tap failed: $grund")
                gestartetUm = 0L
                offenFertig()
                ende()
            }
        }
        fun anfangen() {
            if (vorbei) return
            if (!darfGeste(b)) {
                gesteNichtVorne(b, grund)
                ende()
                return
            }
            // If a finger is on the IH window: wait first, then open the shield/IH button.
            if (knopfGehalten()) {
                haupt.postDelayed({ anfangen() }, KNOPF_WARTEN_MS)
                return
            }
            knopfDurchlaessig = fenster?.fuerGesteVerstecken(bereich) == true
            val s = schild
            when {
                s != null -> s.durchlassen(bereich, startPunkte, dauerMs, leser.zuletzt?.bildBreite ?: 0, los)
                // Without a shield: if the IH button has just switched to "pass-through", wait briefly until Android has it.
                knopfDurchlaessig -> haupt.postDelayed({ los {} }, KNOPF_VORLAUF_MS)
                else -> los {}
            }
        }
        anfangen()
    }

    /**
     * End the run. Review: [sperrenErzwingen] = lock the screen even if [grund] does not say so
     * (e.g. pocket mode: Idle Heroes no longer in front); [text] = what shows in the IH panel and in the log.
     */
    private fun beenden(grund: StoppGrund, sperrenErzwingen: Boolean = false, text: String = grund.text) {
        haupt.removeCallbacks(schrittAufgabe)
        haupt.removeCallbacks(wachhund)
        haupt.removeCallbacks(vordergrundWache)
        fremdVorneSeit = 0L
        if (BotZustand.lauf == BotZustand.Lauf.GESTOPPT) return
        BotZustand.lauf = BotZustand.Lauf.GESTOPPT
        hitzePause = false
        wartetAufSpiel = false
        // Review before publishing (Nov 22): the volume keys are no longer looked at.
        tastenFilter(false)
        // Review: no more gestures from now on. Whatever is still waiting to pass the shield is dropped, and the shield is
        // closed again right away. (Gestures without a shield check right before sending whether the bot is still running.)
        schild?.gestenAbbrechen()
        val sperren = grund.sperren || sperrenErzwingen
        LaufSpeicher.beendet(this)
        // Scrolling: if the run learned how far the list moves, remember it for the next run.
        runCatching {
            val w = bot?.wischVerhaeltnis
            val alt = wischStart
            if (w != null && (if (alt == null) w != 1.0 else kotlin.math.abs(w - alt) > 1e-6)) {
                LernSpeicher.wischVerhaeltnisMerken(this, w)
                BotZustand.log("Swiping: ratio ${"%.2f".format(w)} remembered for the next run")
            }
        }
        if (grund == StoppGrund.ZWEITPASSWORT_FALSCH) BotZustand.hinweis = BotZustand.Hinweis.PASSWORT_FALSCH
        if (grund == StoppGrund.VERBINDUNG_WEG) BotZustand.hinweis = BotZustand.Hinweis.VERBINDUNG
        BotZustand.letzteMeldung = text
        BotZustand.stoppGrund = text
        // Field test 1.20 ("could not get back into the phone"): after EVERY stop the shield is gone right away and
        // pocket mode is off – it never stays beyond a stop.
        BotZustand.taschenmodusGewuenscht = false
        schildWeg()
        Variante.laufEnde()
        BotZustand.log("Stopped: $text. Bought: ${BotZustand.gekauft} copies")
        fenster?.bildschirm(anlassen = false, dunkel = false)
        BotZustand.melde()
        // After every run automatically an error report (log + latest screenshots).
        // Flight recorder: this report always comes – if one is running right now, afterwards.
        // Review before publishing (Nov 22): afterwards the flight recorder's screenshots in the cache are deleted.
        BerichtSender.sende(this, "Run ended: $text", automatisch = true, immer = true, flugDanachLeeren = true)
        if (sperren) {
            // Wait briefly so that the message can still be seen – then screen off (not shut down).
            haupt.postDelayed({
                // Review: if a new run was started in the meantime (so the player is there), do not lock.
                if (laufAktiv()) {
                    BotZustand.log("Screen stays on – a new run is already active")
                } else {
                    val ok = performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    BotZustand.log(if (ok) "Screen turned off" else "Turning the screen off failed")
                }
            }, 3000)
        }
    }
}
