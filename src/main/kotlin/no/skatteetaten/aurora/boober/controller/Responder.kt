package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import org.springframework.stereotype.Component

@Component
class Responder {
    fun create(items: List<Any>) = Response(items = items)
    fun create(item: Any) = Response(items = listOf(item))
    fun create() = Response()
}