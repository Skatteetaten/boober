package no.skatteetaten.aurora.boober.service.internal

import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageStreamTag
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.tag
import io.fabric8.openshift.api.model.ImageStreamTag

class ImageStreamTagGenerator {

    fun create(imageStreamName: String, tagName: String): ImageStreamTag {
        return imageStreamTag {
            metadata {
                name = tagName
            }
            tag {
                from {
                    name = imageStreamName
                }
            }
        }
    }
}