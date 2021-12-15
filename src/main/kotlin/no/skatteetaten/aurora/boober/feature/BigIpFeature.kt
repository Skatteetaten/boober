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
import no.skatteetaten.aurora.boober.utils.truncateStringAndHashTrailingCharacters
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class BigIpFeature(
    @Value("\${boober.route.suffix}") val routeSuffix: String
) : Feature {
    enum class Errors(val message: String) {
        MissingLegacyService("bigip/service is required if other bigip flags are set. bigip/service is deprecated and you should move that configuration to bigip/<name>/service."),
        MissingMultipleService("bigip/<name>/service is required if any other bigip flags are set"),
        BothLegacyAndMultipleConfigIsSet("specifying both bigip/service and bigip/<name>/service is not allowed. bigip/service is deprecated and you should move that configuration to bigip/<name>/service.")
    }

    val bigIpLegacyConfigKeys: List<String> = listOf(
        "enabled",
        "service",
        "asmPolicy",
        "externalHost",
        "oauthScopes",
        "apiPaths",
        "routeAnnotations",
        "trailingSlash"
    )

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        val multipleConfigs = cmd.applicationFiles.getBigIPHosts()
            .flatMap { name ->
                setOf(
                    // name is part of host in the OpenShift Route resource
                    AuroraConfigFieldHandler("bigip/$name/enabled", { it.boolean() }),
                    AuroraConfigFieldHandler("bigip/$name/service"),
                    AuroraConfigFieldHandler("bigip/$name/asmPolicy"),
                    AuroraConfigFieldHandler("bigip/$name/externalHost"),
                    AuroraConfigFieldHandler("bigip/$name/oauthScopes"),
                    AuroraConfigFieldHandler("bigip/$name/apiPaths"),
                    AuroraConfigFieldHandler("bigip/$name/trailingSlash")
                ) + findRouteAnnotationHandlers("bigip/$name", cmd.applicationFiles, "routeAnnotations")
            }.toSet()

        val legacyConfig = setOf(
            AuroraConfigFieldHandler("bigip/service"),
            AuroraConfigFieldHandler("bigip/asmPolicy"),
            AuroraConfigFieldHandler("bigip/externalHost"),
            AuroraConfigFieldHandler("bigip/oauthScopes"),
            AuroraConfigFieldHandler("bigip/apiPaths"),
            AuroraConfigFieldHandler("bigip/enabled", { it.boolean() }),
            AuroraConfigFieldHandler("bigip/trailingSlash")
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
            return listOf(AuroraDeploymentSpecValidationException(Errors.BothLegacyAndMultipleConfigIsSet.message))
        }

        if (!isMultipleConfig && isMissingLegacyServiceConfig) {
            return listOf(AuroraDeploymentSpecValidationException(Errors.MissingLegacyService.message))
        }

        if (isMissingMultipleServiceConfig) {
            return listOf(AuroraDeploymentSpecValidationException(Errors.MissingMultipleService.message))
        }

        return emptyList()
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return if (adc.isMultipleBigIpConfig()) {
            adc.getBigIPHosts()
                .flatMap { generateBigIPResources(it, adc) }
                .toSet()
        } else {
            generateLegacyBigIPResource(adc, context)
        }
    }

    fun generateBigIPResources(host: String, adc: AuroraDeploymentSpec): Set<AuroraResource> {
        if (!adc.isBigIPHostEnabled(host)) {
            return emptySet()
        }

        // This is used to preserve legacy configuration
        val isApplicationHost = host == adc.name

        val routeHost = if (isApplicationHost) {
            DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")
        } else {
            "bigip-${adc.name}-${adc.namespace}-$host".truncateStringAndHashTrailingCharacters(63)
        }

        val routeName = if (isApplicationHost) "$host-bigip" else "${adc.name}-$host-bigip"

        val route = ConfiguredRoute(
            objectName = routeName,
            host = routeHost,
            annotations = adc.getRouteAnnotations("bigip/$host/routeAnnotations/").addIfNotNull("bigipRoute" to "true")
        )

        val bigIpName = if (isApplicationHost) host else "${adc.name}-$host"

        val bigIp = BigIp(
            _metadata = newObjectMeta {
                name = bigIpName
                namespace = adc.namespace
            },
            spec = BigIpSpec(
                routeName,
                BigIpKonfigurasjonstjenesten(
                    service = adc["bigip/$host/service"],
                    asmPolicy = adc.getOrNull("bigip/$host/asmPolicy"),
                    oauthScopes = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/$host/oauthScopes"),
                    apiPaths = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/$host/apiPaths"),
                    externalHost = adc.getOrNull("bigip/$host/externalHost"),
                    trailingSlash = adc.getOrNull("bigip/$host/trailingSlash")
                )
            )
        )

        return setOf(
            route.generateOpenShiftRoute(adc.namespace, adc.name, routeSuffix).generateAuroraResource(),
            bigIp.generateAuroraResource()
        )
    }

    fun generateLegacyBigIPResource(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        val enabled = adc.isLegacyFeatureEnabled()
        if (!enabled) {
            return emptySet()
        }

        val routeName = "${adc.name}-bigip"

        // dette var den gamle applicationDeploymentId som må nå være hostname
        val routeHost = DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")

        val auroraRoute = ConfiguredRoute(
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
                routeName,
                BigIpKonfigurasjonstjenesten(
                    service = adc["bigip/service"],
                    asmPolicy = adc.getOrNull("bigip/asmPolicy"),
                    oauthScopes = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/oauthScopes"),
                    apiPaths = adc.getDelimitedStringOrArrayAsSetOrNull("bigip/apiPaths"),
                    externalHost = adc.getOrNull("bigip/externalHost"),
                    trailingSlash = adc.getOrNull("bigip/trailingSlash")
                )
            )
        )

        return setOf(
            auroraRoute.generateOpenShiftRoute(adc.namespace, adc.name, routeSuffix).generateAuroraResource(),
            bigIp.generateAuroraResource()
        )
    }

    private fun AuroraDeploymentSpec.isLegacyFeatureEnabled(): Boolean {
        val hasService = this.getOrNull<String>("bigip/service")
        val isEnabled = getOrNull<Boolean>("bigip/enabled")
        return isEnabled ?: !hasService.isNullOrEmpty()
    }

    fun fetchExternalHostsAndPaths(adc: AuroraDeploymentSpec): List<String> {
        return if (adc.isMultipleBigIpConfig()) {
            adc.getBigIPHosts()
                .filter { adc.isBigIPHostEnabled(it) }
                .mapNotNull { host -> adc.getOrNull<String>("bigip/$host/externalHost") }
                .flatMap { externalHost ->
                    adc.getDelimitedStringOrArrayAsSet("bigip/$externalHost/apiPaths").map {
                        "$externalHost$it"
                    }
                }
        } else {
            val enabled = adc.isLegacyFeatureEnabled()
            if (!enabled) return emptyList()
            val host: String = adc.getOrNull("bigip/externalHost") ?: return emptyList()
            adc.getDelimitedStringOrArrayAsSet("bigip/apiPaths").map {
                "$host$it"
            }
        }
    }

    private fun List<AuroraConfigFile>.getBigIPHosts(): Set<String> {
        return this.findSubKeys("bigip").filter { !bigIpLegacyConfigKeys.contains(it) }.toSet()
    }

    private fun AuroraDeploymentSpec.isMultipleBigIpConfig(): Boolean {
        return this.getBigIPHosts().isNotEmpty()
    }

    private fun AuroraDeploymentSpec.getBigIPHosts(): Set<String> {
        return this.findSubKeysRaw("bigip")
            .filter {
                val isSubKeyToHost = it.split("/").size > 2
                when {
                    isSubKeyToHost -> false
                    else -> !bigIpLegacyConfigKeys.contains(it)
                }
            }
            .toSet()
    }

    private fun AuroraDeploymentSpec.isBigIPHostEnabled(host: String): Boolean {
        val hasSubKeys = this.hasSubKeys("bigip/$host")
        return getOrNull<Boolean>("bigip/$host/enabled") ?: hasSubKeys
    }
}
