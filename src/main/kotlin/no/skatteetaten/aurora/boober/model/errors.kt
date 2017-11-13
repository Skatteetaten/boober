package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import java.util.*

enum class ConfigErrorType {
    ILLEGAL,
    MISSING,
    INVALID
}

sealed class Error

data class ValidationError(val application: String, val environment: String, val messages: List<Error>) : Error()

data class VersioningError(val fileName: String, val name: String, val date: Date) : Error()

data class ConfigFieldError(val type: ConfigErrorType, val message: String, val field: AuroraConfigField? = null) : Error() {

    companion object {
        fun illegal(message: String, auroraConfigField: AuroraConfigField? = null): ConfigFieldError {
            return ConfigFieldError(ConfigErrorType.ILLEGAL, message, auroraConfigField)
        }

        fun missing(message: String, path: String): ConfigFieldError {
            val acf = AuroraConfigField(path, TextNode(""), "Unknown")
            return ConfigFieldError(ConfigErrorType.MISSING, message, acf)
        }

        fun invalid(filename: String, path: String): ConfigFieldError {
            val acf = AuroraConfigField(path, TextNode(""), filename)
            return ConfigFieldError(ConfigErrorType.INVALID, "$path is not a valid config field pointer", acf)
        }
    }
}
