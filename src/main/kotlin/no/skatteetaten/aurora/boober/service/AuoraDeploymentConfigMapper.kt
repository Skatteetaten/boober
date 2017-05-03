package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.required

interface AuroraDeploymentConfigMapper {

    val extractors: List<AuroraConfigFieldHandler>
}

class AuroraDeploymentConfigMapperV1 : AuroraDeploymentConfigMapper {

    override val extractors = listOf(
            AuroraConfigFieldHandler("schemaVersion", defultValue = "v1"),
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
            AuroraConfigFieldHandler("name"),
            AuroraConfigFieldHandler("flags/route", defultValue = "false"),
            AuroraConfigFieldHandler("flags/cert", defultValue = "false"),
            AuroraConfigFieldHandler("flags/debug", defultValue = "false"),
            AuroraConfigFieldHandler("flags/alarm", defultValue = "false"),
            AuroraConfigFieldHandler("flags/rolling", defultValue = "false"),
            AuroraConfigFieldHandler("resources/cpu/min", defultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defultValue = "2000"),
            AuroraConfigFieldHandler("resources/memory/min", defultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defultValue = "256Mi"),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("permissions/admin/groups", validator = { it.notBlank("Groups must be set.") }),
            AuroraConfigFieldHandler("permissions/admin/users"),
            AuroraConfigFieldHandler("replicas", defultValue = "1"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
            AuroraConfigFieldHandler("extraTags", defultValue = "latest,major,minor,patch"),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("database"),
            AuroraConfigFieldHandler("certificateCn"),
            AuroraConfigFieldHandler("webseal/path"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("prometheus/path"),
            AuroraConfigFieldHandler("prometheus/port"),
            AuroraConfigFieldHandler("managementPath")
    )


}