package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate

@Component
class OpenShiftRequestHandler(val restTemplate: RestTemplate) {

    val logger by logger()

    @Retryable(value = OpenShiftException::class, maxAttempts = 3, backoff = Backoff(delay = 500))
    fun <T> exchange(requestEntity: RequestEntity<T>): ResponseEntity<JsonNode> {

        val createResponse: ResponseEntity<JsonNode> = try {
            logger.info("${requestEntity.method} resource at ${requestEntity.url}")
            restTemplate.exchange(requestEntity, JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            val messages = "Request failed will be retried. url=${requestEntity.url}, method=${requestEntity.method}, message=${e.message}, code=${e.rawStatusCode}, statusText=${e.statusText}"
            logger.debug(messages)
            throw OpenShiftException(messages, e)
        } catch (e: RestClientException) {
            val messages = "Request failed will be retried url=${requestEntity.url}, method=${requestEntity.method}, message=${e.message}"
            logger.debug(messages)
            throw OpenShiftException(messages, e)
        }
        logger.trace("Body=${createResponse.body}")
        return createResponse
    }
}