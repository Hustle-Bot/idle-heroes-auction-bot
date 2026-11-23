package io.github.hustlebot.ihauctionbot.kern.bild

import kotlin.math.abs

/**
 * A screenshot as pixels (ARGB, row by row).
 *
 * All positions in the recognition refer to the reference phone (a Motorola, 2712 × 1220, landscape).
 * If a screenshot has a different size, it is converted first with [aufReferenz].
 */
class Bild(val breite: Int, val hoehe: Int, val pixel: IntArray) {
    init {
        require(breite > 0 && hoehe > 0 && pixel.size == breite * hoehe) { "Image size does not match" }
    }

    fun argb(x: Int, y: Int): Int = pixel[y * breite + x]

    /** Color at a position; black outside the image. */
    fun farbe(x: Int, y: Int): Farbe {
        if (x !in 0 until breite || y !in 0 until hoehe) return Farbe.SCHWARZ
        val p = pixel[y * breite + x]
        return Farbe(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
    }

    /** Nov 11 (speed): red, green, blue as one number (0xRRGGBB, outside: 0 like black) – without a Farbe object. */
    fun rgb(x: Int, y: Int): Int {
        if (x !in 0 until breite || y !in 0 until hoehe) return 0
        return pixel[y * breite + x] and 0xFFFFFF
    }

    /** Nov 11 (speed): brightness like [Farbe.hell], without creating a Farbe object (outside: 0 like black). */
    fun hell(x: Int, y: Int): Int {
        if (x !in 0 until breite || y !in 0 until hoehe) return 0
        val p = pixel[y * breite + x]
        return ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
    }

    /** The same image in reference size 2712 × 1220 (nearest neighbor – fast and good enough here). */
    fun aufReferenz(): Bild {
        if (breite == REF_BREITE && hoehe == REF_HOEHE) return this
        val neu = IntArray(REF_BREITE * REF_HOEHE)
        for (y in 0 until REF_HOEHE) {
            val qy = (y.toLong() * hoehe / REF_HOEHE).toInt()
            for (x in 0 until REF_BREITE) {
                val qx = (x.toLong() * breite / REF_BREITE).toInt()
                neu[y * REF_BREITE + x] = pixel[qy * breite + qx]
            }
        }
        return Bild(REF_BREITE, REF_HOEHE, neu)
    }

    /**
     * Rotated by [grad] (90, 180 or 270) clockwise. Nov 9: in case a screenshot
     * comes in portrait although the game runs in landscape.
     */
    fun gedreht(grad: Int): Bild {
        val g = ((grad % 360) + 360) % 360
        if (g == 0) return this
        val nb = if (g == 180) breite else hoehe
        val nh = if (g == 180) hoehe else breite
        val neu = IntArray(nb * nh)
        for (y in 0 until hoehe) {
            for (x in 0 until breite) {
                val (nx, ny) = when (g) {
                    90 -> (hoehe - 1 - y) to x
                    180 -> (breite - 1 - x) to (hoehe - 1 - y)
                    else -> y to (breite - 1 - x) // 270
                }
                neu[ny * nb + nx] = pixel[y * breite + x]
            }
        }
        return Bild(nb, nh, neu)
    }

    companion object {
        const val REF_BREITE = 2712
        const val REF_HOEHE = 1220
    }
}

/**
 * Nov 9: if a screenshot comes in portrait, it is rotated in both directions and the
 * direction in which the auction house is recognized is kept. The tap points then apply to the
 * landscape screen – exactly as Android expects the gestures.
 */
object Ausrichtung {
    data class Ergebnis(val bildschirm: io.github.hustlebot.ihauctionbot.kern.Bildschirm, val drehung: Int, val bild: Bild)

    /** [letzte]: last matching rotation (tried first). */
    fun erkenne(roh: Bild, letzte: Int = 0, zweit: ZweitLeser? = null, notiz: (String) -> Unit = {}): Ergebnis {
        if (roh.breite >= roh.hoehe) return Ergebnis(Erkenner.erkenne(roh, zweit, notiz), 0, roh)
        val reihenfolge = if (letzte == 90) listOf(90, 270) else listOf(270, 90)
        for (d in reihenfolge) {
            val g = roh.gedreht(d)
            val s = Erkenner.erkenne(g, zweit, notiz)
            if (s.ort != io.github.hustlebot.ihauctionbot.kern.Ort.UNBEKANNT) return Ergebnis(s, d, g)
        }
        val g = roh.gedreht(reihenfolge.first())
        return Ergebnis(Erkenner.erkenne(g), reihenfolge.first(), g)
    }
}

/** A color (red, green, blue, 0–255 each). */
data class Farbe(val r: Int, val g: Int, val b: Int) {
    /** Brightness 0–255. */
    val hell: Int get() = (r * 30 + g * 59 + b * 11) / 100

    /** Is every primary color at most [toleranz] away from the other color? */
    fun nahe(o: Farbe, toleranz: Int = 30): Boolean =
        abs(r - o.r) <= toleranz && abs(g - o.g) <= toleranz && abs(b - o.b) <= toleranz

    override fun toString(): String = "($r,$g,$b)"

    companion object {
        val SCHWARZ = Farbe(0, 0, 0)
    }
}
