package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.Config
import org.springframework.stereotype.Service

@Service
class OpenShiftConfigService {

    fun applyConfig(config: Config) {

        println(config)
    }
}