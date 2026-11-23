package io.github.hustlebot.ihauctionbot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.hustlebot.ihauctionbot.kern.SchildDurchlass
import kotlin.math.abs

/**
 * Pocket mode. A window over the whole screen swallows every touch from outside, so that nothing in the game gets
 * tapped while the phone is in a pocket. The touch-swallowing strips themselves draw nothing; what is visible is a thin
 * coloured frame with a small label in its own window ([rahmen], see below), which is hidden briefly for every
 * screenshot of the bot – so the recognition never sees it. Dimming is still done only through the brightness.
 *
 * For each gesture of the bot, [BotDienst] makes it briefly pass-through ([durchlassen]) – afterwards it is closed again.
 * The IH button lies in its own window ABOVE it (stays usable). Field test 1.20 ("could not get back into the phone"):
 * unlock = hold the IH button for 2 s (one step, no extra button any more), or the power button, or emergency stop:
 * volume down 3 times (within 2 s) or both volume keys briefly together – see [BotDienst]. The shield itself remembers
 * nothing: after a restart there is never a shield.
 *
 * Field test 1.30 (Motorola with Android 16: the bot's taps got stuck on the shield): before, a second window was MOVED
 * for the "hole". But Android moves windows with a small movement (animation) – while it runs, the window still takes
 * touches at the old position. Exactly the taps whose hole lay to the right of the previous one landed on the still
 * moving window (1559/584 after 1153/584 twice, 1641/682 after 1356/555); all others got through, and "fully open"
 * (just a switch, nothing moves) worked every time. In the emulator the animations are off – that is why it did not
 * show up there.
 * Now the shield consists of [STREIFEN] fixed, vertical strip windows side by side that are NEVER moved or resized.
 * For a gesture only the strips around its path are switched to pass-through (the same switch as for "fully open").
 * If something does not fit (widths unclear, a tap of the bot still hits the shield), the whole shield becomes
 * briefly pass-through as before. Pass-through only counts once Android has redrawn the windows (two frames) – and
 * never earlier than [SchildDurchlass.vorlaufMs].
 *
 * While the shield is there, the screen stays on (FLAG_KEEP_SCREEN_ON on the first strip).
 *
 * Review before publishing (Nov 22): the shield is visible – a thin coloured frame around the screen and a small
 * label ("Pocket mode – hold IH 2 s"), in their own window that takes no touches ([rahmen]). For the bot's own
 * screenshots they are hidden briefly ([fuerFotoVerstecken]), like the open IH panel. BotDienst only puts up a shield
 * once Idle Heroes is confirmed in front.
 */
class TaschenSchild(
    dienst: BotDienst,
    /** A tap of the bot arrived at the shield (exactly at the planned position) – it got stuck there. */
    private val gesteHaengt: () -> Unit,
    /** Someone else touched the screen (the touch was swallowed). */
    private val beruehrt: () -> Unit,
) {
    private val ctx: Context = ContextThemeWrapper(dienst, android.R.style.Theme_DeviceDefault)
    private val wm = dienst.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val haupt = Handler(Looper.getMainLooper())

    /** Which gestures may pass right now (rules and tests in the core). */
    val durchlass = SchildDurchlass()

    /** A strip: fixed window from [links] to [rechts] (screen pixels), full height. */
    private class Streifen(val ansicht: FrameLayout, val params: WindowManager.LayoutParams, val links: Int, val rechts: Int) {
        var da = false
        var offen = false
    }

    private val streifen = ArrayList<Streifen>()

    /** Visible frame + label (own window, takes no touches; null = could not be shown – the shield works anyway). */
    private var rahmen: FrameLayout? = null
    private val rahmenText = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        val d = dienst.resources.displayMetrics.density
        setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
    }
    private val rahmenRand = (3 * dienst.resources.displayMetrics.density).toInt().coerceAtLeast(2)

    /** Pocket mode (true) or "a touch stops the bot" – only for the frame's colour and label. */
    private var tasche = true

    /** Is the frame hidden right now for a screenshot of the bot? */
    private var rahmenFuerFotoWeg = false

    /** Screen width the strips were created for. */
    private var breite = 0

    var gezeigt = false
        private set

    private enum class Modus { ZU, LOCH, GANZ }

    private var modus = Modus.ZU

    /** Left and right edge of the open area (only in mode LOCH). */
    private var lochLinks = 0
    private var lochRechts = 0

    /** Taps of the bot that got stuck on the shield despite the hole – from [LOCH_FEHLER_MAX] on only "fully open". */
    private var lochFehler = 0
    private var lochAus = false
    private var modusGemeldet = false

    /** Counts up on [entfernen]/[gestenAbbrechen] – a gesture still waiting for two frames is then dropped. */
    private var abbruchNr = 0

    /** Where the currently open gestures start (number from [SchildDurchlass.auf] → points). */
    private val ziele = HashMap<Long, List<Pair<Float, Float>>>()

    /**
     * Swallow every touch from outside. Review: only a touch EXACTLY at the position where a gesture of the bot is
     * starting counts as "stuck" – a foreign touch (pocket) does not count.
     */
    @SuppressLint("ClickableViewAccessibility")
    private val beruehrung = View.OnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (durchlass.durchlaessig && amZiel(e.rawX, e.rawY)) {
                if (modus == Modus.LOCH && !lochAus && ++lochFehler >= LOCH_FEHLER_MAX) {
                    lochAus = true
                    BotZustand.log("🎒 Shield: the bot's taps hit the shield next to the hole – from now on the whole shield opens briefly for every gesture")
                }
                gesteHaengt()
            } else {
                beruehrt()
            }
        }
        true
    }

    private fun amZiel(x: Float, y: Float): Boolean =
        ziele.values.any { punkte -> punkte.any { (px, py) -> abs(px - x) <= NAH_PX && abs(py - y) <= NAH_PX } }

    private fun streifenParams(links: Int, breiteStreifen: Int, ersterStreifen: Boolean) = WindowManager.LayoutParams(
        breiteStreifen,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (ersterStreifen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = links
        y = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // Review: no insets for system bars – window position = screen position.
        fitInsetsTypes = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    fun zeigen(): Boolean {
        if (gezeigt) return true
        val b = runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(0)
        streifen.clear()
        // Unknown width: a single strip over everything (then there is only "fully open").
        val anzahl = if (b > 0) STREIFEN else 1
        val gesamt = if (b > 0) b else WindowManager.LayoutParams.MATCH_PARENT
        breite = b
        for (i in 0 until anzahl) {
            val links = if (b > 0) i * b / anzahl else 0
            val rechts = if (b > 0) (i + 1) * b / anzahl else b
            val w = if (b > 0) rechts - links else gesamt
            val ansicht = FrameLayout(ctx).apply {
                setOnTouchListener(beruehrung)
                keepScreenOn = i == 0
            }
            streifen += Streifen(ansicht, streifenParams(links, w, i == 0), links, rechts)
        }
        for (s in streifen) {
            val ok = try {
                wm.addView(s.ansicht, s.params)
                true
            } catch (e: Exception) {
                BotZustand.log("Pocket mode: the shield could not be created (${e.message})")
                false
            }
            if (!ok) {
                streifen.filter { it.da }.forEach { runCatching { wm.removeView(it.ansicht) } }
                streifen.clear()
                return false
            }
            s.da = true
        }
        gezeigt = true
        modus = Modus.ZU
        rahmenZeigen()
        return true
    }

    /** The visible frame + label: its own window over the whole screen that takes no touches (never fatal). */
    private fun rahmenZeigen() {
        if (rahmen != null) return
        val ansicht = FrameLayout(ctx)
        (rahmenText.parent as? android.view.ViewGroup)?.removeView(rahmenText)
        ansicht.addView(rahmenText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = rahmenRand })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            fitInsetsTypes = 0
        }
        if (runCatching { wm.addView(ansicht, params) }.isSuccess) {
            rahmen = ansicht
            rahmenFuerFotoWeg = false
            rahmenFaerben()
        } else {
            BotZustand.log("Shield: the visible frame could not be shown (the shield works anyway)")
        }
    }

    private fun rahmenFaerben() {
        val farbe = if (tasche) FARBE_TASCHE else FARBE_STOPP
        rahmenText.text = if (tasche) "Pocket mode – hold IH 2 s" else "Shield – a touch stops the bot"
        rahmenText.background = GradientDrawable().apply {
            setColor(farbe)
            cornerRadius = rahmenRand * 3f
        }
        rahmen?.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(rahmenRand, farbe)
        }
    }

    /** Pocket mode or "a touch stops the bot": colour and label of the frame. */
    fun hinweis(tascheAn: Boolean) {
        if (tasche == tascheAn && rahmen != null) return
        tasche = tascheAn
        rahmenFaerben()
    }

    /**
     * Before a screenshot of the bot: hide the frame and label (like the IH panel). true = they were visible – then
     * wait a moment before the screenshot, and call [nachFotoZeigen] afterwards.
     */
    fun fuerFotoVerstecken(): Boolean {
        val r = rahmen ?: return false
        if (rahmenFuerFotoWeg) return false
        r.visibility = View.INVISIBLE
        rahmenFuerFotoWeg = true
        return true
    }

    fun nachFotoZeigen() {
        if (!rahmenFuerFotoWeg) return
        rahmenFuerFotoWeg = false
        rahmen?.visibility = View.VISIBLE
    }

    private fun rahmenWeg(sofort: Boolean): Boolean {
        val r = rahmen ?: return true
        rahmen = null
        rahmenFuerFotoWeg = false
        return runCatching { if (sofort) wm.removeViewImmediate(r) else wm.removeView(r) }.isSuccess
    }

    fun entfernen() {
        abbruchNr++
        haupt.removeCallbacksAndMessages(null)
        durchlass.vergessen()
        ziele.clear()
        for (s in streifen) {
            if (s.da) runCatching { wm.removeView(s.ansicht) }
            s.da = false
            s.ansicht.keepScreenOn = false
        }
        streifen.clear()
        rahmenWeg(sofort = false)
        gezeigt = false
        modus = Modus.ZU
    }

    /**
     * Review (a crash left the shield behind): remove IMMEDIATELY, without going through the main thread's message
     * queue (it no longer runs after a crash). Only call from the main thread. true = all windows are gone.
     */
    fun sofortEntfernen(): Boolean {
        var ok = true
        for (s in streifen) {
            if (s.da) ok = runCatching { wm.removeViewImmediate(s.ansicht) }.isSuccess && ok
            s.da = false
        }
        streifen.clear()
        ok = rahmenWeg(sofort = true) && ok
        gezeigt = false
        return ok
    }

    /**
     * Review: the bot has stopped. Gestures still waiting to pass are dropped, and the shield is closed completely
     * right away (it may stay there for now, see [BotDienst]).
     */
    fun gestenAbbrechen() {
        abbruchNr++
        haupt.removeCallbacksAndMessages(null)
        durchlass.vergessen()
        ziele.clear()
        if (gezeigt) schliessen()
    }

    /**
     * Keep the screen on (while the bot is running) – not after a stop: then it turns off after the normal time,
     * and the shield goes away together with the screen.
     */
    fun bildschirmAnlassen(an: Boolean) {
        val s = streifen.firstOrNull() ?: return
        if (!s.da) return
        s.ansicht.keepScreenOn = an
        s.params.flags = if (an) {
            s.params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            s.params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        runCatching { wm.updateViewLayout(s.ansicht, s.params) }
    }

    /**
     * A gesture of the bot needs to pass: open the shield (only the strips around [bereich], or fully) and call [geste]
     * once Android has applied it – at the earliest after [SchildDurchlass.vorlaufMs] and after two redrawn frames.
     * [geste] gets "fertig" (done) – it must be called after the gesture (Android's answer: completed or cancelled,
     * also on errors). After that, the shield is closed again after [SchildDurchlass.nachlaufMs].
     * Emergency: if "fertig" never comes, it is locked again anyway after [dauerMs] + [SchildDurchlass.NOTFALL_MS].
     * [startPunkte]: where the gesture starts (only there a touch counts as "stuck").
     * [bildBreite]: width of the last screenshot (= the screen the bot taps on), 0 = unknown.
     */
    fun durchlassen(
        bereich: Rect,
        startPunkte: List<Pair<Float, Float>>,
        dauerMs: Long,
        bildBreite: Int,
        geste: (fertig: () -> Unit) -> Unit,
    ) {
        if (!gezeigt) {
            geste {}
            return
        }
        val (id, jetztAuf) = durchlass.auf()
        ziele[id] = startPunkte
        if (jetztAuf) {
            oeffnen(bereich, bildBreite)
        } else if (modus == Modus.LOCH && (bereich.left - LOCH_RAND_PX < lochLinks || bereich.right + LOCH_RAND_PX > lochRechts)) {
            // A second gesture outside the open area (should not happen): then fully open.
            ganzOeffnen()
        }
        val zu = Runnable {
            ziele.remove(id)
            if (durchlass.zu(id) && gezeigt) schliessen()
        }
        val vorlauf = durchlass.vorlaufMs
        haupt.postDelayed(zu, vorlauf + dauerMs + SchildDurchlass.NOTFALL_MS)
        val fertig = {
            haupt.removeCallbacks(zu)
            haupt.postDelayed(zu, durchlass.nachlaufMs)
            Unit
        }
        val los = Runnable {
            try {
                geste(fertig)
            } catch (e: Exception) {
                BotZustand.log("Pocket mode: gesture failed (${e.message})")
                fertig()
            }
        }
        // Only once Android has redrawn the windows (two frames), and never earlier than the lead time.
        val start = SystemClock.uptimeMillis()
        val nr = abbruchNr
        nachZweiBildern {
            // Cancelled in the meantime or shield gone: the gesture is dropped (as before with the lead time; BotDienst carries on).
            if (nr != abbruchNr || !gezeigt) return@nachZweiBildern
            val rest = (vorlauf - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0L)
            haupt.postDelayed(los, rest)
        }
    }

    /** [weiter] after the frame after next (Choreographer) – in the main thread. */
    private fun nachZweiBildern(weiter: () -> Unit) {
        val ch = runCatching { Choreographer.getInstance() }.getOrNull()
        if (ch == null) {
            haupt.post { weiter() }
            return
        }
        ch.postFrameCallback { ch.postFrameCallback { haupt.post { weiter() } } }
    }

    /** Open the shield for a gesture: if possible only the strips around [bereich], otherwise fully. */
    private fun oeffnen(bereich: Rect, bildBreite: Int) {
        val jetzt = runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(0)
        val links = bereich.left - LOCH_RAND_PX
        val rechts = bereich.right + LOCH_RAND_PX
        // Only use the hole if three sources agree on the screen width (strips when created, window now,
        // screenshot).
        val warum = when {
            lochAus -> null // already reported
            streifen.size < 2 -> "only one strip"
            breite <= 0 || jetzt != breite || bildBreite <= 0 || abs(breite - bildBreite) > 2 ->
                "widths do not match (strips $breite / window $jetzt / screenshot $bildBreite)"
            else -> "ok"
        }
        if (!modusGemeldet) {
            modusGemeldet = true
            // Once per shield everything that is needed to recalculate the layout.
            val ort = IntArray(2)
            val erster = streifen.firstOrNull()
            runCatching { erster?.ansicht?.getLocationOnScreen(ort) }
            BotZustand.log(
                "🎒 Shield: ${streifen.size} strips, first at ${ort[0]}/${ort[1]} " +
                    "${erster?.ansicht?.width ?: 0}x${erster?.ansicht?.height ?: 0}, screen $jetzt wide, screenshot $bildBreite wide – " +
                    if (warum == "ok") "for gestures only the strips around their path open" else "fully open for gestures ($warum)",
            )
        }
        if (warum != "ok") {
            ganzOeffnen()
            return
        }
        // Only flip switches – no window is moved or resized.
        for (s in streifen) {
            val auf = s.rechts > links && s.links < rechts
            if (auf) setzeOffen(s, true)
        }
        lochLinks = streifen.filter { it.offen }.minOfOrNull { it.links } ?: links
        lochRechts = streifen.filter { it.offen }.maxOfOrNull { it.rechts } ?: rechts
        modus = Modus.LOCH
    }

    /** As before: the whole shield briefly pass-through. */
    private fun ganzOeffnen() {
        for (s in streifen) setzeOffen(s, true)
        modus = Modus.GANZ
    }

    /** Closed again: all strips touchable again. */
    private fun schliessen() {
        for (s in streifen) setzeOffen(s, false)
        modus = Modus.ZU
    }

    private fun setzeOffen(s: Streifen, offen: Boolean) {
        if (!s.da || s.offen == offen) return
        s.params.flags = if (offen) {
            s.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            s.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (runCatching { wm.updateViewLayout(s.ansicht, s.params) }.isSuccess) s.offen = offen
    }

    private companion object {
        /** Within this distance (pixels) of the planned position a touch counts as a tap of the bot. */
        const val NAH_PX = 8f

        /** At least this much (pixels) stays open to the left and right of the gesture's path. */
        const val LOCH_RAND_PX = 40

        /** This many fixed strips (226 pixels wide each on the test phone). */
        const val STREIFEN = 12

        /** A tap of the bot may get stuck this often despite the hole, then only "fully open". */
        const val LOCH_FEHLER_MAX = 2

        /** Frame colours: pocket mode orange, "a touch stops the bot" blue. */
        const val FARBE_TASCHE = 0xE6EF6C00.toInt()
        const val FARBE_STOPP = 0xE61565C0.toInt()
    }
}
