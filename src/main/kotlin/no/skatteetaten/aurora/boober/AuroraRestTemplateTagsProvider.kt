package no.skatteetaten.aurora.boober

import io.micrometer.core.instrument.Tag
import io.micrometer.spring.web.client.RestTemplateExchangeTags
import io.micrometer.spring.web.client.RestTemplateExchangeTagsProvider
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component
import java.util.Arrays

@Component
class AuroraRestTemplateTagsProvider : RestTemplateExchangeTagsProvider {

    override fun getTags(
        urlTemplate: String?,
        request: HttpRequest,
        response: ClientHttpResponse?
    ): Iterable<Tag> {
        return Arrays.asList(RestTemplateExchangeTags.method(request),
            RestTemplateExchangeTags.status(response),
            RestTemplateExchangeTags.clientName(request))
    }
}