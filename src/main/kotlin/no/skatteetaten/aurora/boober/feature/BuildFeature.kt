package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.*
import io.fabric8.openshift.api.model.BuildConfig
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import org.springframework.stereotype.Service

@Service
class BuildFeature() : Feature {
    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type == TemplateType.development
    }

    override fun handlers(header: AuroraDeploymentSpec, adr: ApplicationDeploymentRef, files: List<AuroraConfigFile>, auroraConfig: AuroraConfig): Set<AuroraConfigFieldHandler> {
        return gav(files, adr) + setOf(
                AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
                AuroraConfigFieldHandler("builder/version", defaultValue = "1"),
                AuroraConfigFieldHandler("baseImage/name", defaultValue = "wingnut8"),
                AuroraConfigFieldHandler("baseImage/version", defaultValue = "1")
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, auroraConfig: AuroraConfig): Set<AuroraResource> {
        return setOf(
                AuroraResource("${adc.name}-bc", createBuild(adc))
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>): Set<AuroraResource> {
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
        return resources
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
                            kind = "ImageStream"
                            name = "${adc.name}:latest"
                        }
                    }
                }
            }
        }
    }
}