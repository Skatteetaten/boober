package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.importPolicy
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newImageStream
import com.fkorotkov.openshift.newTagReference
import com.fkorotkov.openshift.spec
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.AuroraVersion

object ImageStreamGenerator {

    fun createLocalImageStream(
        isName: String,
        isLabels: Map<String, String>,
        reference: OwnerReference,
        isNamespace: String
    ): ImageStream {
        return newImageStream {
            metadata {
                ownerReferences = listOf(reference)
                name = isName
                namespace = isNamespace
                labels = isLabels
            }
        }
    }

    fun createRemoteImageStream(
        isName: String,
        isLabels: Map<String, String>,
        dockerRegistry: String,
        dockerImagePath: String,
        dockerTag: String,
        reference: OwnerReference,
        isNamespace: String
    ): ImageStream {
        return newImageStream {
            metadata {
                ownerReferences = listOf(reference)
                name = isName
                namespace = isNamespace
                labels = isLabels
            }
            spec {
                dockerImageRepository = "$dockerRegistry/$dockerImagePath"
                tags = listOf(
                    newTagReference {
                        name = "default"
                        from {
                            kind = "DockerImage"
                            name = "$dockerRegistry/$dockerImagePath:$dockerTag"
                        }
                        if (!AuroraVersion.isFullAuroraVersion(dockerTag)) {
                            importPolicy {
                                scheduled = true
                            }
                        }
                    }
                )
            }
        }
    }
}