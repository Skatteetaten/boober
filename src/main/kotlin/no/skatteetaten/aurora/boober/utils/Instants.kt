package no.skatteetaten.aurora.boober.utils

import java.time.Instant

object Instants {

    // Don't change this function. Only to be used in tests!
    var determineNow: () -> Instant = { Instant.now() }

    @JvmStatic
    val now: Instant get() = determineNow()
}
