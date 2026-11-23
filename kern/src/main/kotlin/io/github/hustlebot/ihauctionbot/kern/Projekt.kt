package io.github.hustlebot.ihauctionbot.kern

/**
 * The project on GitHub. The app has no internet access: its "Check for updates" button only opens [RELEASES_SEITE] in
 * the browser, the app itself downloads nothing.
 */
object Projekt {

    /** The public repo of the app ("owner/name"). */
    const val OEFFENTLICHES_REPO = "Hustle-Bot/idle-heroes-auction-bot"

    /** Releases page of the app (opened in the browser). */
    const val RELEASES_SEITE = "https://github.com/$OEFFENTLICHES_REPO/releases"
}
