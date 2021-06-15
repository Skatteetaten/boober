package no.skatteetaten.aurora.boober.feature

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newRoute
import com.fkorotkov.openshift.port
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.tls
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.EnvVar
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
import no.skatteetaten.aurora.boober.model.openshift.CNameType
import no.skatteetaten.aurora.boober.model.openshift.CnameSpec
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.int
import no.skatteetaten.aurora.boober.utils.isValidDns
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith
import no.skatteetaten.aurora.boober.utils.validDnsPreExpansion

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
const val ROUTE_FEATURE_FIELD = "route"
const val ROUTE_DEFAULTS_FEATURE_FIELD = "routeDefaults"

@Service
class RouteFeature(@Value("\${boober.route.suffix}") val routeSuffix: String) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return findDefaultRouteHandlers(header) +
            findRouteHandlers(cmd.applicationFiles) +
            findRouteAnnotationHandlers(ROUTE_DEFAULTS_FEATURE_FIELD, cmd.applicationFiles)
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        return mapOf(
            ROUTE_CONTEXT_KEY to parseConfiguredRoutes(spec),
            APPLICATION_DEPLOYMENT_REF_CONTEXT_KEY to cmd.applicationDeploymentRef

        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        val routes = context.routes

        val applicationDeploymentRef = context.applicationDeploymentRef
        val tlsErrors = validateTlsForRoutes(routes, applicationDeploymentRef)

        val duplicateRouteErrors = validateUniquenessOfRoutenames(routes, applicationDeploymentRef)

        val duplicateHostError = validateUniqueRouteHost(routes, applicationDeploymentRef)

        val cnameAndFqdnHostSimultaneously = validateSingleFqdnOrCname(routes, applicationDeploymentRef)

        val dnsErrors = validateHostCompliesToDns(routes, applicationDeploymentRef)

        return tlsErrors
            .addIfNotNull(dnsErrors)
            .addIfNotNull(duplicateRouteErrors)
            .addIfNotNull(duplicateHostError)
            .addIfNotNull(cnameAndFqdnHostSimultaneously)
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val routes = context.routes

        val cnames = routes.generateCnames(adc.namespace)

        val openshiftRoutes = routes.generateOpenshiftRoutes(adc.namespace, adc.name)

        return openshiftRoutes + cnames
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        val route = context.routes.firstOrNull() ?: return

        val envVars = route.getEnvVars(routeSuffix)

        resources.addEnvVarsToMainContainers(envVars, this::class.java)
    }

    fun parseConfiguredRoutes(
        adc: AuroraDeploymentSpec
    ): List<no.skatteetaten.aurora.boober.feature.Route> {

        val defaultAnnotations = adc.getRouteAnnotations("$ROUTE_DEFAULTS_FEATURE_FIELD/annotations/")
        val isSimplifiedRoute = adc.isSimplifiedConfig(ROUTE_FEATURE_FIELD)

        if (isSimplifiedRoute) {
            return getRoute(adc, defaultAnnotations)
        }

        val routes = adc.findSubKeysRaw(ROUTE_FEATURE_FIELD)

        return routes.flatMap { routeName ->
            getRoute(adc, defaultAnnotations, routeName)
        }
    }

    private fun getRoute(
        adc: AuroraDeploymentSpec,
        defaultAnnotations: Map<String, String>,
        routeName: String = ""
    ): List<no.skatteetaten.aurora.boober.feature.Route> {
        if (!adc.isRouteEnabled(routeName)) {
            return emptyList()
        }

        val secure = getTlsOrNull(adc, routeName)

        val annotations =
            if (routeName.isNotEmpty()) adc.getRouteAnnotations("$ROUTE_FEATURE_FIELD/$routeName/annotations/")
            else null

        val allAnnotations = defaultAnnotations.addIfNotNull(annotations)

        val cname = getCnameOrNull(adc, routeName)

        val objectname =
            if (routeName.isNotEmpty()) adc.replacer.replace(routeName).ensureStartWith(adc.name, "-") else adc.name

        val route = Route(
            objectName = objectname,
            host = adc.getRouteFieldOrDefault(routeName, "host"),
            path = adc.getOrNull("$ROUTE_FEATURE_FIELD/$routeName/path"),
            annotations = allAnnotations,
            tls = secure,
            fullyQualifiedHost = adc.getOrNull("$ROUTE_FEATURE_FIELD/$routeName/fullyQualifiedHost") ?: false,
            cname = cname
        )

        return listOfNotNull(route, getAzureRouteOrNull(route, adc, routeName))
    }

    private fun getTlsOrNull(adc: AuroraDeploymentSpec, routeName: String): SecureRoute? {
        return if (adc.isTlsEnabled(routeName)) {
            SecureRoute(
                adc.getRouteFieldOrDefault(routeName, "tls/insecurePolicy"),
                adc.getRouteFieldOrDefault(routeName, "tls/termination")
            )
        } else null
    }

    private fun getCnameOrNull(adc: AuroraDeploymentSpec, routeName: String): Cname? {
        return if (adc.isCnameEnabled(routeName)) {
            Cname(
                ttl = adc.getRouteFieldOrDefault(routeName = routeName, suffix = "cname/ttl"),
                type = CNameType.MSDNS
            )
        } else {
            null
        }
    }

    private fun getAzureRouteOrNull(
        route: no.skatteetaten.aurora.boober.feature.Route,
        adc: AuroraDeploymentSpec,
        routeName: String = ""
    ): no.skatteetaten.aurora.boober.feature.Route? {
        return if (adc.isAzureRoute(routeName)) {
            val cname = Cname(
                ttl = adc.getRouteFieldOrDefault(routeName = routeName, suffix = "cname/ttl"),
                type = CNameType.AzureDNS
            )

            val labels = mapOf("type" to "azure")
            route.copy(objectName = route.objectName.ensureEndsWith("-azure"), labels = labels, cname = cname)
        } else {
            null
        }
    }

    private fun findDefaultRouteHandlers(header: AuroraDeploymentSpec): Set<AuroraConfigFieldHandler> {
        val applicationPlatform = header.applicationPlatform
        return setOf(
            AuroraConfigFieldHandler(
                ROUTE_FEATURE_FIELD,
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/host",
                defaultValue = "@name@-@affiliation@-@env@",
                validator = { it?.validDnsPreExpansion() }
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/tls/enabled",
                validator = { it.boolean() },
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/cname/enabled",
                validator = { it.boolean() },
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/azure",
                validator = { it.boolean() },
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/cname/ttl",
                validator = { it.int() },
                defaultValue = 300
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/tls/insecurePolicy",
                defaultValue = InsecurePolicy.valueOf(applicationPlatform.insecurePolicy),
                validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }) }),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/tls/termination",
                defaultValue = TlsTermination.edge,
                validator = { it.oneOf(TlsTermination.values().map { v -> v.name }) })
        )
    }

    fun findRouteHandlers(applicationFiles: List<AuroraConfigFile>): Set<AuroraConfigFieldHandler> {

        val routeHandlers = applicationFiles.findSubKeysExpanded(ROUTE_FEATURE_FIELD)

        return routeHandlers.flatMap { key ->

            listOf(
                AuroraConfigFieldHandler(
                    "$key/enabled",
                    validator = { it.boolean() },
                    defaultValue = true
                ),
                AuroraConfigFieldHandler(
                    "$key/azure",
                    validator = { it.boolean() },
                    defaultValue = false
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

    fun willCreateResource(
        adc: AuroraDeploymentSpec,
        cmd: AuroraContextCommand
    ): Boolean {
        val simplified = adc.isSimplifiedConfig(ROUTE_FEATURE_FIELD)

        if (simplified) {
            return adc[ROUTE_FEATURE_FIELD]
        }

        return cmd.applicationFiles.findSubKeys(ROUTE_FEATURE_FIELD).any {
            adc.isRouteEnabled(it)
        }
    }

    fun fetchExternalHostsAndPaths(adc: AuroraDeploymentSpec): List<String> {
        val routes = parseConfiguredRoutes(adc)

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

        val fqdnRoute = routes.filter { it.fullyQualifiedHost }.map { it.url() }

        return annotationeExternalPath + fqdnRoute
    }

    private fun List<no.skatteetaten.aurora.boober.feature.Route>.generateCnames(namespace: String): Set<AuroraResource> =
        this.mapNotNull { route ->
            route.generateAuroraCname(routeNamespace = namespace, routeSuffix = route.suffix(routeSuffix))
                ?.let { generateResource(it) }
        }.toSet()

    private fun List<no.skatteetaten.aurora.boober.feature.Route>.generateOpenshiftRoutes(
        namespace: String,
        name: String
    ): Set<AuroraResource> =
        this.map {
            val openshiftRoute = it.generateOpenShiftRoute(
                routeNamespace = namespace,
                serviceName = name,
                routeSuffix = it.suffix(routeSuffix)
            )
            generateResource(openshiftRoute)
        }.toSet()

    private fun validateHostCompliesToDns(
        routes: List<no.skatteetaten.aurora.boober.feature.Route>,
        applicationDeploymentRef: ApplicationDeploymentRef
    ): List<AuroraConfigException> {
        return routes
            .filter { !it.host.isValidDns() }
            .map {
                AuroraConfigException(
                    "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} has invalid dns name \"${it.host}\"",
                    errors = listOf(
                        ConfigFieldErrorDetail.illegal(message = "host=${it.host} must be a valid dns entry.")
                    )
                )
            }
    }

    private fun validateSingleFqdnOrCname(
        routes: List<no.skatteetaten.aurora.boober.feature.Route>,
        applicationDeploymentRef: ApplicationDeploymentRef
    ): AuroraConfigException? {
        val cnameAndFqdnHost = routes.filter { it.fullyQualifiedHost && it.cname != null }

        return if (cnameAndFqdnHost.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} has cname and fullyQualifiedHost configurations simultaneously",
                errors = cnameAndFqdnHost.map { route ->
                    ConfigFieldErrorDetail.illegal(message = "host=${route.objectName} needs to either be used as a bigIp config, or as a cname config. It cannot be both.")
                }
            )
        } else null
    }

    private fun validateUniqueRouteHost(
        routes: List<no.skatteetaten.aurora.boober.feature.Route>,
        applicationDeploymentRef: ApplicationDeploymentRef
    ): AuroraConfigException? {
        val duplicatedHosts =
            routes.groupBy { listOf(it.host, it.path, it.labels?.get("type")) }.filter { it.value.size > 1 }

        return if (duplicatedHosts.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have duplicated host+path configurations",
                errors = duplicatedHosts.map { route ->
                    val (host, path, _) = route.key

                    val pathField = path ?: ""
                    val routesValues = route.value.joinToString(",") { it.objectName }
                    ConfigFieldErrorDetail.illegal(message = "host=$host$pathField is not unique. Remove the configuration from one of the following routes $routesValues")
                }
            )
        } else null
    }

    private fun validateUniquenessOfRoutenames(
        routes: List<no.skatteetaten.aurora.boober.feature.Route>,
        applicationDeploymentRef: ApplicationDeploymentRef
    ): AuroraConfigException? {
        val routeNames = routes.groupBy { it.objectName }
        val duplicateRoutes = routeNames.filter { it.value.size > 1 }.map { it.key }

        return if (duplicateRoutes.isNotEmpty()) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have routes with duplicate names",
                errors = duplicateRoutes.map {
                    ConfigFieldErrorDetail.illegal(message = "Route name=$it is duplicated")
                }
            )
        } else null
    }

    private fun validateTlsForRoutes(
        routes: List<no.skatteetaten.aurora.boober.feature.Route>,
        applicationDeploymentRef: ApplicationDeploymentRef
    ) = routes.mapNotNull {
        if (it.tls != null && it.host.contains('.') && !it.fullyQualifiedHost) {
            AuroraConfigException(
                "Application ${applicationDeploymentRef.application} in environment ${applicationDeploymentRef.environment} have a tls enabled route with a '.' in the host",
                errors = listOf(ConfigFieldErrorDetail.illegal(message = "Route name=${it.objectName} with tls uses '.' in host name"))
            )
        } else {
            null
        }
    }
}

/**
 * @property ttl Time to live on the cname entry: A default value if not overridden
 */
data class Cname(
    val ttl: Int,
    val type: CNameType
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

    fun url(urlSuffix: String = ""): String {
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
                host = if (cname != null && cname.type != CNameType.AzureDNS) {
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
                    host = withoutInitialPeriod(routeSuffix),
                    ttl = route.cname.ttl,
                    type = route.cname.type
                )
            )
        } else null
    }

    fun getEnvVars(routeSuffix: String): List<EnvVar> {
        val urlSuffix = if (cname == null) routeSuffix else ""
        val url = url(urlSuffix)

        return mapOf(
            "ROUTE_NAME" to url,
            "ROUTE_URL" to "${this.protocol}$url"
        ).toEnvVars()
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

private fun AuroraDeploymentSpec.isRouteEnabled(routeName: String): Boolean {
    val isSimplified = routeName.isEmpty()
    return if (!isSimplified) {
        this["$ROUTE_FEATURE_FIELD/$routeName/enabled"]
    } else {
        this["$ROUTE_FEATURE_FIELD"]
    }
}

private fun AuroraDeploymentSpec.isAzureRoute(routeName: String = ""): Boolean =
    if (routeName.isEmpty()) this["$ROUTE_DEFAULTS_FEATURE_FIELD/azure"] else this.getRouteFieldOrDefault(
        routeName,
        "azure"
    )

private fun AuroraDeploymentSpec.isTlsEnabled(routeName: String = ""): Boolean =
    this.isFieldEnabled(routeName, "tls")

private fun AuroraDeploymentSpec.isFieldEnabled(routeName: String, field: String): Boolean {
    val isEnabledDefault: Boolean = this["$ROUTE_DEFAULTS_FEATURE_FIELD/$field/enabled"]

    if (routeName.isEmpty()) {
        return isEnabledDefault
    }

    val enabledForRouteName =
        this.hasSubKeys("$ROUTE_FEATURE_FIELD/$routeName/$field") && this.getRouteFieldOrDefault(
            routeName,
            "$field/enabled"
        )
    return enabledForRouteName || isEnabledDefault
}

private fun AuroraDeploymentSpec.isCnameEnabled(routeName: String = "") =
    this.isFieldEnabled(routeName, "cname")

private inline fun <reified T> AuroraDeploymentSpec.getRouteFieldOrDefault(routeName: String, suffix: String): T =
    this.getOrDefault(ROUTE_FEATURE_FIELD, routeName, suffix)
