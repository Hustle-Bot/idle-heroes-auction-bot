package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Nov 11: tool for checking – reads any number of images with the recognizer and writes one line (JSON) per image
 * with everything that was recognized. Only with AUSZUG_LISTE=<file with image paths> and
 * AUSZUG_ZIEL=<output file>; in a normal test run nothing happens.
 */
class ErkennerAuszugTest {

    @Test
    fun `write recognizer extract`() {
        val liste = System.getenv("AUSZUG_LISTE")
        val ziel = System.getenv("AUSZUG_ZIEL")
        assumeTrue(liste != null && ziel != null, "Only with AUSZUG_LISTE and AUSZUG_ZIEL")
        val aus = StringBuilder()
        for (pfad in File(liste!!).readLines().filter { it.isNotBlank() }) {
            val img = ImageIO.read(File(pfad)) ?: error("Image $pfad not readable")
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            ZiffernLeser.zwischenspeicherLeeren()
            val s = Erkenner.erkenne(Bild(img.width, img.height, px))
            aus.append("{\"bild\":\"").append(pfad).append("\",").append(json(s)).append("}\n")
        }
        File(ziel!!).writeText(aus.toString())
    }

    companion object {
        fun json(s: Bildschirm): String {
            fun n(v: Any?) = if (v == null) "null" else if (v is String || v is Char) "\"$v\"" else v.toString()
            val zeilen = s.zeilen.joinToString(",") { z ->
                "{\"oben\":${z.oben},\"preis\":${n(z.preis)},\"sofort\":${n(z.sofortPreis)},\"restzeit\":${n(z.restzeitSek)}," +
                    "\"hatRestzeit\":${z.hatRestzeit},\"werte\":[${z.werte.joinToString(",") { n(it) }}],\"zeichen\":\"${z.zeichen}\"," +
                    "\"kreuz\":${z.ueberbotenKreuz},\"kreuzPunkt\":${z.kreuzPunkt?.let { "[${it.x},${it.y}]" } ?: "null"}," +
                    "\"stufe\":${n(z.stufe)},\"verdeckt\":${z.verdeckt}}"
            }
            val unlesbar = s.unlesbar.joinToString(",") { "\"${it.feld}@${it.kontext}\"" }
            val knoepfe = s.knoepfe.entries.joinToString(",") { "\"${it.key}\":[${it.value.x},${it.value.y}]" }
            val filter = s.filterFelder.filter { it.gewaehlt }.joinToString(",") { "\"${it.stufe}\"" }
            return "\"ort\":\"${s.ort}\",\"sg\":${n(s.sg)},\"laufendeGebote\":${n(s.laufendeGebote)},\"keineGebote\":${s.keineGebote}," +
                "\"aktuellesGebot\":${n(s.aktuellesGebot)},\"bestaetigenPreis\":${n(s.bestaetigenPreis)}," +
                "\"meldung\":${s.meldung?.let { "{\"breite\":${it.breite},\"kurz\":${it.kurz}}" } ?: "null"}," +
                "\"sortierung\":\"${s.sortierung}\",\"filterMinStellen\":${n(s.filterMinStellen)},\"filterMaxStellen\":${n(s.filterMaxStellen)}," +
                "\"filterGewaehlt\":[$filter],\"knoepfe\":{$knoepfe},\"zeilen\":[$zeilen],\"unlesbar\":[$unlesbar]"
        }
    }
}
