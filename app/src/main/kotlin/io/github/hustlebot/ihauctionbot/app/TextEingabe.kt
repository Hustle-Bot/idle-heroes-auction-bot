package io.github.hustlebot.ihauctionbot.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.graphics.Rect
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.hustlebot.ihauctionbot.kern.Punkt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secret values, ONLY on the phone (own file "geheim", not in the backup, never in the log).
 * Auction house secondary password ("Enter Secondary Password").
 *
 * The password is stored encrypted (AES/GCM) with a key from the Android Keystore that never leaves the phone's
 * secure hardware. A password saved by an older version in plain text is encrypted once and the plain value deleted.
 */
object Geheim {
    private const val DATEI = "geheim" // own file, excluded in res/xml/sicherung*.xml
    private const val ZWEITPASSWORT = "zweitpasswort" // old: plain text (only read once to migrate it)
    private const val ZWEITPASSWORT_VERSCHLUESSELT = "zweitpasswortVerschluesselt"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ih_zweitpasswort"
    private const val VERFAHREN = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val sperre = Any()

    /** Is a secondary password saved? (Without decrypting it – cheap, for every step of the bot.) */
    fun hatZweitpasswort(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        return !p.getString(ZWEITPASSWORT_VERSCHLUESSELT, null).isNullOrEmpty() || !p.getString(ZWEITPASSWORT, null).isNullOrEmpty()
    }

    /** Encrypts a password saved in plain text by an older version (once; called when the service starts). */
    fun migrieren(ctx: Context) = synchronized(sperre) {
        val alt = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString(ZWEITPASSWORT, null)
        if (!alt.isNullOrEmpty()) speichere(ctx, alt, migration = true)
    }

    /** The secondary password, or null (none saved, or it can no longer be decrypted). Never log the result. */
    fun zweitpasswort(ctx: Context): String? = synchronized(sperre) {
        val p = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val alt = p.getString(ZWEITPASSWORT, null)
        if (!alt.isNullOrEmpty()) {
            // Migration: encrypt the plain value once, then delete it.
            speichere(ctx, alt, migration = true)
            return alt
        }
        val gespeichert = p.getString(ZWEITPASSWORT_VERSCHLUESSELT, null)?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { entschluessele(gespeichert) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun setzeZweitpasswort(ctx: Context, wert: String?) = synchronized(sperre) {
        if (wert.isNullOrEmpty()) {
            ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().remove(ZWEITPASSWORT).remove(ZWEITPASSWORT_VERSCHLUESSELT).apply()
        } else {
            speichere(ctx, wert)
        }
    }

    /**
     * Encrypts and saves; an old plain value is removed in the same step. If encrypting fails (no Keystore), a new
     * password is not saved at all, and an old plain one stays until encrypting works (so it is not lost).
     */
    private fun speichere(ctx: Context, wert: String, migration: Boolean = false) {
        val verschluesselt = runCatching { verschluessele(wert) }.getOrNull()
        val e = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
        when {
            verschluesselt != null -> e.putString(ZWEITPASSWORT_VERSCHLUESSELT, verschluesselt).remove(ZWEITPASSWORT)
            migration -> return
            else -> e.remove(ZWEITPASSWORT_VERSCHLUESSELT).remove(ZWEITPASSWORT)
        }
        e.apply()
    }

    /** The AES key in the Android Keystore (created on first use; it cannot be read out). */
    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** IV (12 bytes, chosen by the Keystore) + ciphertext with GCM tag, as Base64. */
    private fun verschluessele(text: String): String {
        val c = Cipher.getInstance(VERFAHREN)
        c.init(Cipher.ENCRYPT_MODE, schluessel())
        val iv = c.iv
        require(iv.size == IV_BYTES)
        return Base64.encodeToString(iv + c.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun entschluessele(gespeichert: String): String {
        val daten = Base64.decode(gespeichert, Base64.NO_WRAP)
        require(daten.size > IV_BYTES)
        val c = Cipher.getInstance(VERFAHREN)
        c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(TAG_BITS, daten, 0, IV_BYTES))
        return String(c.doFinal(daten, IV_BYTES, daten.size - IV_BYTES), Charsets.UTF_8)
    }
}

/**
 * The state of a run that survives a crash/restart of the app (SG budget).
 * A run begins with "Start" and ends with "Stop" or a stop condition. If the app is
 * killed in the middle of a run, the next "Start" continues with the same starting SG.
 */
object LaufSpeicher {
    // Review before publishing (Nov 22): not in the backup (res/xml/sicherung*.xml) – a restored "run active" would lock
    // the screen on another phone.
    private const val DATEI = "lauf"

    fun offenerLaufStartSg(ctx: Context): Int? {
        val p = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        return if (p.getBoolean("aktiv", false)) p.getInt("startSg", -1).takeIf { it >= 0 } else null
    }

    fun begonnen(ctx: Context, startSg: Int?) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
            .putBoolean("aktiv", true)
            .putInt("startSg", startSg ?: -1)
            .putBoolean(NEUSTART_GEMELDET, false)
            .putLong(LETZTER_SCHRITT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Review before publishing (Nov 22): when the bot last made a step (System.currentTimeMillis; the app saves it at
     * most every 20 s). After a crash or an update the screen is only locked if this was less than 2 minutes ago.
     */
    fun schrittMerken(ctx: Context, um: Long) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putLong(LETZTER_SCHRITT, um).apply()
    }

    fun letzterSchritt(ctx: Context): Long = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getLong(LETZTER_SCHRITT, 0L)

    private const val LETZTER_SCHRITT = "letzterSchritt"

    /**
     * Review: the accessibility service reconnects although a run was not ended with Stop – the app crashed in the
     * middle of a run or was killed by Android. true = that is the case now (once per run; after that only again after
     * the next start). The starting SG stays saved (the SG budget keeps counting, as before).
     */
    fun neustartMitOffenemLauf(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (!p.getBoolean("aktiv", false) || p.getBoolean(NEUSTART_GEMELDET, false)) return false
        p.edit().putBoolean(NEUSTART_GEMELDET, true).apply()
        return true
    }

    /**
     * Review: the accessibility service was shut down properly (e.g. turned off in the phone settings) while a run
     * was open – that is not a crash, so do not lock the screen when it reconnects.
     */
    fun neustartNichtMelden(ctx: Context) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putBoolean(NEUSTART_GEMELDET, true).apply()
    }

    private const val NEUSTART_GEMELDET = "neustartGemeldet"

    fun startSgMerken(ctx: Context, startSg: Int) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().putInt("startSg", startSg).apply()
    }

    fun beendet(ctx: Context) {
        ctx.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * Writes text into an input field of the game (price fields in the filter, secondary password).
 *
 * How: the field has already been tapped (the keyboard opens). Then look for the focused, editable field
 * (also in other windows, e.g. the input field above the keyboard), set the text,
 * send "Done" and close the keyboard. The result (worked yes/no) goes to [fertig].
 * The text itself is never written to the log.
 *
 * Review ("the secondary password must never end up in another app"): ONLY fields of Idle Heroes count
 * ([spielPakete]). For the secondary password ([schreibe] with passwort = true) only a focused field or a password field.
 * If there is none, the input fails (the bot then reports "Secondary password could not be entered").
 * Review before publishing (Nov 22): only the allowed game apps (allow-list of the accessibility config) – never "the
 * app that is in front".
 * Privacy review, round 2 (Nov 23): only windows known to be the game's ([istSpielFenster], learned from the game's own
 * events) are read at all – no other app's window is ever asked for its content, not even for its app name.
 */
class TextEingabe(
    private val dienst: AccessibilityService,
    private val haupt: Handler,
    /** Package names of Idle Heroes (allow-list); empty → no input. */
    private val spielPakete: () -> Set<String>,
    /** Is the window with this id one of the game's? Only those windows are read. */
    private val istSpielFenster: (Int) -> Boolean,
) {

    /**
     * [nochErlaubt]: asked right before writing; false (bot stopped or paused) =
     * write nothing, confirm nothing.
     */
    fun schreibe(
        text: String,
        pruefen: Boolean,
        passwort: Boolean = false,
        nochErlaubt: () -> Boolean = { true },
        getippt: Punkt? = null,
        fertig: (Boolean) -> Unit,
    ) {
        // Wait briefly until the field has the focus after the tap.
        // Speed: shorter than before (700 ms) – if the field comes later, it is searched again.
        // Review: the bot only calls this once the tap on the field is really finished.
        haupt.postDelayed({ versuche(text, pruefen, passwort, nochErlaubt, getippt, 0, fertig) }, 450)
    }

    private fun versuche(
        text: String,
        pruefen: Boolean,
        passwort: Boolean,
        nochErlaubt: () -> Boolean,
        getippt: Punkt?,
        runde: Int,
        fertig: (Boolean) -> Unit,
    ) {
        if (!nochErlaubt()) {
            tastaturZu { fertig(false) }
            return
        }
        val feld = suche(passwort, getippt)
        if (feld == null) {
            if (runde < 3) {
                haupt.postDelayed({ versuche(text, pruefen, passwort, nochErlaubt, getippt, runde + 1, fertig) }, 300)
            } else {
                BotZustand.log(warumKeins ?: "No input field of Idle Heroes found – text input does not work here")
                tastaturZu { fertig(false) }
            }
            return
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val gesetzt = runCatching { feld.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        val gelesen = runCatching { feld.refresh(); feld.text?.toString() }.getOrNull()
        // For passwords the field often shows dots – then only trust "set".
        val ok = gesetzt && (!pruefen || gelesen == null || gelesen == text)
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { feld.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
        }
        haupt.postDelayed({ tastaturZu { fertig(ok) } }, 200)
    }

    /** Why [suche] returned no field last time (for the log), null = none found. */
    private var warumKeins: String? = null

    /**
     * The focused, editable field of Idle Heroes – first via the focus, otherwise search the game's windows (only windows
     * known to be the game's, see [istSpielFenster]).
     * [passwort]: only a focused field or a password field (never "any" field).
     *
     * Field test 1.30 (the tap on the max price field did not arrive, the max price ended up in the still focused
     * min price field): [getippt] = where the bot just tapped (screen pixels). If the game's fields have a real size,
     * only the field closest to the tap is written to (at most [NAH_PX] away) – and only if no OTHER field has the
     * focus right now. Otherwise: no field (the input fails, the core then checks by itself). Without real sizes
     * (invisible input field) the focused field, as before.
     */
    private fun suche(passwort: Boolean, getippt: Punkt?): AccessibilityNodeInfo? {
        warumKeins = null
        val spiele = runCatching { spielPakete() }.getOrNull().orEmpty().filter { it.isNotBlank() }.toSet()
        if (spiele.isEmpty()) return null
        fun vomSpiel(k: AccessibilityNodeInfo): Boolean = k.packageName?.toString() in spiele
        fun erlaubt(k: AccessibilityNodeInfo): Boolean = k.isEditable && vomSpiel(k) && (!passwort || k.isFocused || k.isPassword)
        // Review round 2 (Nov 23): only the game's windows (known from its own events) – their root is asked, no other
        // window's (before: every window's root and the active window, whatever app it was). The focused one first.
        val spielFenster = runCatching { dienst.windows }.getOrNull().orEmpty()
            .filter { istSpielFenster(it.id) }
            .sortedByDescending { it.isFocused }
        val wurzeln = spielFenster.mapNotNull { w -> runCatching { w.root }.getOrNull() }.filter { vomSpiel(it) }
        val fokus = wurzeln.firstNotNullOfOrNull { w ->
            runCatching { w.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable && vomSpiel(it) }
        }
        val felder = ArrayList<AccessibilityNodeInfo>()
        fokus?.let { felder += it }
        for (w in wurzeln) {
            val warteschlange = ArrayDeque<AccessibilityNodeInfo>().apply { add(w) }
            var n = 0
            while (warteschlange.isNotEmpty() && n < 500) {
                val k = warteschlange.removeFirst()
                n++
                if (erlaubt(k) && felder.none { it == k }) felder += k
                for (i in 0 until k.childCount) k.getChild(i)?.let { warteschlange.add(it) }
            }
        }
        val passende = felder.filter { erlaubt(it) }
        if (passende.isEmpty()) return null
        // Check before 1.31: in the secondary password window there is exactly ONE field – then it is the right one, even
        // if the game reports it somewhere other than the tap (e.g. because the view slides up for the keyboard). Otherwise
        // the bot would stop at every password window. (Only fields of the game, focused or password fields – see above.)
        if (passwort && passende.size == 1) return passende[0]
        if (getippt != null) {
            val mitGroesse = passende.mapNotNull { k ->
                val r = Rect()
                runCatching { k.getBoundsInScreen(r) }
                if (r.width() >= MIN_BREITE_PX && r.height() >= MIN_HOEHE_PX) k to r else null
            }
            if (mitGroesse.isNotEmpty()) {
                val (ziel, rahmen) = mitGroesse.minBy { (_, r) -> abstand(r, getippt) }
                if (abstand(rahmen, getippt) > NAH_PX) {
                    warumKeins = "No input field under the tap (${getippt.x}/${getippt.y}; nearest field at " +
                        "${rahmen.left}/${rahmen.top}–${rahmen.right}/${rahmen.bottom}) – nothing written"
                    return null
                }
                val anderesFokussiert = mitGroesse.any { (k, _) -> k.isFocused && k != ziel }
                if (anderesFokussiert) {
                    warumKeins = "Another field still has the focus (the tap probably did not arrive) – nothing written"
                    return null
                }
                return ziel
            }
        }
        // Without a tap position or without real field sizes: as before – the focused field, otherwise the first match.
        return fokus?.takeIf { erlaubt(it) } ?: passende.firstOrNull { it.isFocused }
            ?: passende.firstOrNull { !passwort || it.isPassword }
    }

    /** Distance (pixels) from [p] to the rectangle [r] – 0 if [p] is inside. */
    private fun abstand(r: Rect, p: Punkt): Int {
        val dx = maxOf(r.left - p.x, 0, p.x - r.right)
        val dy = maxOf(r.top - p.y, 0, p.y - r.bottom)
        return maxOf(dx, dy)
    }

    private companion object {
        /** The tap may be this far (pixels) from the field. Min and max price are ~400 px apart. */
        const val NAH_PX = 120

        /** Smaller than this, a field has no "real" size (some games use an invisible input field). */
        const val MIN_BREITE_PX = 20
        const val MIN_HOEHE_PX = 10
    }

    /** Close the keyboard (if open), then continue. */
    private fun tastaturZu(weiter: () -> Unit) {
        val offen = runCatching { dienst.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        if (!offen) {
            weiter()
            return
        }
        val steuerung = dienst.softKeyboardController
        steuerung.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
        haupt.postDelayed({
            steuerung.setShowMode(AccessibilityService.SHOW_MODE_AUTO)
            weiter()
        }, 350)
    }
}
