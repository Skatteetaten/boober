package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
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

        fun missing(message: String, name: String): ConfigFieldError {
            val acf = AuroraConfigField(AuroraConfigFieldHandler(name))
            return ConfigFieldError(ConfigErrorType.MISSING, message, acf)
        }

        fun invalid(filename: String, path: String): ConfigFieldError {
            val acf = AuroraConfigField(AuroraConfigFieldHandler(path.substring(1)), AuroraConfigFile(filename, TextNode("")))
            return ConfigFieldError(ConfigErrorType.INVALID, "$path is not a valid config field pointer", acf)
        }
    }
}
