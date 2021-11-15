package no.skatteetaten.aurora.boober.feature.azure

import no.skatteetaten.aurora.boober.feature.AbstractResolveTagFeature
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.isJob
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.isListOrEmpty
import no.skatteetaten.aurora.boober.utils.isValidDns
import no.skatteetaten.aurora.boober.utils.validUrl
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AzureFeature(
    cantusService: CantusService,
    @Value("\${clinger.sidecar.default.version:0.4.0}") val sidecarVersion: String
) : AbstractResolveTagFeature(cantusService, "") {
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
                validator = { it.validUrl(required = false) }
            ),

            AuroraConfigFieldHandler(
                JwtToStsConverterSubPart.ConfigPath.ivGroupsRequired,
                defaultValue = false,
                validator = { it.boolean() }
            ),

            AuroraConfigFieldHandler(
                AuroraAzureAppSubPart.ConfigPath.azureAppFqdn,
                validator = { it.isValidDns(required = false) }
            ),
            AuroraConfigFieldHandler(
                AuroraAzureAppSubPart.ConfigPath.groups,
                validator = { it.isListOrEmpty(required = false) }
            ),
            AuroraConfigFieldHandler(
                AuroraAzureAppSubPart.ConfigPath.managedRoute,
                defaultValue = false,
                validator = { it.boolean() }
            ),
            AuroraConfigFieldHandler(
                "webseal",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("webseal/host") // Needed to be able to run tests
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
        jwtToStsConverter.modify(adc, resources, context, this)
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
