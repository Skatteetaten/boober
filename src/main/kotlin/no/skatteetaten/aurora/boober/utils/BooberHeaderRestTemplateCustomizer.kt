package no.skatteetaten.aurora.boober.utils

import no.skatteetaten.aurora.mvc.AuroraHeaderRestTemplateCustomizer
import no.skatteetaten.aurora.mvc.AuroraRequestParser
import org.slf4j.MDC
import org.springframework.http.HttpRequest
import java.util.UUID

class BooberHeaderRestTemplateCustomizer : AuroraHeaderRestTemplateCustomizer("boober") {

    override fun addCorrelationId(request: HttpRequest) {
        val contextMap: Map<String, String> = MDC.getCopyOfContextMap()
        val korrelasjonsid = if (contextMap.containsKey(AuroraRequestParser.KORRELASJONSID_FIELD)) {
            contextMap[AuroraRequestParser.KORRELASJONSID_FIELD]!!
        } else {
            UUID.randomUUID().toString()
        }
        request.headers.addIfAbsent(AuroraRequestParser.KORRELASJONSID_FIELD, korrelasjonsid)
    }
}
