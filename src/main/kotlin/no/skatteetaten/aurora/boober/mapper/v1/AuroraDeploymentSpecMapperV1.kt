package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.AuroraConfigValidator.Companion.namePattern
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraBuild
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraLocalTemplate
import no.skatteetaten.aurora.boober.model.AuroraRoute
import no.skatteetaten.aurora.boober.model.AuroraTemplate
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraDeploymentSpecMapperV1(val applicationId: ApplicationId) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{1,10}$", "Affiliation can only contain letters and must be no longer than 10 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern(namePattern, "Name must be alphanumeric and no more than 40 characters", false) }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("permissions/admin"),
            AuroraConfigFieldHandler("permissions/view"),
            AuroraConfigFieldHandler("permissions/adminServiceAccount"),

            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("certificate/commonName"),
            AuroraConfigFieldHandler("certificate"),
            AuroraConfigFieldHandler("database")
    )

    fun createAuroraDeploymentSpec(auroraConfigFields: AuroraConfigFields,
                                   volume: AuroraVolume?,
                                   route: AuroraRoute?,
                                   build: AuroraBuild?,
                                   deploy: AuroraDeploy?,
                                   template: AuroraTemplate?,
                                   localTemplate: AuroraLocalTemplate?
    ): AuroraDeploymentSpec {
        val name = auroraConfigFields.extractOrDefault("name",applicationId.application)
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
        val view = auroraConfigFields.extractOrNull("permissions/view", { it.textValue().split(" ").toSet() })?.let {
            Permission(it)
        }

        //if sa present add to admin users.
        val sa = auroraConfigFields.extractOrNull("permissions/adminServiceAccount", { it.textValue().split(" ").toSet() }) ?: emptySet()
        val permission = Permissions(
                admin = Permission(
                        auroraConfigFields.extract("permissions/admin", { it.textValue().split(" ").filter { !it.isBlank() }.toSet() }), sa),
                view = view)
        return permission
    }
}