package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.utils.findAllPointers

private val logger = KotlinLogging.logger {}

class AuroraDeploymentSpecConfigFieldValidator(
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val applicationFiles: List<AuroraConfigFile>,
    val fieldHandlers: Set<AuroraConfigFieldHandler>,
    val fields: Map<String, AuroraConfigField>
) {

    fun validate(fullValidation: Boolean = true) {

        val envPointers = listOf(
            "env/name", "env/ttl", "envName", "affiliation",
            "permissions/admin", "permissions/view", "permissions/adminServiceAccount"
        )

        val errors: List<ConfigFieldErrorDetail> = fieldHandlers.mapNotNull { e ->
            val rawField = fields[e.name]
            if (rawField == null) {
                e.validator(null)?.let {
                    ConfigFieldErrorDetail.missing(it.localizedMessage, e.name)
                }
            } else {
                val invalidEnvSource =
                    envPointers.contains(e.name) && !rawField.isDefault && rawField.name.let {
                        !it.split("/").last().startsWith(
                            "about"
                        )
                    }

                logger.trace("Validating field=${e.name}")
                val auroraConfigField: JsonNode? = rawField.value
                logger.trace("value is=${jacksonObjectMapper().writeValueAsString(auroraConfigField)}")

                val result = e.validator(auroraConfigField)
                logger.trace("validator result is=$result")

                val err = when {
                    invalidEnvSource -> ConfigFieldErrorDetail.illegal(
                        "Invalid Source field=${e.name} requires an about source. Actual source is source=${rawField.name}",
                        e.name, rawField
                    )
                    result == null -> null
                    auroraConfigField != null -> ConfigFieldErrorDetail.illegal(
                        result.localizedMessage,
                        e.name,
                        rawField
                    )
                    else -> ConfigFieldErrorDetail.missing(result.localizedMessage, e.name)
                }
                if (err != null) {
                    logger.trace("Error=$err message=${err.message}")
                }
                err
            }
        }

        // TODO test unmapped
        val unmappedErrors = if (fullValidation) {
            getUnmappedPointers().flatMap { pointerError ->
                pointerError.value.map { ConfigFieldErrorDetail.invalid(pointerError.key, it) }
            }
        } else {
            emptyList()
        }

        (errors + unmappedErrors).takeIf { it.isNotEmpty() }?.let {
            val aid = applicationDeploymentRef
            throw AuroraConfigException(
                "Config for application ${aid.application} in environment ${aid.environment} contains errors",
                errors = it
            )
        }
    }

    private fun getUnmappedPointers(): Map<String, List<String>> {
        val allPaths = fieldHandlers.map { "/${it.name}" }

        val filePointers = applicationFiles.associateBy({ it.configName }, { it.asJsonNode.findAllPointers(3) })

        return filePointers.mapValues { it.value - allPaths }.filterValues { it.isNotEmpty() }
    }
}
