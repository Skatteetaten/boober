package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField

enum class ErrorType {
    ILLEGAL,
    MISSING,
    INVALID,
    GENERIC
}

data class ApplicationError(val application: String, val environment: String, val details: List<ErrorDetail>, val type: String = "APPLICATION")

data class AuroraConfigFieldError(val path: String, val fileName: String? = null, val value: JsonNode? = null)

open class ErrorDetail(val type: ErrorType = ErrorType.GENERIC, val message: String)

class ConfigFieldErrorDetail(type: ErrorType, message: String, val field: AuroraConfigFieldError? = null)
    : ErrorDetail(type, message) {

    companion object {
        fun illegal(message: String, auroraConfigField: AuroraConfigField? = null): ConfigFieldErrorDetail {
            val fieldError = auroraConfigField?.let {
                AuroraConfigFieldError(it.handler.path, it.source?.name, it.valueOrDefault)
            }
            return ConfigFieldErrorDetail(ErrorType.ILLEGAL, message, fieldError)
        }

        fun missing(message: String, path: String): ConfigFieldErrorDetail {
            val fieldError = AuroraConfigFieldError(path)
            return ConfigFieldErrorDetail(ErrorType.MISSING, message, fieldError)
        }

        fun invalid(filename: String, path: String): ConfigFieldErrorDetail {
            val fieldError = AuroraConfigFieldError(path, filename)
            return ConfigFieldErrorDetail(ErrorType.INVALID, "$path is not a valid config field pointer", fieldError)
        }
    }
}
