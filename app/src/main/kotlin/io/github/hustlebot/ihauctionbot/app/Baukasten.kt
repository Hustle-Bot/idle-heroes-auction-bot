package io.github.hustlebot.ihauctionbot.app

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Building blocks of the settings screen, for the parts EinstellungenZusatz fills in (updates card, reports).
 */
interface Baukasten {
    val act: Activity

    /** The current settings. */
    val einstellungen: Einstellungen

    /** Change the settings and save them right away. */
    fun aendereEinstellungen(aenderung: (Einstellungen) -> Einstellungen)

    fun karte(titel: String, erklaerung: String): LinearLayout

    fun text(t: String): TextView

    fun knopf(t: String, aktion: () -> Unit): Button

    fun abstand(top: Int): LinearLayout.LayoutParams

    fun dp(wert: Int): Int

    /** Show status, banner and log anew. */
    fun zeigeStand()

    /** Reports were switched on or off – grey out the settings that depend on them. */
    fun berichteGeaendert()
}
