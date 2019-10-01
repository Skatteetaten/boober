package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

fun clearQuotes(str: String?) = str?.replace("\"", "")

fun getRefNameFromRequest(): String {

    val request = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request

    val queryParam: String? = request.getParameter("reference")

    val header: String? = request.getHeader("Ref-Name")

    val ref = queryParam ?: header

    if (ref.isNullOrBlank()) {
        return "master"
    }

    return ref
}