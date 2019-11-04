package no.skatteetaten.aurora.boober.utils

import java.util.UUID

object UUIDGenerator {

    // Don't change this function. Only to be used in tests!
    var generateId: () -> String = { UUID.randomUUID().toString() }

    val deployId: String
        get() = generateId().substring(0, 7)
}


