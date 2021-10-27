package no.skatteetaten.aurora.boober.model.openshift

import io.fabric8.openshift.api.model.ImageStreamImport
import io.fabric8.openshift.api.model.NamedTagEventList

fun ImageStreamImport.findErrorMessage(): String? {
    val errorStatuses = listOf("false", "failure")
    val tag = this.findImportStatusTag()
    return tag?.conditions
        ?.firstOrNull { errorStatuses.contains(it.status.lowercase()) }
        ?.message
}

fun ImageStreamImport.isDifferentImage(imageHash: String?): Boolean {
    val tag = this.findImportStatusTag()

    val image = tag?.items?.firstOrNull()?.image
    return image?.let { return it != imageHash } ?: true
}

fun ImageStreamImport.findImportStatusTag(): NamedTagEventList? {
    val tagName = this.spec.images.first().to.name
    return this.status
        ?.import
        ?.status
        ?.tags
        ?.firstOrNull { it.tag == tagName }
}
