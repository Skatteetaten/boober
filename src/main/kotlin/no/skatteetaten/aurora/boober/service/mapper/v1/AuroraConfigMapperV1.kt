package no.skatteetaten.aurora.boober.service.mapper.v1

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigMapper
import no.skatteetaten.aurora.boober.service.mapper.findExtractors
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.required

abstract class AuroraConfigMapperV1(aid: ApplicationId, auroraConfig: AuroraConfig, allFiles: List<AuroraConfigFile>, openShiftClient: OpenShiftClient) :
        AuroraConfigMapper(aid, auroraConfig, allFiles, openShiftClient) {

    val v1Handlers = listOf(
            AuroraConfigFieldHandler("schemaVersion", defaultValue = "v1"),
            AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern("^[a-z][-a-z0-9]{0,23}[a-z0-9]$", "Name must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("flags/route", defaultValue = "false"),
            AuroraConfigFieldHandler("permissions/admin/groups", validator = { it.notBlank("Groups must be set.") }),
            AuroraConfigFieldHandler("permissions/admin/users"),
            AuroraConfigFieldHandler("database"),
            AuroraConfigFieldHandler("certificateCn"),
            AuroraConfigFieldHandler("webseal/path"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("secretFolder")
    ) + allFiles.findExtractors("config")
}