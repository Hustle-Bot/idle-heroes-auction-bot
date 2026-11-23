package io.github.hustlebot.ihauctionbot.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.BuyoutFreigabe
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.bericht.BerichtJson
import io.github.hustlebot.ihauctionbot.kern.bericht.BerichtNamen
import io.github.hustlebot.ihauctionbot.kern.bericht.BerichtText
import io.github.hustlebot.ihauctionbot.kern.bericht.BildPuffer
import io.github.hustlebot.ihauctionbot.kern.bericht.FlugEintrag
import io.github.hustlebot.ihauctionbot.kern.bericht.FlugEreignisBremse
import io.github.hustlebot.ihauctionbot.kern.bericht.FlugschreiberNamen
import io.github.hustlebot.ihauctionbot.kern.bericht.FlugschreiberRegeln
import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Error reports.
 *
 * During the run it remembers the last [BerichtNamen.MAX_BILDER] screenshots (half resolution) with what the
 * recognition found in them – NEVER screenshots with the secondary password window or shortly after the password input.
 * A report = info.txt (version, settings without secrets) + protokoll.txt (since the start) + the screenshots.
 * It is written into the app's private storage (not backed up, at most [MAX_LOKAL] reports). Then it is handed to
 * [Variante.berichtAbliefern] (it stays on the phone until the player shares it himself).
 *
 * Created: by hand (IH button / settings), automatically at the end of every run and when the bot is stuck
 * on the same screen for 45 seconds (at most every 10 minutes).
 *
 * Flight recorder ("see every run afterwards"): every analysed screenshot of the last 3 minutes
 * stays (half size, JPEG) in a ring buffer in the app's cache, plus one line per screenshot (what the bot saw and
 * did, see [FlugschreiberRegeln]). It goes into every report: as flugschreiber.csv and the screenshots
 * (at most 25 MB, newest screenshots first). Same privacy rules as the report screenshots.
 * Report because of special events ([flugEreignis]) at most every 10 minutes, at the end of a run always
 * ([sende] with immer = true).
 *
 * Review before publishing (Nov 22), keeping data short: the flight recorder's screenshots in the cache are deleted
 * as soon as the report at the end of the run is built; reports and saved screenshots older than [AUFBEWAHREN_MS]
 * are deleted; "Delete reports, log and screenshots" in the settings removes everything at once ([allesLoeschen]).
 * Only screenshots that may be kept get here at all (game alone in front, see BotDienst).
 * Review round 3 (Nov 23): the log keeps no line older than 14 days ([BotZustand.protokollAufraeumen]); the flight
 * recorder's screenshots left behind by a crash are deleted ([resteLoeschen]); saved screenshots are black outside the
 * game and under other windows ([Schwaerzen]).
 */
object BerichtSender {

    internal class Merkbild(val zeit: LocalTime, val jpeg: ByteArray, val json: String)

    /** Field test 1.9: crop of an unreadable number in full resolution (for lernziffern/). */
    private class Lernbild(val name: String, val png: ByteArray)

    internal val puffer = BildPuffer<Merkbild>(BerichtNamen.MAX_BILDER)
    private val lernPuffer = BildPuffer<Lernbild>(BerichtNamen.MAX_LERNBILDER)

    /** Which places (field + context) already have a learning image in this run – only one per place. */
    private val lernStellen = HashSet<String>()

    /** At most this many learning images from one screenshot (the crops are small, but the screenshot should move on quickly). */
    private const val LERNBILDER_JE_FOTO = 6
    private val verkleinerer = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()

    /** Flight recorder: this many screenshots may wait to be packed – more: skip the screenshot (never slow down). */
    private const val MAX_WARTEND = 3
    private val wartend = AtomicInteger(0)

    /** Reports being sent right now or waiting for it (automatic ones only when none is on its way). */
    private val offen = AtomicInteger(0)

    // ---------------- Flight recorder ----------------

    /** Subfolder in the report (on the phone) with the flight recorder's screenshots. */
    internal const val FLUG_UNTERORDNER = "flugschreiber"
    private const val JPEG_QUALITAET = 70

    @Volatile private var flugAn = false
    @Volatile private var flugOrdner: File? = null
    @Volatile private var laufStartMs = 0L

    /** The lines in the ring (old → new); their screenshots in [flugOrdner]. Only with synchronized(flugRing). */
    private val flugRing = ArrayList<FlugEintrag>()
    private val flugBremse = FlugEreignisBremse()

    /** Length of the log file at the start of the run – the report contains the log from here on. */
    @Volatile private var protokollStart = 0L

    /** At most this many reports stay on the phone. */
    private const val MAX_LOKAL = 5

    /** Reports and saved screenshots older than this are deleted (14 days). */
    const val AUFBEWAHREN_MS = 14L * 24 * 60 * 60 * 1000

    /** Folder in the cache with a report ZIP prepared for sharing (see BerichtTeilen). */
    const val GETEILT_ORDNER = "geteilt"

    /** At most this many screenshots the bot saved itself ([BildSpeicher]) go into a report. */
    private const val MAX_GESPEICHERT = 3

    /** Counts the runs – the flight recorder of a run is only deleted if no new run has started in the meantime. */
    private val laufNr = AtomicInteger(0)

    /**
     * A run begins. [flugschreiber] = setting "Flight recorder" (the ring is emptied). The flight recorder only records
     * when reports are switched on ([Variante.berichteAn]). Then the hook [Variante.laufBeginnt] is called.
     */
    fun laufBeginnt(ctx: Context, flugschreiber: Boolean) {
        val ordner = File(ctx.applicationContext.cacheDir, FLUG_UNTERORDNER)
        val flug = flugschreiber && Variante.berichteAn(ctx)
        laufNr.incrementAndGet()
        laufStartMs = System.currentTimeMillis()
        flugOrdner = ordner
        flugAn = flug
        verkleinerer.execute {
            synchronized(flugRing) {
                flugRing.clear()
                runCatching { ordner.deleteRecursively() }
                if (flug) ordner.mkdirs()
            }
        }
        puffer.leeren()
        lernPuffer.leeren()
        synchronized(lernStellen) { lernStellen.clear() }
        protokollStart = BotZustand.protokollLaenge()
        Variante.laufBeginnt(ctx.applicationContext, protokollStart)
    }

    /**
     * Field test 1.9: remember numbers that were there but not readable as small crops in FULL resolution –
     * from exactly the image the recognition saw ([gesehen], already rotated and in sRGB).
     * [erwartetesGebot]: what the bot wants to bid right now – goes into the file name for "Current Bid" (bid − 1) and
     * for the amount in the confirm window, so that when learning it is clear which number should be there.
     */
    private fun merkeLernbilder(gesehen: Bild, schirm: Bildschirm, erwartetesGebot: Int?, schwarz: List<Rechteck>) {
        var n = 0
        for (u in schirm.unlesbar) {
            if (n >= LERNBILDER_JE_FOTO) break
            val x0 = u.x0.coerceIn(0, gesehen.breite - 1)
            val y0 = u.y0.coerceIn(0, gesehen.hoehe - 1)
            val b = (u.x1.coerceIn(0, gesehen.breite) - x0)
            val h = (u.y1.coerceIn(0, gesehen.hoehe) - y0)
            if (b <= 0 || h <= 0) continue
            // Review round 3 (Nov 23): a number under another window (e.g. a chat head) is not kept as a learning image.
            if (Schwaerzen.trifft(x0, y0, x0 + b, y0 + h, schwarz)) continue
            val neu = synchronized(lernStellen) { lernStellen.add(u.feld + "|" + u.kontext) }
            if (!neu) continue
            try {
                val ausschnitt = Bitmap.createBitmap(gesehen.pixel, y0 * gesehen.breite + x0, gesehen.breite, b, h, Bitmap.Config.ARGB_8888)
                val aus = ByteArrayOutputStream()
                ausschnitt.compress(Bitmap.CompressFormat.PNG, 100, aus)
                ausschnitt.recycle()
                val erwartet = when (u.feld) {
                    "aktuellesGebot" -> erwartetesGebot?.minus(1)
                    "bestaetigen" -> erwartetesGebot
                    else -> null
                }
                lernPuffer.dazu(Lernbild(BerichtNamen.lernBild(LocalTime.now(), u.feld, u.kontext, erwartet?.toString()), aus.toByteArray()))
                n++
            } catch (e: Exception) {
                // A missing learning image is no problem.
            }
        }
    }

    /**
     * Remember an analysed screenshot for the report and the flight recorder. Runs in the image thread (right after the
     * recognition); scaling down is fast, JPEG packing runs in its own thread – if [MAX_WARTEND] screenshots are already
     * waiting there, this one is skipped (the line in the flight recorder stays).
     * [schirm] = what the recognition saw in EXACTLY this screenshot (null = not read), [ergebnis] = what the bot then
     * wanted. [erlaubt] = false (secondary password, shortly after the input, not read): no screenshot, only the line.
     * Review round 3 (Nov 23): [schwarz] = what is blacked out in the saved copy (outside the game, other windows over
     * it – in pixels of [bild]; [gesehen] is only given if the same rectangles fit it).
     */
    fun merke(
        bild: Bitmap?,
        schirm: Bildschirm?,
        erlaubt: Boolean,
        botTat: String?,
        gesehen: Bild? = null,
        erwartetesGebot: Int? = null,
        ergebnis: Ergebnis? = null,
        schwarz: List<Rechteck> = emptyList(),
    ) {
        val flug = flugAn && flugOrdner != null
        val zeitMs = (System.currentTimeMillis() - laufStartMs).coerceAtLeast(0L)
        if (!erlaubt || bild == null || schirm == null) {
            if (flug) verkleinerer.execute { flugDazu(FlugschreiberRegeln.zeile(zeitMs, null, 0, schirm, ergebnis)) }
            return
        }
        if (gesehen != null && schirm.unlesbar.isNotEmpty()) merkeLernbilder(gesehen, schirm, erwartetesGebot, schwarz)
        if (wartend.incrementAndGet() > MAX_WARTEND) {
            wartend.decrementAndGet()
            if (flug) verkleinerer.execute { flugDazu(FlugschreiberRegeln.zeile(zeitMs, null, 0, schirm, ergebnis)) }
            return
        }
        val klein = try {
            val halb = Bitmap.createScaledBitmap(bild, (bild.width / 2).coerceAtLeast(1), (bild.height / 2).coerceAtLeast(1), true)
            // Review round 3 (Nov 23): black out in the small copy (cheaper); no copy possible → no screenshot.
            val geschwaerzt = Schwaerzen.bitmap(halb, schwarz, halb.width.toFloat() / bild.width)
            if (geschwaerzt !== halb && halb !== bild) halb.recycle()
            geschwaerzt ?: throw IllegalStateException("no memory to black out")
        } catch (e: Exception) {
            wartend.decrementAndGet()
            if (flug) verkleinerer.execute { flugDazu(FlugschreiberRegeln.zeile(zeitMs, null, 0, schirm, ergebnis)) }
            return
        }
        val zeit = LocalTime.now()
        val json = BerichtJson.bildschirm(schirm, zeit, botTat)
        verkleinerer.execute {
            try {
                val aus = ByteArrayOutputStream()
                klein.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITAET, aus)
                val jpeg = aus.toByteArray()
                puffer.dazu(Merkbild(zeit, jpeg, json))
                if (flug) flugBild(zeitMs, jpeg, json, schirm, ergebnis)
            } catch (e: Exception) {
                // A missing screenshot in the report is no problem.
            } finally {
                if (klein !== bild) klein.recycle()
                wartend.decrementAndGet()
            }
        }
    }

    /** Flight recorder: screenshot + JSON into the ring (only in the packing thread). */
    private fun flugBild(zeitMs: Long, jpeg: ByteArray, json: String, schirm: Bildschirm, ergebnis: Ergebnis?) {
        val ordner = flugOrdner ?: return
        val basis = FlugschreiberNamen.bildBasis(zeitMs)
        val jsonBytes = json.toByteArray()
        val eintrag = try {
            File(ordner, "$basis.jpg").writeBytes(jpeg)
            File(ordner, "$basis.json").writeBytes(jsonBytes)
            FlugschreiberRegeln.zeile(zeitMs, basis, (jpeg.size + jsonBytes.size).toLong(), schirm, ergebnis)
        } catch (e: IOException) {
            FlugschreiberRegeln.zeile(zeitMs, null, 0, schirm, ergebnis)
        }
        flugDazu(eintrag)
    }

    /** Flight recorder: add a line, delete old ones (older than 3 minutes, over 40 MB) with their screenshots (only in the packing thread). */
    private fun flugDazu(eintrag: FlugEintrag) {
        val ordner = flugOrdner ?: return
        if (!flugAn) return
        synchronized(flugRing) {
            flugRing += eintrag
            val bleiben = FlugschreiberRegeln.behalten(flugRing)
            if (bleiben.size == flugRing.size) return
            val bleibenZeiten = bleiben.mapTo(HashSet()) { it.zeitMs }
            for (alt in flugRing) {
                if (alt.zeitMs in bleibenZeiten) continue
                alt.datei?.let { b ->
                    File(ordner, "$b.jpg").delete()
                    File(ordner, "$b.json").delete()
                }
            }
            flugRing.clear()
            flugRing.addAll(bleiben)
        }
    }

    /**
     * Flight recorder: special event ("tap blocked", unknown screen, "-7" …) → report with flight recorder,
     * at most once in 10 minutes (and only if none is on its way right now).
     */
    fun flugEreignis(ctx: Context, grund: String) {
        if (!flugAn) return
        val jetzt = System.currentTimeMillis()
        if (!flugBremse.erlaubt(jetzt)) return
        if (!sende(ctx, "Flight recorder: $grund", automatisch = true, flugKurz = true)) flugBremse.zuruecknehmen(jetzt)
    }

    /**
     * Copy the flight recorder into the report folder: the newest screenshots to flugschreiber/, plus flugschreiber.csv.
     * [kurz] (event, "bot stuck"): only the last minute, at most 8 MB; otherwise 3 minutes, at most 25 MB; and never more
     * than [Variante.flugGrenze] allows – after that only the list. First waits briefly until the packing
     * thread has written all screenshots remembered so far (so that the last screenshot before a stop is included).
     * Result: number of screenshots and their bytes.
     */
    private fun flugKopie(ctx: Context, ordner: File, kurz: Boolean): Pair<Int, Long> {
        val quelle = flugOrdner ?: return 0 to 0L
        if (!flugAn) return 0 to 0L
        runCatching { verkleinerer.submit {}.get(5, TimeUnit.SECONDS) }
        val ziel = File(ordner, FLUG_UNTERORDNER)
        val auswahl = synchronized(flugRing) {
            if (flugRing.isEmpty()) return 0 to 0L
            val grenze = Variante.flugGrenze(ctx)
            ziel.mkdirs()
            FlugschreiberRegeln.fuerBericht(flugRing, kurz, grenze).map { e ->
                val b = e.datei ?: return@map e
                val ok = runCatching {
                    File(quelle, "$b.jpg").copyTo(File(ziel, "$b.jpg"), overwrite = true)
                    File(quelle, "$b.json").copyTo(File(ziel, "$b.json"), overwrite = true)
                }.isSuccess
                if (ok) e else e.copy(datei = null, bytes = 0, grund = e.grund + " (screenshot missing)")
            }
        }
        File(ordner, FlugschreiberNamen.CSV).writeText(FlugschreiberRegeln.csv(auswahl))
        val mitBild = auswahl.filter { it.datei != null }
        return mitBild.size to mitBild.sumOf { it.bytes }
    }

    /**
     * Create a report (in the background) and hand it to [Variante.berichtAbliefern].
     * [automatisch]: quiet if one is already running.
     * Flight recorder: [immer] = even if one is on its way right now – then this one comes afterwards
     * (report at the end of a run). true = the report comes (or is queued).
     * [flugKurz]: report because of an event or "bot stuck": flight recorder only the last minute.
     * [flugDanachLeeren]: report at the end of a run – afterwards the flight recorder's screenshots in the cache are
     * deleted (they are in the report now), unless a new run has started in the meantime.
     * If reports are off ([Variante.berichteAn]), no report is created at all; by hand there is a note in the log.
     */
    fun sende(
        ctx: Context,
        grund: String,
        automatisch: Boolean,
        immer: Boolean = false,
        flugKurz: Boolean = false,
        flugDanachLeeren: Boolean = false,
    ): Boolean {
        if (!Variante.berichteAn(ctx)) {
            if (!automatisch) BotZustand.log(Variante.BERICHTE_AUS_TEXT)
            return false
        }
        if (offen.incrementAndGet() > 1 && !immer) {
            offen.decrementAndGet()
            if (!automatisch) BotZustand.log("A report is already being sent …")
            return false
        }
        val app = ctx.applicationContext
        val stand = BotZustand.klarText().replace('\n', ' ')
        val nr = laufNr.get()
        try {
            sender.execute {
                try {
                    // First let the packing thread finish the screenshots remembered so far – so the last screenshots
                    // before a stop (e.g. an uncertain one) are in the report.
                    runCatching { verkleinerer.submit {}.get(5, TimeUnit.SECONDS) }
                    val bilder = puffer.alle()
                    val lernbilder = lernPuffer.alle()
                    BotZustand.log(
                        "Creating report (${bilder.size} screenshots" + (if (lernbilder.isNotEmpty()) ", ${lernbilder.size} learning images" else "") + ", reason: $grund) …",
                    )
                    // Saved screenshots (full size) not in the short reports (event, "bot stuck") – less data.
                    val ordner = schreibeLokal(app, grund, stand, bilder, lernbilder, mitGespeichert = !flugKurz)
                    val (flugBilder, flugBytes) = runCatching { flugKopie(app, ordner, flugKurz) }.getOrDefault(0 to 0L)
                    if (flugDanachLeeren) flugLeeren(app, nr)
                    // The finished report goes to the hook (here: it stays on the phone).
                    BotZustand.log(Variante.berichtAbliefern(app, ordner, grund, bilder.size + flugBilder, flugBilder, flugBytes))
                } catch (e: Exception) {
                    BotZustand.log("Report failed (${e.javaClass.simpleName})")
                } finally {
                    offen.decrementAndGet()
                }
            }
        } catch (e: Exception) {
            offen.decrementAndGet()
            return false
        }
        return true
    }

    private fun basis(ctx: Context): File = File(ctx.noBackupFilesDir, "berichte")

    /** Writes the report into the app's own storage (not into the backup). */
    private fun schreibeLokal(
        ctx: Context,
        grund: String,
        stand: String,
        bilder: List<Merkbild>,
        lernbilder: List<Lernbild>,
        mitGespeichert: Boolean,
    ): File {
        val basis = basis(ctx).apply { mkdirs() }
        aufraeumen(basis)
        val ordner = File(basis, BerichtNamen.ordner(LocalDateTime.now())).apply { mkdirs() }
        val einst = EinstellungenSpeicher.laden(ctx)
        File(ordner, BerichtNamen.INFO).writeText(
            BerichtText.info(
                zeit = LocalDateTime.now(),
                grund = grund,
                version = AppInfo.version(ctx),
                geraet = "${Build.MANUFACTURER} ${Build.MODEL}",
                android = Build.VERSION.RELEASE ?: "?",
                // Nov 23: no max buyout where the build does not offer buyout (like the bot got them).
                regeln = BuyoutFreigabe.regelnFuerLauf(einst.regeln, Variante.BUYOUT_FREIGEGEBEN),
                zielKopien = einst.zielKopien,
                endeUm = einst.endeUm,
                sgBudget = einst.sgBudget,
                bildschirmAnlassen = einst.bildschirmAnlassen,
                bildschirmDunkel = einst.bildschirmDunkel,
                // Only yes/no – the values themselves never go into the report.
                hatZweitpasswort = Geheim.hatZweitpasswort(ctx),
                stand = stand,
                zusatz = Variante.infoZeilen(ctx),
            ),
        )
        File(ordner, BerichtNamen.PROTOKOLL).writeText(BotZustand.protokollSeit(protokollStart))
        bilder.forEachIndexed { i, b ->
            val name = BerichtNamen.bildBasis(i + 1, b.zeit)
            File(ordner, "$name.jpg").writeBytes(b.jpeg)
            File(ordner, "$name.json").writeText(b.json)
        }
        // Review before publishing (Nov 22): the screenshots the bot saved itself in this run (no longer in the gallery).
        val gespeichert = File(ordner, BerichtNamen.GESPEICHERT_ORDNER)
        val seit = maxOf(laufStartMs, System.currentTimeMillis() - 24L * 60 * 60 * 1000)
        for (f in if (mitGespeichert) BildSpeicher.neueste(ctx, seit, MAX_GESPEICHERT) else emptyList()) {
            if (!BerichtNamen.gehoertZumBericht(BerichtNamen.GESPEICHERT_ORDNER + "/" + f.name)) continue
            gespeichert.mkdirs()
            runCatching { f.copyTo(File(gespeichert, f.name), overwrite = true) }
        }
        // Field test 1.9: learning images into the subfolder lernziffern/ (names are already checked).
        for (l in lernbilder) {
            if (!BerichtNamen.gehoertZumBericht(l.name)) continue
            val f = File(ordner, l.name)
            f.parentFile?.mkdirs()
            f.writeBytes(l.png)
        }
        return ordner
    }

    /**
     * The newest report saved on the phone (folder), or null. Waits briefly for a report that is being written right
     * now. Only reports created by this app (folder names checked).
     */
    fun neuesterBericht(ctx: Context): File? {
        runCatching { sender.submit {}.get(10, TimeUnit.SECONDS) }
        return (basis(ctx.applicationContext).listFiles() ?: emptyArray())
            .filter { it.isDirectory && BerichtNamen.istBerichtOrdner(it.name) }
            .maxByOrNull { it.name }
    }

    /** Clean up old reports (only the newest stay, none older than [AUFBEWAHREN_MS]). */
    private fun aufraeumen(basis: File) {
        zuAlteLoeschen(basis)
        val alte = (basis.listFiles() ?: emptyArray())
            .filter { it.isDirectory && BerichtNamen.istBerichtOrdner(it.name) }
            .sortedBy { it.name }
        alte.dropLast(MAX_LOKAL - 1).forEach { it.deleteRecursively() }
        // Flight recorder (up to 25 MB per report): keep its screenshots only in the newest report left behind.
        alte.takeLast(MAX_LOKAL - 1).dropLast(1).forEach {
            File(it, FLUG_UNTERORDNER).deleteRecursively()
            File(it, FlugschreiberNamen.CSV).delete()
        }
    }

    /** Review before publishing (Nov 22): reports older than [AUFBEWAHREN_MS] are deleted. */
    private fun zuAlteLoeschen(basis: File) {
        val grenze = System.currentTimeMillis() - AUFBEWAHREN_MS
        (basis.listFiles() ?: emptyArray())
            .filter { it.isDirectory && BerichtNamen.istBerichtOrdner(it.name) && it.lastModified() < grenze }
            .forEach { it.deleteRecursively() }
    }

    /**
     * Review before publishing (Nov 22): the report at the end of the run is built – delete the flight recorder's
     * screenshots in the cache (only in the report thread; not if a new run has started since).
     */
    private fun flugLeeren(ctx: Context, nr: Int) {
        if (laufNr.get() != nr) return
        runCatching {
            verkleinerer.submit {
                synchronized(flugRing) {
                    if (laufNr.get() == nr) {
                        flugRing.clear()
                        File(ctx.cacheDir, FLUG_UNTERORDNER).deleteRecursively()
                    }
                }
                Unit
            }.get(10, TimeUnit.SECONDS)
        }
    }

    /**
     * Review before publishing (Nov 22), when the app is opened: delete reports and saved screenshots older than
     * [AUFBEWAHREN_MS], and the report ZIP prepared for sharing last time (older than [geteiltAlterMs] – the app it was
     * shared with may still be reading a fresh one). In the background.
     */
    fun alteLoeschen(ctx: Context, geteiltAlterMs: Long) {
        val app = ctx.applicationContext
        // Review round 3 (Nov 23): plus the flight recorder's leftovers after a crash, and log lines older than 14 days.
        resteLoeschen(app)
        sender.execute {
            runCatching { zuAlteLoeschen(basis(app)) }
            BildSpeicher.loeschen(app, System.currentTimeMillis() - AUFBEWAHREN_MS)
            runCatching { BotZustand.protokollAufraeumen() }
            val grenze = System.currentTimeMillis() - geteiltAlterMs
            (File(app.cacheDir, GETEILT_ORDNER).listFiles() ?: emptyArray())
                .filter { it.lastModified() < grenze }
                .forEach { runCatching { it.deleteRecursively() } }
        }
    }

    /**
     * Review round 3 (Nov 23): the flight recorder's screenshots in the cache (cache/flugschreiber) are normally deleted
     * at the end of a run. If the app crashed or was killed in the middle of a run, they stayed – they are deleted when
     * the service starts again or the app is opened. Only as long as no run has started in this app process (then the
     * folder belongs to that run).
     */
    fun resteLoeschen(ctx: Context) {
        val ordner = File(ctx.applicationContext.cacheDir, FLUG_UNTERORDNER)
        runCatching {
            verkleinerer.execute {
                synchronized(flugRing) {
                    if (laufNr.get() == 0) runCatching { ordner.deleteRecursively() }
                }
            }
        }
    }

    /**
     * Review before publishing (Nov 22): "Delete reports, log and screenshots" (settings, only while no run is active):
     * all reports on the phone, the flight recorder, the shared ZIP, the saved screenshots, the remembered screenshots
     * in memory and the log. [fertig] comes in the report thread when everything is gone.
     */
    fun allesLoeschen(ctx: Context, fertig: () -> Unit) {
        val app = ctx.applicationContext
        sender.execute {
            runCatching { verkleinerer.submit {}.get(5, TimeUnit.SECONDS) }
            synchronized(flugRing) {
                flugRing.clear()
                runCatching { File(app.cacheDir, FLUG_UNTERORDNER).deleteRecursively() }
            }
            puffer.leeren()
            lernPuffer.leeren()
            synchronized(lernStellen) { lernStellen.clear() }
            runCatching { basis(app).deleteRecursively() }
            runCatching { File(app.cacheDir, GETEILT_ORDNER).deleteRecursively() }
            BildSpeicher.loeschen(app)
            BotZustand.protokollLoeschen()
            fertig()
        }
    }
}
