package io.github.hustlebot.ihauctionbot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.hustlebot.ihauctionbot.kern.ZweimalTippen
import kotlin.math.abs

/**
 * The floating IH button over the game.
 *
 * Simple to use:
 *  - Collapsed: round button in the colour of the state (green = running, yellow = paused, grey = off).
 *  - Tapping it opens the panel: status in simple words and four big buttons (Start, Pause, Stop, Settings).
 *  - Press and drag moves the button.
 *
 * Field test 1.8 (the open panel covered the left menu): while the bot is running, the panel closes by itself
 * after [AUTO_ZU_MS].
 *
 * Version 1.11 (the button floated over every app): when the button is visible is decided by
 * [io.github.hustlebot.ihauctionbot.kern.KnopfSichtbarkeit] in [BotDienst]; here it is only shown and hidden ([sichtbarkeit]).
 * When hidden, the window is empty and lets every tap through.
 */
class SchwebeFenster(private val dienst: BotDienst) {

    private val ctx: Context = ContextThemeWrapper(dienst, android.R.style.Theme_DeviceDefault)
    private val wm = dienst.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dichte = dienst.resources.displayMetrics.density
    private fun dp(wert: Int): Int = (wert * dichte).toInt()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Bottom left above the purple menu: the bot does not read or tap anything there (the player can move it).
        x = dp(8)
        y = dp(290)
    }

    /**
     * Review ("hold IH for 2 s" was interrupted by the bot's taps): Android cancels a real touch as soon as the bot
     * sends a gesture. That is why the window reports every touch (button AND panel, e.g. "Stop") to [BotDienst] –
     * while a finger is on it, the bot sends no gesture.
     */
    private val wurzel = object : LinearLayout(ctx) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> dienst.knopfBeruehrt(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dienst.knopfBeruehrt(false)
            }
            return super.dispatchTouchEvent(e)
        }
    }.apply { orientation = LinearLayout.VERTICAL }

    private val knopf = TextView(ctx).apply {
        text = "IH"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        background = rund(FARBE_AUS)
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
    }

    private val feld = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = GradientDrawable().apply {
            setColor(0xF0202124.toInt())
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.WRAP_CONTENT)
    }

    /**
     * 1.11 (more buttons in the panel): the panel can scroll, so that the lower buttons can be reached too
     * when it would reach past the bottom edge of the screen. Height limited to the space below the IH button.
     */
    private val rolle = object : ScrollView(ctx) {
        override fun onMeasure(breiteSpec: Int, hoeheSpec: Int) {
            super.onMeasure(breiteSpec, View.MeasureSpec.makeMeasureSpec(maxHoehe, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
    }

    /** Maximum height of the panel (space below the IH button down to the bottom edge). */
    private var maxHoehe = Int.MAX_VALUE / 2

    /**
     * The button had been pushed off the screen (e.g. dragged down in portrait – in the game, in landscape,
     * that position is outside) and could not be reached any more. It now always stays fully on the screen:
     * while dragging, when shown, after rotating and when the app is opened.
     * @return true if the position was changed.
     */
    private fun imBildschirmHalten(): Boolean {
        val rahmen = runCatching { wm.currentWindowMetrics.bounds }.getOrNull() ?: return false
        val rand = dp(4)
        val maxX = (rahmen.width() - dp(54) - rand).coerceAtLeast(rand)
        val maxY = (rahmen.height() - dp(54) - rand).coerceAtLeast(rand)
        val x = params.x.coerceIn(rand, maxX)
        val y = params.y.coerceIn(rand, maxY)
        if (x == params.x && y == params.y) return false
        params.x = x
        params.y = y
        return true
    }

    /** After rotating or when the app is opened: bring the button back onto the screen if needed. */
    fun bildschirmGeaendert() {
        if (!gezeigt) return
        if (imBildschirmHalten()) runCatching { wm.updateViewLayout(wurzel, params) }
        rolleBegrenzen()
    }

    /** Emergency (button in the settings): back to the starting position at the bottom left. */
    fun zurueckInDieEcke() {
        params.x = dp(8)
        params.y = dp(290)
        imBildschirmHalten()
        if (gezeigt) runCatching { wm.updateViewLayout(wurzel, params) }
        rolleBegrenzen()
    }

    /** Recalculate the space below the button (when opening the panel and after moving it). */
    private fun rolleBegrenzen() {
        val hoehe = runCatching { wm.currentWindowMetrics.bounds.height() }.getOrNull() ?: return
        val neu = (hoehe - params.y - dp(54) - dp(6) - dp(8)).coerceAtLeast(dp(160))
        if (neu != maxHoehe) {
            maxHoehe = neu
            rolle.requestLayout()
        }
    }

    private val titel = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val status = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, dp(4), 0, dp(2))
    }

    private val meldung = TextView(ctx).apply {
        setTextColor(0xFFBDBDBD.toInt())
        textSize = 13f
        maxLines = 3
        setPadding(0, dp(2), 0, dp(6))
    }

    /** Notice from the hook [Variante.panelHinweis] (if any). */
    private val zusatzHinweis = TextView(ctx).apply {
        setTextColor(0xFFFFB74D.toInt())
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        visibility = View.GONE
        setPadding(0, 0, 0, dp(6))
    }

    private val startKnopf = grosserKnopf("▶  Start", 0xFF2E7D32.toInt()) { dienst.starten() }
    private val pauseKnopf = grosserKnopf("❚❚  Pause", 0xFFF9A825.toInt()) { dienst.pauseUmschalten() }
    private val stoppKnopf = grosserKnopf("■  Stop", 0xFFC62828.toInt()) { dienst.stoppenVonHand() }
    private val einstellungenKnopf = grosserKnopf("⚙  Settings", 0xFF455A64.toInt()) {
        feld.visibility = View.GONE
        dienst.oeffneEinstellungen()
    }

    /**
     * "🎒 Pocket mode" – the whole screen is locked while the bot is running (the bot's taps get through).
     * Unlock: hold the IH button for 2 s.
     */
    private val taschenKnopf = grosserKnopf("🎒  Pocket mode", 0xFF6D4C41.toInt()) {
        val warAn = BotZustand.taschenmodusGewuenscht
        dienst.taschenmodusUmschalten()
        if (!warAn && laeuftGerade()) feld.visibility = View.GONE
        aktualisiere()
    }

    /** Error report (log + recent screenshots, never the password) – what happens with it: see [Variante]. */
    private val berichtKnopf = grosserKnopf(Variante.BERICHT_KNOPF, 0xFF1565C0.toInt()) { dienst.berichtSenden() }

    /**
     * "✕ Hide" in the button grid – puts the IH button away until Idle Heroes is in front again.
     * If the bot is running, it is stopped cleanly first – for that you have to tap twice.
     */
    private val ausblendenBestaetigung = ZweimalTippen(BESTAETIGEN_MS)
    private val ausblendenKnopf = grosserKnopf("✕  Hide", 0xFF546E7A.toInt()) {
        if (laeuftGerade() && !ausblendenBestaetigung.tippen(System.currentTimeMillis())) {
            fragen()
            return@grosserKnopf
        }
        ausblendenBestaetigung.abbrechen()
        feld.visibility = View.GONE
        dienst.knopfAusblenden()
    }

    /**
     * "Turn IH off completely" – accessibility service off, the button is gone completely. Tap twice (no extra
     * window); if the bot is running, it is stopped cleanly first.
     */
    private val ausschaltenBestaetigung = ZweimalTippen(BESTAETIGEN_MS)
    private val ausschaltenKnopf = grosserKnopf("⏻  Turn IH off completely", 0xFF4E342E.toInt()) {
        if (!ausschaltenBestaetigung.tippen(System.currentTimeMillis())) {
            fragen()
            return@grosserKnopf
        }
        dienst.ganzAusschalten()
    }

    /** Explanation while asking: how to turn it back on. */
    private val ausschaltenHinweis = TextView(ctx).apply {
        text = "To turn it back on: phone settings → Accessibility → IH Auction Bot."
        setTextColor(0xFFFFB74D.toInt())
        textSize = 13f
        visibility = View.GONE
        setPadding(0, dp(4), 0, 0)
    }

    /** Is the button put away right now (not visible, lets all taps through)? */
    private var versteckt = false

    /** May "Hide" be tapped right now? (Not while a hook holds the button, see [Variante.knopfSperre].) */
    private var ausblendenErlaubt = true

    private fun laeuftGerade(): Boolean = BotZustand.lauf == BotZustand.Lauf.LAEUFT || BotZustand.lauf == BotZustand.Lauf.PAUSE

    /** First tap: the button asks "Tap again …"; after [BESTAETIGEN_MS] the normal text is back. */
    private fun fragen() {
        // Do not close automatically while asking.
        wurzel.removeCallbacks(autoZu)
        zuGeplant = false
        aktualisiere()
        wurzel.postDelayed({ aktualisiere() }, BESTAETIGEN_MS + 100)
    }

    private val tipp = TextView(ctx).apply {
        text = "Tap IH = close · press and drag = move"
        setTextColor(0xFF9E9E9E.toInt())
        textSize = 11f
        setPadding(0, dp(8), 0, 0)
    }

    private var gezeigt = false
    private val beobachter: () -> Unit = { wurzel.post { aktualisiere() } }

    /** Is the automatic closing already scheduled? (Do not reschedule on every step, otherwise it never closes.) */
    private var zuGeplant = false
    private val autoZu = Runnable {
        zuGeplant = false
        // Even if it is only invisible for the screenshot right now: then it stays closed after the screenshot.
        if (BotZustand.lauf == BotZustand.Lauf.LAEUFT && (feld.visibility == View.VISIBLE || fuerFotoVersteckt)) {
            fuerFotoVersteckt = false
            feld.visibility = View.GONE
            aktualisiere()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun zeigen() {
        feld.addView(titel)
        feld.addView(status)
        feld.addView(meldung)
        feld.addView(zusatzHinweis)
        feld.addView(reihe(startKnopf, pauseKnopf))
        feld.addView(reihe(stoppKnopf, einstellungenKnopf))
        // Pocket mode.
        feld.addView(taschenKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
        // "✕ Hide" in the same grid, across the full width.
        feld.addView(ausblendenKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
        feld.addView(berichtKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        // Extra button from the hook [Variante.panelKnopf] (if any); it can close the panel.
        Variante.panelKnopf(ctx) { feld.visibility = View.GONE }?.let { feld.addView(it) }
        feld.addView(ausschaltenKnopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        feld.addView(ausschaltenHinweis)
        feld.addView(tipp)
        wurzel.addView(knopf)
        rolle.addView(feld)
        wurzel.addView(rolle)
        rolleBegrenzen()

        val schwelle = ViewConfiguration.get(ctx).scaledTouchSlop
        knopf.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var rohX = 0f
            private var rohY = 0f
            private var bewegt = false

            /**
             * Review: this touch began in pocket mode. It belongs to it entirely until released – even if pocket mode
             * turns off in the middle after holding for 2 s (otherwise releasing would close the panel again right away).
             */
            private var tascheBeruehrung = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) tascheBeruehrung = dienst.taschenGesperrt()
                // Pocket mode: a normal tap does nothing, neither does moving – only holding for 2 s
                // (without slipping) unlocks (field test 1.20: the second step "Unlock" did not work).
                if (tascheBeruehrung) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            rohX = e.rawX
                            rohY = e.rawY
                            dienst.taschenKnopfGedrueckt()
                        }
                        MotionEvent.ACTION_MOVE ->
                            if (abs(e.rawX - rohX) > schwelle * 3 || abs(e.rawY - rohY) > schwelle * 3) dienst.taschenKnopfVerrutscht()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            dienst.taschenKnopfLosgelassen()
                            tascheBeruehrung = false
                        }
                    }
                    return true
                }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        rohX = e.rawX
                        rohY = e.rawY
                        bewegt = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - rohX).toInt()
                        val dy = (e.rawY - rohY).toInt()
                        if (abs(dx) > schwelle || abs(dy) > schwelle) bewegt = true
                        if (bewegt) {
                            params.x = startX + dx
                            params.y = startY + dy
                            // Do not allow dragging past the edge of the screen.
                            imBildschirmHalten()
                            rolleBegrenzen()
                            wm.updateViewLayout(wurzel, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!bewegt) {
                        feld.visibility = if (feld.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        if (feld.visibility == View.VISIBLE) {
                            rolleBegrenzen()
                            rolle.scrollTo(0, 0)
                        }
                        // Closed or opened: open "Tap again …" questions are over.
                        ausblendenBestaetigung.abbrechen()
                        ausschaltenBestaetigung.abbrechen()
                        // Newly opened: the time until the automatic closing starts again.
                        wurzel.removeCallbacks(autoZu)
                        zuGeplant = false
                        aktualisiere()
                    }
                }
                return true
            }
        })

        imBildschirmHalten()
        wm.addView(wurzel, params)
        gezeigt = true
        BotZustand.beobachte(beobachter)
        aktualisiere()
    }

    /** Pocket mode safety: is the button registered right now (on the screen)? */
    val istGezeigt: Boolean get() = gezeigt

    /** Is the button visible right now (registered and not put away)? */
    val istSichtbar: Boolean get() = gezeigt && !versteckt

    /**
     * Pocket mode: the shield is its own window and was added AFTER the IH button – otherwise it would lie on top of it.
     * Register the window once more, so that the IH button is on top again.
     * Field test 1.20 ("could not get back into the phone"): the button is then ALWAYS visible and touchable (no matter
     * whether "only in Idle Heroes" or hidden) and on the screen. false = failed – then there must be no shield.
     */
    fun ueberSchild(): Boolean {
        if (!gezeigt) return false
        versteckt = false
        fuerGesteVersteckt = false
        wurzel.visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        imBildschirmHalten()
        runCatching { wm.removeViewImmediate(wurzel) }
        val ok = runCatching { wm.addView(wurzel, params) }
            .onFailure { BotZustand.log("IH button could not be brought to the front: ${it.message}") }
            .isSuccess
        if (!ok) {
            // No longer registered: treat it that way, so that nobody believes the button is there.
            gezeigt = false
            BotZustand.vergiss(beobachter)
        }
        aktualisiere()
        return ok
    }

    /** After unlocking, open the panel (show the status). */
    fun aufklappen() {
        if (!gezeigt || versteckt) return
        feld.visibility = View.VISIBLE
        rolleBegrenzen()
        rolle.scrollTo(0, 0)
        wurzel.removeCallbacks(autoZu)
        zuGeplant = false
        aktualisiere()
    }

    fun entfernen() {
        if (!gezeigt) return
        wurzel.removeCallbacks(autoZu)
        BotZustand.vergiss(beobachter)
        runCatching { wm.removeView(wurzel) }
        gezeigt = false
    }

    /**
     * Show or put away the button. Put away: panel closed, nothing visible, the window lets every tap through
     * (it stays registered – so nothing gets lost). Only changes something when the state changes.
     */
    fun sichtbarkeit(sichtbar: Boolean, ausblendenErlaubt: Boolean) {
        val erlaubtGeaendert = ausblendenErlaubt != this.ausblendenErlaubt
        this.ausblendenErlaubt = ausblendenErlaubt
        if (!gezeigt) return
        if (sichtbar == !versteckt) {
            if (erlaubtGeaendert) aktualisiere()
            return
        }
        versteckt = !sichtbar
        if (versteckt) {
            ausblendenBestaetigung.abbrechen()
            ausschaltenBestaetigung.abbrechen()
            wurzel.removeCallbacks(autoZu)
            zuGeplant = false
            fuerFotoVersteckt = false
            feld.visibility = View.GONE
            wurzel.visibility = View.GONE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            wurzel.visibility = View.VISIBLE
            // Review: if a gesture of the bot is going over the button right now, it stays pass-through until afterwards.
            if (!fuerGesteVersteckt) params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            // When shown again, make sure it is on the screen (e.g. after rotating to landscape).
            imBildschirmHalten()
        }
        runCatching { wm.updateViewLayout(wurzel, params) }
        aktualisiere()
    }

    /** Close the panel (after the start, so that it does not cover anything). */
    fun zuklappen() {
        feld.visibility = View.GONE
        aktualisiere()
    }

    private var fuerFotoVersteckt = false

    /** Review: is the button letting all taps through right now for a gesture of the bot? */
    private var fuerGesteVersteckt = false

    /**
     * Review ("a tap of the bot must never land on the IH button"): if the path of a gesture ([bereich], screen pixels)
     * lies on the IH button or the open panel, the window lets all taps through for the gesture (it stays visible –
     * invisible would briefly cancel "keep screen on"). true = made pass-through; call [nachGesteZeigen] afterwards.
     */
    fun fuerGesteVerstecken(bereich: Rect): Boolean {
        if (!gezeigt || versteckt || fuerGesteVersteckt) return false
        if (wurzel.width <= 0 || wurzel.height <= 0) return false
        val ort = IntArray(2)
        wurzel.getLocationOnScreen(ort)
        val rand = dp(8)
        val fenster = Rect(ort[0] - rand, ort[1] - rand, ort[0] + wurzel.width + rand, ort[1] + wurzel.height + rand)
        if (!Rect.intersects(fenster, bereich)) return false
        fuerGesteVersteckt = true
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(wurzel, params) }
        return true
    }

    /** After the gesture: button touchable again (unless it is put away completely right now, see [sichtbarkeit]). */
    fun nachGesteZeigen() {
        if (!fuerGesteVersteckt) return
        fuerGesteVersteckt = false
        if (!gezeigt || versteckt) return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(wurzel, params) }
    }

    /** If the panel is open, make it briefly invisible for the screenshot. true = was hidden. */
    fun fuerFotoVerstecken(): Boolean {
        if (versteckt || feld.visibility != View.VISIBLE) return false
        feld.visibility = View.INVISIBLE
        fuerFotoVersteckt = true
        return true
    }

    fun nachFotoZeigen() {
        if (!fuerFotoVersteckt) return
        fuerFotoVersteckt = false
        if (versteckt) return
        feld.visibility = View.VISIBLE
    }

    /** Keep the screen on and dim it if wanted, while the bot is running. */
    fun bildschirm(anlassen: Boolean, dunkel: Boolean) {
        wurzel.keepScreenOn = anlassen
        params.screenBrightness = if (dunkel) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (gezeigt) runCatching { wm.updateViewLayout(wurzel, params) }
    }

    private fun aktualisiere() {
        val lauf = BotZustand.lauf
        val laeuft = lauf == BotZustand.Lauf.LAEUFT || lauf == BotZustand.Lauf.PAUSE
        // In pocket mode the button shows a backpack, the panel stays closed.
        val tasche = BotZustand.taschenmodusAktiv
        knopf.text = if (tasche) "🎒" else "IH"
        if (tasche && feld.visibility == View.VISIBLE) feld.visibility = View.GONE
        taschenKnopf.text = when {
            tasche -> "🎒  Pocket mode is on"
            BotZustand.taschenmodusGewuenscht -> "🎒  Pocket mode at start: on (tap = off)"
            laeuft -> "🎒  Pocket mode on (lock everything)"
            else -> "🎒  Pocket mode on at start"
        }
        titel.text = when (lauf) {
            BotZustand.Lauf.LAEUFT -> "● Bot is running"
            BotZustand.Lauf.PAUSE -> "● Bot is paused"
            BotZustand.Lauf.GESTOPPT -> "● Bot is off"
            BotZustand.Lauf.BEREIT -> "● Bot is ready"
        }
        titel.setTextColor(
            when (lauf) {
                BotZustand.Lauf.LAEUFT -> 0xFF81C784.toInt()
                BotZustand.Lauf.PAUSE -> 0xFFFFD54F.toInt()
                else -> 0xFFE0E0E0.toInt()
            },
        )
        status.text = BotZustand.klarText()
        meldung.text = BotZustand.letzteMeldung
        meldung.visibility = if (BotZustand.letzteMeldung.isBlank()) View.GONE else View.VISIBLE
        val hinweis = Variante.panelHinweis()
        zusatzHinweis.text = hinweis ?: ""
        zusatzHinweis.visibility = if (hinweis != null && !laeuft) View.VISIBLE else View.GONE
        pauseKnopf.text = if (lauf == BotZustand.Lauf.PAUSE) "▶  Resume" else "❚❚  Pause"
        setzeAn(startKnopf, !laeuft)
        setzeAn(pauseKnopf, laeuft)
        setzeAn(stoppKnopf, laeuft)
        setzeAn(ausblendenKnopf, ausblendenErlaubt)
        val jetzt = System.currentTimeMillis()
        if (!laeuft) ausblendenBestaetigung.abbrechen()
        ausblendenKnopf.text = when {
            ausblendenBestaetigung.wartet(jetzt) -> "Tap again: stop bot + hide"
            laeuft -> "✕  Stop + hide"
            else -> "✕  Hide"
        }
        val ausFragt = ausschaltenBestaetigung.wartet(jetzt)
        ausschaltenKnopf.text = when {
            ausFragt && laeuft -> "Tap again: stop bot + turn off completely"
            ausFragt -> "Tap again: really turn off completely"
            else -> "⏻  Turn IH off completely"
        }
        ausschaltenHinweis.visibility = if (ausFragt) View.VISIBLE else View.GONE
        knopf.background = rund(
            when (lauf) {
                BotZustand.Lauf.LAEUFT -> FARBE_LAEUFT
                BotZustand.Lauf.PAUSE -> FARBE_PAUSE
                else -> FARBE_AUS
            },
        )
        // If the bot is running and the panel is open (and not hidden for the screenshot right now): close it soon.
        val fragt = ausblendenBestaetigung.wartet(jetzt) || ausFragt
        if (lauf == BotZustand.Lauf.LAEUFT && feld.visibility == View.VISIBLE && !zuGeplant && !fragt) {
            zuGeplant = true
            wurzel.postDelayed(autoZu, AUTO_ZU_MS)
        }
    }

    private fun setzeAn(b: Button, an: Boolean) {
        b.isEnabled = an
        b.alpha = if (an) 1f else 0.35f
    }

    private fun reihe(links: View, rechts: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(links, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(0, dp(4), dp(4), 0) })
        addView(rechts, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), 0, 0) })
    }

    private fun grosserKnopf(text: String, farbe: Int, aktion: () -> Unit) = Button(ctx).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        background = GradientDrawable().apply {
            setColor(farbe)
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { aktion() }
    }

    private fun rund(farbe: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(farbe)
        setStroke(dp(2), Color.WHITE)
    }

    private companion object {
        const val FARBE_LAEUFT = 0xE62E7D32.toInt() // green
        const val FARBE_PAUSE = 0xE6F9A825.toInt() // yellow
        const val FARBE_AUS = 0xCC757575.toInt() // grey

        /** The panel stays open this long while the bot is running. */
        const val AUTO_ZU_MS = 8_000L

        /** A button showing "Tap again …" waits this long for the second tap. */
        const val BESTAETIGEN_MS = 5_000L
    }
}
