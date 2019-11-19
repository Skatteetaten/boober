package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newRoute
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.tls
import com.fkorotkov.openshift.to
import io.fabric8.openshift.api.model.Route
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.addEnvVar
import no.skatteetaten.aurora.boober.model.findSubHandlers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class RouteFeature(@Value("\${boober.route.suffix}") val routeSuffix: String) : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val applicationPlatform: ApplicationPlatform = header.applicationPlatform

        return findRouteHandlers(cmd.applicationFiles) + setOf(
            AuroraConfigFieldHandler(
                "route",
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/host",
                defaultValue = "@name@-@affiliation@-@env@"
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/tls/enabled",
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/tls/insecurePolicy",
                defaultValue = InsecurePolicy.valueOf(applicationPlatform.insecurePolicy),
                validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }) }),
            AuroraConfigFieldHandler(
                "routeDefaults/tls/termination",
                defaultValue = TlsTermination.edge,
                validator = { it.oneOf(TlsTermination.values().map { v -> v.name }) })
        ) +
            findRouteAnnotationHandlers("routeDefaults", cmd.applicationFiles)
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        return getRoute(adc, cmd).map {
            val resource = generateRoute(
                route = it,
                routeNamespace = adc.namespace,
                serviceName = adc.name,
                routeSuffix = routeSuffix
            )
            generateResource(resource)
        }.toSet()
    }

    fun getRoute(
        adc: AuroraDeploymentSpec,
        cmd: AuroraContextCommand
    ): List<no.skatteetaten.aurora.boober.feature.Route> {

        val route = "route"
        val simplified = adc.isSimplifiedConfig(route)

        val defaultAnnotations = adc.getRouteAnnotations("routeDefaults/annotations/")
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
                        tls = secure,
                        annotations = defaultAnnotations
                    )
                )
            }
            return listOf()
        }
        val routes = cmd.applicationFiles.findSubKeys(route)

        return routes.mapNotNull {

            if (!adc.get<Boolean>("$route/$it/enabled")) {
                null
            } else {
                val secure =
                    if (cmd.applicationFiles.findSubKeys("$route/$it/tls").isNotEmpty() ||
                        adc["routeDefaults/tls/enabled"]
                    ) {
                        SecureRoute(
                            adc.getOrDefault(route, it, "tls/insecurePolicy"),
                            adc.getOrDefault(route, it, "tls/termination")
                        )
                    } else null

                val annotations = adc.getRouteAnnotations("$route/$it/annotations/")
                val allAnnotations = defaultAnnotations.addIfNotNull(annotations)
                Route(
                    objectName = adc.replacer.replace(it).ensureStartWith(adc.name, "-"),
                    host = adc.getOrDefault(route, it, "host"),
                    path = adc.getOrNull("$route/$it/path"),
                    annotations = allAnnotations,
                    tls = secure
                )
            }
        }
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        getRoute(adc, cmd).firstOrNull()?.let {
            val url = it.url(routeSuffix)
            val routeVars = mapOf(
                "ROUTE_NAME" to url,
                "ROUTE_URL" to "${it.protocol}$url"
            ).toEnvVars()
            resources.addEnvVar(routeVars, this::class.java)
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
                if (route.annotations.isNotEmpty()) {
                    annotations = route.annotations.mapKeys { kv -> kv.key.replace("|", "/") }
                }
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
                host = "${route.host}$routeSuffix"
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
                AuroraConfigFieldHandler("$key/enabled", defaultValue = true),
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

    fun findRouteAnnotationHandlers(
        prefix: String,
        applicationFiles: List<AuroraConfigFile>
    ): Set<AuroraConfigFieldHandler> {

        return applicationFiles.findSubHandlers("$prefix/annotations", validatorFn = { key ->
            {
                if (key.contains("/")) {
                    IllegalArgumentException("Annotation $key cannot contain '/'. Use '|' instead")
                } else null
            }
        }).toSet()
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        cmd: AuroraContextCommand
    ): List<Exception> {
        val routes = getRoute(adc, cmd)

        val applicationDeploymentRef = cmd.applicationDeploymentRef
        val tlsErrors = routes.mapNotNull {
            if (it.tls != null && it.host.contains('.')) {
                AuroraConfigException(
                    "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have a tls enabled route with a '.' in the host",
                    errors = listOf(ConfigFieldErrorDetail.illegal(message = "Route name=${it.objectName} with tls uses '.' in host name"))
                )
            } else {
                null
            }
        }

        val routeNames = routes.groupBy { it.objectName }
        val duplicateRoutes = routeNames.filter { it.value.size > 1 }.map { it.key }

        val duplicateRouteErrors = if (duplicateRoutes.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have routes with duplicate names",
                errors = duplicateRoutes.map {
                    ConfigFieldErrorDetail.illegal(message = "Route name=$it is duplicated")
                }
            )
        } else null

        val duplicatedHosts = routes.groupBy { it.target }.filter { it.value.size > 1 }
        val duplicateHostError = if (duplicatedHosts.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have duplicated targets",
                errors = duplicatedHosts.map { route ->
                    val routes = route.value.joinToString(",") { it.objectName }
                    ConfigFieldErrorDetail.illegal(message = "target=${route.key} is duplicated in routes $routes")
                }
            )
        } else null

        return tlsErrors.addIfNotNull(duplicateRouteErrors).addIfNotNull(duplicateHostError)
    }
}

data class Route(
    val objectName: String,
    val host: String,
    val path: String? = null,
    val annotations: Map<String, String>,
    val tls: SecureRoute? = null
) {
    val target: String
        get(): String = if (path != null) "$host$path" else host

    val protocol: String
        get(): String = if (tls != null) "https://" else "http://"

    fun url(urlSuffix: String) = "$host$urlSuffix".let { if (path != null) "$it${path.ensureStartWith("/")}" else it }
}

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
