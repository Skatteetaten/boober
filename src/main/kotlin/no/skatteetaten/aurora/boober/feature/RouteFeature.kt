package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newRoute
import com.fkorotkov.openshift.port
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.tls
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.openshift.api.model.Route
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.addEnvVarsToMainContainers
import no.skatteetaten.aurora.boober.model.findSubHandlers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.model.openshift.AuroraCname
import no.skatteetaten.aurora.boober.model.openshift.CnameSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.int
import no.skatteetaten.aurora.boober.utils.isValidDns
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith
import no.skatteetaten.aurora.boober.utils.validDnsPreExpansion
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

private const val ROUTE_CONTEXT_KEY = "route"
private const val APPLICATION_DEPLOYMENT_REF_CONTEXT_KEY = "applicationDeploymentRef"

private val FeatureContext.routes: List<no.skatteetaten.aurora.boober.feature.Route>
    get() = this.getContextKey(
        ROUTE_CONTEXT_KEY
    )
private val FeatureContext.applicationDeploymentRef: ApplicationDeploymentRef
    get() = this.getContextKey(
        APPLICATION_DEPLOYMENT_REF_CONTEXT_KEY
    )

const val WEMBLEY_EXTERNAL_HOST_ANNOTATION_NAME = "wembley.sits.no|externalHost"
const val WEBMLEY_API_PATHS_ANNOTATION_NAME = "wembley.sits.no|apiPaths"

@Service
class RouteFeature(@Value("\${boober.route.suffix}") val routeSuffix: String) : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val applicationPlatform: ApplicationPlatform = header.applicationPlatform

        return findRouteHandlers(cmd.applicationFiles) + setOf(
            AuroraConfigFieldHandler(
                "route",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/host",
                defaultValue = "@name@-@affiliation@-@env@",
                validator = { it?.validDnsPreExpansion() }
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/tls/enabled",
                validator = { it.boolean() },
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/cname/enabled",
                validator = { it.boolean() },
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "routeDefaults/cname/ttl",
                validator = { it.int() },
                defaultValue = 300
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

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        return mapOf(
            ROUTE_CONTEXT_KEY to getRoute(spec),
            APPLICATION_DEPLOYMENT_REF_CONTEXT_KEY to cmd.applicationDeploymentRef

        )
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val cnames: MutableSet<AuroraResource> = HashSet()

        return context.routes.map {
            val resource = it.generateOpenShiftRoute(
                routeNamespace = adc.namespace,
                serviceName = adc.name,
                routeSuffix = it.suffix(routeSuffix)
            )

            it.generateAuroraCname(routeNamespace = adc.namespace, routeSuffix = it.suffix(routeSuffix))
                ?.let { cname -> cnames.add(generateResource(cname)) }

            generateResource(resource)
        }.toMutableSet().apply {
            addAll(cnames)
        }
    }

    fun getRoute(
        adc: AuroraDeploymentSpec
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
                val cname = if (adc["routeDefaults/cname/enabled"]) {
                    Cname(
                        ttl = adc["routeDefaults/cname/ttl"]
                    )
                } else null
                return listOf(
                    Route(
                        objectName = adc.name,
                        host = adc["routeDefaults/host"],
                        tls = secure,
                        annotations = defaultAnnotations,
                        cname = cname
                    )
                )
            }
            return listOf()
        }
        val routes = adc.findSubKeysRaw(route)

        return routes.mapNotNull {
            if (!adc.get<Boolean>("$route/$it/enabled")) {
                null
            } else {
                val secure =
                    if (adc.hasSubKeys("$route/$it/tls") || adc["routeDefaults/tls/enabled"]) {
                        SecureRoute(
                            adc.getOrDefault(route, it, "tls/insecurePolicy"),
                            adc.getOrDefault(route, it, "tls/termination")
                        )
                    } else null

                val annotations = adc.getRouteAnnotations("$route/$it/annotations/")
                val allAnnotations = defaultAnnotations.addIfNotNull(annotations)
                val cname =
                    if ((adc.hasSubKeys("$route/$it/cname") && adc.getOrDefault(
                            route,
                            it,
                            "cname/enabled"
                        )) || adc["routeDefaults/cname/enabled"]
                    ) {
                        Cname(
                            ttl = adc.getOrDefault(route, it, "cname/ttl")
                        )
                    } else null

                Route(
                    objectName = adc.replacer.replace(it).ensureStartWith(adc.name, "-"),
                    host = adc.getOrDefault(route, it, "host"),
                    path = adc.getOrNull("$route/$it/path"),
                    annotations = allAnnotations,
                    tls = secure,
                    fullyQualifiedHost = adc.getOrNull("$route/$it/fullyQualifiedHost") ?: false,
                    cname = cname
                )
            }
        }
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {

        context.routes.firstOrNull()?.let {
            val url = if (it.cname == null) {
                it.url(routeSuffix)
            } else {
                it.url("")
            }
            val routeVars = mapOf(
                "ROUTE_NAME" to url,
                "ROUTE_URL" to "${it.protocol}$url"
            ).toEnvVars()
            resources.addEnvVarsToMainContainers(routeVars, this::class.java)
        }
    }

    fun findRouteHandlers(applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeysExpanded("route")

        return routeHandlers.flatMap { key ->

            listOf(
                AuroraConfigFieldHandler(
                    "$key/enabled",
                    validator = { it.boolean() },
                    defaultValue = true
                ),
                AuroraConfigFieldHandler(
                    "$key/cname/enabled",
                    validator = { it.boolean(false) }
                ),
                AuroraConfigFieldHandler(
                    "$key/cname/ttl",
                    validator = { it.int(false) }
                ),
                AuroraConfigFieldHandler(
                    "$key/host",
                    validator = { it?.validDnsPreExpansion() }
                ),
                AuroraConfigFieldHandler(
                    "$key/fullyQualifiedHost",
                    validator = { it.boolean(false) }), // since this is internal I do not want default value on it.
                AuroraConfigFieldHandler("$key/path",
                    validator = { it?.startsWith("/", "Path must start with /") }),
                AuroraConfigFieldHandler("$key/tls/enabled", validator = { it.boolean() }),
                AuroraConfigFieldHandler("$key/tls/insecurePolicy",
                    validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }, required = false) }),
                AuroraConfigFieldHandler("$key/tls/termination",
                    validator = { it.oneOf(TlsTermination.values().map { v -> v.name }, required = false) })

            ) + findRouteAnnotationHandlers(key, applicationFiles)
        }.toSet()
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        val routes = context.routes

        if (routes.isNotEmpty() && adc.isJob) {
            throw AuroraDeploymentSpecValidationException("Routes are not supported for jobs/cronjobs")
        }

        val applicationDeploymentRef = context.applicationDeploymentRef
        val tlsErrors = routes.mapNotNull {
            if (it.tls != null && it.host.contains('.') && !it.fullyQualifiedHost) {
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

        val duplicatedHosts = routes.groupBy { it.host to it.path }.filter { it.value.size > 1 }
        val duplicateHostError = if (duplicatedHosts.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have duplicated host+path configurations",
                errors = duplicatedHosts.map { route ->
                    val path = route.key.second?.let { " host=$it" } ?: ""
                    val routesValues = route.value.joinToString(",") { it.objectName }
                    ConfigFieldErrorDetail.illegal(message = "host=${route.key.first}$path is not unique. Remove the configuration from one of the following routes $routesValues")
                }
            )
        } else null

        val cnameAndFqdnHost = routes.filter { it.fullyQualifiedHost && it.cname != null }
        val cnameAndFqdnHostSimultaneously = if (cnameAndFqdnHost.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} has cname and fullyQualifiedHost configurations simultaneously",
                errors = cnameAndFqdnHost.map { route ->
                    ConfigFieldErrorDetail.illegal(message = "host=${route.objectName} needs to either be used as a bigIp config, or as a cname config. It cannot be both.")
                }
            )
        } else null

        val dnsErrors = routes
            .filter { !it.host.isValidDns() }
            .map {
                AuroraConfigException(
                    "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} has invalid dns name \"${it.host}\"",
                    errors = cnameAndFqdnHost.map { route ->
                        ConfigFieldErrorDetail.illegal(message = "host=${route.host} must be a valid dns entry.")
                    }
                )
            }

        return tlsErrors
            .addIfNotNull(dnsErrors)
            .addIfNotNull(duplicateRouteErrors)
            .addIfNotNull(duplicateHostError)
            .addIfNotNull(cnameAndFqdnHostSimultaneously)
    }

    fun willCreateResource(
        adc: AuroraDeploymentSpec,
        cmd: AuroraContextCommand
    ): Boolean {

        val simplified = adc.isSimplifiedConfig("route")

        if (simplified) {
            return adc["route"]
        }

        return cmd.applicationFiles.findSubKeys("route").any {
            adc.getOrNull<Boolean>("route/$it/enabled") == true
        }
    }

    fun fetchExternalHostsAndPaths(adc: AuroraDeploymentSpec): List<String> {
        val routes = getRoute(adc)

        val annotationeExternalPath = routes.filter {
            it.annotations[WEMBLEY_EXTERNAL_HOST_ANNOTATION_NAME] != null &&
                it.annotations[WEBMLEY_API_PATHS_ANNOTATION_NAME] != null
        }.flatMap {
            val paths = it.annotations[WEBMLEY_API_PATHS_ANNOTATION_NAME]?.split(",") ?: emptyList()
            val name = it.annotations[WEMBLEY_EXTERNAL_HOST_ANNOTATION_NAME]
            paths.map { path ->
                "$name${path.trim()}"
            }
        }

        val fqdnRoute = routes.filter { it.fullyQualifiedHost }.map { it.url(urlSuffix = "") }

        return annotationeExternalPath.addIfNotNull(fqdnRoute)
    }
}

/**
 * @property ttl Time to live on the cname entry: A default value if not overridden
 */
data class Cname(
    val ttl: Int
)

data class Route(
    val objectName: String,
    val host: String,
    val path: String? = null,
    val annotations: Map<String, String> = emptyMap(),
    val labels: Map<String, String>? = null,
    val tls: SecureRoute? = null,
    val fullyQualifiedHost: Boolean = false,
    val cname: Cname? = null
) {
    val target: String
        get(): String = if (path != null) "$host$path" else host

    val protocol: String
        get(): String = if (tls != null) "https://" else "http://"

    fun suffix(urlSuffix: String) = if (fullyQualifiedHost) "" else urlSuffix

    fun url(urlSuffix: String): String {
        return "$host${suffix(urlSuffix)}".let { if (path != null) "$it${path.ensureStartWith("/")}" else it }
    }

    fun generateOpenShiftRoute(
        routeNamespace: String,
        serviceName: String,
        routeSuffix: String
    ): Route {
        val route = this
        return newRoute {
            metadata {
                name = route.objectName
                namespace = routeNamespace
                if (route.annotations.isNotEmpty()) {
                    annotations = route.annotations.mapKeys { kv -> kv.key.replace("|", "/") }
                }
                labels = route.labels
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
                port {
                    targetPort = IntOrString("http")
                }
                host = if (cname != null) {
                    // When having cname the host must be FQDN
                    route.host
                } else {
                    "${route.host}$routeSuffix"
                }
                route.path?.let {
                    path = it
                }
            }
        }
    }

    fun generateAuroraCname(routeNamespace: String, routeSuffix: String): AuroraCname? {
        val route = this

        return if (route.cname != null) {
            AuroraCname(
                _metadata = newObjectMeta {
                    name = route.objectName
                    namespace = routeNamespace
                },
                spec = CnameSpec(
                    cname = route.host,
                    host = withoutInitialPeriod("$routeSuffix"),
                    ttl = route.cname.ttl
                )
            )
        } else null
    }

    private fun withoutInitialPeriod(str: String): String {
        return if (str.startsWith(".")) {
            str.substring(1)
        } else {
            str
        }
    }
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

fun findRouteAnnotationHandlers(
    prefix: String,
    applicationFiles: List<AuroraConfigFile>,
    annotationsKey: String = "annotations"
): Set<AuroraConfigFieldHandler> {

    return applicationFiles.findSubHandlers("$prefix/$annotationsKey", validatorFn = { key ->
        {
            if (key.contains("/")) {
                IllegalArgumentException("Annotation $key cannot contain '/'. Use '|' instead")
            } else null
        }
    }).toSet()
}
