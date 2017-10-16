package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.ValidationError
import no.skatteetaten.aurora.boober.service.ValidationErrorType.*
import no.skatteetaten.aurora.boober.utils.findAllPointers
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AuroraConfigValidator(val applicationId: ApplicationId,
                            val applicationFiles: List<AuroraConfigFile>,
                            val fieldHandlers: Set<AuroraConfigFieldHandler>,
                            val auroraConfigFields: AuroraConfigFields) {
    val logger: Logger = LoggerFactory.getLogger(AuroraConfigValidator::class.java)

    @JvmOverloads
    fun validate(checkUnmappedPointers: Boolean = true) {
        val errors: List<ValidationError> = fieldHandlers.mapNotNull { e ->
            val auroraConfigField = auroraConfigFields.fields[e.name]
            val result = e.validator(auroraConfigField?.value)

            when {
                result == null -> null
                auroraConfigField != null -> illegalConfigField(result.localizedMessage, auroraConfigField)
                else -> missingConfigField(e.path, result.localizedMessage)
            }
        }

        val unmappedErrors = if (checkUnmappedPointers) {
            getUnmappedPointers().flatMap { pointerError ->
                pointerError.value.map { invalidConfigField(it, pointerError.key) }
            }
        } else {
            emptyList()
        }

        (errors + unmappedErrors).takeIf { it.isNotEmpty() }?.let {
            logger.debug("{}", it)
            val aid = applicationId
            throw ApplicationConfigException(
                    "Config for application ${aid.application} in environment ${aid.environment} contains errors",
                    errors = it.mapNotNull { it }
            )
        }
    }

    private fun illegalConfigField(message: String, auroraConfigField: AuroraConfigField): ValidationError {
        return ValidationError(ILLEGAL, message, auroraConfigField)
    }

    private fun missingConfigField(path: String, message: String): ValidationError {
        val acf = AuroraConfigField(path, TextNode(""), "Unknown")
        return ValidationError(MISSING, message, acf)
    }

    private fun invalidConfigField(path: String, filename: String): ValidationError {
        val acf = AuroraConfigField(path, TextNode(""), filename)
        return ValidationError(INVALID, "$path is not a valid config field pointer", acf)
    }


    private fun getUnmappedPointers(): Map<String, List<String>> {
        val allPaths = fieldHandlers.map { it.path }

        val filePointers = applicationFiles.associateBy({ it.configName }, { it.contents.findAllPointers(3) })

        return filePointers.mapValues { it.value - allPaths }.filterValues { it.isNotEmpty() }
    }
}