package io.github.hustlebot.ihauctionbot.attrappe.app

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import io.github.hustlebot.ihauctionbot.attrappe.Atlas
import io.github.hustlebot.ihauctionbot.attrappe.Bilder
import io.github.hustlebot.ihauctionbot.attrappe.Bildbauer
import io.github.hustlebot.ihauctionbot.attrappe.Spiel
import io.github.hustlebot.ihauctionbot.attrappe.SpielEinstellung
import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import kotlin.math.abs

/** The real test images from the assets (JPG) as a [Bild]. */
class AssetBilder(private val assets: AssetManager) : Bilder {
    override fun lade(name: String): Bild {
        val datei = if ('.' in name) name else "$name.jpg"
        val bmp = assets.open(datei).use { BitmapFactory.decodeStream(it) } ?: error("Image $name missing")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val b = Bild(bmp.width, bmp.height, px)
        bmp.recycle()
        return b
    }
}

/**
 * Nov 11: fake game in the emulator – shows the images of the [Spiel] fake game full screen (landscape) and passes
 * taps/swipes on to it. The price fields in the filter are real input fields (so that the bot's text input
 * is really tested). Everything that happens is written to files/attrappe.log (adb: run-as … cat files/attrappe.log).
 *
 * Start (adb): am start -n io.github.hustlebot.ihauctionbot.attrappe/io.github.hustlebot.ihauctionbot.attrappe.app.SpielActivity
 *   --ei limit 12 --ei start 9 --ez kreuzfremd false
 */
class SpielActivity : Activity() {

    private val haupt = Handler(Looper.getMainLooper())
    private val arbeiter = Executors.newSingleThreadExecutor()
    private var spiel: Spiel? = null
    private lateinit var ansicht: SpielAnsicht
    /** Real input fields: 0/1 = price fields in the filter, 2 = secondary password. */
    private val felder = arrayOfNulls<EditText>(3)
    private var log: FileWriter? = null
    private var letzteAnsicht: Spiel.Ansicht? = null
    private var setztText = false

    private val malen = object : Runnable {
        override fun run() {
            neuMalen()
            haupt.postDelayed(this, MAL_ABSTAND_MS)
        }
    }

    private val stand = object : Runnable {
        override fun run() {
            spiel?.let { s ->
                val t = System.currentTimeMillis()
                runCatching {
                    File(filesDir, "stand.txt").writeText(
                        "gebote=${s.gebote}\nnachgebote=${s.nachgebote}\nweggedrueckt=${s.weggedrueckt}\nlimit7=${s.limitMeldungen}\n" +
                            "verboten=${s.verboten}\ngewonnen=${s.gewonnen}\neintraege=${s.eintraege(t)}\nansicht=${s.ansicht}\n",
                    )
                }
            }
            haupt.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Draw up to all edges (like the game) – otherwise the image does not match the bot's screenshot.
        window.setDecorFitsSystemWindows(false)
        val einst = SpielEinstellung(
            limit = intent.getIntExtra("limit", 50),
            startEintraege = intent.getIntExtra("start", 0),
            ueberbietenAlleMs = intent.getLongExtra("ueberbieten", 25_000L),
            ladenJedesNteGebot = intent.getIntExtra("laden", 5),
            kreuzOeffnetFenster = intent.getBooleanExtra("kreuzfremd", false),
            passwort = intent.getStringExtra("passwort"),
            bestaetigenJedesNte = intent.getIntExtra("bestaetigen", 0),
        )
        val datei = File(filesDir, "attrappe.log")
        datei.delete()
        log = FileWriter(datei, true)
        schreibe("START fake game $einst")

        val wurzel = FrameLayout(this)
        wurzel.setBackgroundColor(Color.BLACK)
        ansicht = SpielAnsicht(this)
        ansicht.groesseGemeldet = { schreibe(it) }
        wurzel.addView(ansicht, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        for (i in 0..2) {
            val feld = EditText(this).apply {
                inputType = if (i == 2) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                setBackgroundColor(Color.WHITE)
                setTextColor(0xFF3A2A20.toInt())
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                letterSpacing = 0.2f
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f)
                setPadding(12, 0, 4, 0)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                visibility = View.GONE
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (!setztText) {
                            spiel?.textSetzen(if (i == 2) Spiel.FELD_PASSWORT else i, s?.toString() ?: "", System.currentTimeMillis())
                        }
                    }
                })
            }
            felder[i] = feld
            wurzel.addView(feld, FrameLayout.LayoutParams(10, 10))
        }
        setContentView(wurzel)
        vollbild()

        // Load the pieces from the real screenshots (takes a few seconds).
        arbeiter.execute {
            try {
                val bilder = AssetBilder(assets)
                val atlas = Atlas(bilder)
                val fremd = bilder.lade("transaktionen")
                haupt.post {
                    val s = Spiel(Bildbauer(atlas), einst, System.currentTimeMillis(), fremd)
                    s.protokoll = { schreibe(it) }
                    spiel = s
                    schreibe("BEREIT")
                    neuMalen()
                }
            } catch (e: Throwable) {
                schreibe("FEHLER while loading: $e")
                Log.e(TAG, "Loading", e)
            }
        }
        haupt.post(malen)
        haupt.post(stand)
    }

    private fun vollbild() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) vollbild()
    }

    /**
     * "Back" never closes the fake game (the game does not simply do that either). The test only presses "Back" to remove a
     * "System UI isn't responding" window of the slow emulator – if it hits the fake game, it shows up in the log.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            schreibe("ZURUECK (nothing happens)")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        haupt.removeCallbacksAndMessages(null)
        runCatching { log?.close() }
        super.onDestroy()
    }

    private fun neuMalen() {
        val s = spiel ?: return
        val jetzt = System.currentTimeMillis()
        val b = try {
            s.bild(jetzt, filterSelbstMalen = false)
        } catch (e: Throwable) {
            schreibe("FEHLER while drawing: $e")
            return
        }
        ansicht.zeige(b)
        // Price fields only in the filter window, password field only in the secondary password window (at that spot in the image).
        val laedt = s.laedt(jetzt)
        val imFilter = s.ansicht == Spiel.Ansicht.FILTER && !laedt
        val imPasswort = s.ansicht == Spiel.Ansicht.PASSWORT && !laedt
        if (s.ansicht != letzteAnsicht) {
            setztText = true
            when (s.ansicht) {
                Spiel.Ansicht.FILTER -> {
                    felder[0]?.setText(s.filterText[0])
                    felder[1]?.setText(s.filterText[1])
                }
                Spiel.Ansicht.PASSWORT -> felder[2]?.setText("")
                else -> felder.forEach { it?.clearFocus() }
            }
            setztText = false
            letzteAnsicht = s.ansicht
        }
        listOf(Bildbauer.FILTER_MIN, Bildbauer.FILTER_MAX, Bildbauer.PASSWORT_FELD).forEachIndexed { i, r ->
            val f = felder[i] ?: return@forEachIndexed
            val sichtbar = if (i == 2) imPasswort else imFilter
            f.visibility = if (sichtbar) View.VISIBLE else View.GONE
            if (sichtbar && ansicht.width > 0) {
                val lp = f.layoutParams as FrameLayout.LayoutParams
                val (dw, dh) = ansicht.bildschirm()
                val ort = ansicht.ortAufDemBildschirm()
                val x0 = r.x0 * dw / 2712 - ort[0]
                val y0 = r.y0 * dh / 1220 - ort[1]
                val w = (r.x1 - r.x0) * dw / 2712
                val h = (r.y1 - r.y0) * dh / 1220
                if (lp.leftMargin != x0 || lp.topMargin != y0 || lp.width != w || lp.height != h) {
                    lp.leftMargin = x0
                    lp.topMargin = y0
                    lp.width = w
                    lp.height = h
                    f.layoutParams = lp
                }
            }
        }
    }

    /** Tap or swipe (reference pixels) to the fake game. */
    fun eingabe(x0: Int, y0: Int, x1: Int, y1: Int) {
        val s = spiel ?: return
        val jetzt = System.currentTimeMillis()
        if (abs(x1 - x0) < WISCH_MIN && abs(y1 - y0) < WISCH_MIN) s.tippen(x0, y0, jetzt) else s.wischen(y0, y1, jetzt)
        neuMalen()
    }

    private fun schreibe(text: String) {
        Log.i(TAG, text)
        runCatching {
            log?.write(if (text.firstOrNull()?.isDigit() == true) "$text\n" else "${System.currentTimeMillis()} $text\n")
            log?.flush()
        }
    }

    /**
     * Shows the image over the WHOLE screen and reports touches (in reference pixels 2712 × 1220). The calculation
     * uses the screen, not the size of the view: if Android leaves room e.g. for the navigation bar,
     * image and tap positions still match the bot's screenshot (bug in the first emulator run).
     */
    class SpielAnsicht(ctx: Context) : View(ctx) {
        private var bitmap: Bitmap? = null
        private val pinsel = Paint(Paint.FILTER_BITMAP_FLAG)
        private var startX = 0
        private var startY = 0
        private val ort = IntArray(2)
        var groesseGemeldet: ((String) -> Unit)? = null

        /** Width × height of the whole screen (as in the screenshot). */
        fun bildschirm(): Pair<Int, Int> {
            val r = (context as Activity).windowManager.maximumWindowMetrics.bounds
            return r.width().coerceAtLeast(1) to r.height().coerceAtLeast(1)
        }

        fun ortAufDemBildschirm(): IntArray {
            getLocationOnScreen(ort)
            return ort
        }

        override fun onSizeChanged(w: Int, h: Int, altW: Int, altH: Int) {
            super.onSizeChanged(w, h, altW, altH)
            val (dw, dh) = bildschirm()
            post { groesseGemeldet?.invoke("GROESSE view ${w}x$h at ${ortAufDemBildschirm().joinToString(",")}, screen ${dw}x$dh") }
        }

        fun zeige(b: Bild) {
            val bm = bitmap?.takeIf { it.width == b.breite && it.height == b.hoehe }
                ?: Bitmap.createBitmap(b.breite, b.hoehe, Bitmap.Config.ARGB_8888).also { bitmap = it }
            bm.setPixels(b.pixel, 0, b.breite, 0, 0, b.breite, b.hoehe)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bm = bitmap ?: return
            val (dw, dh) = bildschirm()
            val o = ortAufDemBildschirm()
            canvas.drawBitmap(bm, Rect(0, 0, bm.width, bm.height), Rect(-o[0], -o[1], dw - o[0], dh - o[1]), pinsel)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val (dw, dh) = bildschirm()
            val x = (e.rawX * 2712 / dw).toInt()
            val y = (e.rawY * 1220 / dh).toInt()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = x
                    startY = y
                }
                MotionEvent.ACTION_UP -> (context as? SpielActivity)?.eingabe(startX, startY, x, y)
            }
            return true
        }
    }

    companion object {
        const val TAG = "Attrappe"
        const val MAL_ABSTAND_MS = 300L
        const val WISCH_MIN = 30
    }
}
