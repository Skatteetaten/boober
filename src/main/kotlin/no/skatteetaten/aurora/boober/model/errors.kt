package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler

enum class ErrorType {
    ILLEGAL,
    MISSING,
    INVALID,
    GENERIC
}

data class ApplicationError(val application: String, val environment: String, val details: List<ErrorDetail>, val type : String = "APPLICATION")

open class ErrorDetail(val type: ErrorType = ErrorType.GENERIC, val message: String)

class ConfigFieldErrorDetail(type: ErrorType, message: String, val field: AuroraConfigField? = null)
    : ErrorDetail(type, message) {

    companion object {
        fun illegal(message: String, auroraConfigField: AuroraConfigField? = null): ConfigFieldErrorDetail {
            return ConfigFieldErrorDetail(ErrorType.ILLEGAL, message, auroraConfigField)
        }

        fun missing(message: String, name: String): ConfigFieldErrorDetail {
            val acf = AuroraConfigField(AuroraConfigFieldHandler(name))
            return ConfigFieldErrorDetail(ErrorType.MISSING, message, acf)
        }

        fun invalid(filename: String, path: String): ConfigFieldErrorDetail {
            val acf = AuroraConfigField(AuroraConfigFieldHandler(path.substring(1)), AuroraConfigFile(filename, TextNode("")))
            return ConfigFieldErrorDetail(ErrorType.INVALID, "$path is not a valid config field pointer", acf)
        }
    }
}
