package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.slf4j.Logger
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.backoff.FixedBackOffPolicy
import org.springframework.retry.listener.RetryListenerSupport
import org.springframework.retry.policy.NeverRetryPolicy
import org.springframework.retry.policy.SimpleRetryPolicy
import org.springframework.retry.support.RetryTemplate
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.net.URLDecoder
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

private const val REQUEST_ENTITY = "requestEntity"

open class RetryingRestTemplateWrapper(
    val restTemplate: RestTemplate,
    open val retries: Int = 3,
    val backoff: Long = 500,
    open val baseUrl: String = ""
) {

    private val retryTemplate = retryTemplate(logger)

    fun <U : Any> get(responseType: KClass<U>, url: String, vararg uriVars: Any): ResponseEntity<U> =
        get(HttpHeaders(), responseType, url, *uriVars)

    fun uri(url: String, vararg uriVars: Any) = restTemplate.uriTemplateHandler.expand(url, *uriVars)

    fun <U : Any> get(headers: HttpHeaders, type: KClass<U>, url: String, vararg uriVars: Any): ResponseEntity<U> {
        return exchange(RequestEntity<Any>(headers, HttpMethod.GET, uri(url, *uriVars)), type)
    }

    fun <T, U : Any> post(
        body: T,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        type: KClass<U>,
        url: String
    ): ResponseEntity<U> {
        return exchange(RequestEntity(body, headers, HttpMethod.POST, URI.create(url)), type)
    }

    fun <T, U : Any> put(
        body: T,
        headers: HttpHeaders,
        type: KClass<U>,
        url: String,
        vararg uriVars: Any
    ): ResponseEntity<U> {
        return exchange(RequestEntity(body, headers, HttpMethod.PUT, uri(url, *uriVars)), type)
    }

    fun <T, U : Any> patch(
        body: T,
        responseType: KClass<U>,
        url: String,
        vararg uriVars: Any,
        headers: HttpHeaders = HttpHeaders.EMPTY
    ): ResponseEntity<U> {
        return exchange(RequestEntity(body, headers, HttpMethod.PATCH, uri(url, *uriVars)), responseType)
    }

    fun exchange(requestEntity: RequestEntity<*>, retry: Boolean = true): ResponseEntity<JsonNode> =
        exchange(requestEntity, JsonNode::class, retry)

    fun <U : Any> exchange(requestEntity: RequestEntity<*>, type: KClass<U>, retry: Boolean = true): ResponseEntity<U> {

        // URI og query params med url templates tuller det til med at url blir dobbel encodet, derav decoding
        val url = baseUrl + URLDecoder.decode(requestEntity.url.toString(), Charsets.UTF_8)
        val responseEntity = if (retry) {
            retryTemplate.execute<ResponseEntity<U>, RestClientException> {
                it.setAttribute(REQUEST_ENTITY, requestEntity)
                restTemplate.exchange(
                    url,
                    requestEntity.method!!,
                    requestEntity,
                    type.java,
                    emptyMap<String, String>()
                )
            }
        } else {
            restTemplate.exchange(
                url,
                requestEntity.method!!,
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
            if (retries == 0) {
                setRetryPolicy(NeverRetryPolicy())
            } else {
                setRetryPolicy(SimpleRetryPolicy(retries))
                setBackOffPolicy(
                    FixedBackOffPolicy().apply {
                        backOffPeriod = backoff
                    }
                )
            }
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
        fun getTokenSnippetFromAuthHeader(headers: HttpHeaders) =
            headers.get(HttpHeaders.AUTHORIZATION)
                ?.firstOrNull()
                ?.split(" ")
                ?.get(1)?.let {
                    it.takeLast(Integer.min(it.length, 5))
                }
    }
}
