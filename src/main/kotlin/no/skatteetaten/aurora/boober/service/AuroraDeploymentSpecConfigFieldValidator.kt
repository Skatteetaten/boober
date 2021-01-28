package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.utils.findAllPointers

private val logger = KotlinLogging.logger {}

class AuroraDeploymentSpecConfigFieldValidator(
    val applicationFiles: List<AuroraConfigFile>,
    val fieldHandlers: Set<AuroraConfigFieldHandler>,
    val fields: Map<String, AuroraConfigField>
) {

    fun validate(fullValidation: Boolean = true): List<ConfigFieldErrorDetail> {

        //OVERFORING det hadde vært bedre om hver peker sier om den selv er en env peker eller ikke
        //OVERFORING eller om det å lage/oppdatere et miljø er helt separert fra det å lage en applikasjon
        //OVERFORING det kan jo argumenteres for at noen har lov til å deploye en applikasjon i et navnerom men ikke endre hvem som kan deploye applikasjoner der f.eks

        val envPointers = listOf(
            "env/name", "env/ttl", "envName", "affiliation",
            "permissions/admin", "permissions/view", "permissions/adminServiceAccount"
        )

        // OVERFORING dette er første steget i validering, den validerer alle validatorene som er rett i handlerene
        // OVERFORING når man skrev dette hadde vi veldig få validatorer som ikke var i handlerne. Kanskje man bare burde samlet dette 1 plass i validate i featurene i steden for i handlerene og i featurenene?

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
        // OVERFORING her lager vi da feil hvis man har en json peker i sin struktur som _ikke_ har en handler
        // OVERFORING her burde vi vel egentlig returnert et objekt som inneholder disse feilene som en egen type så det er lettere å skipe dem eller ei?
        val unmappedErrors = if (fullValidation) {
            getUnmappedPointers().flatMap { pointerError ->
                pointerError.value.map { ConfigFieldErrorDetail.invalid(pointerError.key, it) }
            }
        } else {
            emptyList()
        }

        return errors + unmappedErrors
    }

    private fun getUnmappedPointers(): Map<String, List<String>> {
        val allPaths = fieldHandlers.map { "/${it.name}" }

        val filePointers = applicationFiles.associateBy({ it.configName }, { it.asJsonNode.findAllPointers(3) })

        return filePointers.mapValues { it.value - allPaths }.filterValues { it.isNotEmpty() }
    }
}
