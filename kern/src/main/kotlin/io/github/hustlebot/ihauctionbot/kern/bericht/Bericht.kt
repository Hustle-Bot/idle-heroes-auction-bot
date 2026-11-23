package io.github.hustlebot.ihauctionbot.kern.bericht

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Json
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Error reports (Nov 9).
 *
 * So that one can look up later what the bot saw and did: version, settings WITHOUT
 * secrets, the log since the start and the last ~20 screenshots with what recognition found
 * in them. One folder Bericht-<date>/ per report, in the app's private storage.
 *
 * Privacy: never a screenshot while the secondary password window is open or shortly after the bot
 * entered the password. The password is never in the report.
 */
object BerichtNamen {
    const val INFO = "info.txt"
    const val PROTOKOLL = "protokoll.txt"

    /** At most this many screenshots per report. */
    const val MAX_BILDER = 20

    /**
     * Nov 9 (field test 1.9): subfolder for learning images – full-resolution crops of numbers that were
     * there but not readable. The missing digits are learned from them.
     */
    const val LERN_ORDNER = "lernziffern"

    /** At most this many learning images per report (only one per spot). */
    const val MAX_LERNBILDER = 30

    private val ORDNER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val BILD_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss_SSS")
    private val ORDNER_MUSTER = Regex("^Bericht-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}$")
    private val BILD_MUSTER = Regex("^bild_\\d{2}_\\d{6}_\\d{3}\\.(jpg|json)$")
    private val LERN_MUSTER = Regex("^$LERN_ORDNER/[A-Za-z0-9_.+-]{1,140}\\.png$")

    /**
     * Review before publishing (Nov 22): subfolder for the screenshots the bot saved itself (diagnostics at the start,
     * unknown or unsure screens, an unknown window after "Buyout Price"). They used to go to the public gallery; now they
     * stay inside the app, and the newest of a run go into the report.
     */
    const val GESPEICHERT_ORDNER = "gespeichert"

    /** The kinds of saved screenshots (beginning of the file name). */
    val GESPEICHERT_ARTEN = setOf("diagnose", "unsicher", "buyout")
    private val GESPEICHERT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
    private val GESPEICHERT_DATEI = Regex("^(diagnose|unsicher|buyout)_\\d{8}_\\d{6}_\\d{3}\\.png$")

    /** File name of a saved screenshot, e.g. "unsicher_20261122_174501_250.png" (unknown kinds become "unsicher"). */
    fun gespeichertesBild(art: String, zeit: LocalDateTime): String =
        (if (art in GESPEICHERT_ARTEN) art else "unsicher") + "_" + GESPEICHERT_FORMAT.format(zeit) + ".png"

    /** Is [name] (without folder) the file name of a saved screenshot? */
    fun istGespeichertesBild(name: String): Boolean = GESPEICHERT_DATEI.matches(name)

    /** e.g. "Bericht-2026-11-09_17-53-20". */
    fun ordner(zeit: LocalDateTime): String = "Bericht-" + ORDNER_FORMAT.format(zeit)

    fun istBerichtOrdner(name: String): Boolean = ORDNER_MUSTER.matches(name)

    /** File name without extension for screenshot no. [nr] (1, 2, …), e.g. "bild_03_175307_120". */
    fun bildBasis(nr: Int, zeit: LocalTime): String {
        require(nr in 0..99) { "Image number $nr" }
        return "bild_" + nr.toString().padStart(2, '0') + "_" + BILD_FORMAT.format(zeit)
    }

    /** Only these files belong to a report – nothing else ever goes into it. */
    fun gehoertZumBericht(datei: String): Boolean =
        datei == INFO || datei == PROTOKOLL || BILD_MUSTER.matches(datei) ||
            (LERN_MUSTER.matches(datei) && ".." !in datei) ||
            (datei.startsWith("$GESPEICHERT_ORDNER/") && istGespeichertesBild(datei.removePrefix("$GESPEICHERT_ORDNER/")))

    /**
     * Name of a learning image in the report, e.g. "lernziffern/175307_120_preis_z676_3074-3169-24.png" or with the
     * expected value "…_aktuellesGebot_bid-fenster_erwartet-47.png". Only simple characters, so that the path is safe.
     */
    fun lernBild(zeit: LocalTime, feld: String, kontext: String, erwartet: String?): String {
        fun sauber(t: String) = t.replace(Regex("[^A-Za-z0-9_.+-]"), "-").replace(Regex("\\.{2,}"), ".").trim('.', '-')
        val teile = listOfNotNull(
            BILD_FORMAT.format(zeit), sauber(feld).ifEmpty { "zahl" }, sauber(kontext).takeIf { it.isNotEmpty() },
            erwartet?.let { "erwartet-" + sauber(it) },
        )
        return "$LERN_ORDNER/" + teile.joinToString("_").take(140) + ".png"
    }
}

/** What recognition found on a screenshot, as JSON (one file next to every report screenshot). */
object BerichtJson {

    fun bildschirm(s: Bildschirm, zeit: LocalTime, botTat: String?): String = buildString {
        fun t(x: String) = Json.text(x)
        fun n(x: Int?) = x?.toString() ?: "null"
        append("{")
        append("\"zeit\":").append(t(zeit.toString()))
        append(",\"ort\":").append(t(s.ort.name))
        append(",\"ortText\":").append(t(s.ort.text))
        append(",\"sg\":").append(n(s.sg))
        append(",\"laufendeGebote\":").append(n(s.laufendeGebote))
        append(",\"aktuellesGebot\":").append(n(s.aktuellesGebot))
        append(",\"bestaetigenPreis\":").append(n(s.bestaetigenPreis))
        // Nov 22 (buyout): "Buyout Price: N" in the Bid window.
        append(",\"sofortPreis\":").append(n(s.sofortPreis))
        append(",\"meldung\":").append(s.meldung?.let { "{\"breite\":${it.breite},\"kurz\":${it.kurz}}" } ?: "null")
        append(",\"sortierung\":").append(t(s.sortierung.name))
        append(",\"filterMinStellen\":").append(n(s.filterMinStellen))
        append(",\"filterMaxStellen\":").append(n(s.filterMaxStellen))
        append(",\"knoepfe\":{")
        append(s.knoepfe.entries.sortedBy { it.key.name }.joinToString(",") { (k, p) -> "${t(k.name)}:[${p.x},${p.y}]" })
        append("}")
        append(",\"filterGewaehlt\":[")
        append(s.filterFelder.filter { it.gewaehlt }.joinToString(",") { t(it.stufe) })
        append("]")
        append(",\"zeilen\":[")
        append(
            s.zeilen.joinToString(",") { z ->
                "{\"oben\":${z.oben},\"verdeckt\":${z.verdeckt},\"id\":${z.id?.let { t(it) } ?: "null"},\"preis\":${n(z.preis)},\"sofort\":${n(z.sofortPreis)}," +
                    "\"restzeitSek\":${n(z.restzeitSek)},\"hatRestzeit\":${z.hatRestzeit}," +
                    "\"werte\":[${z.werte.joinToString(",") { n(it) }}],\"zeichen\":${t(z.zeichen.name)}," +
                    "\"kreuz\":${z.ueberbotenKreuz},\"stufe\":${z.stufe?.let { t(it.toString()) } ?: "null"}," +
                    "\"tippen\":[${z.tippen.x},${z.tippen.y}]}"
            },
        )
        append("]")
        append(",\"unlesbar\":[")
        append(s.unlesbar.joinToString(",") { u -> "{\"feld\":${t(u.feld)},\"kontext\":${t(u.kontext)},\"bereich\":[${u.x0},${u.y0},${u.x1},${u.y1}]}" })
        append("]")
        append(",\"botTat\":").append(botTat?.let { t(it) } ?: "null")
        append("}")
    }
}

/** Privacy for report screenshots (Nov 9): never the secondary password window, never shortly after typing it. */
object BerichtDatenschutz {
    /** No screenshots are taken for this long after the password was entered. */
    const val NACH_PASSWORT_MS = 60_000L

    fun bildErlaubt(ort: Ort?, jetztMs: Long, keineBilderBisMs: Long): Boolean =
        ort != Ort.ZWEITPASSWORT && jetztMs >= keineBilderBisMs
}

/**
 * Is the bot stuck? (Nov 9): no action on the same screen for [grenzeMs], although it is not
 * waiting on purpose (e.g. 50 bids, budget planned, pause). Then at most one automatic report
 * every [abstandMs].
 */
class Haenger(val grenzeMs: Long = 45_000, val abstandMs: Long = 600_000) {
    private var seit: Long? = null
    private var ort: Ort? = null
    private var letzterBericht: Long? = null

    /** A new run starts (the gap between two reports is kept). */
    fun start(jetztMs: Long) {
        seit = jetztMs
        ort = null
    }

    /** The bot tapped/swiped/typed. */
    fun gehandelt(jetztMs: Long) {
        seit = jetztMs
    }

    /** true = send an automatic report now. */
    fun pruefe(jetztMs: Long, gesehen: Ort?, wartetAbsichtlich: Boolean): Boolean {
        if (wartetAbsichtlich || gesehen != ort) {
            ort = gesehen
            seit = jetztMs
            return false
        }
        val s = seit ?: return false
        if (jetztMs - s < grenzeMs) return false
        letzterBericht?.let { if (jetztMs - it < abstandMs) return false }
        letzterBericht = jetztMs
        seit = jetztMs
        return true
    }
}

/** The last [max] entries (the oldest drop out). Safe for several threads. */
class BildPuffer<T>(val max: Int = BerichtNamen.MAX_BILDER) {
    private val liste = ArrayDeque<T>()

    @Synchronized
    fun dazu(t: T) {
        liste.addLast(t)
        while (liste.size > max) liste.removeFirst()
    }

    @Synchronized
    fun alle(): List<T> = liste.toList()

    @Synchronized
    fun leeren() = liste.clear()

    @get:Synchronized
    val groesse: Int get() = liste.size
}

/** Text for info.txt – ONLY yes/no for the password, never the value itself. */
object BerichtText {

    fun info(
        zeit: LocalDateTime,
        grund: String,
        version: String,
        geraet: String,
        android: String,
        regeln: List<Suchregel>,
        zielKopien: Int,
        endeUm: LocalTime?,
        sgBudget: Int?,
        bildschirmAnlassen: Boolean,
        bildschirmDunkel: Boolean,
        hatZweitpasswort: Boolean,
        stand: String,
        /** More lines for "Settings:" from a hook of the app (never a secret value, only yes/no). */
        zusatz: List<String> = emptyList(),
    ): String = buildString {
        appendLine("Error report from $zeit")
        appendLine("Reason: $grund")
        appendLine("App version: $version")
        appendLine("Phone: $geraet, Android $android")
        appendLine()
        appendLine("Settings:")
        if (regeln.isEmpty()) appendLine("  (no rule)")
        regeln.forEachIndexed { i, r -> appendLine("  Rule ${i + 1}: $r") }
        appendLine("  Goal: " + if (zielKopien > 0) "$zielKopien copies" else "no limit")
        appendLine("  End at: " + (endeUm?.toString() ?: "no time"))
        appendLine("  SG budget: " + (sgBudget?.let { "$it SG" } ?: "no limit"))
        appendLine("  Keep screen on: " + jaNein(bildschirmAnlassen) + ", dark: " + jaNein(bildschirmDunkel))
        appendLine("  Secondary password saved: " + jaNein(hatZweitpasswort))
        zusatz.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Status: $stand")
    }

    private fun jaNein(b: Boolean) = if (b) "yes" else "no"
}
