package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.startsWith

class AuroraRouteMapperV1(val applicationFiles: List<AuroraConfigFile>, val env: AuroraDeployEnvironment, val name: String) {

    //TODO: I do not like all these strings. Can we make it more composable

    val handlers = findRouteHandlers() + listOf(
            AuroraConfigFieldHandler("route", defaultValue = false),
            AuroraConfigFieldHandler("routeDefaults/name", defaultValue = name),
            AuroraConfigFieldHandler("routeDefaults/affiliation", defaultValue = env.affiliation),
            AuroraConfigFieldHandler("routeDefaults/env", defaultValue = env.envName),
            AuroraConfigFieldHandler("routeDefaults/separator", defaultValue = "-")) +
            findRouteAnnotationHandlers("routeDefaults")


    fun route(auroraConfigFields: AuroraConfigFields): AuroraRoute {
        return AuroraRoute(
                route = getRoute(auroraConfigFields)
        )
    }

    fun getRoute(auroraConfigFields: AuroraConfigFields): List<Route> {


        val simplified = auroraConfigFields.isSimplifiedConfig("route")

        if (simplified) {
            if (auroraConfigFields.extract("route")) {
                return listOf(Route(objectName = name,
                        name = auroraConfigFields.extract("routeDefaults/name"),
                        affiliation = auroraConfigFields.extract("routeDefaults/affiliation"),
                        env = auroraConfigFields.extract("routeDefaults/env"),
                        separator = auroraConfigFields.extract("routeDefaults/separator")))
            }
            return listOf()
        }
        val routes = applicationFiles.findSubKeys("route")

        return routes.map {
            Route(it.ensureStartWith(name, "-"),
                    auroraConfigFields.extractIfExistsOrNull("route/$it/name")
                            ?: auroraConfigFields.extract("routeDefaults/name"),
                    auroraConfigFields.extractIfExistsOrNull("route/$it/affiliation")
                            ?: auroraConfigFields.extract("routeDefaults/affiliation"),
                    auroraConfigFields.extractIfExistsOrNull("route/$it/env")
                            ?: auroraConfigFields.extract("routeDefaults/env"),
                    auroraConfigFields.extractIfExistsOrNull("route/$it/separator")
                            ?: auroraConfigFields.extract("routeDefaults/separator"),
                    auroraConfigFields.extractOrNull("route/$it/path"),
                    auroraConfigFields.getRouteAnnotations("route/$it/annotations", handlers))
        }.toList()
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeys("route")

        return routeHandlers.flatMap { routeName ->
            listOf(
                    AuroraConfigFieldHandler("route/$routeName/name"),
                    AuroraConfigFieldHandler("route/$routeName/affiliation"),
                    AuroraConfigFieldHandler("route/$routeName/env"),
                    AuroraConfigFieldHandler("route/$routeName/separator"),
                    AuroraConfigFieldHandler("route/$routeName/path", validator = { it?.startsWith("/", "Path must start with /") })
            ) + findRouteAnnotationHandlers("route/$routeName")
        }
    }

    fun findRouteAnnotationHandlers(prefix: String): List<AuroraConfigFieldHandler> {

        return applicationFiles.flatMap { ac ->
            ac.asJsonNode.at("/$prefix/annotations")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet().map { key ->
            AuroraConfigFieldHandler("$prefix/annotations/$key")
        }
    }


}