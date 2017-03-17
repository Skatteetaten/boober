package no.skatteetaten.aurora.boober

import org.springframework.stereotype.Service

@Service
class OpenShiftConfigService {

    fun applyConfig(config: Config) {

        println(config)
    }
}