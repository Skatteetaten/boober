package no.skatteetaten.aurora.boober.mapper.v1

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraConfigMapper
import no.skatteetaten.aurora.boober.mapper.findConfig
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern

abstract class AuroraConfigMapperV1(aid: ApplicationId, auroraConfig: AuroraConfig, allFiles: List<AuroraConfigFile>, openShiftClient: OpenShiftClient) :
        AuroraConfigMapper(aid, auroraConfig, allFiles, openShiftClient) {

    val v1Handlers = baseHandlers + listOf(
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("name", validator = { it.pattern("^[a-z][-a-z0-9]{0,23}[a-z0-9]$", "Name must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("flags/route", defaultValue = "false"),
            AuroraConfigFieldHandler("permissions/admin/groups", validator = { json ->
                if (json == null || json.textValue().isBlank()) {
                    IllegalArgumentException("Groups must be set")
                } else {
                    val groups = json.textValue().split(" ").toSet()
                    groups.filter { !openShiftClient.isValidGroup(it) }
                            .takeIf { it.isNotEmpty() }
                            ?.let { AuroraConfigException("The following groups are not valid=${it.joinToString()}") }
                }
            }),
            AuroraConfigFieldHandler("permissions/admin/users", validator = { json ->

                val users = json?.textValue()?.split(" ")?.toSet()
                users?.filter { !openShiftClient.isValidUser(it) }
                        .takeIf { it != null && it.isNotEmpty() }
                        ?.let { AuroraConfigException("The following users are not valid=${it.joinToString()}") }


            }),
            AuroraConfigFieldHandler("database"),
            AuroraConfigFieldHandler("webseal/path"),
            AuroraConfigFieldHandler("webseal/roles"),
            AuroraConfigFieldHandler("secretFolder", validator = { json ->

                val secretFolder = json?.textValue()
                val secrets = secretFolder?.let {
                    auroraConfig.getSecrets(it)
                }

                if (secrets != null && secrets.isEmpty()) {
                    IllegalArgumentException("No secrets in folder=$secretFolder")
                } else {
                    null
                }
            })) + allFiles.findConfig()
}