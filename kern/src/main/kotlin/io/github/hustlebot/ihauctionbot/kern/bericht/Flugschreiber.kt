package io.github.hustlebot.ihauctionbot.kern.bericht

import io.github.hustlebot.ihauctionbot.kern.Aktion
import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ergebnis
import io.github.hustlebot.ihauctionbot.kern.Punkt

/**
 * Nov 11 (wish: after every run "one can see where the bot taps and what it sees"): flight recorder.
 *
 * During a run the app keeps EVERY screenshot the bot evaluated from the last [FlugschreiberRegeln.MAX_ALTER_MS]
 * (half size, JPEG, in the app's cache on the phone), plus one line per screenshot: time since the start, what
 * recognition saw and what the bot did in response (tap/swipe with points on the screen AND in
 * reference pixels, reason, waiting time). It goes into the error report: at the end of every run,
 * with the "bot is stuck" report and on special events (at most once in [FlugschreiberRegeln.EREIGNIS_ABSTAND_MS]).
 *
 * Privacy as with the report images: never a screenshot with the secondary password window or shortly after typing it –
 * such lines have no image.
 *
 * Only what works without Android is here (with tests): names, which images stay / go into a report, CSV, throttle.
 */

/** One line in the flight recorder. [datei] = image name without extension (null = no image), [bytes] = size of image + JSON. */
data class FlugEintrag(
    val zeitMs: Long,
    val datei: String?,
    val bytes: Long,
    val ort: String,
    val aktion: String,
    val x: Int? = null,
    val y: Int? = null,
    val refX: Int? = null,
    val refY: Int? = null,
    val grund: String = "",
)

/** Names – only these files belong to the flight recorder in the report. */
object FlugschreiberNamen {
    const val CSV = "flugschreiber.csv"
    private val BILD_MUSTER = Regex("^f_\\d{9}\\.(jpg|json)$")

    /** Name of an image (without extension), e.g. "f_000012750" = 12.75 s after the start. */
    fun bildBasis(zeitMs: Long): String = "f_" + zeitMs.coerceIn(0, 999_999_999).toString().padStart(9, '0')

    /** An image or its JSON file (only in the report's subfolder for the flight recorder). */
    fun istBildDatei(name: String): Boolean = BILD_MUSTER.matches(name)

    /** May this file go into the report folder itself? (the list – the images go into the subfolder.) */
    fun gehoertDazu(datei: String): Boolean = datei == CSV
}

object FlugschreiberRegeln {
    /** The images stay in the ring this long (3 minutes). */
    const val MAX_ALTER_MS = 180_000L

    /** At most this much space on the phone. */
    const val MAX_RING_BYTES: Long = 40L * 1024 * 1024

    /** At most this much goes into one report (newest images first). */
    const val MAX_BERICHT_BYTES: Long = 25L * 1024 * 1024

    /** A report because of a special event at most this often (the one at the end of a run always comes). */
    const val EREIGNIS_ABSTAND_MS = 600_000L

    /**
     * Nov 11 (review: far too much data per hour possible): reports because of an event or "bot is stuck"
     * only bring the last minute, at most this much.
     */
    const val KURZ_ALTER_MS = 60_000L
    const val KURZ_MAX_BYTES: Long = 8L * 1024 * 1024

    /**
     * Which entries stay? Only those of the last [maxAlterMs] (measured from the newest) and together at most [maxBytes] –
     * the newest win. Result old → new.
     */
    fun behalten(
        eintraege: List<FlugEintrag>,
        maxAlterMs: Long = MAX_ALTER_MS,
        maxBytes: Long = MAX_RING_BYTES,
    ): List<FlugEintrag> {
        if (eintraege.isEmpty()) return emptyList()
        val sortiert = eintraege.sortedBy { it.zeitMs }
        val neuester = sortiert.last().zeitMs
        val behalten = ArrayList<FlugEintrag>()
        var summe = 0L
        for (e in sortiert.asReversed()) {
            if (neuester - e.zeitMs > maxAlterMs) break
            if (summe + e.bytes > maxBytes) break
            summe += e.bytes
            behalten += e
        }
        return behalten.asReversed()
    }

    /**
     * Nov 11 (review): what goes into a report. [kurz] = report because of an event or "bot is stuck":
     * only the last minute, at most [KURZ_MAX_BYTES]; otherwise (end of run, by hand) the last 3 minutes, at most
     * [MAX_BERICHT_BYTES]. [grenze] = a further limit for the images in bytes (default: none). All lines of the period
     * stay in the list; images only the newest ones that still fit (with [grenze] 0: none).
     */
    fun fuerBericht(eintraege: List<FlugEintrag>, kurz: Boolean, grenze: Long = Long.MAX_VALUE): List<FlugEintrag> {
        val zeitraum = behalten(eintraege, if (kurz) KURZ_ALTER_MS else MAX_ALTER_MS, Long.MAX_VALUE)
        val maxBytes = minOf(if (kurz) KURZ_MAX_BYTES else MAX_BERICHT_BYTES, grenze.coerceAtLeast(0))
        val mitBild = behalten(zeitraum, Long.MAX_VALUE, maxBytes).filter { it.datei != null }.mapTo(HashSet()) { it.zeitMs }
        return zeitraum.map { if (it.datei != null && it.zeitMs !in mitBild) it.copy(datei = null, bytes = 0) else it }
    }

    const val CSV_KOPF = "zeit_ms,datei,ort,aktion,x,y,ref_x,ref_y,grund"

    /** flugschreiber.csv (old → new). Fields with a comma/quote are put in "…", line breaks removed. */
    fun csv(eintraege: List<FlugEintrag>): String = buildString {
        append(CSV_KOPF).append('\n')
        for (e in eintraege.sortedBy { it.zeitMs }) {
            append(
                listOf(
                    e.zeitMs.toString(),
                    e.datei?.let { "$it.jpg" } ?: "",
                    e.ort,
                    e.aktion,
                    e.x?.toString() ?: "",
                    e.y?.toString() ?: "",
                    e.refX?.toString() ?: "",
                    e.refY?.toString() ?: "",
                    e.grund,
                ).joinToString(",") { feld(it) },
            ).append('\n')
        }
    }

    private fun feld(t: String): String {
        val einzeilig = t.replace(Regex("[\\r\\n]+"), " ")
        return if (einzeilig.any { it == ',' || it == '"' }) "\"" + einzeilig.replace("\"", "\"\"") + "\"" else einzeilig
    }

    /**
     * The line for an evaluated screenshot: place ([schirm] = what recognition saw in EXACTLY this screenshot, null = not
     * read) and what the core wanted to do in response ([ergebnis], null = no result, e.g. an error in recognition).
     * Points are given on the screen (x/y) and in reference pixels 2712 × 1220 (ref_x/ref_y). For a swipe the target is
     * in the reason, for a sequence all steps. The secondary password is never in it (the core does not even know it).
     */
    fun zeile(zeitMs: Long, datei: String?, bytes: Long, schirm: Bildschirm?, ergebnis: Ergebnis?): FlugEintrag {
        val ort = schirm?.ort?.name ?: "NICHT_GELESEN"
        fun ref(p: Punkt): Punkt? = schirm?.inReferenz(p)
        fun mit(aktion: String, p: Punkt?, grund: String) = FlugEintrag(
            zeitMs = zeitMs, datei = datei, bytes = bytes, ort = ort, aktion = aktion,
            x = p?.x, y = p?.y, refX = p?.let { ref(it) }?.x, refY = p?.let { ref(it) }?.y, grund = grund,
        )
        fun punktText(p: Punkt): String = "${p.x}/${p.y}" + (ref(p)?.let { " (ref ${it.x}/${it.y})" } ?: "")
        return when (ergebnis) {
            null -> mit("KEIN_ERGEBNIS", null, "")
            is Ergebnis.Handeln -> {
                val weiter = " · next in ${ergebnis.naechsterSchrittMs} ms"
                when (val a = ergebnis.aktion) {
                    is Aktion.Tippen -> mit("TIPPEN", a.punkt, a.grund + " · ${a.dauerMs} ms" + weiter)
                    is Aktion.Wischen -> mit(
                        "WISCHEN",
                        a.von,
                        "to ${punktText(a.nach)} in ${a.dauerMs} ms" + (if (a.haltenMs > 0) " + ${a.haltenMs} ms hold" else "") +
                            " · " + a.grund + weiter,
                    )
                    is Aktion.Eingeben -> mit("EINGEBEN", a.feld, a.grund + weiter)
                    is Aktion.PasswortEingeben -> mit("ZWEITPASSWORT", a.feld, a.grund + weiter)
                    is Aktion.Folge -> mit(
                        "FOLGE",
                        a.schritte.firstNotNullOfOrNull { s -> (s as? Aktion.Tippen)?.punkt ?: (s as? Aktion.Eingeben)?.feld },
                        a.grund + ": " + a.schritte.joinToString(" | ") { s ->
                            when (s) {
                                is Aktion.Tippen -> "Tap ${punktText(s.punkt)} ${s.grund}"
                                is Aktion.Eingeben -> "Type ${punktText(s.feld)} ${s.grund}"
                                else -> s.grund
                            }
                        } + weiter,
                    )
                }
            }
            is Ergebnis.Warten -> mit("WARTEN", null, "${ergebnis.grund} · ${ergebnis.ms} ms")
            is Ergebnis.Unsicher -> mit("UNSICHER", null, "${ergebnis.grund} · ${ergebnis.ms} ms")
            is Ergebnis.Fertig -> mit("STOPP", null, ergebnis.grund.text)
            Ergebnis.Pausiert -> mit("PAUSE", null, "")
        }
    }
}

/**
 * A report because of a special event ("tap locked", unknown screen, "-7" …) at most once in
 * [abstandMs]. The report at the end of a run always comes (does not ask here). Safe for several threads.
 */
class FlugEreignisBremse(val abstandMs: Long = FlugschreiberRegeln.EREIGNIS_ABSTAND_MS) {
    private var letztes: Long? = null

    /** true = send a report now (and not again for [abstandMs] from now on). */
    @Synchronized
    fun erlaubt(jetztMs: Long): Boolean {
        letztes?.let { if (jetztMs - it in 0 until abstandMs) return false }
        letztes = jetztMs
        return true
    }

    /** The report did not even start (e.g. another one already on its way): do not count this attempt. */
    @Synchronized
    fun zuruecknehmen(jetztMs: Long) {
        if (letztes == jetztMs) letztes = null
    }
}
