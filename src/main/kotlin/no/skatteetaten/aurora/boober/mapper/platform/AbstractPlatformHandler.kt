package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.Container

//TODO: Should these handlers have properties or methods?
abstract class AbstractPlatformHandler {
    open fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> = handlers

    abstract val container: List<Container>
}

//TODO: Is there an easier typesafe way to instantiate these classes?
//TODO: Can the handlers be objects? We do not rely on any state in them
enum class ApplicationPlatform(val handler: AbstractPlatformHandler) {
    java(JavaPlatformHandler()),
    web(WebPlatformHandler())
}