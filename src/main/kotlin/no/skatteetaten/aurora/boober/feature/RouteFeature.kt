package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.openshift.*
import io.fabric8.openshift.api.model.Route
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.v1.findSubHandlers
import no.skatteetaten.aurora.boober.mapper.v1.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith
import org.springframework.stereotype.Service

@Service
class RouteFeature(val routeSuffix: String = ".foo.bar") : Feature {
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {
        return findRouteHandlers(header.applicationFiles) + setOf(
                AuroraConfigFieldHandler("route", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("routeDefaults/host", defaultValue = "@name@-@affiliation@-@env@"),
                AuroraConfigFieldHandler("routeDefaults/tls/enabled", defaultValue = false),
                AuroraConfigFieldHandler(
                        "routeDefaults/tls/termination",
                        defaultValue = TlsTermination.edge,
                        validator = { it.oneOf(TlsTermination.values().map { v -> v.name }) })
        ) +
                findRouteAnnotationHandlers("routeDefaults", header.applicationFiles)

    }

    override fun generate(adc: AuroraDeploymentContext): Set<AuroraResource> {
        val route = "route"

        if (adc.isSimplifiedConfig(route)) {
            if (adc[route]) {
                val secure = if (adc["routeDefaults/tls/enabled"]) {
                    SecureRoute(
                            adc["routeDefaults/tls/insecurePolicy"],
                            adc["routeDefaults/tls/termination"]
                    )
                } else null

                return setOf(AuroraResource("${adc.name}-route", generateRoute(
                        routeName = adc.name,
                        routeNamespace = adc.namespace,
                        routeHost = adc["routeDefaults/host"],
                        routeTls = secure
                )))
            }
            return emptySet()
        }
        val routes = adc.getSubKeyNames("$route/")
        return routes.map {

            // TODO what if route/it/tls/enabled is set to false?
            val secure =
                    if (adc.getSubKeyNames("$route/$it/tls/").isNotEmpty() ||
                            adc["routeDefaults/tls/enabled"]
                    ) {
                        SecureRoute(
                                adc.getOrDefault(route, it, "tls/insecurePolicy"),
                                adc.getOrDefault(route, it, "tls/termination")
                        )
                    } else null


            val routeName = adc.replacer.replace(it).ensureStartWith(adc.name, "-")
            AuroraResource("$routeName-route", generateRoute(
                    routeName = routeName,
                    routeNamespace = adc.namespace,
                    routeHost = adc.getOrDefault(route, it, "host"),
                    serviceName = adc.name,
                    routeTls = secure,
                    routePath = adc.getOrNull("$route/$it/path"),
                    routeAnnotations = adc.getRouteAnnotations("$route/$it/annotations/")
            ))
        }.toSet()
    }

    fun generateRoute(routeName: String,
                      routeNamespace: String,
                      routeHost: String,
                      serviceName: String = routeName,
                      routeTls: SecureRoute? = null,
                      routePath: String? = null,
                      routeAnnotations: Map<String, String> = emptyMap()

    ): Route {
        return newRoute {
            metadata {
                name = routeName
                namespace = routeNamespace
                annotations = routeAnnotations.mapKeys { kv -> kv.key.replace("|", "/") }
            }
            spec {
                to {
                    kind = "Service"
                    name = serviceName
                }
                routeTls?.let {
                    tls {
                        insecureEdgeTerminationPolicy = it.insecurePolicy.name
                        termination = it.termination.name.toLowerCase()
                    }
                }
                host = "${routeHost}$routeSuffix"
                routePath?.let {
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
}