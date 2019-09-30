package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.InsecurePolicy
import no.skatteetaten.aurora.boober.model.Route
import no.skatteetaten.aurora.boober.model.SecureRoute
import no.skatteetaten.aurora.boober.model.TlsTermination
import no.skatteetaten.aurora.boober.utils.addIfNotNull
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
            defaultValue = TlsTermination.edge,
            validator = { it.oneOf(TlsTermination.values().map { v -> v.name }) })
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

        val defaultAnnotations = auroraDeploymentSpec.getRouteDefaultAnnotations("routeDefaults/annotations", handlers)
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
                        tls = secure,
                        annotations = if (defaultAnnotations.isEmpty()) null else defaultAnnotations
                    )
                )
            }
            return listOf()
        }
        val routes = applicationFiles.findSubKeys(route)

        return routes.mapNotNull {
            if (!auroraDeploymentSpec.get<Boolean>("$route/$it/enabled")) {
                null
            } else {
                val secure =
                    if (applicationFiles.findSubKeys("$route/$it/tls").isNotEmpty() ||
                        auroraDeploymentSpec["routeDefaults/tls/enabled"]
                    ) {
                        SecureRoute(
                            auroraDeploymentSpec.getOrDefault(route, it, "tls/insecurePolicy"),
                            auroraDeploymentSpec.getOrDefault(route, it, "tls/termination")
                        )
                    } else null

                val annotations = defaultAnnotations.addIfNotNull(
                    auroraDeploymentSpec.getRouteAnnotations(
                        "$route/$it/annotations",
                        handlers
                    )
                )
                val r = Route(
                    objectName = replacer.replace(it).ensureStartWith(name, "-"),
                    host = auroraDeploymentSpec.getOrDefault(route, it, "host"),
                    path = auroraDeploymentSpec.getOrNull("$route/$it/path"),
                    annotations = if (annotations.isEmpty()) null else annotations,
                    tls = secure
                )
                r
            }
        }
    }

    fun findRouteHandlers(): List<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeysExpanded("route")

        return routeHandlers.flatMap { key ->

            listOf(
                AuroraConfigFieldHandler("$key/enabled", defaultValue = true),
                AuroraConfigFieldHandler("$key/host"),
                AuroraConfigFieldHandler("$key/path",
                    validator = { it?.startsWith("/", "Path must start with /") }),
                AuroraConfigFieldHandler("$key/tls/enabled"),
                AuroraConfigFieldHandler("$key/tls/insecurePolicy",
                    validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }, required = false) }),
                AuroraConfigFieldHandler("$key/tls/termination",
                    validator = { it.oneOf(TlsTermination.values().map { v -> v.name }, required = false) })

            ) + findRouteAnnotationHandlers(key)
        }
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