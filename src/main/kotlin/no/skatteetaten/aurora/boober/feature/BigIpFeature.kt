package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
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

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    fun isBigIPMultipleConfigKey(key: String, withBigIPPrefix: Boolean): Boolean {
        val bigIpSubKeys: List<String> = listOf(
            "enabled",
            "service",
            "asmPolicy",
            "externalHost",
            "oauthScopes",
            "apiPaths",
            "routeAnnotations"
        )

        if (withBigIPPrefix && key.split("/").size > 2) {
            return false
        }

        val subkeys = if (withBigIPPrefix) {
            bigIpSubKeys.map { "bigip/$it" }
        } else bigIpSubKeys

        return !subkeys.contains(key)
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        // Enables or disables all bigIp routes.
        // TODO: Should this be added for each route?
        val bigipEnabledHandler = AuroraConfigFieldHandler("bigip/enabled", { it.boolean() })

        val multipleBigIPHandlers = cmd.applicationFiles.findSubKeys("bigip")
            .filter { isBigIPMultipleConfigKey(it, false) }
            .flatMap { host ->
                setOf(
                    // host == host in OpenShift Route resource
                    AuroraConfigFieldHandler("bigip/$host"),
                    AuroraConfigFieldHandler("bigip/$host/service"),
                    AuroraConfigFieldHandler("bigip/$host/asmPolicy"),
                    AuroraConfigFieldHandler("bigip/$host/externalHost"),
                    AuroraConfigFieldHandler("bigip/$host/oauthScopes"),
                    AuroraConfigFieldHandler("bigip/$host/apiPaths")
                ) + findRouteAnnotationHandlers("bigip/$host", cmd.applicationFiles, "routeAnnotations")
            }

        // This type of configuration only supports one BigIP route. This is now deprecated in favor of multipleBigIPHandlers.
        val legacyHandlers = setOf(
            AuroraConfigFieldHandler("bigip/service"),
            AuroraConfigFieldHandler("bigip/asmPolicy"),
            AuroraConfigFieldHandler("bigip/externalHost"),
            AuroraConfigFieldHandler("bigip/oauthScopes"),
            AuroraConfigFieldHandler("bigip/apiPaths")
        ) + findRouteAnnotationHandlers("bigip", cmd.applicationFiles, "routeAnnotations")

        return setOf(bigipEnabledHandler) + multipleBigIPHandlers + legacyHandlers
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        val multipleBigIPConfig = adc.getSubKeys("bigip").filter {
            isBigIPMultipleConfigKey(it.key, true)
        }

        val isMissingLegacyServiceConfig =
            adc.hasSubKeys("bigip") && adc.getOrNull<String>("bigip/service").isNullOrEmpty()
        val isMissingMultipleConfig = multipleBigIPConfig.isEmpty() ||
            multipleBigIPConfig.any { adc.getOrNull<String>("${it.key}/service").isNullOrEmpty() }

        if (isMissingLegacyServiceConfig && isMissingMultipleConfig) {
            throw AuroraDeploymentSpecValidationException("bigip/<host>/service is required if any other bigip flags are set")
        }

        val hasConfiguredBothLegacyAndMultipleConfig = !isMissingLegacyServiceConfig && !isMissingMultipleConfig
        if (hasConfiguredBothLegacyAndMultipleConfig) {
            throw AuroraDeploymentSpecValidationException("specifying both bigip/service and bigip/<host>/service is not allowed. bigip/service is deprecated and you should move that configuration to bigip/<host>/service.")
        }

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val enabled = adc.isFeatureEnabled()
        if (!enabled) {
            return emptySet()
        }

        val hasLegacyConfig = adc.getOrNull<String>("bigip/service") != null

        if (hasLegacyConfig) {
            return generateBigIPResourcesWithLegacyConfig(adc)
        }

        return adc.getSubKeys("bigip")
            .filter { isBigIPMultipleConfigKey(it.key, true) }
            .map { it.key.split("/").last() }
            .flatMap { generateBigIPResources(it, adc) }
            .toSet()
    }

    fun generateBigIPResources(host: String, adc: AuroraDeploymentSpec): Set<AuroraResource> {
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

    // Deprecated
    // Code in this function could be merged with generateBigIPResources, but keeping it separate will make it easier
    // to delete legacy config.
    fun generateBigIPResourcesWithLegacyConfig(adc: AuroraDeploymentSpec): Set<AuroraResource> {
        val routeName = "${adc.name}-bigip"

        // dette var den gamle applicationDeploymentId som må nå være hostname
        val routeHost = DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")

        val auroraRoute = Route(
            objectName = routeName,
            host = routeHost,
            annotations = adc.getRouteAnnotations("bigip/routeAnnotations/").addIfNotNull("bigipRoute" to "true")
        )

        val bigIp = BigIp(
            _metadata = newObjectMeta {
                name = adc.name
                namespace = adc.namespace
            },
            spec = BigIpSpec(
                routeName, BigIpKonfigurasjonstjenesten(
                    service = adc["bigip/service"],
                    asmPolicy = adc.getOrNull("bigip/asmPolicy"),
                    oauthScopes = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/oauthScopes"),
                    apiPaths = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/apiPaths"),
                    externalHost = adc.getOrNull("bigip/externalHost")
                )
            )
        )

        return setOf(
            auroraRoute.generateOpenShiftRoute(adc.namespace, adc.name, routeSuffix).generateAuroraResource(),
            bigIp.generateAuroraResource()
        )
    }

    private fun AuroraDeploymentSpec.isFeatureEnabled(): Boolean {
        val hasSubkeys = this.hasSubKeys("bigip")
        return getOrNull<Boolean>("bigip/enabled") ?: hasSubkeys
    }

    fun fetchExternalHostsAndPaths(adc: AuroraDeploymentSpec): List<String> {
        val enabled = adc.isFeatureEnabled()
        if (!enabled) return emptyList()

        val host: String = adc.getOrNull("bigip/externalHost") ?: return emptyList()
        val legacyExternalHostsAndPaths = adc.getDelimitedStringOrArrayAsSet("bigip/apiPaths").map {
            "$host$it"
        }

        val multipleHostsAndPaths = adc.getSubKeys("bigip")
            .filter { isBigIPMultipleConfigKey(it.key, true) }
            .mapNotNull { host -> adc.getOrNull<String>("bigip/$host/externalHost") }
            .flatMap { externalHost ->
                adc.getDelimitedStringOrArrayAsSet("bigip/$host/apiPaths").map {
                    "$externalHost$it"
                }
            }

        return legacyExternalHostsAndPaths + multipleHostsAndPaths
    }
}
