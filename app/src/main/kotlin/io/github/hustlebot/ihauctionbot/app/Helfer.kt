package io.github.hustlebot.ihauctionbot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.content.pm.PackageManager
import android.os.Build
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.BildschirmLeser
import io.github.hustlebot.ihauctionbot.kern.bild.Ausrichtung
import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import io.github.hustlebot.ihauctionbot.kern.bild.Erkenner
import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck
import io.github.hustlebot.ihauctionbot.kern.bild.ZiffernLeser
import io.github.hustlebot.ihauctionbot.kern.bild.ZweitLeser
import io.github.hustlebot.ihauctionbot.kern.bericht.BerichtNamen
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Reads the newest screenshot with the recognition from the core (auction house, rows, prices, windows).
 * Without a screenshot it reports "unknown" – then the bot does not tap anywhere.
 *
 * [zweit] = second text recognition (Google ML Kit) for numbers the core cannot read. Review round 2 (Nov 23): set for
 * each run from the setting – null = the core reads every number itself.
 * Also: how long does the recognition take? About once a minute a "⏱ Recognition …" line goes into the log.
 */
class SpielLeser : BildschirmLeser {
    /** Second text recognition for this run (null = none). */
    @Volatile var zweit: ZweitLeser? = null

    /** The newest screenshot (already converted to sRGB). Review: only set it through [neuesBild]. */
    @Volatile var bild: Bitmap? = null
        private set

    /**
     * Review (privacy): number of the screenshot in [bild] – and of the screenshot [zuletzt] and [gesehen] come from.
     * If the bot does not read a screenshot at all (e.g. stop time reached), [zuletzt]/[gesehen] still belong to the one
     * before; then the new screenshot must not be saved with their permission (see [frisch]).
     */
    @Volatile var bildNr: Long = 0
        private set
    @Volatile private var zuletztNr: Long = -1

    /** New screenshot for the next step. Returns its number (only call from the image thread). */
    fun neuesBild(neu: Bitmap?): Long {
        bild = neu
        bildNr++
        return bildNr
    }

    /** Does [zuletzt] come from the screenshot with number [nr]? */
    fun frisch(nr: Long): Boolean = nr >= 0 && zuletztNr == nr

    /** What was recognised last (for the log and the display). */
    @Volatile var zuletzt: Bildschirm? = null

    /** Exactly the image the recognition saw last (after rotating) – for the diagnostic image. */
    @Volatile var gesehen: Bild? = null

    /** By how many degrees the screenshot had to be rotated (0 = landscape as expected). */
    @Volatile var drehung: Int = 0

    // ---------- Timing and second text recognition ----------

    /** How long the last recognition took (ms). */
    @Volatile var letzteErkennungMs: Long = 0
        private set
    private var summeMs = 0L
    private var maxMs = 0L
    private var anzahl = 0
    private var zweitGefragt = 0
    private var trefferVorher = 0L
    private var neuVorher = 0L
    private var letzteZeitZeile = System.currentTimeMillis()

    /** What the second text recognition read last (only new things go into the log). */
    private val gemeldeteNotizen = ArrayDeque<String>()

    private val zaehlenderZweit = ZweitLeser { b: Bild, r: Rechteck ->
        zweit?.let { z ->
            zweitGefragt++
            z.lies(b, r)
        }
    }

    private fun notiz(text: String) {
        if (text in gemeldeteNotizen) return
        gemeldeteNotizen.addLast(text)
        while (gemeldeteNotizen.size > 40) gemeldeteNotizen.removeFirst()
        BotZustand.log(text)
    }

    override fun lies(): Bildschirm {
        val nr = bildNr
        val bm = bild ?: return Bildschirm.UNBEKANNT
        val start = System.nanoTime()
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        // Without a second reader the core reads every number itself.
        val e = Ausrichtung.erkenne(Bild(bm.width, bm.height, px), drehung, zaehlenderZweit.takeIf { zweit != null }, ::notiz)
        if (e.drehung != drehung) BotZustand.log("Screenshot comes in portrait – rotating it by ${e.drehung}°")
        drehung = e.drehung
        gesehen = e.bild
        zuletzt = e.bildschirm
        zuletztNr = nr
        zeitMerken((System.nanoTime() - start) / 1_000_000)
        return e.bildschirm
    }

    private fun zeitMerken(ms: Long) {
        letzteErkennungMs = ms
        summeMs += ms
        maxMs = maxOf(maxMs, ms)
        anzahl++
        val jetzt = System.currentTimeMillis()
        if (jetzt - letzteZeitZeile < 60_000) return
        val treffer = ZiffernLeser.treffer - trefferVorher
        val neu = ZiffernLeser.neuGelesen - neuVorher
        val anteil = if (treffer + neu > 0) treffer * 100 / (treffer + neu) else 0
        BotZustand.log(
            "⏱ Recognition: avg ${summeMs / anzahl} ms, longest $maxMs ms ($anzahl screenshots) · " +
                "numbers from the cache: $anteil %" +
                (if (zweit != null) " · second text recognition asked $zweitGefragt times" else ""),
        )
        letzteZeitZeile = jetzt
        summeMs = 0
        maxMs = 0
        anzahl = 0
        zweitGefragt = 0
        trefferVorher = ZiffernLeser.treffer
        neuVorher = ZiffernLeser.neuGelesen
    }
}

/** Facts about the installed app. */
object AppInfo {
    /** Version of the installed app, e.g. "1.42" ("?" if unknown). */
    fun version(ctx: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }
    }.getOrNull() ?: "?"
}

/** Convert all screenshots to sRGB (some phones deliver Display P3 – the recognition knows sRGB colours). */
fun inSrgb(quelle: Bitmap): Bitmap {
    val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    if (quelle.colorSpace == srgb && quelle.config == Bitmap.Config.ARGB_8888) return quelle
    val ziel = Bitmap.createBitmap(quelle.width, quelle.height, Bitmap.Config.ARGB_8888, true, srgb)
    Canvas(ziel).drawBitmap(quelle, 0f, 0f, null)
    return ziel
}

/**
 * Saves screenshots the bot was unsure about (diagnostics at the start, nothing recognised for 10 s, unsure, an unknown
 * window after "Buyout Price").
 *
 * Review before publishing (Nov 22): no longer in the public gallery (Pictures/IdleHeroesBot) – only inside the app
 * (noBackupFilesDir/bilder: not in the backup, no other app can read it), at most [MAX_DATEIEN] files and [MAX_BYTES].
 * The newest of a run go into the next report (folder gespeichert/, see [BerichtSender]). They are deleted after
 * 14 days and with "Delete reports, log and screenshots" in the settings. The caller only passes screenshots that may
 * be kept (game alone in front, never the password window).
 */
object BildSpeicher {
    private const val ORDNER = "bilder"
    private const val ABSTAND_MS = 30_000L
    private const val MAX_DATEIEN = 12
    private const val MAX_BYTES = 40L * 1024 * 1024
    private val arbeiter = Executors.newSingleThreadExecutor()
    @Volatile private var zuletzt = 0L

    private fun ordner(ctx: Context): File = File(ctx.applicationContext.noBackupFilesDir, ORDNER)

    /**
     * Saves [bild] in the app. Review round 3 (Nov 23): [schwarz] = what is blacked out first (outside the game, other
     * apps' overlays – see [Schwaerzen]); [bild] itself is not changed.
     */
    fun speichereUnsicher(
        ctx: Context,
        bild: Bitmap,
        grund: String,
        sofort: Boolean = false,
        vorsilbe: String = "unsicher",
        schwarz: List<Rechteck> = emptyList(),
    ) {
        val jetzt = System.currentTimeMillis()
        if (!sofort && jetzt - zuletzt < ABSTAND_MS) return
        zuletzt = jetzt
        val app = ctx.applicationContext
        arbeiter.execute {
            var aus: Bitmap? = null
            try {
                aus = Schwaerzen.bitmap(bild, schwarz) ?: return@execute
                val dir = ordner(app).apply { mkdirs() }
                val name = BerichtNamen.gespeichertesBild(vorsilbe, LocalDateTime.now())
                File(dir, name).outputStream().buffered().use { aus.compress(Bitmap.CompressFormat.PNG, 100, it) }
                begrenzen(dir)
                BotZustand.log("Screenshot saved in the app: $name ($grund) – it goes into the next report")
            } catch (e: Exception) {
                BotZustand.log("Saving the screenshot failed: ${e.message}")
            } finally {
                if (aus != null && aus !== bild) aus.recycle()
            }
        }
    }

    /** Only the newest [MAX_DATEIEN] files, together at most [MAX_BYTES] (only in the worker thread). */
    private fun begrenzen(dir: File) {
        val dateien = dateien(dir).sortedByDescending { it.lastModified() }
        var summe = 0L
        dateien.forEachIndexed { i, f ->
            summe += f.length()
            if (i >= MAX_DATEIEN || summe > MAX_BYTES) f.delete()
        }
    }

    private fun dateien(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && BerichtNamen.istGespeichertesBild(it.name) }

    /**
     * For a report: the newest saved screenshots since [seitMs] (System.currentTimeMillis), at most [anzahl]. Waits
     * briefly for a screenshot that is being written right now.
     */
    fun neueste(ctx: Context, seitMs: Long, anzahl: Int): List<File> {
        runCatching { arbeiter.submit {}.get(5, TimeUnit.SECONDS) }
        return dateien(ordner(ctx)).filter { it.lastModified() >= seitMs }.sortedByDescending { it.lastModified() }.take(anzahl)
    }

    /** Delete saved screenshots older than [vorMs] (System.currentTimeMillis); 0 = all. */
    fun loeschen(ctx: Context, vorMs: Long = 0L) {
        val app = ctx.applicationContext
        runCatching {
            arbeiter.submit {
                dateien(ordner(app)).filter { vorMs <= 0L || it.lastModified() < vorMs }.forEach { it.delete() }
                Unit
            }.get(10, TimeUnit.SECONDS)
        }
    }
}

/**
 * Review round 3 (Nov 23): before a screenshot is SAVED (report, flight recorder, saved screenshots, learning images),
 * everything outside the game and every other window over it (another app's chat head, bubble or caller-ID strip, the
 * status bar) is painted black – the rectangles come from the window list
 * ([io.github.hustlebot.ihauctionbot.kern.SpielVorne.schwaerzen]). Only the saved copy: what the bot reads is not changed.
 */
object Schwaerzen {

    /**
     * A copy of [bild] with [rechtecke] black ([faktor] = size of [bild] compared to the screenshot the rectangles belong
     * to, e.g. 0.5 for the half size in reports). Nothing to black out → [bild] itself. null = no copy possible (memory)
     * → do not save.
     */
    fun bitmap(bild: Bitmap, rechtecke: List<Rechteck>, faktor: Float = 1f): Bitmap? {
        if (rechtecke.isEmpty()) return bild
        val kopie = runCatching { bild.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return null
        val c = Canvas(kopie)
        val farbe = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        for (r in rechtecke) {
            // Rounded outwards: never a pixel of the window left over at the edge.
            c.drawRect(
                floor(r.x0 * faktor),
                floor(r.y0 * faktor),
                ceil(r.x1 * faktor),
                ceil(r.y1 * faktor),
                farbe,
            )
        }
        return kopie
    }

    /** Does the area [x0, y0, x1, y1) touch one of [rechtecke]? (A learning image there is not kept.) */
    fun trifft(x0: Int, y0: Int, x1: Int, y1: Int, rechtecke: List<Rechteck>): Boolean =
        rechtecke.any { it.x0 < x1 && x0 < it.x1 && it.y0 < y1 && y0 < it.y1 }
}
