package io.github.hustlebot.ihauctionbot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hustlebot.ihauctionbot.kern.Suchregel

/**
 * ONLY in the test build (debug) – controls the bot in the emulator via adb: set rules, start, stop,
 * pocket mode. This does not exist in the real app (release). Only senders with the DUMP permission (adb shell).
 * Review before publishing (Nov 22): TEST_EINSTELLEN also stores the consent (the emulator test cannot tap "Agree").
 *
 * Example (<app> = package of the installed app, e.g. io.github.hustlebot.ihauctionbot):
 *   adb shell am broadcast -n <app>/io.github.hustlebot.ihauctionbot.app.TestSteuerung -a <app>.TEST_EINSTELLEN --es regeln "C-,10,20,5"
 */
class TestSteuerung : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val dienst = BotDienst.instanz
        val antwort = when (i.action?.removePrefix(ctx.packageName)) {
            EINSTELLEN -> {
                val regeln = (i.getStringExtra("regeln") ?: "C-,10,20,5").split(";").mapNotNull { r ->
                    val t = r.split(",").map { it.trim() }
                    if (t.size < 3) null else Suchregel(
                        t[0], t[1].toInt(), t[2].toInt(),
                        maxRestzeitMin = t.getOrNull(3)?.toIntOrNull(),
                        buyoutMax = t.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
                    )
                }
                val alt = EinstellungenSpeicher.laden(ctx)
                EinstellungenSpeicher.speichern(
                    ctx,
                    alt.copy(
                        regeln = regeln, zielKopien = 0, endeUm = null, sgBudget = null,
                        bildschirmAnlassen = true, bildschirmDunkel = false, stromsparen = false, knopfNurImSpiel = false,
                        // --ez buyout true = buyout (beta) on; a 5th value in a rule is its max buyout ("C-,10,20,5,15").
                        buyout = i.getBooleanExtra("buyout", false),
                        // The app's default is now "no shield". The emulator test still checks the shield:
                        // --ez tasche true = pocket mode, otherwise "touch stops"; --es beruehrung NICHTS/STOPPT/TASCHE takes priority.
                        beruehrung = i.getStringExtra("beruehrung")?.let { n -> Beruehrung.entries.firstOrNull { it.name == n.uppercase() } }
                            ?: if (i.getBooleanExtra("tasche", false)) Beruehrung.TASCHE else Beruehrung.STOPPT,
                    ),
                )
                i.getStringExtra("passwort")?.let { Geheim.setzeZweitpasswort(ctx, it) }
                Zustimmung.geben(ctx)
                dienst?.zustimmungGeaendert()
                "set: ${regeln.joinToString("; ")}"
            }
            START -> if (dienst == null) "no service" else {
                dienst.starten()
                "started"
            }
            STOPP -> if (dienst == null) "no service" else {
                dienst.stoppenVonHand()
                "stopped"
            }
            TASCHE -> if (dienst == null) "no service" else {
                dienst.taschenmodusSetzen(true)
                "pocket mode on"
            }
            // Nov 23: the emulator never passes the volume keys to the accessibility service – the same emergency stop
            // as the keys, so that "bot stops, shield gone" can still be checked (the keys themselves need a phone test).
            NOTAUS -> if (dienst == null) "no service" else {
                dienst.notausTest()
                "emergency stop"
            }
            // Nov 23: is the bot still running? (The test notices a stop of the bot by itself.) Not logged.
            STATUS -> {
                val stand = BotZustand.lauf.name + (BotZustand.stoppGrund?.let { " · $it" } ?: "")
                resultData = stand
                return
            }
            else -> "unknown: ${i.action}"
        }
        BotZustand.log("Test control: $antwort")
        resultData = antwort
    }

    companion object {
        // Actions are "<package of the app>" + these endings (see src/debug/AndroidManifest.xml).
        const val EINSTELLEN = ".TEST_EINSTELLEN"
        const val START = ".TEST_START"
        const val STOPP = ".TEST_STOPP"
        const val TASCHE = ".TEST_TASCHE"
        const val NOTAUS = ".TEST_NOTAUS"
        const val STATUS = ".TEST_STATUS"
    }
}
