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

            logger.trace("Validating field=${e.name}")
            val auroraConfigField: JsonNode? = rawField.valueOrDefault
            logger.trace("value is=${jacksonObjectMapper().writeValueAsString(auroraConfigField)}")

            val result = e.validator(auroraConfigField)
            logger.trace("validator result is=${result}")

            val err = when {
                result == null -> null
                auroraConfigField != null -> ConfigFieldError.illegal(result.localizedMessage, rawField)
                else -> ConfigFieldError.missing(result.localizedMessage, e.name)
            }
            if(err!=null){
                logger.trace("Error=$err message=${err.message}")
            }
            err
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