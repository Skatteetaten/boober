package no.skatteetaten.aurora.boober.service.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.service.mapper.findExtractors
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

class AuroraConfigMapperV1LocalTemplate(aid: ApplicationId,
                                        auroraConfig: AuroraConfig,
                                        allFiles: List<AuroraConfigFile>,
                                        openShiftClient: OpenShiftClient) :
        AuroraConfigMapperV1(aid, auroraConfig, allFiles, openShiftClient) {

    override fun createAuroraDc(): AuroraObjectsConfig {

        val type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val templateJson = extractTemplateJson()

        return AuroraLocalTemplateConfig(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),
                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = type,
                name = auroraConfigFields.extract("name"),
                envName = auroraConfigFields.extractOrDefault("envName", aid.environmentName),
                permissions = extractPermissions(),
                secrets = extractSecret(),
                config = auroraConfigFields.getConfigMap(allFiles.findExtractors("config")),
                templateJson = templateJson,
                parameters = auroraConfigFields.getParameters(allFiles.findExtractors("parameters")),
                flags = AuroraProcessConfigFlags(
                        auroraConfigFields.extract("flags/route", { it.asText() == "true" })
                ),
                fields = auroraConfigFields.fields
        )

    }

    private fun extractTemplateJson(): JsonNode {
        val templateFile = auroraConfigFields.extract("templateFile").let { fileName ->
            auroraConfig.auroraConfigFiles.find { it.name == fileName }?.contents
        }
        return templateFile ?: throw IllegalArgumentException("templateFile is required")
    }

    val handlers = listOf(
            AuroraConfigFieldHandler("templateFile", validator = { json ->
                val fileName = json?.textValue()
                if (auroraConfig.auroraConfigFiles.none { it.name == fileName }) {
                    IllegalArgumentException("The file named $fileName does not exist in AuroraConfig")
                } else {
                    null
                }
            })
    )

    override val fieldHandlers = v1Handlers + handlers + allFiles.findExtractors("parameters")
    override val auroraConfigFields = AuroraConfigFields.create(fieldHandlers, allFiles)

}