package io.github.hustlebot.ihauctionbot.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.hustlebot.ihauctionbot.kern.Projekt

/**
 * Parts of the settings screen ("oss"): the app has no internet access and does not update itself. "Check for updates"
 * only opens the releases page in the browser; reports stay on the phone and can be shared by the player himself.
 */
class EinstellungenZusatz(private val b: Baukasten) {

    private val act = b.act
    private lateinit var versionText: TextView

    /** No extra rows in the "Setup" card (the app installs nothing). */
    fun einrichtung(karte: LinearLayout) = Unit

    fun updateKarte(): View = b.karte(
        "Updates",
        "This app does not update itself and has no internet access. New versions are published on GitHub: download " +
            "the new APK there and install it over this app – your settings are kept.",
    ).apply {
        versionText = b.text("").apply { textSize = 16f }
        addView(versionText)
        addView(b.knopf("Check for updates (opens GitHub)") {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Projekt.RELEASES_SEITE)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(act, "No browser found. The address is ${Projekt.RELEASES_SEITE}", Toast.LENGTH_LONG).show()
            }
        })
    }

    /** Explanation of the "Reports" card. */
    val berichtErklaerung: String =
        "A report contains the log, the latest screenshots and the settings – never your secondary password, never " +
            "screenshots of the password window. Reports stay inside this app on your phone (the last 5, at most 14 days); nothing is " +
            "sent anywhere. One is saved at the end of every run, when the bot is stuck and when you tap \"Save report\". " +
            "To get help, share the latest report yourself, e.g. in a GitHub issue. The screenshots show your game " +
            "account – only share it with people you trust."

    /** "Save report" and "Share latest report" (at the top of the "Reports" card). */
    fun berichtTeil(karte: LinearLayout) {
        karte.addView(b.knopf("Save report") {
            BerichtSender.sende(act, "by hand (settings)", automatisch = false)
            Toast.makeText(act, "The report is being saved …", Toast.LENGTH_SHORT).show()
        })
        karte.addView(b.knopf("Share latest report") { BerichtTeilen.teilen(act) })
    }

    /** No extra card. */
    fun zusatzKarte(): View? = null

    /** Settings opened (again). */
    fun beimOeffnen() {
        versionText.text = "Installed: version ${AppInfo.version(act)}"
    }

    /** Settings closed for good. */
    fun beimSchliessen() = Unit
}
