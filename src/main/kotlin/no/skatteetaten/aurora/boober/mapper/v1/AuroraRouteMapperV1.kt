package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.startsWith

class AuroraRouteMapperV1(val applicationFiles: List<AuroraConfigFile>, val name: String) {

    val handlers = findRouteHandlers() + listOf(
        AuroraConfigFieldHandler("route", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("routeDefaults/host", defaultValue = "@name@-@affiliation@-@env@")
    ) +
        findRouteAnnotationHandlers("routeDefaults")

    fun route(auroraDeploymentSpec: AuroraDeploymentSpec): AuroraRoute {
        return AuroraRoute(
            route = getRoute(auroraDeploymentSpec)
        )
    }

    fun getRoute(auroraDeploymentSpec: AuroraDeploymentSpec): List<Route> {

        val route = "route"
        val simplified = auroraDeploymentSpec.isSimplifiedConfig(route)

        if (simplified) {
            if (auroraDeploymentSpec[route]) {
                return listOf(Route(objectName = name, host = auroraDeploymentSpec["routeDefaults/host"]))
            }
            return listOf()
        }
        val routes = applicationFiles.findSubKeys(route)

        return routes.map {
            Route(
                it.ensureStartWith(name, "-"),
                auroraDeploymentSpec.getOrNull("$route/$it/host")
                    ?: auroraDeploymentSpec["routeDefaults/host"],
                auroraDeploymentSpec.getOrNull("$route/$it/path"),
                auroraDeploymentSpec.getRouteAnnotations("$route/$it/annotations", handlers)
            )
        }.toList()
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeys("route")

        return routeHandlers.flatMap { routeName ->
            listOf(
                AuroraConfigFieldHandler("route/$routeName/host"),
                AuroraConfigFieldHandler(
                    "route/$routeName/path",
                    validator = { it?.startsWith("/", "Path must start with /") })
            ) + findRouteAnnotationHandlers("route/$routeName")
        }
    }

    fun findRouteAnnotationHandlers(prefix: String): List<AuroraConfigFieldHandler> {

        return applicationFiles.findSubHandlers("$prefix/annotations", validatorFn = { key ->
            {
                // This validator is a bit weird since we check the key and not the value.
                if (key.contains("/")) {
                    IllegalArgumentException("Annotation $key cannot contain '/'. Use '|' instead")
                } else null
            }
        })
    }
}