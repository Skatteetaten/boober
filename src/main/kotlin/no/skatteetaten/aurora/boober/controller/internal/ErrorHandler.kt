package no.skatteetaten.aurora.boober.controller.internal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.NoSuchResourceException
import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraVersioningException
import no.skatteetaten.aurora.boober.model.ErrorDetail
import no.skatteetaten.aurora.boober.model.PreconditionFailureException
import no.skatteetaten.aurora.boober.service.AuroraConfigServiceException
import no.skatteetaten.aurora.boober.service.DeployLogServiceException
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.ServiceException
import no.skatteetaten.aurora.boober.service.UnauthorizedAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.HttpStatus.PRECONDITION_FAILED
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@ControllerAdvice
class ErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ServiceException::class)
    fun handleValidationErrors(ex: ServiceException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(AuroraConfigServiceException::class)
    fun handleAuroraConfigServiceErrors(ex: ServiceException, req: WebRequest) =
        handleException(ex, req, INTERNAL_SERVER_ERROR)

    @ExceptionHandler(DeployLogServiceException::class)
    fun handleDeployLogServiceErrors(ex: ServiceException, req: WebRequest) =
        handleException(ex, req, INTERNAL_SERVER_ERROR)

    @ExceptionHandler(AuroraConfigException::class)
    fun handleValidationErrors(ex: AuroraConfigException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(IllegalStateException::class)
    fun handleStateRequest(ex: IllegalStateException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(IllegalAccessException::class)
    fun handleAccessRequest(ex: IllegalAccessException, req: WebRequest) = handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(MultiApplicationValidationException::class)
    fun handleAccessRequest(ex: MultiApplicationValidationException, req: WebRequest) =
        handleException(ex, req, BAD_REQUEST)

    @ExceptionHandler(UnauthorizedAccessException::class)
    fun handleAccessRequest(ex: UnauthorizedAccessException, req: WebRequest) = handleException(ex, req, FORBIDDEN)

    @ExceptionHandler(PreconditionFailureException::class)
    fun handleAccessRequest(ex: PreconditionFailureException, req: WebRequest) =
        handleException(ex, req, PRECONDITION_FAILED)

    @ExceptionHandler(NoSuchResourceException::class)
    fun handleAccessRequest(ex: NoSuchResourceException, req: WebRequest) = handleException(ex, req, NOT_FOUND)

    @ExceptionHandler(OpenShiftException::class)
    fun handleOpenShiftErrors(ex: OpenShiftException, req: WebRequest) = handleException(ex, req, INTERNAL_SERVER_ERROR)

    @ExceptionHandler(RuntimeException::class)
    fun handleGenericErrors(ex: RuntimeException, req: WebRequest) = handleException(ex, req, INTERNAL_SERVER_ERROR)

    private fun handleException(e: Exception, request: WebRequest, httpStatus: HttpStatus): ResponseEntity<*> {

        logger.debug("error", e)
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val message = createErrorMessage(e)

        val items = when (e) {
            is AuroraConfigException -> e.errors
            is AuroraVersioningException -> e.errors
            is MultiApplicationValidationException -> e.toValidationErrors()
            is AuroraConfigServiceException -> listOf(ErrorDetail(message = e.cause?.message ?: "Unknown"))
            else -> listOf(ErrorDetail(message = e.message ?: "Unknown"))
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
            try {
                val json: Map<*, *>? = jacksonObjectMapper().readValue(cause.responseBodyAsString, Map::class.java)
                if (json?.get("kind")!! == "Status") json["message"] as String? else null
            } catch (e: Exception) {
                cause.responseBodyAsString
            }
        } else null

        return StringBuilder().apply {
            e.message?.let { append("$it.") }
            openShiftMessage?.let { append(" $it") }
        }.toString()
    }
}