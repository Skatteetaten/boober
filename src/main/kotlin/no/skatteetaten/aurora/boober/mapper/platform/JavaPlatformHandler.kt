package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.Container
import no.skatteetaten.aurora.boober.utils.addIfNotNull

class JavaPlatformHandler : AbstractPlatformHandler() {
    override val container: List<Container>
        get() = listOf(
                Container("java", mapOf("http" to 8080, "management" to 8081, "jolokia" to 8778))
        )


    override fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> {


        //TODO: Should we send in the baseHandlers here and look for Type.build|development instead? Theen we can remove
        // the handlers from buildMapper

        val buildHandlers = handlers.find { it.name.startsWith("baseImage") }?.let {
            setOf(
                    AuroraConfigFieldHandler("baseImage/name", defaultValue = "flange"),
                    AuroraConfigFieldHandler("baseImage/version", defaultValue = "8")
            )
        }

        return handlers.addIfNotNull(buildHandlers)

    }
}

