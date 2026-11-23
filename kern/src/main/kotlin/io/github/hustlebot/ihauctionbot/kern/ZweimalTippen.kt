package io.github.hustlebot.ihauctionbot.kern

/**
 * Nov 9: confirm without an extra window – the first tap asks ("Tap again …"), a second tap within
 * [fensterMs] carries it out. After that (or when the time runs out) it starts over.
 * Used for "Turn IH off completely" and "Hide" while the bot is running (the bot is stopped first).
 */
class ZweimalTippen(private val fensterMs: Long = 5_000) {

    private var ersterTippUm: Long? = null

    /** One tap. true = confirmed (second tap in time) → carry it out now. */
    fun tippen(jetztMs: Long): Boolean {
        if (wartet(jetztMs)) {
            ersterTippUm = null
            return true
        }
        ersterTippUm = jetztMs
        return false
    }

    /** Is it waiting for the second tap right now? (Then the button shows "Tap again …".) */
    fun wartet(jetztMs: Long): Boolean {
        val erster = ersterTippUm ?: return false
        return jetztMs - erster in 0..fensterMs
    }

    fun abbrechen() {
        ersterTippUm = null
    }
}
