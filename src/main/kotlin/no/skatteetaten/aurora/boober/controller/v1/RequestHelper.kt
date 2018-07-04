package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

fun clearQuotes(str: String?) = str?.replace("\"", "")

fun getRefNameFromRequest(): String {
    val request = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    val header: String? = request.getHeader("Ref-Name")
    return if (header.isNullOrBlank()) "master" else header!!
}