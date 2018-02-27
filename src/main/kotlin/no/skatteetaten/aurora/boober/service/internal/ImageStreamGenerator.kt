package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageStream
import com.fkorotkov.openshift.importPolicy
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.tagReference
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.AuroraVersion

object ImageStreamGenerator {

    fun createLocalImageStream(isName: String, isLabels: Map<String, String>): ImageStream {
        return imageStream {
            apiVersion = "v1"
            metadata {
                ownerReferences = null
                finalizers = null
                name = isName
                labels = isLabels
            }
        }
    }

    fun createRemoteImageStream(isName: String,
                                isLabels: Map<String, String>,
                                dockerRegistry: String,
                                dockerImagePath: String,
                                dockerTag: String): ImageStream {
        return imageStream {
            apiVersion = "v1"
            metadata {
                name = isName
                ownerReferences = null
                finalizers = null
                labels = isLabels
            }
            spec {
                dockerImageRepository = "$dockerRegistry/$dockerImagePath"
                tags = listOf(
                        tagReference {
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