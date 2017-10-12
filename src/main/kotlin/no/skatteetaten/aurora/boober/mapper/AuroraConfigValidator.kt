package no.skatteetaten.aurora.boober.mapper

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.ValidationError
import no.skatteetaten.aurora.boober.utils.findAllPointers
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AuroraConfigValidator(val applicationId: ApplicationId,
                            val applicationFiles: List<AuroraConfigFile>,
                            val fieldHandlers: Set<AuroraConfigFieldHandler>,
                            val auroraConfigFields: AuroraConfigFields) {
    val logger: Logger = LoggerFactory.getLogger(AuroraConfigValidator::class.java)

    @JvmOverloads
    fun validate(checkUnmapedPointers: Boolean = true) {
        val errors = fieldHandlers.mapNotNull { e ->
            val auroraConfigField = auroraConfigFields.fields[e.name]

            e.validator(auroraConfigField?.value)?.let {
                ValidationError(it.localizedMessage, auroraConfigField)
            }
        }

        val unmappedErrors = if (checkUnmapedPointers) {
            getUnmappedPointers().flatMap { pointerError ->
                pointerError.value.map { ValidationError("$it is not a valid config field pointer", fileName = pointerError.key) }
            }
        } else {
            emptyList()
        }

        (errors + unmappedErrors).takeIf { it.isNotEmpty() }?.let {
            logger.debug("{}", it)
            val aid = applicationId
            throw ApplicationConfigException(
                    "Config for application ${aid.application} in environment ${aid.environment} contains errors",
                    errors = it.mapNotNull { it })
        }
    }


    private fun getUnmappedPointers(): Map<String, List<String>> {
        val allPaths = fieldHandlers.map { it.path }

        val filePointers = applicationFiles.associateBy({ it.configName }, { it.contents.findAllPointers(3) })

        return filePointers.mapValues { it.value - allPaths }.filterValues { it.isNotEmpty() }
    }
}