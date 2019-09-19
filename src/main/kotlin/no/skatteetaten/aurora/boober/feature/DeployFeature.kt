package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.*
import com.fkorotkov.openshift.*
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.internal.ContainerGenerator.auroraContainer
import no.skatteetaten.aurora.boober.utils.*

val AuroraDeploymentSpec.envName get(): String = this.getOrNull("env/name") ?: this["envName"]
val AuroraDeploymentSpec.name get(): String = this["name"]
val AuroraDeploymentSpec.affiliation get(): String = this["affiliation"]

val AuroraDeploymentSpec.type get(): TemplateType = this["type"]

val AuroraDeploymentSpec.tag
    get(): String = when (type) {
        TemplateType.development -> "latest"
        else -> "default"
    }

val AuroraDeploymentSpec.namespace
    get(): String {
        return when {
            envName.isBlank() -> affiliation
            envName.startsWith("-") -> "${affiliation}$envName"
            else -> "$affiliation-$envName"
        }
    }
val AuroraDeploymentSpec.releaseTo: String? get() = this.getOrNull<String>("releaseTo")?.takeUnless { it.isEmpty() }
val AuroraDeploymentSpec.groupId:String get() = this["groupId"]

val AuroraDeploymentSpec.dockerGroup get() = groupId.replace(".", "_")

val AuroraDeploymentSpec.dockerImagePath: String get() =  """$dockerGroup/$this["artifactId"]"""

val AuroraDeploymentSpec.dockerTag: String get() = releaseTo ?: this["version"]

fun AuroraDeploymentSpec.quantity(resource: String, classifier: String): Pair<String, Quantity> = resource to QuantityBuilder().withAmount(this["resources/$resource/$classifier"]).build()

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


class DeployFeature : Feature {
    override fun handlers(header: AuroraDeploymentSpec,
                          adr: ApplicationDeploymentRef,
                          files: List<AuroraConfigFile>) = setOf(
            AuroraConfigFieldHandler("artifactId",
                    defaultValue = files.find { it.type == AuroraConfigFileType.BASE }?.name?.removeExtension()
                            ?: adr.application,
                    defaultSource = "fileName",
                    validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters", false) }),

            AuroraConfigFieldHandler(
                    "groupId",
                    validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("version", validator = {
                it.pattern(
                        "^[\\w][\\w.-]{0,127}$",
                        "Version must be a 128 characters or less, alphanumeric and can contain dots and dashes"
                )
            }),
            AuroraConfigFieldHandler("releaseTo"),
            AuroraConfigFieldHandler(
                    "deployStrategy/type",
                    defaultValue = "rolling",
                    validator = { it.oneOf(listOf("recreate", "rolling")) }),
            AuroraConfigFieldHandler("deployStrategy/timeout", defaultValue = 180),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "10m"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi"),
            AuroraConfigFieldHandler("replicas", defaultValue = 1),
            AuroraConfigFieldHandler("serviceAccount"),
            AuroraConfigFieldHandler("prometheus", defaultValue = true, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("prometheus/path", defaultValue = "/prometheus"),
            AuroraConfigFieldHandler("prometheus/port", defaultValue = 8081),
            AuroraConfigFieldHandler("management", defaultValue = true, canBeSimplifiedConfig = true),
            AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
            AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
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
    override fun generate(adc: AuroraDeploymentSpec): Set<AuroraResource> {

        val containerInput: Map<String, Map<String, Int>> =
                mapOf("${adc.name}-java" to mapOf(
                        "http" to PortNumbers.INTERNAL_HTTP_PORT,
                        "management" to PortNumbers.INTERNAL_ADMIN_PORT,
                        "jolokia" to PortNumbers.JOLOKIA_HTTP_PORT)
                )

        val container = containerInput.map {
            createContainer(adc, it.key, it.value)
        }
        return setOf(
                AuroraResource("${adc.name}-dc", create(adc, container)),
                AuroraResource("${adc.name}-service", createService(adc)),
                // TODO: Parameterize docker registry
                AuroraResource("${adc.name}-is", createImageStream(adc, "http://docker.registry"))

        )
    }

    fun createService(adc:AuroraDeploymentSpec) : Service {

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
    fun createImageStream(adc:AuroraDeploymentSpec, dockerRegistry:String) = newImageStream {
        metadata {
            name = adc.name
            namespace = adc.namespace
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
    fun createContainer(adc: AuroraDeploymentSpec, containerName: String, containerPorts: Map<String, Int>): Container {

        return auroraContainer {
            name = containerName
            ports = containerPorts.map {
                newContainerPort {
                    name = it.key
                    containerPort = it.value
                    protocol = "TCP"
                }
            }

            // TODO: args
            //args = adcContainer.args

            val portEnv = containerPorts.map {
                val portName = if (it.key == "http") "HTTP_PORT" else "${it.key}_HTTP_PORT".toUpperCase()
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }
            env = portEnv

            resources {
                requests = mapOf(adc.quantity("cpu", "min"), adc.quantity("memory", "min"))
                limits = mapOf(adc.quantity("cpu", "max"), adc.quantity("memory", "max"))
            }

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
                                    name = "${adc.name}:${adc.tag}"
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