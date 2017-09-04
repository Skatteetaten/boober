package no.skatteetaten.aurora.boober.controller.internal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.internal.OpenShiftException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@ControllerAdvice
class ErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(AuroraConfigException::class)
    fun handleValidationErrors(ex: AuroraConfigException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(ApplicationConfigException::class)
    fun handleValidationErrors(ex: ApplicationConfigException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(OpenShiftException::class)
    fun handleOpenShiftErrors(ex: OpenShiftException, req: WebRequest) = handleException(ex, req, INTERNAL_SERVER_ERROR)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(RuntimeException::class)
    fun handleGenericErrors(ex: RuntimeException, req: WebRequest) = handleException(ex, req, INTERNAL_SERVER_ERROR)


    private fun handleException(e: Exception, request: WebRequest, httpStatus: HttpStatus): ResponseEntity<*> {

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val message = createErrorMessage(e)

        val items = when (e) {
            is AuroraConfigException -> e.errors
            is ApplicationConfigException -> e.errors
            is AuroraVersioningException -> e.errors
            else -> listOf()
        }

        if (httpStatus.is5xxServerError) {

            logger.error("Unexpected error while handling request", e)
        }

        val response = Response(false, message, items)

        return handleExceptionInternal(e, response, headers, httpStatus, request)
    }

    private fun createErrorMessage(e: Exception): String {

        val cause = e.cause
        val openShiftMessage = if (cause is org.springframework.web.client.HttpClientErrorException) {
            val json: Map<*, *>? = jacksonObjectMapper().readValue(cause.responseBodyAsString, Map::class.java)
            if (json?.get("kind")!! == "Status") json["message"] as String? else null
        } else null

        return StringBuilder().apply {
            e.message?.let { append(it + ".") }
            openShiftMessage?.let { append(" " + it) }
        }.toString()
    }
}