package no.skatteetaten.aurora.boober.service.mapper.v1

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.service.mapper.findExtractors
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

class AuroraConfigMapperV1Template(aid: ApplicationId,
                                   auroraConfig: AuroraConfig,
                                   allFiles: List<AuroraConfigFile>,
                                   openShiftClient: OpenShiftClient) :
        AuroraConfigMapperV1(aid, auroraConfig, allFiles, openShiftClient) {

    override fun createAuroraDc(): AuroraObjectsConfig {

        val type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) })

        return AuroraTemplateConfig(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),
                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = type,
                name = auroraConfigFields.extract("name"),
                envName = auroraConfigFields.extractOrDefault("envName", aid.environmentName),
                permissions = extractPermissions(),
                secrets = extractSecret(),
                config = auroraConfigFields.getConfigMap(allFiles.findExtractors("config")),
                template = auroraConfigFields.extract("template"),
                parameters = auroraConfigFields.getParameters(allFiles.findExtractors("parameters")),
                flags = AuroraProcessConfigFlags(
                        auroraConfigFields.extract("flags/route", { it.asText() == "true" })
                ),
                fields = auroraConfigFields.fields
        )

    }




    val handlers = listOf(
            AuroraConfigFieldHandler("template", validator = { json ->

                val template = json?.textValue()

                if (template == null) {
                    IllegalArgumentException("Template is required")
                } else if (!openShiftClient.templateExist(template)) {
                    IllegalArgumentException("Template $template does not exist in openshift namespace")
                } else {
                    null
                }
            })
    )

    override val fieldHandlers = v1Handlers + handlers + allFiles.findExtractors("parameters")
    override val auroraConfigFields = AuroraConfigFields.create(fieldHandlers, allFiles)

}