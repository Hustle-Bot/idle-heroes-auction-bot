package io.github.hustlebot.ihauctionbot.kern.bild

import io.github.hustlebot.ihauctionbot.kern.Ort
import io.github.hustlebot.ihauctionbot.kern.Sortierung
import io.github.hustlebot.ihauctionbot.kern.Zeichen

/**
 * Nov 11: ALL images from the four error reports (folder testbilder/berichte, half resolution 1356 x 610,
 * saved like that by the phone). Every image looked at by hand: place, rows, badges, clock/hammer/X, message,
 * sort order, filter and all numbers (where the bot could not read anything back then, read off by hand).
 * Time "*" = a remaining time is there, the exact value was not read off here. Time "~" = the row lies under a
 * message that is just fading out (half transparent) – whether the remaining time is found does not matter then (only never wrong).
 * Generated automatically (Claude, Nov 11) from the JSON files of the reports plus manual corrections.
 */
object BerichtAblesung {
    private fun z(
        t: Int, p: Int, s: Int?, zeit: String?, a: Int, h: Int, sp: Int,
        zeichen: Zeichen = Zeichen.KEINS, kreuz: Boolean = false, stufe: Char? = null,
    ) = ErwarteteZeile(t, p, s, zeit, listOf(a, h, sp), zeichen, kreuz, stufe)

    private fun v(t: Int) = ErwarteteZeile(t, 0, null, null, listOf(0, 0, 0), verdeckt = true)

    val alle: List<Erwartet> = listOf(
        Erwartet(
            "berichte/20-42-45_bild_01",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/20-42-45_bild_02",
            Ort.MARKT,
            sg = 5941,
            meldung = false,
            sortierung = Sortierung.PREIS,
            zeilen = listOf(
                z(314, 10, 60, "413447", 1551, 1323, 8, stufe = 'C'),
                z(495, 10, 33, "413353", 2060, 2048, 17, stufe = 'C'),
                z(676, 10, 176, "413338", 3534, 3170, 25, stufe = 'B'),
                z(857, 10, 80, "413010", 2695, 2571, 21, stufe = 'C'),
                z(1038, 10, 40, "413004", 886, 1354, 14, stufe = 'C'),
            ),
        ),
        Erwartet(
            "berichte/20-42-45_bild_03",
            Ort.FILTER,
            meldung = null,
            filterGewaehlt = listOf(),
            filterStellen = 2 to 8,
        ),
        Erwartet(
            "berichte/20-42-45_bild_04",
            Ort.FILTER,
            meldung = null,
            filterGewaehlt = listOf("B-"),
            filterStellen = 2 to 2,
        ),
        Erwartet(
            "berichte/20-42-45_bild_05",
            Ort.MARKT,
            sg = 5941,
            meldung = false,
            sortierung = Sortierung.PREIS,
            zeilen = listOf(
                z(314, 11, 140, "150713", 3062, 3770, 35, stufe = 'B'),
                z(495, 11, 99, "150709", 2613, 2492, 31, stufe = 'B'),
                z(676, 11, 99, "150704", 3885, 2845, 34, stufe = 'B'),
                z(857, 11, 99, "150520", 3231, 2600, 38, stufe = 'B'),
                z(1038, 11, 99, "150516", 3440, 2984, 19, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/20-42-45_bild_06",
            Ort.MARKT,
            sg = 5941,
            meldung = false,
            sortierung = Sortierung.PREIS,
            zeilen = listOf(
                z(314, 11, 140, "*", 3062, 3770, 35, stufe = 'B'),
                z(495, 11, 99, "*", 2613, 2492, 31, stufe = 'B'),
                z(676, 11, 99, "150704", 3885, 2845, 34, stufe = 'B'),
                z(857, 11, 99, "*", 3231, 2600, 38, stufe = 'B'),
                z(1038, 11, 99, "*", 3440, 2984, 19, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/20-42-45_bild_07",
            Ort.SORTIEREN,
            meldung = null,
        ),
        Erwartet(
            "berichte/20-42-45_bild_08",
            Ort.SORTIEREN,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/20-42-45_bild_09",
            Ort.SORTIEREN,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/20-42-45_bild_10",
            Ort.SORTIEREN,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/20-42-45_bild_11",
            Ort.AUKTIONSHAUS_ANDERS,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/20-42-45_bild_12",
            Ort.MARKT,
            sg = 5941,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 54, 150, "000331", 3267, 3360, 33, stufe = 'B'),
                z(495, 59, 154, "000526", 3607, 3392, 29, stufe = 'B'),
                z(676, 47, 216, "001120", 3074, 3169, 24, stufe = 'B'),
                z(857, 25, 216, "001143", 3402, 2856, 38, stufe = 'B'),
                z(1038, 57, 93, "001429", 2767, 2529, 28, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/20-42-45_bild_13",
            Ort.MEINE_GEBOTE,
            sg = 5941,
            zaehler = 0,
            meldung = false,
            zeilen = emptyList(),
        ),
        Erwartet(
            "berichte/20-42-45_bild_14",
            Ort.MARKT,
            sg = 5941,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 54, 150, "000329", 3267, 3360, 33, stufe = 'B'),
                z(495, 59, 154, "000524", 3607, 3392, 29, stufe = 'B'),
                z(676, 47, 216, "*", 3074, 3169, 24, stufe = 'B'),
                z(857, 25, 216, "001141", 3402, 2856, 38, stufe = 'B'),
                z(1038, 57, 93, "001427", 2767, 2529, 28, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/20-42-45_bild_15",
            Ort.MARKT,
            sg = 5941,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 54, 150, "000328", 3267, 3360, 33, stufe = 'B'),
                z(495, 59, 154, "*", 3607, 3392, 29, stufe = 'B'),
                z(676, 47, 216, "001117", 3074, 3169, 24, stufe = 'B'),
                z(857, 25, 216, "001140", 3402, 2856, 38, stufe = 'B'),
                z(1038, 57, 93, "001426", 2767, 2529, 28, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/20-42-45_bild_16",
            Ort.BIETEN,
            aktuellesGebot = 54,
            bietFeld = 55,
            meldung = false,
        ),
        Erwartet(
            "berichte/21-46-15_bild_01",
            Ort.SORTIEREN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_02",
            Ort.SORTIEREN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_03",
            Ort.SORTIEREN,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/21-46-15_bild_04",
            Ort.SORTIEREN,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/21-46-15_bild_05",
            Ort.SORTIEREN,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/21-46-15_bild_06",
            Ort.AUKTIONSHAUS_ANDERS,
            meldung = true, // Nov 22: "Operated too frequently. Please retry in N second(s)."
        ),
        Erwartet(
            "berichte/21-46-15_bild_07",
            Ort.MARKT,
            sg = 5779,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 57, 216, "000121", 2258, 3337, 37, stufe = 'B'),
                z(495, 58, 93, "000815", 3270, 2325, 38, stufe = 'B'),
                z(676, 54, 210, "000840", 1971, 2367, 44, stufe = 'B'),
                z(857, 51, 192, "001330", 2468, 2911, 29, stufe = 'B'),
                z(1038, 52, 92, "001523", 2025, 4446, 30, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/21-46-15_bild_08",
            Ort.MEINE_GEBOTE,
            sg = 5779,
            zaehler = 0,
            meldung = false,
            zeilen = emptyList(),
        ),
        Erwartet(
            "berichte/21-46-15_bild_09",
            Ort.AUKTIONSHAUS_ANDERS,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_10",
            Ort.AUKTIONSHAUS_ANDERS,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_11",
            Ort.MARKT,
            sg = 5779,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 57, 216, "000118", 2258, 3337, 37, stufe = 'B'),
                z(495, 58, 93, "*", 3270, 2325, 38, stufe = 'B'),
                z(676, 54, 210, "000837", 1971, 2367, 44, stufe = 'B'),
                z(857, 51, 192, "001327", 2468, 2911, 29, stufe = 'B'),
                z(1038, 52, 92, "001520", 2025, 4446, 30, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/21-46-15_bild_12",
            Ort.MARKT,
            sg = 5779,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 57, 216, "000117", 2258, 3337, 37, stufe = 'B'),
                z(495, 58, 93, "*", 3270, 2325, 38, stufe = 'B'),
                z(676, 54, 210, "000836", 1971, 2367, 44, stufe = 'B'),
                z(857, 51, 192, "001326", 2468, 2911, 29, stufe = 'B'),
                z(1038, 52, 92, "001519", 2025, 4446, 30, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/21-46-15_bild_13",
            Ort.MARKT,
            sg = 5779,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(314, 57, 216, "000117", 2258, 3337, 37, stufe = 'B'),
                z(495, 58, 93, "*", 3270, 2325, 38, stufe = 'B'),
                z(676, 54, 210, "000836", 1971, 2367, 44, stufe = 'B'),
                z(857, 51, 192, "001326", 2468, 2911, 29, stufe = 'B'),
                z(1038, 52, 92, "001519", 2025, 4446, 30, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/21-46-15_bild_14",
            Ort.LADEN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_15",
            Ort.LADEN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_16",
            Ort.LADEN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_17",
            Ort.LADEN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_18",
            Ort.LADEN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_19",
            Ort.LADEN,
            meldung = null,
        ),
        Erwartet(
            "berichte/21-46-15_bild_20",
            Ort.BIETEN,
            aktuellesGebot = 57,
            bietFeld = 58,
            meldung = false,
        ),
        Erwartet(
            "berichte/19-33-55_bild_01",
            Ort.MARKT,
            sg = 4829,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(443),
                v(624),
                z(805, 59, 216, "005315", 2289, 3871, 31, Zeichen.UHR, stufe = 'B'),
                z(986, 58, 216, "005321", 5023, 2184, 24, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_02",
            Ort.MARKT,
            sg = 4829,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(443),
                v(624),
                z(805, 59, 216, "005315", 2289, 3871, 31, Zeichen.UHR, stufe = 'B'),
                z(986, 58, 216, "005321", 5023, 2184, 24, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_03",
            Ort.MARKT,
            sg = 4829,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(443),
                v(624),
                z(805, 59, 216, "005314", 2289, 3871, 31, Zeichen.UHR, stufe = 'B'),
                z(986, 58, 216, "005320", 5023, 2184, 24, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_04",
            Ort.MARKT,
            sg = 4829,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(443),
                v(624),
                z(805, 59, 216, "005314", 2289, 3871, 31, Zeichen.UHR, stufe = 'B'),
                z(986, 58, 216, "005320", 5023, 2184, 24, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_05",
            Ort.MARKT,
            sg = 4829,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(443, 59, 99, "005124", 2782, 2587, 27, Zeichen.UHR, stufe = 'B'),
                z(624, 58, 99, "005307", 2906, 2692, 31, stufe = 'B'),
                z(805, 59, 216, "005313", 2289, 3871, 31, Zeichen.UHR, stufe = 'B'),
                z(986, 58, 216, "005319", 5023, 2184, 24, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_06",
            Ort.MARKT,
            sg = 4829,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(411, 58, 216, "005319", 5023, 2184, 24, stufe = 'B'),
                z(592, 56, 91, "005820", 2413, 2651, 39, stufe = 'B'),
                z(773, 54, 148, "010641", 2526, 3788, 19, stufe = 'B'),
                z(954, 57, 81, "011507", 3058, 3214, 31, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_07",
            Ort.MARKT,
            sg = 4829,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(471, 56, 91, "005819", 2413, 2651, 39, stufe = 'B'),
                z(652, 54, 148, "010640", 2526, 3788, 19, stufe = 'B'),
                z(833, 57, 81, "011506", 3058, 3214, 31, stufe = 'B'),
                z(1014, 57, 98, "011742", 3010, 3600, 34, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-33-55_bild_08",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_09",
            Ort.BIETEN,
            aktuellesGebot = 54,
            bietFeld = 55,
            meldung = false,
        ),
        Erwartet(
            "berichte/19-33-55_bild_10",
            Ort.BESTAETIGEN,
            bestaetigen = 55,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_11",
            Ort.BIETEN,
            aktuellesGebot = 54,
            bietFeld = 55,
            meldung = false,
        ),
        Erwartet(
            "berichte/19-33-55_bild_12",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_13",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_14",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_15",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_16",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_17",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_18",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_19",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-33-55_bild_20",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-39-28_bild_01",
            Ort.BIETEN,
            aktuellesGebot = 52,
            bietFeld = 53,
            meldung = true,
        ),
        Erwartet(
            "berichte/19-39-28_bild_02",
            Ort.BIETEN,
            aktuellesGebot = 52,
            bietFeld = 53,
            meldung = true,
        ),
        Erwartet(
            "berichte/19-39-28_bild_03",
            Ort.BIETEN,
            aktuellesGebot = 52,
            bietFeld = 53,
            meldung = false,
        ),
        Erwartet(
            "berichte/19-39-28_bild_04",
            Ort.BESTAETIGEN,
            bestaetigen = 53,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-39-28_bild_05",
            Ort.MARKT,
            sg = 4271,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(460),
                v(641),
                z(822, 53, 99, "013343", 3361, 3585, 31, Zeichen.UHR, stufe = 'B'),
                z(1004, 53, 90, "013435", 4157, 2650, 32, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_06",
            Ort.MARKT,
            sg = 4271,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(460),
                v(641),
                z(822, 53, 99, "013343", 3361, 3585, 31, Zeichen.UHR, stufe = 'B'),
                z(1004, 53, 90, "013435", 4157, 2650, 32, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_07",
            Ort.MARKT,
            sg = 4271,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(460),
                v(641),
                z(822, 53, 99, "013342", 3361, 3585, 31, Zeichen.UHR, stufe = 'B'),
                z(1004, 53, 90, "013434", 4157, 2650, 32, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_08",
            Ort.MARKT,
            sg = 4271,
            meldung = true, meldungKurz = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(460),
                v(641),
                z(822, 53, 99, "013342", 3361, 3585, 31, Zeichen.UHR, stufe = 'B'),
                z(1004, 53, 90, "013434", 4157, 2650, 32, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_09",
            Ort.MARKT,
            sg = 4271,
            meldung = null,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(460, 56, 99, "~", 3398, 2803, 32, Zeichen.UHR, stufe = 'B'),
                z(641, 57, 110, "~", 3698, 3670, 27, Zeichen.UHR, stufe = 'B'),
                z(822, 53, 99, "013341", 3361, 3585, 31, Zeichen.UHR, stufe = 'B'),
                z(1004, 53, 90, "013433", 4157, 2650, 32, Zeichen.UHR, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_10",
            Ort.MARKT,
            sg = 4271,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(415, 53, 90, "013432", 4157, 2650, 32, Zeichen.UHR, stufe = 'B'),
                z(597, 51, 139, "013524", 2457, 3263, 44, stufe = 'B'),
                z(778, 57, 89, "013643", 2956, 2904, 23, stufe = 'B'),
                z(959, 51, 141, "014129", 3467, 2629, 25, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_11",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-39-28_bild_12",
            Ort.BIETEN,
            aktuellesGebot = 51,
            bietFeld = 52,
            meldung = false,
        ),
        Erwartet(
            "berichte/19-39-28_bild_13",
            Ort.BESTAETIGEN,
            bestaetigen = 52,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-39-28_bild_14",
            Ort.MARKT,
            sg = 4219,
            meldung = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(468),
                v(649),
                z(831, 51, 141, "014127", 3467, 2629, 25, Zeichen.UHR, stufe = 'B'),
                z(1012, 56, 216, "014204", 2272, 2844, 45, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_15",
            Ort.MARKT,
            sg = 4219,
            meldung = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(468),
                v(649),
                z(831, 51, 141, "014126", 3467, 2629, 25, Zeichen.UHR, stufe = 'B'),
                z(1012, 56, 216, "014203", 2272, 2844, 45, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_16",
            Ort.MARKT,
            sg = 4219,
            meldung = true,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                v(468),
                v(649),
                z(831, 51, 141, "014126", 3467, 2629, 25, Zeichen.UHR, stufe = 'B'),
                z(1012, 56, 216, "014203", 2272, 2844, 45, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_17",
            Ort.MARKT,
            sg = 4219,
            meldung = false,
            sortierung = Sortierung.RANG,
            zeilen = listOf(
                z(468, 51, 139, "013520", 2457, 3263, 44, stufe = 'B'),
                z(649, 57, 89, "013639", 2956, 2904, 23, stufe = 'B'),
                z(831, 51, 141, "*", 3467, 2629, 25, Zeichen.UHR, stufe = 'B'),
                z(1012, 56, 216, "014202", 2272, 2844, 45, stufe = 'B'),
            ),
        ),
        Erwartet(
            "berichte/19-39-28_bild_18",
            Ort.UNBEKANNT,
            meldung = null,
        ),
        Erwartet(
            "berichte/19-39-28_bild_19",
            Ort.BIETEN,
            aktuellesGebot = 56,
            bietFeld = 57,
            meldung = false,
        ),
        Erwartet(
            "berichte/19-39-28_bild_20",
            Ort.BESTAETIGEN,
            bestaetigen = 57,
            meldung = null,
        ),
    )
}
