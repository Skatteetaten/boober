package no.skatteetaten.aurora.boober.service.internal

import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.openshift.api.model.ImageStreamTag
import io.fabric8.openshift.api.model.TagReference

class ImageStreamTagGenerator {

    fun create(imageStreamName: String, tagName: String): ImageStreamTag {
        val tagReference = TagReference().apply {
            from = ObjectReference().apply { name = imageStreamName }
        }
        return ImageStreamTag().apply {
            metadata = ObjectMeta().apply { name = tagName }
            tag = tagReference
        }
    }
}