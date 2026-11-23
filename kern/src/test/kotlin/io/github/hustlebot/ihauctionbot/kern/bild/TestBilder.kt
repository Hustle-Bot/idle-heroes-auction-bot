package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.Zeichen
import java.io.File
import javax.imageio.ImageIO

/** Loads the real screenshots from idle-heroes-bot/testbilder. */
object TestBilder {
    private val ordner: File = listOf(File("../testbilder"), File("testbilder"), File("idle-heroes-bot/testbilder"))
        .first { it.isDirectory }

    private val cache = HashMap<String, Bild>()

    /**
     * Nov 12: new screenshots come as PNG (exactly the pixels the bot sees too – without JPG blur).
     * So first "<name>.jpg", otherwise "<name>.png".
     */
    fun lade(name: String): Bild = cache.getOrPut(name) {
        val datei = File(ordner, "$name.jpg").takeIf { it.isFile } ?: File(ordner, "$name.png")
        val img = ImageIO.read(datei) ?: error("Image $name missing")
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        Bild(img.width, img.height, px)
    }
}

/** What is visible on a row (read off the screenshots by hand). */
data class ErwarteteZeile(
    val oben: Int,
    val preis: Int,
    val sofort: Int?,
    val zeit: String?,
    val werte: List<Int>,
    val zeichen: Zeichen = Zeichen.KEINS,
    val kreuz: Boolean = false,
    val stufe: Char? = null,
    /** Nov 9 (field test 1.9): a message lies over the row – nothing on it is readable (values do not matter here). */
    val verdeckt: Boolean = false,
    /** Nov 9: the remaining time is there, but is not read (yet) – the bot does not guess. */
    val zeitUnlesbar: Boolean = false,
)

/** What is visible on a screenshot. */
data class Erwartet(
    val name: String,
    val ort: Ort,
    val sg: Int? = null,
    val zeilen: List<ErwarteteZeile> = emptyList(),
    val zaehler: Int? = null,
    val aktuellesGebot: Int? = null,
    val bestaetigen: Int? = null,
    val meldung: Boolean? = false,
    val meldungKurz: Boolean = false,
    val filterGewaehlt: List<String>? = null,
    /**
     * Use it for learning the digits? With so few images the bot learns from all that contain numbers.
     * What matters is that a NEVER seen digit counts as "not readable" instead of being misread
     * (see ZiffernTest).
     */
    val lernen: Boolean = false,
    /** Nov 11: label of the sort button (null = do not check). */
    val sortierung: Sortierung? = null,
    /** Nov 11: filter window – how many characters are in the min and max price fields (null = do not check). */
    val filterStellen: Pair<Int, Int>? = null,
)

object Ablesung {
    private fun z(
        t: Int, p: Int, s: Int?, zeit: String?, a: Int, h: Int, sp: Int,
        zeichen: Zeichen = Zeichen.KEINS, kreuz: Boolean = false, stufe: Char? = null, zeitUnlesbar: Boolean = false,
    ) = ErwarteteZeile(t, p, s, zeit, listOf(a, h, sp), zeichen, kreuz, stufe, zeitUnlesbar = zeitUnlesbar)

    /** Row under a message (covered). */
    private fun v(t: Int) = ErwarteteZeile(t, 0, null, null, listOf(0, 0, 0), verdeckt = true)

    val alle: List<Erwartet> = listOf(
        Erwartet(
            "markt-unsortiert", Ort.MARKT, sg = 20704, lernen = true,
            zeilen = listOf(
                z(314, 10, 65, "395921", 2184, 2320, 22, stufe = 'C'),
                z(495, 10, null, "395921", 2813, 3301, 36, stufe = 'B'),
                z(676, 10, 50, "395917", 1751, 1672, 24, stufe = 'C'),
                z(857, 10, null, "395851", 2039, 2390, 15, stufe = 'C'),
                z(1038, 10, null, "395851", 1998, 1278, 13, stufe = 'C'),
            ),
        ),
        Erwartet(
            "markt-c-minus", Ort.MARKT, sg = 20704, lernen = true,
            zeilen = listOf(
                z(314, 18, 28, "013530", 1081, 1870, 8, stufe = 'C'),
                z(495, 17, 36, "013934", 1454, 1142, 16, stufe = 'C'),
                z(676, 18, 25, "020603", 1433, 889, 24, stufe = 'C'),
                z(857, 16, 60, "021716", 1926, 709, 15, stufe = 'C'),
                z(1038, 17, 60, "021719", 886, 1961, 10, stufe = 'C'),
            ),
        ),
        Erwartet(
            "markt-nach-gebot", Ort.MARKT, sg = 20667, lernen = true,
            zeilen = listOf(
                z(314, 19, 28, "013458", 1081, 1870, 8, Zeichen.HAMMER, stufe = 'C'),
                z(495, 18, 36, "013902", 1454, 1142, 16, Zeichen.UHR, stufe = 'C'),
                z(676, 18, 25, "020531", 1433, 889, 24, stufe = 'C'),
                z(857, 16, 60, "021644", 1926, 709, 15, stufe = 'C'),
                z(1038, 17, 60, "021647", 886, 1961, 10, stufe = 'C'),
            ),
        ),
        // Nov 9 (field test 1.9): the rows are read even with a message – only the ones under the band are covered
        // ("Bid Failure" is taller than "Bidding successful" and also touches the top of row 3).
        Erwartet(
            "toast-verloren", Ort.MARKT, sg = 20667, meldung = true,
            zeilen = listOf(
                z(314, 19, 28, "013456", 1081, 1870, 8, Zeichen.HAMMER, stufe = 'C'),
                v(495),
                v(676),
                z(857, 16, 60, "021642", 1926, 709, 15, stufe = 'C'),
                z(1038, 17, 60, "021645", 886, 1961, 10, stufe = 'C'),
            ),
        ),
        // Nov 9 (field test 1.9): sorted by Awakened Tier, four of our own bids (clock). The price 47 (row 3)
        // was not read by 1.9 – the copy was skipped.
        Erwartet(
            "markt-rang-gebote", Ort.MARKT, sg = 5686, lernen = true,
            zeilen = listOf(
                z(314, 55, 150, "000256", 3267, 3360, 33, Zeichen.UHR, stufe = 'B'),
                z(495, 60, 154, "000451", 3607, 3392, 29, Zeichen.UHR, stufe = 'B'),
                z(676, 47, 216, "001044", 3074, 3169, 24, stufe = 'B'),
                z(857, 26, 216, "001108", 3402, 2856, 38, Zeichen.UHR, stufe = 'B'),
                z(1038, 58, 93, "001354", 2767, 2529, 28, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        // Nov 9 (field test 1.9): "Bidding successful. Check it in My Biddings." lies over row 2.
        // At 1224 px it counts as "short" (< 1350) – but in the Bid window such a message is only believed as the limit
        // with a matching form/counter, and over the list it confirms the bid (Meldung.wieErfolg).
        Erwartet(
            "markt-toast-erfolg-1", Ort.MARKT, sg = 5643, meldung = true, meldungKurz = true, lernen = true,
            zeilen = listOf(
                z(318, 56, 98, "001606", 2571, 2999, 30, Zeichen.UHR, stufe = 'B'),
                v(499),
                z(680, 57, 80, "002430", 2598, 2720, 29, stufe = 'B'),
                z(861, 55, 81, "002437", 2704, 3048, 26, stufe = 'B'),
                z(1042, 57, 94, "002655", 2983, 2262, 34, stufe = 'B'),
            ),
        ),
        Erwartet(
            "markt-toast-erfolg-2", Ort.MARKT, sg = 5693, meldung = true, meldungKurz = true, lernen = true,
            zeilen = listOf(
                z(314, 57, 150, "000122", 3267, 3360, 33, Zeichen.HAMMER, stufe = 'B'),
                v(495),
                z(676, 26, 216, "000935", 3402, 2856, 38, Zeichen.UHR, stufe = 'B'),
                z(857, 58, 93, "001221", 2767, 2529, 28, Zeichen.UHR, stufe = 'B'),
                z(1038, 56, 98, "001611", 2571, 2999, 30, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        // Nov 9 (field test 1.11): sorted by Awakened Tier, SG 5,553, IH button at the top left, own bids with a clock.
        // Image 1 is scrolled a bit (first row cut off at the top). Row 5 shows 00:20:45 – the bot does not read that
        // (yet); it does not guess.
        Erwartet(
            "markt-rang-uhr-1", Ort.MARKT, sg = 5553, lernen = true,
            zeilen = listOf(
                z(295, 52, 92, "001428", 2025, 4446, 30, stufe = 'B'),
                z(476, 56, 216, "001443", 4040, 2327, 30, stufe = 'B'),
                z(657, 56, 89, "001506", 3681, 3127, 20, Zeichen.UHR, stufe = 'B'),
                z(838, 59, 84, "001554", 3706, 2801, 17, stufe = 'B'),
                z(1019, 58, 161, "002045", 3543, 3206, 35, stufe = 'B'),
            ),
        ),
        Erwartet(
            "markt-rang-uhr-2", Ort.MARKT, sg = 5553, lernen = true,
            zeilen = listOf(
                z(314, 58, 216, "000029", 2258, 3337, 37, Zeichen.UHR, stufe = 'B'),
                z(495, 59, 93, "000723", 3270, 2325, 38, Zeichen.UHR, stufe = 'B'),
                z(676, 54, 210, "000748", 1971, 2367, 44, stufe = 'B'),
                z(857, 53, 192, "001238", 2468, 2911, 29, Zeichen.UHR, stufe = 'B'),
                z(1038, 52, 92, "001431", 2025, 4446, 30, stufe = 'B'),
            ),
        ),
        Erwartet(
            "markt-kurz-vor-ende", Ort.MARKT, sg = 20498, lernen = true,
            zeilen = listOf(
                z(314, 22, null, null, 1075, 1552, 14, stufe = 'C'),
                z(495, 23, null, "000041", 1302, 1110, 19, stufe = 'C'),
                z(676, 22, null, "000126", 2496, 729, 10, stufe = 'C'),
                z(857, 22, null, "000215", 1597, 1436, 10, stufe = 'C'),
                z(1038, 23, null, "000319", 1950, 1147, 8, stufe = 'C'),
            ),
        ),
        Erwartet(
            "listen-ende", Ort.MARKT, sg = 17593, lernen = true,
            zeilen = listOf(
                z(293, 60, 216, "004819", 4082, 2002, 31, stufe = 'B'),
                z(474, 60, 216, "004819", 3028, 2653, 30, stufe = 'B'),
                z(655, 60, 216, "004819", 2489, 3700, 20, stufe = 'B'),
                z(837, 60, 216, "004819", 2566, 4528, 32, stufe = 'B'),
                z(1018, 60, 216, "004819", 2093, 3172, 38, stufe = 'B'),
            ),
        ),
        Erwartet(
            "meine-gebote-49", Ort.MEINE_GEBOTE, sg = 17593, zaehler = 49, lernen = true,
            zeilen = listOf(
                z(374, 33, null, "003441", 2274, 3365, 23, Zeichen.UHR, stufe = 'C'),
                z(555, 33, null, "003412", 2315, 2101, 21, Zeichen.UHR, stufe = 'C'),
                z(737, 35, null, "000844", 2489, 2544, 22, kreuz = true, stufe = 'C'),
                z(918, 36, null, "003056", 2136, 2332, 27, Zeichen.UHR, stufe = 'C'),
            ),
        ),
        Erwartet(
            "meine-gebote-43", Ort.MEINE_GEBOTE, sg = 18160, zaehler = 43, lernen = true,
            zeilen = listOf(
                z(374, 33, null, "003806", 2274, 3365, 23, Zeichen.UHR, stufe = 'C'),
                z(555, 33, null, "003737", 2315, 2101, 21, Zeichen.UHR, stufe = 'C'),
                z(737, 34, null, "001209", 2489, 2544, 22, Zeichen.UHR, stufe = 'C'),
                z(918, 36, null, "003421", 2136, 2332, 27, Zeichen.UHR, stufe = 'C'),
            ),
        ),
        // Nov 11: My Biddings at 50 bids, scrolled down (half a row at the top). All four outbid
        // (red X at the top right) – they count in the game until the X is pressed.
        Erwartet(
            "meine-gebote-50-kreuze", Ort.MEINE_GEBOTE, sg = 3489, zaehler = 50, lernen = true,
            zeilen = listOf(
                z(477, 60, 91, "001137", 2413, 2651, 39, kreuz = true, stufe = 'B'),
                z(658, 61, 124, "004848", 2227, 4109, 35, kreuz = true, stufe = 'B'),
                z(839, 61, 216, "003749", 4052, 3659, 20, kreuz = true, stufe = 'B'),
                z(1020, 61, 89, "003346", 2600, 3205, 23, kreuz = true, stufe = 'B'),
            ),
        ),
        // Nov 11: one outbid (57, X), three in front (clock).
        Erwartet(
            "meine-gebote-50-gemischt", Ort.MEINE_GEBOTE, sg = 3489, zaehler = 50, lernen = true,
            zeilen = listOf(
                z(443, 57, 89, "000308", 2200, 2918, 32, kreuz = true, stufe = 'B'),
                z(624, 58, 89, "022657", 4325, 2695, 26, Zeichen.UHR, stufe = 'B'),
                z(805, 58, 83, "022630", 3212, 3137, 25, Zeichen.UHR, stufe = 'B'),
                z(986, 58, 114, "013950", 2799, 2954, 28, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        // Nov 12: "Bidding Items: 41" (PNG from the phone, full resolution). On the phone it said "counter not readable – counting
        // by myself (6 own bids)". All four fully visible rows are our own bids in front (clock). On the left "Bidding
        // Price" (= preis), on the right below it "Buyout Price" (= sofort) – never mix them up.
        Erwartet(
            "meine-gebote-41", Ort.MEINE_GEBOTE, sg = 8874, zaehler = 41, lernen = true,
            zeilen = listOf(
                z(374, 50, 93, "002038", 3275, 3351, 26, Zeichen.UHR, stufe = 'B'),
                z(555, 50, 103, "001048", 2936, 2983, 25, Zeichen.UHR, stufe = 'B'),
                z(737, 51, 98, "001616", 3129, 3382, 21, Zeichen.UHR, stufe = 'B'),
                z(918, 51, 95, "001106", 4400, 2106, 22, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        Erwartet("meine-gebote-leer", Ort.MEINE_GEBOTE, sg = 20704, zaehler = 0),
        Erwartet("bid-fenster", Ort.BIETEN, aktuellesGebot = 18, lernen = true),
        Erwartet("limit-50-status-7", Ort.BIETEN, aktuellesGebot = 62, meldung = true, meldungKurz = true, lernen = true),
        // Nov 9 (field test 1.7): saved by the bot itself when it did NOT recognize the window –
        // B- without buyout ("Buyout Price: --", gray Buyout button), IH button on the left of the image.
        Erwartet("bid-fenster-b-ohne-buyout-1", Ort.BIETEN, aktuellesGebot = 53, lernen = true),
        Erwartet("bid-fenster-b-ohne-buyout-2", Ort.BIETEN, aktuellesGebot = 53, lernen = true),
        // Nov 9 (field test 1.8): "Current Bid not readable" – Current Bid 53, field 54, ends 00:31:20.
        Erwartet("bid-fenster-b-ohne-buyout-3", Ort.BIETEN, aktuellesGebot = 53, lernen = true),
        Erwartet("bestaetigen-18", Ort.BESTAETIGEN, bestaetigen = 18, lernen = true),
        Erwartet("bestaetigen-17", Ort.BESTAETIGEN, bestaetigen = 17, lernen = true),
        Erwartet("filter-fenster", Ort.FILTER, filterGewaehlt = listOf("C-"), meldung = null),
        Erwartet("sortier-fenster", Ort.SORTIEREN, meldung = null),
        Erwartet("markt-anzeige", Ort.MARKT_ANZEIGE, meldung = null),
        Erwartet("empfohlen", Ort.AUKTIONSHAUS_ANDERS, meldung = null),
        Erwartet("helden-verkaufen", Ort.AUKTIONSHAUS_ANDERS, meldung = null),
        Erwartet("geschenke", Ort.AUKTIONSHAUS_ANDERS, meldung = null),
        Erwartet("laden", Ort.AUKTIONSHAUS_ANDERS, meldung = null),
        // Nov 11: loading screen – everything darkened, a golden star spins in the middle. Just wait.
        Erwartet("laden-stern", Ort.LADEN, meldung = null),
        Erwartet("transaktionen", Ort.UNBEKANNT, meldung = null),
        // Nov 9 (field test 1.8): market, but the expanded IH panel covers the left menu.
        // Must NOT count as "other auction house page" (otherwise the bot taps into the menu on the left).
        Erwartet("markt-mit-ih-feld", Ort.UNBEKANNT, meldung = null),
        // Nov 12: bidding list with the expanded IH panel on the left (PNG). Tap nothing here either; the rows to the right
        // of it are checked by ZeilenNebenIhFeld (Bidding Price / Buyout Price).
        Erwartet("markt-ih-feld-14-kopien", Ort.UNBEKANNT, meldung = null),
        Erwartet("markt-ih-feld-61-kopien", Ort.UNBEKANNT, meldung = null),
        // Nov 11: Aspen (dark knight, purple faction sign) for 58 – NOT used for learning, so that it
        // shows whether the reader is reliable on an unknown image too.
        Erwartet(
            "markt-aspen-58", Ort.MARKT, sg = 4774, sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(423, 58, 99, "005244", 2906, 2692, 31, stufe = 'B'),
                z(604, 59, 216, "005250", 2289, 3871, 31, Zeichen.UHR, stufe = 'B'),
                z(786, 58, 216, "005256", 5023, 2184, 24, stufe = 'B'),
                z(967, 56, 91, "005757", 2413, 2651, 39, stufe = 'B'),
            ),
        ),
        // Nov 11: other windows in the game (also with a red X at the top right) – never take them for the auction house.
        Erwartet("ziel-belohnungen-1", Ort.UNBEKANNT, meldung = null),
        Erwartet("ziel-belohnungen-2", Ort.UNBEKANNT, meldung = null),
        Erwartet("ziel-belohnungen-3", Ort.UNBEKANNT, meldung = null),
        Erwartet("sternenjuwel-tausch", Ort.UNBEKANNT, meldung = null),
        Erwartet("rangliste", Ort.UNBEKANNT, meldung = null),
    )
}

/**
 * Nov 12: bidding list with the expanded IH panel lying over it on the left – so the whole image counts as "unknown"
 * (the bot taps nothing there, otherwise it reads the list without the panel). The rows to the right of it are fully visible, though:
 * they are used to check that "Bidding Price" (top = preis) and "Buyout Price" (below = sofort, "--" = no
 * buyout = null) are never mixed up. Remaining times above 24 hours (41:59:09). NOT used for learning – so
 * they show whether the reader is reliable on unknown images. The rank badge is mostly under the panel (not checked).
 */
object ZeilenNebenIhFeld {
    private fun z(t: Int, p: Int, s: Int?, zeit: String, a: Int, h: Int, sp: Int) =
        ErwarteteZeile(t, p, s, zeit, listOf(a, h, sp))

    val alle: Map<String, List<ErwarteteZeile>> = mapOf(
        "markt-ih-feld-14-kopien" to listOf(
            z(314, 10, null, "415909", 1026, 1875, 14),
            z(495, 10, 525, "415731", 4191, 4347, 38),
            z(676, 10, 59, "415725", 1879, 2358, 22),
            z(857, 10, 59, "415716", 2963, 1441, 28),
            z(1038, 11, 40, "192841", 1310, 1750, 9),
        ),
        "markt-ih-feld-61-kopien" to listOf(
            z(314, 10, 176, "415818", 4158, 3352, 32),
            z(495, 10, 27, "415804", 985, 1724, 13),
            z(676, 10, 38, "415759", 1560, 1833, 15),
            z(857, 10, 38, "415537", 2273, 1148, 14),
            z(1038, 10, 38, "415109", 1629, 2290, 22),
        ),
    )
}
