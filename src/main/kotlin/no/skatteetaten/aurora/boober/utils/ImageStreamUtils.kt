package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

fun ImageStream.findImageName(): String? = this.spec?.tags?.get(0)?.from?.name

fun ImageStream.findTagName(): String? = this.spec?.tags?.get(0)?.name

fun ImageStream.isSameImage(imageStream: ImageStream): Boolean =
        this.findCurrentImageHash() == imageStream.findCurrentImageHash()

fun ImageStream.findCurrentImageHash(): String? {
    return this.status?.tags?.firstOrNull()?.items?.firstOrNull()?.image
}

fun ImageStream.findErrorMessage(): String? {
    this.status?.tags?.firstOrNull()?.conditions?.let {
        if (it.size > 0 && it[0].status.toLowerCase() == "false") {
            return it[0].message
        }
    }
    return null
}

fun ImageStream.from(openShiftResponse: OpenShiftResponse): ImageStream =
        jacksonObjectMapper().readValue(openShiftResponse.responseBody.toString())
