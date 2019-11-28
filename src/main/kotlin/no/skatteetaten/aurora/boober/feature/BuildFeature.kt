package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.customStrategy
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newBuildConfig
import com.fkorotkov.openshift.output
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.to
import io.fabric8.openshift.api.model.BuildConfig
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import org.springframework.stereotype.Service

@Service
class BuildFeature : Feature {
    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return header.type == TemplateType.development
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val applicationPlatform: ApplicationPlatform = header.applicationPlatform
        return gavHandlers(header, cmd) + setOf(
            AuroraConfigFieldHandler("builder/name", defaultValue = "architect"),
            AuroraConfigFieldHandler("builder/version", defaultValue = "1"),
            AuroraConfigFieldHandler(
                "baseImage/name",
                defaultValue = applicationPlatform.baseImageName
            ),
            AuroraConfigFieldHandler(
                "baseImage/version",
                defaultValue = applicationPlatform.baseImageVersion
            )
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        return setOf(generateResource(createBuild(adc)))
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        resources.forEach {
            if (it.resource.kind == "ImageStream") {
                modifyResource(it, "Remove spec from imagestream")
                val imageStream: ImageStream = it.resource as ImageStream
                imageStream.spec = null
            }

            if (it.resource.kind == "DeploymentConfig") {

                modifyResource(it, "Change imageChangeTrigger to follow latest")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
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
