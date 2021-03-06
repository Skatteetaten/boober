package no.skatteetaten.aurora.boober

import io.micrometer.core.instrument.Tag
import org.springframework.boot.actuate.metrics.web.client.RestTemplateExchangeTags
import org.springframework.boot.actuate.metrics.web.client.RestTemplateExchangeTagsProvider
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component

@Component
class AuroraRestTemplateTagsProvider : RestTemplateExchangeTagsProvider {

    override fun getTags(
        urlTemplate: String?,
        request: HttpRequest,
        response: ClientHttpResponse?
    ): Iterable<Tag> {
        return listOf(
            RestTemplateExchangeTags.method(request),
            RestTemplateExchangeTags.status(response),
            RestTemplateExchangeTags.clientName(request)
        )
    }
}
