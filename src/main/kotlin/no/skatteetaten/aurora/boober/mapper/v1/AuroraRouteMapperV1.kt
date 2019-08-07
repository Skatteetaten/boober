package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.model.SecureRoute
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith
import org.apache.commons.text.StringSubstitutor

class AuroraRouteMapperV1(
    val applicationFiles: List<AuroraConfigFile>,
    val name: String,
    val replacer: StringSubstitutor
) {

    val handlers = findRouteHandlers() + listOf(
        AuroraConfigFieldHandler("route", defaultValue = false, canBeSimplifiedConfig = true),
        AuroraConfigFieldHandler("routeDefaults/host", defaultValue = "@name@-@affiliation@-@env@"),
        AuroraConfigFieldHandler("routeDefaults/tls/enabled", defaultValue = false),
        AuroraConfigFieldHandler(
            "routeDefaults/tls/termination",
            defaultValue = "edge",
            validator = { it.oneOf(listOf("edge", "passthrough")) })
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

                val secure = if (auroraDeploymentSpec["routeDefaults/tls/enabled"]) {
                    SecureRoute(
                        auroraDeploymentSpec["routeDefaults/tls/insecurePolicy"],
                        auroraDeploymentSpec["routeDefaults/tls/termination"]
                    )
                } else null
                return listOf(
                    Route(
                        objectName = name,
                        host = auroraDeploymentSpec["routeDefaults/host"],
                        tls = secure
                    )
                )
            }
            return listOf()
        }
        val routes = applicationFiles.findSubKeys(route)

        return routes.map {

            val secure =
                if (applicationFiles.findSubKeys("$route/$it/tls").isNotEmpty() || auroraDeploymentSpec["routeDefaults/tls/enabled"]) {
                    SecureRoute(
                        auroraDeploymentSpec.getOrDefault(route, it, "tls/insecurePolicy"),
                        auroraDeploymentSpec.getOrDefault(route, it, "tls/termination")
                    )
                } else null

            Route(
                objectName = replacer.replace(it).ensureStartWith(name, "-"),
                host = auroraDeploymentSpec.getOrNull("$route/$it/host")
                    ?: auroraDeploymentSpec["routeDefaults/host"],
                path = auroraDeploymentSpec.getOrNull("$route/$it/path"),
                annotations = auroraDeploymentSpec.getRouteAnnotations("$route/$it/annotations", handlers),
                tls = secure
            )
        }
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        //TODO: switch to findSubKeysExpanded
        val routeHandlers = applicationFiles.findSubKeys("route")

        return routeHandlers.flatMap { routeName ->

            val tlsHandlers = findRouteTlsHandlers("route/$routeName/tls")
            listOf(
                AuroraConfigFieldHandler("route/$routeName/host"),
                AuroraConfigFieldHandler(
                    "route/$routeName/path",
                    validator = { it?.startsWith("/", "Path must start with /") })
            ) + tlsHandlers + findRouteAnnotationHandlers("route/$routeName")
        }
    }

    fun findRouteTlsHandlers(prefix: String): List<AuroraConfigFieldHandler> {
        return applicationFiles.findSubHandlers("$prefix/enabled") +
            applicationFiles.findSubHandlers("$prefix/insecurePolicy", validatorFn = {
                { it.oneOf(listOf("deny", "allow", "redirect")) }
            }) +
            applicationFiles.findSubHandlers("$prefix/termination", validatorFn = {
                { it.oneOf(listOf("edge", "passthrough")) }
            })
    }

    fun findRouteAnnotationHandlers(prefix: String): List<AuroraConfigFieldHandler> {

        return applicationFiles.findSubHandlers("$prefix/annotations", validatorFn = { key ->
            {
                if (key.contains("/")) {
                    IllegalArgumentException("Annotation $key cannot contain '/'. Use '|' instead")
                } else null
            }
        })
    }
}