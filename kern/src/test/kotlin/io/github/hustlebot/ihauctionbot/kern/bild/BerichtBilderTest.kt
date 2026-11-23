package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Nov 11: "Image and number recognition should work 100 %." Checks the recognizer on ALL real images:
 * test images (full resolution) and all images from the error reports (half resolution).
 *
 * Rule as always: better "not readable" than wrong. In the report images (half resolution) the digits are
 * only half as large as on the phone – there the bot does not have to be able to read them, but must NEVER misread them.
 * Everything else (place, rows, badges, clock/hammer/X, message, sort order, filter) must be right there too.
 */
class BerichtBilderTest {

    /** Counts per field: correct / not readable / wrong. */
    class Zaehlung {
        val felder = LinkedHashMap<String, IntArray>()
        val fehler = ArrayList<String>()

        fun struktur(feld: String, ok: Boolean, text: () -> String) {
            val z = felder.getOrPut(feld) { IntArray(3) }
            if (ok) z[0]++ else { z[2]++; fehler += "$feld: ${text()}" }
        }

        fun zahl(feld: String, soll: Any?, ist: Any?, wo: String) {
            val z = felder.getOrPut(feld) { IntArray(3) }
            when (ist) {
                soll -> z[0]++
                null -> z[1]++
                else -> { z[2]++; fehler += "$feld WRONG: $wo – read $ist instead of $soll" }
            }
        }

        val falsche get() = fehler.filter { " WRONG: " in it }
        val strukturFehler get() = fehler.filter { " WRONG: " !in it }
    }

    companion object {
        private fun zeitInSek(t: String) = t.substring(0, 2).toInt() * 3600 + t.substring(2, 4).toInt() * 60 + t.substring(4, 6).toInt()

        /** Compares a recognition with what is really on the image. */
        fun pruefe(e: Erwartet, s: Bildschirm, z: Zaehlung) {
            val n = e.name
            z.struktur("place", e.ort == s.ort) { "$n: ${s.ort} instead of ${e.ort}" }
            if (e.ort != s.ort) return
            e.sg?.let { z.zahl("SG", it, s.sg, n) }
            e.zaehler?.let { z.zahl("Bidding Items", it, s.laufendeGebote, n) }
            e.aktuellesGebot?.let { z.zahl("Current Bid", it, s.aktuellesGebot, n) }
            e.bestaetigen?.let { z.zahl("Starry Gems spent", it, s.bestaetigenPreis, n) }
            // Nov 23 (review round 3): the bid field (half resolution: usually not readable, but never wrong).
            e.bietFeld?.let { z.zahl("bid field", it, s.bietFeld, n) }
            when (e.meldung) {
                true -> z.struktur("message", s.meldung != null && s.meldung!!.kurz == e.meldungKurz) { "$n: ${s.meldung} (short expected: ${e.meldungKurz})" }
                false -> z.struktur("message", s.meldung == null) { "$n: ${s.meldung} instead of none" }
                null -> {}
            }
            e.sortierung?.let { soll -> z.struktur("sort order", soll == s.sortierung) { "$n: ${s.sortierung} instead of $soll" } }
            e.filterGewaehlt?.let { soll ->
                val ist = s.filterFelder.filter { it.gewaehlt }.map { it.stufe }
                z.struktur("filter rank", soll == ist) { "$n: $ist instead of $soll" }
            }
            e.filterStellen?.let { soll ->
                z.struktur("filter price fields", soll == (s.filterMinStellen to s.filterMaxStellen)) { "$n: ${s.filterMinStellen}/${s.filterMaxStellen} instead of $soll" }
            }
            if (e.ort != Ort.MARKT && e.ort != Ort.MEINE_GEBOTE) return
            val gleich = e.zeilen.size == s.zeilen.size && e.zeilen.zip(s.zeilen).all { (w, i) -> kotlin.math.abs(w.oben - i.oben) <= 3 }
            z.struktur("rows found", gleich) { "$n: ${s.zeilen.map { it.oben }} instead of ${e.zeilen.map { it.oben }}" }
            if (!gleich) return
            for ((w, i) in e.zeilen.zip(s.zeilen)) {
                val wo = "$n row ${w.oben}"
                z.struktur("covered (message)", w.verdeckt == i.verdeckt) { "$wo: ${i.verdeckt} instead of ${w.verdeckt}" }
                if (w.verdeckt || i.verdeckt) continue
                z.struktur("rank badge", w.stufe == null || w.stufe == i.stufe) { "$wo: ${i.stufe} instead of ${w.stufe}" }
                z.struktur("clock/hammer", w.zeichen == i.zeichen) { "$wo: ${i.zeichen} instead of ${w.zeichen}" }
                z.struktur("red X", w.kreuz == i.ueberbotenKreuz) { "$wo: ${i.ueberbotenKreuz} instead of ${w.kreuz}" }
                if (w.zeit != "~") z.struktur("remaining time there?", (w.zeit != null) == i.hatRestzeit) { "$wo: hatRestzeit ${i.hatRestzeit}" }
                z.zahl("price", w.preis, i.preis, wo)
                z.zahl("buyout", w.sofort, i.sofortPreis, wo)
                if (w.zeit != null && w.zeit != "*" && w.zeit != "~" && !w.zeitUnlesbar) z.zahl("remaining time", zeitInSek(w.zeit), i.restzeitSek, wo)
                for (k in 0..2) z.zahl("stats (row ID)", w.werte[k], i.werte[k], "$wo stat ${k + 1}")
            }
        }

        fun tabelle(titel: String, z: Zaehlung): String {
            val sb = StringBuilder("== $titel ==\n")
            sb.append(String.format("%-22s %8s %10s %7s %6s\n", "field", "correct", "unreadable", "WRONG", "rate"))
            for ((f, w) in z.felder) {
                val n = w.sum()
                sb.append(String.format("%-22s %8d %10d %7d %5.0f%%\n", f, w[0], w[1], w[2], 100.0 * w[0] / n))
            }
            return sb.toString()
        }
    }

    @TestFactory
    fun `report images - place, rows, badges, icons, message are right, no number wrong`(): List<DynamicTest> =
        BerichtAblesung.alle.map { e ->
            DynamicTest.dynamicTest(e.name) {
                ZiffernLeser.zwischenspeicherLeeren()
                val z = Zaehlung()
                pruefe(e, Erkenner.erkenne(TestBilder.lade(e.name)), z)
                assertTrue(z.fehler.isEmpty(), z.fehler.joinToString("\n"))
            }
        }

    /**
     * Nov 11: "The bot skips most Aspens." Aspen = dark knight with a purple faction sign and
     * glowing purple eyes. Every Aspen row on the full-resolution images must be found and read COMPLETELY
     * (stats = row ID, badge, price, buyout, remaining time, clock). Plus other dark hero images.
     */
    @Test
    fun `Aspen and other dark hero images - every row found and read completely`() {
        val aspen = mapOf(
            "markt-rang-gebote" to listOf(314, 676), "markt-toast-erfolg-2" to listOf(314),
            "markt-rang-uhr-1" to listOf(476), "markt-rang-uhr-2" to listOf(314, 857),
            "meine-gebote-50-kreuze" to listOf(658, 839), "markt-aspen-58" to listOf(423),
            // other dark images: blue skin / dark hair (2413-2651-39), skull faction (2025-4446-30)
            "markt-aspen-58#dunkel" to listOf(967), "markt-rang-uhr-1#dunkel" to listOf(295),
        )
        var n = 0
        for ((schluessel, oben) in aspen) {
            val name = schluessel.substringBefore('#')
            val e = Ablesung.alle.first { it.name == name }
            ZiffernLeser.zwischenspeicherLeeren()
            val s = Erkenner.erkenne(TestBilder.lade(name))
            for (t in oben) {
                val w = e.zeilen.first { it.oben == t }
                val z = s.zeilen.firstOrNull { kotlin.math.abs(it.oben - t) <= 3 }
                    ?: error("$name: row $t (${w.werte}) not found – found ${s.zeilen.map { it.oben }}")
                val wo = "$name row $t"
                assertEquals(w.werte.joinToString("-"), z.id, "$wo: row ID")
                assertEquals(w.stufe, z.stufe, "$wo: rank badge")
                assertEquals(w.preis, z.preis, "$wo: price")
                assertEquals(w.sofort, z.sofortPreis, "$wo: buyout")
                assertEquals(w.zeit?.let { zeitInSek(it) }, z.restzeitSek, "$wo: remaining time")
                assertEquals(w.zeichen, z.zeichen, "$wo: clock/hammer")
                assertEquals(w.kreuz, z.ueberbotenKreuz, "$wo: red X")
                n++
            }
        }
        // On the half-size report images: every Aspen row found, badge right (numbers: never wrong – see above).
        val aspenIds = setOf("3267-3360-33", "3074-3169-24", "2258-3337-37", "2468-2911-29", "2782-2587-27", "2906-2692-31",
            "2272-2844-45", "2060-2048-17", "3534-3170-25", "886-1354-14")
        for (e in BerichtAblesung.alle) {
            val zeilen = e.zeilen.filter { !it.verdeckt && it.werte.joinToString("-") in aspenIds }
            if (zeilen.isEmpty()) continue
            ZiffernLeser.zwischenspeicherLeeren()
            val s = Erkenner.erkenne(TestBilder.lade(e.name))
            for (w in zeilen) {
                val z = s.zeilen.firstOrNull { kotlin.math.abs(it.oben - w.oben) <= 3 } ?: error("${e.name}: Aspen row ${w.oben} not found")
                assertEquals(w.stufe, z.stufe, "${e.name} row ${w.oben}: rank badge")
                assertEquals(w.zeichen, z.zeichen, "${e.name} row ${w.oben}: clock/hammer")
                n++
            }
        }
        assertTrue(n >= 30, "only $n Aspen rows checked")
    }

    /** With TEMPO=1: how long does the whole recognition take per image (without cache)? */
    @Test
    fun `measure recognition speed`() {
        assumeTrue(System.getenv("TEMPO") == "1", "Only with TEMPO=1")
        val bilder = (Ablesung.alle.filter { it.ort == Ort.MARKT || it.ort == Ort.MEINE_GEBOTE }).map { TestBilder.lade(it.name) }
        repeat(3) { for (b in bilder) { ZiffernLeser.zwischenspeicherLeeren(); Erkenner.erkenne(b) } } // warm up
        val messungen = (1..25).map {
            val t0 = System.nanoTime()
            for (b in bilder) { ZiffernLeser.zwischenspeicherLeeren(); Erkenner.erkenne(b) }
            (System.nanoTime() - t0) / 1e6 / bilder.size
        }.sorted()
        println("TEMPO: ${bilder.size} list images, median %.2f ms per image (min %.2f)".format(messungen[12], messungen[0]))
    }

    /** With GENAUIGKEIT=1: how large are dropped pieces next to the digits on real images? (for RESTE_ANTEIL) */
    @Test
    fun `measure leftovers next to the digits`() {
        assumeTrue(System.getenv("GENAUIGKEIT") == "1", "Only with GENAUIGKEIT=1")
        val werte = ArrayList<Pair<Float, String>>()
        fun miss(b: Bild, r: Rechteck, s: Schrift, minH: Int, soll: String, wo: String) {
            val z = ZiffernLeser.zerlege(b, r, s, minH)
            if (z.glyphen.isEmpty()) return
            val ohne = z.glyphen.joinToString("") { g -> ZiffernLeser.vergleiche(g, s)?.first?.toString() ?: "?" }
            werte += ZiffernLeser.resteAnteil(z) to "$wo $s should be $soll blobs $ohne"
        }
        for (e in Ablesung.alle + BerichtAblesung.alle) {
            val b = TestBilder.lade(e.name).aufReferenz()
            e.sg?.let { miss(b, Erkenner.SG_FELD, Schrift.SG, 18, it.toString(), e.name) }
            e.aktuellesGebot?.let { miss(b, Erkenner.AKTUELLES_GEBOT_FELD, Schrift.HELL, 14, it.toString(), e.name) }
            for (w in e.zeilen.filter { !it.verdeckt }) {
                val wo = "${e.name} z${w.oben}"
                miss(b, Erkenner.preisFeld(w.oben), Schrift.WEISS, 14, w.preis.toString(), wo)
                w.sofort?.let { miss(b, Erkenner.sofortFeld(w.oben), Schrift.WEISS, 14, it.toString(), wo) }
                w.zeit?.let { miss(b, Erkenner.zeitFeld(w.oben), Schrift.GRUEN, 14, it, wo) }
                for (k in 0..2) miss(b, Erkenner.wertFeld(w.oben, k), Schrift.ROT, 16, w.werte[k].toString(), wo)
            }
        }
        for (p in Lernziffern.alle.filter { it.art != "bestaetigen" }) miss(p.bild, p.feld, p.schrift, p.minHoehe, p.wert, p.datei)
        println("== Leftovers: ${werte.size} fields, the largest shares ==")
        val (halb, voll) = werte.partition { it.second.startsWith("berichte/") }
        println("-- full resolution (${voll.size} fields) --")
        voll.sortedByDescending { it.first }.take(12).forEach { println("%.3f %s".format(it.first, it.second)) }
        println("-- half resolution (${halb.size} fields) --")
        halb.sortedByDescending { it.first }.take(6).forEach { println("%.3f %s".format(it.first, it.second)) }
    }

    /** With GENAUIGKEIT=1: table correct / not readable / wrong per field and image source. */
    @Test
    fun `accuracy per field`() {
        assumeTrue(System.getenv("GENAUIGKEIT") == "1", "Only with GENAUIGKEIT=1")
        val quellen = listOf(
            "test images full, used for learning" to Ablesung.alle.filter { it.lernen },
            "test images full, NOT learned" to Ablesung.alle.filter { !it.lernen },
            "report images half resolution" to BerichtAblesung.alle,
        )
        val aus = StringBuilder()
        for ((titel, liste) in quellen) {
            val z = Zaehlung()
            for (e in liste) {
                ZiffernLeser.zwischenspeicherLeeren()
                pruefe(e, Erkenner.erkenne(TestBilder.lade(e.name)), z)
            }
            aus.append(tabelle(titel, z))
            z.fehler.forEach { aus.append("  ").append(it).append('\n') }
        }
        val z = Zaehlung()
        for (p in Lernziffern.alle) {
            ZiffernLeser.zwischenspeicherLeeren()
            z.zahl("crop ${p.art}", p.wert, Lernziffern.lies(p), p.datei)
        }
        aus.append(tabelle("learning digit crops full (error reports)", z))
        z.falsche.forEach { aus.append("  ").append(it).append('\n') }
        println(aus)
    }
}
