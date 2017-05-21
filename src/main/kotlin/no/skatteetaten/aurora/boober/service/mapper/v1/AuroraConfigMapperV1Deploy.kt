package no.skatteetaten.aurora.boober.service.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.service.mapper.findExtractors
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank


class AuroraConfigMapperV1Deploy(aid: ApplicationId,
                                 auroraConfig: AuroraConfig,
                                 allFiles: List<AuroraConfigFile>,
                                 openShiftClient: OpenShiftClient) :
        AuroraConfigMapperV1(aid, auroraConfig, allFiles, openShiftClient) {


    val handlers = listOf(
            AuroraConfigFieldHandler("flags/cert", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/debug", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/alarm", defaultValue = "true"),
            AuroraConfigFieldHandler("flags/rolling", defaultValue = "false"),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "256Mi"),
            AuroraConfigFieldHandler("replicas", defaultValue = "1"),
            AuroraConfigFieldHandler("groupId", validator = { it.length(200, "GroupId must be set and be shorter then 200 characters") }),
            AuroraConfigFieldHandler("artifactId", validator = { it.length(50, "ArtifactId must be set and be shorter then 50 characters") }),
            AuroraConfigFieldHandler("version", validator = { it.notBlank("Version must be set") }),
            AuroraConfigFieldHandler("extraTags", defaultValue = "latest,major,minor,patch"),
            AuroraConfigFieldHandler("splunkIndex"),
            AuroraConfigFieldHandler("prometheus/path"),
            AuroraConfigFieldHandler("prometheus/port"),
            AuroraConfigFieldHandler("managementPath")
    )

    override val fieldHandlers = v1Handlers + handlers

    override val auroraConfigFields = AuroraConfigFields.create(fieldHandlers, allFiles)


    override fun createAuroraDc(): AuroraObjectsConfig {

        val type = auroraConfigFields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val groupId = auroraConfigFields.extract("groupId")
        val artifactId = auroraConfigFields.extract("artifactId")
        val name = auroraConfigFields.extractOrDefault("name", artifactId)


        return AuroraDeploymentConfig(
                schemaVersion = auroraConfigFields.extract("schemaVersion"),

                affiliation = auroraConfigFields.extract("affiliation"),
                cluster = auroraConfigFields.extract("cluster"),
                type = type,
                name = name,
                envName = auroraConfigFields.extractOrDefault("envName", aid.environmentName),

                groupId = groupId,
                artifactId = artifactId,
                version = auroraConfigFields.extract("version"),

                replicas = auroraConfigFields.extract("replicas", JsonNode::asInt),
                extraTags = auroraConfigFields.extract("extraTags"),

                flags = AuroraDeploymentConfigFlags(
                        auroraConfigFields.extract("flags/route", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/cert", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/debug", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/alarm", { it.asText() == "true" }),
                        auroraConfigFields.extract("flags/rolling", { it.asText() == "true" })
                ),
                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/memory/min"),
                                max = auroraConfigFields.extract("resources/memory/max")
                        ),
                        cpu = AuroraDeploymentConfigResource(
                                min = auroraConfigFields.extract("resources/cpu/min"),
                                max = auroraConfigFields.extract("resources/cpu/max")
                        )
                ),
                permissions = extractPermissions(),
                splunkIndex = auroraConfigFields.extractOrNull("splunkIndex"),
                database = auroraConfigFields.extractOrNull("database"),
                managementPath = auroraConfigFields.extractOrNull("managementPath"),

                certificateCn = auroraConfigFields.extractOrDefault("certificateCn", "$groupId.$name"),

                webseal = auroraConfigFields.findAll("webseal", {
                    Webseal(
                            auroraConfigFields.extract("webseal/path"),
                            auroraConfigFields.extractOrNull("webseal/roles")
                    )
                }),

                prometheus = auroraConfigFields.findAll("prometheus", {
                    HttpEndpoint(
                            auroraConfigFields.extract("prometheus/path"),
                            auroraConfigFields.extractOrNull("prometheus/port", JsonNode::asInt)
                    )
                }),

                secrets = auroraConfigFields.extractOrNull("secretFolder", {
                    auroraConfig.getSecrets(it.asText())
                }),

                config = auroraConfigFields.getConfigMap(allFiles.findExtractors("config")),
                fields = auroraConfigFields.fields
        )
    }


    override fun typeValidation(fields: AuroraConfigFields): List<Exception> {
        val errors = mutableListOf<Exception>()

        val artifactId = fields.extract("artifactId")
        val name = fields.extractOrDefault("name", artifactId)


        if (!Regex("^[a-z][-a-z0-9]{0,23}[a-z0-9]$").matches(name)) {
            errors.add(IllegalArgumentException("Name [$name] is not valid DNS952 label. 24 length alphanumeric."))
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

}