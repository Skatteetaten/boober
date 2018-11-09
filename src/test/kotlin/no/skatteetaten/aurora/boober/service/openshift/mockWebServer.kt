package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

fun MockWebServer.enqueueJson(status: Int = 200, body: Any) {
    val json = body as? String ?: jacksonObjectMapper().writeValueAsString(body)
    val response = MockResponse()
        .setResponseCode(status)
        .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE)
        .setBody(json)
    this.enqueue(response)
}

fun MockWebServer.execute(status: Int, response: Any, fn: () -> Unit): RecordedRequest {
    this.enqueueJson(status, response)
    fn()
    return this.takeRequest()
}

fun MockWebServer.execute(response: MockResponse, fn: () -> Unit): RecordedRequest {
    this.enqueue(response)
    fn()
    return this.takeRequest()
}

fun MockWebServer.execute(response: Any, fn: () -> Unit): RecordedRequest {
    this.enqueueJson(body = response)
    fn()
    return this.takeRequest()
}

fun MockWebServer.execute(vararg responses: Any, fn: () -> Unit): List<RecordedRequest> {
    responses.forEach { this.enqueueJson(body = it) }
    fn()
    return (1..responses.size).toList().map { this.takeRequest() }
}