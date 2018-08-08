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

    val handlers = findRouteHandlers() + listOf(
        AuroraConfigFieldHandler("route", defaultValue = false, subKeyFlag = true),
        AuroraConfigFieldHandler("routeDefaults/host", defaultValue = "@name@-@affiliation@-@env@")) +
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
                return listOf(Route(objectName = name, host = auroraConfigFields.extract("routeDefaults/host")))
            }
            return listOf()
        }
        val routes = applicationFiles.findSubKeys("route")

        return routes.map {
            Route(it.ensureStartWith(name, "-"),
                auroraConfigFields.extractIfExistsOrNull("route/$it/host")
                    ?: auroraConfigFields.extract("routeDefaults/host"),
                auroraConfigFields.extractOrNull("route/$it/path"),
                auroraConfigFields.getRouteAnnotations("route/$it/annotations", handlers))
        }.toList()
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeys("route")

        return routeHandlers.flatMap { routeName ->
            listOf(
                AuroraConfigFieldHandler("route/$routeName/host"),
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