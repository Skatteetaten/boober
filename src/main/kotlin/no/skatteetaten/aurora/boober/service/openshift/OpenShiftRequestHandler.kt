package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.utils.logger
import org.slf4j.Logger
import org.springframework.http.HttpHeaders
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.backoff.FixedBackOffPolicy
import org.springframework.retry.listener.RetryListenerSupport
import org.springframework.retry.policy.SimpleRetryPolicy
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.lang.Integer.min

@Component
class OpenShiftRequestHandler(val restTemplate: RestTemplate) {

    companion object {
        val REQUEST_ENTITY = "requestEntity"
    }

    final val logger by logger()

    val retryTemplate = retryTemplate(logger)

    fun <T> exchange(requestEntity: RequestEntity<T>, retry: Boolean = true): ResponseEntity<JsonNode> {

        val responseEntity = if (retry) {
            retryTemplate.execute<ResponseEntity<JsonNode>, RestClientException> {
                it.setAttribute(REQUEST_ENTITY, requestEntity)
                tryExchange(requestEntity)
            }
        } else {
            tryExchange(requestEntity)
        }
        logger.trace("Body={}", responseEntity.body)
        return responseEntity
    }

    private fun tryExchange(requestEntity: RequestEntity<*>): ResponseEntity<JsonNode> {
        return try {
            restTemplate.exchange<JsonNode>(requestEntity, JsonNode::class.java)
        } catch (e: Exception) {
            throw OpenShiftException("An error occurred while communicating with OpenShift", e)
        }
    }

    private final fun retryTemplate(logger: Logger): RetryTemplate {

        val template = RetryTemplate().apply {
            setRetryPolicy(SimpleRetryPolicy(3))
            setBackOffPolicy(FixedBackOffPolicy().apply {
                backOffPeriod = 500
            })
        }

        template.registerListener(RetryLogger(logger))
        return template
    }
}

class RetryLogger(val logger: Logger) : RetryListenerSupport() {

    /**
     * Logs whether the request was successful or not
     */
    override fun <T : Any?, E : Throwable?> close(context: RetryContext?, callback: RetryCallback<T, E>?, throwable: Throwable?) {

        val requestEntity: RequestEntity<*> = context?.getAttribute(OpenShiftRequestHandler.REQUEST_ENTITY) as RequestEntity<*>
        if (context.getAttribute(RetryContext.EXHAUSTED)?.let { it as Boolean } == true) {
            logger.error("Request failed. Giving up. url=${requestEntity.url}, method=${requestEntity.method}")
        } else {
            val tokenSnippet = getTokenSnippetFromAuthHeader(requestEntity.headers)
            logger.debug("Requested url=${requestEntity.url}, method=${requestEntity.method}, tokenSnippet=${tokenSnippet}")
        }
        super.close(context, callback, throwable)
    }

    /**
     * Logs information about the request and the number of attempts when an error occurs.
     */
    override fun <T : Any?, E : Throwable?> onError(context: RetryContext?, callback: RetryCallback<T, E>?, e: Throwable?) {

        val requestEntity: RequestEntity<*> = context?.getAttribute(OpenShiftRequestHandler.REQUEST_ENTITY) as RequestEntity<*>
        val cause = e?.cause
        val params = mutableMapOf(
            "attempt" to context.retryCount,
            "url" to requestEntity.url,
            "method" to requestEntity.method,
            "message" to cause?.message
        )
        if (cause is RestClientResponseException) {
            params.put("code", cause.rawStatusCode)
            params.put("statusText", cause.statusText)
            val messageFromResponse = try {
                val response = jacksonObjectMapper().readValue<JsonNode>(cause.responseBodyAsString)
                response.get("message")
                    ?.textValue()
            } catch (e: Exception) {
                "<N/A>"
            }
            params.put("messageFromResponse", messageFromResponse)
        }

        val message = StringBuilder("Request failed. ")
        params.map { "${it.key}=${it.value}" }
            .joinTo(message, ", ")
        logger.warn(message.toString())
    }

    companion object {
        @JvmStatic
        fun getTokenSnippetFromAuthHeader(headers: HttpHeaders) =
            headers.get(HttpHeaders.AUTHORIZATION)
                ?.firstOrNull()
                ?.split(" ")
                ?.get(1)?.let { it.substring(0, min(it.length, 5)) }
    }
}