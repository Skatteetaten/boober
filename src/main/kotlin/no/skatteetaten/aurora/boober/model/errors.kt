package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

enum class ErrorType {
    ILLEGAL,
    MISSING,
    INVALID,
    GENERIC,
    SKIPPED,
    WARNING
}

data class ApplicationError(
    val application: String,
    val environment: String,
    val details: List<ErrorDetail>,
    val type: String = "APPLICATION"
)

data class AuroraConfigFieldError(val path: String, val fileName: String? = null, val value: JsonNode? = null)

open class ErrorDetail(val type: ErrorType = ErrorType.GENERIC, val message: String)

class ConfigFieldErrorDetail(type: ErrorType, message: String, val field: AuroraConfigFieldError? = null) :
    ErrorDetail(type, message) {

    fun asWarning(): String {
        val typeMessage = if (type != ErrorType.WARNING) "ERROR type=$type " else ""
        val fieldMessage = field?.let {
            if (it.fileName == null) {
                "path=${it.path}"
            } else {
                "file=${it.fileName} path=${it.path}"
            }
        } ?: ""
        return "$typeMessage$fieldMessage message=$message"
    }

    companion object {
        fun illegal(
            message: String,
            path: String = "",
            auroraConfigField: AuroraConfigField? = null
        ): ConfigFieldErrorDetail {
            return forSeverity(message, path, auroraConfigField, ErrorType.ILLEGAL)
        }

        fun missing(message: String, path: String): ConfigFieldErrorDetail {
            val fieldError = AuroraConfigFieldError(path)
            return ConfigFieldErrorDetail(ErrorType.MISSING, message, fieldError)
        }

        fun invalid(filename: String, path: String): ConfigFieldErrorDetail {
            val fieldError = AuroraConfigFieldError(path, filename)
            return ConfigFieldErrorDetail(ErrorType.INVALID, "$path is not a valid config field pointer", fieldError)
        }

        fun forSeverity(
            message: String,
            path: String = "",
            auroraConfigField: AuroraConfigField? = null,
            severity: ErrorType = ErrorType.ILLEGAL
        ): ConfigFieldErrorDetail {
            val fieldError = auroraConfigField?.let {
                AuroraConfigFieldError(path, auroraConfigField.name, auroraConfigField.value)
            }
            return ConfigFieldErrorDetail(severity, message, fieldError)
        }
    }
}
