package io.github.hustlebot.ihauctionbot.kern.bild

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * Learns the digits from real screenshots. Learned from:
 *  - the test images with lernen = true (SG at the top right from all test images),
 *  - Nov 11: the crops from the error reports (testbilder/ziffern, value in the file name),
 *  - Nov 11: "Buyout Price: 28 / 109" in the Bid window – same font as "Current Bid" (gives the 0 and 9).
 */
object ZiffernLernen {
    class Probe(val zeichen: Char, val merkmal: FloatArray, val seite: Float)

    /** Nov 11: up to 12 templates per digit, as different as possible (before: 8 – too few for all variants). */
    const val PRO_ZIFFER = 12

    /** "Buyout Price: 28" in the Bid window – only the number after the colon (measured on bid-fenster.jpg). */
    val SOFORT_IM_FENSTER = Rechteck(1440, 785, 1640, 845)

    /**
     * Collect all samples; [ohneBericht]: leave out the crops of this error report, [ohneBild]: leave out this test
     * image (both for the cross-check).
     */
    fun sammle(ohneBericht: String? = null, probleme: MutableList<String> = ArrayList(), ohneBild: String? = null): Map<Schrift, List<Probe>> {
        val proben = HashMap<Schrift, MutableList<Probe>>()
        fun nimm(name: String, bild: Bild, feld: Rechteck, schrift: Schrift, text: String, minHoehe: Int = 14, vonRechts: Boolean = false) {
            var gl = ZiffernLeser.glyphen(bild, feld, schrift, minHoehe)
            if (vonRechts) gl = gl.takeLast(text.length)
            if (gl.size != text.length) {
                probleme += "$name $schrift '$text': ${gl.size} blobs instead of ${text.length} (heights ${gl.map { it.hoehe }})"
                return
            }
            gl.forEachIndexed { i, g -> proben.getOrPut(schrift) { ArrayList() } += Probe(text[i], g.merkmal(), g.seite) }
        }
        // SG at the top right does not matter for the decisions – so learn from all images (otherwise digits are missing).
        for (e in Ablesung.alle.filter { it.name != ohneBild }) {
            e.sg?.let { nimm(e.name, TestBilder.lade(e.name), Erkenner.SG_FELD, Schrift.SG, it.toString(), 18) }
        }
        for (e in Ablesung.alle.filter { it.lernen && it.name != ohneBild }) {
            val b = TestBilder.lade(e.name)
            e.zaehler?.let { nimm(e.name, b, Erkenner.ZAEHLER_FELD, Schrift.WEISS, it.toString()) }
            e.aktuellesGebot?.let { nimm(e.name, b, Erkenner.AKTUELLES_GEBOT_FELD, Schrift.HELL, it.toString()) }
            e.bestaetigen?.let { nimm(e.name, b, Erkenner.BESTAETIGEN_FELD, Schrift.BRAUN, it.toString(), 16, vonRechts = true) }
            for (z in e.zeilen.filter { !it.verdeckt }) {
                nimm(e.name, b, Erkenner.preisFeld(z.oben), Schrift.WEISS, z.preis.toString())
                z.sofort?.let { nimm(e.name, b, Erkenner.sofortFeld(z.oben), Schrift.WEISS, it.toString()) }
                z.zeit?.let { nimm(e.name, b, Erkenner.zeitFeld(z.oben), Schrift.GRUEN, it) }
                z.werte.forEachIndexed { i, w -> nimm(e.name, b, Erkenner.wertFeld(z.oben, i), Schrift.ROT, w.toString(), 16) }
            }
        }
        nimm("bid-fenster Buyout", TestBilder.lade("bid-fenster"), SOFORT_IM_FENSTER, Schrift.HELL, "28")
        nimm("limit-50-status-7 Buyout", TestBilder.lade("limit-50-status-7"), SOFORT_IM_FENSTER, Schrift.HELL, "109")
        for (p in Lernziffern.alle.filter { it.bericht != ohneBericht }) {
            if (p.art == "bestaetigen") nimm(p.datei, p.bild, p.feld, Schrift.BRAUN, p.wert, 16, vonRechts = true)
            else nimm(p.datei, p.bild, p.feld, p.schrift, p.wert, p.minHoehe)
        }
        return proben
    }

    /** Per digit up to [PRO_ZIFFER] samples, as different as possible (always add the one that is furthest away). */
    fun auswahl(gruppe: List<Probe>, anzahl: Int = PRO_ZIFFER): List<Probe> {
        if (gruppe.size <= anzahl) return gruppe
        val gewaehlt = arrayListOf(gruppe.first())
        while (gewaehlt.size < anzahl) {
            gewaehlt += gruppe.filter { it !in gewaehlt }.maxBy { p -> gewaehlt.minOf { abstand(p, it) } }
        }
        return gewaehlt
    }

    private fun abstand(a: Probe, b: Probe): Float {
        var d = 0f
        for (i in a.merkmal.indices) {
            val e = a.merkmal[i] - b.merkmal[i]
            d += e * e
        }
        return d / a.merkmal.size + 1.5f * (a.seite - b.seite) * (a.seite - b.seite)
    }

    /** A template exactly as it is written in ZiffernVorlagen.kt (values 0–9, aspect ratio to 3 digits). */
    fun werteText(p: Probe) = p.merkmal.joinToString("") { ((it * 9f) + 0.5f).toInt().coerceIn(0, 9).toString() }
    fun seiteText(p: Probe) = String.format(Locale.ROOT, "%.3f", p.seite)

    fun vorlagen(proben: Map<Schrift, List<Probe>>): Map<Schrift, List<Vorlage>> = Schrift.values().associateWith { s ->
        proben[s].orEmpty().groupBy { it.zeichen }.toSortedMap().flatMap { (z, g) ->
            auswahl(g).map { Vorlage(z, seiteText(it).toFloat(), werteText(it)) }
        }
    }
}

class ZiffernLernenTest {

    @AfterEach
    fun zurueck() {
        ZiffernLeser.vorlagenQuelle = { ZiffernVorlagen.fuer(it) }
        ZiffernLeser.zwischenspeicherLeeren()
    }

    /** Rewrites ZiffernVorlagen.kt. Only with LERNEN=1 (`LERNEN=1 gradle :kern:test --tests '*ZiffernLernen*'`). */
    @Test
    fun `learn digits`() {
        assumeTrue(System.getenv("LERNEN") == "1", "Only with LERNEN=1")
        val probleme = ArrayList<String>()
        val proben = ZiffernLernen.sammle(probleme = probleme)
        probleme.forEach { println("PROBLEM: $it") }

        val code = StringBuilder()
        code.append("package io.github.hustlebot.ihauctionbot.kern.bild\n\n")
        code.append("// Generated automatically by ZiffernLernenTest (LERNEN=1) from the screenshots in testbilder/.\n")
        code.append("// Do not edit by hand – let it learn again.\n")
        code.append("internal object ZiffernVorlagen {\n")
        code.append("    fun fuer(schrift: Schrift): List<Vorlage> = when (schrift) {\n")
        for (s in Schrift.values()) code.append("        Schrift.$s -> ${s.name.lowercase(Locale.ROOT)}\n")
        code.append("    }\n\n")
        for (s in Schrift.values()) {
            code.append("    private val ${s.name.lowercase(Locale.ROOT)}: List<Vorlage> = listOf(\n")
            for ((zeichen, gruppe) in proben[s].orEmpty().groupBy { it.zeichen }.toSortedMap()) {
                for (p in ZiffernLernen.auswahl(gruppe)) {
                    code.append("        Vorlage('$zeichen', ${ZiffernLernen.seiteText(p)}f, \"${ZiffernLernen.werteText(p)}\"),\n")
                }
            }
            code.append("    )\n\n")
        }
        code.setLength(code.length - 1)
        code.append("}\n")
        val ziel = File("src/main/kotlin/io/github/hustlebot/ihauctionbot/kern/bild/ZiffernVorlagen.kt")
        ziel.writeText(code.toString())
        println("Written: ${ziel.absolutePath}")
        for (s in Schrift.values()) {
            println("$s: " + proben[s].orEmpty().groupBy { it.zeichen }.toSortedMap().map { "${it.key}×${it.value.size}" })
        }
    }

    /** The saved templates are exactly the ones learned from the images (nobody forgot to learn again). */
    @Test
    fun `ZiffernVorlagen match the learning images`() {
        val neu = ZiffernLernen.vorlagen(ZiffernLernen.sammle())
        for (s in Schrift.values()) {
            val alt = ZiffernVorlagen.fuer(s).map { "${it.zeichen}${it.seite}" }
            assertEquals(neu.getValue(s).map { "${it.zeichen}${it.seite}" }, alt, "$s: let ZiffernVorlagen.kt learn again (LERNEN=1)")
        }
    }

    /**
     * Nov 11: cross-check. Learn without the crops of ONE error report and then read exactly those: they may
     * be unreadable (then the bot asks again), but NEVER wrong.
     */
    @Test
    fun `cross-check - numbers of an error report that was not learned are never misread`() {
        val berichte = Lernziffern.alle.map { it.bericht }.distinct()
        val zeilen = ArrayList<String>()
        val falsch = ArrayList<String>()
        for (b in berichte) {
            val vorlagen = ZiffernLernen.vorlagen(ZiffernLernen.sammle(ohneBericht = b))
            ZiffernLeser.vorlagenQuelle = { vorlagen.getValue(it) }
            ZiffernLeser.zwischenspeicherLeeren()
            var richtig = 0
            var unlesbar = 0
            val proben = Lernziffern.alle.filter { it.bericht == b }
            for (p in proben) {
                when (Lernziffern.lies(p)) {
                    p.wert -> richtig++
                    null -> unlesbar++
                    else -> falsch += "${p.datei}: ${Lernziffern.lies(p)}"
                }
            }
            zeilen += "report $b not learned: ${proben.size} numbers, correct $richtig, not readable $unlesbar"
        }
        println(zeilen.joinToString("\n"))
        assertTrue(falsch.isEmpty(), "Misread:\n" + falsch.joinToString("\n"))
    }

    /**
     * Nov 12: cross-check for the new images from the phone (PNG, full resolution) – learn without the image and then
     * read it: counter "Bidding Items", SG, price (Bidding Price), buyout (Buyout Price), remaining time and stats are
     * correct or not readable, but NEVER wrong.
     */
    @Test
    fun `cross-check - new phone images are never misread without learning`() {
        val falsch = ArrayList<String>()
        val zeilen = ArrayList<String>()
        for (name in listOf("meine-gebote-41")) {
            val vorlagen = ZiffernLernen.vorlagen(ZiffernLernen.sammle(ohneBild = name))
            ZiffernLeser.vorlagenQuelle = { vorlagen.getValue(it) }
            ZiffernLeser.zwischenspeicherLeeren()
            val e = Ablesung.alle.first { it.name == name }
            val s = Erkenner.erkenne(TestBilder.lade(name))
            var richtig = 0
            var unlesbar = 0
            fun pruefe(was: String, soll: Any?, ist: Any?) {
                when (ist) {
                    soll -> richtig++
                    null -> unlesbar++
                    else -> falsch += "$name $was: $ist instead of $soll"
                }
            }
            pruefe("Bidding Items", e.zaehler, s.laufendeGebote)
            pruefe("SG", e.sg, s.sg)
            for (w in e.zeilen) {
                val z = s.zeilen.firstOrNull { kotlin.math.abs(it.oben - w.oben) <= 3 } ?: continue
                pruefe("row ${w.oben} price", w.preis, z.preis)
                pruefe("row ${w.oben} buyout", w.sofort, z.sofortPreis)
                val sek = w.zeit?.let { it.substring(0, 2).toInt() * 3600 + it.substring(2, 4).toInt() * 60 + it.substring(4, 6).toInt() }
                pruefe("row ${w.oben} remaining time", sek, z.restzeitSek)
                w.werte.forEachIndexed { i, wert -> pruefe("row ${w.oben} stat ${i + 1}", wert, z.werte[i]) }
            }
            zeilen += "$name not learned: correct $richtig, not readable $unlesbar"
        }
        println(zeilen.joinToString("\n"))
        assertTrue(falsch.isEmpty(), "Misread:\n" + falsch.joinToString("\n"))
    }
}
