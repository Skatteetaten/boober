package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.*
import io.fabric8.openshift.api.model.BuildConfig
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.mapper.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import org.springframework.stereotype.Service

data class AuroraBuild(
        val baseName: String,
        val baseVersion: String,
        val builderName: String,
        val builderVersion: String,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val outputKind: String,
        val outputName: String,
        val applicationPlatform: String
)

@Service
class BuildFeature() : Feature {
    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type == TemplateType.development
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {

        val applicationPlatform: ApplicationPlatform = header.applicationPlatform
        return gavHandlers(header, cmd) + setOf(
                AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
                AuroraConfigFieldHandler("builder/version", defaultValue = "1"),
                AuroraConfigFieldHandler("baseImage/name", defaultValue = applicationPlatform.baseImageName),
                AuroraConfigFieldHandler("baseImage/version", defaultValue = applicationPlatform.baseImageVersion)
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraResource> {
        return setOf(
                AuroraResource("${adc.name}-bc", createBuild(adc))
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {
        resources.forEach {
            if (it.resource.kind == "ImageStream") {
                val imageStream: ImageStream = jacksonObjectMapper().convertValue(it.resource)
                imageStream.spec = null
            }

            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                dc.spec.triggers.forEach { dtp ->
                    if (dtp.type == "ImageChange") {
                        dtp.imageChangeParams.from.name = "${adc.name}:latest"
                    }
                }
            }

        }
    }

    fun createBuild(adc: AuroraDeploymentSpec): BuildConfig {
        return newBuildConfig {
            metadata {
                name = adc.name
                namespace = adc.namespace
            }

            spec {
                strategy {
                    type = "Custom"
                    customStrategy {
                        from {
                            kind = "ImageStreamTag"
                            namespace = "openshift"
                            name = "${adc.get<String>("builder/name")}:${adc.get<String>("builder/version")}"
                        }

                        val envMap = mapOf(
                                "ARTIFACT_ID" to adc.artifactId,
                                "GROUP_ID" to adc.groupId,
                                "VERSION" to adc["version"],
                                "DOCKER_BASE_VERSION" to adc["baseImage/version"],
                                "DOCKER_BASE_IMAGE" to "aurora/${adc.get<String>("baseImage/name")}",
                                "PUSH_EXTRA_TAGS" to "latest,major,minor,patch"
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
                            name = "${adc.name}:latest"
                        }
                    }
                }
            }
        }
    }
}