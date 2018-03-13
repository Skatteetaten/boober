package no.skatteetaten.aurora.boober.service

import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ClockService {

    fun getNow() = Instant.now()
}