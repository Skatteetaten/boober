package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.openshift.BigIp
import no.skatteetaten.aurora.boober.model.openshift.BigIpKonfigurasjonstjenesten
import no.skatteetaten.aurora.boober.model.openshift.BigIpSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class BigIpFeature(
    @Value("\${boober.route.suffix}") val routeSuffix: String
) : Feature {

    enum class Errors(val message: String) {
        MissingLegacyService("bigip/service is required if other bigip flags are set. bigip/service is deprecated and you should move that configuration to bigip/<host>/service."),
        MissingMultipleService("bigip/<host>/service is required if any other bigip flags are set"),
        BothLegacyAndMultipleConfigIsSet("specifying both bigip/service and bigip/<host>/service is not allowed. bigip/service is deprecated and you should move that configuration to bigip/<host>/service.")
    }

    val bigIpLegacyConfigKeys: List<String> = listOf(
        "enabled",
        "service",
        "asmPolicy",
        "externalHost",
        "oauthScopes",
        "apiPaths",
        "routeAnnotations"
    )

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val multipleConfigs = cmd.applicationFiles.getBigIPHosts()
            .flatMap { host ->
                setOf(
                    // host == host in OpenShift Route resource
                    AuroraConfigFieldHandler("bigip/$host"),
                    AuroraConfigFieldHandler("bigip/$host/enabled", { it.boolean() }),
                    AuroraConfigFieldHandler("bigip/$host/service"),
                    AuroraConfigFieldHandler("bigip/$host/asmPolicy"),
                    AuroraConfigFieldHandler("bigip/$host/externalHost"),
                    AuroraConfigFieldHandler("bigip/$host/oauthScopes"),
                    AuroraConfigFieldHandler("bigip/$host/apiPaths")
                ) + findRouteAnnotationHandlers("bigip/$host", cmd.applicationFiles, "routeAnnotations")
            }.toSet()

        val legacyConfig = setOf(
            AuroraConfigFieldHandler("bigip/service"),
            AuroraConfigFieldHandler("bigip/asmPolicy"),
            AuroraConfigFieldHandler("bigip/externalHost"),
            AuroraConfigFieldHandler("bigip/oauthScopes"),
            AuroraConfigFieldHandler("bigip/apiPaths"),
            AuroraConfigFieldHandler("bigip/enabled", { it.boolean() })
        ) + findRouteAnnotationHandlers("bigip", cmd.applicationFiles, "routeAnnotations")

        return multipleConfigs + legacyConfig
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val hosts = adc.getBigIPHosts()
        val isMultipleConfig = hosts.isNotEmpty()

        val isMissingLegacyServiceConfig =
            adc.hasSubKeys("bigip") && adc.getOrNull<String>("bigip/service").isNullOrEmpty()

        val isMissingMultipleServiceConfig = hosts.any {
            adc.getOrNull<String>("bigip/$it/service").isNullOrEmpty()
        }

        val hasConfiguredBothLegacyAndMultipleConfig = !isMissingLegacyServiceConfig && isMultipleConfig
        if (hasConfiguredBothLegacyAndMultipleConfig) {
            throw AuroraDeploymentSpecValidationException(Errors.BothLegacyAndMultipleConfigIsSet.message)
        }

        if (!isMultipleConfig && isMissingLegacyServiceConfig) {
            throw AuroraDeploymentSpecValidationException(Errors.MissingLegacyService.message)
        }

        if (isMissingMultipleServiceConfig) {
            throw AuroraDeploymentSpecValidationException(Errors.MissingMultipleService.message)
        }

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return adc.getBigIPHosts()
            .flatMap { generateBigIPResources(it, adc) }
            .toSet()
    }

    fun generateBigIPResources(host: String, adc: AuroraDeploymentSpec): Set<AuroraResource> {
        if (!adc.isBigIPHostEnabled(host)) {
            return emptySet()
        }

        // This is used to preserve legacy configuration
        val isApplicationHost = host == adc.name

        val routeName = if (isApplicationHost) "${adc.name}-bigip" else "$host-bigip"

        val routeHost = if (isApplicationHost) {
            DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")
        } else {
            "$host-${adc.namespace}"
        }

        val route = Route(
            objectName = routeName,
            host = routeHost,
            annotations = adc.getRouteAnnotations("bigip/$host/routeAnnotations/").addIfNotNull("bigipRoute" to "true")
        )

        val bigIp = BigIp(
            _metadata = newObjectMeta {
                name = adc.name
                namespace = adc.namespace
            },
            spec = BigIpSpec(
                routeName, BigIpKonfigurasjonstjenesten(
                    service = adc["bigip/$host/service"],
                    asmPolicy = adc.getOrNull("bigip/$host/asmPolicy"),
                    oauthScopes = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/$host/oauthScopes"),
                    apiPaths = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/$host/apiPaths"),
                    externalHost = adc.getOrNull("bigip/$host/externalHost")
                )
            )
        )

        return setOf(
            route.generateOpenShiftRoute(adc.namespace, adc.name, routeSuffix).generateAuroraResource(),
            bigIp.generateAuroraResource()
        )
    }

    fun fetchExternalHostsAndPaths(adc: AuroraDeploymentSpec): List<String> {
        return adc.getBigIPHosts()
            .filter { adc.isBigIPHostEnabled(it) }
            .mapNotNull { host -> adc.getOrNull<String>("bigip/$host/externalHost") }
            .flatMap { externalHost ->
                adc.getDelimitedStringOrArrayAsSet("bigip/$externalHost/apiPaths").map {
                    "$externalHost$it"
                }
            }
    }

    private fun List<AuroraConfigFile>.getBigIPHosts(): Set<String> {
        return this.findSubKeys("bigip").filter { !bigIpLegacyConfigKeys.contains(it) }.toSet()
    }

    private fun AuroraDeploymentSpec.getBigIPHosts(): Set<String> {
        return this.getSubKeys("bigip")
            .filter {
                val isSubKeyToHost = it.key.split("/").size > 2
                when {
                    isSubKeyToHost -> false
                    else -> !bigIpLegacyConfigKeys.map { "bigip/$it" }.contains(it.key)
                }
            }
            .map { it.key.split("/").last() }
            .toSet()
    }

    private fun AuroraDeploymentSpec.isBigIPHostEnabled(host: String): Boolean {
        val hasSubKeys = this.hasSubKeys("bigip/$host")
        return getOrNull<Boolean>("bigip/$host/enabled") ?: hasSubKeys
    }
}
