package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.findAllPointers
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraApplicationMapperV1(val applicationFiles: List<AuroraConfigFile>,
                                val openShiftClient: OpenShiftClient,
                                val deployCommand: DeployCommand) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern("^[a-z][-a-z0-9]{0,23}[a-z0-9]$", "Name must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("permissions/admin/groups", validator = validateGroups(openShiftClient)),
            AuroraConfigFieldHandler("permissions/admin/users", validator = validateUsers(openShiftClient)),
            AuroraConfigFieldHandler("permissions/view/groups", validator = validateGroups(openShiftClient, false)),
            AuroraConfigFieldHandler("permissions/view/users", validator = validateUsers(openShiftClient))
    )

    fun auroraApplicationConfig(auroraConfigFields: AuroraConfigFields,
                                fieldHandlers: Set<AuroraConfigFieldHandler>,
                                dc: AuroraDeploymentCore?,
                                build: AuroraBuild?,
                                deploy: AuroraDeploy?,
                                template: AuroraTemplate?,
                                localTemplate: AuroraLocalTemplate?
    ): AuroraApplicationConfig {
        val name = auroraConfigFields.extract("name")
        return AuroraApplicationConfig(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),

                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) }),
                name = name,
                envName = auroraConfigFields.extractOrDefault("envName", deployCommand.applicationId.environment),
                permissions = extractPermissions(auroraConfigFields),
                fields = auroraConfigFields.fields,
                unmappedPointers = getUnmappedPointers(fieldHandlers),
                dc = dc,
                build = build,
                deploy = deploy,
                template = template,
                localTemplate = localTemplate)

    }


    private fun validateGroups(openShiftClient: OpenShiftClient, required: Boolean = true): (JsonNode?) -> Exception? {
        return { json ->
            if (required && (json == null || json.textValue().isBlank())) {
                IllegalArgumentException("Groups must be set")
            } else {
                val groups = json?.textValue()?.split(" ")?.toSet()
                groups?.filter { !openShiftClient.isValidGroup(it) }
                        .takeIf { it != null && it.isNotEmpty() }
                        ?.let { AuroraConfigException("The following groups are not valid=${it.joinToString()}") }
            }
        }
    }

    private fun validateUsers(openShiftClient: OpenShiftClient): (JsonNode?) -> AuroraConfigException? {
        return { json ->
            val users = json?.textValue()?.split(" ")?.toSet()
            users?.filter { !openShiftClient.isValidUser(it) }
                    .takeIf { it != null && it.isNotEmpty() }
                    ?.let { AuroraConfigException("The following users are not valid=${it.joinToString()}") }
        }
    }

    protected fun extractPermissions(auroraConfigFields: AuroraConfigFields): Permissions {
        val viewGroups = auroraConfigFields.extractOrNull("permissions/view/groups", { it.textValue().split(" ").toSet() })
        val viewUsers = auroraConfigFields.extractOrNull("permissions/view/users", { it.textValue().split(" ").toSet() })
        val view = if (viewGroups != null || viewUsers != null) {
            Permission(viewGroups, viewUsers)
        } else null

        return Permissions(
                admin = Permission(
                        auroraConfigFields.extract("permissions/admin/groups", { it.textValue().split(" ").toSet() }),
                        auroraConfigFields.extractOrNull("permissions/admin/users", { it.textValue().split(" ").toSet() })),
                view = view)
    }

    fun getUnmappedPointers(fieldHandlers: Set<AuroraConfigFieldHandler>): Map<String, List<String>> {
        val allPaths = fieldHandlers.map { it.path }

        val filePointers = applicationFiles.associateBy({ it.configName }, { it.contents.findAllPointers(3) })

        return filePointers.mapValues { it.value - allPaths }.filterValues { it.isNotEmpty() }
    }


}