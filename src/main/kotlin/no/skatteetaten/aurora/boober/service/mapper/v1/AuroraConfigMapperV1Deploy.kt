package no.skatteetaten.aurora.boober.service.mapper.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.mapper.AuroraConfigMapper
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.validation.*
import no.skatteetaten.aurora.boober.utils.length
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.pattern
import no.skatteetaten.aurora.boober.utils.required

class AuroraConfigMapperV1Deploy(aid: ApplicationId, auroraConfig: AuroraConfig, allFiles: List<AuroraConfigFile>) : AuroraConfigMapper(aid, auroraConfig, allFiles) {

    override fun transform(fields: Map<String, AuroraConfigField>): AuroraObjectsConfig {

        val type = fields.extract("type", { TemplateType.valueOf(it.textValue()) })

        val groupId = fields.extract("groupId")
        val artifactId = fields.extract("artifactId")
        val name = fields.extractOrDefault("name", artifactId)


        return AuroraDeploymentConfig(
                schemaVersion = fields.extract("schemaVersion"),

                affiliation = fields.extract("affiliation"),
                cluster = fields.extract("cluster"),
                type = type,
                name = name,
                envName = fields.extractOrDefault("envName", aid.environmentName),

                groupId = groupId,
                artifactId = artifactId,
                version = fields.extract("version"),

                replicas = fields.extract("replicas", JsonNode::asInt),
                extraTags = fields.extract("extraTags"),

                flags = AuroraDeploymentConfigFlags(
                        fields.extract("flags/route", { it.asText() == "true" }),
                        fields.extract("flags/cert", { it.asText() == "true" }),
                        fields.extract("flags/debug", { it.asText() == "true" }),
                        fields.extract("flags/alarm", { it.asText() == "true" }),
                        fields.extract("flags/rolling", { it.asText() == "true" })
                ),
                resources = AuroraDeploymentConfigResources(
                        memory = AuroraDeploymentConfigResource(
                                min = fields.extract("resources/memory/min"),
                                max = fields.extract("resources/memory/max")
                        ),
                        cpu = AuroraDeploymentConfigResource(
                                min = fields.extract("resources/cpu/min"),
                                max = fields.extract("resources/cpu/max")
                        )
                ),
                permissions = extractPermissions(fields),
                splunkIndex = fields.extractOrNull("splunkIndex"),
                database = fields.extractOrNull("database"),
                managementPath = fields.extractOrNull("managementPath"),

                certificateCn = fields.extractOrDefault("certificateCn", "$groupId.$name"),

                webseal = fields.findAll("webseal", {
                    Webseal(
                            it.extract("webseal/path"),
                            it.extractOrNull("webseal/roles")
                    )
                }),

                prometheus = fields.findAll("prometheus", {
                    HttpEndpoint(
                            it.extract("prometheus/path"),
                            it.extractOrNull("prometheus/port", JsonNode::asInt)
                    )
                }),

                secrets = fields.extractOrNull("secretFolder", {
                    auroraConfig.getSecrets(it.asText())
                }),

                config = fields.getConfigMap(allFiles.findExtractors("config")),
                fields = fields
        )
    }


    override fun typeValidation(fields: Map<String, AuroraConfigField>, openShiftClient: OpenShiftClient): List<Exception> {
        val errors = mutableListOf<Exception>()

        val artifactId = fields.extract("artifactId")
        val name = fields.extractOrDefault("name", artifactId)


        if (!Regex("^[a-z][-a-z0-9]{0,23}[a-z0-9]$").matches(name)) {
            errors.add(IllegalArgumentException("Name [$name] is not valid DNS952 label. 24 length alphanumeric."))
        }

        val secrets = extractSecret(fields)

        if (secrets != null && secrets.isEmpty()) {
            errors.add(IllegalArgumentException("Missing secret files"))
        }

        val permissions = extractPermissions(fields)

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


    val handlers = listOf(
            AuroraConfigFieldHandler("schemaVersion", defaultValue = "v1"),
            AuroraConfigFieldHandler("affiliation", validator = { it.pattern("^[a-z]{0,23}[a-z]$", "Affiliation is must be alphanumeric and under 24 characters") }),
            AuroraConfigFieldHandler("cluster", validator = { it.notBlank("Cluster must be set") }),
            AuroraConfigFieldHandler("type", validator = { it.required("Type is required") }),
            AuroraConfigFieldHandler("name"),
            AuroraConfigFieldHandler("envName"),
            AuroraConfigFieldHandler("flags/route", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/cert", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/debug", defaultValue = "false"),
            AuroraConfigFieldHandler("flags/alarm", defaultValue = "true"),
            AuroraConfigFieldHandler("flags/rolling", defaultValue = "false"),
            AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "0"),
            AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
            AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
            AuroraConfigFieldHandler("resources/memory/max", defaultValue = "256Mi"),
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
            AuroraConfigFieldHandler("secretFolder"),
            AuroraConfigFieldHandler("template"),
            AuroraConfigFieldHandler("templateFile")
    )

    override val fieldHandlers = handlers + allFiles.findExtractors("config")

}