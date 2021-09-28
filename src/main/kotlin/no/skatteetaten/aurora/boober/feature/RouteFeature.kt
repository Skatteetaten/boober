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
import no.skatteetaten.aurora.boober.model.openshift.CnameDNSResolver
import no.skatteetaten.aurora.boober.model.openshift.CnameSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureEndsWith
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.int
import no.skatteetaten.aurora.boober.utils.isValidDns
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.startsWith

private val logger = KotlinLogging.logger {}

private const val ROUTE_CONTEXT_KEY = "route"
private const val APPLICATION_DEPLOYMENT_REF_CONTEXT_KEY = "applicationDeploymentRef"

private val FeatureContext.routes: List<ConfiguredRoute>
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
        val routes = parseConfiguredRoutes(spec)
        return mapOf(
            ROUTE_CONTEXT_KEY to routes,
            APPLICATION_DEPLOYMENT_REF_CONTEXT_KEY to cmd.applicationDeploymentRef
        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {

        val routes = context.routes

        if (routes.isNotEmpty() && adc.isJob) {
            return listOf(AuroraDeploymentSpecValidationException("Routes are not supported for jobs/cronjobs"))
        }

        val applicationDeploymentRef = context.applicationDeploymentRef
        val tlsErrors = validateTlsForRoutes(routes, applicationDeploymentRef)

        val duplicateRouteErrors = validateUniquenessOfRoutenames(routes, applicationDeploymentRef)

        val duplicateHostError = validateUniqueRouteHost(routes, applicationDeploymentRef)

        val dnsErrors = validateHostCompliesToDns(routes, applicationDeploymentRef)

        return tlsErrors
            .addIfNotNull(dnsErrors)
            .addIfNotNull(duplicateRouteErrors)
            .addIfNotNull(duplicateHostError)
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val routes = context.routes

        // TODO: Do we need AuroraCname for Azure?
        val auroraCnames = routes.filter { !it.shouldGenerateAzureRoute }.generateCnames(adc.namespace)

        val openshiftRoutes = routes.generateOpenshiftRoutes(adc.namespace, adc.name)

        return openshiftRoutes + auroraCnames
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
    ): List<ConfiguredRoute> {
        val isSimplifiedRoute = adc.isSimplifiedConfig(ROUTE_FEATURE_FIELD)
        val defaultRoute = getDefaultRoute(adc)

        if (isSimplifiedRoute) {
            if (!adc.isRouteEnabled()) return emptyList()

            return listOf(defaultRoute)
        }

        val routeNames = adc.findSubKeysRaw(ROUTE_FEATURE_FIELD)

        val configuredRoutes = routeNames.mapNotNull { routeName ->
            getRoute(adc, routeName, defaultRoute)
        }

        return configuredRoutes
    }

    private fun getCname(adc: AuroraDeploymentSpec, host: String, objectName: String, routeName: String = ""): Cname? {
        val azureDnsCname = getAzureDnsCnameOrNull(adc, routeName)
        val msDnsCname = getMsDnsCnameOrNull(adc, routeName)
        if (azureDnsCname == null && msDnsCname == null) return null
        val ttl = msDnsCname?.ttl ?: azureDnsCname?.ttl ?: 300

        return Cname(
            routeHost = host,
            ttl = ttl,
            objectName = objectName,
            msDnsCname = msDnsCname,
            azureDnsCname = azureDnsCname
        )
    }

    private fun getDefaultRoute(
        adc: AuroraDeploymentSpec
    ): ConfiguredRoute {
        val defaultAnnotations = adc.getRouteAnnotations("$ROUTE_DEFAULTS_FEATURE_FIELD/annotations/")

        val shouldGenerateAzureRoute = adc.isAzureConfigured()
        val isFullyQualifiedHost = adc.isMsDnsCnameEnabled()
        val host: String = adc["$ROUTE_DEFAULTS_FEATURE_FIELD/host"]
        val defaultCname = getCname(adc, host, adc.name)
        return ConfiguredRoute(
            host = host,
            objectName = adc.name,
            tls = getTlsOrNull(adc),
            fullyQualifiedHost = isFullyQualifiedHost,
            annotations = defaultAnnotations,
            shouldGenerateAzureRoute = shouldGenerateAzureRoute,
            cname = defaultCname
        )
    }

    private fun getRoute(
        adc: AuroraDeploymentSpec,
        routeName: String,
        defaultRoute: ConfiguredRoute
    ): ConfiguredRoute? {
        if (!adc.isRouteEnabled(routeName)) return null

        val secure = getTlsOrNull(adc, routeName)

        val annotations = adc.getRouteAnnotations("$ROUTE_FEATURE_FIELD/$routeName/annotations/")

        val objectname = adc.replacer.replace(routeName).ensureStartWith(adc.name, "-")

        val isMsDnsCnameConfigured = adc.isMsDnsCnameEnabled(routeName)
        val fullyQualifiedHost: Boolean =
            adc["$ROUTE_FEATURE_FIELD/$routeName/fullyQualifiedHost"] || isMsDnsCnameConfigured
        val host = adc.getOrNull("$ROUTE_FEATURE_FIELD/$routeName/host") ?: defaultRoute.host
        val cnameOrNull = getCname(adc, host, objectname, routeName)

        return ConfiguredRoute(
            objectName = objectname,
            host = host,
            path = adc.getOrNull("$ROUTE_FEATURE_FIELD/$routeName/path"),
            annotations = defaultRoute.annotations + annotations,
            tls = secure,
            fullyQualifiedHost = fullyQualifiedHost,
            shouldGenerateAzureRoute = adc.isAzureConfigured(routeName),
            cname = cnameOrNull
        )
    }

    private fun getTlsOrNull(adc: AuroraDeploymentSpec, routeName: String = ""): SecureRoute? {
        return if (adc.isTlsEnabled(routeName)) {
            SecureRoute(
                adc.getRouteFieldOrDefault(routeName, "tls/insecurePolicy"),
                adc.getRouteFieldOrDefault(routeName, "tls/termination")
            )
        } else null
    }

    private fun getMsDnsCnameOrNull(adc: AuroraDeploymentSpec, routeName: String = ""): CnameDNSResolver? {
        return if (adc.isMsDnsCnameEnabled(routeName)) {
            CnameDNSResolver(
                ttl = adc.getRouteFieldOrDefault(routeName = routeName, suffix = "cname/ttl")
            )
        } else {
            null
        }
    }

    private fun getAzureDnsCnameOrNull(adc: AuroraDeploymentSpec, routeName: String = ""): CnameDNSResolver? {
        return if (adc.isAzureConfigured(routeName)) {
            CnameDNSResolver(
                ttl = adc.getRouteFieldOrDefault(routeName = routeName, suffix = "azure/cnameTtl")
            )
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
                defaultValue = "@name@-@affiliation@-@env@"
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
                "$ROUTE_DEFAULTS_FEATURE_FIELD/azure/enabled",
                validator = { it.boolean() },
                defaultValue = false
            ),
            AuroraConfigFieldHandler(
                "$ROUTE_DEFAULTS_FEATURE_FIELD/azure/cnameTtl",
                validator = { it.int(false) },
                defaultValue = 300
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
                    "$key/azure/enabled",
                    validator = { it.boolean() }
                ),
                AuroraConfigFieldHandler(
                    "$key/azure/cnameTtl",
                    validator = { it.int(false) }
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
                    "$key/host"
                ),
                AuroraConfigFieldHandler(
                    "$key/fullyQualifiedHost",
                    validator = { it.boolean(false) },
                    defaultValue = false
                ), // since this is internal I do not want default value on it.
                AuroraConfigFieldHandler(
                    "$key/path",
                    validator = { it?.startsWith("/", "Path must start with /") }
                ),
                AuroraConfigFieldHandler("$key/tls/enabled", validator = { it.boolean() }),
                AuroraConfigFieldHandler(
                    "$key/tls/insecurePolicy",
                    validator = { it.oneOf(InsecurePolicy.values().map { v -> v.name }, required = false) }
                ),
                AuroraConfigFieldHandler(
                    "$key/tls/termination",
                    validator = { it.oneOf(TlsTermination.values().map { v -> v.name }, required = false) }
                )
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

    private fun List<ConfiguredRoute>.generateCnames(namespace: String) =
        this.filter { it.cname != null }
            .mapNotNull {
                val auroraCname = it.cname?.generateAuroraCname(
                    routeNamespace = namespace,
                    routeSuffix = routeSuffix
                )

                auroraCname?.let {
                    generateResource(auroraCname)
                }
            }.toSet()

    private fun List<ConfiguredRoute>.generateOpenshiftRoutes(
        namespace: String,
        name: String
    ): Set<AuroraResource> =
        this.flatMap { configuredRoute: ConfiguredRoute ->
            val openshiftRoute = configuredRoute.generateOpenShiftRoute(
                routeNamespace = namespace,
                serviceName = name,
                routeSuffix = configuredRoute.suffix(routeSuffix)
            )

            val routeResource = generateResource(openshiftRoute)

            if (configuredRoute.shouldGenerateAzureRoute) {
                val azureRoute = configuredRoute.copy(
                    objectName = configuredRoute.objectName.ensureEndsWith("-azure"),
                    labels = mapOf("type" to "azure"),
                    fullyQualifiedHost = true
                )
                val azureRouteSuffix = configuredRoute.suffix(routeSuffix)
                val azureOpenshiftRoute = azureRoute.generateOpenShiftRoute(
                    routeNamespace = namespace,
                    serviceName = name,
                    routeSuffix = azureRouteSuffix
                )

                val azureRouteResource = generateResource(azureOpenshiftRoute)
                // TODO: Add routeResource
                listOf(azureRouteResource)
            } else {
                listOf(routeResource)
            }
        }.toSet()

    private fun validateHostCompliesToDns(
        routes: List<ConfiguredRoute>,
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

    private fun validateUniqueRouteHost(
        routes: List<ConfiguredRoute>,
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
        routes: List<ConfiguredRoute>,
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
        routes: List<ConfiguredRoute>,
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

data class Cname(
    val routeHost: String,
    val ttl: Int,
    val objectName: String,
    val msDnsCname: CnameDNSResolver?,
    val azureDnsCname: CnameDNSResolver?
) {
    fun generateAuroraCname(routeNamespace: String, routeSuffix: String) =
        AuroraCname(
            _metadata = newObjectMeta {
                name = objectName
                namespace = routeNamespace
            },
            spec = CnameSpec(
                cname = routeHost,
                host = withoutInitialPeriod(routeSuffix),
                ttl = ttl,
                azureDns = azureDnsCname,
                msDns = msDnsCname
            )
        )

    private fun withoutInitialPeriod(str: String): String =
        if (str.startsWith(".")) {
            str.substring(1)
        } else {
            str
        }
}

data class ConfiguredRoute(
    val objectName: String,
    val host: String,
    val path: String? = null,
    val annotations: Map<String, String> = emptyMap(),
    val labels: Map<String, String>? = null,
    val tls: SecureRoute? = null,
    val fullyQualifiedHost: Boolean = false,
    val shouldGenerateAzureRoute: Boolean = false,
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
                host = if (routeSuffix.isEmpty()) {
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

    fun getEnvVars(routeSuffix: String): List<EnvVar> {
        val urlSuffix = if (!fullyQualifiedHost) routeSuffix else ""
        val url = url(urlSuffix)

        return mapOf(
            "ROUTE_NAME" to url,
            "ROUTE_URL" to "${this.protocol}$url"
        ).toEnvVars()
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

private fun AuroraDeploymentSpec.isRouteEnabled(routeName: String = ""): Boolean {
    val isSimplified = routeName.isEmpty()
    return if (!isSimplified) {
        this["$ROUTE_FEATURE_FIELD/$routeName/enabled"]
    } else {
        this[ROUTE_FEATURE_FIELD]
    }
}

private fun AuroraDeploymentSpec.isAzureConfigured(routeName: String = ""): Boolean =
    this.isFieldEnabled(routeName, "azure")

private fun AuroraDeploymentSpec.isTlsEnabled(routeName: String = ""): Boolean =
    this.isFieldEnabled(routeName, "tls")

private fun AuroraDeploymentSpec.isFieldEnabled(routeName: String, field: String): Boolean {
    val isEnabledDefault: Boolean = this["$ROUTE_DEFAULTS_FEATURE_FIELD/$field/enabled"]

    if (routeName.isEmpty()) {
        return isEnabledDefault
    }

    val hasSubKeys = hasSubKeys("$ROUTE_FEATURE_FIELD/$routeName/$field")
    val isEnabled = this.getOrNull<Boolean>("$ROUTE_FEATURE_FIELD/$routeName/$field/enabled")

    if (isEnabled != null) {
        return isEnabled
    }

    return hasSubKeys || isEnabledDefault
}

private fun AuroraDeploymentSpec.isMsDnsCnameEnabled(routeName: String = "") =
    this.isFieldEnabled(routeName, "cname")

private inline fun <reified T> AuroraDeploymentSpec.getRouteFieldOrDefault(routeName: String, suffix: String): T =
    this.getOrDefault(ROUTE_FEATURE_FIELD, routeName, suffix)
