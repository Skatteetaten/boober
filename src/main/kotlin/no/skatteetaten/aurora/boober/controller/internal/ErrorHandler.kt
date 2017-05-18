package no.skatteetaten.aurora.boober.controller.internal

@org.springframework.web.bind.annotation.ControllerAdvice
class ErrorHandler : org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler() {

    @org.springframework.web.bind.annotation.ExceptionHandler(no.skatteetaten.aurora.boober.service.internal.AuroraConfigException::class)
    fun handleValidationErrors(ex: no.skatteetaten.aurora.boober.service.internal.AuroraConfigException, req: org.springframework.web.context.request.WebRequest) = handleException(ex, req, org.springframework.http.HttpStatus.BAD_REQUEST)

    @org.springframework.web.bind.annotation.ExceptionHandler(no.skatteetaten.aurora.boober.service.internal.OpenShiftException::class)
    fun handleOpenShiftErrors(ex: no.skatteetaten.aurora.boober.service.internal.OpenShiftException, req: org.springframework.web.context.request.WebRequest) = handleException(ex, req, org.springframework.http.HttpStatus.BAD_REQUEST)

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException, req: org.springframework.web.context.request.WebRequest) = handleException(ex, req, org.springframework.http.HttpStatus.BAD_REQUEST)

    @org.springframework.web.bind.annotation.ExceptionHandler(RuntimeException::class)
    fun handleGenericErrors(ex: RuntimeException, req: org.springframework.web.context.request.WebRequest) = handleException(ex, req, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)


    private fun handleException(e: Exception, request: org.springframework.web.context.request.WebRequest, httpStatus: org.springframework.http.HttpStatus): org.springframework.http.ResponseEntity<*> {

        val headers = org.springframework.http.HttpHeaders().apply { contentType = org.springframework.http.MediaType.APPLICATION_JSON }
        val message = createErrorMessage(e)

        val items = when (e) {
            is no.skatteetaten.aurora.boober.service.internal.AuroraConfigException -> e.errors
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
            val json: Map<*, *>? = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(cause.responseBodyAsString, Map::class.java)
            if (json?.get("kind")!! == "Status") json["message"] as String? else null
        } else null

        return StringBuilder().apply {
            e.message?.let { append(it + ".") }
            openShiftMessage?.let { append(" " + it) }
        }.toString()
    }
}