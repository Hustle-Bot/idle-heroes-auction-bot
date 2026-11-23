package io.github.hustlebot.ihauctionbot.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.hustlebot.ihauctionbot.kern.BuyoutFreigabe
import io.github.hustlebot.ihauctionbot.kern.KOPIE_TYPEN
import io.github.hustlebot.ihauctionbot.kern.Suchregel
import java.time.LocalTime

/**
 * The main screen of the app – simple, everything is saved right away.
 *
 * At the top: status in plain words, big START and STOP. Then the essentials: what to buy, when to stop, what a
 * touch does. Everything else is under "Advanced settings" (collapsed), one short sentence per option.
 * The bot can also be started and stopped with the floating IH button in the game.
 */
class EinstellungenActivity : Activity() {

    private lateinit var einst: Einstellungen

    /** The parts EinstellungenZusatz fills in (updates card, reports). */
    private lateinit var zusatz: EinstellungenZusatz

    private lateinit var standText: TextView
    private lateinit var hinweisBanner: TextView
    private lateinit var dienstText: TextView
    /** Shown at the top only while the accessibility service is off. */
    private lateinit var einrichtungsHinweis: LinearLayout
    /** Review before publishing (Nov 22): shown at the top while the service is on but the player has not agreed yet. */
    private lateinit var zustimmungsHinweis: LinearLayout
    private lateinit var startKnopf: Button
    private lateinit var stoppKnopf: Button
    /** "Advanced settings": collapsed at first. */
    private lateinit var erweitert: LinearLayout
    private lateinit var erweitertKnopf: Button
    private lateinit var stromsparenBox: CheckBox
    /** Buyout (beta) on/off for all rules. */
    private lateinit var buyoutBox: CheckBox
    private lateinit var regelListe: LinearLayout
    private lateinit var zielFeld: EditText
    private lateinit var budgetFeld: EditText
    private lateinit var endeKnopf: Button
    private lateinit var anlassenBox: CheckBox
    private lateinit var dunkelBox: CheckBox
    private lateinit var knopfBox: CheckBox
    /** Flight recorder on/off. */
    private lateinit var flugBox: CheckBox
    /** Review round 2 (Nov 23): Google text recognition (second reader for numbers) on/off. */
    private lateinit var mlKitBox: CheckBox
    /** "When I touch the screen during a run: bot stops / pocket mode". */
    private lateinit var beruehrtTascheKnopf: RadioButton
    private lateinit var beruehrtStoppKnopf: RadioButton
    private lateinit var taschenJetztBox: CheckBox
    private lateinit var spielText: TextView
    private lateinit var vergessenKnopf: Button
    private lateinit var ausschaltenKnopf: Button

    /** "Turn IH off completely" needs a second tap. */
    private val ausschaltenBestaetigung = io.github.hustlebot.ihauctionbot.kern.ZweimalTippen(5_000)
    private lateinit var protokollText: TextView

    private val beobachter: () -> Unit = { runOnUiThread { zeigeStand() } }

    private fun dp(wert: Int): Int = (wert * resources.displayMetrics.density).toInt()

    /** Building blocks for [zusatz]. */
    private val baukasten = object : Baukasten {
        override val act: Activity get() = this@EinstellungenActivity
        override val einstellungen: Einstellungen get() = einst
        override fun aendereEinstellungen(aenderung: (Einstellungen) -> Einstellungen) {
            einst = aenderung(einst)
            speichern()
        }
        override fun karte(titel: String, erklaerung: String): LinearLayout = this@EinstellungenActivity.karte(titel, erklaerung)
        override fun text(t: String): TextView = this@EinstellungenActivity.text(t)
        override fun knopf(t: String, aktion: () -> Unit): Button = grosserKnopf(t, aktion)
        override fun abstand(top: Int): LinearLayout.LayoutParams = this@EinstellungenActivity.abstand(top)
        override fun dp(wert: Int): Int = this@EinstellungenActivity.dp(wert)
        override fun zeigeStand() = this@EinstellungenActivity.zeigeStand()
        override fun berichteGeaendert() = zeigeFlugschreiber()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Review round 2 (Nov 23): moves a consent of an older version once – before anything is logged (see Zustimmung).
        Zustimmung.gegeben(this)
        BotZustand.init(this)
        einst = EinstellungenSpeicher.laden(this)
        zusatz = EinstellungenZusatz(baukasten)
        setContentView(baueOberflaeche())
        zeigeRegeln()
        zeigeEnde()
        // Review before publishing (Nov 22), keeping data short: on a fresh start of the screen (not after rotating)
        // delete reports and saved screenshots older than 14 days, and the report ZIP shared last time (if it is older
        // than 10 minutes – the app it was shared with may still be reading a fresh one).
        if (savedInstanceState == null) BerichtSender.alteLoeschen(this, geteiltAlterMs = 10 * 60_000L)
        // First start: say once what the app does, before anything else (the "Agree" is also asked before the
        // accessibility settings).
        if (savedInstanceState == null && !Zustimmung.gegeben(this) && !Zustimmung.ersterStartGezeigt(this)) {
            Zustimmung.ersterStartMerken(this)
            hinweisDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        // Review round 2 (Nov 23): tells the accessibility service that the app window in front is this app (it does not
        // read any window to find out).
        BotZustand.appBildschirmOffen = true
        // App opened → a hidden IH button is back.
        BotDienst.instanz?.appGeoeffnet()
        BotZustand.beobachte(beobachter)
        zeigeEinrichtung()
        zeigeKnopf()
        zeigeFlugschreiber()
        zusatz.beimOeffnen()
        zeigeStand()
        // Pocket mode switch to the current state (e.g. off after unlocking).
        if (::taschenJetztBox.isInitialized) taschenJetztBox.isChecked = BotZustand.taschenmodusGewuenscht
    }

    override fun onPause() {
        // Nothing gets lost, even when leaving without pressing a button.
        speichern()
        BotZustand.vergiss(beobachter)
        BotZustand.appBildschirmOffen = false
        BotDienst.instanz?.appVerlassen()
        super.onPause()
    }

    override fun onDestroy() {
        zusatz.beimSchliessen()
        super.onDestroy()
    }

    // ---------------- User interface ----------------

    private fun baueOberflaeche(): View {
        val spalte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        spalte.addView(TextView(this).apply {
            text = "IH Auction Bot"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        standText = text("").apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        spalte.addView(standText)
        hinweisBanner = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rund(0xFFEF6C00.toInt())
            visibility = View.GONE
        }
        spalte.addView(hinweisBanner, abstand(top = 8))

        // First use: the accessibility service is still off.
        einrichtungsHinweis = karte("Setup needed", "Turn on the accessibility service \"IH Auction Bot\" once – the bot needs it to see and tap the game.").apply {
            addView(grosserKnopf("Open accessibility settings") { bedienungshilfeOeffnen() })
            visibility = View.GONE
        }
        spalte.addView(einrichtungsHinweis)
        // Review before publishing (Nov 22): the service is on, but the player has not agreed yet – it does nothing.
        zustimmungsHinweis = karte(
            "Your OK is needed",
            "The accessibility service is on, but you have not agreed yet to what it does. Until you do, the bot does " +
                "nothing: no IH button, no screenshots, no taps.",
        ).apply {
            addView(grosserKnopf("Read and agree") { hinweisDialog(null) })
            visibility = View.GONE
        }
        spalte.addView(zustimmungsHinweis)

        // START and STOP
        val knoepfe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startKnopf = farbKnopf("START", 0xFF2E7D32.toInt()) { startGetippt() }
        stoppKnopf = farbKnopf("STOP", 0xFFC62828.toInt()) { stoppGetippt() }
        knoepfe.addView(startKnopf, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(0, 0, dp(6), 0) })
        knoepfe.addView(stoppKnopf, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        spalte.addView(knoepfe, abstand(top = 12))
        spalte.addView(text(
            "START switches to Idle Heroes – open the Bidding Market there. You can also stop with the IH button, " +
                "the power button, or by pressing volume down 3 times quickly.",
        ).apply { alpha = 0.8f })

        // What to buy
        spalte.addView(karte("What to buy", "The bot bids on these ranks up to the price shown; the top rule is searched first.").apply {
            regelListe = LinearLayout(this@EinstellungenActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(regelListe)
            addView(grosserKnopf("+ Add rule") { regelDialog(null) })
            buyoutBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Buyout (beta)"
                textSize = 16f
                isChecked = einst.buyout
                setOnCheckedChangeListener { _, _ ->
                    speichern()
                    zeigeRegeln()
                }
            }
            // Nov 23: only where the build offers buyout (the public app does not – not tested in the game yet). The box
            // still exists hidden, so that saving keeps the saved value as it is.
            val buyoutText = text(
                "Instantly buys a copy when its Buyout Price is at or below the rule's max buyout. Counts toward the SG " +
                    "budget. Off = the bot only bids.",
            ).apply { alpha = 0.8f }
            if (!Variante.BUYOUT_FREIGEGEBEN) {
                buyoutBox.visibility = View.GONE
                buyoutText.visibility = View.GONE
            }
            addView(buyoutBox, abstand(top = 10))
            addView(buyoutText)
        })

        // When to stop
        spalte.addView(karte("When to stop", "The bot stops at the end time or when the SG budget is used up – whichever comes first.").apply {
            addView(text("SG budget (empty = no limit)"))
            budgetFeld = EditText(this@EinstellungenActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 18f
                hint = "empty = no limit"
                setText(einst.sgBudget?.toString() ?: "")
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = speichern()
                })
            }
            addView(budgetFeld)
            endeKnopf = grosserKnopf("") {
                val vorschlag = einst.endeUm ?: LocalTime.of(7, 0)
                TimePickerDialog(this@EinstellungenActivity, { _, h, m ->
                    einst = einst.copy(endeUm = LocalTime.of(h, m))
                    speichern()
                    zeigeEnde()
                }, vorschlag.hour, vorschlag.minute, true).show()
            }
            addView(endeKnopf, abstand(top = 6))
            addView(grosserKnopf("No end time") {
                einst = einst.copy(endeUm = null)
                speichern()
                zeigeEnde()
            })
        })

        // Touching the screen during a run (field test 1.30: the default is "Nothing"; the other two are still beta).
        spalte.addView(karte("Touching the screen", "What happens when you touch the screen while the bot runs.").apply {
            val wahl = RadioGroup(this@EinstellungenActivity)
            val nichtsKnopf = RadioButton(this@EinstellungenActivity).apply {
                id = View.generateViewId()
                text = "Nothing (default)"
                textSize = 16f
            }
            beruehrtStoppKnopf = RadioButton(this@EinstellungenActivity).apply {
                id = View.generateViewId()
                text = "Stop on touch (beta)"
                textSize = 16f
            }
            beruehrtTascheKnopf = RadioButton(this@EinstellungenActivity).apply {
                id = View.generateViewId()
                text = "🎒 Pocket mode (beta)"
                textSize = 16f
            }
            wahl.addView(nichtsKnopf)
            wahl.addView(beruehrtStoppKnopf)
            wahl.addView(beruehrtTascheKnopf)
            wahl.check(
                when (einst.beruehrung) {
                    Beruehrung.NICHTS -> nichtsKnopf.id
                    Beruehrung.STOPPT -> beruehrtStoppKnopf.id
                    Beruehrung.TASCHE -> beruehrtTascheKnopf.id
                },
            )
            wahl.setOnCheckedChangeListener { _, _ -> speichern() }
            addView(wahl)
            addView(text(
                "Stop on touch: the first touch stops the bot. Pocket mode: touches are ignored – hold the IH button for 2 s " +
                    "or press the power button to get out.",
            ).apply { alpha = 0.8f })
        })

        // Advanced settings (collapsed)
        erweitertKnopf = grosserKnopf("") {
            erweitert.visibility = if (erweitert.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            zeigeErweitertKnopf()
        }
        spalte.addView(erweitertKnopf, abstand(top = 16))
        erweitert = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        spalte.addView(erweitert)
        zeigeErweitertKnopf()
        baueErweitert(erweitert)

        return ScrollView(this).apply {
            clipToPadding = false
            addView(spalte)
            // Android 15 draws under the status bar – so add padding.
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left + dp(16), i.top + dp(16), i.right + dp(16), i.bottom + dp(24))
                insets
            }
        }
    }

    /** Everything under "Advanced settings" – one short sentence per option. */
    private fun baueErweitert(spalte: LinearLayout) {
        // How to start
        spalte.addView(karte(
            "How to start",
            "In Idle Heroes open Auction House → Auction Market → Bidding Market and tap START (here or on the round IH " +
                "button); the bot sets the filter and the sorting itself.",
        ))

        // Setup
        spalte.addView(karte("Setup", "Turn these on once; green means all good.").apply {
            dienstText = text("").apply { textSize = 16f }
            addView(dienstText)
            addView(grosserKnopf("Open accessibility settings") { bedienungshilfeOeffnen() })
            // Review round 3 (Nov 23): worded like GUIDE.md, section 1 (Android 13 or newer, not only 15).
            addView(text(
                "Android 13 or newer may say \"Restricted setting\": Android protects this setting because harmful apps " +
                    "have abused it. Only allow it if the checksum and the certificate of the APK you installed matched " +
                    "(see GUIDE.md, \"Install\"). Then open App info → ⋮ → \"Allow restricted settings\" and switch the " +
                    "service on. To undo it: switch the service off and uninstall the app.",
            ).apply { alpha = 0.8f })
            addView(grosserKnopf("Open app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
            zusatz.einrichtung(this)
        })

        // Number of copies
        spalte.addView(karte("Copies to buy", "The bot stops after this many copies (0 = no limit).").apply {
            zielFeld = EditText(this@EinstellungenActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 18f
                setText(einst.zielKopien.toString())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = speichern()
                })
            }
            addView(zielFeld)
        })

        // Secondary password
        spalte.addView(karte(
            "Secondary password",
            "The bot types it when the game asks for it; it is stored encrypted on this phone only and never appears in logs or reports.",
        ).apply {
            // Review round 3 (Nov 23): the saved password is never decrypted for this screen and never put back into the
            // field (not even hidden). Saved: "Saved ✓" with Replace and Delete. "Show" only shows what is being typed
            // right now; the field is saved with "Save" (no longer on every keystroke).
            val ctx = this@EinstellungenActivity
            val gespeichertZeile = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val eingabe = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val pwFeld = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                textSize = 18f
                hint = "Secondary password"
            }
            val zeigenBox = CheckBox(ctx).apply {
                text = "Show what I type"
                textSize = 16f
                setOnCheckedChangeListener { _, an ->
                    // Review before publishing (Nov 22): while the password is readable, no screenshot or screen
                    // recording of this screen (FLAG_SECURE); unticked, screenshots of the settings work again (support).
                    if (an) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    pwFeld.inputType = InputType.TYPE_CLASS_NUMBER or
                        (if (an) InputType.TYPE_NUMBER_VARIATION_NORMAL else InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                    pwFeld.setSelection(pwFeld.text.length)
                }
            }
            val abbrechenKnopf = kleinerKnopf("Cancel") {}
            /** [ersetzen] = the field for a new password is open although one is saved. */
            fun zeigePasswort(ersetzen: Boolean) {
                val da = Geheim.hatZweitpasswort(ctx)
                val feldOffen = !da || ersetzen
                gespeichertZeile.visibility = if (feldOffen) View.GONE else View.VISIBLE
                eingabe.visibility = if (feldOffen) View.VISIBLE else View.GONE
                abbrechenKnopf.visibility = if (da) View.VISIBLE else View.GONE
                if (!ersetzen) {
                    // Nothing typed stays in the field, and the screen may be captured again (FLAG_SECURE off).
                    pwFeld.text.clear()
                    zeigenBox.isChecked = false
                }
            }
            gespeichertZeile.addView(text("Saved ✓").apply { textSize = 18f }, gewicht(1.4f))
            gespeichertZeile.addView(kleinerKnopf("Replace") {
                zeigePasswort(ersetzen = true)
                pwFeld.requestFocus()
            }, gewicht(1f))
            gespeichertZeile.addView(kleinerKnopf("Delete") {
                AlertDialog.Builder(ctx)
                    .setMessage("Delete the saved secondary password? The bot then stops when the game asks for it.")
                    .setPositiveButton("Delete") { _, _ ->
                        Geheim.setzeZweitpasswort(ctx, null)
                        zeigePasswort(ersetzen = false)
                        Toast.makeText(ctx, "Secondary password deleted.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, gewicht(1f))
            eingabe.addView(pwFeld)
            eingabe.addView(zeigenBox)
            val knoepfe = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            knoepfe.addView(kleinerKnopf("Save") {
                val neu = pwFeld.text.toString()
                if (neu.isEmpty()) {
                    Toast.makeText(ctx, "Type the secondary password first.", Toast.LENGTH_SHORT).show()
                    return@kleinerKnopf
                }
                Geheim.setzeZweitpasswort(ctx, neu)
                val ok = Geheim.hatZweitpasswort(ctx)
                zeigePasswort(ersetzen = false)
                Toast.makeText(
                    ctx,
                    if (ok) "Secondary password saved." else "Could not save it – this phone's secure storage did not work.",
                    Toast.LENGTH_LONG,
                ).show()
            }, gewicht(1f))
            abbrechenKnopf.setOnClickListener { zeigePasswort(ersetzen = false) }
            knoepfe.addView(abbrechenKnopf, gewicht(1f))
            eingabe.addView(knoepfe)
            addView(gespeichertZeile)
            addView(eingabe)
            zeigePasswort(ersetzen = false)
        })

        // Screen and battery
        spalte.addView(karte("Screen and battery", "The bot only sees the game while the screen is on – plug in the charger overnight.").apply {
            stromsparenBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Power saving (recommended)"
                textSize = 16f
                isChecked = einst.stromsparen
                setOnCheckedChangeListener { _, _ -> speichern() }
            }
            addView(stromsparenBox)
            addView(text("Dims the screen while the bot runs and checks less often while it only waits.").apply { alpha = 0.8f })
            anlassenBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Keep the screen on while the bot runs (needed)"
                textSize = 16f
                isChecked = einst.bildschirmAnlassen
                setOnCheckedChangeListener { _, _ -> speichern() }
            }
            addView(anlassenBox)
            dunkelBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Very dark screen while the bot runs"
                textSize = 16f
                isChecked = einst.bildschirmDunkel
                setOnCheckedChangeListener { _, _ -> speichern() }
            }
            addView(dunkelBox)
            addView(grosserKnopf("Open battery settings") { Variante.akkuEinstellungen(this@EinstellungenActivity) }, abstand(top = 6))
            addView(text("Let the app run without battery limits, so that Android does not stop it at night.").apply { alpha = 0.8f })
        })

        // Review round 2 (Nov 23): Google's text recognition only if the player wants it.
        spalte.addView(karte("Reading numbers", "The bot reads prices and SG from the screenshots itself.").apply {
            mlKitBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Google text recognition (second reader for numbers)"
                textSize = 16f
                isChecked = einst.mlKit
                setOnCheckedChangeListener { _, _ -> speichern() }
            }
            addView(mlKitBox)
            addView(text(
                "On: numbers the bot cannot read itself are also given to Google's text recognition (ML Kit, part of " +
                    "Google Play services, runs on the phone). Google Play services may send usage statistics about it " +
                    "to Google. Off: nothing of it is used – the bot bids normally. Takes effect at the next START.",
            ).apply { alpha = 0.8f })
        })

        // Pocket mode for this run
        spalte.addView(karte("Pocket mode now", "Turns pocket mode on for this run only, whatever is chosen under \"Touching the screen\".").apply {
            taschenJetztBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Pocket mode on now (only for this run)"
                textSize = 16f
                isChecked = BotZustand.taschenmodusGewuenscht
                setOnCheckedChangeListener { box, an ->
                    // Only the displayed state was updated (onResume) – nothing to do.
                    if (an == BotZustand.taschenmodusGewuenscht) return@setOnCheckedChangeListener
                    val dienst = BotDienst.instanz
                    if (dienst == null) {
                        Toast.makeText(this@EinstellungenActivity, "Turn on the accessibility service first.", Toast.LENGTH_LONG).show()
                        box.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    dienst.taschenmodusSetzen(an)
                    box.isChecked = BotZustand.taschenmodusGewuenscht
                    if (an && laeuftGerade()) {
                        Toast.makeText(
                            this@EinstellungenActivity,
                            "Pocket mode on – everything locked. Way out: hold the IH button for 2 s or press the power button.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            addView(taschenJetztBox)
        })

        // IH button (it used to float over every app)
        spalte.addView(karte("IH button", "The round button that floats over the game; while the bot runs it is always visible.").apply {
            knopfBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Show the IH button only in Idle Heroes"
                textSize = 16f
                isChecked = einst.knopfNurImSpiel
                setOnCheckedChangeListener { _, an ->
                    speichern()
                    BotDienst.instanz?.knopfEinstellungGeaendert(an)
                }
            }
            addView(knopfBox)
            // The button had been pushed off the screen and could not be reached any more.
            addView(grosserKnopf("Bring back the IH button (bottom left)") {
                val dienst = BotDienst.instanz
                if (dienst == null) {
                    Toast.makeText(this@EinstellungenActivity, "Turn on the accessibility service first.", Toast.LENGTH_LONG).show()
                } else {
                    dienst.knopfZurueckholen()
                    Toast.makeText(this@EinstellungenActivity, "The IH button is back at the bottom left.", Toast.LENGTH_SHORT).show()
                }
            })
            spielText = text("")
            addView(spielText, abstand(top = 6))
            vergessenKnopf = grosserKnopf("Forget the learned game") {
                BotDienst.instanz?.spielVergessen() ?: SpielSpeicher.speichern(this@EinstellungenActivity, null)
                zeigeKnopf()
            }
            addView(vergessenKnopf)
            // Completely off (the accessibility service turns itself off).
            ausschaltenKnopf = grosserKnopf("Turn IH off completely") { ausschaltenGetippt() }
            addView(ausschaltenKnopf, abstand(top = 6))
            addView(text("Turns the button and the bot off until you turn the accessibility service on again.").apply { alpha = 0.8f })
        })

        // Updates
        spalte.addView(zusatz.updateKarte())

        // Reports
        spalte.addView(karte("Reports", zusatz.berichtErklaerung).apply {
            zusatz.berichtTeil(this)
            // Flight recorder – so that after a run you can see where the bot tapped and what it saw.
            flugBox = CheckBox(this@EinstellungenActivity).apply {
                text = "Flight recorder"
                textSize = 16f
                isChecked = einst.flugschreiber
                setOnCheckedChangeListener { _, _ -> speichern() }
            }
            addView(flugBox, abstand(top = 12))
            addView(text("Adds the screenshots of the last 3 minutes to each report (up to about 25 MB), never the password window.").apply { alpha = 0.8f })
            // Review before publishing (Nov 22): everything the app saved about runs can be deleted at once.
            addView(grosserKnopf("Delete reports, log and screenshots") { loeschenGetippt() }, abstand(top = 12))
            addView(text(
                "Removes the saved reports, the flight recorder, the log and the saved screenshots from this phone. " +
                    "Reports and screenshots are also deleted by themselves after 14 days.",
            ).apply { alpha = 0.8f })
        })

        // Extra card from EinstellungenZusatz (if any)
        zusatz.zusatzKarte()?.let { spalte.addView(it) }

        // Log
        spalte.addView(karte("Log", "What the bot did last. Screenshots the bot was unsure about stay inside the app and go into the next report.").apply {
            protokollText = TextView(this@EinstellungenActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
            }
            addView(protokollText)
            addView(grosserKnopf("Refresh log") { zeigeStand() })
        })
    }

    private fun zeigeErweitertKnopf() {
        erweitertKnopf.text = if (erweitert.visibility == View.VISIBLE) "▾  Advanced settings" else "▸  Advanced settings"
    }

    // ---------------- START / STOP ----------------

    private fun laeuftGerade(): Boolean = BotZustand.lauf == BotZustand.Lauf.LAEUFT || BotZustand.lauf == BotZustand.Lauf.PAUSE

    /** START: first the overnight checks (only warnings), then start and switch to Idle Heroes. */
    private fun startGetippt() {
        val dienst = BotDienst.instanz
        if (dienst == null) {
            AlertDialog.Builder(this)
                .setMessage("Turn on the accessibility service \"IH Auction Bot\" first – the bot needs it to see and tap the game.")
                .setPositiveButton("Open accessibility settings") { _, _ -> bedienungshilfeOeffnen() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        // Review before publishing (Nov 22): the service does nothing until the player agreed.
        if (!Zustimmung.gegeben(this)) {
            hinweisDialog { startGetippt() }
            return
        }
        if (laeuftGerade()) {
            Toast.makeText(this, "The bot is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        speichern()
        val warnungen = NachtCheck.warnungen(this)
        if (warnungen.isEmpty()) {
            starteJetzt()
            return
        }
        val frage = AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage(warnungen.joinToString("\n\n") { "• $it" })
            .setPositiveButton("Start anyway") { _, _ -> starteJetzt() }
            .setNegativeButton("Cancel", null)
        if (NachtCheck.akkuOptimiert(this)) frage.setNeutralButton("Battery settings") { _, _ -> Variante.akkuEinstellungen(this) }
        frage.show()
    }

    private fun starteJetzt() {
        val dienst = BotDienst.instanz ?: return
        dienst.starten()
        zeigeStand()
        if (BotZustand.lauf != BotZustand.Lauf.LAEUFT) {
            // e.g. no rule turned on
            Toast.makeText(this, BotZustand.letzteMeldung.ifBlank { "The bot did not start." }, Toast.LENGTH_LONG).show()
            return
        }
        // Switch to Idle Heroes (if Android lets us open it). Review before publishing (Nov 22): otherwise this screen
        // stays – the app no longer goes to the back (then another app would be in front of the bot). The bot waits
        // without screenshots until the player opens Idle Heroes.
        val kandidaten = (listOfNotNull(dienst.gelerntesSpiel) + dienst.spielApps).distinct()
        val spiel = kandidaten.firstNotNullOfOrNull { runCatching { packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
        if (spiel != null && runCatching { startActivity(spiel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            Toast.makeText(this, "Bot started – open the Bidding Market in Idle Heroes.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Bot started – now open Idle Heroes yourself and go to the Bidding Market.", Toast.LENGTH_LONG).show()
    }

    /** STOP: the same as Stop on the IH button – stops right away. */
    private fun stoppGetippt() {
        val dienst = BotDienst.instanz
        if (dienst != null && laeuftGerade()) {
            dienst.stoppenVonHand()
            Toast.makeText(this, "Bot stopped.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "The bot is not running.", Toast.LENGTH_SHORT).show()
        }
        zeigeStand()
    }

    // ---------------- Display ----------------

    private fun zeigeStand() {
        standText.text = BotZustand.kurzText()
        val laeuft = laeuftGerade()
        startKnopf.isEnabled = !laeuft
        startKnopf.alpha = if (laeuft) 0.4f else 1f
        val banner = Variante.bannerText()
        hinweisBanner.text = banner ?: ""
        hinweisBanner.visibility = if (banner != null) View.VISIBLE else View.GONE
        protokollText.text = BotZustand.letzteZeilen(60).joinToString("\n").ifEmpty { "(still empty)" }
    }

    private fun zeigeEinrichtung() {
        val an = BotDienst.instanz != null
        val ok = Zustimmung.gegeben(this)
        einrichtungsHinweis.visibility = if (!an) View.VISIBLE else View.GONE
        zustimmungsHinweis.visibility = if (an && !ok) View.VISIBLE else View.GONE
        dienstText.text = when {
            an && ok -> "✅ Accessibility service is ON – the IH button floats over the game."
            an -> "⚠️ Accessibility service is ON, but waiting for your OK – tap \"Read and agree\" at the top."
            else -> "❌ Accessibility service is OFF – please tap \"Open accessibility settings\" → turn on IH Auction Bot."
        }
    }

    // ---------------- Review before publishing (Nov 22): what the app does – and the player's OK ----------------

    /**
     * Before every jump to Android's accessibility settings: say plainly what the service does, what is stored and the
     * risks – "Agree" stores the consent ([Zustimmung]) and only then opens the settings; "Not now" does nothing.
     */
    private fun bedienungshilfeOeffnen() {
        hinweisDialog {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Could not open Android's accessibility settings.", Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * The disclosure ("prominent disclosure"). [danach] runs after "Agree" (null = only store the consent).
     * Agreeing switches the service on right away if it is already turned on in Android.
     */
    private fun hinweisDialog(danach: (() -> Unit)?) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Before you use IH Auction Bot")
            .setMessage(hinweisText())
            .setCancelable(false)
            .setPositiveButton("Agree") { _, _ ->
                Zustimmung.geben(this)
                BotDienst.instanz?.zustimmungGeaendert()
                zeigeEinrichtung()
                zeigeStand()
                danach?.invoke()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun hinweisText(): String {
        // The data note comes from Variante.DATEN_TEXT.
        val daten = Variante.DATEN_TEXT
        // Review round 2 (Nov 23): say whether Google's text recognition is on right now (setting under Advanced settings).
        val mlKit = if (::einst.isInitialized && einst.mlKit) "on" else "off"
        return "This app uses Android's accessibility service to bid in the Idle Heroes auction house for you. " +
            "While it is on, it:\n\n" +
            "• takes screenshots of the whole screen to see the game – it only keeps them while Idle Heroes is in front;\n" +
            "• taps and swipes in the game for you;\n" +
            "• types numbers into the game's price fields and, if you saved one, your auction house secondary password " +
            "(only into Idle Heroes);\n" +
            "• listens to the volume keys while the bot runs (emergency stop) – it never blocks or records any key;\n" +
            "• checks which app is in front (from Android's window list), so that it only works inside Idle Heroes – " +
            "it never reads the content of other apps' windows;\n" +
            // Review round 3 (Nov 23): the two other things the service can do.
            "• can lock the screen at the end of a run (e.g. goal reached, end time, budget spent, or a stop because of a " +
            "problem – like pressing the power button), and once if the app was killed in the middle of a run;\n" +
            "• can put an invisible touch shield over the screen during a run, only if you choose it under \"Touching " +
            "the screen\" (stop on touch or pocket mode – off by default). Pocket mode swallows your touches; way out: " +
            "hold the IH button for 2 s, the power button, or volume down 3 times.\n\n" +
            "Google text recognition (Advanced settings → Reading numbers, now $mlKit): if on, numbers the bot cannot " +
            "read itself are also given to Google's text recognition (ML Kit, part of Google Play services, runs on the " +
            "phone); Google Play services may send usage statistics about it to Google. If off, it is not used at all.\n\n" +
            "What is stored on this phone: your settings, the secondary password (encrypted), a log, reports and some " +
            "screenshots of the game (black outside the game and where another app's window lies over it). Reports, " +
            "screenshots and log lines are deleted after 14 days; you can delete them at any time " +
            "under Advanced settings → Reports. " + daten + "\n\n" +
            "Please note: this app is unofficial and not made by the game's publisher. Using it may break the game's " +
            "Terms of Service, and your account could be banned. The bot spends your Starry Gems – set an SG budget. " +
            "If you are under 18, only use it with a parent's OK."
    }

    /** "Delete reports, log and screenshots" – only while no run is active, after asking. */
    private fun loeschenGetippt() {
        if (laeuftGerade()) {
            Toast.makeText(this, "Stop the bot first.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete reports, log and screenshots?")
            .setMessage(
                "All saved reports, the flight recorder, the log and the screenshots the bot saved are deleted from this " +
                    "phone. Your settings and rules stay. This cannot be undone.",
            )
            .setPositiveButton("Delete") { _, _ ->
                BerichtSender.allesLoeschen(this) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            Toast.makeText(this, "Reports, log and screenshots deleted.", Toast.LENGTH_SHORT).show()
                            zeigeStand()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The flight recorder only works when reports are on ([Variante.berichteAn]). */
    private fun zeigeFlugschreiber() {
        if (!::flugBox.isInitialized) return
        val an = Variante.berichteAn(this)
        flugBox.isEnabled = an
        flugBox.alpha = if (an) 1f else 0.5f
    }

    /** The first tap asks, the second (within 5 s) turns everything off. */
    private fun ausschaltenGetippt() {
        val dienst = BotDienst.instanz
        if (dienst == null) {
            zeigeKnopf()
            return
        }
        if (!ausschaltenBestaetigung.tippen(System.currentTimeMillis())) {
            ausschaltenKnopf.text = if (BotZustand.lauf == BotZustand.Lauf.LAEUFT || BotZustand.lauf == BotZustand.Lauf.PAUSE) {
                "Tap again: stop bot + turn off completely"
            } else {
                "Tap again: really turn off completely"
            }
            ausschaltenKnopf.postDelayed({ zeigeKnopf() }, 5_100)
            return
        }
        dienst.ganzAusschalten()
        // The accessibility service turns off in a moment – then refresh the display.
        ausschaltenKnopf.postDelayed({
            zeigeEinrichtung()
            zeigeKnopf()
        }, 2_000)
    }

    /** Which app has been learned as Idle Heroes? */
    private fun zeigeKnopf() {
        val gelernt = BotDienst.instanz?.gelerntesSpiel ?: SpielSpeicher.laden(this)
        spielText.text = if (gelernt != null) {
            "✅ Idle Heroes recognised (app \"$gelernt\")."
        } else {
            "Idle Heroes not seen yet. The button shows over Idle Heroes from Google Play and over this app."
        }
        vergessenKnopf.visibility = if (gelernt != null) View.VISIBLE else View.GONE
        val an = BotDienst.instanz != null
        if (!ausschaltenBestaetigung.wartet(System.currentTimeMillis())) {
            ausschaltenKnopf.text = if (an) "Turn IH off completely" else "IH is completely off"
        }
        ausschaltenKnopf.isEnabled = an
    }

    private fun zeigeRegeln() {
        regelListe.removeAllViews()
        if (einst.regeln.isEmpty()) regelListe.addView(text("No rule yet – tap \"+ Add rule\" below."))
        einst.regeln.forEachIndexed { i, regel ->
            val karte = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rund(if (regel.aktiv) 0x2242A5F5 else 0x14808080)
                setPadding(dp(12), dp(10), dp(12), dp(8))
            }
            karte.addView(TextView(this).apply {
                text = "${i + 1}.  ${regel.kopie}: up to ${regel.hoechstesGebot} SG" +
                    BuyoutFreigabe.regelText(regel.buyoutMax, Variante.BUYOUT_FREIGEGEBEN)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                alpha = if (regel.aktiv) 1f else 0.5f
            })
            val wann = regel.maxRestzeitMin?.let { "only the last $it min" } ?: "any time left"
            val buyoutAus = if (Variante.BUYOUT_FREIGEGEBEN && regel.buyoutMax != null && !einst.buyout) " · buyout is switched off" else ""
            karte.addView(text(
                if (regel.aktiv) "on · filter ${regel.minPreis}–${regel.maxPreis} · $wann$buyoutAus" else "OFF – not searched for",
            ).apply { alpha = 0.8f })
            val reihe = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            reihe.addView(kleinerKnopf("▲") { verschiebe(i, -1) }.apply { isEnabled = i > 0 }, gewicht(0.7f))
            reihe.addView(kleinerKnopf("▼") { verschiebe(i, 1) }.apply { isEnabled = i < einst.regeln.lastIndex }, gewicht(0.7f))
            reihe.addView(kleinerKnopf(if (regel.aktiv) "Off" else "On") {
                aendereRegeln { liste -> liste.set(i, regel.copy(aktiv = !regel.aktiv)) }
            }, gewicht(1f))
            reihe.addView(kleinerKnopf("Edit") { regelDialog(i) }, gewicht(1.2f))
            reihe.addView(kleinerKnopf("Delete") {
                AlertDialog.Builder(this)
                    .setMessage("Delete rule \"${regelText(regel)}\"?")
                    .setPositiveButton("Delete") { _, _ -> aendereRegeln { it.removeAt(i) } }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, gewicht(1.3f))
            karte.addView(reihe)
            regelListe.addView(karte, abstand(top = 6))
        }
    }

    private fun zeigeEnde() {
        endeKnopf.text = "End time: " + (einst.endeUm?.toString() ?: "none")
    }

    // ---------------- Saving (immediately) ----------------

    private fun speichern() {
        if (!::zielFeld.isInitialized || !::anlassenBox.isInitialized || !::dunkelBox.isInitialized || !::budgetFeld.isInitialized ||
            !::knopfBox.isInitialized || !::flugBox.isInitialized || !::stromsparenBox.isInitialized || !::buyoutBox.isInitialized ||
            !::beruehrtTascheKnopf.isInitialized || !::beruehrtStoppKnopf.isInitialized || !::mlKitBox.isInitialized
        ) return
        // EinstellungenZusatz keeps and applies its own settings.
        einst = einst.copy(
            zielKopien = zielFeld.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            sgBudget = budgetFeld.text.toString().trim().toIntOrNull()?.takeIf { it > 0 },
            bildschirmAnlassen = anlassenBox.isChecked,
            bildschirmDunkel = dunkelBox.isChecked,
            stromsparen = stromsparenBox.isChecked,
            buyout = buyoutBox.isChecked,
            knopfNurImSpiel = knopfBox.isChecked,
            beruehrung = when {
                beruehrtTascheKnopf.isChecked -> Beruehrung.TASCHE
                beruehrtStoppKnopf.isChecked -> Beruehrung.STOPPT
                else -> Beruehrung.NICHTS
            },
            flugschreiber = flugBox.isChecked,
            mlKit = mlKitBox.isChecked,
        )
        EinstellungenSpeicher.speichern(this, einst)
    }

    private fun aendereRegeln(aenderung: (MutableList<Suchregel>) -> Unit) {
        val liste = einst.regeln.toMutableList()
        aenderung(liste)
        einst = einst.copy(regeln = liste)
        speichern()
        zeigeRegeln()
    }

    private fun verschiebe(i: Int, richtung: Int) {
        val j = i + richtung
        if (j !in einst.regeln.indices) return
        aendereRegeln { java.util.Collections.swap(it, i, j) }
    }

    private fun regelDialog(index: Int?) {
        val alt = index?.let { einst.regeln[it] } ?: STANDARD_REGEL
        val auswahl = Spinner(this).apply {
            adapter = ArrayAdapter(this@EinstellungenActivity, android.R.layout.simple_spinner_dropdown_item, KOPIE_TYPEN)
            setSelection(KOPIE_TYPEN.indexOf(alt.kopie).coerceAtLeast(0))
        }
        val minFeld = zahlFeld(alt.minPreis.toString())
        val maxFeld = zahlFeld(alt.maxPreis.toString())
        val zeitFeld = zahlFeld(alt.maxRestzeitMin?.toString() ?: "").apply { hint = "empty = any time" }
        val buyoutFeld = zahlFeld(alt.buyoutMax?.toString() ?: "").apply { hint = "empty = no buyout" }
        val aktivBox = CheckBox(this).apply { text = "Rule turned on"; textSize = 16f; isChecked = alt.aktiv }
        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(text("Which copy? (rank)"))
            addView(auswahl)
            addView(text("Filter left: min price (usually 10)"))
            addView(minFeld)
            addView(text("Filter right: max price – the bot bids on copies up to this, its bid is at most max price + 1"))
            addView(maxFeld)
            addView(text("Only bid if the auction ends within … minutes (empty = any time)"))
            addView(zeitFeld)
            // Nov 23: only where the build offers buyout; otherwise the rule's saved max buyout stays as it is.
            if (Variante.BUYOUT_FREIGEGEBEN) {
                addView(text("Max buyout (SG): buy a copy outright up to this price (empty = no buyout; needs \"Buyout (beta)\")"))
                addView(buyoutFeld)
            }
            addView(aktivBox)
        }
        AlertDialog.Builder(this)
            .setTitle(if (index == null) "New rule" else "Edit rule")
            .setView(ScrollView(this).apply { addView(inhalt) })
            .setPositiveButton("Save") { _, _ ->
                val neu = Suchregel(
                    kopie = auswahl.selectedItem as String,
                    minPreis = minFeld.text.toString().toIntOrNull() ?: 0,
                    maxPreis = maxFeld.text.toString().toIntOrNull() ?: 0,
                    aktiv = aktivBox.isChecked,
                    maxRestzeitMin = zeitFeld.text.toString().trim().takeIf { it.isNotEmpty() }?.toIntOrNull(),
                    buyoutMax = if (Variante.BUYOUT_FREIGEGEBEN) {
                        buyoutFeld.text.toString().trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: 0 }
                    } else {
                        alt.buyoutMax
                    },
                )
                if (!neu.gueltig) {
                    Toast.makeText(
                        this,
                        "Not saved: the min price must not be above the max price, and the minutes and the max buyout must be greater than 0.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    aendereRegeln { liste -> if (index == null) liste.add(neu) else liste.set(index, neu) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- small building blocks ----------------

    private fun karte(titel: String, erklaerung: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rund(0x14808080)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = abstand(top = 14)
        addView(TextView(this@EinstellungenActivity).apply {
            text = titel
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(text(erklaerung).apply { alpha = 0.8f; setPadding(0, dp(2), 0, dp(8)) })
    }

    private fun text(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun grosserKnopf(t: String, aktion: () -> Unit) = Button(this).apply {
        text = t
        textSize = 16f
        isAllCaps = false
        minHeight = dp(52)
        setOnClickListener { aktion() }
    }

    /** Big coloured button (START / STOP). */
    private fun farbKnopf(t: String, farbe: Int, aktion: () -> Unit) = Button(this).apply {
        text = t
        textSize = 22f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        background = rund(farbe)
        setOnClickListener { aktion() }
    }

    private fun kleinerKnopf(t: String, aktion: () -> Unit) = Button(this).apply {
        text = t
        textSize = 14f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(46)
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { aktion() }
    }

    private fun zahlFeld(wert: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 18f
        setText(wert)
    }

    private fun gewicht(g: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, g)

    private fun abstand(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun rund(farbe: Int) = GradientDrawable().apply {
        setColor(farbe)
        cornerRadius = dp(12).toFloat()
    }
}
