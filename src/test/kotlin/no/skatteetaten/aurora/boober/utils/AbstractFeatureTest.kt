package no.skatteetaten.aurora.boober.utils

import assertk.Assert
import assertk.Result
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.spec
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChangeParams
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newDeploymentConfig
import com.fkorotkov.openshift.newDeploymentTriggerPolicy
import com.fkorotkov.openshift.rollingParams
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.template
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.clearAllMocks
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.DeploymentConfigFeature
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.AuroraResourceSource
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.junit.jupiter.api.BeforeEach
import java.time.Instant

abstract class AbstractFeatureTest : AbstractAuroraConfigTest() {

    val cluster = "utv"
    val affiliation = "paas"

    val openShiftClient: OpenShiftClient = mockk()

    val FEATURE_ABOUT = """{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "segment" : "aurora",
  "affiliation" : "$affiliation",
  "type": "deploy",
  "cluster": "utv"
}"""

    fun createdcAuroraResource() =
        AuroraResource(newDeploymentConfig {

            metadata {
                name = "simple"
                namespace = "paas-utv"
            }
            spec {
                strategy {
                    type = "Rolling"
                    rollingParams {
                        intervalSeconds = 1
                        maxSurge = IntOrString("25%")
                        maxUnavailable = IntOrString(0)
                        timeoutSeconds = 180
                        updatePeriodSeconds = 1L
                    }
                }
                triggers = listOf(
                    newDeploymentTriggerPolicy {
                        type = "ImageChange"
                        imageChangeParams {
                            automatic = true
                            containerNames = listOf("simple")
                            from {
                                name = "simple:default"
                                kind = "ImageStreamTag"
                            }
                        }
                    }

                )
                replicas = 1
                selector = mapOf("name" to "simple")
                template {
                    spec {
                        containers = listOf(
                            newContainer {
                                name = "simple"
                            }
                        )
                        restartPolicy = "Always"
                        dnsPolicy = "ClusterFirst"
                    }
                }
            }
        }, createdSource = AuroraResourceSource(DeploymentConfigFeature::class.java))

    abstract val feature: Feature
    val mapper = jsonMapper()
    val config = mutableMapOf(
        "about.json" to FEATURE_ABOUT,
        "utv/about.json" to """{ }""",
        "simple.json" to """{ }""",
        "utv/simple.json" to """{ }"""
    )
    val aid = ApplicationDeploymentRef("utv", "simple")

    @BeforeEach
    fun setup() {
        Instants.determineNow = { Instant.EPOCH }
        clearAllMocks()
    }

    fun <T> Assert<Result<T>>.singleApplicationError(expectedMessage: String) {
        this.isFailure()
            .isInstanceOf(MultiApplicationValidationException::class)
            .transform { mae ->
                val errors = mae.errors.flatMap { it.errors }
                if (errors.size != 1) {
                    throw mae
                } else {
                    errors.first()
                }
            }
            .messageContains(expectedMessage)
    }

    fun createAuroraDeploymentContext(
        app: String = """{}""",
        base: String = """{}""",
        fullValidation: Boolean = true
    ): AuroraDeploymentContext {
        val service = AuroraDeploymentContextService(featuers = listOf(feature))
        val auroraConfig =
            createAuroraConfig(config.addIfNotNull("simple.json" to base).addIfNotNull("utv/simple.json" to app))

        val deployCommand = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = aid,
            auroraConfigRef = AuroraConfigRef("test", "master", "123abb")
        )
        return service.createValidatedAuroraDeploymentContexts(listOf(deployCommand), fullValidation).first()
    }

    fun generateResources(app: String = """{}""", base: String = """{}"""): Set<AuroraResource> {
        val adc = createAuroraDeploymentContext(app, base)

        return adc.features.flatMap {
            it.key.generate(it.value, adc.cmd)
        }.toSet()
    }

    fun modifyResources(
        app: String = """{}""",
        base: String = """{}""",
        existingResources: MutableSet<AuroraResource>
    ) {
        val adc = createAuroraDeploymentContext(app, base)

        val generated = generateResources(app, base)
        existingResources.addAll(generated)

        adc.features.forEach {
            it.key.modify(it.value, existingResources, adc.cmd)
        }
    }

    fun createAuroraConfigFieldHandlers(
        app: String = """{}""",
        base: String = """{}"""
    ): Set<AuroraConfigFieldHandler> {
        val ctx = createAuroraDeploymentContext(app, base)
        return ctx.featureHandlers.values.first()
    }

    fun assertDeploymentConfigMountsVolume(
        dc: DeploymentConfig,
        attachment: HasMetadata,
        additionalEnv: Map<String, String> = emptyMap()
    ) {
        val podSpec = dc.spec.template.spec

        val volumeName = podSpec.volumes[0].name
        val volumeEnvName = "VOLUME_${volumeName}".replace("-", "_").toUpperCase()
        val volumeEnvValue = podSpec.containers[0].volumeMounts[0].mountPath

        val expectedEnv = additionalEnv.addIfNotNull(volumeEnvName to volumeEnvValue)

        assertThat(volumeName).isEqualTo(podSpec.containers[0].volumeMounts[0].name)
        when {
            attachment.kind == "ConfigMap" -> assertThat(podSpec.volumes[0].configMap.name).isEqualTo(attachment.metadata.name)
            attachment.kind == "Secret" -> assertThat(podSpec.volumes[0].secret.secretName).isEqualTo(attachment.metadata.name)
            attachment.kind == "PersistentVolumeClaim" -> assertThat(podSpec.volumes[0].persistentVolumeClaim.claimName).isEqualTo(
                attachment.metadata.name
            )
        }
        val env: Map<String, String> = podSpec.containers[0].env.associate { it.name to it.value }
        assertThat(env).isEqualTo(expectedEnv)
    }
}
