package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.service.ValidationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@ControllerAdvice
class ErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ValidationException::class)
    fun handleValidationException(e: ValidationException, request: WebRequest): ResponseEntity<*> {

        return handleException(e, request, HttpStatus.BAD_REQUEST)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException, request: WebRequest): ResponseEntity<*> {

        return handleException(e, request, HttpStatus.BAD_REQUEST)
    }

    private fun handleException(e: Exception, request: WebRequest, httpStatus: HttpStatus): ResponseEntity<*> {

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

        val responseError = mutableMapOf(
                "status" to httpStatus.value(),
                "errorMessage" to e.message,
                "cause" to e.cause?.message
        )
        val error = when (e) {
            is ValidationException -> responseError + ("errors" to e.errors)
            else -> responseError
        }

        return handleExceptionInternal(e, error, headers, httpStatus, request)
    }
}