package no.skatteetaten.aurora.boober.utils

import io.fabric8.openshift.api.model.Route

fun Route.findErrorMessage(): String? {
    val ingress = this.status?.ingress
    if (ingress.isNullOrEmpty()) {
        return null
    }

    val result = ingress.flatMap { it.conditions }
        .filter { it.status == "False" }
        .map { it.message }

    if (result.isEmpty()) {
        return null
    }
    return result.joinToString(",")
}
