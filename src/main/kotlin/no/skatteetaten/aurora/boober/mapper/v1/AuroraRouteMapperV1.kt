package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.utils.startsWith

class AuroraRouteMapperV1(val applicationFiles: List<AuroraConfigFile>) {


    val handlers = findRouteHandlers()


    fun route(auroraConfigFields: AuroraConfigFields): AuroraRoute {

        val name = auroraConfigFields.extract("name")

        return AuroraRoute(
                route = getRoute(auroraConfigFields, name)
        )
    }

    fun getRoute(auroraConfigFields: AuroraConfigFields, name: String): List<Route> {

        val routes = findSubKeys("route")

        return routes.map {
            val routePath = auroraConfigFields.extractOrNull("route/$it/path")
            val routeHost = auroraConfigFields.extractOrNull("route/$it/host")
            val routeName = if (!it.startsWith(name)) {
                "$name-$it"
            } else {
                it
            }
            Route(routeName, routeHost, routePath, auroraConfigFields.getRouteAnnotations("route/$it/annotations", handlers))
        }.toList()
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = findSubKeys("route")

        return routeHandlers.flatMap { routeName ->
            listOf(
                    AuroraConfigFieldHandler("route/$routeName/host"),
                    AuroraConfigFieldHandler("route/$routeName/path", validator = { it?.startsWith("/", "Path must start with /") })
            ) + findRouteAnnotaionHandlers("route/$routeName")
        }
    }

    fun findRouteAnnotaionHandlers(prefix: String): List<AuroraConfigFieldHandler> {

        return applicationFiles.flatMap { ac ->
            ac.contents.at("/$prefix/annotations")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet().map { key ->
            AuroraConfigFieldHandler("$prefix/annotations/$key")
        }
    }


    private fun findSubKeys(name: String): Set<String> {

        return applicationFiles.flatMap {
            if (it.contents.has(name)) {
                it.contents[name].fieldNames().asSequence().toList()
            } else {
                emptyList()
            }
        }.toSet()
    }


}