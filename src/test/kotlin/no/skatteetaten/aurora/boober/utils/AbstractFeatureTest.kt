package no.skatteetaten.aurora.boober.utils

import assertk.Assert
import assertk.Result
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import assertk.assertions.support.expected
import assertk.assertions.support.show
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newService
import com.fkorotkov.kubernetes.newServicePort
import com.fkorotkov.kubernetes.spec
import com.fkorotkov.openshift.customStrategy
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChangeParams
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newBuildConfig
import com.fkorotkov.openshift.newDeploymentConfig
import com.fkorotkov.openshift.newDeploymentTriggerPolicy
import com.fkorotkov.openshift.newImageStream
import com.fkorotkov.openshift.output
import com.fkorotkov.openshift.rollingParams
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.template
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.clearAllMocks
import io.mockk.mockk
import java.time.Instant
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.AuroraResourceSource
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.junit.jupiter.api.BeforeEach

/*
  Abstract class to test a single feature
  Override the feature variable with the Feature you want to test

  Look at the helper methods in this class to create handlers/resources for this feature

 */

class TestDefaultFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return emptySet()
    }
}

abstract class AbstractFeatureTest : AbstractAuroraConfigTest() {

    abstract val feature: Feature

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

    fun createEmptyImageStream() =
        AuroraResource(newImageStream {
            metadata {
                name = "simple"
                namespace = "paas-utv"
            }
            spec {
                dockerImageRepository = "docker.registry/org_test/simple"
            }
        }, createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

    fun createEmptyService() = AuroraResource(newService {
        metadata {
            name = "simple"
            namespace = "paas-utv"
        }

        spec {
            ports = listOf(
                newServicePort {
                    name = "http"
                    protocol = "TCP"
                    port = PortNumbers.HTTP_PORT
                    targetPort = IntOrString(PortNumbers.INTERNAL_HTTP_PORT)
                    nodePort = 0
                }
            )

            selector = mapOf("name" to "simple")
            type = "ClusterIP"
            sessionAffinity = "None"
        }
    }, createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

    fun createEmptyBuildConfig() = AuroraResource(
        newBuildConfig {
            metadata {
                name = "simple"
                namespace = "paas-utv"
            }

            spec {
                strategy {
                    type = "Custom"
                    customStrategy {
                        from {
                            kind = "ImageStreamTag"
                            namespace = "openshift"
                            name = "architect:1"
                        }

                        val envMap = mapOf(
                            "ARTIFACT_ID" to "simpe",
                            "GROUP_ID" to "org.test",
                            "VERSION" to "1"
                        )

                        env = envMap.map {
                            newEnvVar {
                                name = it.key
                                value = it.value
                            }
                        }
                        exposeDockerSocket = true
                    }
                    output {
                        to {
                            kind = "ImageStreamTag"
                            name = "simple:latest"
                        }
                    }
                }
            }
        }, createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

    fun createEmptyApplicationDeployment() = AuroraResource(
        ApplicationDeployment(
            spec = ApplicationDeploymentSpec(),
            _metadata = newObjectMeta {
                name = "simple"
                namespace = "paas-utv"
            }
        ), createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

    // TODO: This should be read from a file, we should also provide IS, Service and AD objects that can be modified.
    fun createEmptyDeploymentConfig() =
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
        }, createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

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

    fun <T> Assert<Result<T>>.applicationErrors(vararg message: String) {
        this.applicationErrors(message.toList())
    }

    fun <T> Assert<Result<T>>.applicationErrors(messages: List<String>) {
        this.isFailure()
            .isInstanceOf(MultiApplicationValidationException::class)
            .transform { mae ->
                val errors = mae.errors.flatMap { it.errors }
                errors.zip(messages).forEach { (actual, expected) ->
                    if (!actual.localizedMessage.contains(expected)) {
                        expected(":${show(actual.localizedMessage)} to contain:${show(expected)}")
                    }
                }
            }
    }

    fun createAuroraDeploymentContext(
        app: String = """{}""",
        fullValidation: Boolean = true,
        files: List<AuroraConfigFile> = emptyList()
    ): AuroraDeploymentContext {
        val service = AuroraDeploymentContextService(featuers = listOf(feature))
        val auroraConfig =
            createAuroraConfig(config.addIfNotNull("utv/simple.json" to app), files)

        val deployCommand = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = aid,
            auroraConfigRef = AuroraConfigRef("test", "master", "123abb"),
            overrides = files.filter { it.override }
        )
        return service.createValidatedAuroraDeploymentContexts(listOf(deployCommand), fullValidation).first()
    }

    fun generateResources(
        app: String = """{}""",
        vararg resource: AuroraResource
    ): Set<AuroraResource> {
        return generateResources(app, resource.toMutableSet())
    }

    fun generateResources(
        app: String = """{}""",
        resource: AuroraResource,
        files: List<AuroraConfigFile> = emptyList()
    ): Set<AuroraResource> {
        return generateResources(app, mutableSetOf(resource), files)
    }

    fun generateResources(
        app: String = """{}""",
        resources: MutableSet<AuroraResource> = mutableSetOf(),
        files: List<AuroraConfigFile> = emptyList()
    ): Set<AuroraResource> {

        val adc = createAuroraDeploymentContext(app, files = files)

        val generated = adc.features.flatMap {
            it.key.generate(it.value, adc.cmd)
        }.toSet()

        if (resources.isEmpty()) {
            return generated
        }

        resources.addAll(generated)

        adc.features.forEach {
            it.key.modify(it.value, resources, adc.cmd)
        }
        return resources
    }

    fun createAuroraConfigFieldHandlers(
        app: String = """{}"""
    ): Set<AuroraConfigFieldHandler> {
        val ctx = createAuroraDeploymentContext(app)
        return ctx.featureHandlers.values.first()
    }

    fun Assert<AuroraResource>.auroraResourceMountsAttachment(
        attachment: HasMetadata,
        additionalEnv: Map<String, String> = emptyMap()
    ): Assert<AuroraResource> = transform { actual ->

        assertThat(actual.resource).isInstanceOf(DeploymentConfig::class.java)
        assertThat(actual).auroraResourceModifiedByThisFeatureWithComment("Added env vars, volume mount, volume")

        val dc = actual.resource as DeploymentConfig
        val podSpec = dc.spec.template.spec

        val volumeName = podSpec.volumes[0].name
        val volumeEnvName = "VOLUME_$volumeName".replace("-", "_").toUpperCase()
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
        actual
    }

    fun Assert<AuroraResource>.auroraResourceCreatedByThisFeature(): Assert<AuroraResource> = transform { actual ->
        val expected = feature::class.java
        if (expected == actual.createdSource.feature) {
            actual
        } else {
            expected(":${show(expected)} and:${show(actual.createdSource.feature)} to be the same")
        }
    }

    fun Assert<AuroraResource>.auroraResourceMatchesFile(fileName: String): Assert<AuroraResource> = transform { ar ->
        val actualJson: JsonNode = jacksonObjectMapper().convertValue(ar.resource)
        val expectedJson = loadJsonResource(fileName)
        compareJson(expectedJson, actualJson)
        ar
    }

    fun Assert<AuroraResource>.auroraResourceModifiedByThisFeatureWithComment(comment: String) = transform { ar ->
        val actual = ar.sources.first()
        val expected = AuroraResourceSource(feature::class.java, comment)
        if (actual == expected) {
            ar
        } else {
            expected(":${show(expected)} and:${show(actual)} to be the same")
        }
    }
}
