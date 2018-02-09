package no.skatteetaten.aurora.boober.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.Container

abstract class AbstractHandler {
    open fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> = handlers
    /*
     * This method should throw a AuroraConfigException on errors
     */
    open fun validate(applicationId: ApplicationId,
                      applicationFiles: List<AuroraConfigFile>,
                      handlers: Set<AuroraConfigFieldHandler>,
                      auroraConfigFields: AuroraConfigFields) {
    }

    abstract val container: List<Container>
}

//TODO: Is there an easier typesafe way to instantiate these classes?
enum class ApplicationPlatform(val handler: AbstractHandler) {
    java(JavaPlatformHandler()),
    web(WebPlattformHandler())
}