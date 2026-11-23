package io.github.hustlebot.ihauctionbot.kern.bild

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Nov 11: crops from the error reports from the phone (folder testbilder/ziffern, full resolution) that the
 * bot could NOT read back then. The correct value is in the file name (read off by hand):
 * <bericht>_<nr>_<art>_<wert>.png – bericht: time of the error report, art: preis, sofort, zeit, aktuell,
 * bestaetigen, wert, zaehler.
 * Nov 12: "zaehler" = "Bidding Items: N" in My Biddings (crop exactly Erkenner.ZAEHLER_FELD). On the phone
 * "49" was not readable: the 9 was one pixel row lower than on the JPG test images and therefore too similar to the 0.
 */
object Lernziffern {
    data class Probe(val datei: String, val bericht: String, val art: String, val wert: String, val bild: Bild) {
        val schrift: Schrift
            get() = when (art) {
                "preis", "sofort", "zaehler" -> Schrift.WEISS
                "zeit" -> Schrift.GRUEN
                "aktuell" -> Schrift.HELL
                "bestaetigen" -> Schrift.BRAUN
                "wert" -> Schrift.ROT
                // Nov 23 (review round 3): the bid field in the Bid window (crops "..._feld_54.png").
                "feld" -> Schrift.FELD
                else -> error("unknown kind $art")
            }
        val minHoehe: Int get() = if (art == "bestaetigen" || art == "wert") 16 else 14

        /** The whole crop is the field. */
        val feld: Rechteck get() = Rechteck(0, 0, bild.breite, bild.hoehe)
    }

    private val ordner: File = listOf(File("../testbilder/ziffern"), File("testbilder/ziffern"), File("idle-heroes-bot/testbilder/ziffern"))
        .first { it.isDirectory }

    val alle: List<Probe> by lazy {
        ordner.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }.map { f ->
            val teile = f.nameWithoutExtension.split("_")
            val img = ImageIO.read(f)
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            Probe(f.name, teile[0], teile[2], teile[3], Bild(img.width, img.height, px))
        }
    }

    /** Reads a sample the way the recognizer reads the field (confirm: only the last blobs = the number). */
    fun lies(p: Probe): String? {
        if (p.art == "bestaetigen") {
            // The crop is exactly Erkenner.BESTAETIGEN_FELD – put it into an empty image at the same spot.
            val f = Erkenner.BESTAETIGEN_FELD
            val gross = IntArray(2712 * 1220)
            for (y in 0 until p.bild.hoehe) for (x in 0 until p.bild.breite) {
                gross[(f.y0 + y) * 2712 + f.x0 + x] = p.bild.pixel[y * p.bild.breite + x]
            }
            return Erkenner.bestaetigenPreis(Bild(2712, 1220, gross))?.toString()
        }
        return ZiffernLeser.lies(p.bild, p.feld, p.schrift, p.minHoehe)?.text
    }
}

class LernziffernTest {

    /** Nov 11: every number the bot could not read on the phone is now read correctly. */
    @Test
    fun `all crops from the error reports are read correctly`() {
        val falsch = Lernziffern.alle.mapNotNull { p ->
            ZiffernLeser.zwischenspeicherLeeren()
            val gelesen = Lernziffern.lies(p)
            if (gelesen == p.wert) null else "${p.datei}: read $gelesen"
        }
        assertEquals(emptyList<String>(), falsch)
    }

    @Test
    fun `diagnosis of the learning digits`() {
        assumeTrue(System.getenv("DIAGNOSE") == "1", "Only with DIAGNOSE=1")
        var gut = 0
        for (p in Lernziffern.alle) {
            ZiffernLeser.zwischenspeicherLeeren()
            val gl = ZiffernLeser.glyphen(p.bild, p.feld, p.schrift, p.minHoehe)
            val teile = gl.map { g ->
                val v = ZiffernLeser.vergleiche(g, p.schrift)
                "${v?.first}(${"%.3f".format(v?.second ?: 9f)} ${g.breite}x${g.hoehe})"
            }
            val gelesen = Lernziffern.lies(p)
            if (gelesen == p.wert) gut++
            println("${if (gelesen == p.wert) "OK  " else "FAIL"} ${p.datei} ${p.bild.breite}x${p.bild.hoehe} expected ${p.wert} read $gelesen  ${teile.joinToString(" ")}")
        }
        println("Correct: $gut of ${Lernziffern.alle.size}")
    }

    /** DIAG_BILD=<path> DIAG_FELD=x0,y0,x1,y1 (reference) DIAG_SCHRIFT=ROT DIAG_MINH=16 */
    @Test
    fun `diagnosis of a field`() {
        val pfad = System.getenv("DIAG_BILD")
        assumeTrue(pfad != null, "Only with DIAG_BILD")
        val img = ImageIO.read(File(pfad!!))
        val b = Bild(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width)).aufReferenz()
        val (x0, y0, x1, y1) = System.getenv("DIAG_FELD")!!.split(",").map { it.trim().toInt() }
        val s = Schrift.valueOf(System.getenv("DIAG_SCHRIFT") ?: "WEISS")
        val r = Rechteck(x0, y0, x1, y1)
        val minH = System.getenv("DIAG_MINH")?.toInt() ?: 14
        for (g in ZiffernLeser.glyphen(b, r, s, minH)) {
            val v = ZiffernLeser.vergleiche(g, s)
            println("Blob x${g.x0}-${g.x1} ${g.breite}x${g.hoehe} → ${v?.first} ${"%.3f".format(v?.second ?: 9f)}")
        }
        println("read: ${ZiffernLeser.lies(b, r, s, minH)?.text}")
    }

    @Test
    fun `diagnosis of other fonts`() {
        assumeTrue(System.getenv("DIAGNOSE") == "1", "Only with DIAGNOSE=1")
        for (p in Lernziffern.alle.filter { it.art == "aktuell" || it.art == "bestaetigen" }) {
            var gl = ZiffernLeser.glyphen(p.bild, p.feld, p.schrift, p.minHoehe)
            if (p.art == "bestaetigen") gl = gl.takeLast(p.wert.length)
            gl.forEachIndexed { i, g ->
                val zeile = Schrift.values().joinToString(" ") { s ->
                    val v = ZiffernVorlagen.fuer(s)
                    if (v.isEmpty()) "$s:-" else {
                        val m = g.merkmal()
                        val beste = v.groupBy { it.zeichen }.mapValues { (_, l) ->
                            l.minOf { t ->
                                var d = 0f
                                for (k in m.indices) { val e = m[k] - t.merkmal[k]; d += e * e }
                                d / m.size + 1.5f * (g.seite - t.seite) * (g.seite - t.seite)
                            }
                        }.entries.sortedBy { it.value }
                        "$s:${beste[0].key}(${"%.3f".format(beste[0].value)})/${beste.getOrNull(1)?.key}(${"%.3f".format(beste.getOrNull(1)?.value ?: 9f)})"
                    }
                }
                println("${p.datei} should be ${p.wert[i]} ${g.breite}x${g.hoehe} side ${"%.2f".format(g.seite)} → $zeile")
            }
        }
    }
}
