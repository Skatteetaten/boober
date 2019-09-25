package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.*
import com.fkorotkov.openshift.*
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.*
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.*
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.beans.factory.annotation.Value

val AuroraDeploymentSpec.envName get(): String = this.getOrNull("env/name") ?: this["envName"]
val AuroraDeploymentSpec.name get(): String = this["name"]
val AuroraDeploymentSpec.affiliation get(): String = this["affiliation"]
val AuroraDeploymentSpec.type get(): TemplateType = this["type"]

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

val AuroraDeploymentSpec.version: String get() = this["version"]
val AuroraDeploymentSpec.dockerTag: String get() = releaseTo ?: version


//transform to resource right away?
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
        AuroraConfigFieldHandler("version", validator = {
            it.pattern(
                    "^[\\w][\\w.-]{0,127}$",
                    "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes",
                    this.type.versionRequired
            )
        })

fun gavHandlers(spec: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand) =
        setOf(
                AuroraConfigFieldHandler("artifactId",
                        defaultValue = cmd.applicationFiles.find { it.type == AuroraConfigFileType.BASE }?.name?.removeExtension()
                                ?: cmd.adr.application,
                        defaultSource = "fileName",
                        validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),

                AuroraConfigFieldHandler(
                        "groupId",
                        validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
                spec.versionHandler
        )

val AuroraDeploymentSpec.managementPath
    get() = this.featureEnabled("management") {
        val path = this.get<String>("$it/path").ensureStartWith("/")
        val port = this.get<Int>("$it/port").toString().ensureStartWith(":")
        "$port$path"
    }

abstract class AbstractDeployFeature(
        @Value("\${integrations.docker.registry}") val dockerRegistry: String) : Feature {

    abstract fun createContainers(adc: AuroraDeploymentSpec): List<Container>

    abstract fun enable(platform: ApplicationPlatform): Boolean

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type in listOf(TemplateType.deploy, TemplateType.development) && enable(header.applicationPlatform)
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> = gavHandlers(header, cmd) + setOf(
            AuroraConfigFieldHandler("releaseTo"),
            AuroraConfigFieldHandler(
                    "deployStrategy/type",
                    defaultValue = "rolling",
                    validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180),
            AuroraConfigFieldHandler("replicas", defaultValue = 1),
            AuroraConfigFieldHandler("serviceAccount"),

            AuroraConfigFieldHandler("prometheus", defaultValue = true, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),

            AuroraConfigFieldHandler("readiness", defaultValue = true, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("readiness/port", defaultValue = 8080),
            AuroraConfigFieldHandler("readiness/path"),
            AuroraConfigFieldHandler("readiness/delay", defaultValue = 10),
            AuroraConfigFieldHandler("readiness/timeout", defaultValue = 1),
            AuroraConfigFieldHandler("liveness", defaultValue = false, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("liveness/port", defaultValue = 8080),
            AuroraConfigFieldHandler("liveness/path"),
            AuroraConfigFieldHandler("liveness/delay", defaultValue = 10),
            AuroraConfigFieldHandler("liveness/timeout", defaultValue = 1)
    )


    //this is java
    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraResource> {

        val container = createContainers(adc)
        return setOf(
                AuroraResource("${adc.name}-dc", create(adc, container)),
                AuroraResource("${adc.name}-service", createService(adc)),
                AuroraResource("${adc.name}-is", createImageStream(adc, dockerRegistry))


        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {
        val name = "${adc.artifactId}"
        val id = DigestUtils.sha1Hex("${adc.groupId}/${adc.artifactId}")
        resources.forEach {
            if (it.resource.kind == "ApplicationDeployment") {
                val ad: ApplicationDeployment = jacksonObjectMapper().convertValue(it.resource)
                ad.spec.applicationName = name
                ad.spec.applicationId = id
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
                            nodePort = 0
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
            labels = mapOf("releasedVersion" to adc.version)
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

    fun createContainer(adc: AuroraDeploymentSpec, containerName: String, containerPorts: Map<String, Int>, containerArgs: List<String> = emptyList()): Container {

        return auroraContainer {
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
            env = portEnv



            adc.probe("liveness")?.let {
                livenessProbe = it
            }

            adc.probe("readiness")?.let { readinessProbe = it }
        }
    }


    fun create(
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
}

data class HttpEndpoint(
        val path: String,
        val port: Int?
)

fun auroraContainer(block: Container.() -> Unit = {}): Container {
    val instance = Container()
    instance.block()
    instance.terminationMessagePath = "/dev/termination-log"
    instance.imagePullPolicy = "IfNotPresent"
    instance.securityContext {
        privileged = false
    }
    instance.volumeMounts = listOf(newVolumeMount {
        name = "application-log-volume"
        mountPath = "/u01/logs"
    }) + instance.volumeMounts

    instance.env = listOf(
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
    ) + instance.env
    return instance
}