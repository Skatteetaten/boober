package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.apps.metadata
import com.fkorotkov.kubernetes.apps.newDeployment
import com.fkorotkov.kubernetes.apps.rollingUpdate
import com.fkorotkov.kubernetes.apps.spec
import com.fkorotkov.kubernetes.apps.strategy
import com.fkorotkov.kubernetes.apps.template
import com.fkorotkov.kubernetes.emptyDir
import com.fkorotkov.kubernetes.fieldRef
import com.fkorotkov.kubernetes.httpGet
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newLabelSelector
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.newService
import com.fkorotkov.kubernetes.newServicePort
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.securityContext
import com.fkorotkov.kubernetes.spec
import com.fkorotkov.kubernetes.tcpSocket
import com.fkorotkov.kubernetes.valueFrom
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChangeParams
import com.fkorotkov.openshift.importPolicy
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newDeploymentConfig
import com.fkorotkov.openshift.newDeploymentTriggerPolicy
import com.fkorotkov.openshift.newImageStream
import com.fkorotkov.openshift.newTagReference
import com.fkorotkov.openshift.recreateParams
import com.fkorotkov.openshift.rollingParams
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.template
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.IntOrStringBuilder
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.AuroraVersion
import no.skatteetaten.aurora.boober.model.Paths
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.model.Validator
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import no.skatteetaten.aurora.boober.utils.oneOf
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.removeExtension
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value

val AuroraDeploymentSpec.envName get(): String = this.getOrNull("env/name") ?: this["envName"]
val AuroraDeploymentSpec.name get(): String = this["name"]
val AuroraDeploymentSpec.affiliation get(): String = this["affiliation"]
val AuroraDeploymentSpec.type get(): TemplateType = this["type"]
val AuroraDeploymentSpec.deployState get(): DeploymentState = this["deployState"]

val AuroraDeploymentSpec.applicationDeploymentId: String get() = DigestUtils.sha1Hex("${this.namespace}/${this.name}")
val AuroraDeploymentSpec.namespace
    get(): String {
        return when {
            envName.isBlank() -> affiliation
            envName.startsWith("-") -> "${affiliation}$envName"
            else -> "$affiliation-$envName"
        }
    }
val AuroraDeploymentSpec.releaseTo: String? get() = this.getOrNull<String>("releaseTo")?.takeUnless { it.isEmpty() }
val AuroraDeploymentSpec.groupId: String get() = this["groupId"]
val AuroraDeploymentSpec.artifactId: String get() = this["artifactId"]
val AuroraDeploymentSpec.dockerGroup get() = groupId.replace(".", "_")

val AuroraDeploymentSpec.dockerImagePath: String get() = "$dockerGroup/${this.artifactId}"

// TODO: This version/deployTag can be empty if template and version is not set in auroraConfig, can we just enforce
// TODO: everybody to have version for template and say it is required?
val AuroraDeploymentSpec.version: String get() = this["version"]
val AuroraDeploymentSpec.dockerTag: String get() = releaseTo ?: version

// transform to resource right away?
fun AuroraDeploymentSpec.probe(name: String): Probe? {
    val adc = this
    return this.featureEnabled(name) { field ->
        newProbe {
            val probePort = IntOrStringBuilder().withIntVal(adc["$field/port"]).build()
            adc.getOrNull<String>("$field/path")?.let { probePath ->
                httpGet {
                    path = probePath.ensureStartWith("/")
                    port = probePort
                }
            } ?: tcpSocket {
                port = probePort
            }

            initialDelaySeconds = adc["$field/delay"]
            timeoutSeconds = adc["$field/timeout"]
        }
    }
}

val AuroraDeploymentSpec.cluster: String get() = this["cluster"]

fun AuroraDeploymentSpec.extractPlaceHolders(): Map<String, String> {
    val segmentPair = this.getOrNull<String>("segment")?.let {
        "segment" to it
    }
    val placeholders = mapOf(
        "name" to name,
        "env" to envName,
        "affiliation" to affiliation,
        "cluster" to cluster
    ).addIfNotNull(segmentPair)
    return placeholders
}

val AuroraDeploymentSpec.versionHandler: AuroraConfigFieldHandler
    get() =
        AuroraConfigFieldHandler("version",
            validator = {
                it.pattern(
                    pattern = "^[\\w][\\w.-]{0,127}$",
                    message = "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes",
                    required = this.type.versionAndGroupRequired
                )
            })

val AuroraDeploymentSpec.groupIdHandler: AuroraConfigFieldHandler
    get() = AuroraConfigFieldHandler(
        "groupId",
        validator = {
            it.length(
                length = 200,
                message = "GroupId must be set and be shorter then 200 characters",
                required = this.type.versionAndGroupRequired
            )
        })

fun gavHandlers(spec: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

    val artifactValidator: Validator =
        { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }

    val artifactHandler = AuroraConfigFieldHandler(
        "artifactId",
        defaultValue = cmd.applicationFiles.find { it.type == AuroraConfigFileType.BASE }?.name?.removeExtension(),
        defaultSource = "fileName",
        validator = artifactValidator
    )
    return setOf(
        artifactHandler,
        spec.groupIdHandler,
        spec.versionHandler
    )
}

abstract class AbstractDeployFeature(
    @Value("\${integrations.docker.registry}") val dockerRegistry: String
) : Feature {

    abstract fun createContainers(adc: AuroraDeploymentSpec): List<Container>

    abstract fun enable(platform: ApplicationPlatform): Boolean

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type in listOf(
            TemplateType.deploy,
            TemplateType.development
        ) && enable(header.applicationPlatform)
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> =
        gavHandlers(header, cmd) + setOf(
            AuroraConfigFieldHandler("releaseTo"),
            AuroraConfigFieldHandler(
                "deployStrategy/type",
                defaultValue = "rolling",
                validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180),
            AuroraConfigFieldHandler("replicas", defaultValue = 1),
            AuroraConfigFieldHandler("serviceAccount"),

            AuroraConfigFieldHandler(
                "prometheus",
                defaultValue = true,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                "prometheus/path",
                defaultValue = "/prometheus"
            ),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),

            AuroraConfigFieldHandler(
                "readiness",
                defaultValue = true,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("readiness/port", defaultValue = 8080),
            AuroraConfigFieldHandler("readiness/path"),
            AuroraConfigFieldHandler("readiness/delay", defaultValue = 10),
            AuroraConfigFieldHandler("readiness/timeout", defaultValue = 1),
            AuroraConfigFieldHandler(
                "liveness",
                validator = { it.boolean() },
                defaultValue = false,
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("liveness/port", defaultValue = 8080),
            AuroraConfigFieldHandler("liveness/path"),
            AuroraConfigFieldHandler("liveness/delay", defaultValue = 10),
            AuroraConfigFieldHandler("liveness/timeout", defaultValue = 1)
        )

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        return if (adc.deployState == DeploymentState.deployment) {
            setOf(
                generateResource(createDeployment(adc, createContainers(adc))),
                generateResource(createService(adc))
            )
        } else {
            setOf(
                generateResource(createDeploymentConfig(adc, createContainers(adc))),
                generateResource(createService(adc)),
                generateResource(createImageStream(adc, dockerRegistry))
            )
        }
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        val name = adc.artifactId
        val id = DigestUtils.sha1Hex("${adc.groupId}/$name")
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val labels = mapOf("applicationId" to id).normalizeLabels()
                modifyResource(it, "Added application name and id")
                val ad: ApplicationDeployment = it.resource as ApplicationDeployment

                if (adc.deployState == DeploymentState.deployment) {
                    ad.spec.runnableType = "Deployment"
                } else {
                    ad.spec.runnableType = "DeploymentConfig"
                }
                ad.spec.applicationName = name
                ad.spec.applicationId = id
                ad.metadata.labels = ad.metadata.labels?.addIfNotNull(labels) ?: labels
            }
        }
    }

    fun createService(adc: AuroraDeploymentSpec): Service {

        val prometheus = adc.featureEnabled("prometheus") {
            HttpEndpoint(adc["$it/path"], adc.getOrNull("$it/port"))
        }

        val prometheusAnnotations = prometheus?.takeIf { it.path != "" }?.let {
            mapOf(
                "prometheus.io/scheme" to "http",
                "prometheus.io/scrape" to "true",
                "prometheus.io/path" to it.path,
                "prometheus.io/port" to "${it.port}"
            )
        } ?: mapOf("prometheus.io/scrape" to "false")

        return newService {
            metadata {
                name = adc.name
                namespace = adc.namespace
                annotations = prometheusAnnotations
            }

            spec {
                ports = listOf(
                    newServicePort {
                        name = "http"
                        protocol = "TCP"
                        port = PortNumbers.HTTP_PORT
                        targetPort = IntOrString(PortNumbers.INTERNAL_HTTP_PORT)
                    },
                    newServicePort {
                        name = "extra"
                        protocol = "TCP"
                        port = PortNumbers.EXTRA_APPLICATION_PORT
                        targetPort = IntOrString(PortNumbers.EXTRA_APPLICATION_PORT)
                    }
                )

                selector = mapOf("name" to adc.name)
                type = "ClusterIP"
                sessionAffinity = "None"
            }
        }
    }

    fun createImageStream(adc: AuroraDeploymentSpec, dockerRegistry: String) = newImageStream {
        metadata {
            name = adc.name
            namespace = adc.namespace
            labels = mapOf("releasedVersion" to adc.version).normalizeLabels()
        }
        spec {
            dockerImageRepository = "$dockerRegistry/${adc.dockerImagePath}"
            tags = listOf(
                newTagReference {
                    name = "default"
                    from {
                        kind = "DockerImage"
                        name = "$dockerRegistry/${adc.dockerImagePath}:${adc.dockerTag}"
                    }
                    if (!AuroraVersion.isFullAuroraVersion(adc.dockerTag)) {
                        importPolicy {
                            scheduled = true
                        }
                    }
                }
            )
        }
    }

    fun createContainer(
        adc: AuroraDeploymentSpec,
        containerName: String,
        containerPorts: Map<String, Int>,
        containerArgs: List<String> = emptyList()
    ): Container {

        val dockerImage = "$dockerRegistry/${adc.dockerImagePath}:${adc.dockerTag}"

        return newContainer {

            terminationMessagePath = "/dev/termination-log"
            imagePullPolicy = "IfNotPresent"
            securityContext {
                privileged = false
            }
            volumeMounts = listOf(newVolumeMount {
                name = "application-log-volume"
                mountPath = Paths.logPath
            })

            val standardEnv = listOf(
                newEnvVar {
                    name = "POD_NAME"
                    valueFrom {
                        fieldRef {
                            apiVersion = "v1"
                            fieldPath = "metadata.name"
                        }
                    }
                },
                newEnvVar {
                    name = "POD_NAMESPACE"
                    valueFrom {
                        fieldRef {
                            apiVersion = "v1"
                            fieldPath = "metadata.namespace"
                        }
                    }
                }
            )
            if (adc.deployState == DeploymentState.deployment) {
                image = dockerImage
            }
            name = containerName
            ports = containerPorts.map {
                newContainerPort {
                    name = it.key
                    containerPort = it.value
                    protocol = "TCP"
                }
            }

            args = containerArgs

            val portEnv = containerPorts.map {
                val portName = if (it.key == "http") "HTTP_PORT" else "${it.key}_HTTP_PORT".toUpperCase()
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }
            env = standardEnv + portEnv

            adc.probe("liveness")?.let {
                livenessProbe = it
            }

            adc.probe("readiness")?.let { readinessProbe = it }
        }
    }

    fun createDeploymentConfig(
        adc: AuroraDeploymentSpec,
        container: List<Container>
    ): DeploymentConfig {

        return newDeploymentConfig {

            metadata {
                name = adc.name
                namespace = adc.namespace
            }
            spec {
                strategy {
                    val deployType: String = adc["deployStrategy/type"]
                    if (deployType == "rolling") {
                        type = "Rolling"
                        rollingParams {
                            intervalSeconds = 1
                            maxSurge = IntOrString("25%")
                            maxUnavailable = IntOrString(0)
                            timeoutSeconds = adc["deployStrategy/timeout"]
                            updatePeriodSeconds = 1L
                        }
                    } else {
                        type = "Recreate"
                        recreateParams {
                            timeoutSeconds = adc["deployStrategy/timeout"]
                        }
                    }
                }
                triggers = listOf(
                    newDeploymentTriggerPolicy {
                        type = "ImageChange"
                        imageChangeParams {
                            automatic = true
                            containerNames = container.map { it.name }
                            from {
                                name = "${adc.name}:default"
                                kind = "ImageStreamTag"
                            }
                        }
                    }

                )
                replicas = adc["replicas"]
                selector = mapOf("name" to adc.name)
                template {
                    spec {
                        volumes = volumes + newVolume {
                            name = "application-log-volume"
                            emptyDir()
                        }
                        containers = container
                        restartPolicy = "Always"
                        dnsPolicy = "ClusterFirst"
                        adc.getOrNull<String>("serviceAccount")?.let {
                            serviceAccount = it
                        }
                    }
                }
            }
        }
    }

    fun createDeployment(
        adc: AuroraDeploymentSpec,
        container: List<Container>
    ): Deployment {

        // https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
        return newDeployment {
            metadata {
                name = adc.name
                namespace = adc.namespace
            }
            spec {

                progressDeadlineSeconds = adc["deployStrategy/timeout"]
                strategy {
                    val deployType: String = adc["deployStrategy/type"]
                    if (deployType == "rolling") {
                        type = "RollingUpdate"
                        rollingUpdate {
                            maxSurge = IntOrString("25%")
                            maxUnavailable = IntOrString(0)
                        }
                    } else {
                        type = "Recreate"
                    }
                }
                replicas = adc["replicas"]
                selector = newLabelSelector {
                    matchLabels = mapOf("name" to adc.name)
                }
                template {
                    spec {
                        volumes = volumes + newVolume {
                            name = "application-log-volume"
                            emptyDir()
                        }
                        containers = container
                        restartPolicy = "Always"
                        dnsPolicy = "ClusterFirst"
                        adc.getOrNull<String>("serviceAccount")?.let {
                            serviceAccount = it
                        }
                    }
                }
            }
        }
    }
}

data class HttpEndpoint(
    val path: String,
    val port: Int?
)
