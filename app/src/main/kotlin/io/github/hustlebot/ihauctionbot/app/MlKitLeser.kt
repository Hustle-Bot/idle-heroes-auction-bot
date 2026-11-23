package io.github.hustlebot.ihauctionbot.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck
import io.github.hustlebot.ihauctionbot.kern.bild.ZweitLeser
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Second opinion with Google ML Kit – only for numbers the bot's own digit reader cannot read
 * (the core decides which: [io.github.hustlebot.ihauctionbot.kern.bild.Nachlesen]).
 *
 * ML Kit comes through Google Play services (not built into the app – the app stays small). If it is not there (yet),
 * this is logged ONCE and the bot keeps reading on its own; it is tried again after 5 minutes.
 * Runs in the bot's image thread (never in the main thread) and waits at most [ZEIT_MS] for an answer.
 *
 * Review round 2 (Nov 23): only used while the setting "Google text recognition" is on ([Einstellungen.mlKit]) – BotDienst
 * creates this object only then, and Google's client is only set up on the first number it is asked for. Off, nothing of
 * ML Kit is set up; the bot reads every number itself.
 */
class MlKitLeser : ZweitLeser {

    companion object {
        /** Wait at most this long for an answer. */
        const val ZEIT_MS = 400L

        /** After an error (not installed) do not ask again for this long. */
        private const val PAUSE_NACH_FEHLER_MS = 5 * 60_000L

        /** ML Kit needs text at least ~16 pixels high – small crops are scaled up. */
        private const val MIN_HOEHE = 64
    }

    private val erkennerFaul: Lazy<TextRecognizer?> = lazy {
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (e: Throwable) {
            nichtDa(e)
            null
        }
    }
    private val erkenner: TextRecognizer? by erkennerFaul

    /** The setting was turned off: close Google's client (if it was set up at all). This object is not used again. */
    fun schliessen() {
        if (erkennerFaul.isInitialized()) runCatching { erkennerFaul.value?.close() }
    }

    @Volatile private var gesperrtBis = 0L
    @Volatile private var gemeldet = false
    @Volatile private var gingSchon = false

    private fun nichtDa(e: Throwable) {
        gesperrtBis = System.currentTimeMillis() + PAUSE_NACH_FEHLER_MS
        if (!gemeldet) {
            gemeldet = true
            BotZustand.log("Second text recognition (Google ML Kit) not available – the bot keeps reading on its own (${e.javaClass.simpleName})")
        }
    }

    override fun lies(bild: Bild, bereich: Rechteck): String? {
        if (System.currentTimeMillis() < gesperrtBis) return null
        val e = erkenner ?: return null
        val x0 = bereich.x0.coerceIn(0, bild.breite)
        val y0 = bereich.y0.coerceIn(0, bild.hoehe)
        val b = bereich.x1.coerceIn(0, bild.breite) - x0
        val h = bereich.y1.coerceIn(0, bild.hoehe) - y0
        if (b <= 0 || h <= 0) return null
        var ausschnitt: Bitmap? = null
        var gross: Bitmap? = null
        return try {
            val px = IntArray(b * h)
            for (y in 0 until h) {
                val q = (y0 + y) * bild.breite + x0
                for (x in 0 until b) px[y * b + x] = bild.pixel[q + x] or 0xFF000000.toInt()
            }
            ausschnitt = Bitmap.createBitmap(px, b, h, Bitmap.Config.ARGB_8888)
            val faktor = if (h < MIN_HOEHE) (MIN_HOEHE + h - 1) / h else 1
            gross = if (faktor > 1) Bitmap.createScaledBitmap(ausschnitt, b * faktor, h * faktor, true) else ausschnitt
            val text = Tasks.await(e.process(InputImage.fromBitmap(gross, 0)), ZEIT_MS, TimeUnit.MILLISECONDS)
            if (!gingSchon) {
                gingSchon = true
                BotZustand.log("Second text recognition (Google ML Kit) is ready")
            }
            text.text.trim().takeIf { it.isNotEmpty() }
        } catch (t: TimeoutException) {
            null // too slow – this field stays "not readable"
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (t: Exception) {
            nichtDa(t)
            null
        } finally {
            if (gross != null && gross !== ausschnitt) gross.recycle()
            ausschnitt?.recycle()
        }
    }
}
