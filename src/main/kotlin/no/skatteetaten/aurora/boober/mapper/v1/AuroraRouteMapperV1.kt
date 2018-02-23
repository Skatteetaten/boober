package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.utils.startsWith

class AuroraRouteMapperV1(val applicationId: ApplicationId, val applicationFiles: List<AuroraConfigFile>) {

    val handlers = findRouteHandlers() +
        AuroraConfigFieldHandler("route", defaultValue = false)

    fun route(auroraConfigFields: AuroraConfigFields): AuroraRoute {
        return AuroraRoute(
            route = getRoute(auroraConfigFields, auroraConfigFields.extract("name"))
        )
    }

    fun getRoute(auroraConfigFields: AuroraConfigFields, name: String): List<Route> {

        val simplified = auroraConfigFields.isSimplifiedConfig("route")
        if (simplified && auroraConfigFields.extract("route")) {
            return listOf(Route(name = name))
        }
        val routes = applicationFiles.findSubKeys("route")

        return routes.map {
            val routeName = if (!it.startsWith(name)) {
                "$name-$it"
            } else {
                it
            }
            Route(routeName,
                auroraConfigFields.extractOrNull("route/$it/host"),
                auroraConfigFields.extractOrNull("route/$it/path"),
                auroraConfigFields.getRouteAnnotations("route/$it/annotations", handlers))
        }
            .toList()
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeys("route")

        return routeHandlers.flatMap { routeName ->
            listOf(
                AuroraConfigFieldHandler("route/$routeName/host"),
                AuroraConfigFieldHandler("route/$routeName/path", validator = { it?.startsWith("/", "Path must start with /") })
            ) + findRouteAnnotaionHandlers("route/$routeName")
        }
    }

    fun findRouteAnnotaionHandlers(prefix: String): List<AuroraConfigFieldHandler> {

        return applicationFiles.flatMap { ac ->
            ac.asJsonNode.at("/$prefix/annotations")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }
            .toSet()
            .map { key ->
                AuroraConfigFieldHandler("$prefix/annotations/$key")
            }
    }
}