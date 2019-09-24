package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.openshift.*
import io.fabric8.openshift.api.model.Route
import no.skatteetaten.aurora.boober.mapper.*
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.addEnvVar
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith
import org.springframework.stereotype.Service

@Service
class RouteFeature(val routeSuffix: String = ".foo.bar") : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        val applicationPlatform: ApplicationPlatform = header.applicationPlatform

        return findRouteHandlers(header.applicationFiles) + setOf(
                AuroraConfigFieldHandler("route", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("routeDefaults/host", defaultValue = "@name@-@affiliation@-@env@"),
                AuroraConfigFieldHandler("routeDefaults/tls/enabled", defaultValue = false),
                AuroraConfigFieldHandler(
                        "routeDefaults/tls/insecurePolicy",
                        defaultValue = applicationPlatform.insecurePolicy,
                        validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }) }),
                AuroraConfigFieldHandler(
                        "routeDefaults/tls/termination",
                        defaultValue = TlsTermination.edge,
                        validator = { it.oneOf(TlsTermination.values().map { v -> v.name }) })
        ) +
                findRouteAnnotationHandlers("routeDefaults", header.applicationFiles)

    }


    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {

        return getRoute(adc).map {
            AuroraResource("${it.objectName}-route", generateRoute(
                    route = it,
                    routeNamespace = adc.namespace,
                    serviceName = adc.name,
                    routeSuffix = routeSuffix
            ))
        }.toSet()
    }

    fun getRoute(adc: AuroraDeploymentContext): List<no.skatteetaten.aurora.boober.feature.Route> {

        val route = "route"
        val simplified = adc.isSimplifiedConfig(route)

        if (simplified) {
            if (adc[route]) {

                val secure = if (adc["routeDefaults/tls/enabled"]) {
                    SecureRoute(
                            adc["routeDefaults/tls/insecurePolicy"],
                            adc["routeDefaults/tls/termination"]
                    )
                } else null
                return listOf(
                        Route(
                                objectName = adc.name,
                                host = adc["routeDefaults/host"],
                                tls = secure
                        )
                )
            }
            return listOf()
        }
        val routes = adc.applicationFiles.findSubKeys(route)

        return routes.map {

            val secure =
                    if (adc.applicationFiles.findSubKeys("$route/$it/tls").isNotEmpty() ||
                            adc["routeDefaults/tls/enabled"]
                    ) {
                        SecureRoute(
                                adc.getOrDefault(route, it, "tls/insecurePolicy"),
                                adc.getOrDefault(route, it, "tls/termination")
                        )
                    } else null

            Route(
                    objectName = adc.replacer.replace(it).ensureStartWith(adc.name, "-"),
                    host = adc.getOrDefault(route, it, "host"),
                    path = adc.getOrNull("$route/$it/path"),
                    annotations = adc.getRouteAnnotations("$route/$it/annotations/"),
                    tls = secure
            )
        }
    }

    // TODO: Validation

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        getRoute(adc).firstOrNull()?.let {
            val url = it.url(routeSuffix)
            val routeVars = mapOf(
                    "ROUTE_NAME" to url,
                    "ROUTE_URL" to "${it.protocol}$url"
            ).toEnvVars()
            resources.addEnvVar(routeVars)
        }
    }

    fun generateRoute(
            route: no.skatteetaten.aurora.boober.feature.Route,
            routeNamespace: String,
            serviceName: String,
            routeSuffix: String
    ): Route {
        return newRoute {
            metadata {
                name = route.objectName
                namespace = routeNamespace
                annotations = route.annotations?.mapKeys { kv -> kv.key.replace("|", "/") }
            }
            spec {
                to {
                    kind = "Service"
                    name = serviceName
                }
                route.tls?.let {
                    tls {
                        insecureEdgeTerminationPolicy = it.insecurePolicy.name
                        termination = it.termination.name.toLowerCase()
                    }
                }
                host = "${route.host}${routeSuffix}"
                route.path?.let {
                    path = it
                }
            }
        }
    }

    fun findRouteHandlers(applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeysExpanded("route")

        return routeHandlers.flatMap { key ->

            listOf(
                    AuroraConfigFieldHandler("$key/host"),
                    AuroraConfigFieldHandler("$key/path",
                            validator = { it?.startsWith("/", "Path must start with /") }),
                    AuroraConfigFieldHandler("$key/tls/enabled"),
                    AuroraConfigFieldHandler("$key/tls/insecurePolicy",
                            validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }, required = false) }),
                    AuroraConfigFieldHandler("$key/tls/termination",
                            validator = { it.oneOf(TlsTermination.values().map { v -> v.name }, required = false) })

            ) + findRouteAnnotationHandlers(key, applicationFiles)
        }.toSet()
    }

    fun findRouteAnnotationHandlers(prefix: String, applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {

        return applicationFiles.findSubHandlers("$prefix/annotations", validatorFn = { key ->
            {
                if (key.contains("/")) {
                    IllegalArgumentException("Annotation $key cannot contain '/'. Use '|' instead")
                } else null
            }
        }).toSet()
    }

    // TODO: Incorporate
    fun validateRoutes(
            auroraRoute: AuroraRoute,
            applicationDeploymentRef: ApplicationDeploymentRef
    ) {

        auroraRoute.route.forEach {
            if (it.tls != null && it.host.contains('.')) {
                throw AuroraConfigException(
                        "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have a tls enabled route with a '.' in the host",
                        errors = listOf(ConfigFieldErrorDetail.illegal(message = "Route name=${it.objectName} with tls uses '.' in host name"))
                )
            }
        }

        val routeNames = auroraRoute.route.groupBy { it.objectName }
        val duplicateRoutes = routeNames.filter { it.value.size > 1 }.map { it.key }

        if (duplicateRoutes.isNotEmpty()) {
            throw AuroraConfigException(
                    "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have routes with duplicate names",
                    errors = duplicateRoutes.map {
                        ConfigFieldErrorDetail.illegal(message = "Route name=$it is duplicated")
                    }
            )
        }

        val duplicatedHosts = auroraRoute.route.groupBy { it.target }.filter { it.value.size > 1 }
        if (duplicatedHosts.isNotEmpty()) {
            throw AuroraConfigException(
                    "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have duplicated targets",
                    errors = duplicatedHosts.map { route ->
                        val routes = route.value.joinToString(",") { it.objectName }
                        ConfigFieldErrorDetail.illegal(message = "target=${route.key} is duplicated in routes $routes")
                    }
            )
        }
    }
}

data class Route(
        val objectName: String,
        val host: String,
        val path: String? = null,
        val annotations: Map<String, String>? = null,
        val tls: SecureRoute? = null
) {
    val target: String
        get(): String = if (path != null) "$host$path" else host

    val protocol: String
        get(): String = if (tls != null) "https://" else "http://"

    fun url(urlSuffix: String) = "$host$urlSuffix".let { if (path != null) "$it${path.ensureStartWith("/")}" else it }
}

data class AuroraRoute(
        val route: List<no.skatteetaten.aurora.boober.feature.Route>
)



enum class InsecurePolicy {
    Redirect, None, Allow
}

enum class TlsTermination {
    edge, passthrough
}

data class SecureRoute(
        val insecurePolicy: InsecurePolicy,
        val termination: TlsTermination
)