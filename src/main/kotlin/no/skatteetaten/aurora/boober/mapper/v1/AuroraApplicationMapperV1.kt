package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraApplication
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraApplicationMapperV1(val openShiftClient: OpenShiftClient,
                                val applicationId: ApplicationId) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,8}[a-z]$", "Affiliation is must be alphanumeric and not more then 10 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern("^[a-z][-a-z0-9]{0,38}[a-z0-9]$", "Name must be alphanumeric and no more then 40 characters") }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("permissions/admin", validator = validateGroups(openShiftClient)),
            AuroraConfigFieldHandler("permissions/view", validator = validateGroups(openShiftClient, false))
    )

    fun auroraApplicationConfig(auroraConfigFields: AuroraConfigFields,
                                volume: AuroraVolume?,
                                route: AuroraRoute?,
                                build: AuroraBuild?,
                                deploy: AuroraDeploy?,
                                template: AuroraTemplate?,
                                localTemplate: AuroraLocalTemplate?
    ): AuroraApplication {
        val name = auroraConfigFields.extract("name")
        return AuroraApplication(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),

                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) }),
                name = name,
                envName = auroraConfigFields.extractOrDefault("envName", applicationId.environment),
                permissions = extractPermissions(auroraConfigFields),
                fields = auroraConfigFields.fields,
                volume = volume,
                route = route,
                build = build,
                deploy = deploy,
                template = template,
                localTemplate = localTemplate)

    }


    fun validateGroups(openShiftClient: OpenShiftClient, required: Boolean = true): (JsonNode?) -> Exception? {
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

    private fun extractPermissions(auroraConfigFields: AuroraConfigFields): Permissions {
        val viewGroups = auroraConfigFields.extractOrNull("permissions/view", { it.textValue().split(" ").toSet() })
        val view = if (viewGroups != null) {
            Permission(viewGroups)
        } else null

        return Permissions(
                admin = Permission(
                        auroraConfigFields.extract("permissions/admin", { it.textValue().split(" ").toSet() })),
                view = view)
    }
}