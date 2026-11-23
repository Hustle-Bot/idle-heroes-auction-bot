package io.github.hustlebot.ihauctionbot.kern

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** Screenshot + recognition (report from Nov 11: 60–100 ms) – only after that does a tap reach the game. */
const val SIM_ERKENNUNG_MS = 85L

/** Android does not deliver a new screenshot faster than every ~0.38 s (report from Nov 11: 363 ms). */
const val SIM_FOTO_ABSTAND_MS = 380L

/**
 * Nov 11: one run of the bot in the [DauerSpiel] over many (simulated) hours – without real waiting.
 * After every step it is checked whether the bot is stuck, and at the end whether it stopped for a clear reason.
 */
class DauerLauf(val sz: Szenario, val seed: Long, val takt: Takt = Takt()) {
    val start: Instant = LocalDateTime.of(2026, 11, 12, 8, 0).atZone(BERLIN).toInstant()
    val sim = DauerSpiel(seed, start, sz)
    private val log = ArrayDeque<String>()
    val probleme = ArrayList<String>()
    var stopp: StoppGrund? = null
        private set
    var stoppUm: Instant? = null
        private set
    var schritte = 0
        private set
    var handlungen = 0
        private set

    /** Longest time without an action (ms) and during what. */
    var laengsteRuhe = 0L
        private set
    var laengsteRuheBei = ""
        private set
    var eintraegeZuHoch = 0
        private set
    var eintraegeZuNiedrig = 0
        private set
    var gesperrteTipps = 0
        private set

    /** The last steps when our own count was far too low for the first time (to look into). */
    var zaehlungSpur: String? = null
        private set

    val bot = AuktionsBot(
        sz.regeln,
        StoppBedingung(
            sz.zielKopien,
            sz.endeNachMin?.let { LocalTime.from(start.plusSeconds(it * 60).atZone(BERLIN)) },
            sz.sgBudget,
            zone = BERLIN,
        ),
        sim,
        takt = takt,
        protokoll = { notiz(it) },
        hatZweitpasswort = true,
        maxNeuverbindungen = sz.maxNeuverbindungen,
        buyoutAn = sz.buyoutAn,
    )

    init {
        sim.beiVerstoss = { problem("Violation: $it") }
    }

    private fun notiz(text: String) {
        log.addLast("${DauerSpiel.uhr(sim.jetzt)} $text")
        if (log.size > 250) log.removeFirst()
    }

    private fun problem(text: String) {
        if (probleme.size < 20) probleme += "$text\n      last steps:\n" + log.takeLast(60).joinToString("\n") { "        $it" }
    }

    fun los(): DauerLauf {
        val ende = start.plusMillis((sz.stunden * 3_600_000).toLong())
        var letzteHandlung = start
        var kandidatSeit: Instant? = null
        var naechstePruefung = start
        var gebotZahl = 0
        while (sim.jetzt.isBefore(ende)) {
            val t0 = sim.jetzt
            val pauseVorher = bot.zuOftPauseBis
            val e = bot.schritt(t0)
            schritte++
            val ms: Long = when (e) {
                is Ergebnis.Handeln -> {
                    handlungen++
                    // Nov 22 (rate limit): during the pause after "Operated too frequently" the bot taps nothing
                    // (the "Lost connection" dialog has its own waiting time and is not part of it).
                    val pause = listOfNotNull(pauseVorher, bot.zuOftPauseBis).maxOrNull()
                    if (pause != null && t0.isBefore(pause) && !e.aktion.grund.startsWith("Confirm (reconnect")) {
                        problem("Action during the pause after \"too frequently\" (until ${DauerSpiel.uhr(pause)}): ${e.aktion.grund}")
                    }
                    notiz("→ ${e.aktion.grund}")
                    sim.ausfuehren(e.aktion, t0.plusMillis(SIM_ERKENNUNG_MS))
                    val a = e.aktion
                    if (a is Aktion.Eingeben || a is Aktion.PasswortEingeben || (a is Aktion.Folge && a.hatEingaben)) {
                        bot.eingabeErgebnis(sim.letzteEingabeOk)
                    }
                    val ruhe = Duration.between(letzteHandlung, t0).toMillis()
                    letzteHandlung = t0
                    if (ruhe > laengsteRuhe) {
                        laengsteRuhe = ruhe
                        laengsteRuheBei = "until ${DauerSpiel.uhr(t0)} (${log.lastOrNull { !it.contains("→") } ?: ""})"
                    }
                    val folge = (a as? Aktion.Folge)?.let { it.schritte.size * it.abstandMs } ?: 0
                    e.naechsterSchrittMs + folge
                }
                is Ergebnis.Warten -> {
                    notiz("… ${e.grund}")
                    if (DEBUG_TEXT != null && e.grund.contains(DEBUG_TEXT) && debugAuszuege < 4 && log.takeLast(2).all { DEBUG_TEXT in it }) {
                        debugAuszuege++
                        File("build/dauerlauf-debug.txt").appendText("=== ${sz.name} ${seed}\n" + log.takeLast(30).joinToString("\n") + "\n")
                    }
                    e.ms
                }
                is Ergebnis.Unsicher -> {
                    notiz("? ${e.grund}")
                    e.ms
                }
                is Ergebnis.Fertig -> {
                    stopp = e.grund
                    stoppUm = t0
                    break
                }
                Ergebnis.Pausiert -> 1000
            }
            val schrittMs = maxOf(ms + SIM_ERKENNUNG_MS, SIM_FOTO_ABSTAND_MS)
            sim.jetzt = t0.plusMillis(schrittMs)
            zeitFuer(e, schrittMs)

            // Every 5 s (simulated): is the bot stuck? Is its count roughly right?
            if (!sim.jetzt.isBefore(naechstePruefung)) {
                naechstePruefung = sim.jetzt.plusSeconds(5)
                if (sim.gebote.size != gebotZahl) {
                    gebotZahl = sim.gebote.size
                    kandidatSeit = null
                }
                val rest = sz.sgBudget?.let { it - sim.nettoAusgegeben }
                // Nov 22: while the game cannot be used (lost connection …), nobody can bid.
                val k = if (stopp == null && !sim.gesperrt()) sim.kandidat(bot.aktiveRegeln, rest) else null
                if (k == null) {
                    kandidatSeit = null
                } else if (kandidatSeit == null) {
                    kandidatSeit = sim.jetzt
                } else if (Duration.between(kandidatSeit, sim.jetzt).toMinutes() >= HAENGER_MIN) {
                    problem("Stuck: for $HAENGER_MIN min there would have been something to bid on (e.g. ${k.stufe} ${k.id} for ${k.preis} SG, ${sim.restSek(k)} s left), but no bid")
                    kandidatSeit = sim.jetzt
                }
                val wahr = sim.meineListe().size
                eintraegeZuHoch = maxOf(eintraegeZuHoch, bot.gebote.eintraege - wahr)
                if (wahr - bot.gebote.eintraege > eintraegeZuNiedrig) {
                    eintraegeZuNiedrig = wahr - bot.gebote.eintraege
                    if (eintraegeZuNiedrig >= 15 && zaehlungSpur == null) {
                        zaehlungSpur = "true $wahr, bot ${bot.gebote.eintraege}\n" + log.takeLast(80).joinToString("\n")
                    }
                }
            }
            if (Duration.between(letzteHandlung, sim.jetzt).toMinutes() >= RUHE_MAX_MIN && !legitimesWarten()) {
                problem("No action for $RUHE_MAX_MIN min")
                letzteHandlung = sim.jetzt
            }
        }
        gesperrteTipps = bot.gesperrteTipps
        // Ping-pong: the same copy opened often without a bid arriving.
        for ((id, zeiten) in sim.oeffnungenOhneGebot) {
            val dicht = zeiten.windowed(PING_PONG_OEFFNUNGEN).any { Duration.between(it.first(), it.last()).toMinutes() < 10 }
            if (dicht) problem("Ping-pong: $id opened ${zeiten.size} times without a bid arriving")
        }
        stopp?.let { g ->
            if (g !in sz.erwarteteStopps) problem("Unexpected stop: ${g.text}")
        }
        // Nov 23 (review: "no lasting minimum gap between reloads"): never two reloads of the list (refresh, filter Confirm,
        // sort order) less than 5 s apart – and the flow waits by itself (the last safety net in the bot never has to step in).
        if (sim.abfragenZuDicht > 0) {
            problem("${sim.abfragenZuDicht} reloads of the list less than ${DauerSpiel.ABFRAGE_MIN_MS / 1000} s apart (shortest ${sim.kuerzesterAbfrageAbstandMs} ms)")
        }
        if (bot.abfrageGebremst > 0) problem("Reload held back by the last safety net ${bot.abfrageGebremst} times (a path without the gap)")
        return this
    }

    /** Nov 11 (speed): where the time goes – the sum in ms per kind of step. */
    val zeiten = HashMap<String, Long>()

    private fun zeitFuer(e: Ergebnis, ms: Long) {
        val text = when (e) {
            is Ergebnis.Handeln -> "→ " + e.aktion.grund
            is Ergebnis.Warten -> "… " + e.grund
            is Ergebnis.Unsicher -> "? " + e.grund
            else -> return
        }
        val art = text.substringBefore(" for ").substringBefore("(").substringBefore(" – taking").replace(Regex("[0-9]+"), "#").trim().take(60)
        zeiten.merge(art, ms, Long::plus)
    }

    fun zeitTabelle(n: Int = 14): String {
        val gesamt = zeiten.values.sum().coerceAtLeast(1)
        return zeiten.entries.sortedByDescending { it.value }.take(n).joinToString("\n") { (k, v) ->
            String.format(java.util.Locale.GERMANY, "    %5.1f %%  %7.1f s/bid  %s", 100.0 * v / gesamt, v / 1000.0 / sim.gebote.size.coerceAtLeast(1), k)
        }
    }

    /** Is the bot waiting for a long time right now for a good reason (50 bids, too little SG, budget planned)? */
    private fun legitimesWarten(): Boolean {
        val zeile = log.lastOrNull { it.contains("… ") } ?: return false
        return listOf("50 bids", "Too little SG", "Budget planned", "Goal").any { it in zeile }
    }

    val minuten: Double get() = Duration.between(start, stoppUm ?: sim.jetzt).toMillis() / 60_000.0
    val geboteProMinute: Double get() = sim.gebote.size / minuten.coerceAtLeast(0.01)

    /** Nov 22 (refresh rate): refreshes that reached the game per hour. */
    val aktualisierungenProStunde: Double get() = sim.aktualisierungen / (minuten / 60).coerceAtLeast(0.01)

    fun zusammenfassung(): String = String.format(
        java.util.Locale.GERMANY,
        "%-34s Seed %-3d %5.1f h · %5d bids (%.2f/min) · won %4d (bot estimates %4d), in front open %3d · -7: %3d · Bid Failure %3d · My Biddings %3d× · X %3d · " +
            "loading %3d · password again %d · swipes %d (exactly 5 new: %d) · through message %d · missed taps %d, in transition %d, locked %d · longest quiet %.1f min · count +%d/−%d · SG %d → %d · " +
            "lost connection %d, reconnects %d · notifications %d · pop-ups %d · restarts %d · bought out %d (bot %d) · " +
            "refreshes %d (%.0f/h, reloads %d incl. menu %d, closer than 5 s %d) · too frequently %d (bot %d, after a reload %d) · " +
            "bid field checked %d, not checked %d, raised %d (closed %d, not seen %d, above max %d, own bid outbid %d) · " +
            "SG net %d (budget %s) · stop: %s · problems: %d",
        sz.name, seed, minuten / 60, sim.gebote.size, geboteProMinute, sim.gewonnen, bot.gebote.gekaufteKopien, sim.vorneOffen(), sim.limitMeldungen,
        sim.bidFehlschlaege, sim.meineGeboteGeoeffnet, sim.kreuze, sim.ladenEreignisse, sim.neueSitzungen, sim.hauptWischer, sim.genauFuenfNeu, sim.tippsDurchMeldung, sim.fehltipps, sim.tippsImUebergang,
        gesperrteTipps, laengsteRuhe / 60_000.0, eintraegeZuHoch, eintraegeZuNiedrig, sim.startSg, sim.sg,
        sim.trennungen, bot.neuverbindungen, sim.benachrichtigungen, sim.popups, sim.neustarts, sim.sofortGekauft, bot.sofortGekauft,
        sim.aktualisierungen, aktualisierungenProStunde, sim.listenAbfragen, sim.navAbfragen, sim.abfragenZuDicht, sim.zuOftMeldungen, bot.zuOftMeldungen,
        sim.zuOftBeiAbfrage, bot.bietFeldGeprueft, bot.bietFeldUngeprueft, sim.feldHoeherFenster, bot.bietFeldFalsch,
        sim.feldHoeherUnerkannt, sim.ueberMaxUnerkannt, sim.selbstUeberbotenUnerkannt, sim.nettoAusgegeben, sz.sgBudget?.toString() ?: "-", stopp?.name ?: "-", probleme.size,
    )

    private var debugAuszuege = 0

    companion object {
        /** Only for looking into: extracts when this text comes as the waiting reason three times in a row. */
        val DEBUG_TEXT: String? = System.getenv("DAUERLAUF_DEBUG")
        const val HAENGER_MIN = 5L
        const val RUHE_MAX_MIN = 10L
        const val PING_PONG_OEFFNUNGEN = 4
    }
}

/**
 * Nov 11: the bot runs alone for hours (long unattended run). These tests let it run for many simulated hours in the
 * rebuilt auction house ([DauerSpiel]) – with everything that happens in the real game – and check at every
 * step: never tap into the purchase bar at the top, never Cancel/Buyout, no bid above max price+1, above the SG or
 * above the budget, never get stuck, and every stop has a clear reason.
 *
 * The results (bids per minute etc.) are written to kern/build/dauerlauf.txt afterwards.
 */
class DauerlaufTest {

    private val c = Suchregel("C-", 10, 20, maxRestzeitMin = 5)
    private val b = Suchregel("B-", 10, 60, maxRestzeitMin = 5)

    /** For a quick run (e.g. on GitHub) the duration can be shortened with DAUERLAUF_FAKTOR. */
    private val faktor = System.getenv("DAUERLAUF_FAKTOR")?.toDoubleOrNull() ?: 1.0

    private fun pruefe(sz: Szenario, vararg seeds: Long): List<DauerLauf> {
        val laeufe = seeds.map { DauerLauf(sz.copy(stunden = sz.stunden * faktor), it).los() }
        bericht(laeufe)
        val fehler = laeufe.filter { it.probleme.isNotEmpty() }
        assertTrue(fehler.isEmpty()) {
            fehler.joinToString("\n\n") { l -> l.zusammenfassung() + "\n" + l.probleme.joinToString("\n") { "  - $it" } }
        }
        return laeufe
    }

    private fun bericht(laeufe: List<DauerLauf>) {
        val datei = File("build/dauerlauf.txt")
        synchronized(DauerlaufTest::class.java) {
            datei.parentFile?.mkdirs()
            datei.appendText(laeufe.joinToString("\n", postfix = "\n") { it.zusammenfassung() })
            File("build/dauerlauf-zeiten.txt").appendText(
                laeufe.joinToString("\n", postfix = "\n") { "Time per bid (${it.sz.name}, seed ${it.seed}, ${String.format(java.util.Locale.GERMANY, "%.2f", it.geboteProMinute)} bids/min):\n" + it.zeitTabelle() },
            )
            laeufe.mapNotNull { l -> l.zaehlungSpur?.let { "=== ${l.sz.name} Seed ${l.seed}\n$it" } }.takeIf { it.isNotEmpty() }?.let {
                File("build/dauerlauf-zaehlung.txt").appendText(it.joinToString("\n", postfix = "\n"))
            }
        }
    }

    @Test
    fun `normal market - two rules, 10 hours`() {
        pruefe(Szenario("normal B- and C-", listOf(b, c), stunden = 10.0, startSg = 80_000), 1, 2, 11)
    }

    @Test
    fun `many bids - 50 limit, My Biddings with red X, 8 hours`() {
        pruefe(
            Szenario(
                "50 limit", listOf(Suchregel("C-", 10, 20, maxRestzeitMin = 60)), stunden = 8.0, startSg = 60_000,
                ankunft = mapOf("C-" to 4.0, "B-" to 1.0),
            ),
            3, 4, 12,
        )
    }

    @Test
    fun `many messages - a foreign message every few seconds, also over the Bid window, 8 hours`() {
        // Nov 11 (field test 1.20): outbid 7 times in a few minutes – a message all the time.
        pruefe(Szenario("many messages", listOf(b, c), stunden = 8.0, startSg = 80_000, fremdeMeldungAlleS = 4.0), 31, 32)
    }

    @Test
    fun `swiping too far - list moves 1,3 times as far as dragged, 6 hours`() {
        // Nov 11 (field test 1.20): "corrects nicely, but scrolls clearly too far".
        pruefe(Szenario("swipe 1.3x", listOf(b, c), stunden = 6.0, startSg = 80_000, wischUeberschuss = 1.3), 41, 42)
    }

    @Test
    fun `stutter - rarely the list slides one whole row further, no copy missing`() {
        // Nov 12 (review of 1.31): if the list slides on evenly after every swipe and ONCE a whole row
        // further (3 % of the swipes), the wrong row fits the grid just as well – the bot must not be "certain" then.
        pruefe(
            Szenario(
                "stutter", listOf(b, c), stunden = 6.0, startSg = 80_000, wischUeberschuss = 1.05, wischUeberschussKurz = 1.0,
                wischStreuung = 0.025, rutschImmer = 75, rutschAusreisser = 0.03,
            ),
            51, 52, 53,
        )
    }

    @Test
    fun `wide stutter - rarely the list slides 150 to 400 pixels further, no copy missing`() {
        pruefe(
            Szenario(
                "wide stutter", listOf(b, c), stunden = 6.0, startSg = 80_000, wischUeberschuss = 1.05, wischUeberschussKurz = 1.0,
                wischStreuung = 0.025, rutschImmer = 75, rutschAusreisser = 0.03, rutschAusreisserPx = 150..400,
            ),
            51, 52, 53,
        )
    }

    @Test
    fun `field test 1,31 - list visibly slides on after every swipe, short swipes further, 6 hours`() {
        // Nov 12 (field test 1.31): long swipes move the list 1.14 times as far (with sliding on), short ones about 1.3 times;
        // after every swipe it visibly slides on for a good 1 s.
        pruefe(
            Szenario(
                "field test 1.31", listOf(b, c), stunden = 6.0, startSg = 80_000, wischUeberschuss = 1.05, wischUeberschussKurz = 1.0,
                wischStreuung = 0.025, rutschImmer = 75,
            ),
            51, 52,
        )
    }

    @Test
    fun `stress - often loading screen, often price jumps, often without confirm, 12 hours`() {
        pruefe(
            Szenario(
                "stress", listOf(b, c), stunden = 12.0, startSg = 120_000, haengerProStunde = 6.0, preisSprung = 0.15,
                ohneBestaetigen = 0.6, wettbewerb = 0.6,
            ),
            21, 22,
        )
    }

    @Test
    fun `little SG - waits without harm, stops when nothing can come back any more`() {
        pruefe(
            Szenario(
                "little SG", listOf(c), stunden = 12.0, startSg = 150, endeNachMin = 6 * 60,
                erwarteteStopps = setOf(StoppGrund.SG_ALLE, StoppGrund.ZEIT_ERREICHT),
            ),
            5,
        )
    }

    @Test
    fun `SG budget - never more planned, stops when it is spent`() {
        val l = pruefe(
            Szenario(
                "budget 600 SG", listOf(b, c), stunden = 12.0, sgBudget = 600,
                erwarteteStopps = setOf(StoppGrund.BUDGET_ERREICHT),
            ),
            6,
        ).single()
        budgetAusgeschoepft(l, 600)
    }

    /**
     * Nov 23 (review: "the SG budget can grow"): budget runs stop because the budget is used up – net (bought + in front),
     * not earlier: never more than the budget (checked at every bid by [DauerSpiel]), and not more than one expensive copy less.
     */
    private fun budgetAusgeschoepft(l: DauerLauf, budget: Int) {
        if (faktor < 1.0) return
        assertTrue(l.stopp == StoppGrund.BUDGET_ERREICHT) { l.zusammenfassung() }
        assertTrue(l.sim.nettoAusgegeben in budget - 70..budget) { "net ${l.sim.nettoAusgegeben} of $budget: ${l.zusammenfassung()}" }
    }

    @Test
    fun `SG budget - SG topped up during the run do not add to the budget`() {
        // Nov 23 (review): before, the budget was "start balance − balance now" – 5000 SG topped up after 10 minutes made
        // 5600 SG of budget out of 600.
        for (seed in listOf(6L, 61L)) {
            val l = pruefe(
                Szenario(
                    "budget 600, top-up 5000 SG", listOf(b, c), stunden = 12.0, sgBudget = 600, aufladenNachMin = 10, aufladenSg = 5000,
                    erwarteteStopps = setOf(StoppGrund.BUDGET_ERREICHT),
                ),
                seed,
            ).single()
            assertTrue(l.sim.aufgeladen == 5000 || faktor < 1.0) { l.zusammenfassung() }
            budgetAusgeschoepft(l, 600)
        }
    }

    @Test
    fun `SG budget - start balance misread (a digit lost on the first images) does not add to the budget`() {
        // Nov 23 (review): "3000" read twice for 30000 at the start – before, 27000 SG of extra budget.
        val l = pruefe(
            Szenario(
                "budget 600, start misread", listOf(b, c), stunden = 12.0, sgBudget = 600, sgStartFalschBilder = 6,
                erwarteteStopps = setOf(StoppGrund.BUDGET_ERREICHT),
            ),
            6,
        ).single()
        // The start is raised to balance + what the run used by its own bookkeeping – the real start, or a little above it.
        assertTrue(faktor < 1.0 || l.bot.startSg!! in l.sim.startSg..l.sim.startSg + 100) { "start ${l.bot.startSg}: ${l.zusammenfassung()}" }
        budgetAusgeschoepft(l, 600)
    }

    @Test
    fun `price fields fail for 40 minutes, then work again - the bot gets both lists back`() {
        // Nov 23 (coordinator): before, the price fields were given up for good after 3 failed inputs in a row. The game's
        // filter kept the other rule's prices – with C- 10–20 and B- 30–60 one rank's list stayed empty for the rest of the night.
        val b3060 = Suchregel("B-", 30, 60, maxRestzeitMin = 5)
        val laeufe = pruefe(
            Szenario("price fields fail 60-100 min", listOf(b3060, c), stunden = 4.0, startSg = 80_000, eingabeKaputtMin = 60..100),
            1, 2,
        )
        if (faktor >= 1.0) for (l in laeufe) {
            val kaputt = l.start.plusSeconds(60 * 60)
            val wieder = l.start.plusSeconds(100 * 60)
            assertTrue(l.bot.preisfelderGehen == true && l.bot.preisfelderNeuVersuche > 0) { l.zusammenfassung() }
            for (stufe in listOf("B-", "C-")) {
                assertTrue(l.sim.gebotStufen.any { it.second == stufe && it.first.isBefore(kaputt) }) { "$stufe before: ${l.zusammenfassung()}" }
                // Both lists are back within a few minutes after typing works again (not only after the 10–40 min pause).
                val erstes = l.sim.gebotStufen.firstOrNull { it.second == stufe && it.first.isAfter(wieder) }?.first
                assertTrue(erstes != null && Duration.between(wieder, erstes).toMinutes() < 6) { "$stufe after: first bid $erstes – ${l.zusammenfassung()}" }
            }
        }
    }

    @Test
    fun `goal copies - stops when enough are bought`() {
        pruefe(
            Szenario(
                "goal 15 copies", listOf(b, c), stunden = 12.0, zielKopien = 15,
                erwarteteStopps = setOf(StoppGrund.ZIEL_ERREICHT),
            ),
            7,
        )
    }

    @Test
    fun `connection lost - stop right away, tap nothing afterwards`() {
        // Nov 22: with 0 reconnects (the behaviour before).
        val l = pruefe(
            Szenario(
                "connection lost after 2 h", listOf(b, c), stunden = 6.0, verbindungWegNachMin = 120, maxNeuverbindungen = 0,
                erwarteteStopps = setOf(StoppGrund.VERBINDUNG_WEG),
            ),
            8,
        ).single()
        if (faktor >= 1.0) {
            assertTrue(l.stopp == StoppGrund.VERBINDUNG_WEG) { "No stop because of the connection: ${l.zusammenfassung()}" }
            val nach = Duration.between(l.start.plusSeconds(120 * 60), l.stoppUm).toMillis()
            assertTrue(nach in 0..2000) { "stop only $nach ms after the connection was lost" }
        }
    }

    // ---------------- Nov 22: overnight (owner: the bot runs for hours unattended) ----------------

    @Test
    fun `connection gone for good - 5 times Confirm after waiting, then stop`() {
        val l = pruefe(
            Szenario(
                "connection gone for good", listOf(b, c), stunden = 4.0, verbindungWegNachMin = 120,
                erwarteteStopps = setOf(StoppGrund.VERBINDUNG_WEG),
            ),
            8,
        ).single()
        if (faktor >= 1.0) {
            assertTrue(l.stopp == StoppGrund.VERBINDUNG_WEG) { l.zusammenfassung() }
            assertTrue(l.sim.confirmGetippt == 5 && l.bot.neuverbindungen == 5) { l.zusammenfassung() }
        }
    }

    @Test
    fun `overnight - lost connection every 30 to 60 min, Confirm leads back - 5 reconnects, bids on each time, the 6th stops`() {
        val laeufe = pruefe(
            Szenario(
                "reconnect every 30–60 min", listOf(b, c), stunden = 8.0, startSg = 80_000, verbindungWegAlleMin = 30..60,
                erwarteteStopps = setOf(StoppGrund.VERBINDUNG_WEG),
            ),
            71, 72, 73,
        )
        if (faktor >= 1.0) {
            for (l in laeufe) {
                assertTrue(l.stopp == StoppGrund.VERBINDUNG_WEG) { l.zusammenfassung() }
                assertTrue(l.bot.neuverbindungen == 5 && l.sim.confirmGetippt == 5 && l.sim.trennungen == 6) { l.zusammenfassung() }
                // After every reconnect the bot bid again (filter and sort order set again).
                val zeiten = l.sim.confirmZeiten
                for ((i, um) in zeiten.withIndex()) {
                    val bis = zeiten.getOrNull(i + 1) ?: l.stoppUm!!
                    assertTrue(l.sim.gebote.any { it.first.isAfter(um) && it.first.isBefore(bis) }) {
                        "no bid after reconnect ${i + 1}: ${l.zusammenfassung()}"
                    }
                }
            }
        }
    }

    @Test
    fun `lost connection - Confirm leads to a screen the bot does not know - no tap, stop after 3 minutes`() {
        val laeufe = pruefe(
            Szenario(
                "reconnect into the unknown", listOf(b, c), stunden = 3.0, verbindungWegAlleMin = 30..60, nachConfirmUnbekannt = true,
                erwarteteStopps = setOf(StoppGrund.VERBINDUNG_WEG),
            ),
            74, 75,
        )
        if (faktor >= 1.0) {
            for (l in laeufe) {
                assertTrue(l.stopp == StoppGrund.VERBINDUNG_WEG && l.sim.confirmGetippt == 1) { l.zusammenfassung() }
                val nach = Duration.between(l.sim.confirmZeiten.single(), l.stoppUm).toMillis()
                assertTrue(nach in 180_000..185_000) { "stop $nach ms after Confirm" }
            }
        }
    }

    @Test
    fun `overnight with disturbances - notifications, pop-ups, restarts, lost connection, 8 to 12 hours without a problem`() {
        val nacht = Szenario(
            "overnight disturbances", listOf(b, c), stunden = 8.0, startSg = 100_000, verbindungWegAlleMin = 120..240,
            benachrichtigungAlleMin = 4.0, popupProStunde = 2.0, neustartProStunde = 0.5, haengerProStunde = 2.0,
        )
        val laeufe = pruefe(nacht, 81, 82) + pruefe(nacht.copy(stunden = 10.0), 83) + pruefe(nacht.copy(stunden = 12.0), 84)
        if (faktor >= 1.0) {
            for (l in laeufe) {
                assertTrue(l.stopp == null) { "stopped: ${l.zusammenfassung()}" }
                assertTrue(l.sim.benachrichtigungen > 50 && l.sim.popups > 5 && l.sim.neustarts > 0 && l.sim.trennungen > 0) { l.zusammenfassung() }
            }
        }
    }

    @Test
    fun `overnight with buyout - copies bought outright up to the maximum, budget and goal kept, 8 hours`() {
        val laeufe = pruefe(
            Szenario(
                "overnight buyout", listOf(b.copy(buyoutMax = 70), c.copy(buyoutMax = 30)), stunden = 8.0, startSg = 100_000,
                buyoutAnteil = 0.3, buyoutAn = true, benachrichtigungAlleMin = 6.0, popupProStunde = 1.0, verbindungWegAlleMin = 120..240,
            ),
            85, 86,
        ) + pruefe(
            Szenario(
                "buyout with budget", listOf(b.copy(buyoutMax = 70), c.copy(buyoutMax = 30)), stunden = 8.0, sgBudget = 3000,
                buyoutAnteil = 0.3, buyoutAn = true, erwarteteStopps = setOf(StoppGrund.BUDGET_ERREICHT),
            ),
            87,
        )
        for (l in laeufe) {
            assertTrue(l.sim.sofortGekauft > 0) { l.zusammenfassung() }
            // Counted only when the SG really dropped by the price – never more than really bought.
            assertTrue(l.bot.sofortGekauft <= l.sim.sofortGekauft && l.bot.sofortGekauft >= l.sim.sofortGekauft * 0.8) { l.zusammenfassung() }
        }
    }

    @Test
    fun `restart of the game lands on the main screen - the bot taps nothing and stops`() {
        val l = pruefe(
            Szenario(
                "restart into the main screen", listOf(b, c), stunden = 4.0, neustartProStunde = 1.0, neustartInsUnbekannte = true,
                erwarteteStopps = setOf(StoppGrund.NICHTS_ERKANNT),
            ),
            88,
        ).single()
        if (faktor >= 1.0) assertTrue(l.stopp == StoppGrund.NICHTS_ERKANNT) { l.zusammenfassung() }
    }

    @Test
    fun `speed - full market, bids per minute`() {
        val laeufe = pruefe(
            Szenario(
                "speed (full market)", listOf(c), stunden = 2.0, startSg = 200_000, ankunft = mapOf("C-" to 12.0),
                laufzeitMin = 20..60, wettbewerb = 3.0,
            ),
            9, 10,
        )
        laeufe.forEach { assertTrue(it.geboteProMinute > 1.0) { "Far too slow: ${it.zusammenfassung()}" } }
    }

    // ---------------- Nov 22: the game's rate limit and the refresh rate (review before publishing) ----------------

    @Test
    fun `quiet market - few copies, the bot refreshes far less often and still bids on them`() {
        val laeufe = pruefe(
            Szenario("quiet market", listOf(b, c), stunden = 8.0, startSg = 80_000, ankunft = mapOf("C-" to 0.3, "B-" to 0.15)),
            91, 92,
        )
        if (faktor >= 1.0) for (l in laeufe) {
            assertTrue(l.sim.gebote.isNotEmpty()) { l.zusammenfassung() }
            // Before Nov 22: 518 refreshes per hour (about every 7 s, the "old screenshot" wait included). Now ~240: every
            // 5–15 s while nothing is to be done, fast only while a fitting copy ends within a minute.
            assertTrue(l.aktualisierungenProStunde < 350) { "too many refreshes: ${l.zusammenfassung()}" }
            assertTrue(l.bot.leerlaufGewartet > 0) { l.zusammenfassung() }
        }
    }

    @Test
    fun `rate limit - the game refuses reloads less than 4 s apart and some bids, the bot pauses and keeps bidding, 8 hours`() {
        val laeufe = pruefe(
            Szenario(
                "rate limit", listOf(b, c), stunden = 8.0, startSg = 80_000, zuOftAbstandMs = 4000, zuOftBeimBieten = 0.03,
                benachrichtigungAlleMin = 6.0, popupProStunde = 1.0,
            ),
            93, 94,
        )
        if (faktor >= 1.0) for (l in laeufe) {
            // Never a tap while the message is visible or during the pause (checked at every step: "problems"), no stop,
            // and bidding goes on.
            assertTrue(l.stopp == null) { l.zusammenfassung() }
            assertTrue(l.sim.zuOftMeldungen > 0 && l.bot.zuOftMeldungen > 0) { l.zusammenfassung() }
            assertTrue(l.geboteProMinute > 0.5) { l.zusammenfassung() }
            // Nov 23 (review: "no lasting minimum gap"): at most one reload every 5 s, always – the game never refuses a
            // reload any more. What is left are the refused taps on "Bid" (3 % in this scenario, the bot cannot avoid them).
            assertTrue(l.sim.zuOftBeiAbfrage == 0) { l.zusammenfassung() }
            assertTrue(l.sim.zuOftMeldungen <= l.sim.gebote.size * 0.05) { l.zusammenfassung() }
        }
    }

    @Test
    fun `rate limit at 5 s - the game refuses reloads less than 5 s apart, the bot never gets that message`() {
        // Nov 23 (review): the real reports point to a limit of about 5 s ("Awakened Tier ↑" 2 s after the filter → "retry
        // in 3 second(s)"). With the bot's gap of 5 s, no reload is refused – and bidding goes on as usual.
        val laeufe = pruefe(
            Szenario("rate limit 5 s", listOf(b, c), stunden = 8.0, startSg = 80_000, zuOftAbstandMs = 5000),
            93, 94,
        )
        if (faktor >= 1.0) for (l in laeufe) {
            assertTrue(l.stopp == null && l.sim.zuOftMeldungen == 0 && l.bot.zuOftMeldungen == 0) { l.zusammenfassung() }
            assertTrue(l.geboteProMinute > 5.0) { l.zusammenfassung() }
        }
    }

    @Test
    fun `rate limit for good - every Bid is refused, the bot pauses longer each time and stops the 5th time`() {
        val l = pruefe(
            Szenario(
                "rate limit for good", listOf(b, c), stunden = 2.0, startSg = 80_000, zuOftBeimBieten = 1.0,
                erwarteteStopps = setOf(StoppGrund.ZU_OFT),
            ),
            95,
        ).single()
        if (faktor >= 1.0) {
            assertTrue(l.stopp == StoppGrund.ZU_OFT && l.bot.zuOftMeldungen == ZU_OFT_MAX_IN_FOLGE) { l.zusammenfassung() }
            assertTrue(l.sim.gebote.isEmpty()) { l.zusammenfassung() }
            // Pauses 5 + 10 + 20 + 40 s, the window waits 10 s after each – a few minutes, not seconds.
            assertTrue(l.minuten in 1.5..15.0) { l.zusammenfassung() }
        }
    }

    @Test
    fun `speed with many messages - full market, My Biddings, a message every few seconds, 2 hours`() {
        // Nov 11 (long run): after a long cleanup in My Biddings (with many messages) the bot stopped right away in
        // the list ("message for 3 minutes") – the time in My Biddings counted too.
        pruefe(
            Szenario(
                "speed + messages", listOf(c), stunden = 2.0, startSg = 200_000, ankunft = mapOf("C-" to 12.0),
                laufzeitMin = 20..60, wettbewerb = 3.0, fremdeMeldungAlleS = 4.0,
            ),
            601, 602, 603, 604,
        )
    }

    /**
     * Nov 23 (review round 3: "the bot assumes the bid field holds Current Bid + 1"): in every 10th Bid window the field is
     * 1–3 SG higher ("+" tapped, a quirk of the game) – and "Bid" bids what is in it. The bot reads the field digit by digit
     * (learned: 0, 1, 4, 5, 8, 9; the others show as '?'): it never taps Bid on a field it can see is too high ([DauerSpiel]
     * reports that as a violation) and closes the window. A raise hidden by digits not learned yet is counted – that is the
     * part the bot still cannot see (as before the check).
     */
    @Test
    fun `bid field raised now and then - never Bid on a field the bot can see is too high`() {
        val laeufe = pruefe(Szenario("bid field raised", listOf(b, c), stunden = 8.0, startSg = 80_000, feldHoeher = 0.1), 111, 112)
        if (faktor >= 1.0) for (l in laeufe) {
            assertTrue(l.sim.feldHoeherFenster > 100 && l.bot.bietFeldFalsch > 0 && l.sim.feldHoeherGetippt == 0) { l.zusammenfassung() }
            assertTrue(l.geboteProMinute > 4.0) { l.zusammenfassung() }
        }
        // The same with every digit learned: only a field the bot did not see at all (a band over it, a moving first image)
        // can still slip through.
        val alle = pruefe(
            Szenario("bid field raised, all digits", listOf(b, c), stunden = 8.0, startSg = 80_000, feldHoeher = 0.1, feldZiffern = null),
            111,
        ).single()
        if (faktor >= 1.0) {
            val l = laeufe.first()
            assertTrue(alle.sim.feldHoeherUnerkannt < l.sim.feldHoeherUnerkannt) { alle.zusammenfassung() + "\n" + l.zusammenfassung() }
        }
    }
}
