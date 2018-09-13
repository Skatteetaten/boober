package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.utils.logger
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
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
import kotlin.reflect.KClass

@Component
class OpenShiftRequestHandler(restTemplate: RestTemplate) : DefaultRetryingRequestHandler(restTemplate)

@Component
class BitbucketRequestHandler(@Qualifier("bitbucket") restTemplate: RestTemplate) :
    DefaultRetryingRequestHandler(restTemplate)

private const val REQUEST_ENTITY = "requestEntity"

open class DefaultRetryingRequestHandler(val restTemplate: RestTemplate) {

    private val logger by logger()

    private val retryTemplate = retryTemplate(logger)

    fun <T, U : Any> put(
        body: T,
        headers: HttpHeaders,
        responseType: KClass<U>,
        url: String,
        vararg uriVariables: Any
    ): ResponseEntity<U> {
        val uri = restTemplate.uriTemplateHandler.expand(url, *uriVariables)
        return exchange(RequestEntity(body, headers, HttpMethod.PUT, uri), responseType)
    }

    fun <U : Any> get(responseType: KClass<U>, url: String, vararg uriVariables: Any): ResponseEntity<U> =
        get(HttpHeaders(), responseType, url, *uriVariables)

    fun <U : Any> get(
        headers: HttpHeaders,
        responseType: KClass<U>,
        url: String,
        vararg uriVariables: Any
    ): ResponseEntity<U> {
        val uri = restTemplate.uriTemplateHandler.expand(url, *uriVariables)
        return exchange(RequestEntity<Any>(headers, HttpMethod.GET, uri), responseType)
    }

    fun exchange(requestEntity: RequestEntity<*>, retry: Boolean = true): ResponseEntity<JsonNode> =
        exchange(requestEntity, JsonNode::class, retry)

    fun <U : Any> exchange(requestEntity: RequestEntity<*>, t: KClass<U>, retry: Boolean = true): ResponseEntity<U> {

        val responseEntity = if (retry) {
            retryTemplate.execute<ResponseEntity<U>, RestClientException> {
                it.setAttribute(REQUEST_ENTITY, requestEntity)
                tryExchange(requestEntity, t)
            }
        } else {
            tryExchange(requestEntity, t)
        }
        logger.trace("Body={}", responseEntity.body)
        return responseEntity
    }

    private fun <U : Any> tryExchange(requestEntity: RequestEntity<*>, t: KClass<U>): ResponseEntity<U> {
        return try {
            restTemplate.exchange(requestEntity, t.java)
        } catch (e: Exception) {
            throw OpenShiftException("An error occurred while communicating with OpenShift", e)
        }
    }

    private fun retryTemplate(logger: Logger): RetryTemplate {

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
    override fun <T : Any?, E : Throwable?> close(
        context: RetryContext?,
        callback: RetryCallback<T, E>?,
        throwable: Throwable?
    ) {

        val requestEntity: RequestEntity<*> = context?.getAttribute(REQUEST_ENTITY) as RequestEntity<*>
        if (context.getAttribute(RetryContext.EXHAUSTED)?.let { it as Boolean } == true) {
            logger.error("Request failed. Giving up. url=${requestEntity.url}, method=${requestEntity.method}")
        } else {
            val tokenSnippet = getTokenSnippetFromAuthHeader(requestEntity.headers)
            logger.debug("Requested url=${requestEntity.url}, method=${requestEntity.method}, tokenSnippet=$tokenSnippet")
        }
        super.close(context, callback, throwable)
    }

    /**
     * Logs information about the request and the number of attempts when an error occurs.
     */
    override fun <T : Any?, E : Throwable?> onError(
        context: RetryContext?,
        callback: RetryCallback<T, E>?,
        e: Throwable?
    ) {

        val requestEntity: RequestEntity<*> = context?.getAttribute(REQUEST_ENTITY) as RequestEntity<*>
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
                response.get("message")?.textValue()
            } catch (e: Exception) {
                "<N/A>"
            }
            params.put("messageFromResponse", messageFromResponse)
        }

        val message = StringBuilder("Request failed. ")
        params.map { "${it.key}=${it.value}" }.joinTo(message, ", ")
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