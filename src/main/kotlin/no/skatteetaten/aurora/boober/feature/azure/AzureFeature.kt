package no.skatteetaten.aurora.boober.feature.azure

import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.feature.AbstractResolveTagFeature
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.isJob
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.isListOrEmpty
import no.skatteetaten.aurora.boober.utils.isValidDns
import no.skatteetaten.aurora.boober.utils.validUrl
import org.springframework.beans.factory.annotation.Value

class AzureFeature(
    cantusService: CantusService,
    @Value("\${clinger.sidecar.default.version:0.4.0}") val sidecarVersion: String
) : AbstractResolveTagFeature(cantusService) {
    private val jwtToStsConverter = JwtToStsConverterSubPart()
    private val auroraAzureApp = AuroraAzureAppSubPart()

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        return spec.isJwtToStsConverterEnabled || spec.isAzureAppFqdnEnabled
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                JwtToStsConverterSubPart.ConfigPath.enabled,
                defaultValue = false,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(
                JwtToStsConverterSubPart.ConfigPath.version,
                defaultValue = sidecarVersion
            ),
            AuroraConfigFieldHandler(
                JwtToStsConverterSubPart.ConfigPath.discoveryUrl,
                validator = { it.validUrl(required = false) }),

            AuroraConfigFieldHandler(
                JwtToStsConverterSubPart.ConfigPath.ivGroupsRequired,
                defaultValue = false,
                validator = { it.boolean() }),

            AuroraConfigFieldHandler(
                AuroraAzureAppSubPart.ConfigPath.azureAppFqdn,
                validator = { it.isValidDns(required = false) }
            ),
            AuroraConfigFieldHandler(
                AuroraAzureAppSubPart.ConfigPath.groups,
                validator = { it.isListOrEmpty(required = false) }
            )
        )
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {
        val clingerTag = spec.getOrNull<String>(JwtToStsConverterSubPart.ConfigPath.version)

        if (validationContext || clingerTag == null) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = "no_skatteetaten_aurora",
            name = "clinger",
            tag = clingerTag
        )
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        if (!adc.isJwtToStsConverterEnabled) {
            return
        }

        val container = jwtToStsConverter.createClingerProxyContainer(adc, context)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                modifyResource(it, "Added clinger sidecar container")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                val podSpec = dc.spec.template.spec
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                modifyResource(it, "Added clinger sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Service") {
                val service: Service = it.resource as Service
                service.spec.ports.filter { p -> p.name == "http" }.forEach { port ->
                    port.targetPort = IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT)
                }

                modifyResource(it, "Changed targetPort to point to clinger")
            }
        }
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return auroraAzureApp.generate(adc, this)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        return auroraAzureApp.validate(adc)
    }
}
