package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.ConfigFieldError
import no.skatteetaten.aurora.boober.service.AuroraConfigException
import no.skatteetaten.aurora.boober.utils.findAllPointers
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AuroraConfigValidator(val applicationId: ApplicationId,
                            val applicationFiles: List<AuroraConfigFile>,
                            val fieldHandlers: Set<AuroraConfigFieldHandler>,
                            val auroraConfigFields: AuroraConfigFields) {
    val logger: Logger = LoggerFactory.getLogger(AuroraConfigValidator::class.java)

    companion object {
        val namePattern = "^[a-z][-a-z0-9]{0,38}[a-z0-9]$"
    }

    @JvmOverloads
    fun validate(fullValidation: Boolean = true) {

        val errors: List<ConfigFieldError> = fieldHandlers.mapNotNull { e ->
            val rawField = auroraConfigFields.fields[e.name]!!

            //TODO: We should change this
            val auroraConfigField = if (rawField.value.isMissingNode) {
                jacksonObjectMapper().convertValue(rawField.handler.defaultValue, JsonNode::class.java)
            } else {
                rawField.value
            }


            val result = e.validator(auroraConfigField)

            when {
                auroraConfigField == null && rawField.handler.defaultValue != null -> null
                result == null -> null
                auroraConfigField != null -> ConfigFieldError.illegal(result.localizedMessage, rawField)
                else -> ConfigFieldError.missing(result.localizedMessage, e.path)
            }
        }

        val unmappedErrors = if (fullValidation) {
            getUnmappedPointers().flatMap { pointerError ->
                pointerError.value.map { ConfigFieldError.invalid(pointerError.key, it) }
            }
        } else {
            emptyList()
        }


        (errors + unmappedErrors).takeIf { it.isNotEmpty() }?.let {
            logger.debug("{}", it)
            val aid = applicationId
            throw AuroraConfigException(
                    "Config for application ${aid.application} in environment ${aid.environment} contains errors",
                    errors = it
            )
        }
    }

    private fun getUnmappedPointers(): Map<String, List<String>> {
        val allPaths = fieldHandlers.map { it.path }

        val filePointers = applicationFiles.associateBy({ it.configName }, { it.contents.findAllPointers(3) })

        return filePointers.mapValues { it.value - allPaths }.filterValues { it.isNotEmpty() }
    }
}