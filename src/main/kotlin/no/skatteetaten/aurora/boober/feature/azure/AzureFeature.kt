package no.skatteetaten.aurora.boober.feature.azure

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import no.skatteetaten.aurora.boober.feature.AbstractResolveTagFeature
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.isJob
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.CantusService

/**
 * Azure feature collects several azure related items. These are:
 * <ul>
 *     <li>JwtToStsConverter: Clinger sidecar converts JWT token to webseal header</li>
 *     <li>AuroraAzureAppSubPart: Control migration away from webseal to azure ad JWT token</li>
 *     <li>AuroraAzureApimSubPart: Registration of openapi-spec in Azure API Management. Notice
 *     that you also need to enable the azure shard and create a dns entry in Azure AD</li>
 * </ul>
 *
 * @see no.skatteetaten.aurora.boober.feature.RouteFeature
 */
@Service
class AzureFeature(
    cantusService: CantusService,
    @Value("\${clinger.sidecar.default.version:0}") val sidecarVersion: String,
    @Value("\${clinger.sidecar.default.ldapurl}") val defaultLdapUrl: String,
    @Value("\${clinger.sidecar.default.jwks}") val defaultAzureJwks: String
) : AbstractResolveTagFeature(cantusService) {
    private val jwtToStsConverter = JwtToStsConverterSubPart()
    private val auroraAzureApp = AuroraAzureAppSubPart()
    private val auroraApim = AuroraAzureApimSubPart()

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        return spec.isJwtToStsConverterEnabled || spec.isAzureAppFqdnEnabled || spec.isApimEnabled
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return jwtToStsConverter.handlers(sidecarVersion, defaultLdapUrl, defaultAzureJwks) +
            auroraAzureApp.handlers() +
            auroraApim.handlers(cmd.applicationFiles)
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
        jwtToStsConverter.modify(adc, resources, context.imageMetadata, this)
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return auroraAzureApp.generate(adc, this) + auroraApim.generate(adc, this)
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        return auroraAzureApp.validate(adc) + auroraApim.validate(adc) + jwtToStsConverter.validate(adc)
    }
}
