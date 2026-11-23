package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Bildschirm
import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.UnlesbareZahl

/**
 * Nov 11 ("please add text recognition too"): a second opinion for numbers that our own
 * digit reader cannot read reliably. In the app this is Google ML Kit (via Google Play services, not built
 * into the app). The core only knows this interface – the tests use simulated readers.
 */
fun interface ZweitLeser {
    /**
     * Text in the area [bereich] of [bild] (pixels of this image, full resolution).
     * null = not available, timed out or nothing found. Returns after ~400 ms at the latest.
     */
    fun lies(bild: Bild, bereich: Rechteck): String?
}

/**
 * Nov 11: read missing numbers again with the [ZweitLeser] – ONLY the ones the bot needs right now and could not
 * read itself: price and remaining time of a row, SG at the top right, "Current Bid", amount in the confirm window,
 * counter and remaining times in My Biddings.
 *
 * Rules (the bot never guesses):
 *  - The text must look EXACTLY like the expected number ("47", "01:35:30", "12,345") – otherwise it does not count.
 *  - The number must make sense (e.g. price not above the buyout price, remaining time at most 48 hours).
 *  - If both readers have a number and they differ → counts as NOT readable.
 *  - For "Current Bid" and the SG balance the bot also asks when our own reader was only just sure.
 *  - At most [MAX_FRAGEN] questions and [MAX_ZEIT_MS] per screenshot – the bot should not get slower.
 *  - Same pixels = same answer (cache), so nothing is asked twice.
 */
object Nachlesen {
    const val MAX_FRAGEN = 6
    const val MAX_ZEIT_MS = 900L

    /** From this distance on, one of our own digits counts as "just barely" (it is sure up to [ZiffernLeser.SCHWELLE]). */
    const val KNAPP = 0.055f

    private const val ZWISCHEN_GROESSE = 512

    /** Answers per area (checksum of the pixels). "" = nothing read. */
    private val zwischen = object : LinkedHashMap<Long, String>(ZWISCHEN_GROESSE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > ZWISCHEN_GROESSE
    }

    /** For tests. */
    fun zwischenspeicherLeeren() = synchronized(zwischen) { zwischen.clear() }

    // ---------------- Strict reading ----------------

    private val NUR_ZIFFERN = Regex("^[0-9]{1,8}$")
    private val MIT_TAUSENDERN = Regex("^[0-9]{1,3}([,.][0-9]{3})+$")
    private val ZEIT = Regex("^([0-9]{1,2})[:.]([0-9]{2})[:.]([0-9]{2})$")
    private val BETRAG = Regex("^(?:.*spent)?\\s*[:;]?\\s*([0-9]{1,7})$", RegexOption.IGNORE_CASE)

    private fun sauber(text: String?): String? = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    /** Digits only (spaces in between allowed), [von] to [bis]. */
    fun zahl(text: String?, von: Int, bis: Int): Int? {
        val t = sauber(text)?.replace(" ", "") ?: return null
        if (!NUR_ZIFFERN.matches(t)) return null
        return t.toLongOrNull()?.takeIf { it in von..bis }?.toInt()
    }

    /** SG balance: "12345" or with thousands separator "12,345". */
    fun sg(text: String?): Int? {
        val t = sauber(text)?.replace(" ", "") ?: return null
        val ziffern = when {
            NUR_ZIFFERN.matches(t) -> t
            MIT_TAUSENDERN.matches(t) -> t.replace(",", "").replace(".", "")
            else -> return null
        }
        return ziffern.toLongOrNull()?.takeIf { it in 0..99_999_999 }?.toInt()
    }

    /** Remaining time "01:35:30" (or "1.35.30") in seconds; at most 48 hours. */
    fun restzeit(text: String?): Int? {
        val t = sauber(text)?.replace(" ", "") ?: return null
        val m = ZEIT.matchEntire(t) ?: return null
        val (h, min, s) = m.destructured
        val hh = h.toInt()
        val mm = min.toInt()
        val ss = s.toInt()
        if (mm >= 60 || ss >= 60 || hh > 48) return null
        return hh * 3600 + mm * 60 + ss
    }

    /** Amount in the confirm window: "Starry Gems spent: 18" or just "18". */
    fun bestaetigen(text: String?): Int? {
        val t = sauber(text) ?: return null
        val m = BETRAG.matchEntire(t) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it >= 1 }
    }

    /** Both readers together: both present and different → not readable (null). */
    fun vereinen(eigen: Int?, zweit: Int?): Int? = when {
        eigen == null -> zweit
        zweit == null -> eigen
        eigen == zweit -> eigen
        else -> null
    }

    // ---------------- Reading again ----------------

    /**
     * Fills in missing numbers in [s] (reference pixels, not yet converted). [ref] = image in reference size
     * (for our own reader), [original] = image in full resolution (the crop is taken from there).
     * [notiz]: what was read again (for the log). [knapp]: was our own reader only just sure for this field?
     */
    fun ergaenze(
        s: Bildschirm,
        ref: Bild,
        original: Bild,
        zweit: ZweitLeser,
        notiz: (String) -> Unit = {},
        uhr: () -> Long = System::currentTimeMillis,
        knapp: (Rechteck, Schrift, Int) -> Boolean = { r, schrift, minHoehe ->
            (ZiffernLeser.lies(ref, r, schrift, minHoehe)?.schlechtester ?: 0f) > KNAPP
        },
    ): Bildschirm {
        val start = uhr()
        var fragen = 0
        val unlesbar = s.unlesbar.toMutableList()

        fun frage(r: Rechteck): String? {
            if (fragen >= MAX_FRAGEN || uhr() - start > MAX_ZEIT_MS) return null
            val aus = imOriginal(r, original)
            if (aus.breite <= 0 || aus.hoehe <= 0) return null
            val schluessel = ZiffernLeser.pruefsumme(original, aus, -7, 0)
            synchronized(zwischen) { zwischen[schluessel] }?.let { return it.ifEmpty { null } }
            fragen++
            val text = try {
                zweit.lies(original, aus)
            } catch (e: Exception) {
                null
            }
            synchronized(zwischen) { zwischen[schluessel] = text ?: "" }
            return text
        }

        /** The learning image name gets the second opinion added (helps when learning the digit later). */
        fun merke(feld: String, r: Rechteck, wert: Int) {
            val i = unlesbar.indexOfFirst { it.feld == feld && it.x0 == r.x0 && it.y0 == r.y0 }
            if (i >= 0) unlesbar[i] = unlesbar[i].copy(kontext = unlesbar[i].kontext + "_zweit-" + wert)
        }

        fun unlesbarDa(feld: String, r: Rechteck): Boolean = s.unlesbar.any { it.feld == feld && it.x0 == r.x0 && it.y0 == r.y0 }

        var neu = s

        // 1. What directly decides about a bid comes first.
        when (s.ort) {
            Ort.BIETEN -> {
                val r = Erkenner.AKTUELLES_GEBOT_FELD
                val eigen = s.aktuellesGebot
                val nachfragen = eigen != null && knapp(r, Schrift.HELL, 14)
                if (eigen == null && unlesbarDa("aktuellesGebot", r) || nachfragen) {
                    val z = zahl(frage(r), 1, 9_999_999)
                    val wert = vereinen(eigen, z)
                    if (eigen == null && wert != null) {
                        notiz("Second text recognition: Current Bid = $wert")
                        merke("aktuellesGebot", r, wert)
                    }
                    if (eigen != null && z != null && wert == null) notiz("Current Bid: own reader $eigen, second text recognition $z – counts as not readable")
                    neu = neu.copy(aktuellesGebot = wert)
                }
            }
            Ort.BESTAETIGEN -> {
                val r = Erkenner.BESTAETIGEN_FELD
                if (s.bestaetigenPreis == null && unlesbarDa("bestaetigen", r)) {
                    bestaetigen(frage(r))?.let { wert ->
                        notiz("Second text recognition: amount in the confirm window = $wert SG")
                        merke("bestaetigen", r, wert)
                        neu = neu.copy(bestaetigenPreis = wert)
                    }
                }
            }
            Ort.MEINE_GEBOTE -> {
                val r = Erkenner.ZAEHLER_FELD
                if (s.laufendeGebote == null && unlesbarDa("zaehler", r)) {
                    zahl(frage(r), 0, 99)?.let { wert ->
                        notiz("Second text recognition: Bidding Items = $wert")
                        merke("zaehler", r, wert)
                        neu = neu.copy(laufendeGebote = wert, keineGebote = wert == 0 && s.zeilen.isEmpty())
                    }
                }
            }
            else -> Unit
        }

        // 2. SG balance at the top right (bidding list).
        if (s.ort == Ort.MARKT) {
            val r = Erkenner.SG_FELD
            val eigen = s.sg
            val nachfragen = eigen != null && knapp(r, Schrift.SG, 18)
            if (eigen == null && unlesbarDa("sg", r) || nachfragen) {
                val z = sg(frage(r))
                val wert = vereinen(eigen, z)
                if (eigen == null && wert != null) {
                    notiz("Second text recognition: SG balance = $wert")
                    merke("sg", r, wert)
                }
                if (eigen != null && z != null && wert == null) notiz("SG balance: own reader $eigen, second text recognition $z – counts as not readable")
                neu = neu.copy(sg = wert)
            }
        }

        // 3. Rows: price and remaining time (bidding list and My Biddings).
        if (s.ort == Ort.MARKT || s.ort == Ort.MEINE_GEBOTE) {
            val zeilen = neu.zeilen.map { z ->
                if (z.verdeckt) return@map z
                var zeile = z
                val pr = Erkenner.preisFeld(z.oben)
                // Nov 11: in My Biddings too (there the price decides between bidding again and dismissing).
                if (z.preis == null && unlesbarDa("preis", pr)) {
                    zahl(frage(pr), 1, 9_999_999)?.takeIf { p -> z.sofortPreis == null || p <= z.sofortPreis }?.let { p ->
                        notiz("Second text recognition: price ${z.id ?: "?"} (row ${z.oben}) = $p SG")
                        merke("preis", pr, p)
                        zeile = zeile.copy(preis = p)
                    }
                }
                val zr = Erkenner.zeitFeld(z.oben)
                val zeitFehlt = z.hatRestzeit && z.restzeitSek == null &&
                    (s.ort == Ort.MEINE_GEBOTE || unlesbarDa("zeit", zr))
                if (zeitFehlt) {
                    restzeit(frage(zr))?.let { sek ->
                        notiz("Second text recognition: remaining time ${z.id ?: "?"} (row ${z.oben}) = $sek s")
                        merke("zeit", zr, sek)
                        zeile = zeile.copy(restzeitSek = sek)
                    }
                }
                zeile
            }
            neu = neu.copy(zeilen = zeilen)
        }

        return if (neu === s) s else neu.copy(unlesbar = unlesbar)
    }

    /** Reference rectangle → rectangle in the full-resolution image. */
    private fun imOriginal(r: Rechteck, b: Bild): Rechteck = Rechteck(
        (r.x0 * b.breite / Bild.REF_BREITE).coerceIn(0, b.breite),
        (r.y0 * b.hoehe / Bild.REF_HOEHE).coerceIn(0, b.hoehe),
        ((r.x1 * b.breite + Bild.REF_BREITE - 1) / Bild.REF_BREITE).coerceIn(0, b.breite),
        ((r.y1 * b.hoehe + Bild.REF_HOEHE - 1) / Bild.REF_HOEHE).coerceIn(0, b.hoehe),
    )

    /** Helper for tests: a recognition field as UnlesbareZahl (the way recognition reports it). */
    fun unlesbar(feld: String, r: Rechteck, kontext: String = ""): UnlesbareZahl = UnlesbareZahl(feld, r.x0, r.y0, r.x1, r.y1, kontext)
}
