package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.Logger
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
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import kotlin.reflect.KClass

private const val REQUEST_ENTITY = "requestEntity"

open class RetryingRestTemplateWrapper(val restTemplate: RestTemplate) {

    private val logger by logger()

    private val retryTemplate = retryTemplate(logger)

    fun <U : Any> get(responseType: KClass<U>, url: String, vararg uriVars: Any): ResponseEntity<U> =
        get(HttpHeaders(), responseType, url, *uriVars)

    fun <U : Any> get(headers: HttpHeaders, type: KClass<U>, url: String, vararg uriVars: Any): ResponseEntity<U> {
        val uri = restTemplate.uriTemplateHandler.expand(url, *uriVars)
        return exchange(RequestEntity<Any>(headers, HttpMethod.GET, uri), type)
    }

    fun <T, U : Any> put(
        body: T,
        headers: HttpHeaders,
        type: KClass<U>,
        url: String,
        vararg uriVars: Any
    ): ResponseEntity<U> {
        val uri = restTemplate.uriTemplateHandler.expand(url, *uriVars)
        return exchange(RequestEntity(body, headers, HttpMethod.PUT, uri), type)
    }

    fun exchange(requestEntity: RequestEntity<*>, retry: Boolean = true): ResponseEntity<JsonNode> =
        exchange(requestEntity, JsonNode::class, retry)

    fun <U : Any> exchange(requestEntity: RequestEntity<*>, type: KClass<U>, retry: Boolean = true): ResponseEntity<U> {

        val responseEntity = if (retry) {
            retryTemplate.execute<ResponseEntity<U>, RestClientException> {
                it.setAttribute(REQUEST_ENTITY, requestEntity)
                restTemplate.exchange(
                    requestEntity.url.toString(),
                    requestEntity.method,
                    requestEntity,
                    type.java,
                    emptyMap<String, String>()
                )
            }
        } else {
            restTemplate.exchange(
                requestEntity.url.toString(),
                requestEntity.method,
                requestEntity,
                type.java,
                emptyMap<String, String>()
            )
        }
        logger.trace("Body={}", responseEntity.body)
        return responseEntity
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
            logger.warn("Request status=failed. Giving up. url=${requestEntity.url}, method=${requestEntity.method}")
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
        if (e is HttpServerErrorException) {
            params["body"] = e.responseBodyAsString
        }
        if (cause is RestClientResponseException) {
            params["code"] = cause.rawStatusCode
            params["statusText"] = cause.statusText
            val messageFromResponse = try {
                val response = jacksonObjectMapper().readValue<JsonNode>(cause.responseBodyAsString)
                response.get("message")?.textValue()
            } catch (e: Exception) {
                "<N/A>"
            }
            params["messageFromResponse"] = messageFromResponse
        }

        val message = StringBuilder("Request status=retrying ")
        params.map { "${it.key}=${it.value}" }.joinTo(message, ", ")
        logger.warn(message.toString())
    }

    companion object {
        @JvmStatic
        fun getTokenSnippetFromAuthHeader(headers: HttpHeaders) =
            headers.get(HttpHeaders.AUTHORIZATION)
                ?.firstOrNull()
                ?.split(" ")
                ?.get(1)?.let { it.substring(0, Integer.min(it.length, 5)) }
    }
}