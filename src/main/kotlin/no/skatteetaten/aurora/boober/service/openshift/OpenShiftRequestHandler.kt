package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.OpenShiftException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

@Component
class OpenShiftRequestHandler(val restTemplate: RestTemplate) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftRequestHandler::class.java)

    @Retryable(value = OpenShiftException::class, maxAttempts = 3, backoff = Backoff(delay = 500))
    fun <T> exchange(requestEntity: RequestEntity<T>): ResponseEntity<JsonNode> {
        logger.info("${requestEntity.method} resource at ${requestEntity.url}")

        val createResponse: ResponseEntity<JsonNode> = try {
            restTemplate.exchange(requestEntity, JsonNode::class.java)
        } catch (e: HttpClientErrorException) {
            throw OpenShiftException("Request failed url=${requestEntity.url}, method=${requestEntity.method}, message=${e.message}, code=${e.statusCode.value()}", e)
        }
        logger.debug("Body=${createResponse.body}")
        return createResponse
    }
}