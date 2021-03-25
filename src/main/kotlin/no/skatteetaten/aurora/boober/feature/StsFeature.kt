package no.skatteetaten.aurora.boober.feature

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.utils.ConditionalOnPropertyMissingOrEmpty
import no.skatteetaten.aurora.boober.utils.boolean

val AuroraDeploymentSpec.stsCommonName: String?
    get() {
        val simplified = this.isSimplifiedConfig("sts")
        if (!simplified) {
            return this.getOrNull("sts/cn")
        }

        val value: Boolean = this["sts"]
        if (!value) {
            return null
        }
        val groupId: String = this.getOrNull<String>("groupId") ?: ""
        return "$groupId.${this.name}"
    }

private val logger = KotlinLogging.logger { }

// TODO: Should we change this to allow more the one?
@ConditionalOnPropertyMissingOrEmpty("integrations.skap.url")
@Service
class StsDisabledFeature : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "sts",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("sts/cn"),
            header.groupIdHandler
        )
    }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> {
        adc.stsCommonName?.let {
            if (it.isNotEmpty()) {
                return listOf(AuroraConfigException("STS is not supported."))
            }
        }
        return emptyList()
    }
}

@Service
@ConditionalOnProperty(value = ["integrations.skap.url", "integrations.bigbird.url"])
class StsFeature(
    val sts: StsProvisioner,
    @Value("\${integrations.bigbird.url}") val bigBirdUrl: String
) : Feature {
    private val suffix = "sts"

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                suffix,
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("sts/cn"),
            header.groupIdHandler
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {
        return adc.stsCommonName?.let {
            val result = sts.generateCertificate(it, adc.name, adc.envName)

            val secret = StsSecretGenerator.create(adc.name, result, adc.namespace, suffix)
            setOf(generateResource(secret))
        } ?: emptySet()
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        adc.stsCommonName?.let {
            StsSecretGenerator.attachSecret(
                appName = adc.name,
                certSuffix = suffix,
                resources = resources,
                source = this::class.java,
                additionalEnv = mapOf("STS_DISCOVERY_URL" to bigBirdUrl)
            )
        }
    }

    fun willCreateResource(spec: AuroraDeploymentSpec) = spec.stsCommonName != null
}
