package io.github.hustlebot.ihauctionbot.kern.bild

/** A rectangle in the image (right/bottom edge not included). */
data class Rechteck(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val breite: Int get() = x1 - x0
    val hoehe: Int get() = y1 - y0
}

/**
 * The fonts of the numbers in the game. Each has its own fill color – that is how the bot separates
 * the digits from the background.
 */
enum class Schrift {
    /** White with a black outline: prices in the list, "Bidding Items: 49". */
    WEISS,

    /** White on a dark bar: SG balance at the top right (a slightly different font than the prices). */
    SG,

    /** Light beige with a brown outline: "Current Bid: 18" in the Bid window. */
    HELL,

    /** Green with a black outline: remaining time "01:35:30". */
    GRUEN,

    /** Dark red: hero stats (attack, HP, speed) in the list. */
    ROT,

    /** Brown: "Starry Gems spent: 18" in the confirm window. */
    BRAUN,

    /**
     * Nov 23 (review round 3): thin brown digits on the white input fields – the bid field in the Bid window ("54" next to
     * "Current Bid: 53") and the price fields of the filter window (same font and size, ~25 px high).
     */
    FELD,
    ;

    /** Does this pixel belong to the fill of a digit? */
    fun gehoert(f: Farbe): Boolean = gehoert(f.r, f.g, f.b)

    /** Nov 11 (speed): the same directly from the primary colors, without creating a Farbe object for every pixel. */
    fun gehoert(r: Int, g: Int, b: Int): Boolean = when (this) {
        WEISS, SG -> r > 205 && g > 205 && b > 212 && maxOf(r, g, b) - minOf(r, g, b) < 35
        HELL -> (r * 30 + g * 59 + b * 11) / 100 > 205 && r >= b
        GRUEN -> g > 165 && g - r > 40 && g - b > 70
        ROT -> r in 100..215 && g < 110 && b < 110 && r - g > 50
        BRAUN -> (r * 30 + g * 59 + b * 11) / 100 < 125 && r > g && r - b > 40
        // Fill ~(140, 100, 72) with soft edges on pure white; the white of the field (≥ 240) never counts.
        FELD -> (r * 30 + g * 59 + b * 11) / 100 < 200 && r > g && r - b > 25
    }
}

/** A connected blob (one digit or a punctuation mark). */
class Glyphe(val x0: Int, val y0: Int, val x1: Int, val y1: Int, private val punkte: IntArray) {
    val breite: Int get() = x1 - x0 + 1
    val hoehe: Int get() = y1 - y0 + 1
    val anzahl: Int get() = punkte.size / 2

    /** Feature for comparing: fill level in a 10 × 14 grid. */
    fun merkmal(): FloatArray {
        val zaehler = IntArray(RASTER_B * RASTER_H)
        var i = 0
        while (i < punkte.size) {
            val gx = ((punkte[i] - x0) * RASTER_B / breite).coerceIn(0, RASTER_B - 1)
            val gy = ((punkte[i + 1] - y0) * RASTER_H / hoehe).coerceIn(0, RASTER_H - 1)
            zaehler[gy * RASTER_B + gx]++
            i += 2
        }
        val zelle = (breite.toFloat() / RASTER_B) * (hoehe.toFloat() / RASTER_H)
        return FloatArray(zaehler.size) { (zaehler[it] / zelle).coerceIn(0f, 1f) }
    }

    val seite: Float get() = breite.toFloat() / hoehe

    companion object {
        const val RASTER_B = 10
        const val RASTER_H = 14
    }
}

/** A learned digit (from real game screenshots). */
class Vorlage(val zeichen: Char, val seite: Float, werte: String) {
    val merkmal: FloatArray = FloatArray(werte.length) { (werte[it] - '0') / 9f }
}

/** Result of reading a number. */
data class Gelesen(val text: String, val schlechtester: Float)

/**
 * Reads numbers from an image area: separate the fill color, find blobs, compare each blob with
 * the learned digits. If a digit is not recognized reliably, null is returned –
 * the bot never guesses.
 */
object ZiffernLeser {
    /** From this distance on, a digit counts as not recognized. */
    const val SCHWELLE = 0.075f

    /**
     * Where the learned digits come from. Only replaceable for tests (Nov 11: cross-check – learn without the images
     * of an error report and check that its numbers are still never misread).
     */
    internal var vorlagenQuelle: (Schrift) -> List<Vorlage> = { ZiffernVorlagen.fuer(it) }

    /** All blobs of the font in the area, from left to right (small punctuation marks are already removed). */
    fun glyphen(bild: Bild, bereich: Rechteck, schrift: Schrift, minHoehe: Int = 14): List<Glyphe> =
        zerlege(bild, bereich, schrift, minHoehe).glyphen

    /** A dropped blob (too small or too low for a digit): position and number of pixels. */
    class Rest(val x0: Int, val x1: Int, val y0: Int, val y1: Int, val anzahl: Int)

    /** The digit blobs and the dropped leftovers (without the piece cut off at the left edge). */
    class Zerlegung(val glyphen: List<Glyphe>, val reste: List<Rest>)

    /**
     * Nov 11: "45" was read as "4" – the 5 had fallen apart into many small pieces (paler), which were each
     * dropped as "too small". Then a digit is missing and the number is WRONG instead of unreadable.
     * So: if dropped pieces lie next to or between the digits (at their height) that together make up at least
     * [RESTE_ANTEIL] of a digit, the number counts as not readable. The colons of the remaining time are much
     * smaller (measured on all real screenshots: at most ≈ 0.2 of a digit).
     */
    fun resteVerdaechtig(z: Zerlegung): Boolean = resteAnteil(z) >= RESTE_ANTEIL

    /** Largest share (measured against one digit) that dropped pieces have in a gap next to the digits. */
    fun resteAnteil(z: Zerlegung): Float {
        val gl = z.glyphen
        if (gl.isEmpty() || z.reste.isEmpty()) return 0f
        val ziffer = gl.map { it.anzahl }.sorted()[gl.size / 2].toFloat()
        val breite = gl.maxOf { it.breite }
        val oben = gl.minOf { it.y0 }
        val unten = gl.maxOf { it.y1 }
        // Gaps: left (one digit width), between every two digits, right (two digit widths)
        val grenzen = ArrayList<IntArray>()
        grenzen += intArrayOf(gl.first().x0 - breite, gl.first().x0 - 1)
        for (i in 0 until gl.size - 1) grenzen += intArrayOf(gl[i].x1 + 1, gl[i + 1].x0 - 1)
        grenzen += intArrayOf(gl.last().x1 + 1, gl.last().x1 + 2 * breite)
        var groesster = 0f
        for (g in grenzen) {
            var summe = 0
            for (r in z.reste) {
                val mx = (r.x0 + r.x1) / 2
                val my = (r.y0 + r.y1) / 2
                if (mx in g[0]..g[1] && my in oben..unten) summe += r.anzahl
            }
            if (summe / ziffer > groesster) groesster = summe / ziffer
        }
        return groesster
    }

    /** From this share of a digit on, dropped pieces next to the digits count as a lost digit. */
    const val RESTE_ANTEIL = 0.35f

    fun zerlege(bild: Bild, bereich: Rechteck, schrift: Schrift, minHoehe: Int = 14): Zerlegung {
        val x0 = bereich.x0.coerceIn(0, bild.breite)
        val x1 = bereich.x1.coerceIn(0, bild.breite)
        val y0 = bereich.y0.coerceIn(0, bild.hoehe)
        val y1 = bereich.y1.coerceIn(0, bild.hoehe)
        val b = x1 - x0
        val h = y1 - y0
        if (b <= 0 || h <= 0) return Zerlegung(emptyList(), emptyList())
        val maske = BooleanArray(b * h)
        val bildPixel = bild.pixel
        for (y in 0 until h) {
            val basis = (y0 + y) * bild.breite + x0
            for (x in 0 until b) {
                val p = bildPixel[basis + x]
                maske[y * b + x] = schrift.gehoert(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
            }
        }
        val besucht = BooleanArray(b * h)
        val alle = ArrayList<Glyphe>()
        val reste = ArrayList<Rest>()
        val stapel = IntArray(b * h)
        // Nov 11 (speed): collect the points of a blob in a fixed buffer (before: a list of individually
        // boxed numbers – a lot of memory work for nothing).
        var puffer = IntArray(512)
        for (start in maske.indices) {
            if (!maske[start] || besucht[start]) continue
            var oben = 0
            stapel[oben++] = start
            besucht[start] = true
            var n = 0
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            while (oben > 0) {
                val p = stapel[--oben]
                val px = p % b
                val py = p / b
                if (n + 2 > puffer.size) puffer = puffer.copyOf(puffer.size * 2)
                puffer[n++] = x0 + px
                puffer[n++] = y0 + py
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx !in 0 until b || ny !in 0 until h) continue
                    val q = ny * b + nx
                    if (maske[q] && !besucht[q]) {
                        besucht[q] = true
                        stapel[oben++] = q
                    }
                }
            }
            // Cut off at the left edge (usually a piece of the gem image in front of it) – drop it.
            if (minX == 0) continue
            if (n / 2 < 10) {
                reste += Rest(x0 + minX, x0 + maxX, y0 + minY, y0 + maxY, n / 2)
                continue
            }
            alle += Glyphe(x0 + minX, y0 + minY, x0 + maxX, y0 + maxY, puffer.copyOf(n))
        }
        if (alle.isEmpty()) return Zerlegung(emptyList(), reste)
        val groesste = alle.maxOf { it.hoehe }
        val (gut, klein) = alle.partition { it.hoehe >= minHoehe && it.hoehe >= groesste * 0.6 }
        for (g in klein) reste += Rest(g.x0, g.x1, g.y0, g.y1, g.anzahl)
        return Zerlegung(gut.sortedBy { it.x0 }, reste)
    }

    /** Best matching digit and the distance to it. */
    fun vergleiche(g: Glyphe, schrift: Schrift): Pair<Char, Float>? {
        val vorlagen = vorlagenQuelle(schrift)
        if (vorlagen.isEmpty()) return null
        val beste = abstaende(g, vorlagen)
        // Best and second-best digit (for each digit the smallest distance to one of its templates)
        var erstes = -1
        var zweites = -1
        for (z in beste.indices) {
            if (beste[z] == Float.MAX_VALUE) continue
            if (erstes < 0 || beste[z] < beste[erstes]) {
                zweites = erstes
                erstes = z
            } else if (zweites < 0 || beste[z] < beste[zweites]) {
                zweites = z
            }
        }
        if (erstes < 0) return null
        val zeichen = '0' + erstes
        // Too similar to another digit (e.g. 6 and 8)? Then better "not recognized".
        if (zweites >= 0 && beste[erstes] > 0.015f && beste[zweites] < beste[erstes] * 1.6f) {
            return zeichen to maxOf(beste[erstes], SCHWELLE + 0.001f)
        }
        return zeichen to beste[erstes]
    }

    /**
     * Smallest distance to each digit 0–9 (Float.MAX_VALUE = no template, or certainly not among the two best).
     * Nov 11 (speed, now up to 12 templates per digit): a template is not compared to the end as soon as it can
     * beat neither the best distance so far of the same digit nor the second best of all digits. The best and
     * second-best digit and their distances are exactly the same as with the full comparison – just faster.
     */
    private fun abstaende(g: Glyphe, vorlagen: List<Vorlage>): FloatArray {
        val m = g.merkmal()
        val s = g.seite
        val n = m.size
        val beste = FloatArray(10) { Float.MAX_VALUE }
        var min1 = Float.MAX_VALUE
        var min2 = Float.MAX_VALUE
        for (v in vorlagen) {
            val z = v.zeichen - '0'
            val ds = s - v.seite
            val seitenTeil = 1.5f * ds * ds
            val bisher = beste[z]
            val schranke = minOf(bisher, min2)
            val grenze = if (schranke == Float.MAX_VALUE) Float.MAX_VALUE else (schranke - seitenTeil) * n
            if (grenze <= 0f) continue
            val vm = v.merkmal
            var d = 0f
            var i = 0
            while (i < n) {
                val e = m[i] - vm[i]
                d += e * e
                if (d >= grenze) break
                i++
            }
            if (i < n) continue
            val gesamt = d / n + seitenTeil
            if (gesamt < bisher) {
                beste[z] = gesamt
                min1 = Float.MAX_VALUE
                min2 = Float.MAX_VALUE
                for (w in beste) {
                    if (w < min1) { min2 = min1; min1 = w } else if (w < min2) min2 = w
                }
            }
        }
        return beste
    }

    /** Reads all digits in the area. null if nothing is there or a digit is uncertain. */
    fun lies(bild: Bild, bereich: Rechteck, schrift: Schrift, minHoehe: Int = 14): Gelesen? =
        lesung(bild, bereich, schrift, minHoehe).gelesen

    fun zahl(bild: Bild, bereich: Rechteck, schrift: Schrift, minHoehe: Int = 14): Int? =
        lies(bild, bereich, schrift, minHoehe)?.text?.takeIf { it.length in 1..7 }?.toIntOrNull()

    /** Read digits from blobs already found (null = nothing there or a digit uncertain). */
    private fun liesFlecken(gl: List<Glyphe>, schrift: Schrift): Gelesen? {
        if (gl.isEmpty()) return null
        val text = StringBuilder()
        var schlechtester = 0f
        for (g in gl) {
            val (z, d) = vergleiche(g, schrift) ?: return null
            if (d > SCHWELLE) return null
            text.append(z)
            if (d > schlechtester) schlechtester = d
        }
        return Gelesen(text.toString(), schlechtester)
    }

    // ---------------- Cache (Nov 11: "make digit recognition faster") ----------------

    /** What was read in an area: the number (or null) and how many font blobs were there. */
    class Lesung(val gelesen: Gelesen?, val flecken: Int)

    private const val ZWISCHEN_GROESSE = 2048
    private val zwischen = object : LinkedHashMap<Long, Lesung>(ZWISCHEN_GROESSE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Lesung>?): Boolean = size > ZWISCHEN_GROESSE
    }

    /** For timing: this many times a result came from the cache or had to be read anew. */
    @Volatile var treffer: Long = 0
        private set
    @Volatile var neuGelesen: Long = 0
        private set

    /** For tests: clear the cache. */
    fun zwischenspeicherLeeren() = synchronized(zwischen) { zwischen.clear() }

    /**
     * Reads an area – with a cache: if the pixels are exactly the same as in an earlier screenshot
     * (price and stats of a row rarely change), the result from back then is returned without reading again.
     * Key = checksum over ALL pixels of the area (64 bit) plus size and font.
     */
    fun lesung(bild: Bild, bereich: Rechteck, schrift: Schrift, minHoehe: Int = 14): Lesung {
        val schluessel = pruefsumme(bild, bereich, schrift.ordinal, minHoehe)
        synchronized(zwischen) {
            zwischen[schluessel]?.let {
                treffer++
                return it
            }
        }
        val z = zerlege(bild, bereich, schrift, minHoehe)
        val l = Lesung(if (resteVerdaechtig(z)) null else liesFlecken(z.glyphen, schrift), z.glyphen.size)
        synchronized(zwischen) {
            neuGelesen++
            zwischen[schluessel] = l
        }
        return l
    }

    /**
     * FNV-1a (64 bit) over size, [art] (e.g. the font), [minHoehe] and all pixels (without transparency)
     * of the area. Also used for the cache of the second text recognition ([Nachlesen]).
     */
    internal fun pruefsumme(bild: Bild, bereich: Rechteck, art: Int, minHoehe: Int): Long {
        val x0 = bereich.x0.coerceIn(0, bild.breite)
        val x1 = bereich.x1.coerceIn(0, bild.breite)
        val y0 = bereich.y0.coerceIn(0, bild.hoehe)
        val y1 = bereich.y1.coerceIn(0, bild.hoehe)
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        fun mische(v: Int) {
            h = (h xor (v.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L
        }
        mische(x1 - x0)
        mische(y1 - y0)
        mische(art)
        mische(minHoehe)
        val px = bild.pixel
        for (y in y0 until y1) {
            val basis = y * bild.breite
            for (x in x0 until x1) mische(px[basis + x] and 0xFFFFFF)
        }
        return h
    }
}
