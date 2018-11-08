package no.skatteetaten.aurora.boober.model.openshift

import io.fabric8.openshift.api.model.ImageStreamImport

fun ImageStreamImport.findErrorMessage(tagName: String): String? {
    val errorStatuses = listOf("false", "failure")
    return this.status?.import?.status?.tags
        ?.firstOrNull { it.tag == tagName }
        ?.conditions
        ?.firstOrNull { errorStatuses.contains(it.status.toLowerCase()) }
        ?.message
}

fun ImageStreamImport.isDifferentImage(imageHash: String?): Boolean =
    this.status?.import?.status?.tags?.firstOrNull()?.items?.firstOrNull()?.image
        ?.let { return it != imageHash } ?: true
