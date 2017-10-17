package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraDeploymentSpecMapperV1(val applicationId: ApplicationId) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,8}[a-z]$", "Affiliation must be alphanumeric and not more than 10 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern("^[a-z][-a-z0-9]{0,38}[a-z0-9]$", "Name must be alphanumeric and no more than 40 characters") }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("permissions/admin"),
            AuroraConfigFieldHandler("permissions/view")
    )

    fun createAuroraDeploymentSpec(auroraConfigFields: AuroraConfigFields,
                                   volume: AuroraVolume?,
                                   route: AuroraRoute?,
                                   build: AuroraBuild?,
                                   deploy: AuroraDeploy?,
                                   template: AuroraTemplate?,
                                   localTemplate: AuroraLocalTemplate?
    ): AuroraDeploymentSpec {
        val name = auroraConfigFields.extract("name")
        return AuroraDeploymentSpec(
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

    private fun extractPermissions(auroraConfigFields: AuroraConfigFields): Permissions {
        val viewGroups = auroraConfigFields.extractOrNull("permissions/view", { it.textValue().split(" ").toSet() })
        val view = if (viewGroups != null) {
            Permission(viewGroups)
        } else null

        return Permissions(
                admin = Permission(
                        auroraConfigFields.extract("permissions/admin", { it.textValue().split(" ").filter { !it.isBlank() }.toSet() })),
                view = view)
    }
}