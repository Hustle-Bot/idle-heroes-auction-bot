package io.github.hustlebot.ihauctionbot.kern

import kotlin.math.abs

/** A point on the phone screen in pixels. */
data class Punkt(val x: Int, val y: Int)

/** Where the bot is right now. */
enum class Ort(val text: String) {
    /** Auction house → Auction Market → Bidding Market, tab "Items for Bidding". This is where bids are placed. */
    MARKT("bidding list"),

    /** Same page, but tab "Items on Display" (you cannot bid there yet). */
    MARKT_ANZEIGE("tab Items on Display"),

    /** Auction Market → My Biddings (own bids, counter "Bidding Items: N"). */
    MEINE_GEBOTE("My Biddings"),

    /** Window "Bid" (Current Bid, Bid button). */
    BIETEN("Bid window"),

    /** Window "Starry Gems spent: X – Join the bidding?". */
    BESTAETIGEN("confirm window"),

    /** Filter window (Awakened Tier, price range). */
    FILTER("filter window"),

    /** Sort window (Price ↑ etc.). */
    SORTIEREN("sort window"),

    /**
     * Window "Enter Secondary Password" (Nov 9): appears after "Bid" until the game is restarted.
     * NOT yet checked on a phone screenshot (only a PC screenshot) – see README.
     */
    ZWEITPASSWORT("secondary password window"),

    /**
     * Nov 9: "Lost connection with game server." with a yellow Confirm button in the middle.
     * Then the bot stops (taps nothing). Derived from a PC screenshot – not yet checked on the phone.
     */
    VERBINDUNG_WEG("connection lost"),

    /**
     * Nov 11: loading screen – the whole screen is darkened, a golden star (six-pointed star in a ring)
     * spins in the middle. Then the bot just waits and taps nothing.
     */
    LADEN("game loading"),

    /** Another page in the auction house (Recommended, Hero Listing, …). */
    AUKTIONSHAUS_ANDERS("other auction house page"),

    /** Not recognized – then the bot taps nowhere. */
    UNBEKANNT("unknown"),
}

/**
 * The icon on the right of a row.
 * Working assumption from Nov 8 (seen in My Biddings): clock = I have the highest bid,
 * hammer = I bid, but was outbid. Still has to be confirmed on the phone.
 */
enum class Zeichen { KEINS, UHR, HAMMER }

/** The buttons the bot knows. */
enum class Knopf {
    AKTUALISIEREN, SORTIEREN, FILTER,
    MENUE_AUKTIONSMARKT, MENUE_BIETERMARKT, MENUE_MEINE_GEBOTE, REITER_BIETEN,
    BIETEN, SCHLIESSEN, BESTAETIGEN,

    // Nov 9 (field test 1.8): deliberately NO button for "Cancel" in the confirm window – the bot never taps it
    // ("Decline after Bid does nothing there"), but closes with the red X ([SCHLIESSEN]).
    FILTER_OK, SORT_PREIS_AUF,

    /** Nov 9: sort order "Awakened Tier ↑" – so the copies that end soon are at the top. */
    SORT_RANG_AUF,

    /** Filter window: left and right price field (Price Range). */
    FILTER_MIN, FILTER_MAX,

    /** Secondary password window: input field and "Verify" (never "Forgot Password"). */
    PASSWORT_FELD, PASSWORT_OK,

    /**
     * Nov 22 (buyout): the yellow "Buyout Price" button in the Bid window. Only offered when it is YELLOW on THIS
     * screenshot (it is gray when the copy has no buyout). The bot taps it only with buyout switched on.
     */
    SOFORTKAUF,
}

/** What the bidding list is sorted by (label of the sort button). */
enum class Sortierung { PREIS, RANG, UNBEKANNT }

/**
 * A message in a dark band in the middle of the screen (disappears after a few seconds).
 * [yOben]/[yUnten]: where the band is (0/0 = not known) – rows there are covered.
 */
data class Meldung(val breite: Int, val yOben: Int = 0, val yUnten: Int = 0) {
    /** Short message in the Bid window = maybe "Server status code:-7" = already 50 bids (Nov 8). */
    val kurz: Boolean get() = breite < KURZ_GRENZE

    /**
     * Nov 9 (field test 1.8): as wide as the real "-7" message? Not every short message is the limit
     * (after the secondary password a different one came) – the bot only believes it together with My Biddings, see AuktionsBot.
     */
    val wieLimit: Boolean get() = abs(breite - LIMIT_BREITE) <= LIMIT_TOLERANZ

    /**
     * Nov 9 (field test 1.9): "Bidding successful. Check it in My Biddings." comes after every bid,
     * stays 3–4 s over the list (1224 px wide on the reference phone) and confirms the bid.
     */
    val wieErfolg: Boolean get() = abs(breite - ERFOLG_BREITE) <= ERFOLG_TOLERANZ

    /**
     * Nov 22 (rate limit): two lines of text, like "Bid Failure: You have lost the bid for an awakened … at tier C-!"
     * (196 px high, 1524–1542 px wide on the real screenshots). One-line messages are 138–146 px high and at most ~1430 px
     * wide; the game wraps longer texts at ~1540 px, so a band from [FEHLSCHLAG_MIN_BREITE] on is a wrapped one too.
     */
    val wieFehlschlag: Boolean get() = (yUnten > yOben && yUnten - yOben >= ZWEIZEILIG_MIN) || breite >= FEHLSCHLAG_MIN_BREITE

    /**
     * Nov 22 (rate limit): "Operated too frequently. Please retry in N second(s)." – one line, 1398–1428 px wide on the
     * real report screenshots (testbilder/berichte/20-42-45_bild_08 … _11, 21-46-15_bild_03 … _06). Wider than
     * "Bidding successful" (1152–1248), narrower than "Bid Failure" (two lines, from 1524).
     */
    val wieZuOft: Boolean get() = !wieFehlschlag && abs(breite - ZU_OFT_BREITE) <= ZU_OFT_TOLERANZ

    /** Nov 22: a message the bot knows over the list ("Bidding successful", "Bid Failure") – it keeps bidding through it. */
    val bekannt: Boolean get() = wieErfolg || wieFehlschlag

    /**
     * Nov 23 (review: unknown messages in the Bid window were tapped through): a message the bot knows in the Bid or confirm
     * window – "Bidding successful", "Bid Failure" or the form of "Server status code:-7" (short, ~762 px; the short message
     * right after the secondary password has the same form, field test 1.8). These are handled as before; every other band
     * there pauses the bot, and Bid, Buyout and Confirm are never tapped under it.
     */
    val bekanntImFenster: Boolean get() = bekannt || (kurz && wieLimit)

    /**
     * Does the band cover a row whose card starts at [kartenOben]? What counts is the content of the card
     * (numbers, image), not the border: if content and band overlap by at least [VERDECKT_MIN] px, it is covered.
     * A few pixels are enough: "Bid Failure" (taller band) touches the top of row 3 and makes the price and
     * attack value unreadable there – better treat it as covered (then the remembered values apply) than half-read.
     */
    fun verdeckt(kartenOben: Int): Boolean = ueberlappung(kartenOben) >= VERDECKT_MIN

    /** How many pixels (vertically) the band covers the card content at [kartenOben] (0 = not at all). */
    fun ueberlappung(kartenOben: Int): Int {
        if (yUnten <= yOben) return 0
        val von = maxOf(kartenOben + KARTE_OBEN, yOben)
        val bis = minOf(kartenOben + KARTE_UNTEN, yUnten)
        return (bis - von).coerceAtLeast(0)
    }

    companion object {
        /** "Bid Failure: …" is about 1540 px wide, "Server status code:-7" about 760 px (measured on the reference phone). */
        const val KURZ_GRENZE = 1350
        const val LIMIT_BREITE = 762
        const val LIMIT_TOLERANZ = 150

        /** "Bidding successful. Check it in My Biddings." – 1224 px on the reference phone (field test 1.9, two screenshots). */
        const val ERFOLG_BREITE = 1224
        const val ERFOLG_TOLERANZ = 100

        /** Nov 22: "Operated too frequently. Please retry in N second(s)." – 1398–1428 px (8 real screenshots). */
        const val ZU_OFT_BREITE = 1415
        const val ZU_OFT_TOLERANZ = 70

        /** Nov 22: from this height (px between the two bright lines) a band holds two lines of text (one line: 138–146). */
        const val ZWEIZEILIG_MIN = 175

        /** Nov 22: a band at least this wide is a wrapped, two-line text ("Bid Failure"), whatever its measured height. */
        const val FEHLSCHLAG_MIN_BREITE = 1500
        const val VERDECKT_MIN = 8

        /** Content of a row: from this far below to this far below the top edge of the card (2712×1220). */
        const val KARTE_OBEN = 5
        const val KARTE_UNTEN = 145
    }
}

/**
 * Nov 9 (field test 1.9): a number was there (spots in the font color), but could not be read.
 * The app puts a full-resolution crop of it into the next error report (folder lernziffern/),
 * so that the missing digits can be learned from real screenshots.
 * [feld]: what it is ("preis", "zeit", "sofort", "wert", "sg", "aktuellesGebot", "bestaetigen", "zaehler").
 * [kontext]: how to recognize the spot (e.g. row and copy), goes into the file name.
 */
data class UnlesbareZahl(val feld: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val kontext: String = "")

/**
 * A row (one copy) in the bidding list or in My Biddings.
 *
 * @param werte     attack, HP, speed (the three numbers on the left) – the bot recognizes the copy by them.
 * @param stufe     letter of the rank badge on the image (C, B, A …) – without +/-.
 * @param ueberbotenKreuz red cross at the top right (only in My Biddings): outbid.
 * @param kreuzPunkt Nov 11: center of this red X (to dismiss it), found on THIS screenshot – null = no X.
 */
data class Zeile(
    val oben: Int,
    val preis: Int?,
    val sofortPreis: Int?,
    val restzeitSek: Int?,
    val hatRestzeit: Boolean,
    val werte: List<Int?>,
    val zeichen: Zeichen,
    val ueberbotenKreuz: Boolean = false,
    val stufe: Char? = null,
    val tippen: Punkt,
    /**
     * Nov 9 (field test 1.9): a message ("Bidding successful …") lies over this row – the numbers
     * on it are not readable. The bot then uses what it remembered about the row and taps through the
     * message (tested: that works).
     */
    val verdeckt: Boolean = false,
    val kreuzPunkt: Punkt? = null,
) {
    /** How the bot recognizes the copy again (null if the values are not readable). */
    val id: String? get() = if (werte.size == 3 && werte.all { it != null }) werte.joinToString("-") else null

    /** Short description for the log. */
    fun text(): String = "${id ?: "?"} · ${preis ?: "?"} SG · " +
        (restzeitSek?.let { dauerText(it) } ?: if (hatRestzeit) "time ?" else "no time") +
        (if (zeichen != Zeichen.KEINS) " · $zeichen" else "") +
        if (ueberbotenKreuz) " · X" else ""
}

/** A rank selected in the filter window. */
data class FilterFeld(val stufe: String, val punkt: Punkt, val gewaehlt: Boolean)

/** Everything recognition found on a screenshot. */
data class Bildschirm(
    val ort: Ort,
    val zeilen: List<Zeile> = emptyList(),
    /** Starry Gems at the top right. */
    val sg: Int? = null,
    /** "Bidding Items: N" in My Biddings. */
    val laufendeGebote: Int? = null,
    /** "No bidding items." in My Biddings. */
    val keineGebote: Boolean = false,
    val meldung: Meldung? = null,
    /** Bid window: "Current Bid: N". */
    val aktuellesGebot: Int? = null,
    /** Confirm window: "Starry Gems spent: N". */
    val bestaetigenPreis: Int? = null,
    /** Nov 22 (buyout): Bid window "Buyout Price: N" (null = not readable or "--"). */
    val sofortPreis: Int? = null,
    val knoepfe: Map<Knopf, Punkt> = emptyMap(),
    val filterFelder: List<FilterFeld> = emptyList(),
    /** Filter window: how many characters are in the price fields (e.g. "10" = 2, "99999999" = 8). */
    val filterMinStellen: Int? = null,
    val filterMaxStellen: Int? = null,
    /** Bidding list: label of the sort button. */
    val sortierung: Sortierung = Sortierung.UNBEKANNT,
    /** Swipe path to push the list further down (from, to). */
    val wischWeg: Pair<Punkt, Punkt>? = null,
    /** Nov 9: numbers that were there but not readable – for the learning images in the error report. */
    val unlesbar: List<UnlesbareZahl> = emptyList(),
    /**
     * Nov 11: size of the screenshot that all points (buttons, [Zeile.tippen], swipe path) refer to.
     * [Zeile.oben], on the other hand, is always in reference pixels (2712 × 1220). Needed for the tap lock at the top
     * and to convert swipe paths from reference pixels.
     */
    val bildBreite: Int = io.github.hustlebot.ihauctionbot.kern.bild.Bild.REF_BREITE,
    val bildHoehe: Int = io.github.hustlebot.ihauctionbot.kern.bild.Bild.REF_HOEHE,
) {
    fun knopf(k: Knopf): Punkt? = knoepfe[k]

    /** Point in reference pixels (2712 × 1220) → point on this screenshot. */
    fun ausReferenz(p: Punkt): Punkt = Punkt(
        p.x * bildBreite / io.github.hustlebot.ihauctionbot.kern.bild.Bild.REF_BREITE,
        p.y * bildHoehe / io.github.hustlebot.ihauctionbot.kern.bild.Bild.REF_HOEHE,
    )

    /** Point on this screenshot → reference pixels. */
    fun inReferenz(p: Punkt): Punkt = Punkt(
        p.x * io.github.hustlebot.ihauctionbot.kern.bild.Bild.REF_BREITE / bildBreite,
        p.y * io.github.hustlebot.ihauctionbot.kern.bild.Bild.REF_HOEHE / bildHoehe,
    )

    companion object {
        val UNBEKANNT = Bildschirm(Ort.UNBEKANNT)
    }
}

/** Reads the current screen. The Android app provides the real recognition, tests provide fakes. */
fun interface BildschirmLeser {
    fun lies(): Bildschirm
}

/** All ranks that can be selected in the filter (Nov 8: only C- to SSS exist in the filter). */
val KOPIE_TYPEN: List<String> = listOf("C-", "C", "C+", "B-", "B", "B+", "A-", "A", "A+", "S", "SS", "SSS")

/** The price goes up by this much SG with every bid (Nov 7: 17 → outbid at 18 → bidding again costs 19). */
const val GEBOTS_SCHRITT = 1

/** An offer, the way the decider needs it. */
data class Angebot(
    val id: String,
    val typ: String,
    val menge: Int = 1,
    val preis: Int,
    val restzeitSek: Int? = null,
    val eigenesGebot: Boolean = false,
    val ueberboten: Boolean = false,
    val naechsterPreis: Int? = null,
    val bietenKnopf: Punkt? = null,
) {
    /** What the next bid costs: the shown value, otherwise current price + 1 SG. */
    val gebotsPreis: Int get() = naechsterPreis ?: (preis + GEBOTS_SCHRITT)

    /** Price per copy for a total price (default: the next bid). */
    fun preisProKopie(gesamt: Int = gebotsPreis): Double = gesamt.toDouble() / menge.coerceAtLeast(1)
}

/** Remaining time as text, e.g. "1 h 35 min" or "3 min 20 s". */
fun dauerText(sek: Int): String {
    val h = sek / 3600
    val m = sek % 3600 / 60
    val s = sek % 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min $s s"
        else -> "$s s"
    }
}
