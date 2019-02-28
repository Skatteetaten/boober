package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.importPolicy
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newImageImportSpec
import com.fkorotkov.openshift.newImageStreamImport
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.to
import io.fabric8.openshift.api.model.ImageStreamImport

object ImageStreamImportGenerator {

    fun create(dockerImageUrl: String, imageStreamName: String, isiNamespace: String): ImageStreamImport {
        return newImageStreamImport {
            metadata {
                name = imageStreamName
                namespace = isiNamespace
            }
            spec {
                import = true
                images = listOf(newImageImportSpec {
                    from {
                        kind = "DockerImage"
                        name = dockerImageUrl
                    }

                    to {
                        name = "default"
                    }

                    importPolicy {
                        scheduled = true
                    }
                })
            }
        }
    }
}