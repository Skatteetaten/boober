package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.customStrategy
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newBuildConfig
import com.fkorotkov.openshift.output
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.BuildConfig
import no.skatteetaten.aurora.boober.model.AuroraBuild

object BuildConfigGenerator {

    fun generate(
        source: AuroraBuild,
        buildName: String,
        buildLabels: Map<String, String>,
        ownerReference: OwnerReference,
        buildNamespace: String
    ): BuildConfig {
        return newBuildConfig {
            metadata {
                ownerReferences = listOf(ownerReference)
                name = buildName
                namespace = buildNamespace
                labels = buildLabels
            }

            spec {
                strategy {
                    type = "Custom"
                    customStrategy {
                        from {
                            kind = "ImageStreamTag"
                            namespace = "openshift"
                            name = "${source.builderName}:${source.builderVersion}"
                        }

                        val envMap = mapOf(
                            "ARTIFACT_ID" to source.artifactId,
                            "GROUP_ID" to source.groupId,
                            "VERSION" to source.version,
                            "DOCKER_BASE_VERSION" to source.baseVersion,
                            "DOCKER_BASE_IMAGE" to "aurora/${source.baseName}",
                            "PUSH_EXTRA_TAGS" to "latest,major,minor,patch"

                        )

                        env = envMap.map {
                            newEnvVar {
                                name = it.key
                                value = it.value
                            }
                        }

                        if (source.applicationPlatform == "web") {
                            env = env + newEnvVar {
                                name = "APPLICATION_TYPE"
                                value = "nodejs"
                            }
                        }

                        exposeDockerSocket = true
                    }
                    output {
                        imageLabels = null
                        to {
                            kind = source.outputKind
                            name = source.outputName
                        }
                    }
                }
            }
        }
    }
}