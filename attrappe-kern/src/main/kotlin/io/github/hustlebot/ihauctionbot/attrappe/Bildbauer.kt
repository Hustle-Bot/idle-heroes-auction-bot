package io.github.hustlebot.ihauctionbot.attrappe

import io.github.hustlebot.ihauctionbot.kern.bild.Bild
import io.github.hustlebot.ihauctionbot.kern.bild.Erkenner
import io.github.hustlebot.ihauctionbot.kern.bild.Rechteck

/** A row as the fake game shows it. */
data class Zeilenbild(
    val werte: List<Int>,
    val preis: Int,
    val restSek: Int,
    val zeichen: Zeichenart = Zeichenart.KEINS,
    val kreuz: Boolean = false,
)

enum class Zeichenart { KEINS, UHR, HAMMER }

/** Canvas in reference size (2712 × 1220); [clipOben] / [clipUnten] limit the drawing (list). */
class Leinwand(base: Bild) {
    val breite = base.breite
    val hoehe = base.hoehe
    val pixel: IntArray = base.pixel.copyOf()
    var clipOben = 0
    var clipUnten = hoehe

    fun male(s: Stueck, x: Int, y: Int) {
        for (sy in 0 until s.hoehe) {
            val ty = y + sy
            if (ty < clipOben || ty >= clipUnten || ty < 0 || ty >= hoehe) continue
            for (sx in 0 until s.breite) {
                val tx = x + sx
                if (tx < 0 || tx >= breite) continue
                val i = sy * s.breite + sx
                if (s.maske != null && !s.maske[i]) continue
                pixel[ty * breite + tx] = s.pixel[i]
            }
        }
    }

    fun fuelle(x0: Int, y0: Int, x1: Int, y1: Int, farbe: (Int, Int) -> Int) {
        for (y in maxOf(y0, clipOben, 0) until minOf(y1, clipUnten, hoehe)) {
            for (x in maxOf(x0, 0) until minOf(x1, breite)) pixel[y * breite + x] = farbe(x, y)
        }
    }

    fun farbe(x: Int, y: Int): Int = pixel[y.coerceIn(0, hoehe - 1) * breite + x.coerceIn(0, breite - 1)]

    fun alsBild(): Bild = Bild(breite, hoehe, pixel.copyOf())
}

/**
 * Nov 11: builds the images of the fake game from the [Atlas] (real screenshot pieces). Positions as in the real game
 * (reference phone, a Motorola, 2712 × 1220).
 */
class Bildbauer(val atlas: Atlas) {

    /** Bidding list: rows from top edge 314, every 181 pixels, pushed up by [versatz]. */
    fun markt(zeilen: List<Zeilenbild>, versatz: Int, meldung: Boolean): Bild {
        val l = Leinwand(atlas.markt)
        liste(l, atlas.luecke, zeilen, MARKT_ERSTE, versatz, MARKT_OBEN)
        if (meldung) l.male(atlas.erfolgsMeldung.second, 1356 - atlas.erfolgsMeldung.second.breite / 2, atlas.erfolgsMeldung.first)
        return l.alsBild()
    }

    /** My Biddings: rows from top edge 374, counter "Bidding Items: N". */
    fun meineGebote(zeilen: List<Zeilenbild>, versatz: Int, zaehler: Int): Bild {
        val l = Leinwand(atlas.meine)
        liste(l, atlas.lueckeMeine, zeilen, MEINE_ERSTE, versatz, MEINE_OBEN)
        // Counter: paint over the old text (color to the left of it), new number.
        val f = Erkenner.ZAEHLER_FELD
        val grund = l.farbe(f.x0 - 4, (f.y0 + f.y1) / 2)
        l.fuelle(f.x0, f.y0, f.x1 + 30, f.y1) { _, _ -> grund }
        zahl(l, atlas.zaehler, zaehler.toString(), atlas.zaehlerStart.first, atlas.zaehlerStart.second)
        return l.alsBild()
    }

    /** Bid window with "Current Bid: [aktuell]" (null = do not draw the number) and the "-7" message if needed. */
    fun bieten(aktuell: Int?, minus7: Boolean): Bild {
        val l = Leinwand(atlas.bieten)
        val f = Erkenner.AKTUELLES_GEBOT_FELD
        val grund = l.farbe(f.x1 + 4, (f.y0 + f.y1) / 2)
        l.fuelle(f.x0, f.y0, f.x1, f.y1) { _, _ -> grund }
        val text = aktuell?.toString()
        if (text != null && atlas.aktuell.kann(text)) zahl(l, atlas.aktuell, text, atlas.aktuellStart.first, atlas.aktuellStart.second)
        if (minus7) l.male(atlas.limitMeldung.second, 1356 - atlas.limitMeldung.second.breite / 2, atlas.limitMeldung.first)
        return l.alsBild()
    }

    /** Filter window; [min]/[max] = text in the price fields (null = leave the field empty, e.g. because Android draws it). */
    fun filter(min: String?, max: String?): Bild {
        val l = Leinwand(atlas.filter)
        feldLeeren(l, FILTER_MIN)
        feldLeeren(l, FILTER_MAX)
        min?.let { feldText(l, FILTER_MIN, it) }
        max?.let { feldText(l, FILTER_MAX, it) }
        return l.alsBild()
    }

    /**
     * "Enter Secondary Password" (there is no phone screenshot of it – rebuilt from the confirm window): white
     * input field in the middle, yellow "Forgot Password" on the left (never tap!), yellow "Verify" on the right.
     * [sterne] = this many dots in the field (null = leave the field empty, e.g. because Android draws it).
     */
    fun passwort(sterne: Int?): Bild {
        val l = Leinwand(atlas.bestaetigen)
        // Color the left (red) button yellow.
        l.fuelle(850, 720, 1330, 860) { x, y ->
            val p = l.farbe(x, y)
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            if (r > 220 && g in 80..160 && b < 110) GELB else p
        }
        val f = PASSWORT_FELD
        l.fuelle(f.x0, f.y0, f.x1, f.y1) { _, _ -> WEISS }
        if (sterne != null) {
            var x = f.x0 + 20
            repeat(sterne) {
                l.fuelle(x, 622, x + 14, 638) { _, _ -> DUNKEL }
                x += 26
            }
        }
        return l.alsBild()
    }

    fun sortieren(): Bild = atlas.sortieren
    fun laden(): Bild = atlas.laden

    // ---------- Pieces ----------

    private fun liste(l: Leinwand, luecke: Stueck, zeilen: List<Zeilenbild>, erste: Int, versatz: Int, oben: Int) {
        l.clipOben = oben
        l.clipUnten = l.hoehe
        // List background: tile the gap stripes.
        var y = oben
        while (y < l.hoehe) {
            l.male(luecke, Atlas.KARTE_X0, y)
            y += luecke.hoehe
        }
        zeilen.forEachIndexed { i, z ->
            val t = erste + i * ABSTAND - versatz
            if (t + Atlas.KARTE_UNTER < oben || t - Atlas.KARTE_UEBER >= l.hoehe) return@forEachIndexed
            zeile(l, z, t)
        }
        l.clipOben = 0
    }

    private fun zeile(l: Leinwand, z: Zeilenbild, t: Int) {
        l.male(atlas.karte, Atlas.KARTE_X0, t - Atlas.KARTE_UEBER)
        // Paint over the old numbers of the template (card color from the same height, x = 1530: the card is empty there).
        for (r in listOf(Erkenner.preisFeld(t), Erkenner.sofortFeld(t), Erkenner.zeitFeld(t)) + (0..2).map { Erkenner.wertFeld(t, it) }) {
            leeren(l, r)
        }
        // Stats (attack, HP, speed) – the bot recognizes the copy by them.
        z.werte.forEachIndexed { i, w ->
            val (x, dy) = atlas.werteStart[i]
            zahl(l, atlas.werte, w.toString(), x, t + dy)
        }
        zahl(l, atlas.preis, z.preis.toString(), atlas.preisStart.first, t + atlas.preisStart.second)
        val s = z.restSek.coerceAtLeast(0)
        val zeitText = "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        zahl(l, atlas.zeit, zeitText, atlas.zeitStart.first, t + atlas.zeitStart.second)
        when (z.zeichen) {
            Zeichenart.UHR -> l.male(atlas.uhr, 1995, t + 26)
            Zeichenart.HAMMER -> l.male(atlas.hammer, 1995, t + 26)
            Zeichenart.KEINS -> Unit
        }
        if (z.kreuz) l.male(atlas.kreuz, 2255, t - 14)
    }

    private fun leeren(l: Leinwand, r: Rechteck) {
        l.fuelle(r.x0 - 2, r.y0 - 2, r.x1 + 2, r.y1 + 2) { _, y -> l.farbe(1530, y) }
    }

    /** Draw a number (or remaining time with colons) from the screenshot digits, starting on the left at [x], top edge [y]. */
    private fun zahl(l: Leinwand, schrift: Schriftbild, text: String, x: Int, y: Int) {
        var cx = x
        for (c in text) {
            val zb = schrift.zeichen[c] ?: continue
            l.male(zb.stueck, cx - zb.rand, y + zb.dy - zb.rand)
            cx += zb.breite + schrift.abstand
        }
    }

    private fun feldLeeren(l: Leinwand, f: Rechteck) {
        l.fuelle(f.x0, f.y0, f.x1, f.y1) { _, _ -> WEISS }
    }

    /** Text in the price field: dark blocks like digits (the recognizer only counts the blocks). */
    private fun feldText(l: Leinwand, f: Rechteck, text: String) {
        var x = f.x0 + 14
        for (c in text) {
            l.fuelle(x, 562, x + 16, 608) { _, _ -> DUNKEL }
            x += 26
        }
    }

    companion object {
        const val MARKT_ERSTE = 314
        const val MARKT_OBEN = 300
        const val MEINE_ERSTE = 374
        const val MEINE_OBEN = 365
        const val ABSTAND = 181
        const val WEISS = 0xFFFFFFFF.toInt()
        const val DUNKEL = 0xFF3A2A20.toInt()

        const val GELB = 0xFFF6C951.toInt()

        /** Input field in the secondary password window (the bot taps at (1356, 630)). */
        val PASSWORT_FELD = Rechteck(1150, 598, 1562, 662)

        /** Buttons in the secondary password window: "Forgot Password" (left, forbidden) and "Verify" (right). */
        val PASSWORT_VERGESSEN = Rechteck(850, 720, 1330, 860)
        val PASSWORT_OK = Rechteck(1380, 720, 1860, 860)

        /** Inner areas of the price fields in the filter window (that is where the recognizer counts the characters). */
        val FILTER_MIN = Rechteck(1026, 550, 1280, 620)
        val FILTER_MAX = Rechteck(1433, 550, 1686, 620)
    }
}
