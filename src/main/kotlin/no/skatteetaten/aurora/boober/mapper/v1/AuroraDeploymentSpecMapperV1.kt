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
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

class AuroraDeploymentSpecMapperV1(val applicationId: ApplicationId) {


    val handlers = listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{1,10}$", "Affiliation can only contain letters and must be no longer than 10 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("permissions/admin"),
            AuroraConfigFieldHandler("permissions/view"),
            AuroraConfigFieldHandler("permissions/adminServiceAccount"),
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,8}[a-z]$", "Affiliation must be alphanumeric and not more than 10 characters") }),
            AuroraConfigFieldHandler("envName",
                    defaultSource = "folderName",
                    defaultValue = applicationId.environment
            ),
            AuroraConfigFieldHandler("name",
                    defaultValue = applicationId.application,
                    defaultSource = "fileName",
                    validator = { it.pattern(namePattern, "Name must be alphanumeric and no more than 40 characters", false) }),
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
        val name: String = auroraConfigFields.extract("name")
        return AuroraDeploymentSpec(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),

                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = auroraConfigFields.extract("type"),
                name = name,
                envName = auroraConfigFields.extract("envName"),
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
        val view = auroraConfigFields.extractOrNull<String?>("permissions/view")?.let {
            it.split(" ").toSet()
        }?.let {
            Permission(it)
        }

        //if sa present add to admin users.
        val sa = auroraConfigFields.extractOrNull<String?>("permissions/adminServiceAccount")?.let { it.split(" ").toSet() } ?: emptySet()
        val permission = Permissions(
                admin = Permission(
                        auroraConfigFields.extract<String>("permissions/admin").let { it.split(" ").filter { !it.isBlank() }.toSet() }, sa),
                view = view)
        return permission
    }
}