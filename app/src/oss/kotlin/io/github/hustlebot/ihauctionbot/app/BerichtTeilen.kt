package io.github.hustlebot.ihauctionbot.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.hustlebot.ihauctionbot.kern.bericht.BerichtNamen
import io.github.hustlebot.ihauctionbot.kern.bericht.FlugschreiberNamen
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Share the newest saved report with Android's share sheet. The report is packed into one ZIP file in
 * the app's cache (folder [ORDNER], the only folder the FileProvider serves, see res/xml/berichte_teilen.xml); the
 * app that the player chooses only gets read access to exactly this file. Nothing is sent by this app itself.
 * Review round 3 (Nov 23): "Share log only" ([protokollTeilen]) shares just the log as a text file the same way.
 */
object BerichtTeilen {

    /**
     * Folder in the cache with the ZIP to share – emptied before every share (only ever one file), and deleted the next
     * time the app is opened (see BerichtSender.alteLoeschen).
     */
    private const val ORDNER = BerichtSender.GETEILT_ORDNER

    private val arbeiter = Executors.newSingleThreadExecutor()

    fun teilen(act: Activity) {
        Toast.makeText(act, "Preparing the report …", Toast.LENGTH_SHORT).show()
        arbeiter.execute {
            val bericht = BerichtSender.neuesterBericht(act)
            if (bericht == null) {
                act.runOnUiThread { Toast.makeText(act, "No report saved yet – tap \"Save report\" first.", Toast.LENGTH_LONG).show() }
                return@execute
            }
            val zip = try {
                packe(act, bericht)
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Could not pack the report (${e.javaClass.simpleName}).", Toast.LENGTH_LONG).show() }
                return@execute
            }
            val uri = FileProvider.getUriForFile(act, act.packageName + ".berichte", zip)
            val senden = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "IH Auction Bot report ${bericht.name}")
                clipData = ClipData.newRawUri(bericht.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.runOnUiThread {
                if (!act.isDestroyed) act.startActivity(Intent.createChooser(senden, "Share report"))
            }
        }
    }

    /**
     * Review round 3 (Nov 23): "Share log only" – the log (at most the last 14 days) as ONE text file, without any
     * screenshot, through the same safe path as a report (cache/geteilt/, read-only for the app the player picks).
     * Never the secondary password (it is never logged).
     */
    fun protokollTeilen(act: Activity) {
        Toast.makeText(act, "Preparing the log …", Toast.LENGTH_SHORT).show()
        arbeiter.execute {
            val datei = try {
                schreibeProtokoll(act)
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Could not prepare the log (${e.javaClass.simpleName}).", Toast.LENGTH_LONG).show() }
                return@execute
            }
            val uri = FileProvider.getUriForFile(act, act.packageName + ".berichte", datei)
            val senden = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "IH Auction Bot log")
                clipData = ClipData.newRawUri(datei.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.runOnUiThread {
                if (!act.isDestroyed) act.startActivity(Intent.createChooser(senden, "Share log (privately)"))
            }
        }
    }

    /** Writes the log into the only folder the FileProvider serves (emptied first – only ever one file). */
    private fun schreibeProtokoll(ctx: Context): File {
        val ordner = File(ctx.cacheDir, ORDNER).apply {
            deleteRecursively()
            mkdirs()
        }
        val jetzt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val datei = File(ordner, "IH-Auction-Bot-log-" + jetzt.toString().replace(':', '-') + ".txt")
        val kopf = "IH Auction Bot log – version ${AppInfo.version(ctx)}, ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE ?: "?"}, shared $jetzt\n" +
            "Only the log: no screenshots, no secondary password.\n\n"
        datei.writeText(kopf + BotZustand.protokollGanz())
        return datei
    }

    /** Packs only the files of the report (same rules as everywhere: no other file of the app can end up in it). */
    private fun packe(ctx: Context, bericht: File): File {
        val ordner = File(ctx.cacheDir, ORDNER).apply {
            deleteRecursively()
            mkdirs()
        }
        val zip = File(ordner, bericht.name + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            bericht.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                val name = f.relativeTo(bericht).invariantSeparatorsPath
                val erlaubt = BerichtNamen.gehoertZumBericht(name) || FlugschreiberNamen.gehoertDazu(name) ||
                    (name.startsWith(BerichtSender.FLUG_UNTERORDNER + "/") && FlugschreiberNamen.istBildDatei(name.substringAfter('/')))
                if (!erlaubt) return@forEach
                z.putNextEntry(ZipEntry(bericht.name + "/" + name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }
}
