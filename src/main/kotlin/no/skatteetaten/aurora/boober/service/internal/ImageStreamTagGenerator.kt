package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.image
import com.fkorotkov.openshift.imageStreamTag
import com.fkorotkov.openshift.importPolicy
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.tag
import io.fabric8.openshift.api.model.ImageStreamTag

object ImageStreamTagGenerator {

    fun create(dockerImageUrl: String, tagName: String, isName: String): ImageStreamTag {
        return imageStreamTag {
            metadata {
                apiVersion = "v1"
                name = "$isName:$tagName"
            }
            tag {
                name = tagName
                from {
                    kind = "DockerImage"
                    name = dockerImageUrl
                }
                importPolicy {
                    scheduled = true
                }
            }
            image {}
        }
    }
}