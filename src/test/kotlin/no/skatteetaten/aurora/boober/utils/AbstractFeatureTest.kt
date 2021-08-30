package no.skatteetaten.aurora.boober.utils

import java.time.Instant
import kotlin.reflect.KClass
import org.junit.jupiter.api.BeforeEach
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
import assertk.Assert
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.support.expected
import assertk.assertions.support.show
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.VolumeProjection
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.HeaderHandlers
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
import no.skatteetaten.aurora.boober.service.IdService
import no.skatteetaten.aurora.boober.service.IdServiceFallback
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.createAuroraConfig
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.getAuroraConfigSamples

/*
  Abstract class to test a single feature
  Override the feature variable with the Feature you want to test

  Look at the helper methods in this class to create handlers/resources for this feature

 */

private val logger = KotlinLogging.logger {}

class TestDefaultFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return emptySet()
    }
}

abstract class AbstractFeatureTest : ResourceLoader() {

    abstract val feature: Feature

    val cluster = "utv"
    val affiliation = "paas"
    val appName = "simple"
    val environment = "utv"
    val kubeNs = "$affiliation-$environment"
    val aid = ApplicationDeploymentRef(environment, appName)

    val openShiftClient: OpenShiftClient = mockk()

    // TODO: rewrite when resources are not loaded with package
    val FEATURE_ABOUT = getAuroraConfigSamples().files.find { it.name == "about.json" }?.contents
        ?: throw RuntimeException("Could not find about.json")

    fun createEmptyImageStream() =
        AuroraResource(newImageStream {
            metadata {
                name = appName
                namespace = kubeNs
            }
            spec {
                dockerImageRepository = "docker.registry/org_test/simple"
            }
        }, createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

    fun createEmptyService() = AuroraResource(newService {
        metadata {
            name = "simple"
            namespace = kubeNs
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
                namespace = kubeNs
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
        }, createdSource = AuroraResourceSource(TestDefaultFeature::class.java)
    )

    fun createEmptyApplicationDeployment() = AuroraResource(
        ApplicationDeployment(
            spec = ApplicationDeploymentSpec(),
            _metadata = newObjectMeta {
                name = "simple"
                namespace = kubeNs
            }
        ), createdSource = AuroraResourceSource(TestDefaultFeature::class.java))

    // TODO: This should be read from a file, we should also provide IS, Service and AD objects that can be modified.
    fun createEmptyDeploymentConfig() =
        AuroraResource(newDeploymentConfig {

            metadata {
                name = "simple"
                namespace = kubeNs
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

    val config = mutableMapOf(
        "about.json" to FEATURE_ABOUT,
        "$environment/about.json" to """{ }""",
        "$appName.json" to """{ }""",
        "$environment/$appName.json" to """{ }"""
    )

    @BeforeEach
    fun setup() {
        Instants.determineNow = { Instant.EPOCH }
        clearAllMocks()
    }

    fun createCustomAuroraDeploymentContext(
        adr: ApplicationDeploymentRef,
        vararg file: Pair<String, String>
    ): AuroraDeploymentContext {
        val idService = mockk<IdService>()

        every {
            idService.generateOrFetchId(any())
        } returns "1234567890"

        val service =
            AuroraDeploymentContextService(features = listOf(feature), idService = idService, idServiceFallback = null)
        val auroraConfig = createAuroraConfig(file.toMap())

        val deployCommand = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = adr,
            auroraConfigRef = AuroraConfigRef("test", "master", "123abb"),
            overrides = emptyList()
        )
        return service.createValidatedAuroraDeploymentContexts(listOf(deployCommand), true).first()
    }

    /*
      CreateDeploymentContext for the feature in test
     */
    fun createAuroraDeploymentContext(
        app: String = """{}""",
        fullValidation: Boolean = true,
        files: List<AuroraConfigFile> = emptyList(),
        useHerkimerIdService: Boolean = true
    ): AuroraDeploymentContext {
        val idService =
            if (useHerkimerIdService)
                mockk<IdService>().also {
                    every {
                        it.generateOrFetchId(any())
                    } returns "1234567890"
                } else null

        val idServiceFallback =
            if (!useHerkimerIdService)
                mockk<IdServiceFallback>().also {
                    every {
                        it.generateOrFetchId(any(), any())
                    } returns "fallbackid"
                } else null
        val service =
            AuroraDeploymentContextService(
                features = listOf(feature),
                idService = idService,
                idServiceFallback = idServiceFallback
            )
        val auroraConfig =
            createAuroraConfig(config.addIfNotNull("$environment/simple.json" to app), files)

        val deployCommand = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = aid,
            auroraConfigRef = AuroraConfigRef("test", "master", "123abb"),
            overrides = files.filter { it.override }
        )
        return service.createValidatedAuroraDeploymentContexts(listOf(deployCommand), fullValidation).first()
    }

    fun createAuroraDeploymentSpecForFeature(
        app: String = """{}""",
        fullValidation: Boolean = true,
        files: List<AuroraConfigFile> = emptyList()
    ): AuroraDeploymentSpec {

        val ctx = createAuroraDeploymentContext(app, fullValidation, files)

        val headers = ctx.cmd.applicationDeploymentRef
            .run { HeaderHandlers.create(application, environment) }
            .handlers.map { it.name }
        val fields = ctx.spec.fields
            .filterNot { headers.contains(it.key) }
            .filterNot { it.key in listOf("applicationDeploymentRef", "configVersion") }

        return ctx.spec.copy(fields = fields)
    }

    fun generateResources(
        app: String = """{}""",
        vararg resource: AuroraResource
    ): List<AuroraResource> {
        return generateResources(app, resource.toMutableSet(), createdResources = 1)
    }

    /*
      Will not generate any resources
     */
    fun modifyResources(
        app: String = """{}""",
        vararg resource: AuroraResource
    ): List<AuroraResource> {
        return generateResources(app, resource.toMutableSet(), createdResources = 0)
    }

    fun generateResources(
        app: String = """{}""",
        resource: AuroraResource,
        files: List<AuroraConfigFile> = emptyList(),
        createdResources: Int = 1
    ): List<AuroraResource> {
        return generateResources(app, mutableSetOf(resource), files, createdResources)
    }

    fun generateResources(
        app: String = """{}""",
        resources: MutableSet<AuroraResource> = mutableSetOf(),
        files: List<AuroraConfigFile> = emptyList(),
        createdResources: Int = 1
    ): List<AuroraResource> {

        val numberOfEmptyResources = resources.size
        val adc = createAuroraDeploymentContext(app, files = files)

        val generated = adc.features.flatMap {
            it.key.generate(it.value, adc.featureContext[it.key] ?: emptyMap())
        }.toSet()

        if (resources.isEmpty()) {
            return generated.toList()
        }

        resources.addAll(generated)

        adc.features.forEach {
            it.key.modify(it.value, resources, adc.featureContext[it.key] ?: emptyMap())
        }
        assertThat(resources.size, "Number of resources").isEqualTo(numberOfEmptyResources + createdResources)
        return resources.toList()
    }

    fun createAuroraConfigFieldHandlers(
        app: String = """{}"""
    ): AuroraDeploymentSpec {
        val ctx = createAuroraDeploymentContext(app)
        return ctx.features.values.first()
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
        assertThat(env, "Env vars").isEqualTo(expectedEnv)
        actual
    }

    fun Assert<AuroraResource>.auroraResourceMountsPsat(
        projection: VolumeProjection,
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
        assertThat(podSpec.volumes[0].projected.sources[0].serviceAccountToken.audience).isEqualTo(projection.serviceAccountToken.audience)

        val env: Map<String, String> = podSpec.containers[0].env.associate { it.name to it.value }
        assertThat(env, "Env vars").isEqualTo(expectedEnv)
        actual
    }

    fun Assert<AuroraResource>.auroraResourceCreatedByThisFeature(): Assert<AuroraResource> = transform { actual ->
        val expected = feature::class.java
        if (expected == actual.createdSource.feature) {
            actual
        } else {
            this.expected(":${show(expected)} and:${show(actual.createdSource.feature)} to be the same")
        }
    }

    fun Assert<AuroraResource>.auroraResourceModifiedByThisFeatureWithComment(comment: String, index: Int = 0) =
        transform { ar ->
            val actual = ar.sources.toList()[index]
            val expected = AuroraResourceSource(feature::class.java, comment)
            if (actual == expected) {
                ar
            } else {
                this.expected(":${show(expected)} and:${show(actual)} to be the same")
            }
        }
}

inline fun <reified T : HasMetadata> List<AuroraResource>.findResourceByType(): T =
    this.findResourceByType(T::class).firstOrNull() ?: throw Exception("No resource of specified type found")

inline fun <reified T : HasMetadata> List<AuroraResource>.findResourcesByType(): List<T> =
    this.findResourceByType(T::class)

fun <T : Any> List<AuroraResource>.findResourceByType(kclass: KClass<T>): List<T> =
    filter { it.resource::class == kclass }
        .map { @Suppress("UNCHECKED_CAST") it.resource as T }
