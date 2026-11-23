package io.github.hustlebot.ihauctionbot.kern

import java.time.ZoneId

/**
 * Fixed time zone for the tests (the test pictures and runs were recorded with German local time).
 * The app itself uses the device's time zone (StoppBedingung.zone).
 */
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
