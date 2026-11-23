package io.github.hustlebot.ihauctionbot.attrappe

import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import io.github.hustlebot.ihauctionbot.kern.bild.Erkenner
import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck
import io.github.hustlebot.ihauctionbot.kern.bild.Schrift
import io.github.hustlebot.ihauctionbot.kern.bild.ZiffernLeser
import kotlin.math.abs

/**
 * Nov 11 (test in the emulator): the fake game builds its images from the REAL screenshots (testbilder/) –
 * cards, digits, clock, hammer, red X, messages. So the bot reads them exactly like the real game.
 * [Bilder] provides the screenshots (JVM: from the folder, Android: from the assets). Name without extension = "name.jpg",
 * otherwise the file (e.g. "ziffern/….png").
 */
fun interface Bilder {
    fun lade(name: String): Bild
}

/** A piece cut out of a screenshot. [maske] = only these pixels are drawn (null = all). */
class Stueck(val breite: Int, val hoehe: Int, val pixel: IntArray, val maske: BooleanArray? = null) {
    companion object {
        fun aus(b: Bild, x0: Int, y0: Int, x1: Int, y1: Int, maske: ((Int, Int) -> Boolean)? = null): Stueck {
            val br = x1 - x0
            val h = y1 - y0
            val px = IntArray(br * h) { i -> b.argb(x0 + i % br, y0 + i / br) }
            val m = maske?.let { f -> BooleanArray(br * h) { i -> f(x0 + i % br, y0 + i / br) } }
            return Stueck(br, h, px, m)
        }
    }
}

/** A digit (or a colon): the piece and where it lies relative to the number. */
class Zeichenbild(val stueck: Stueck, val rand: Int, val breite: Int, val dy: Int)

/** A font from real screenshots: digits, gap between two digits, colon if needed. */
class Schriftbild(val zeichen: Map<Char, Zeichenbild>, val abstand: Int) {
    fun kann(text: String): Boolean = text.all { it in zeichen }
}

/**
 * All pieces the fake game builds its images from. Built once (takes a while) and then only used.
 * Positions in reference pixels (2712 × 1220, as in the recognizer).
 */
class Atlas(private val bilder: Bilder) {

    val markt: Bild = bilder.lade("markt-c-minus")
    val meine: Bild = bilder.lade("meine-gebote-49")
    val bieten: Bild = bilder.lade("bid-fenster")
    val filter: Bild = bilder.lade("filter-fenster")
    val sortieren: Bild = bilder.lade("sortier-fenster")
    val laden: Bild = bilder.lade("laden-stern")
    val bestaetigen: Bild = bilder.lade("bestaetigen-18")

    /** A card (row) without an icon: from markt-c-minus, top edge 314. Crop [KARTE_X0]..[KARTE_X1], t-8..t+158. */
    val karte: Stueck = Stueck.aus(markt, KARTE_X0, 314 - KARTE_UEBER, KARTE_X1, 314 + KARTE_UNTER)

    /** Background between the cards (15 lines from the gap below the first card), gets tiled. */
    val luecke: Stueck = Stueck.aus(markt, KARTE_X0, 474, KARTE_X1, 489)
    val lueckeMeine: Stueck = Stueck.aus(meine, KARTE_X0, 374 + 160, KARTE_X1, 374 + 175)

    /** Clock (I am in front) and hammer (outbid) on the right of the row: 100 × 100 around (2045, t + 76). */
    val uhr: Stueck = Stueck.aus(meine, 1995, 374 + 26, 2095, 374 + 126)
    val hammer: Stueck = Stueck.aus(bilder.lade("markt-nach-gebot"), 1995, 314 + 26, 2095, 314 + 126)

    /** Red X at the top right (My Biddings), from meine-gebote-50-kreuze (row 477): x 2255..2332, t-14..t+46. */
    val kreuz: Stueck = Stueck.aus(bilder.lade("meine-gebote-50-kreuze"), 2255, 477 - 14, 2332, 477 + 46)

    /** "Bidding successful …" (over the list) and "Server status code:-7" (in the Bid window). */
    val erfolgsMeldung: Pair<Int, Stueck> = band(bilder.lade("markt-toast-erfolg-1"))
    val limitMeldung: Pair<Int, Stueck> = band(bilder.lade("limit-50-status-7"))

    // ---------- Fonts ----------

    val preis: Schriftbild
    val zeit: Schriftbild
    val werte: Schriftbild
    val aktuell: Schriftbild
    val zaehler: Schriftbild

    /** Where the numbers in a row start (x absolute, y relative to the top edge), from markt-c-minus row 314. */
    val preisStart: Pair<Int, Int>
    val zeitStart: Pair<Int, Int>
    val werteStart: List<Pair<Int, Int>>
    val aktuellStart: Pair<Int, Int>
    val zaehlerStart: Pair<Int, Int>

    init {
        val cMinus = markt
        val zeilen = listOf(314 to listOf("18", "28", "013530"), 495 to listOf("17", "36", "013934"), 676 to listOf("18", "25", "020603"), 857 to listOf("16", "60", "021716"))
        val werteText = mapOf(314 to listOf("1081", "1870", "8"), 495 to listOf("1454", "1142", "16"), 676 to listOf("1433", "889", "24"), 857 to listOf("1926", "709", "15"))
        val kreuze = bilder.lade("meine-gebote-50-kreuze")

        val p = Sammler(Schrift.WEISS, 14)
        for ((t, texte) in zeilen) {
            p.sammle(cMinus, Erkenner.preisFeld(t), texte[0], t)
            p.sammle(cMinus, Erkenner.sofortFeld(t), texte[1], t)
        }
        p.sammle(kreuze, Erkenner.preisFeld(477), "60", 477)
        p.sammle(kreuze, Erkenner.sofortFeld(477), "91", 477)
        p.sammle(kreuze, Erkenner.preisFeld(658), "61", 658)
        p.sammle(kreuze, Erkenner.sofortFeld(658), "124", 658)
        preis = p.fertig()
        preisStart = p.start(cMinus, Erkenner.preisFeld(314), 314)

        val z = Sammler(Schrift.GRUEN, 14, doppelpunkt = true)
        for ((t, texte) in zeilen) z.sammle(cMinus, Erkenner.zeitFeld(t), texte[2], t)
        z.sammle(bilder.lade("markt-nach-gebot"), Erkenner.zeitFeld(314), "013458", 314)
        zeit = z.fertig()
        zeitStart = z.start(cMinus, Erkenner.zeitFeld(314), 314)

        val w = Sammler(Schrift.ROT, 16)
        for ((t, texte) in werteText) for (i in 0..2) w.sammle(cMinus, Erkenner.wertFeld(t, i), texte[i], t)
        werte = w.fertig()
        werteStart = (0..2).map { w.start(cMinus, Erkenner.wertFeld(314, it), 314) }

        val a = Sammler(Schrift.HELL, 14)
        a.sammle(bieten, Erkenner.AKTUELLES_GEBOT_FELD, "18", 0)
        a.sammle(bilder.lade("limit-50-status-7"), Erkenner.AKTUELLES_GEBOT_FELD, "62", 0)
        a.sammle(bilder.lade("bid-fenster-b-ohne-buyout-1"), Erkenner.AKTUELLES_GEBOT_FELD, "53", 0)
        // Nov 11: the bot only bids when "Current Bid" is readable – so all digits: "Buyout Price"
        // in the Bid window (same font, gives 0 and 9) and crops from error reports (4 and 7).
        a.sammle(bieten, SOFORT_IM_FENSTER, "28", 0)
        a.sammle(bilder.lade("limit-50-status-7"), SOFORT_IM_FENSTER, "109", 0)
        for ((datei, text) in listOf("19-33-55_040_aktuell_54" to "54", "21-46-15_013_aktuell_57" to "57", "19-39-28_049_aktuell_56" to "56")) {
            val b = runCatching { bilder.lade("ziffern/$datei.png") }.getOrNull() ?: continue
            a.sammle(b, Rechteck(0, 0, b.breite, b.hoehe), text, 0)
        }
        aktuell = a.fertig()
        aktuellStart = a.start(bieten, Erkenner.AKTUELLES_GEBOT_FELD, 0)

        val c = Sammler(Schrift.WEISS, 14)
        c.sammle(meine, Erkenner.ZAEHLER_FELD, "49", 0)
        c.sammle(bilder.lade("meine-gebote-43"), Erkenner.ZAEHLER_FELD, "43", 0)
        c.sammle(kreuze, Erkenner.ZAEHLER_FELD, "50", 0)
        // Missing digits in the counter: from the price font (same typeface, similar size).
        zaehler = Schriftbild(preis.zeichen + c.fertig().zeichen, c.fertig().abstand)
        zaehlerStart = c.start(meine, Erkenner.ZAEHLER_FELD, 0)
    }

    /** Collects the digits of a font from screenshots with known text. */
    private class Sammler(val schrift: Schrift, val minHoehe: Int, val doppelpunkt: Boolean = false) {
        val zeichen = HashMap<Char, Zeichenbild>()
        val abstaende = ArrayList<Int>()

        fun sammle(b: Bild, feld: Rechteck, text: String, oben: Int) {
            val gl = ZiffernLeser.glyphen(b, feld, schrift, minHoehe)
            if (gl.size != text.length) return
            val linie = gl.minOf { it.y0 }
            gl.forEachIndexed { i, g ->
                if (text[i] !in zeichen) zeichen[text[i]] = schneide(b, g.x0, g.y0, g.x1, g.y1, g.y0 - linie)
                if (i > 0) abstaende += g.x0 - gl[i - 1].x1 - 1
            }
            // Colon (remaining time "01:35:30"): between the 2nd and 3rd digit.
            if (doppelpunkt && gl.size == 6 && ':' !in zeichen) {
                val l = gl[1]
                val r = gl[2]
                val x0 = l.x1 + 3
                val x1 = r.x0 - 3
                if (x1 > x0) {
                    val y0 = gl.minOf { it.y0 }
                    val y1 = gl.maxOf { it.y1 }
                    // Only the fill color inside the gap (otherwise edges of the neighboring digits would come along – the
                    // reader would see them as a "lost digit").
                    zeichen[':'] = schneide(b, x0, y0, x1, y1, 0)
                }
            }
        }

        /** Cut out a digit: fill color only inside the blob, plus the dark border around it. */
        private fun schneide(b: Bild, x0: Int, y0: Int, x1: Int, y1: Int, dy: Int, nurFarbe: Boolean = false): Zeichenbild {
            val r = RAND
            val grund = hintergrund(b, x0 - r, y0 - r, x1 + r, y1 + r)
            val s = Stueck.aus(b, x0 - r, y0 - r, x1 + r + 1, y1 + r + 1) { x, y ->
                val f = b.farbe(x, y)
                val fuellung = schrift.gehoert(f)
                val innen = x in x0..x1 && y in y0..y1
                when {
                    fuellung -> innen || nurFarbe
                    else -> abstand(b.argb(x, y), grund) > 60
                }
            }
            return Zeichenbild(s, r, x1 - x0 + 1, dy)
        }

        fun fertig(): Schriftbild {
            val gap = abstaende.sorted().let { if (it.isEmpty()) 3 else it[it.size / 2] }
            return Schriftbild(HashMap(zeichen), gap)
        }

        /** Where the first digit starts in the screenshot (x absolute, y relative to [oben]). */
        fun start(b: Bild, feld: Rechteck, oben: Int): Pair<Int, Int> {
            val gl = ZiffernLeser.glyphen(b, feld, schrift, minHoehe)
            return if (gl.isEmpty()) (feld.x0 + 4) to (feld.y0 + 4 - oben) else gl.first().x0 to (gl.minOf { it.y0 } - oben)
        }
    }

    /** Cut a message band out of a screenshot: (top edge, piece). */
    private fun band(b: Bild): Pair<Int, Stueck> {
        val m = Erkenner.meldung(b) ?: error("no message in the image")
        val halb = m.breite / 2 + 24
        val y0 = (m.yOben - 4).coerceAtLeast(0)
        val y1 = (m.yUnten + 5).coerceAtMost(b.hoehe)
        return y0 to Stueck.aus(b, 1356 - halb, y0, 1356 + halb, y1)
    }

    companion object {
        /** "Buyout Price: 28" in the Bid window – only the number (as when learning the digits). */
        val SOFORT_IM_FENSTER = Rechteck(1440, 785, 1640, 845)

        const val KARTE_X0 = 750
        const val KARTE_X1 = 2330
        const val KARTE_UEBER = 8
        const val KARTE_UNTER = 158
        const val RAND = 3

        /** Most common color at the edge of a crop (= background). */
        internal fun hintergrund(b: Bild, x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val zaehler = HashMap<Int, Int>()
            for (x in x0..x1) for (y in listOf(y0, y1)) zaehler.merge(grob(b.argb(x, y)), 1, Int::plus)
            for (y in y0..y1) for (x in listOf(x0, x1)) zaehler.merge(grob(b.argb(x, y)), 1, Int::plus)
            return zaehler.maxByOrNull { it.value }?.key ?: 0
        }

        private fun grob(p: Int): Int = (p shr 16 and 0xF0 shl 16) or (p shr 8 and 0xF0 shl 8) or (p and 0xF0)

        internal fun abstand(a: Int, b: Int): Int =
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
    }
}
