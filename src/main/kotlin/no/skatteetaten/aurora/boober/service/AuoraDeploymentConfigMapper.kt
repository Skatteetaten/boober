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
            AuroraConfigFieldHandler("schemaVersion", defaultValue = "v1"),
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
            AuroraConfigFieldHandler("name"),
            AuroraConfigFieldHandler("flags/route", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/cert", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/debug", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/alarm", defaultValue = "true"),
            AuroraConfigFieldHandler("flags/rolling", defaultValue = "false"),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "256Mi"),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("permissions/admin/groups", validator = { it.notBlank("Groups must be set.") }),
            AuroraConfigFieldHandler("permissions/admin/users"),
            AuroraConfigFieldHandler("replicas", defaultValue = "1"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
            AuroraConfigFieldHandler("extraTags", defaultValue = "latest,major,minor,patch"),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("database"),
            AuroraConfigFieldHandler("certificateCn"),
            AuroraConfigFieldHandler("webseal/path"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("prometheus/path"),
            AuroraConfigFieldHandler("prometheus/port"),
            AuroraConfigFieldHandler("managementPath"),
            AuroraConfigFieldHandler("secretFolder")
    )


}