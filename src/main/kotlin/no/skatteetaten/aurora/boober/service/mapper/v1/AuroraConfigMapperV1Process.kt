package no.skatteetaten.aurora.boober.service.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigMapper
import no.skatteetaten.aurora.boober.service.mapper.findExtractors
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.required

class AuroraConfigMapperV1Process(aid: ApplicationId, auroraConfig: AuroraConfig, allFiles: List<AuroraConfigFile>) :
        AuroraConfigMapper(aid, auroraConfig, allFiles) {

    override fun createAuroraDc(): AuroraObjectsConfig {

        val type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val templateFile = auroraConfigFields.extractOrNull("templateFile")

        val templateJson = extractTemplateJson();

        return AuroraProcessConfig(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),
                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = type,
                name = auroraConfigFields.extract("name"),
                envName = auroraConfigFields.extractOrDefault("envName", aid.environmentName),
                permissions = extractPermissions(),
                secrets = extractSecret(),
                config = auroraConfigFields.getConfigMap(allFiles.findExtractors("config")),
                template = auroraConfigFields.extractOrNull("template"),
                templateFile = templateFile,
                templateJson = templateJson,
                parameters = auroraConfigFields.getParameters(allFiles.findExtractors("parameters")),
                flags = AuroraProcessConfigFlags(
                        auroraConfigFields.extract("flags/route", { it.asText() == "true" })
                ),
                fields = auroraConfigFields.fields
        )

    }

    private fun extractTemplateJson(): JsonNode? {
        val templateFile = auroraConfigFields.extractOrNull("templateFile")?.let { fileName ->
            auroraConfig.auroraConfigFiles.find { it.name == fileName }?.contents
        }
        return templateFile
    }

    override fun typeValidation(fields: AuroraConfigFields, openShiftClient: OpenShiftClient): List<Exception> {
        val errors = mutableListOf<Exception>()


        val template = fields.extractOrNull("template")
        val templateFile = fields.extractOrNull("templateFile")

        if (template == null && templateFile == null) {
            errors.add(IllegalArgumentException("Specify either template or templateFile"))
        } else if (template != null && !openShiftClient.templateExist(template)) {
            errors.add(IllegalArgumentException("Template $template does not exist in openshift namespace"))
        } else if (templateFile != null && auroraConfig.auroraConfigFiles.none { it.name == templateFile }) {
            errors.add(IllegalArgumentException("The file named $templateFile does not exist in AuroraConfig"))
        }

        val secrets = extractSecret()

        if (secrets != null && secrets.isEmpty()) {
            errors.add(IllegalArgumentException("Missing secret files"))
        }

        val permissions = extractPermissions()

        permissions.admin.groups
                ?.filter { !openShiftClient.isValidGroup(it) }
                .takeIf { it != null && it.isNotEmpty() }
                ?.let { errors.add(AuroraConfigException("The following groups are not valid=${it.joinToString()}")) }

        permissions.admin.users
                ?.filter { !openShiftClient.isValidUser(it) }
                .takeIf { it != null && it.isNotEmpty() }
                ?.let { errors.add(AuroraConfigException("The following users are not valid=${it.joinToString()}")) }


        return errors

    }

    val handlers = listOf(
            AuroraConfigFieldHandler("schemaVersion", defaultValue = "v1"),
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern("^[a-z][-a-z0-9]{0,23}[a-z0-9]$", "Name must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("flags/route", defaultValue = "false"),
            AuroraConfigFieldHandler("permissions/admin/groups", validator = { it.notBlank("Groups must be set.") }),
            AuroraConfigFieldHandler("permissions/admin/users"),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("database"),
            AuroraConfigFieldHandler("certificateCn"),
            AuroraConfigFieldHandler("webseal/path"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("secretFolder"),
            AuroraConfigFieldHandler("template"),
            AuroraConfigFieldHandler("templateFile")

    )

    override val fieldHandlers = handlers + allFiles.findExtractors("config") + allFiles.findExtractors("parameters")
    override val auroraConfigFields = AuroraConfigFields.create(fieldHandlers, allFiles)

}