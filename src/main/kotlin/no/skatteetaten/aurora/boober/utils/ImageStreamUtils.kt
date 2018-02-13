package no.skatteetaten.aurora.boober.utils

import io.fabric8.openshift.api.model.ImageStream

data class VerificationResult(val success: Boolean = true, val message: String? = null)

fun ImageStream.verifyResponse(): VerificationResult {
    this.status?.let {
        @Suppress("UNCHECKED_CAST")
        val images = it.additionalProperties["images"] as? ArrayList<LinkedHashMap<String, LinkedHashMap<String, String>>>

        val statusValues = images?.get(0)?.get("status")
        val status = statusValues?.get("status")
        val message = statusValues?.get("message")

        if (status?.toLowerCase() == "failure") {
            return VerificationResult(false, message)
        }
    }

    return VerificationResult()
}

fun ImageStream.findImageName(): String? {
    return this.spec?.tags?.get(0)?.from?.name
}
